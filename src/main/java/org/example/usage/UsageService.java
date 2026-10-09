package org.example.usage;

import org.example.token.TokenException;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
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

    /** The longest the service holds back after the server says to slow down. */
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(5);

    /** A server-supplied wait is believed up to this long, whatever it claims. */
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private final Supplier<UsageSnapshot> fetcher;

    private final RefreshSchedule schedule;

    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled when the schedule changes or the service closes. */
    private final Condition changed = lock.newCondition();

    private volatile UsageState state = UsageState.initial();

    private final Backoff backoff;

    private final ErrorLog errors = new ErrorLog();

    private Thread thread;

    private boolean closed;

    public UsageService(Supplier<UsageSnapshot> fetcher, Duration interval) {
        this(fetcher, interval, DEFAULT_MAX_BACKOFF);
    }

    UsageService(Supplier<UsageSnapshot> fetcher, Duration interval, Duration maxBackoff) {
        this.fetcher = fetcher;
        this.schedule = new RefreshSchedule(positive(interval).toNanos());
        this.backoff = new Backoff(positive(maxBackoff).toNanos());
    }

    /**
     * Shows a reading from before this run, until the first refresh has an answer. For the newest
     * reading in the history, so the window is not empty while the first request is on its way.
     * Must be called before {@link #start()}.
     */
    public void restore(UsageSnapshot earlier) {
        lock.lock();
        try {
            if (thread != null || closed) {
                throw new IllegalStateException("already started or closed");
            }
            state = new UsageState(earlier, null, null, false);
        } finally {
            lock.unlock();
        }
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

    /**
     * The time between requests the service is using, in whole seconds, rounded up: the configured interval, or
     * the longer wait while it is backing off after an HTTP 429. This is the interval the window shows.
     */
    public int effectiveIntervalSeconds() {
        lock.lock();
        try {
            long nanos = Math.max(schedule.interval(), backoff.hold());
            return (int) Math.min(Integer.MAX_VALUE, (nanos + 999_999_999L) / 1_000_000_000L);
        } finally {
            lock.unlock();
        }
    }

    /** The errors of this run; in memory only. */
    public ErrorLog errors() {
        return errors;
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
     * Whole seconds until the next scheduled refresh, to the nearest second, and
     * negative once it is overdue. It restarts after a manual refresh, is longer
     * while backing off, and moves when the interval changes.
     *
     * @return empty before the first request has been triggered
     */
    public OptionalLong secondsUntilNextRefresh() {
        lock.lock();
        try {
            OptionalLong nanos = schedule.nanosUntilNextRefresh(System.nanoTime());
            return nanos.isPresent() ? OptionalLong.of(toSeconds(nanos.getAsLong())) : OptionalLong.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Rounded to the nearest second, halves upwards, so that -0.4 s is 0 and -0.6 s is -1. */
    static long toSeconds(long nanos) {
        return Math.floorDiv(nanos + 500_000_000L, 1_000_000_000L);
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
                outcome = settleBackoff(outcome);
                if (outcome.error() != null && !closed) {
                    // The message the window shows in its message line, once, when it appears.
                    LOG.log(System.Logger.Level.WARNING, "Usage refresh failed: " + outcome.error());
                    errors.add(Instant.now(), outcome.error());
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
        } catch (UsageFetchException e) {
            // Logged by run, with the rest of the message the window shows.
            return e.status() == 429 ? Outcome.rateLimited(e.getMessage(), e.retryAfter()) : Outcome.failure(e.getMessage());
        } catch (TokenException | UsageParseException e) {
            // These messages are written for the user and carry no credentials.
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

    /**
     * Decides how long to hold back after this refresh, with the lock held. See
     * {@link Backoff} for the policy. The refresh button is not held back: a person
     * asked. A failure that is not a 429 leaves the hold as it is.
     */
    private Outcome settleBackoff(Outcome outcome) {
        long interval = schedule.interval();
        if (outcome.rateLimited()) {
            long retryAfter = Math.min(outcome.retryAfter().toNanos(), MAX_RETRY_AFTER.toNanos());
            long next = Math.max(interval, backoff.rateLimited(interval, retryAfter));
            schedule.setBackoff(backoff.hold());
            LOG.log(System.Logger.Level.WARNING, "Rate limited; next try in " + describe(next));
            return outcome.withError(outcome.error() + " Next try in " + describe(next) + ".");
        }
        if (outcome.snapshot() != null && backoff.hold() > 0) {
            backoff.succeeded(interval);
            schedule.setBackoff(backoff.hold());
            LOG.log(System.Logger.Level.INFO, backoff.hold() > 0
                    ? "Easing the back-off; next try in " + describe(Math.max(interval, backoff.hold()))
                    : "The back-off is over; back to the usual interval");
        }
        return outcome;
    }

    /** "30 s", "2 min": a wait, rounded up, coarse on purpose. */
    static String describe(long nanos) {
        long seconds = Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
        return seconds < 60 ? seconds + " s" : ((seconds + 59) / 60) + " min";
    }

    private record Outcome(UsageSnapshot snapshot, String error, boolean rateLimited, Duration retryAfter) {

        static Outcome success(UsageSnapshot snapshot) {
            return new Outcome(snapshot, null, false, Duration.ZERO);
        }

        static Outcome failure(String error) {
            return new Outcome(null, error, false, Duration.ZERO);
        }

        static Outcome rateLimited(String error, Duration retryAfter) {
            return new Outcome(null, error, true, retryAfter);
        }

        Outcome withError(String error) {
            return new Outcome(snapshot, error, rateLimited, retryAfter);
        }

        UsageState applyTo(UsageState state) {
            return snapshot != null ? state.succeeded(snapshot) : state.failed(error, Instant.now(), rateLimited);
        }
    }
}
