import com.cdb96.ncmconverter4a.MainKt;
import com.cdb96.ncmconverter4a.NativeCaption;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import com.cdb96.ncmconverter4a.jni.KGGDecrypt;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Native Image entry point: prepare the portable AWT support directory, then start Compose. */
public final class NativeImageMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].endsWith("-smoke")
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
        configureSkikoCache();
        if (args.length == 1 && "--runtime-smoke".equals(args[0])) {
            NativeImageSmoke.verifyResources();
            verifyNativeLibrary();
            org.jetbrains.skiko.Library.INSTANCE.load();
            System.out.println("Native Image runtime smoke test passed");
            return;
        }
        if (args.length == 1 && "--file-picker-smoke".equals(args[0])) {
            NativeImageSmoke.verifyResources();
            NativeImageSmoke.verifyFilePickers();
            System.exit(0);
        }
        if (args.length == 1 && "--startup-smoke".equals(args[0])) {
            NativeImageSmoke.verifyResources();
            NativeImageSmoke.watchDesktopStartup();
        }
        if (args.length == 1 && "--trace-desktop".equals(args[0])) {
            traceDesktopMetadata();
            return;
        }
        MainKt.main();
    }

    private static void configureSkikoCache() throws Exception {
        if (!isWindows()) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = NativeImageMain.class.getResourceAsStream("/icudtl.dat")) {
            if (stream == null) throw new IllegalStateException("Missing Skiko ICU data");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        // Skiko's own cache key includes only the DLL hash. Keep our trimmed
        // ICU separate from other applications and from every previous payload.
        String base = System.getProperty("skiko.data.path");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".ncmconverter4a", "skiko").toString();
        }
        String version = "icu-" + HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        System.setProperty("skiko.data.path", Path.of(base, version).toString());
    }

    /** JVM-only metadata collection; Native Image training never starts AWT. */
    private static void traceDesktopMetadata() throws Exception {
        // Check resources even when Skiko's DLL/data are already cached.
        NativeImageSmoke.verifyResources();
        PgoTraining.run();
        NativeImageSmoke.verifyFilePickers();
        NativeImageSmoke.watchDesktopStartup();
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
        byte[] kggActual = new byte[32];
        for (int i = 0; i < kggActual.length; i++) kggActual[i] = (byte) (i * 17 + 3);
        byte[] kggExpected = java.util.HexFormat.of().parseHex(
            "c05ef3fcd73f9e28533dcbdc50bbf802d07aa065c87b68f4433e82724f4b751c");
        byte[] kggState = KGGDecrypt.prepare(new byte[0]);
        KGGDecrypt.decrypt(kggState, kggActual, 0L, kggActual.length);
        if (!Arrays.equals(kggExpected, kggActual)) {
            throw new IllegalStateException("ncmc4a KGG JNI smoke test returned an unexpected vector");
        }
        if (isWindows() && NativeCaption.INSTANCE.setColors(0L, 0, 0, false)) {
            throw new IllegalStateException("native caption JNI accepted an invalid window handle");
        }
        System.out.println("ncmc4a RC4 JNI smoke test passed");
        System.out.println("ncmc4a KGG JNI smoke test passed");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
