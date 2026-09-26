import com.cdb96.ncmconverter4a.DesktopFilePicker;
import java.awt.Dialog;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
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

        try {
            SwingUtilities.invokeAndWait(() -> {
                Timer closeDialog = new Timer(2000, event -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof Dialog && window.isShowing()) {
                            window.dispose();
                        }
                    }
                });
                closeDialog.setRepeats(false);
                closeDialog.start();
                try {
                    if (!DesktopFilePicker.INSTANCE.pickFiles(true, false).isEmpty()) {
                        throw new IllegalStateException("File picker smoke unexpectedly selected a file");
                    }
                } finally {
                    closeDialog.stop();
                }
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("File picker smoke interrupted", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("File picker smoke failed", error.getCause());
        } finally {
            watchdog.interrupt();
        }
        System.out.println("file picker smoke test passed");
    }
}
