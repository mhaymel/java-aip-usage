package org.example;

/**
 * Makes the program tidy up when the process is told to stop, as well as when its window is
 * closed: a {@code SIGTERM} or {@code SIGINT}, an IDE's stop button, a logout. (A
 * {@code SIGKILL} cannot be caught by anything.)
 *
 * <p>The tidying and its log lines come first, and only then the log file is closed. They are one
 * hook, not two, because the JVM runs hooks side by side in no particular order, and a log that
 * is closed first would swallow the lines that say what happened.
 */
final class ShutdownHook {

    private ShutdownHook() {
    }

    /**
     * @param cleanup what to release; safe to call when it has already been done
     * @param logging closed last, after cleanup has logged; safe to close twice
     */
    static void install(Runnable cleanup, Logging logging) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                cleanup.run();
            } finally {
                logging.close();
            }
        }, "shutdown"));
    }
}
