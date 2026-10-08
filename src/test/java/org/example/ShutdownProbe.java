package org.example;

import java.nio.file.Path;

/**
 * A stand-in for the application, run in a child JVM by {@link ShutdownHookTest}: the same
 * logging and the same {@link ShutdownHook} the application uses, with a cleanup that logs the
 * lines the real one does, and no window or network.
 *
 * <p>Arguments: the log file, then {@code wait} (print "ready" and sit until told to stop) or
 * {@code exit-normally} (clean up and close the log itself, as a closed window does).
 */
public final class ShutdownProbe {

    public static void main(String[] args) throws Exception {
        // Logging.install must come first: it chooses the log manager, which the JDK fixes on first use.
        Logging logging = Logging.install(Path.of(args[0]));
        System.Logger log = System.getLogger("ShutdownProbe");
        RunOnce cleanup = new RunOnce(() -> {
            log.log(System.Logger.Level.INFO, "Shutting down");
            log.log(System.Logger.Level.INFO, "Usage refresh stopped");
            log.log(System.Logger.Level.INFO, "Frontend server stopped");
        });
        ShutdownHook.install(cleanup::run, logging);

        switch (args[1]) {
            case "wait" -> {
                System.out.println("ready");
                System.out.flush();
                Thread.sleep(120_000);
            }
            case "exit-normally" -> {
                cleanup.run();
                logging.close();
            }
            default -> throw new IllegalArgumentException(args[1]);
        }
    }

    private ShutdownProbe() {
    }
}
