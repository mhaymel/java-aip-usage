package org.example.usage;

import org.example.money.Amount;
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
        return start(UsageFetch.of(fetcher), interval);
    }

    private UsageService start(UsageFetch fetcher, Duration interval) {
        UsageService service = new UsageService(fetcher, interval);
        started.add(service);
        service.start();
        return service;
    }

    /** A fetcher whose calls can be held open until released. */
    private static final class Gate implements UsageFetch {

        final AtomicInteger calls = new AtomicInteger();

        final AtomicInteger inFlight = new AtomicInteger();

        final AtomicInteger maxInFlight = new AtomicInteger();

        /** Whether a call was told to give up while it was held. */
        final AtomicBoolean givenUp = new AtomicBoolean();

        volatile Thread fetchThread;

        /** Counted down by a call that has started. */
        volatile CountDownLatch entered = new CountDownLatch(1);

        /** Calls wait on this while it is not null. */
        volatile CountDownLatch hold;

        @Override
        public UsageSnapshot fetch(org.example.Cancel cancel) {
            // Whether this call is held is settled before it says that it is under way: a test that sees it under way and
            // then lets the next call through must not let this one through with it.
            CountDownLatch latch = hold;
            int n = calls.incrementAndGet();
            fetchThread = Thread.currentThread();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                if (latch != null) {
                    // Told to give up, a held call is let go, as the real one stops waiting for the network.
                    cancel.onCancel(latch::countDown);
                    latch.await();
                }
            } catch (InterruptedException e) {
                givenUp.set(true);
                Thread.currentThread().interrupt();
                throw new UsageFetchException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
            if (cancel.isCancelled()) {
                givenUp.set(true);
                throw new UsageFetchException("given up", null);
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

        // The request to the old source is blocked. The restart tells it to give up, and the next request is not held.
        fetcher.hold = null;
        service.restart(null);

        await(() -> fetcher.givenUp.get());
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
            assertEquals("Unexpected error; start the program with --debug to see on the console where it came from.", first.error());
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
        assertFalse(fetcher.givenUp.get(), "the running fetch must not be cancelled");
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
        // A reading, and a 429 for every request after it: one 429 alone is over with the next request, 30 milliseconds
        // later, and a test that looks every few milliseconds on a busy machine can miss it for good.
        AtomicInteger calls = new AtomicInteger();

        UsageService service = start(() -> {
            if (calls.getAndIncrement() > 0) {
                throw rateLimited();
            }
            return snapshot(0);
        }, Duration.ofMillis(30));
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
        assertFalse(entries.get(0).message().contains("Next try in"), "the message says nothing of the next try");
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

        // A fifth of a second, so that what a busy machine adds to a wait is little beside it: a wait that doubled, as the
        // back-off of earlier versions did, would be 400 milliseconds and more, and one that is late by less than the
        // interval is still told from it. With 60 milliseconds and a bound of 150 the test failed now and then under load.
        UsageService service = start(fetch, Duration.ofMillis(200));
        await(() -> fetch.callTimesMillis.size() >= 4);

        for (int n = 1; n < 4; n++) {
            // The interval is counted from when a request is triggered, and what is measured here is when its call began, which
            // on a busy machine is some milliseconds later: so a gap can be that much shorter or longer than the interval.
            assertTrue(fetch.gapBefore(n) >= 140 && fetch.gapBefore(n) < 400, "gap " + n + ": " + fetch.gapBefore(n));
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
    void theErrorOfARateLimitSaysNothingOfTheNextTry() {
        UsageService service = start(() -> {
            throw rateLimited();
        }, Duration.ofSeconds(30));

        await(() -> service.state().error() != null);

        assertEquals("Anthropic is rate limiting usage requests (HTTP 429).", service.state().error());
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

        assertTrue(fetcher.givenUp.get(), "the running fetch is told to give up");
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
        org.example.log.Log.Sink capture = entry -> lines.add(entry.level().word() + " " + entry.message());
        org.example.log.Log.addSink(capture);
        try {
            UsageSnapshot seat = new UsageSnapshot(Instant.parse("2026-10-11T18:26:31Z"), null, true);
            UsageSnapshot spent = new UsageSnapshot(Instant.parse("2026-10-11T18:28:31Z"), new Spend(Amount.parse("186.02"), Amount.parse("1000.0"), "USD", 19, "normal"));
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
            org.example.log.Log.removeSink(capture);
        }
    }

    // ---- one loop that owns everything, and questions that never wait for it

    @Test
    void whatTheWindowAsksEverySecondIsAnsweredWhileTheLoopIsBusy() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UsageService service = new UsageService(new Scripted(false), HOUR);
        started.add(service);
        // Whoever is told of the search is called by the loop; one that takes its time holds the loop up.
        service.onSearchMoved(moved -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        service.setAutomatic(IntervalSearch.fresh());
        service.start();
        assertTrue(busy.await(5, TimeUnit.SECONDS), "the loop is telling of the search");

        long began = System.nanoTime();
        try {
            assertNotNull(service.state().snapshot());
            assertTrue(service.secondsUntilNextRefresh().isPresent());
            assertTrue(service.effectiveIntervalSeconds() > 0);
            assertTrue(service.automatic());
            assertNotNull(service.interval());
            assertTrue(Duration.ofNanos(System.nanoTime() - began).toMillis() < 1000, "none of them waited for the loop");
        } finally {
            release.countDown();
        }
    }

    @Test
    void whoeverIsToldOfTheSearchMayAskTheServiceInTurn() {
        List<String> asked = new CopyOnWriteArrayList<>();
        UsageService[] holder = new UsageService[1];
        UsageService service = new UsageService(new Scripted(false), HOUR);
        holder[0] = service;
        started.add(service);
        service.onSearchMoved(moved -> {
            // Answered on the spot by the loop that is calling: waiting for it would wait for itself.
            asked.add("refreshing again: " + holder[0].refreshNow());
            holder[0].setInterval(Duration.ofSeconds(90));
            asked.add("interval " + holder[0].effectiveIntervalSeconds());
        });
        service.setAutomatic(IntervalSearch.fresh());
        service.start();

        await(() -> asked.size() >= 2);

        assertEquals("refreshing again: true", asked.get(0), "the request has ended, so another may be asked for");
        assertEquals("interval 55", asked.get(1), "the search's interval, the configured one waiting behind it");
    }

    @Test
    void aFaultInWhoeverIsToldOfTheSearchDoesNotEndTheRefreshing() {
        Scripted fetch = new Scripted(false, false);
        UsageService service = new UsageService(fetch, HOUR);
        started.add(service);
        service.onSearchMoved(moved -> {
            throw new IllegalStateException("could not save");
        });
        service.setAutomatic(IntervalSearch.fresh());
        service.start();
        await(() -> fetch.callTimesMillis.size() == 1 && !service.state().refreshing());

        assertTrue(service.refreshNow(), "the loop is still there to take it");
        await(() -> fetch.callTimesMillis.size() == 2);
    }

    @Test
    void whatIsAskedAfterTheServiceIsClosedIsAnsweredAndChangesNothingThatRuns() {
        Gate fetcher = new Gate();
        UsageService service = start(fetcher, HOUR);
        await(() -> fetcher.calls.get() == 1 && !service.state().refreshing());
        service.close();

        assertFalse(service.refreshNow());
        service.setInterval(Duration.ofSeconds(30));
        assertEquals(30, service.effectiveIntervalSeconds(), "the question is answered, by a service that fetches no more");
        service.restart(null);
        service.close();
        assertEquals(1, fetcher.calls.get());
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
        assertEquals(55, service.effectiveIntervalSeconds(), "an answer before any refusal: a tenth down, rounded up to 5");

        assertTrue(service.refreshNow());
        await(() -> saved.size() == 2);
        assertEquals(50, service.effectiveIntervalSeconds(), "a manual refresh moves the search as a scheduled one does");

        assertTrue(service.refreshNow());
        await(() -> saved.size() == 3);
        assertEquals(55, service.effectiveIntervalSeconds(), "a 429 at 50: a tenth up");
        assertTrue(service.state().rateLimited());
        assertEquals("Anthropic is rate limiting usage requests (HTTP 429).", service.state().error());
        assertEquals(List.of("55 s (no refusal yet)", "50 s (no refusal yet)", "55 s (refused at 50 s)"), saved, "and each step is given to be saved");
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
        assertEquals(55, service.effectiveIntervalSeconds(), "as it was after the first answer");
        assertEquals(List.of("55"), saved, "and nothing was given to be saved for it");
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
        service.setAutomatic(IntervalSearch.of(10, null));
        service.start();
        await(() -> service.state().rateLimited() && saved.size() == 1);

        assertEquals(300, service.effectiveIntervalSeconds(), "the interval the row shows is the wait the server named");
        long wait = service.secondsUntilNextRefresh().orElseThrow();
        assertTrue(wait > 290 && wait <= 300, "and it is the wait: " + wait);
        assertEquals("Anthropic is rate limiting usage requests (HTTP 429).", service.state().error());
        assertEquals(List.of("300 s (refused at 10 s)"), saved, "and what is saved");
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
        assertEquals("Anthropic is rate limiting usage requests (HTTP 429).", service.state().error());
    }

    @Test
    void switchedOffTheConfiguredIntervalIsInForceAgainAlsoOneThatWasSetMeanwhile() {
        UsageService service = new UsageService(() -> snapshot(1), Duration.ofSeconds(90));
        started.add(service);
        service.setAutomatic(IntervalSearch.of(30, 25));
        assertEquals(30, service.effectiveIntervalSeconds());

        service.setInterval(Duration.ofSeconds(120));
        assertEquals(30, service.effectiveIntervalSeconds(), "the configured interval waits while the search is on");

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
        service.setAutomatic(IntervalSearch.of(30, 25));
        service.start();
        await(() -> calls.get() == 1 && !service.state().refreshing());
        assertTrue(service.refreshNow());
        await(() -> calls.get() == 2 && !service.state().refreshing());

        assertEquals(30, service.effectiveIntervalSeconds());
        assertEquals(List.of(), saved, "30 with 25 refused stays what it is");
    }

    // ---- the refused interval is forgotten by time

    @Test
    void theRefusedIntervalIsForgottenAStepForTenMinutesWithoutA429AndTheLogAndTheSavedFiguresSaySo() {
        List<String> lines = new CopyOnWriteArrayList<>();
        org.example.log.Log.Sink capture = entry -> lines.add(entry.message());
        org.example.log.Log.addSink(capture);
        try {
            List<String> saved = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(50_000_000_000_000L);
            UsageService service = new UsageService(() -> { calls.incrementAndGet(); return snapshot(1); }, HOUR);
            started.add(service);
            service.searchClock(clock::get);
            service.onSearchMoved(moved -> saved.add(moved.describe()));
            // What was seen: 200 seconds with the refused interval at 195, and no request failing.
            service.setAutomatic(IntervalSearch.of(200, 195));
            service.start();
            await(() -> calls.get() == 1 && !service.state().refreshing());
            assertEquals(List.of(), saved, "the first answer begins the ten minutes and moves nothing");

            // A second short of ten minutes, and then the ten minutes.
            clock.addAndGet(Duration.ofSeconds(599).toNanos());
            assertTrue(service.refreshNow());
            await(() -> calls.get() == 2 && !service.state().refreshing());
            assertEquals(List.of(), saved, "not yet");
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            assertTrue(service.refreshNow());
            await(() -> calls.get() == 3 && !service.state().refreshing());

            assertEquals(List.of("200 s (refused at 190 s)"), saved, "a step of the refused interval alone is given to be saved");
            assertEquals(200, service.effectiveIntervalSeconds(), "the interval follows by its own steps, of eight answers");
            assertEquals(List.of("Automatic interval: 200 s (refused at 195 s)", "Automatic interval: 200 s (refused at 190 s)"),
                    lines.stream().filter(line -> line.startsWith("Automatic interval:")).toList(), "the log says where the search is when it is switched on, and then the step");

            for (int n = 4; n <= 8; n++) {
                int wanted = n;
                assertTrue(service.refreshNow());
                await(() -> calls.get() == wanted && !service.state().refreshing());
            }
            assertEquals(195, service.effectiveIntervalSeconds(), "the eighth answer: a step down, which the refused interval now allows");
            assertEquals(List.of("200 s (refused at 190 s)", "195 s (refused at 190 s)"), saved);
        } finally {
            org.example.log.Log.removeSink(capture);
        }
    }

    @Test
    void whoeverSeesTheStateOfAnAnswerReadsTheIntervalThatBelongsToIt() {
        // The state is what a reader waits for, and the interval is what it reads next: the interval must be there first.
        for (int round = 0; round < 200; round++) {
            UsageService service = new UsageService(() -> {
                throw new UsageFetchException("Anthropic is rate limiting usage requests (HTTP 429).", 429, Duration.ofSeconds(500));
            }, HOUR);
            started.add(service);
            service.setAutomatic(IntervalSearch.fresh());
            service.start();
            while (!service.state().rateLimited()) {
                Thread.onSpinWait();
            }
            assertEquals(300, service.effectiveIntervalSeconds(), "round " + round + ": the 429 is seen, so its interval is in force");
            service.close();
        }
    }
}
