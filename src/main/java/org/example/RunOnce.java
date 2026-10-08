package org.example;

/**
 * Runs an action at most once, however many callers ask and from whichever threads.
 *
 * <p>A caller that arrives while the action is running waits until it has finished and then
 * returns without running it again, so after {@link #run()} returns the action is always
 * complete. If the action throws, that is not tried again either, and only the caller that
 * ran it sees the exception.
 */
final class RunOnce {

    private final Runnable action;

    private boolean started;

    RunOnce(Runnable action) {
        this.action = action;
    }

    synchronized void run() {
        if (started) {
            return;
        }
        started = true;
        action.run();
    }
}
