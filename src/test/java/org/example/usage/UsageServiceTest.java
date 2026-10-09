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
        return new UsageSnapshot(Instant.ofEpochSecond(n), null, List.of());
    }

    private UsageService start(Supplier<UsageSnapshot> fetcher, Duration interval) {
        UsageService service = new UsageService(fetcher, interval);
        started.add(service);
        service.start();
        return service;
    }

    private UsageService start(Supplier<UsageSnapshot> fetcher, Duration interval, Duration maxBackoff) {
        UsageService service = new UsageService(fetcher, interval, maxBackoff);
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
    void theCountdownShowsTheLongerWaitDuringABackoff() {
        UsageService service = start(() -> {
            throw rateLimited();
        }, Duration.ofSeconds(60));
        await(() -> service.state().error() != null);

        long left = service.secondsUntilNextRefresh().getAsLong();

        // The first 429 doubles the 60 s interval.
        assertTrue(left >= 118 && left <= 120, "left: " + left);
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

    // ---- back-off after HTTP 429

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
    void theEffectiveIntervalIsTheConfiguredOneUntilThereIsABackOff() {
        UsageService service = new UsageService(() -> snapshot(1), Duration.ofSeconds(60));

        assertEquals(60, service.effectiveIntervalSeconds());
        service.setInterval(Duration.ofMillis(60_500));
        assertEquals(61, service.effectiveIntervalSeconds(), "rounded up to whole seconds");
    }

    @Test
    void afterARateLimitTheEffectiveIntervalIsTheLongerWaitAndItEasesBack() {
        Scripted fetch = new Scripted(true);

        UsageService service = start(fetch, Duration.ofMillis(1500), Duration.ofSeconds(60));
        await(() -> service.state().rateLimited());

        // One 429 doubles the 1.5 s interval: 3 s.
        assertEquals(3, service.effectiveIntervalSeconds());
        await(() -> service.state().error() == null && service.state().snapshot() != null);
        await(() -> service.effectiveIntervalSeconds() < 3 || fetch.callTimesMillis.size() >= 4);
        assertTrue(service.effectiveIntervalSeconds() <= 3, "never more than the hold");
        assertTrue(service.effectiveIntervalSeconds() >= 2, "and never less than the configured one rounded up");
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
    void eachRateLimitedRefreshInARowDoublesTheWait() {
        Scripted fetch = new Scripted(true, true, true, true);

        start(fetch, Duration.ofMillis(50));
        await(() -> fetch.callTimesMillis.size() >= 4);

        // Interval 50 ms: twice, four times and eight times that, never less.
        assertTrue(fetch.gapBefore(1) >= 95, "first wait " + fetch.gapBefore(1));
        assertTrue(fetch.gapBefore(2) >= 190, "second wait " + fetch.gapBefore(2));
        assertTrue(fetch.gapBefore(3) >= 380, "third wait " + fetch.gapBefore(3));
    }

    @Test
    void theWaitStopsGrowingAtTheMaximum() {
        Scripted fetch = new Scripted(true, true, true, true, true);

        start(fetch, Duration.ofMillis(20), Duration.ofMillis(80));
        await(() -> fetch.callTimesMillis.size() >= 5);

        assertTrue(fetch.gapBefore(1) >= 38, "40 ms: " + fetch.gapBefore(1));
        assertTrue(fetch.gapBefore(2) >= 78, "80 ms: " + fetch.gapBefore(2));
        // Without a cap this would be 160 ms and then 320 ms.
        assertTrue(fetch.gapBefore(3) < 220, "capped near 80 ms: " + fetch.gapBefore(3));
        assertTrue(fetch.gapBefore(4) < 220, "capped near 80 ms: " + fetch.gapBefore(4));
    }

    @Test
    void aSuccessEasesTheBackoffInsteadOfLiftingIt() {
        // Interval 100 ms. 429 -> hold 200; 429 -> 400; ok -> eased to 350; 429 -> 700; ok.
        Scripted fetch = new Scripted(true, true, false, true, false);

        start(fetch, Duration.ofMillis(100));
        await(() -> fetch.callTimesMillis.size() >= 5);

        assertTrue(fetch.gapBefore(1) >= 190, "after the first 429: " + fetch.gapBefore(1));
        assertTrue(fetch.gapBefore(2) >= 390, "after the second: " + fetch.gapBefore(2));
        // Lifted, this would be the plain 100 ms and ask for a 429 again; eased, it is 350 ms.
        assertTrue(fetch.gapBefore(3) >= 340, "after a success the wait is eased, not dropped: " + fetch.gapBefore(3));
        assertTrue(fetch.gapBefore(3) < 600, "and no longer than the hold was: " + fetch.gapBefore(3));
        // The next 429 doubles the eased 350 ms, not the 400 ms from before the success.
        assertTrue(fetch.gapBefore(4) >= 690, "after the third 429: " + fetch.gapBefore(4));
    }

    @Test
    void enoughSuccessesBringTheWaitBackToTheInterval() {
        // One 429 (hold 100 ms at a 50 ms interval), then only successes: 87, 76, 66, 58 ms ... then gone.
        Scripted fetch = new Scripted(true);

        start(fetch, Duration.ofMillis(50));
        await(() -> fetch.callTimesMillis.size() >= 9);

        assertTrue(fetch.gapBefore(1) >= 95, "held: " + fetch.gapBefore(1));
        assertTrue(fetch.gapBefore(2) >= 80, "eased: " + fetch.gapBefore(2));
        assertTrue(fetch.gapBefore(2) < fetch.gapBefore(1) + 40, "and shorter than the hold, give or take scheduling: " + fetch.gapBefore(2));
        assertTrue(fetch.gapBefore(8) < 120, "back to about the interval: " + fetch.gapBefore(8));
    }

    @Test
    void theServersOwnRetryAfterIsHonouredWhenLonger() {
        AtomicInteger calls = new AtomicInteger();
        java.util.List<Long> times = new CopyOnWriteArrayList<>();
        start(() -> {
            times.add(System.nanoTime() / 1_000_000);
            if (calls.getAndIncrement() == 0) {
                throw new UsageFetchException("slow down", 429, Duration.ofMillis(700));
            }
            return snapshot(1);
        }, Duration.ofMillis(50));

        await(() -> times.size() >= 2);

        // The doubled interval alone would be 100 ms; the server asked for 700.
        assertTrue(times.get(1) - times.get(0) >= 690, "waited " + (times.get(1) - times.get(0)));
    }

    @Test
    void aShorterRetryAfterDoesNotShortenTheBackoff() {
        AtomicInteger calls = new AtomicInteger();
        java.util.List<Long> times = new CopyOnWriteArrayList<>();
        start(() -> {
            times.add(System.nanoTime() / 1_000_000);
            if (calls.getAndIncrement() == 0) {
                throw new UsageFetchException("slow down", 429, Duration.ofMillis(1));
            }
            return snapshot(1);
        }, Duration.ofMillis(100));

        await(() -> times.size() >= 2);

        assertTrue(times.get(1) - times.get(0) >= 190, "waited " + (times.get(1) - times.get(0)));
    }

    @Test
    void theErrorSaysWhenTheNextTryIs() {
        UsageService service = start(() -> {
            throw rateLimited();
        }, Duration.ofSeconds(30));

        await(() -> service.state().error() != null);

        assertEquals("Anthropic is rate limiting usage requests (HTTP 429). Next try in 1 min.", service.state().error());
    }

    @Test
    void theNextTryShownGrowsWithEachConsecutiveRateLimit() {
        AtomicInteger calls = new AtomicInteger();
        UsageService service = start(() -> {
            calls.incrementAndGet();
            throw rateLimited();
        }, Duration.ofSeconds(30));
        await(() -> service.state().error() != null);
        assertTrue(service.state().error().endsWith("Next try in 1 min."), service.state().error());

        // The wait is long, so ask by hand: a person's click is never held back.
        assertTrue(service.refreshNow());
        await(() -> calls.get() >= 2 && !service.state().refreshing());
        assertTrue(service.state().error().endsWith("Next try in 2 min."), service.state().error());

        assertTrue(service.refreshNow());
        await(() -> calls.get() >= 3 && !service.state().refreshing());
        assertTrue(service.state().error().endsWith("Next try in 4 min."), service.state().error());
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
    void aManualRefreshIsNotHeldBackByTheBackoff() {
        Scripted fetch = new Scripted(true);
        UsageService service = start(fetch, Duration.ofSeconds(2));
        await(() -> fetch.callTimesMillis.size() >= 1 && !service.state().refreshing());
        // The back-off is now 4 s. A click goes through at once.

        long clicked = System.nanoTime() / 1_000_000;
        assertTrue(service.refreshNow());
        await(() -> fetch.callTimesMillis.size() >= 2);

        assertTrue(fetch.callTimesMillis.get(1) - clicked < 1000, "took " + (fetch.callTimesMillis.get(1) - clicked));
    }

    @Test
    void theSuccessAfterABackoffClearsTheErrorAsUsual() {
        Scripted fetch = new Scripted(true);
        UsageService service = start(fetch, Duration.ofMillis(50));

        await(() -> service.state().snapshot() != null);

        assertNull(service.state().error());
        assertFalse(service.state().stale());
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
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
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
}
