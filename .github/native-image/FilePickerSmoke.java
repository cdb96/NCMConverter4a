import com.cdb96.ncmconverter4a.DesktopFilePicker;
import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Exercises the real desktop file picker and closes its dialog automatically. */
public final class FilePickerSmoke {
    public static void run() {
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
        AtomicReference<Exception> wheelError = new AtomicReference<>();
        AWTEventListener wheelListener = event -> {
            if (event instanceof MouseWheelEvent) wheelReceived.set(true);
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(wheelListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        try {
            SwingUtilities.invokeAndWait(() -> {
                Timer sendWheel = new Timer(600, event -> {
                    try {
                        for (Window window : Window.getWindows()) {
                            if (window instanceof Dialog && window.isShowing()) {
                                Point topLeft = window.getLocationOnScreen();
                                Robot robot = new Robot(window.getGraphicsConfiguration().getDevice());
                                robot.mouseMove(topLeft.x + window.getWidth() / 2,
                                                topLeft.y + window.getHeight() / 2);
                                robot.mouseWheel(1);
                                return;
                            }
                        }
                        throw new IllegalStateException("No visible file picker dialog for wheel test");
                    } catch (Exception error) {
                        wheelError.set(error);
                    }
                });
                sendWheel.setRepeats(false);
                Timer closeDialog = new Timer(2500, event -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof Dialog && window.isShowing()) {
                            window.dispose();
                        }
                    }
                });
                closeDialog.setRepeats(false);
                sendWheel.start();
                closeDialog.start();
                try {
                    if (!DesktopFilePicker.INSTANCE.pickFiles(true, false).isEmpty()) {
                        throw new IllegalStateException("File picker smoke unexpectedly selected a file");
                    }
                } finally {
                    sendWheel.stop();
                    closeDialog.stop();
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
        if (wheelError.get() != null) throw new IllegalStateException("Mouse wheel test failed", wheelError.get());
        if (!wheelReceived.get()) throw new IllegalStateException("File picker did not receive a mouse wheel event");
        System.out.println("file picker smoke test passed");
    }
}
