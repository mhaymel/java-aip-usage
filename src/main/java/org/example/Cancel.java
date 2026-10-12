package org.example;

import java.util.ArrayList;
import java.util.List;

/**
 * A way to tell something that is under way to give up. Whoever starts a request hands one of these along with it and
 * keeps hold of it; whatever waits, for the network or for another program, registers what is to happen when it is
 * told, and stops waiting then.
 *
 * <p>This takes the place of interrupting a thread: nothing is done to the thread that waits, it is told, and it is
 * told once. A cancel that is never cancelled costs nothing.
 */
public final class Cancel {

    private final Object lock = new Object();

    private boolean cancelled;

    private final List<Runnable> told = new ArrayList<>();

    /**
     * Tells everything that registered to give up, each once, in the order they registered. Calling it again does
     * nothing.
     */
    public void cancel() {
        List<Runnable> toTell;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toTell = new ArrayList<>(told);
            told.clear();
        }
        for (Runnable action : toTell) {
            run(action);
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /**
     * Registers what is to happen when this is cancelled. If it already has been, that happens now, on this thread, so
     * that a cancel that came first is never missed.
     */
    public void onCancel(Runnable action) {
        synchronized (lock) {
            if (!cancelled) {
                told.add(action);
                return;
            }
        }
        run(action);
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // One thing that could not be told must not keep the rest from being told.
        }
    }
}
