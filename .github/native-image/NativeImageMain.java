import com.cdb96.ncmconverter4a.MainKt;
import java.nio.file.Path;

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

        MainKt.main();
    }
}
