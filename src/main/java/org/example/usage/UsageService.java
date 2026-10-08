package org.example.usage;

import org.example.token.TokenException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Owns the current {@link UsageState} and the thread that keeps it fresh: it
 * fetches at startup, then again each time the interval has elapsed since the
 * previous request was triggered, and on demand through {@link #refreshNow()}.
 *
 * <p>Requests never overlap: they all run on one thread, and {@link
 * #refreshNow()} declines while one is running or already requested. A failed
 * refresh keeps the last good reading and records the error. {@link
 * #close()} interrupts a request in flight and waits for the thread to end.
 *
 * <p>Timing rules live in {@link RefreshSchedule}.
 */
public final class UsageService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(UsageService.class.getName());

    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    private final Supplier<UsageSnapshot> fetcher;

    private final RefreshSchedule schedule;

    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when the schedule changes or the service closes. */
    private final Condition changed = lock.newCondition();

    private volatile UsageState state = UsageState.initial();

    private Thread thread;

    private boolean closed;

    public UsageService(Supplier<UsageSnapshot> fetcher, Duration interval) {
        this.fetcher = fetcher;
        this.schedule = new RefreshSchedule(positive(interval).toNanos());
    }

    /** Starts the refresh thread; the first request begins at once. */
    public void start() {
        lock.lock();
        try {
            if (thread != null || closed) {
                throw new IllegalStateException("already started or closed");
            }
            thread = new Thread(this::run, "usage-refresh");
            thread.setDaemon(true);
            thread.start();
        } finally {
            lock.unlock();
        }
    }

    /** The latest state; cheap and safe to call as often as the UI polls. */
    public UsageState state() {
        return state;
    }

    public Duration interval() {
        lock.lock();
        try {
            return Duration.ofNanos(schedule.interval());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Changes the interval for the next request, without disturbing one in
     * flight. See {@link RefreshSchedule} for how the due time moves.
     */
    public void setInterval(Duration interval) {
        long nanos = positive(interval).toNanos();
        lock.lock();
        try {
            schedule.setInterval(nanos);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        LOG.log(System.Logger.Level.INFO, "Usage fetch interval is now " + interval.toSeconds() + " s");
    }

    /**
     * Asks for a request now rather than at the next scheduled time. Returns
     * at once, without waiting for the result.
     *
     * @return {@code false} if none was started because one is already running
     *     or already requested, or the service is not running
     */
    public boolean refreshNow() {
        lock.lock();
        try {
            if (thread == null || closed || !schedule.request()) {
                return false;
            }
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void run() {
        lock.lock();
        try {
            while (!closed) {
                long now = System.nanoTime();
                long wait = schedule.nanosUntilDue(now);
                if (wait > 0) {
                    changed.awaitNanos(wait);
                    continue;
                }
                schedule.begin(now);
                state = state.startedRefresh();
                lock.unlock();
                Outcome outcome;
                try {
                    outcome = refresh();
                } finally {
                    lock.lock();
                    schedule.finish();
                }
                if (!closed) {
                    state = outcome.applyTo(state);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private Outcome refresh() {
        try {
            UsageSnapshot snapshot = fetcher.get();
            LOG.log(System.Logger.Level.INFO, "Usage refresh succeeded");
            return Outcome.success(snapshot);
        } catch (TokenException | UsageFetchException | UsageParseException e) {
            // These messages are written for the user and carry no credentials.
            LOG.log(System.Logger.Level.WARNING, "Usage refresh failed: " + e.getMessage());
            return Outcome.failure(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Usage refresh failed unexpectedly", e);
            return Outcome.failure("Unexpected error (" + e.getClass().getSimpleName() + "); see the log.");
        }
    }

    @Override
    public void close() {
        Thread toStop;
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
            toStop = thread;
        } finally {
            lock.unlock();
        }
        if (toStop == null || toStop == Thread.currentThread()) {
            return;
        }
        // Wakes a request that is blocked on the network or on the CLI.
        toStop.interrupt();
        try {
            if (!toStop.join(SHUTDOWN_WAIT)) {
                LOG.log(System.Logger.Level.WARNING, "The usage refresh thread did not stop in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.log(System.Logger.Level.INFO, "Usage refresh stopped");
    }

    private static Duration positive(Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return interval;
    }

    private record Outcome(UsageSnapshot snapshot, String error) {

        static Outcome success(UsageSnapshot snapshot) {
            return new Outcome(snapshot, null);
        }

        static Outcome failure(String error) {
            return new Outcome(null, error);
        }

        UsageState applyTo(UsageState state) {
            return snapshot != null ? state.succeeded(snapshot) : state.failed(error, Instant.now());
        }
    }
}
