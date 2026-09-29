import com.cdb96.ncmconverter4a.MainKt;
import com.cdb96.ncmconverter4a.NativeCaption;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import org.jetbrains.skiko.SkiaLayer;
import org.jetbrains.skiko.GraphicsApi;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.SwingUtilities;

/** Native Image entry point: prepare the Windows AWT support directory, then start Compose. */
public final class NativeImageMain {
    public static void main(String[] args) {
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
        boolean startupSmoke = args.length == 1 && "--startup-smoke".equals(args[0]);
        boolean rendererSwitchSmoke = args.length == 1 && "--renderer-switch-smoke".equals(args[0]);
        if (startupSmoke || rendererSwitchSmoke) {
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                boolean windowShowing = Arrays.stream(Window.getWindows()).anyMatch(Window::isShowing);
                if (!windowShowing) {
                    System.err.println("Compose desktop startup smoke test failed: no visible window");
                    System.exit(1);
                }
                boolean nativeFrame = Arrays.stream(Window.getWindows())
                    .filter(Window::isShowing)
                    .filter(window -> window instanceof Frame)
                    .anyMatch(window -> !((Frame) window).isUndecorated());
                if (!nativeFrame) {
                    System.err.println("Compose desktop startup smoke test failed: native window frame is missing");
                    System.exit(1);
                }
                boolean dropTargetInstalled = Arrays.stream(Window.getWindows())
                    .filter(Window::isShowing).anyMatch(NativeImageMain::hasDropTarget);
                if (!dropTargetInstalled) {
                    System.err.println("Compose desktop startup smoke test failed: no file drop target");
                    System.exit(1);
                }
                boolean fileDropMapped = ((SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap())
                    .getNativesForFlavor(DataFlavor.javaFileListFlavor).contains("HDROP");
                if (!fileDropMapped) {
                    System.err.println("Compose desktop startup smoke test failed: Windows file drops are not mapped");
                    System.exit(1);
                }
                SkiaLayer layer = Arrays.stream(Window.getWindows()).filter(Window::isShowing)
                    .map(NativeImageMain::findSkiaLayer).filter(candidate -> candidate != null).findFirst()
                    .orElseThrow(() -> new IllegalStateException("SkiaLayer is missing"));
                System.out.println("Skiko renderer: " + layer.getRenderApi() + " (" + layer.getRenderInfo() + ")");
                if (rendererSwitchSmoke) {
                    GraphicsApi original = layer.getRenderApi();
                    GraphicsApi other = original == GraphicsApi.SOFTWARE_FAST
                        ? GraphicsApi.DIRECT3D : GraphicsApi.SOFTWARE_FAST;
                    SwingUtilities.invokeAndWait(() -> {
                        layer.setRenderApi(other);
                        layer.needRender(true);
                    });
                    Thread.sleep(1000);
                    if (layer.getRenderApi() != other) {
                        throw new IllegalStateException("Renderer did not switch to " + other);
                    }
                    System.out.println("Skiko renderer switched to: " + layer.getRenderApi());
                    SwingUtilities.invokeAndWait(() -> {
                        layer.setRenderApi(original);
                        layer.needRender(true);
                    });
                    Thread.sleep(1000);
                    if (layer.getRenderApi() != original) {
                        throw new IllegalStateException("Renderer did not switch back to " + original);
                    }
                    System.out.println("Skiko renderer restored: " + layer.getRenderApi());
                }
                System.out.println("Compose desktop startup smoke test passed");
                System.exit(0);
                } catch (Throwable error) {
                    error.printStackTrace();
                    System.exit(1);
                }
            }, "native-startup-smoke-shutdown");
            shutdown.setDaemon(false);
            shutdown.start();
        }

        MainKt.main();
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
        if (NativeCaption.INSTANCE.setColors(0L, 0, 0, false)) {
            throw new IllegalStateException("native caption JNI accepted an invalid window handle");
        }
        System.out.println("ncmc4a RC4 JNI smoke test passed");
    }
}
