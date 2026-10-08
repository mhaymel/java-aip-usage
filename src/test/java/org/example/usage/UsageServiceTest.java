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
}
