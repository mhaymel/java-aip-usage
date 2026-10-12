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
        // The log is opened first, so that everything after it is in it.
        Logging logging = Logging.install(Path.of(args[0]));
        org.example.log.Log log = org.example.log.Log.named("ShutdownProbe");
        RunOnce cleanup = new RunOnce(() -> {
            log.info("Shutting down");
            log.info("Usage refresh stopped");
            log.info("Frontend server stopped");
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
