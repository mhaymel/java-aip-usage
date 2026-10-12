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
    void beforeARefusalTheIntervalGoesDownByATenthWithEachAnswerNotBelowTen() {
        IntervalSearch search = IntervalSearch.fresh();
        List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            search.succeeded();
            steps.add(search.interval());
        }

        assertEquals(List.of(54, 49, 45, 41, 37, 34, 31, 28), steps);
        answers(search, 40);
        assertEquals(10, search.interval());
        assertNull(search.refused());
    }

    @Test
    void aRefusalIsRememberedAndTheNextIntervalIsATenthLongerAtLeastASecond() {
        IntervalSearch search = IntervalSearch.of(20, null);
        search.rateLimited();
        assertEquals(22, search.interval());
        assertEquals(20, search.refused());
        assertEquals("22 s (refused at 20 s)", search.describe());

        IntervalSearch hundred = IntervalSearch.of(100, null);
        hundred.rateLimited();
        assertEquals(110, hundred.interval());

        IntervalSearch ten = IntervalSearch.of(10, null);
        ten.rateLimited();
        assertEquals(11, ten.interval(), "a tenth of 10 is a second: R + 1");
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
        assertEquals(300, search.interval(), "never below R + 1, and R + 1 is above the ceiling: it stays");
    }

    @Test
    void afterEightAnswersWithoutARefusalItIsASecondShorterButNotAsShortAsTheRefusedOne() {
        IntervalSearch search = IntervalSearch.of(40, null);
        search.rateLimited();
        assertEquals(44, search.interval());

        answers(search, 7);
        assertEquals(44, search.interval());
        answers(search, 1);
        assertEquals(43, search.interval());
        answers(search, 8 * 2);
        assertEquals(41, search.interval(), "a second for every eight answers: 43, 42, 41");
        answers(search, 8 * 10);
        assertEquals(search.refused() + 1, search.interval(), "never as short as the refused interval: one above it, which has been forgotten down meanwhile");
        assertEquals(35, search.refused(), "104 answers, five seconds forgotten");
    }

    @Test
    void aRefusalStartsTheCountOfAnswersAgain() {
        IntervalSearch search = IntervalSearch.of(40, null);
        search.rateLimited();
        answers(search, 7);

        search.rateLimited();

        assertEquals(44, search.refused());
        assertEquals(49, search.interval());
        answers(search, 7);
        assertEquals(49, search.interval());
        answers(search, 1);
        assertEquals(48, search.interval());
    }

    @Test
    void theRefusedIntervalIsForgottenASecondForTwentyAnswersAndThenNone() {
        IntervalSearch search = IntervalSearch.of(40, null);
        search.rateLimited();
        answers(search, 19);
        assertEquals(40, search.refused());
        answers(search, 1);
        assertEquals(39, search.refused());

        IntervalSearch low = IntervalSearch.of(10, null);
        low.rateLimited();
        answers(low, 20);
        assertNull(low.refused(), "below 10 there is nothing left to remember");
    }

    @Test
    void aWaitTheServerNamesLongerThanTheStepBecomesTheIntervalAtMostThreeHundred() {
        IntervalSearch search = IntervalSearch.of(11, null);
        search.rateLimited(300);
        assertEquals(300, search.interval());
        assertEquals(11, search.refused(), "the interval that was refused, not the server's wait");

        IntervalSearch above = IntervalSearch.of(11, null);
        above.rateLimited(3600);
        assertEquals(300, above.interval());

        IntervalSearch shorter = IntervalSearch.of(50, null);
        shorter.rateLimited(20);
        assertEquals(55, shorter.interval(), "the server's wait never shortens the step");
    }

    @Test
    void savedFiguresThatCannotBeAStateBeginItAnewAndAnIntervalAtTheRefusedOneIsRaised() {
        assertEquals(60, IntervalSearch.of(9, null).interval());
        assertEquals(60, IntervalSearch.of(301, null).interval());
        assertEquals(60, IntervalSearch.of(30, 5).interval());
        assertNull(IntervalSearch.of(30, 5).refused());
        assertEquals(31, IntervalSearch.of(30, 30).interval());
        assertEquals(31, IntervalSearch.of(20, 30).interval());
        assertEquals(30, IntervalSearch.of(30, 25).interval());
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

            assertTrue(search.interval() >= limit - 1 && search.interval() <= limit + Math.max(3, limit / 8),
                    "limit " + limit + ": settled at " + search.interval());
            // Once it has settled it is refused about every twenty answers, as the refused interval is forgotten a second at a time.
            assertTrue(later <= 1000 / IntervalSearch.ANSWERS_PER_DECAY + 10, "limit " + limit + ": " + later + " refusals in 1000 answers (" + first + " at first)");
        }
    }

    @Test
    void whenTheLimitFallsTheSearchFollowsItDown() {
        IntervalSearch search = IntervalSearch.fresh();
        refusals(60, 400, search);
        assertTrue(search.interval() >= 60);

        refusals(20, 3000, search);

        assertTrue(search.interval() <= 24, "settled at " + search.interval());
    }
}
