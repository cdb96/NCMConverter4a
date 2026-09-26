import com.cdb96.ncmconverter4a.MainKt;
import com.cdb96.ncmconverter4a.jni.RC4Decrypt;
import java.nio.file.Path;
import java.util.Arrays;

/** Native Image entry point: prepare the Windows AWT support directory, then start Compose. */
public final class NativeImageMain {
    public static void main(String[] args) {
        System.setProperty("skiko.renderApi", "SOFTWARE");
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

        RC4Decrypt.ksa(key);
        RC4Decrypt.prgaDecrypt(actual, actual.length);
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("ncmc4a RC4 JNI smoke test returned an unexpected vector");
        }
        System.out.println("ncmc4a RC4 JNI smoke test passed");
    }
}
