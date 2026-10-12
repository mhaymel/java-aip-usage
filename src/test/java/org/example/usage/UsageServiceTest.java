package org.example.usage;

import org.example.token.TokenException;
import org.example.token.TokenException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real refresh thread with doubles for the network; waits are polled, never slept blindly. */
class UsageServiceTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private final List<UsageService> started = new ArrayList<>();

    @AfterEach
    void closeServices() {
        started.forEach(UsageService::close);
    }

    private static UsageSnapshot snapshot(int n) {
        return new UsageSnapshot(Instant.ofEpochSecond(n), null);
    }

    private UsageService start(Supplier<UsageSnapshot> fetcher, Duration interval) {
        UsageService service = new UsageService(fetcher, interval);
        started.add(service);
        service.start();
        return service;
    }

    /** A fetcher whose calls can be held open until released. */
    private static final class Gate implements Supplier<UsageSnapshot> {

        final AtomicInteger calls = new AtomicInteger();

        final AtomicInteger inFlight = new AtomicInteger();

        final AtomicInteger maxInFlight = new AtomicInteger();

        final AtomicBoolean interrupted = new AtomicBoolean();

        volatile Thread fetchThread;

        /** Counted down by a call that has started. */
        volatile CountDownLatch entered = new CountDownLatch(1);

        /** Calls wait on this while it is not null. */
        volatile CountDownLatch hold;

        @Override
        public UsageSnapshot get() {
            int n = calls.incrementAndGet();
            fetchThread = Thread.currentThread();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                CountDownLatch latch = hold;
                if (latch != null) {
                    latch.await();
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new UsageFetchException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
            return snapshot(n);
        }

        /** Makes the next call wait, and returns the latch that frees it. */
        CountDownLatch holdNextCall() {
            hold = new CountDownLatch(1);
            entered = new CountDownLatch(1);
            return hold;
        }
    }

    // ---- beginning again for another source

    @Test
    void aRestartShowsTheReadingItIsGivenAndFetchesAtOnce() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null);
        CountDownLatch held = fetcher.holdNextCall();

        service.restart(snapshot(99));

        assertEquals(snapshot(99), service.state().snapshot(), "the reading of the new source's own history, at once");
        await(() -> fetcher.calls.get() == 2);
        held.countDown();
        await(() -> snapshot(2).equals(service.state().snapshot()));
        assertNull(service.state().error());
    }

    @Test
    void aRestartWithNoReadingBeginsWithNone() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null);
        fetcher.holdNextCall();

        service.restart(null);

        assertNull(service.state().snapshot());
        assertNull(service.state().error());
    }

    @Test
    void aRequestUnderWayAtARestartIsGivenUpAndItsAnswerThrownAway() {
        Gate fetcher = new Gate();
        fetcher.holdNextCall();
        UsageService service = start(fetcher, HOUR);
        await(() -> fetcher.inFlight.get() == 1);

        // The request to the old source is blocked. The restart interrupts it, and the next request is not held.
        fetcher.hold = null;
        service.restart(null);

        await(() -> fetcher.interrupted.get());
        await(() -> fetcher.calls.get() == 2);
        await(() -> snapshot(2).equals(service.state().snapshot()));
        assertNull(service.state().error(), "the request that was given up is not a failed refresh");
        assertTrue(service.errors().newestFirst().isEmpty(), "and is not in the error log");
        assertEquals(1, fetcher.maxInFlight.get(), "never two requests at once");
    }

    @Test
    void theServiceGoesOnRefreshingAfterARestart() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> fetcher.calls.get() == 1);

        service.restart(null);
        // The refresh the restart began has ended: one that is still under way would have the next one refused.
        await(() -> fetcher.calls.get() == 2 && !service.state().refreshing());
        assertTrue(service.refreshNow());

        await(() -> fetcher.calls.get() == 3);
    }

    @Test
    void fetchesImmediatelyAtStartAndPublishesTheReading() {
        Gate fetcher = new Gate();

        UsageService service = start(fetcher, HOUR);

        await(() -> service.state().snapshot() != null);
        UsageState state = service.state();
        assertEquals(snapshot(1), state.snapshot());
        assertNull(state.error());
        assertFalse(state.stale());
        assertEquals(1, fetcher.calls.get());
        await(() -> !service.state().refreshing());
    }

    @Test
    void showsNothingBeforeTheFirstReadingArrives() {
        Gate fetcher = new Gate();
        CountDownLatch release = fetcher.holdNextCall();

        UsageService service = start(fetcher, HOUR);
        awaitEntered(fetcher);

        UsageState state = service.state();
        assertNull(state.snapshot());
        assertNull(state.error());
        assertTrue(state.refreshing());
        release.countDown();
    }

    @Test
    void fetchesAgainWhenTheIntervalElapses() {
        Gate fetcher = new Gate();

        start(fetcher, Duration.ofMillis(40));

        await(() -> fetcher.calls.get() >= 3);
    }

    @Test
    void aFailedRefreshKeepsTheLastReadingMarksItStaleAndShowsTheError() {
        AtomicInteger calls = new AtomicInteger();
        UsageService service = start(() -> {
            int n = calls.incrementAndGet();
            if (n == 2) {
                throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
            }
            return snapshot(n);
        }, Duration.ofMillis(60));

        await(() -> service.state().error() != null);
        UsageState failed = service.state();
        assertEquals(snapshot(1), failed.snapshot());
        assertEquals("Anthropic returned HTTP 503.", failed.error());
        assertNotNull(failed.errorAt());
        assertTrue(failed.stale());

        await(() -> service.state().error() == null);
        UsageState recovered = service.state();
        assertEquals(snapshot(3), recovered.snapshot());
        assertFalse(recovered.stale());
        assertNull(recovered.errorAt());
    }

    @Test
    void aFailureBeforeAnyReadingShowsTheErrorAndNoStaleData() {
        UsageService service = start(() -> {
            throw new TokenException(Reason.NOT_LOGGED_IN, "Log in with Claude Code.");
        }, HOUR);

        await(() -> service.state().error() != null);

        assertEquals("Log in with Claude Code.", service.state().error());
        assertNull(service.state().snapshot());
        assertFalse(service.state().stale());
    }

    @Test
    void anUnparseableResponseIsShownAsAnErrorToo() {
        UsageService service = start(() -> {
            throw new UsageParseException("The usage response is not a JSON object.");
        }, HOUR);

        await(() -> service.state().error() != null);

        assertEquals("The usage response is not a JSON object.", service.state().error());
    }

    @Test
    void anUnexpectedExceptionIsShownGenericallyAndDoesNotStopRefreshing() {
        AtomicInteger calls = new AtomicInteger();
        UsageService service = start(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("internal detail that must not reach the UI");
            }
            return snapshot(2);
        }, Duration.ofMillis(40));

        await(() -> service.state().error() != null || service.state().snapshot() != null);
        // Whichever the UI sees first, the detail is never in it.
        UsageState first = service.state();
        if (first.error() != null) {
            assertTrue(first.error().startsWith("Unexpected error"), first.error());
            assertFalse(first.error().contains("internal detail"), first.error());
        }

        await(() -> service.state().snapshot() != null && service.state().error() == null);
    }

    @Test
    void refreshNowFetchesAtOnceWithoutWaitingForTheSchedule() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null && !service.state().refreshing());

        assertTrue(service.refreshNow());

        await(() -> fetcher.calls.get() == 2);
    }

    @Test
    void refreshNowReturnsWithoutWaitingForTheFetchToFinish() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null && !service.state().refreshing());
        CountDownLatch release = fetcher.holdNextCall();

        assertTrue(service.refreshNow());
        awaitEntered(fetcher);

        // The fetch is blocked, yet refreshNow has already returned.
        assertEquals(2, fetcher.calls.get());
        release.countDown();
    }

    @Test
    void refreshNowDeclinesWhileAFetchIsRunning() {
        Gate fetcher = new Gate();
        CountDownLatch release = fetcher.holdNextCall();
        UsageService service = start(fetcher, HOUR);
        awaitEntered(fetcher);

        assertFalse(service.refreshNow());
        assertFalse(service.refreshNow());
        assertFalse(service.refreshNow());
        release.countDown();

        await(() -> !service.state().refreshing() && service.state().snapshot() != null);
        assertEquals(1, fetcher.calls.get());
        assertTrue(service.refreshNow());
    }

    @Test
    void repeatedRefreshNowCallsStartOnlyOneFetch() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null && !service.state().refreshing());
        CountDownLatch release = fetcher.holdNextCall();

        int accepted = 0;
        for (int i = 0; i < 20; i++) {
            if (service.refreshNow()) {
                accepted++;
            }
        }
        awaitEntered(fetcher);
        release.countDown();
        await(() -> !service.state().refreshing());

        assertEquals(1, accepted);
        assertEquals(2, fetcher.calls.get());
    }

    @Test
    void scheduledAndManualRefreshesNeverOverlap() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        UsageService service = start(() -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return snapshot(calls.incrementAndGet());
        }, Duration.ofMillis(5));

        List<Thread> clickers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread clicker = new Thread(() -> {
                long end = System.nanoTime() + Duration.ofMillis(400).toNanos();
                while (System.nanoTime() < end) {
                    service.refreshNow();
                }
            });
            clickers.add(clicker);
            clicker.start();
        }
        for (Thread clicker : clickers) {
            clicker.join();
        }

        assertEquals(1, maxInFlight.get());
        assertTrue(calls.get() > 3, "calls: " + calls.get());
    }

    @Test
    void changingTheIntervalDuringAFetchNeitherCancelsItNorStartsAnother() throws Exception {
        Gate fetcher = new Gate();
        CountDownLatch release = fetcher.holdNextCall();
        UsageService service = start(fetcher, HOUR);
        awaitEntered(fetcher);

        // Shorter than the hour, but far longer than the fetch takes: not yet elapsed when it ends.
        service.setInterval(Duration.ofMinutes(1));
        Thread.sleep(300);

        assertEquals(1, fetcher.calls.get(), "no extra fetch while one is running");
        assertFalse(fetcher.interrupted.get(), "the running fetch must not be cancelled");
        release.countDown();
        await(() -> service.state().snapshot() != null);
        assertEquals(snapshot(1), service.state().snapshot());
        Thread.sleep(200);
        assertEquals(1, fetcher.calls.get(), "changing the interval is not a reason to fetch again");
    }

    @Test
    void anIntervalShortenedDuringAFetchIsAlreadyDueSoTheNextFetchFollowsAtOnce() throws Exception {
        Gate fetcher = new Gate();
        CountDownLatch release = fetcher.holdNextCall();
        UsageService service = start(fetcher, HOUR);
        awaitEntered(fetcher);
        service.setInterval(Duration.ofMillis(50));
        Thread.sleep(150);

        release.countDown();

        await(() -> fetcher.calls.get() >= 2);
        assertEquals(1, fetcher.maxInFlight.get());
    }

    @Test
    void anIntervalChangeWhileIdleDoesNotFetchUnlessTheNewIntervalHasElapsed() throws Exception {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null && !service.state().refreshing());

        service.setInterval(Duration.ofMinutes(30));
        Thread.sleep(300);

        assertEquals(1, fetcher.calls.get());
    }

    @Test
    void anIntervalShortenedBelowTheTimeAlreadyWaitedFetchesPromptly() throws Exception {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> service.state().snapshot() != null && !service.state().refreshing());
        Thread.sleep(150);

        service.setInterval(Duration.ofMillis(30));

        await(() -> fetcher.calls.get() >= 2);
    }

    @Test
    void anIntervalLengthenedWhileWaitingPostponesTheNextFetch() throws Exception {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, Duration.ofMillis(500));
        await(() -> service.state().snapshot() != null && !service.state().refreshing());

        service.setInterval(HOUR);
        Thread.sleep(900);

        assertEquals(1, fetcher.calls.get());
    }

    // ---- the countdown to the next refresh

    @Test
    void thereIsNoCountdownUntilTheFirstRequestIsTriggered() {
        UsageService notStarted = new UsageService(new Gate(), Duration.ofSeconds(60));

        assertEquals(java.util.OptionalLong.empty(), notStarted.secondsUntilNextRefresh());
    }

    @Test
    void justAfterTheFirstRequestTheWholeIntervalIsLeft() {
        UsageService service = start(new Gate(), Duration.ofSeconds(60));
        await(() -> service.state().snapshot() != null);

        long left = service.secondsUntilNextRefresh().getAsLong();

        assertTrue(left >= 58 && left <= 60, "left: " + left);
    }

    @Test
    void theCountdownRunsDownAsTimePasses() {
        UsageService service = start(new Gate(), Duration.ofSeconds(60));
        await(() -> service.state().snapshot() != null);

        // 60 s at the start; two seconds on it reads 58 or less.
        await(() -> service.secondsUntilNextRefresh().getAsLong() <= 58);
    }

    @Test
    void aManualRefreshRestartsTheCountdown() {
        UsageService service = start(new Gate(), Duration.ofSeconds(60));
        await(() -> service.state().snapshot() != null);
        await(() -> service.secondsUntilNextRefresh().getAsLong() <= 58);

        assertTrue(service.refreshNow());
        await(() -> service.secondsUntilNextRefresh().getAsLong() >= 59);

        assertTrue(service.secondsUntilNextRefresh().getAsLong() <= 60);
    }

    @Test
    void theCountdownGoesNegativeWhenTheRefreshIsOverdue() {
        Gate fetcher = new Gate();
        CountDownLatch release = fetcher.holdNextCall();
        // A 200 ms interval, and a request that does not finish: overdue within a second.
        UsageService service = start(fetcher, Duration.ofMillis(200));
        awaitEntered(fetcher);

        await(() -> service.secondsUntilNextRefresh().getAsLong() <= -1);

        assertTrue(service.state().refreshing(), "the request is still running");
        release.countDown();
    }

    @Test
    void changingTheIntervalMovesTheCountdown() {
        UsageService service = start(new Gate(), Duration.ofSeconds(60));
        await(() -> service.state().snapshot() != null);

        service.setInterval(Duration.ofSeconds(30));

        long left = service.secondsUntilNextRefresh().getAsLong();
        assertTrue(left >= 28 && left <= 30, "left: " + left);
    }

    @Test
    void secondsAreRoundedToTheNearestWithHalvesGoingUp() {
        assertEquals(0, UsageService.toSeconds(0));
        assertEquals(0, UsageService.toSeconds(499_999_999L));
        assertEquals(1, UsageService.toSeconds(500_000_000L));
        assertEquals(1, UsageService.toSeconds(1_499_999_999L));
        assertEquals(60, UsageService.toSeconds(59_600_000_000L));
        assertEquals(0, UsageService.toSeconds(-400_000_000L));
        assertEquals(0, UsageService.toSeconds(-500_000_000L));
        assertEquals(-1, UsageService.toSeconds(-600_000_000L));
        assertEquals(-3, UsageService.toSeconds(-3_000_000_000L));
        assertEquals(-3, UsageService.toSeconds(-3_400_000_000L));
    }

    // ---- HTTP 429 with the automatic interval off: nothing changes of the delay

    private static UsageFetchException rateLimited() {
        return new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429);
    }

    /** A fetcher that answers from a script, one entry per call, and records when each call happened. */
    private static final class Scripted implements Supplier<UsageSnapshot> {

        private final java.util.List<Boolean> failWith429;

        final java.util.List<Long> callTimesMillis = new CopyOnWriteArrayList<>();

        Scripted(Boolean... failWith429) {
            this.failWith429 = java.util.List.of(failWith429);
        }

        @Override
        public UsageSnapshot get() {
            int n = callTimesMillis.size();
            callTimesMillis.add(System.nanoTime() / 1_000_000);
            if (n < failWith429.size() && failWith429.get(n)) {
                throw rateLimited();
            }
            return snapshot(n);
        }

        /** The wait before call {@code n}, in milliseconds. */
        long gapBefore(int n) {
            return callTimesMillis.get(n) - callTimesMillis.get(n - 1);
        }
    }

    @Test
    void aRateLimitedRefreshIsMarkedAndDoesNotMakeTheReadingStale() {
        Scripted fetch = new Scripted(false, true);

        UsageService service = start(fetch, Duration.ofMillis(30));
        await(() -> service.state().rateLimited());

        UsageState state = service.state();
        assertNotNull(state.snapshot(), "the last good reading stays");
        assertNotNull(state.error());
        assertFalse(state.stale(), "a request to slow down does not make the data old");
    }

    @Test
    void anotherFailureIsStaleAndNotMarkedAsRateLimited() {
        Gate fetch = new Gate();
        UsageService service = start(() -> {
            if (fetch.calls.incrementAndGet() > 1) {
                throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
            }
            return snapshot(1);
        }, Duration.ofMillis(30));
        await(() -> service.state().error() != null);

        assertTrue(service.state().stale());
        assertFalse(service.state().rateLimited());
    }

    @Test
    void theNextSuccessClearsTheRateLimit() {
        Scripted fetch = new Scripted(true, false);

        UsageService service = start(fetch, Duration.ofMillis(30));
        await(() -> service.state().rateLimited());
        await(() -> service.state().error() == null && service.state().snapshot() != null);

        assertFalse(service.state().rateLimited());
    }

    @Test
    void everyFailedRefreshIsAnEntryOfTheErrorLogAndASuccessIsNone() {
        Scripted fetch = new Scripted(false, true, true);

        UsageService service = start(fetch, Duration.ofMillis(30));
        await(() -> service.errors().newestFirst().size() >= 2);

        List<ErrorLog.Entry> entries = service.errors().newestFirst();
        assertTrue(entries.get(0).message().contains("HTTP 429"), entries.get(0).message());
        assertTrue(entries.get(0).message().contains("Next try in"), "the message the row would have shown, wait included");
        assertTrue(entries.get(0).at().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        assertEquals(2, entries.size(), "the first call succeeded and added none");
    }

    @Test
    void aProblemWithTheTokenIsAnEntryToo() {
        UsageService service = start(() -> {
            throw new TokenException(Reason.NOT_LOGGED_IN, "Claude Code is not logged in. Log in, then refresh.");
        }, HOUR);
        await(() -> !service.errors().newestFirst().isEmpty());

        assertTrue(service.errors().newestFirst().get(0).message().contains("not logged in"));
    }

    @Test
    void theEffectiveIntervalIsTheConfiguredOne() {
        UsageService service = new UsageService(() -> snapshot(1), Duration.ofSeconds(60));

        assertEquals(60, service.effectiveIntervalSeconds());
        service.setInterval(Duration.ofMillis(60_500));
        assertEquals(61, service.effectiveIntervalSeconds(), "rounded up to whole seconds");
    }

    @Test
    void aRateLimitedRefreshIsFollowedAfterExactlyTheIntervalEveryTime() {
        Scripted fetch = new Scripted(true, true, true, true);

        UsageService service = start(fetch, Duration.ofMillis(60));
        await(() -> fetch.callTimesMillis.size() >= 4);

        for (int n = 1; n < 4; n++) {
            assertTrue(fetch.gapBefore(n) >= 55 && fetch.gapBefore(n) < 150, "gap " + n + ": " + fetch.gapBefore(n));
        }
        assertEquals(1, service.effectiveIntervalSeconds(), "and the interval in force is the configured one, never a longer wait");
    }

    @Test
    void aRetryAfterOfTheServerChangesNothingWithTheAutomaticIntervalOff() {
        AtomicInteger calls = new AtomicInteger();
        java.util.List<Long> times = new CopyOnWriteArrayList<>();
        start(() -> {
            times.add(System.nanoTime() / 1_000_000);
            if (calls.getAndIncrement() == 0) {
                throw new UsageFetchException("slow down", 429, Duration.ofSeconds(30));
            }
            return snapshot(1);
        }, Duration.ofMillis(60));

        await(() -> times.size() >= 2);

        assertTrue(times.get(1) - times.get(0) < 500, "waited " + (times.get(1) - times.get(0)));
    }

    @Test
    void aSuccessAfterARateLimitClearsTheErrorAsUsual() {
        Scripted fetch = new Scripted(true);
        UsageService service = start(fetch, Duration.ofMillis(50));

        await(() -> service.state().snapshot() != null);

        assertNull(service.state().error());
        assertFalse(service.state().stale());
    }

    @Test
    void theCountdownIsCountedToTheConfiguredIntervalAfterARateLimit() {
        UsageService service = start(() -> {
            throw rateLimited();
        }, Duration.ofSeconds(60));
        await(() -> service.state().error() != null);

        long left = service.secondsUntilNextRefresh().getAsLong();

        assertTrue(left >= 58 && left <= 60, "left: " + left);
    }

    @Test
    void theErrorSaysWhenTheNextTryIs() {
        UsageService service = start(() -> {
            throw rateLimited();
        }, Duration.ofSeconds(30));

        await(() -> service.state().error() != null);

        assertEquals("Anthropic is rate limiting usage requests (HTTP 429). Next try in 30 s.", service.state().error());
    }

    @Test
    void otherFailuresAreNotHeldBack() {
        java.util.List<Long> times = new CopyOnWriteArrayList<>();
        start(() -> {
            times.add(System.nanoTime() / 1_000_000);
            throw new UsageFetchException("Anthropic returned HTTP 503.", 503);
        }, Duration.ofMillis(50));

        await(() -> times.size() >= 5);

        // Five tries at a 50 ms interval take about 200 ms; doubling would have taken over 700 ms.
        assertTrue(times.get(4) - times.get(0) < 600, "took " + (times.get(4) - times.get(0)));
    }

    @Test
    void describesAWaitInSecondsThenMinutes() {
        assertEquals("1 s", UsageService.describe(1));
        assertEquals("1 s", UsageService.describe(0));
        assertEquals("30 s", UsageService.describe(30_000_000_000L));
        assertEquals("59 s", UsageService.describe(59_000_000_000L));
        assertEquals("1 min", UsageService.describe(60_000_000_000L));
        assertEquals("2 min", UsageService.describe(61_000_000_000L));
        assertEquals("5 min", UsageService.describe(300_000_000_000L));
    }

    @Test
    void reportsTheCurrentInterval() {
        UsageService service = start(new Gate(), Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(30), service.interval());
        service.setInterval(Duration.ofSeconds(45));
        assertEquals(Duration.ofSeconds(45), service.interval());
    }

    @Test
    void closeStopsARunningFetchAndTheThread() {
        Gate fetcher = new Gate();
        fetcher.holdNextCall();
        UsageService service = start(fetcher, HOUR);
        awaitEntered(fetcher);
        Thread refreshThread = fetcher.fetchThread;

        service.close();

        assertTrue(fetcher.interrupted.get(), "the running fetch is interrupted");
        assertFalse(refreshThread.isAlive(), "the refresh thread has ended");
    }

    @Test
    void closeStopsFurtherFetches() throws Exception {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, Duration.ofMillis(20));
        await(() -> fetcher.calls.get() >= 2);

        service.close();
        int callsAtClose = fetcher.calls.get();
        Thread.sleep(200);

        assertEquals(callsAtClose, fetcher.calls.get());
        assertFalse(service.refreshNow());
    }

    @Test
    void closeIsIdempotentAndWorksBeforeStart() {
        UsageService neverStarted = new UsageService(new Gate(), HOUR);
        neverStarted.close();
        neverStarted.close();

        UsageService service = start(new Gate(), HOUR);
        service.close();
        service.close();
    }

    @Test
    void cannotBeStartedTwiceOrAfterClose() {
        UsageService service = start(new Gate(), HOUR);
        assertThrows(IllegalStateException.class, service::start);

        service.close();
        assertThrows(IllegalStateException.class, service::start);
    }

    @Test
    void refreshNowIsDeclinedBeforeStart() {
        assertFalse(new UsageService(new Gate(), HOUR).refreshNow());
    }

    @Test
    void rejectsANonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new UsageService(new Gate(), Duration.ZERO));
        UsageService service = start(new Gate(), HOUR);
        assertThrows(IllegalArgumentException.class, () -> service.setInterval(Duration.ofSeconds(-1)));
        assertEquals(HOUR, service.interval());
    }

    @Test
    void theStateOfASuccessfulRefreshIsTheVerySnapshotTheFetcherReturned() {
        UsageSnapshot returned = snapshot(7);
        UsageService service = start(() -> returned, HOUR);

        await(() -> service.state().snapshot() != null);

        assertSame(returned, service.state().snapshot());
    }

    private static void awaitEntered(Gate fetcher) {
        try {
            assertTrue(fetcher.entered.await(5, TimeUnit.SECONDS), "the fetch never started");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        await(Duration.ofSeconds(5), condition);
    }

    private static void await(Duration atMost, BooleanSupplier condition) {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + atMost.toSeconds() + " s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void anEarlierReadingIsShownBeforeTheFirstRefreshAndIsNotStale() {
        UsageService service = new UsageService(() -> snapshot(2), HOUR);
        started.add(service);

        service.restore(snapshot(1));

        assertEquals(snapshot(1), service.state().snapshot());
        assertFalse(service.state().stale());
        assertNull(service.state().error());
    }

    @Test
    void anEarlierReadingCannotBeRestoredOnceRunning() {
        UsageService service = start(() -> snapshot(2), HOUR);

        assertThrows(IllegalStateException.class, () -> service.restore(snapshot(1)));
    }

    @Test
    void aReadingWithNoSpendIsAWarningWhenItBeginsAndInformationAfterThatAndIsNoFailure() {
        List<String> lines = new CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                lines.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(capture);
        try {
            UsageSnapshot seat = new UsageSnapshot(Instant.parse("2026-10-11T18:26:31Z"), null, true);
            UsageSnapshot spent = new UsageSnapshot(Instant.parse("2026-10-11T18:28:31Z"), new Spend(186.02, 1000.0, "USD", 19, "normal"));
            List<UsageSnapshot> answers = List.of(seat, seat, spent, seat);
            AtomicInteger calls = new AtomicInteger();
            UsageService service = start(() -> answers.get(calls.getAndIncrement()), HOUR);
            for (int n = 1; n <= answers.size(); n++) {
                int wanted = n;
                await(() -> calls.get() == wanted && !service.state().refreshing());
                if (n < answers.size()) {
                    assertTrue(service.refreshNow());
                }
            }

            assertEquals(List.of(
                            "WARNING " + UsageSnapshot.NO_SPEND_SEAT_BASED,
                            "INFO " + UsageSnapshot.NO_SPEND_SEAT_BASED,
                            "INFO Usage refresh succeeded",
                            "WARNING " + UsageSnapshot.NO_SPEND_SEAT_BASED),
                    lines.stream().filter(line -> line.contains("Received, but") || line.contains("Usage refresh succeeded")).toList(),
                    "a warning when it begins, information while it lasts, and a warning again when it comes back");
            assertNull(service.state().error(), "it is a reading");
            assertTrue(service.errors().newestFirst().isEmpty(), "and not in the error log");
            assertSame(seat, service.state().snapshot());
        } finally {
            root.removeHandler(capture);
        }
    }

    // ---- the automatic interval: the search takes the place of the configured interval

    @Test
    void switchedOnTheIntervalOfTheSearchIsInForceAndEachAnswerMovesIt() {
        Scripted fetch = new Scripted(false, false, true);
        List<String> saved = new CopyOnWriteArrayList<>();
        UsageService service = new UsageService(fetch, HOUR);
        started.add(service);
        service.onSearchMoved(moved -> saved.add(moved.describe()));
        service.setAutomatic(IntervalSearch.fresh());
        assertTrue(service.automatic());
        assertEquals(60, service.effectiveIntervalSeconds(), "the search begins at 60, whatever is configured");

        service.start();
        await(() -> saved.size() == 1);
        assertEquals(54, service.effectiveIntervalSeconds(), "an answer before any refusal: a tenth down");

        assertTrue(service.refreshNow());
        await(() -> saved.size() == 2);
        assertEquals(49, service.effectiveIntervalSeconds(), "a manual refresh moves the search as a scheduled one does");

        assertTrue(service.refreshNow());
        await(() -> saved.size() == 3);
        assertEquals(54, service.effectiveIntervalSeconds(), "a 429 at 49: a tenth up, ceil(53.9)");
        assertTrue(service.state().rateLimited());
        assertTrue(service.state().error().endsWith(" Next try in 54 s."), service.state().error());
        assertEquals(List.of("54 s (no refusal yet)", "49 s (no refusal yet)", "54 s (refused at 49 s)"), saved, "and each step is given to be saved");
    }

    @Test
    void aFailureThatIsNo429LeavesTheSearchWhereItIs() {
        AtomicInteger calls = new AtomicInteger();
        List<String> saved = new CopyOnWriteArrayList<>();
        UsageService service = new UsageService(() -> {
            if (calls.incrementAndGet() == 2) {
                throw new UsageFetchException("Anthropic returned HTTP 500.", 500);
            }
            return snapshot(1);
        }, HOUR);
        started.add(service);
        service.onSearchMoved(moved -> saved.add(String.valueOf(moved.interval())));
        service.setAutomatic(IntervalSearch.fresh());
        service.start();
        await(() -> saved.size() == 1);

        assertTrue(service.refreshNow());
        await(() -> calls.get() == 2 && !service.state().refreshing());

        assertNotNull(service.state().error());
        assertEquals(54, service.effectiveIntervalSeconds(), "as it was after the first answer");
        assertEquals(List.of("54"), saved, "and nothing was given to be saved for it");
    }

    @Test
    void aLongerWaitOfTheServerBecomesTheIntervalOfTheSearchSoThatThereIsOneFigure() {
        AtomicInteger calls = new AtomicInteger();
        List<String> saved = new CopyOnWriteArrayList<>();
        UsageService service = new UsageService(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429, Duration.ofSeconds(300));
            }
            return snapshot(1);
        }, HOUR);
        started.add(service);
        service.onSearchMoved(moved -> saved.add(moved.describe()));
        service.setAutomatic(IntervalSearch.of(11, null));
        service.start();
        await(() -> service.state().rateLimited() && saved.size() == 1);

        assertEquals(300, service.effectiveIntervalSeconds(), "the interval the row shows is the wait the server named");
        long wait = service.secondsUntilNextRefresh().orElseThrow();
        assertTrue(wait > 290 && wait <= 300, "and it is the wait: " + wait);
        assertTrue(service.state().error().endsWith(" Next try in 5 min."), service.state().error());
        assertEquals(List.of("300 s (refused at 11 s)"), saved, "and what is saved");
    }

    @Test
    void aWaitOfTheServerAboveThreeHundredSecondsIsNoMoreThanThreeHundred() {
        AtomicInteger calls = new AtomicInteger();
        UsageService service = new UsageService(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429, Duration.ofSeconds(500));
            }
            return snapshot(1);
        }, HOUR);
        started.add(service);
        service.setAutomatic(IntervalSearch.fresh());
        service.start();
        await(() -> service.state().rateLimited());

        assertEquals(300, service.effectiveIntervalSeconds(), "the search goes no higher");
        long wait = service.secondsUntilNextRefresh().orElseThrow();
        assertTrue(wait > 290 && wait <= 300, "wait " + wait);
        assertTrue(service.state().error().endsWith(" Next try in 5 min."), service.state().error());
    }

    @Test
    void switchedOffTheConfiguredIntervalIsInForceAgainAlsoOneThatWasSetMeanwhile() {
        UsageService service = new UsageService(() -> snapshot(1), Duration.ofSeconds(90));
        started.add(service);
        service.setAutomatic(IntervalSearch.of(29, 23));
        assertEquals(29, service.effectiveIntervalSeconds());

        service.setInterval(Duration.ofSeconds(120));
        assertEquals(29, service.effectiveIntervalSeconds(), "the configured interval waits while the search is on");

        service.setAutomatic(null);
        assertFalse(service.automatic());
        assertEquals(120, service.effectiveIntervalSeconds());
    }

    @Test
    void aSearchThatHasSettledGivesNothingMoreToBeSaved() {
        List<String> saved = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        UsageService service = new UsageService(() -> { calls.incrementAndGet(); return snapshot(1); }, HOUR);
        started.add(service);
        service.onSearchMoved(moved -> saved.add(moved.describe()));
        service.setAutomatic(IntervalSearch.of(24, 23));
        service.start();
        await(() -> calls.get() == 1 && !service.state().refreshing());
        assertTrue(service.refreshNow());
        await(() -> calls.get() == 2 && !service.state().refreshing());

        assertEquals(24, service.effectiveIntervalSeconds());
        assertEquals(List.of(), saved, "24 with 23 refused stays what it is");
    }
}
