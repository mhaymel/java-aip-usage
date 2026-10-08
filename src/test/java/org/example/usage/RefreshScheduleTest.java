package org.example.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Times are written in seconds and converted to the nanoseconds the schedule uses. */
class RefreshScheduleTest {

    private static long s(long seconds) {
        return seconds * 1_000_000_000L;
    }

    private final RefreshSchedule schedule = new RefreshSchedule(s(30));

    @Test
    void firstRequestIsDueImmediately() {
        assertEquals(0, schedule.nanosUntilDue(s(1000)));
    }

    @Test
    void nextRequestIsDueOneIntervalAfterTheLastWasTriggered() {
        schedule.begin(s(100));
        schedule.finish();

        assertEquals(s(30), schedule.nanosUntilDue(s(100)));
        assertEquals(s(10), schedule.nanosUntilDue(s(120)));
        assertEquals(0, schedule.nanosUntilDue(s(130)));
        assertEquals(0, schedule.nanosUntilDue(s(500)));
    }

    @Test
    void theIntervalRunsFromTheTriggerNotFromTheEndOfTheRequest() {
        schedule.begin(s(100));
        // The request takes 20 s; the next is still due 30 s after it began.
        schedule.finish();

        assertEquals(s(10), schedule.nanosUntilDue(s(120)));
    }

    @Test
    void nothingIsDueWhileARequestRuns() {
        schedule.begin(s(100));

        assertTrue(schedule.running());
        assertEquals(Long.MAX_VALUE, schedule.nanosUntilDue(s(10_000)));
        assertThrows(IllegalStateException.class, () -> schedule.begin(s(10_000)));
    }

    @Test
    void aRequestOverrunningTheIntervalIsFollowedImmediately() {
        schedule.begin(s(100));
        schedule.finish();

        assertEquals(0, schedule.nanosUntilDue(s(190)));
    }

    @Test
    void manualRequestMakesTheNextOneDueNowAndRestartsTheClock() {
        schedule.begin(s(100));
        schedule.finish();

        assertTrue(schedule.request());
        assertEquals(0, schedule.nanosUntilDue(s(105)));

        schedule.begin(s(105));
        schedule.finish();
        assertEquals(s(30), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void manualRequestIsDeclinedWhileOneRunsOrIsAlreadyRequested() {
        schedule.begin(s(100));
        assertFalse(schedule.request());
        schedule.finish();

        assertTrue(schedule.request());
        assertFalse(schedule.request());
        assertFalse(schedule.request());
    }

    @Test
    void shorteningTheIntervalMovesTheDueTimeEarlier() {
        schedule.begin(s(100));
        schedule.finish();

        schedule.setInterval(s(10));

        assertEquals(s(5), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void lengtheningTheIntervalMovesTheDueTimeLater() {
        schedule.begin(s(100));
        schedule.finish();

        schedule.setInterval(s(60));

        assertEquals(s(55), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void changingTheIntervalDoesNotMakeARequestDueByItself() {
        schedule.begin(s(100));
        schedule.finish();

        schedule.setInterval(s(31));
        schedule.setInterval(s(10));
        schedule.setInterval(s(300));

        assertEquals(s(295), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void idleScheduleWhoseNewIntervalHasAlreadyElapsedIsDueNow() {
        schedule.begin(s(100));
        schedule.finish();

        // 50 s have passed; the interval shrinks to 10 s, so the next request is overdue.
        schedule.setInterval(s(10));

        assertEquals(0, schedule.nanosUntilDue(s(150)));
    }

    @Test
    void changingTheIntervalDuringARequestLeavesItRunning() {
        schedule.begin(s(100));

        schedule.setInterval(s(5));

        assertTrue(schedule.running());
        assertEquals(Long.MAX_VALUE, schedule.nanosUntilDue(s(110)));
    }

    @Test
    void intervalShortenedDuringARequestIsOverdueWhenItEnds() {
        schedule.begin(s(100));
        schedule.setInterval(s(5));
        schedule.finish();

        assertEquals(0, schedule.nanosUntilDue(s(120)));
    }

    @Test
    void intervalShortenedDuringARequestWaitsOutTheRestIfNotYetElapsed() {
        schedule.begin(s(100));
        schedule.setInterval(s(20));
        schedule.finish();

        // The request ended after 5 s; 15 s of the new 20 s interval remain.
        assertEquals(s(15), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void intervalLengthenedDuringARequestIsMeasuredFromTheSameTrigger() {
        schedule.begin(s(100));
        schedule.setInterval(s(60));
        schedule.finish();

        assertEquals(s(55), schedule.nanosUntilDue(s(105)));
    }

    @Test
    void rejectsANonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> schedule.setInterval(0));
        assertThrows(IllegalArgumentException.class, () -> schedule.setInterval(-1));
        assertThrows(IllegalArgumentException.class, () -> new RefreshSchedule(0));
    }
}
