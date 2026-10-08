package org.example.usage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seconds, converted to the nanoseconds the policy works in. */
class BackoffTest {

    private static long s(long seconds) {
        return seconds * 1_000_000_000L;
    }

    private final Backoff backoff = new Backoff(s(300));

    @Test
    void startsWithNoHold() {
        assertEquals(0, backoff.hold());
    }

    @Test
    void theFirstRateLimitHoldsForTwiceTheInterval() {
        assertEquals(s(60), backoff.rateLimited(s(30), 0));
        assertEquals(s(60), backoff.hold());
    }

    @Test
    void eachFurtherRateLimitInARowDoublesTheHoldUpToTheMaximum() {
        assertEquals(s(60), backoff.rateLimited(s(30), 0));
        assertEquals(s(120), backoff.rateLimited(s(30), 0));
        assertEquals(s(240), backoff.rateLimited(s(30), 0));
        assertEquals(s(300), backoff.rateLimited(s(30), 0), "capped, not 480");
        assertEquals(s(300), backoff.rateLimited(s(30), 0));
    }

    @Test
    void theServersRetryAfterWinsWhenLongerEvenAboveTheMaximum() {
        assertEquals(s(500), backoff.rateLimited(s(30), s(500)));
    }

    @Test
    void aShorterRetryAfterChangesNothing() {
        assertEquals(s(60), backoff.rateLimited(s(30), s(5)));
        assertEquals(s(120), backoff.rateLimited(s(30), s(1)));
    }

    @Test
    void aSuccessTakesAnEighthOffTheHoldAndThatIsTheNewHold() {
        backoff.rateLimited(s(30), 0);
        backoff.rateLimited(s(30), 0);
        backoff.rateLimited(s(30), 0);
        assertEquals(s(240), backoff.hold());

        assertEquals(s(210), backoff.succeeded(s(30)));
        assertEquals(s(210), backoff.hold());
        assertEquals(s(183) + 750_000_000L, backoff.succeeded(s(30)));
    }

    @Test
    void theEasedValueIsWhatTheNextRateLimitDoublesFrom() {
        Backoff roomy = new Backoff(s(1000));
        roomy.rateLimited(s(30), 0);
        roomy.rateLimited(s(30), 0);
        roomy.rateLimited(s(30), 0);
        roomy.succeeded(s(30));
        assertEquals(s(210), roomy.hold());

        assertEquals(s(420), roomy.rateLimited(s(30), 0), "from 210, not from the 240 before the success");
    }

    @Test
    void successesEaseTheHoldAwayInSteps() {
        backoff.rateLimited(s(30), 0);
        assertEquals(s(60), backoff.hold());

        long previous = backoff.hold();
        int successes = 0;
        while (backoff.hold() > 0) {
            long eased = backoff.succeeded(s(30));
            assertTrue(eased < previous, "each success shortens the hold");
            previous = eased;
            successes++;
            assertTrue(successes < 50, "the hold must end");
        }

        // 60, 52.5, 45.9, 40.2, 35.1, 30.8, then 26.9 is under the 30 s interval and counts as gone.
        assertEquals(6, successes);
        assertEquals(0, backoff.hold());
    }

    @Test
    void aSuccessWithNoHoldChangesNothing() {
        assertEquals(0, backoff.succeeded(s(30)));
        assertEquals(0, backoff.hold());
    }

    @Test
    void afterTheHoldIsGoneTheNextRateLimitStartsAtTwiceTheIntervalAgain() {
        backoff.rateLimited(s(30), 0);
        backoff.rateLimited(s(30), 0);
        while (backoff.hold() > 0) {
            backoff.succeeded(s(30));
        }

        assertEquals(s(60), backoff.rateLimited(s(30), 0));
    }

    @Test
    void aLongerIntervalThanTheHoldIsWhatTheDoublingStartsFrom() {
        // The hold was 60 s, then the user set the interval to 100 s.
        backoff.rateLimited(s(30), 0);

        assertEquals(s(200), backoff.rateLimited(s(100), 0));
    }

    @Test
    void aHoldThatIsNoLongerThanTheIntervalIsDroppedOnTheNextSuccess() {
        backoff.rateLimited(s(30), 0);

        // The interval is later raised to 120 s: the 60 s hold means nothing any more.
        assertEquals(0, backoff.succeeded(s(120)));
    }

    @Test
    void theMaximumMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(0));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(-1));
    }
}
