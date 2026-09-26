import com.cdb96.ncmconverter4a.MainKt;

/** Runs the real desktop entry point and exits normally so GraalVM writes tracing metadata. */
public final class TraceDesktopStartup {
    public static void main(String[] args) {
        Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "graalvm-trace-shutdown");
        shutdown.setDaemon(true);
        shutdown.start();
        MainKt.main();
    }
}
