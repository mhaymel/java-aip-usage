package org.example.usage;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the back-off policy against a model of the usage endpoint, in simulated
 * time, to show what easing the hold is for.
 *
 * <p>The model is an assumption, fitted to a live log and not a fact about
 * Anthropic's service: a bucket of eight requests that refills at one a minute, so
 * about one request a minute is sustainable, after a burst. A request with no
 * token left is answered 429. What these tests check is how the policy behaves
 * against that, which is the reason it eases the hold and does not lift it.
 */
class BackoffPolicyTest {

    private static final long SECOND = 1_000_000_000L;

    private static final long MINUTE = 60 * SECOND;

    /** A request sequence's outcome. */
    private record Result(int successes, int rateLimited, int successesUntilClear) {

        double rateLimitedShare() {
            return rateLimited / (double) (successes + rateLimited);
        }
    }

    /** How a success changes the hold: the policy under test, or the old behaviour of lifting it. */
    private enum OnSuccess { EASE, LIFT }

    private static Result simulate(long intervalNanos, long hours, OnSuccess onSuccess, double startTokens, long startHold) {
        double tokens = startTokens;
        Backoff backoff = new Backoff(5 * MINUTE);
        if (startHold > 0) {
            // Begin already held back, as after a long rate limiting.
            for (long hold = intervalNanos; hold < startHold; hold *= 2) {
                backoff.rateLimited(intervalNanos, 0);
            }
        }
        long now = 0;
        long last = 0;
        int ok = 0;
        int limited = 0;
        int successesUntilClear = -1;
        while (now < hours * 60 * MINUTE) {
            tokens = Math.min(8, tokens + (now - last) / (double) MINUTE);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                ok++;
                if (onSuccess == OnSuccess.EASE) {
                    backoff.succeeded(intervalNanos);
                } else {
                    backoff = new Backoff(5 * MINUTE);
                }
                if (successesUntilClear < 0 && backoff.hold() == 0) {
                    successesUntilClear = ok;
                }
            } else {
                limited++;
                backoff.rateLimited(intervalNanos, 0);
            }
            now += Math.max(intervalNanos, backoff.hold());
        }
        return new Result(ok, limited, successesUntilClear);
    }

    private static Result simulate(long intervalNanos, OnSuccess onSuccess) {
        return simulate(intervalNanos, 1, onSuccess, 8, 0);
    }

    @Test
    void atThirtySecondsEasingWastesFarFewerRequestsThanLiftingTheHold() {
        Result eased = simulate(30 * SECOND, OnSuccess.EASE);
        Result lifted = simulate(30 * SECOND, OnSuccess.LIFT);

        // Lifting the hold goes straight back to the pace that was just refused.
        assertTrue(lifted.rateLimitedShare() > 0.25, "lifting: " + lifted);
        assertTrue(eased.rateLimitedShare() < 0.20, "easing: " + eased);
        assertTrue(eased.rateLimited() < lifted.rateLimited() * 0.6, "easing " + eased + " vs lifting " + lifted);
    }

    @Test
    void easingDoesNotCostAnyReadings() {
        Result eased = simulate(30 * SECOND, OnSuccess.EASE);
        Result lifted = simulate(30 * SECOND, OnSuccess.LIFT);

        // The server decides how many readings an hour get through; the policy only
        // decides how many requests are wasted on a refusal.
        assertTrue(eased.successes() >= lifted.successes() - 1, "easing " + eased + " vs lifting " + lifted);
        assertTrue(eased.successes() >= 60, "about one a minute still gets through: " + eased);
    }

    @Test
    void atAPaceTheEndpointAcceptsThereIsNothingToEase() {
        for (OnSuccess policy : OnSuccess.values()) {
            Result result = simulate(60 * SECOND, policy);

            assertEquals(0, result.rateLimited(), policy + ": " + result);
            assertEquals(60, result.successes(), policy + ": " + result);
        }
    }

    @Test
    void aHoldThatIsNoLongerNeededEasesAwayInAReasonableTime() {
        // Held back at the 5 minute maximum, with the server healthy and its bucket full.
        Result result = simulate(30 * SECOND, 6, OnSuccess.EASE, 8, 5 * MINUTE);

        assertTrue(result.successesUntilClear() > 0, "the hold ended: " + result);
        assertTrue(result.successesUntilClear() <= 25, "within 25 readings: " + result);
    }

    @Test
    void easingStaysNearTheSustainablePaceRatherThanCollapsingToTheMaximum() {
        Result eased = simulate(30 * SECOND, 4, OnSuccess.EASE, 8, 0);

        // Four hours at one reading a minute is 240 plus the starting burst; holding at
        // the 5 minute maximum would give about 50.
        assertTrue(eased.successes() > 230, "" + eased);
    }

    @Test
    void theModelItselfBehavesAsDescribed() {
        // Sanity check of the model: at one request a minute from a full bucket, nothing is refused.
        Supplier<Result> everyMinute = () -> simulate(60 * SECOND, 3, OnSuccess.LIFT, 8, 0);
        assertEquals(0, everyMinute.get().rateLimited());
        assertEquals(180, everyMinute.get().successes());
    }
}
