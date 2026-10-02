import com.cdb96.ncmconverter4a.DesktopFilePicker;
import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Exercises the real desktop file picker and closes its dialog automatically. */
public final class FilePickerSmoke {
    public static void run() {
        var existingWindows = Arrays.asList(Window.getWindows());
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(20000);
                System.err.println("File picker smoke timed out");
                System.exit(124);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "file-picker-smoke-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        AtomicBoolean wheelReceived = new AtomicBoolean();
        AtomicBoolean keyPressed = new AtomicBoolean();
        AtomicBoolean keyReleased = new AtomicBoolean();
        AtomicBoolean inputStarted = new AtomicBoolean();
        AtomicBoolean inputFinished = new AtomicBoolean();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        AWTEventListener wheelListener = event -> {
            if (event instanceof MouseWheelEvent) wheelReceived.set(true);
            if (event instanceof KeyEvent key && key.getKeyCode() == KeyEvent.VK_F8) {
                if (key.getID() == KeyEvent.KEY_PRESSED) keyPressed.set(true);
                if (key.getID() == KeyEvent.KEY_RELEASED) keyReleased.set(true);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(wheelListener,
            AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        try {
            SwingUtilities.invokeAndWait(() -> {
                // Also exercise modality with an existing app window, rather
                // than only opening the picker in an otherwise empty process.
                Frame owner = new Frame("File picker smoke");
                owner.setSize(400, 200);
                owner.setLocationRelativeTo(null);
                owner.setVisible(true);
                Timer exercisePicker = new Timer(200, event -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof Dialog && window.isShowing()) {
                            if (wheelReceived.get() && (!windows || inputFinished.get())) {
                                window.dispose();
                                return;
                            }
                            if (windows && inputStarted.compareAndSet(false, true)) {
                                window.setAlwaysOnTop(true);
                                window.toFront();
                                Thread input = new Thread(() -> {
                                    try {
                                        Robot robot = new Robot();
                                        for (int attempt = 0; attempt < 8 &&
                                            !(keyPressed.get() && keyReleased.get()); attempt++) {
                                            robot.delay(500);
                                            // F8 has no picker action and is not
                                            // consumed by Chinese IME composition.
                                            robot.keyPress(KeyEvent.VK_F8);
                                            robot.delay(100);
                                            robot.keyRelease(KeyEvent.VK_F8);
                                        }
                                        robot.delay(200);
                                        if (!(keyPressed.get() && keyReleased.get())) {
                                            throw new IllegalStateException("Picker did not receive native keyboard input");
                                        }
                                        inputFinished.set(true);
                                    } catch (Exception error) {
                                        throw new IllegalStateException("Native picker input failed", error);
                                    }
                                }, "file-picker-smoke-input");
                                input.setDaemon(true);
                                input.start();
                            }
                            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                                new MouseWheelEvent(window, MouseEvent.MOUSE_WHEEL,
                                    System.currentTimeMillis(), 0, 0, 0, 0, false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1));
                            return;
                        }
                    }
                });
                exercisePicker.setInitialDelay(600);
                exercisePicker.start();
                try {
                    if (!DesktopFilePicker.INSTANCE.pickFiles(true, false).isEmpty()) {
                        throw new IllegalStateException("File picker smoke unexpectedly selected a file");
                    }
                    wheelReceived.set(false);
                    keyPressed.set(false);
                    keyReleased.set(false);
                    inputStarted.set(false);
                    inputFinished.set(false);
                    if (!DesktopFilePicker.INSTANCE.pickFiles(false, true).isEmpty()) {
                        throw new IllegalStateException("Database picker smoke unexpectedly selected a file");
                    }
                    wheelReceived.set(false);
                    keyPressed.set(false);
                    keyReleased.set(false);
                    inputStarted.set(false);
                    inputFinished.set(false);
                    if (DesktopFilePicker.INSTANCE.pickDirectory(System.getProperty("user.home")) != null) {
                        throw new IllegalStateException("Directory picker smoke unexpectedly selected a folder");
                    }
                } finally {
                    exercisePicker.stop();
                    for (Window window : Window.getWindows()) {
                        if (!existingWindows.contains(window)) window.dispose();
                    }
                }
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("File picker smoke interrupted", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("File picker smoke failed", error.getCause());
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(wheelListener);
            watchdog.interrupt();
        }
        if (!wheelReceived.get()) throw new IllegalStateException("File picker did not receive a mouse wheel event");
        System.out.println("file picker smoke test passed");
    }
}
