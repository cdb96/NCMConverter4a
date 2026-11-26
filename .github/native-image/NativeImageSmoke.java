import com.cdb96.ncmconverter4a.DesktopFilePicker;
import com.cdb96.ncmconverter4a.DesktopRenderingPreference;
import com.cdb96.ncmconverter4a.converter.NCMConverter;
import com.cdb96.ncmconverter4a.converter.NcmFileInfo;
import com.cdb96.ncmconverter4a.io.BinaryInput;
import com.cdb96.ncmconverter4a.io.BinaryOutput;
import com.cdb96.ncmconverter4a.jni.KGGDecrypt;
import com.cdb96.ncmconverter4a.jni.KGMDecrypt;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import org.jetbrains.skiko.SkiaLayer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.SystemFlavorMap;
import java.awt.event.MouseWheelEvent;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
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
        if (windows) {
            String icuHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource("icudtl.dat")));
            System.out.println("Skiko ICU SHA-256: " + icuHash);
        }
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
                ((Timer) event.getSource()).stop();
                exerciseMouseWheel(layer);
                // The wheel is processed asynchronously on the EDT; only a later EDT
                // callback proves the queue survived it. A dead or blocked EDT never
                // runs this, so the startup watchdog fails the check instead.
                Timer survived = new Timer(750, done -> {
                    System.out.println("Compose desktop startup smoke test passed");
                    System.exit(0);
                });
                survived.setRepeats(false);
                survived.start();
            }
        }).start());
    }

    private static void exerciseMouseWheel(SkiaLayer layer) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            // Only the Windows AWT peer resolves getWheelRotation through JNI.
            for (int rotation : new int[]{3, -3}) {
                layer.dispatchEvent(new MouseWheelEvent(layer, MouseWheelEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, layer.getWidth() / 2, layer.getHeight() / 2,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
            }
            return;
        }
        // The Windows AWT peer resolves MouseWheelEvent.getWheelRotation through JNI
        // while processing a real WM_MOUSEWHEEL; synthetic dispatchEvent never reaches
        // that native path. PostMessageW needs no focus, unlike injected input.
        long hwnd = layer.getWindowHandle();
        if (hwnd == 0) throw new IllegalStateException("SkiaLayer has no native window handle");
        var screen = layer.getLocationOnScreen();
        long lparam = ((screen.x + layer.getWidth() / 2) & 0xFFFFL)
            | (((screen.y + layer.getHeight() / 2) & 0xFFFFL) << 16);
        try (var arena = Arena.ofConfined()) {
            var post = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("user32", arena).find("PostMessageW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            for (int delta : new int[]{3 * 120, -3 * 120}) {
                int posted = (int) post.invoke(hwnd, 0x020A, (long) delta << 16, lparam);
                if (posted == 0) throw new IllegalStateException("PostMessageW WM_MOUSEWHEEL failed");
            }
        } catch (Throwable failure) {
            throw new IllegalStateException("Wheel message injection failed", failure);
        }
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

    public static void verifyConversion() {
        byte[] key = {1, 3, 5, 7, 9};
        for (String format : new String[] {"mp3", "flac", "m4a"}) {
            byte[] plain = makePayload(format);
            byte[] encrypted = plain.clone();
            RC4Decrypt.init(key);
            RC4Decrypt.decrypt(encrypted, encrypted.length);

            for (int iteration = 0; iteration < 3; iteration++) {
                int[] position = {0};
                long[] outputBytes = {0};
                BinaryInput input = (buffer, offset, length) -> {
                    if (position[0] == encrypted.length) return -1;
                    int count = Math.min(length, encrypted.length - position[0]);
                    System.arraycopy(encrypted, position[0], buffer, offset, count);
                    position[0] += count;
                    return count;
                };
                BinaryOutput output = (buffer, offset, length) -> outputBytes[0] += length;
                NCMConverter.INSTANCE.writeAudio(
                    input, output,
                    new NcmFileInfo(key, new byte[0], "Smoke Song", "Smoke Album", "Smoke Artist", format),
                    false, 256 * 1024, null);
                if (position[0] != encrypted.length || outputBytes[0] < 1024 * 1024) {
                    throw new IllegalStateException("NCM conversion smoke failed for " + format);
                }
            }
        }

        byte[] kgmPayload = new byte[1024 * 1024];
        KGMDecrypt.init(new byte[17]);
        int next = KGMDecrypt.decrypt(kgmPayload, 0, kgmPayload.length);
        if (next != kgmPayload.length) throw new IllegalStateException("KGM conversion smoke failed");
        for (int keySize : new int[] {0, 256, 768}) {
            byte[] kggKey = new byte[keySize];
            for (int i = 0; i < keySize; i++) kggKey[i] = (byte) (i * 31 + 7);
            byte[] plain = makePayload("mp3");
            byte[] encrypted = plain.clone();
            KGGDecrypt.decrypt(KGGDecrypt.prepare(kggKey), encrypted, 0L, encrypted.length);
            byte[] state = KGGDecrypt.prepare(kggKey);
            byte[] actual = new byte[plain.length];
            int bufferSize = 64 * 5120;
            for (int offset = 0; offset < encrypted.length; offset += bufferSize) {
                int length = Math.min(bufferSize, encrypted.length - offset);
                byte[] buffer = Arrays.copyOfRange(encrypted, offset, offset + length);
                KGGDecrypt.decrypt(state, buffer, offset, length);
                System.arraycopy(buffer, 0, actual, offset, length);
            }
            if (!Arrays.equals(plain, actual)) throw new IllegalStateException("KGG conversion smoke failed");
        }
        System.out.println("Conversion smoke test passed");
    }

    private static byte[] makePayload(String format) {
        byte[] audio = new byte[1024 * 1024];
        for (int index = 0; index < audio.length; index++) audio[index] = (byte) (index * 29 + 11);
        byte[] header = switch (format) {
            case "mp3" -> new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
            case "flac" -> {
                byte[] flac = new byte[4 + 4 + 34];
                System.arraycopy(new byte[] {'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34}, 0,
                    flac, 0, 8);
                yield flac;
            }
            case "m4a" -> new byte[] {
                0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0,
                0, 16, 0, 8, 'm', 'd', 'a', 't'
            };
            default -> throw new IllegalArgumentException(format);
        };
        byte[] payload = Arrays.copyOf(header, header.length + audio.length);
        System.arraycopy(audio, 0, payload, header.length, audio.length);
        return payload;
    }
}
