package org.example.usage;

import org.example.Cancel;
import org.example.Failure;
import org.example.log.Log;
import org.example.token.TokenException;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the current {@link UsageState} and keeps it fresh: it fetches at startup, then again each time the interval
 * has elapsed since the previous request was triggered, and on demand through {@link #refreshNow()}.
 *
 * <p>One thread, the loop, owns everything the service knows: the schedule, the search for the interval, which
 * request is under way. Whatever is asked of the service from outside is handed to the loop as a message and done
 * there, one after the other, so nothing needs a lock; the loop takes the next message, or waits until the next
 * request is due, whichever comes first. A request runs beside the loop, so that the loop can take a message while
 * one is under way, and its answer comes back as a message too.
 *
 * <p>Requests never overlap: the loop begins one only when none is running, and {@link #refreshNow()} declines while
 * one is running or already requested. A failed refresh keeps the last good reading and records the error. A request
 * that is no longer wanted is told so through its {@link Cancel}, by {@link #close()} and by {@link #restart}; nothing
 * is interrupted.
 *
 * <p>Timing rules live in {@link RefreshSchedule}.
 */
public final class UsageService implements AutoCloseable {

    private static final Log LOG = Log.of(UsageService.class);

    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);

    /** A server-supplied wait is believed up to this long, whatever it claims. */
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private final UsageFetch fetcher;

    private final ErrorLog errors = new ErrorLog();

    /** What is asked of the service, in the order it was asked; taken and done by the loop. */
    private final LinkedBlockingQueue<Runnable> messages = new LinkedBlockingQueue<>();

    private final Thread loop;

    /** Guards {@link #ended}, and what is done in the place of the loop once it has ended. */
    private final Object gate = new Object();

    /** Whether the loop has ended, after which a message would wait for nobody. */
    private boolean ended;

    // ---- what anybody may read at any time, replaced by the loop and never changed

    private volatile UsageState state = UsageState.initial();

    /** What the loop last said of the schedule, for the questions the window asks every second: they never wait for the loop. */
    private volatile Published published;

    /** The request under way, for {@link #close()} to wait for; {@code null} when there is none. */
    private volatile Thread request;

    /** Whether the reading before this one had no spend, so that the log warns of it once and not at every refresh. */
    private volatile boolean noSpendBefore;

    // ---- what the loop alone reads and changes

    private final RefreshSchedule schedule;

    private boolean started;

    private boolean closed;

    /** The way to tell the request under way to give up; {@code null} when there is none. */
    private Cancel current;

    /** The interval the settings give, which is in force while the program does not search for one. */
    private long configuredNanos;

    /** The search for the interval, while the automatic interval is on; {@code null} while it is off. */
    private IntervalSearch search;

    /** The clock the search is told the time of an answer by; another one only in a test, which cannot wait ten minutes. */
    private volatile java.util.function.LongSupplier searchClock = System::nanoTime;

    /** Told where the search is whenever it has moved, so that it can be saved. */
    private java.util.function.Consumer<IntervalSearch> searchListener = moved -> { };

    /** Whether the search has moved since its listener was last told. */
    private boolean searchMoved;

    /** Counts the restarts: a request that was begun before the latest one has an answer nobody wants any more. */
    private long generation;

    /** What the loop publishes of the schedule: the interval in force, whether it is searched for, and what the countdown is counted from. */
    private record Published(long intervalNanos, boolean automatic, RefreshSchedule.Countdown countdown) {
    }

    public UsageService(UsageFetch fetcher, Duration interval) {
        this.fetcher = fetcher;
        this.schedule = new RefreshSchedule(positive(interval).toNanos());
        this.configuredNanos = interval.toNanos();
        publish();
        this.loop = new Thread(this::run, "usage-refresh");
        this.loop.setDaemon(true);
        this.loop.start();
    }

    /** With a fetch that cannot be told to give up; see {@link UsageFetch#of}. */
    public UsageService(Supplier<UsageSnapshot> fetcher, Duration interval) {
        this(UsageFetch.of(fetcher), interval);
    }

    // ---- what is asked of the service

    /**
     * Shows a reading from before this run, until the first refresh has an answer. For the newest
     * reading in the history, so the window is not empty while the first request is on its way.
     * Must be called before {@link #start()}.
     */
    public void restore(UsageSnapshot earlier) {
        ask(() -> {
            if (started || closed) {
                throw new IllegalStateException("already started or closed");
            }
            state = new UsageState(earlier, null, null, false);
            return null;
        });
    }

    /**
     * Begins again for another source of usage, while the service runs: the reading on show came from the old source, so
     * it is replaced by the one given, or by none; a request under way is given up and its answer thrown away; and a
     * request to the new source begins at once.
     *
     * @param earlier the newest reading of the new source's own history, or {@code null}
     */
    public void restart(UsageSnapshot earlier) {
        ask(() -> {
            if (closed) {
                return null;
            }
            generation++;
            state = earlier == null ? UsageState.initial() : new UsageState(earlier, null, null, false);
            if (!started) {
                return null;
            }
            if (schedule.isRunning()) {
                // Tells a request that waits for the network or for the CLI; the next one is asked for when its answer is back.
                current.cancel();
            } else {
                schedule.request();
            }
            return null;
        });
    }

    /** Starts refreshing; the first request begins at once. */
    public void start() {
        ask(() -> {
            if (started || closed) {
                throw new IllegalStateException("already started or closed");
            }
            started = true;
            return null;
        });
    }

    /**
     * The time between requests the service is using, in whole seconds, rounded up: the configured interval, or the
     * interval of the search while the automatic interval is on. This is the interval the window shows.
     */
    public int effectiveIntervalSeconds() {
        long nanos = published.intervalNanos();
        return (int) Math.min(Integer.MAX_VALUE, (nanos + 999_999_999L) / 1_000_000_000L);
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
        return Duration.ofNanos(published.intervalNanos());
    }

    /**
     * Whole seconds until the next scheduled refresh, to the nearest second, and
     * negative once it is overdue. It restarts after a manual refresh and moves when
     * the interval changes.
     *
     * @return empty before the first request has been triggered
     */
    public OptionalLong secondsUntilNextRefresh() {
        OptionalLong nanos = published.countdown().nanosUntilNextRefresh(System.nanoTime());
        return nanos.isPresent() ? OptionalLong.of(toSeconds(nanos.getAsLong())) : OptionalLong.empty();
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
        boolean inForce = ask(() -> {
            configuredNanos = nanos;
            // While the program searches for the interval the configured one is kept, and waits for the search to be switched off.
            if (search != null) {
                return false;
            }
            schedule.setInterval(nanos);
            return true;
        });
        if (inForce) {
            LOG.info("Usage fetch interval is now " + interval.toSeconds() + " s");
        }
    }

    /** For a test: the clock by which the search is told when an answer came, in nanoseconds. */
    void searchClock(java.util.function.LongSupplier clock) {
        searchClock = clock;
    }

    /** Registers who is told where the search for the automatic interval is, each time it has moved; replaces any earlier listener. */
    public void onSearchMoved(java.util.function.Consumer<IntervalSearch> listener) {
        ask(() -> {
            this.searchListener = listener;
            return null;
        });
    }

    /** Whether the program is searching for the interval itself. */
    public boolean automatic() {
        return published.automatic();
    }

    /**
     * Switches the automatic interval on, with the search to go on from, or off, with {@code null}. On, the interval of the
     * search is in force; off, the configured interval is in force again.
     */
    public void setAutomatic(IntervalSearch from) {
        long seconds = ask(() -> {
            search = from;
            schedule.setInterval(from == null ? configuredNanos : Duration.ofSeconds(from.interval()).toNanos());
            return Duration.ofNanos(schedule.interval()).toSeconds();
        });
        LOG.info(from == null
                ? "Automatic interval off: the usage fetch interval is " + seconds + " s, configured"
                : "Automatic interval on: " + from.interval() + " s, calculated ("
                        + (from.refused() == null ? "no refusal yet" : "refused at " + from.refused() + " s") + ")");
    }

    /**
     * Asks for a request now rather than at the next scheduled time. Returns
     * at once, without waiting for the result.
     *
     * @return {@code false} if none was started because one is already running
     *     or already requested, or the service is not running
     */
    public boolean refreshNow() {
        return ask(() -> started && !closed && schedule.request());
    }

    // ---- the loop

    /**
     * Hands a question to the loop and waits for its answer, which is quick: the loop does nothing that takes long.
     * Asked by the loop itself, as when whoever is told of the search asks something in turn, it is answered on the
     * spot; and once the loop has ended, in its place.
     */
    private <T> T ask(Supplier<T> question) {
        if (Thread.currentThread() == loop) {
            T answer = question.get();
            publish();
            return answer;
        }
        CompletableFuture<T> answer = new CompletableFuture<>();
        synchronized (gate) {
            if (ended) {
                T now = question.get();
                publish();
                return now;
            }
            messages.add(() -> {
                try {
                    T now = question.get();
                    // Before the answer, so that whoever asked finds what follows from it when it looks.
                    publish();
                    answer.complete(now);
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                }
            });
        }
        try {
            return answer.get();
        } catch (ExecutionException e) {
            throw (RuntimeException) e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while asking the usage service");
        }
    }

    /** Hands the loop something to do without waiting for it; it is dropped if the loop has ended. */
    private void tell(Runnable message) {
        synchronized (gate) {
            if (!ended) {
                messages.add(message);
            }
        }
    }

    private void publish() {
        published = new Published(schedule.interval(), search != null, schedule.countdown());
    }

    private void run() {
        try {
            while (!closed) {
                long now = System.nanoTime();
                // Before the service is started nothing is due; a request under way is never due again before it has ended.
                long wait = started ? schedule.nanosUntilDue(now) : Long.MAX_VALUE;
                if (wait > 0) {
                    Runnable message = messages.poll(wait, TimeUnit.NANOSECONDS);
                    if (message != null) {
                        try {
                            message.run();
                        } catch (RuntimeException e) {
                            // A fault in what handles an answer, or in whoever is told of the search, must not end the refreshing.
                            LOG.warning("The usage service could not finish a step", e);
                        }
                    }
                } else {
                    begin(now);
                }
                publish();
            }
        } catch (InterruptedException e) {
            // Nothing of the program does this; whatever did wants the loop gone.
            closed = true;
        } finally {
            synchronized (gate) {
                ended = true;
            }
            // What was asked just before the end is still answered, by a service that is closed.
            for (Runnable left = messages.poll(); left != null; left = messages.poll()) {
                left.run();
            }
        }
    }

    /** Begins a request, which runs beside the loop and hands its outcome back as a message. */
    private void begin(long now) {
        schedule.begin(now);
        // What follows from a step is published before the state that tells of it: whoever sees the new state then reads
        // the interval and the countdown that belong to it, not the ones of the step before.
        publish();
        state = state.startedRefresh();
        long begun = generation;
        Cancel cancel = new Cancel();
        current = cancel;
        Thread runner = new Thread(() -> {
            Outcome outcome = refresh(cancel);
            tell(() -> finished(outcome, begun));
        }, "usage-request");
        runner.setDaemon(true);
        request = runner;
        runner.start();
    }

    /** What the loop does with the outcome of a request. */
    private void finished(Outcome answered, long begun) {
        schedule.finish();
        current = null;
        request = null;
        if (closed) {
            return;
        }
        if (begun != generation) {
            // The source was changed while this request was under way: its answer is of the old one, and the request to
            // the new source is due now.
            state = new UsageState(state.snapshot(), state.error(), state.errorAt(), false, state.rateLimited());
            schedule.request();
            return;
        }
        Outcome outcome = settleInterval(answered);
        if (outcome.error() != null) {
            // The message the window shows in its message line, once, when it appears. At the level ERROR since 0.91, where the error
            // log had it; a 429 while the program searches for the interval is part of the search, and stays at INFO.
            if (outcome.rateLimited() && search != null) {
                LOG.info("Usage refresh refused: " + outcome.error());
            } else {
                LOG.error("Usage refresh failed: " + outcome.error());
            }
            errors.add(Instant.now(), outcome.error());
        }
        // The interval and the countdown first, then the state: an answer is not to be seen with the interval of the one
        // before it, as a 429 with the interval it was refused at.
        publish();
        state = outcome.applyTo(state);
        if (searchMoved && search != null) {
            // The listener saves a file, and may ask this service something in turn, which the loop answers on the spot.
            searchMoved = false;
            searchListener.accept(IntervalSearch.of(search.interval(), search.refused()));
        }
    }

    private Outcome refresh(Cancel cancel) {
        try {
            UsageSnapshot snapshot = fetcher.fetch(cancel);
            String noSpend = snapshot.noSpendMessage();
            if (noSpend == null) {
                // At DEBUG since 0.92, as the line of the request that succeeded: the log at INFO is not a line every few seconds.
                LOG.fine("Usage refresh succeeded");
            } else {
                // The response came and holds nothing to show: said in place of the success, as a warning when it begins
                // and as information at the refreshes that follow, so that the log is not filled with warnings.
                LOG.log(noSpendBefore ? Log.Level.INFO : Log.Level.WARNING, noSpend, null);
            }
            noSpendBefore = noSpend != null;
            return Outcome.success(snapshot);
        } catch (UsageFetchException e) {
            // Logged by the loop, with the rest of the message the window shows.
            return e.status() == 429 ? Outcome.rateLimited(Failure.of(e), e.retryAfter()) : Outcome.failure(Failure.of(e));
        } catch (TokenException | UsageParseException e) {
            // These messages are written for the user and carry no credentials.
            return Outcome.failure(Failure.of(e));
        } catch (RuntimeException e) {
            LOG.error("Usage refresh failed unexpectedly", e);
            return Outcome.failure("Unexpected error; start the program with --debug to see on the console where it came from.");
        }
    }

    /**
     * Stops refreshing: a request under way is told to give up, and the loop ends. Waits up to five seconds for both
     * to be gone.
     */
    @Override
    public void close() {
        boolean stopping = ask(() -> {
            boolean wasRunning = started && !closed;
            closed = true;
            if (current != null) {
                // Tells a request that waits for the network or for the CLI.
                current.cancel();
            }
            return wasRunning;
        });
        if (!stopping || Thread.currentThread() == loop) {
            return;
        }
        Thread running = request;
        try {
            long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
            loop.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (running != null) {
                running.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            }
            if (loop.isAlive() || (running != null && running.isAlive())) {
                LOG.warning("The usage refresh thread did not stop in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Usage refresh stopped");
    }

    private static Duration positive(Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return interval;
    }

    /**
     * What an answer does to the interval, with the lock held. With the automatic interval off nothing: the delay is the
     * configured interval after a success, a 429 and any other failure, and a {@code Retry-After} is only logged. With it
     * on, a 429 moves the search up, a success down, and any other failure not at all; a longer wait the server names with a
     * 429 becomes the interval of the search, so that there is one figure for the wait, at most 300 seconds. The refresh
     * button is not held back: a person asked.
     */
    private Outcome settleInterval(Outcome outcome) {
        long interval = schedule.interval();
        if (outcome.rateLimited() && search == null) {
            long retryAfter = Math.min(outcome.retryAfter().toNanos(), MAX_RETRY_AFTER.toNanos());
            if (retryAfter > 0) {
                LOG.info("The server asked for a wait of " + seconds(retryAfter) + " s; the interval of "
                        + seconds(interval) + " s is kept");
            }
            return outcome;
        }
        if (search == null) {
            return outcome;
        }
        if (outcome.rateLimited()) {
            long retryAfter = Math.min(outcome.retryAfter().toNanos(), MAX_RETRY_AFTER.toNanos());
            // In whole seconds, rounded up: a wait of 2.5 seconds is not over after 2.
            search.rateLimited((retryAfter + 999_999_999L) / 1_000_000_000L, searchClock.getAsLong());
            long next = Duration.ofSeconds(search.interval()).toNanos();
            schedule.setInterval(next);
            LOG.info("Automatic interval: " + search.lastStep());
            searchMoved = true;
            return outcome;
        }
        if (outcome.snapshot() != null) {
            search.succeeded(searchClock.getAsLong());
            schedule.setInterval(Duration.ofSeconds(search.interval()).toNanos());
            String step = search.lastStep();
            if (step != null) {
                // A search that has settled says nothing more: nothing of it has changed.
                LOG.info("Automatic interval: " + step);
                searchMoved = true;
            }
        }
        return outcome;
    }

    /** A wait in whole seconds, rounded up and never less than 1, for the log. */
    private static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
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
