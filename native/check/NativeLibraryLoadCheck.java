import java.nio.file.Path;

/** Checks that the packaged native library loads without compiler runtimes on PATH. */
public final class NativeLibraryLoadCheck {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the native library path");
        }
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        System.out.println("Native library loaded");
    }
}
