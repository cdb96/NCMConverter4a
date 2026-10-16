import com.cdb96.ncmconverter4a.DesktopFilePicker;
import com.cdb96.ncmconverter4a.DesktopRenderingPreference;
import org.jetbrains.skiko.SkiaLayer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Shared by JVM metadata tracing and checks of the final Native Image archive. */
public final class NativeImageSmoke {
    public static void verifyResources() throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String core = windows ? "ncmc4a/windows-x86_64/ncmc4a.dll" : "ncmc4a/linux-x86_64/ncmc4a.so";
        String skiko = windows ? "skiko-windows-x64.dll" : "libskiko-linux-x64.so";
        resource(core);
        byte[] library = resource(skiko);
        String expected = new String(resource(skiko + ".sha256"), StandardCharsets.US_ASCII).trim();
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(library));
        if (!actual.equalsIgnoreCase(expected)) throw new IllegalStateException("Skiko checksum mismatch: " + skiko);
        if (windows) resource("icudtl.dat");
        System.out.println("Native Image resources verified");
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream stream = NativeImageSmoke.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing Native Image resource: " + name);
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) throw new IllegalStateException("Empty Native Image resource: " + name);
            return bytes;
        }
    }

    public static void verifyFilePickers() throws Exception {
        Thread watchdog = watchdog("File picker", 30000);
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger closed = new AtomicInteger();
            Timer closeDialogs = new Timer(300, event -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof Dialog && window.isShowing()) {
                        closed.incrementAndGet();
                        window.dispose();
                    }
                }
            });
            closeDialogs.start();
            try {
                if (!DesktopFilePicker.INSTANCE.pickFiles(true, false).isEmpty()
                        || !DesktopFilePicker.INSTANCE.pickFiles(false, true).isEmpty()
                        || DesktopFilePicker.INSTANCE.pickDirectory(System.getProperty("user.home")) != null) {
                    throw new IllegalStateException("File picker unexpectedly selected a file");
                }
                if (closed.get() != 3) throw new IllegalStateException("Not all three file pickers opened");
            } finally {
                closeDialogs.stop();
            }
        });
        watchdog.interrupt();
        System.out.println("File picker smoke test passed");
    }

    public static void watchDesktopStartup() {
        System.out.println("Rendering APPDATA: " + System.getenv("APPDATA"));
        System.out.println("Rendering user.home: " + System.getProperty("user.home"));
        System.out.println("GPU rendering preference: " + DesktopRenderingPreference.INSTANCE.loadGpuEnabled());
        System.out.println("Initial Skiko render API: " + System.getProperty("skiko.renderApi")
            + ", environment: " + System.getenv("SKIKO_RENDER_API"));
        // The watchdog is independent of the EDT, so a blocked event queue fails too.
        watchdog("Compose desktop startup", 30000);
        SwingUtilities.invokeLater(() -> new Timer(250, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof Frame frame) || !window.isShowing()
                        || !"NCMConverter4a".equals(frame.getTitle())) continue;
                SkiaLayer layer = findSkiaLayer(window);
                if (layer == null || layer.getWidth() == 0 || layer.getHeight() == 0) continue;
                // A visible empty window does not prove that Compose rendered a frame.
                try (var bitmap = layer.screenshot()) {
                    if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) continue;
                }
                if (frame.isUndecorated() || !hasDropTarget(window)) {
                    throw new IllegalStateException("Native frame or file drop target is missing");
                }
                var formats = ((SystemFlavorMap) SystemFlavorMap.getDefaultFlavorMap())
                    .getNativesForFlavor(DataFlavor.javaFileListFlavor);
                boolean windows = System.getProperty("os.name").startsWith("Windows");
                if (formats.isEmpty() || windows && !formats.contains("HDROP")) {
                    throw new IllegalStateException("Native file drop formats are missing");
                }
                System.out.println("Skiko renderer: " + layer.getRenderApi() + " (" + layer.getRenderInfo() + ")");
                System.out.println("Compose desktop startup smoke test passed");
                System.exit(0);
            }
        }).start());
    }

    private static Thread watchdog(String name, long timeoutMillis) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(timeoutMillis);
                System.err.println(name + " smoke test timed out");
                System.exit(124);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "native-image-smoke-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
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

    private static boolean hasDropTarget(Component component) {
        if (component.getDropTarget() != null) return true;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) if (hasDropTarget(child)) return true;
        }
        return false;
    }
}
