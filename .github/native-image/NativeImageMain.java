import com.cdb96.ncmconverter4a.MainKt;
import com.cdb96.ncmconverter4a.NativeCaption;
import com.cdb96.ncmconverter4a.DesktopFilePicker;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import java.awt.Dialog;
import java.awt.Window;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Native Image entry point: prepare the portable AWT support directory, then start Compose. */
public final class NativeImageMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && ("--native-smoke".equals(args[0])
                || "--pgo-train".equals(args[0]) || "--trace-desktop".equals(args[0]))) {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                System.err.println("Uncaught Native Image helper failure on " + thread.getName());
                error.printStackTrace();
                System.exit(1);
            });
        }
        System.setProperty("sun.java2d.d3d", "false");

        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank() || javaHome.equals("null") || javaHome.equals("(null)")) {
            String executable = ProcessHandle.current().info().command().orElse(".");
            Path executablePath = Path.of(executable).toAbsolutePath();
            Path appDirectory = executablePath.getParent();
            System.setProperty("java.home", appDirectory == null ? Path.of(".").toAbsolutePath().toString() : appDirectory.toString());
        }

        if (args.length == 1 && "--native-smoke".equals(args[0])) {
            verifyNativeLibrary();
            return;
        }
        if (args.length == 1 && "--pgo-train".equals(args[0])) {
            PgoTraining.run();
            return;
        }
        if (args.length == 1 && "--trace-desktop".equals(args[0])) {
            traceDesktopMetadata();
            return;
        }
        MainKt.main();
    }

    /** JVM-only metadata collection; Native Image training never starts AWT. */
    private static void traceDesktopMetadata() throws Exception {
        PgoTraining.run();
        SwingUtilities.invokeAndWait(() -> {
            Timer closeDialogs = new Timer(500, event -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof Dialog && window.isShowing()) window.dispose();
                }
            });
            closeDialogs.start();
            try {
                DesktopFilePicker.INSTANCE.pickFiles(true, false);
                DesktopFilePicker.INSTANCE.pickFiles(false, true);
                DesktopFilePicker.INSTANCE.pickDirectory(System.getProperty("user.home"));
            } finally {
                closeDialogs.stop();
            }
        });
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            System.out.println("Desktop metadata tracing finished");
            System.exit(0);
        }, "desktop-metadata-exit");
        exit.setDaemon(true);
        exit.start();
        MainKt.main();
    }

    private static void verifyNativeLibrary() {
        byte[] key = {1, 2, 3, 4, 5, 6, 7};
        byte[] actual = new byte[32];
        for (int i = 0; i < actual.length; i++) actual[i] = (byte) i;

        byte[] expected = {
            0x6D, (byte) 0x84, (byte) 0xE4, 0x70, (byte) 0x92, 0x1D,
            (byte) 0xEE, (byte) 0x82, (byte) 0xA1, 0x5E, 0x4D, 0x6C,
            (byte) 0xB4, (byte) 0xD0, 0x0E, 0x5A, 0x0B, 0x00,
            (byte) 0xC2, (byte) 0xC0, (byte) 0xF1, (byte) 0xFE, 0x2D,
            (byte) 0xE9, (byte) 0xB4, (byte) 0x98, (byte) 0xA1, (byte) 0x8F,
            (byte) 0x87, 0x42, (byte) 0x9F, (byte) 0x82
        };

        RC4Decrypt.init(key);
        RC4Decrypt.decrypt(actual, actual.length);
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("ncmc4a RC4 JNI smoke test returned an unexpected vector");
        }
        if (isWindows() && NativeCaption.INSTANCE.setColors(0L, 0, 0, false)) {
            throw new IllegalStateException("native caption JNI accepted an invalid window handle");
        }
        System.out.println("ncmc4a RC4 JNI smoke test passed");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
