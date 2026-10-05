import com.cdb96.ncmconverter4a.MainKt;
import com.cdb96.ncmconverter4a.NativeCaption;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import org.jetbrains.skiko.GraphicsApi;
import org.jetbrains.skiko.SkiaLayer;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Native Image entry point: prepare the portable AWT support directory, then start Compose. */
public final class NativeImageMain {
    public static void main(String[] args) {
        // AWT and coroutine failures can leave the main thread alive. Smoke
        // runs must fail on background exceptions instead of reporting success.
        if (args.length == 1 && (args[0].endsWith("-smoke") || "--pgo-train".equals(args[0]))) {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                System.err.println("Uncaught smoke failure on " + thread.getName());
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
        if (args.length == 1 && "--file-picker-smoke".equals(args[0])) {
            FilePickerSmoke.run();
            return;
        }
        boolean pgoTraining = args.length == 1 && "--pgo-train".equals(args[0]);
        if (pgoTraining) {
            PgoTraining.run();
            FilePickerSmoke.run();
        }
        boolean startupSmoke = pgoTraining || args.length == 1 && "--startup-smoke".equals(args[0]);
        if (startupSmoke) {
            startStartupSmoke();
        }

        if (args.length == 1 && "--file-picker-app-smoke".equals(args[0])) {
            Thread picker = new Thread(() -> {
                try {
                    for (int attempt = 0; attempt < 200; attempt++) {
                        boolean appShowing = Arrays.stream(Window.getWindows())
                            .anyMatch(window -> window.isShowing() && window instanceof Frame frame
                                && "NCMConverter4a".equals(frame.getTitle()));
                        if (appShowing) {
                            FilePickerSmoke.run();
                            if (Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing)) {
                                throw new IllegalStateException("App window closed after file picker smoke");
                            }
                            System.out.println("Compose file picker smoke test passed");
                            System.exit(0);
                        }
                        Thread.sleep(100);
                    }
                    throw new IllegalStateException("App window did not appear for file picker smoke");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("App file picker smoke interrupted", error);
                }
            }, "app-file-picker-smoke");
            picker.start();
        }
        MainKt.main();
    }

    /** Keep the timeout independent of the EDT so a blocked event queue still fails. */
    static void startStartupSmoke() {
        long timeoutMillis = Long.getLong("ncm.startupSmokeTimeoutMillis", 60000L);
        long started = System.nanoTime();
        AtomicReference<String> pending = new AtomicReference<>("AWT event queue has not processed the startup check");
        AtomicBoolean queued = new AtomicBoolean();
        AtomicLong readySince = new AtomicLong();
        Runnable check = () -> {
            try {
                String issue = startupIssue();
                if (issue != null) {
                    readySince.set(0);
                    pending.set(issue);
                } else {
                    pending.set("waiting for initial rendering and profiling");
                    // Let initial paint and tracing/PGO work finish before exiting.
                    readySince.compareAndSet(0, System.nanoTime());
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readySince.get()) >= 2000) {
                        SkiaLayer layer = findSkiaLayer(appFrame());
                        System.out.println("Skiko renderer: " + layer.getRenderApi() + " (" + layer.getRenderInfo() + ")");
                        System.out.println("Compose desktop startup smoke test passed");
                        System.exit(0);
                    }
                }
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            } finally {
                queued.set(false);
            }
        };
        Thread watchdog = new Thread(() -> {
            String reported = null;
            while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < timeoutMillis) {
                if (queued.compareAndSet(false, true)) {
                    EventQueue.invokeLater(check);
                }
                String issue = pending.get();
                if (!issue.equals(reported)) {
                    System.out.println("Waiting for Compose startup: " + issue);
                    reported = issue;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.err.println("Compose desktop startup smoke test failed: timed out after "
                + timeoutMillis + " ms: " + pending.get());
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                System.err.println("Thread " + thread.getName() + " (" + thread.getState() + ")");
                for (StackTraceElement frame : stack) System.err.println("    at " + frame);
            });
            System.exit(1);
        }, "native-startup-smoke-watchdog");
        watchdog.setDaemon(false);
        watchdog.start();
    }

    private static Frame appFrame() {
        return Arrays.stream(Window.getWindows())
            .filter(window -> window instanceof Frame frame && window.isShowing()
                && "NCMConverter4a".equals(frame.getTitle()))
            .map(window -> (Frame) window).findFirst().orElse(null);
    }

    /** All component and renderer checks run on the EDT. */
    private static String startupIssue() {
        Frame frame = appFrame();
        if (frame == null) return "no visible app window";
        if (frame.isUndecorated()) return "native window frame is missing";
        if (!hasDropTarget(frame)) return "no file drop target";
        var fileDropFormats = ((SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap())
            .getNativesForFlavor(DataFlavor.javaFileListFlavor);
        if (isWindows() ? !fileDropFormats.contains("HDROP") : fileDropFormats.isEmpty()) {
            return "file drops are not mapped";
        }
        SkiaLayer layer = findSkiaLayer(frame);
        if (layer == null || !layer.isShowing() || layer.getWidth() <= 0 || layer.getHeight() <= 0) {
            return "SkiaLayer is not ready";
        }
        if (layer.getRenderApi() == GraphicsApi.UNKNOWN) return "Skiko renderer is not initialized";
        return null;
    }

    private static boolean hasDropTarget(Component component) {
        if (component.getDropTarget() != null) return true;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasDropTarget(child)) return true;
            }
        }
        return false;
    }

    private static SkiaLayer findSkiaLayer(Component component) {
        if (component instanceof SkiaLayer layer) return layer;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SkiaLayer layer = findSkiaLayer(child);
                if (layer != null) return layer;
            }
        }
        return null;
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

        long context = RC4Decrypt.create(key);
        try {
            RC4Decrypt.decrypt(context, actual, actual.length);
        } finally {
            RC4Decrypt.destroy(context);
        }
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
