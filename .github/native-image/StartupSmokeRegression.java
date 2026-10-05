import com.cdb96.ncmconverter4a.MainKt;
import java.awt.EventQueue;
import java.util.concurrent.CountDownLatch;

/** Exercises slow startup and an unresponsive EDT in separate JVMs. */
public final class StartupSmokeRegression {
    public static void main(String[] args) throws InterruptedException {
        System.setProperty("sun.java2d.d3d", "false");
        switch (args[0]) {
            case "delayed-startup" -> {
                NativeImageMain.startStartupSmoke();
                // The former one-shot check failed before the app was launched.
                Thread.sleep(17000);
                MainKt.main();
            }
            case "blocked-event-queue" -> {
                System.setProperty("ncm.startupSmokeTimeoutMillis", "1000");
                EventQueue.invokeLater(() -> {
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
                NativeImageMain.startStartupSmoke();
                new CountDownLatch(1).await();
            }
            default -> throw new IllegalArgumentException("Unknown regression scenario: " + args[0]);
        }
    }
}
