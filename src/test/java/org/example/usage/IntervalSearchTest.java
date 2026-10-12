package org.example.usage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalSearchTest {

    private static void answers(IntervalSearch search, int n) {
        for (int i = 0; i < n; i++) {
            search.succeeded();
        }
    }

    @Test
    void itBeginsAtSixtySecondsWithNoRefusalKnown() {
        IntervalSearch search = IntervalSearch.fresh();

        assertEquals(60, search.interval());
        assertNull(search.refused());
        assertEquals("60 s (no refusal yet)", search.describe());
    }

    @Test
    void beforeARefusalTheIntervalGoesDownByATenthRoundedUpToFiveWithEachAnswerNotBelowTen() {
        IntervalSearch search = IntervalSearch.fresh();
        List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            search.succeeded();
            steps.add(search.interval());
        }

        assertEquals(List.of(55, 50, 45, 40, 35, 30, 25, 20), steps);
        answers(search, 40);
        assertEquals(10, search.interval());
        assertNull(search.refused());
    }

    @Test
    void aRefusalIsRememberedAndTheNextIntervalIsATenthLongerRoundedUpToFiveAndAtLeastFiveMore() {
        IntervalSearch search = IntervalSearch.of(20, null);
        search.rateLimited();
        assertEquals(25, search.interval());
        assertEquals(20, search.refused());
        assertEquals("25 s (refused at 20 s)", search.describe());

        IntervalSearch hundred = IntervalSearch.of(100, null);
        hundred.rateLimited();
        assertEquals(110, hundred.interval());

        IntervalSearch odd = IntervalSearch.of(105, null);
        odd.rateLimited();
        assertEquals(120, odd.interval(), "115.5 rounded up to the next 5 is 120");

        IntervalSearch ten = IntervalSearch.of(10, null);
        ten.rateLimited();
        assertEquals(15, ten.interval(), "a tenth of 10 is 1: R + 5");
    }

    @Test
    void itNeverGoesAboveThreeHundred() {
        IntervalSearch search = IntervalSearch.of(290, null);
        search.rateLimited();
        assertEquals(300, search.interval());
        search.rateLimited();
        assertEquals(300, search.interval());
        assertEquals(300, search.refused());
        answers(search, 8);
        assertEquals(300, search.interval(), "never below R + 5, and R + 5 is above the ceiling: it stays");
    }

    @Test
    void afterEightAnswersWithoutARefusalItIsFiveSecondsShorterButNotAsShortAsTheRefusedOnePlusFive() {
        IntervalSearch search = IntervalSearch.of(100, null);
        search.rateLimited();
        assertEquals(110, search.interval());

        answers(search, 7);
        assertEquals(110, search.interval());
        answers(search, 1);
        assertEquals(105, search.interval());
        answers(search, 8 * 5);
        assertEquals(105, search.interval(), "one step above the refused interval, 100, and no lower");
        answers(search, 8 * 5);
        assertEquals(100, search.interval(), "once the refused interval has been forgotten a step, after 60 answers, to 95");
    }

    @Test
    void aRefusalStartsTheCountOfAnswersAgain() {
        IntervalSearch search = IntervalSearch.of(100, null);
        search.rateLimited();
        answers(search, 7);

        search.rateLimited();

        assertEquals(110, search.refused());
        assertEquals(125, search.interval());
        answers(search, 7);
        assertEquals(125, search.interval());
        answers(search, 1);
        assertEquals(120, search.interval());
    }

    @Test
    void theRefusedIntervalIsForgottenAStepForSixtyAnswersAndThenNone() {
        IntervalSearch search = IntervalSearch.of(40, null);
        search.rateLimited();
        answers(search, 59);
        assertEquals(40, search.refused());
        answers(search, 1);
        assertEquals(35, search.refused());

        IntervalSearch low = IntervalSearch.of(10, null);
        low.rateLimited();
        answers(low, 60);
        assertNull(low.refused(), "below 10 there is nothing left to remember");
    }

    @Test
    void everyIntervalIsAMultipleOfFive() {
        IntervalSearch search = IntervalSearch.fresh();
        for (int i = 0; i < 3000; i++) {
            if (i % 37 == 0) {
                search.rateLimited(i % 3 == 0 ? 0 : 47);
            } else {
                search.succeeded();
            }
            assertEquals(0, search.interval() % 5, "interval " + search.interval());
            assertTrue(search.interval() >= 10 && search.interval() <= 300);
        }
    }

    @Test
    void aWaitTheServerNamesLongerThanTheStepBecomesTheIntervalRoundedUpToFiveAtMostThreeHundred() {
        IntervalSearch search = IntervalSearch.of(10, null);
        search.rateLimited(300);
        assertEquals(300, search.interval());
        assertEquals(10, search.refused(), "the interval that was refused, not the server's wait");

        IntervalSearch above = IntervalSearch.of(10, null);
        above.rateLimited(3600);
        assertEquals(300, above.interval());

        IntervalSearch odd = IntervalSearch.of(10, null);
        odd.rateLimited(47);
        assertEquals(50, odd.interval(), "47 seconds, rounded up to the next 5");

        IntervalSearch shorter = IntervalSearch.of(50, null);
        shorter.rateLimited(20);
        assertEquals(55, shorter.interval(), "the server's wait never shortens the step");
    }

    @Test
    void savedFiguresThatCannotBeAStateBeginItAnewAndOthersAreRoundedUpToTheSteps() {
        assertEquals(60, IntervalSearch.of(9, null).interval());
        assertEquals(60, IntervalSearch.of(301, null).interval());
        assertEquals(60, IntervalSearch.of(30, 5).interval());
        assertNull(IntervalSearch.of(30, 5).refused());
        assertEquals(35, IntervalSearch.of(30, 30).interval(), "at the refused interval: R + 5");
        assertEquals(35, IntervalSearch.of(20, 30).interval());
        assertEquals(30, IntervalSearch.of(30, 25).interval());
        assertEquals(25, IntervalSearch.of(23, null).interval(), "a single second of an earlier version is rounded up");
        assertEquals(25, IntervalSearch.of(30, 22).refused(), "so is a refused interval");
    }

    /** A server that refuses any interval shorter than a limit, as the endpoint was seen to behave. */
    private static int refusals(int limit, int answers, IntervalSearch search) {
        IntPredicate refuses = interval -> interval < limit;
        int refused = 0;
        for (int i = 0; i < answers; i++) {
            if (refuses.test(search.interval())) {
                search.rateLimited();
                refused++;
            } else {
                search.succeeded();
            }
        }
        return refused;
    }

    @Test
    void againstAServerWithAFixedLimitItSettlesJustAboveItWithFewRefusals() {
        for (int limit : new int[] {12, 25, 47, 90, 180}) {
            IntervalSearch search = IntervalSearch.fresh();
            int first = refusals(limit, 200, search);
            int later = refusals(limit, 1000, search);

            assertTrue(search.interval() >= limit - 5 && search.interval() <= limit + Math.max(10, limit / 8),
                    "limit " + limit + ": settled at " + search.interval());
            // Once it has settled it is refused about every sixty answers, as the refused interval is forgotten a second at a time.
            assertTrue(later <= 1000 / IntervalSearch.ANSWERS_PER_DECAY + 10, "limit " + limit + ": " + later + " refusals in 1000 answers (" + first + " at first)");
        }
    }

    @Test
    void whenTheLimitFallsTheSearchFollowsItDown() {
        IntervalSearch search = IntervalSearch.fresh();
        refusals(60, 400, search);
        assertTrue(search.interval() >= 50, "near the limit of 60: " + search.interval());

        refusals(20, 9000, search);

        assertTrue(search.interval() <= 30, "settled at " + search.interval());
    }
}
