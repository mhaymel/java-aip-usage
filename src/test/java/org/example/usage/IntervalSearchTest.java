package org.example.usage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalSearchTest {

    private static final long SECOND = 1_000_000_000L;

    /** The clock of a test, in nanoseconds: it begins somewhere, and goes on as the test says. */
    private long now = 7_000 * SECOND;

    private void pass(long seconds) {
        now += seconds * SECOND;
    }

    /** Answers without a 429, a second apart: what counts is their number, and no ten minutes pass. */
    private void answers(IntervalSearch search, int n) {
        for (int i = 0; i < n; i++) {
            pass(1);
            search.succeeded(now);
        }
    }

    /** Answers without a 429 as they come while the program runs: each an interval after the one before. */
    private void answersAtTheInterval(IntervalSearch search, int n) {
        for (int i = 0; i < n; i++) {
            pass(search.interval());
            search.succeeded(now);
        }
    }

    private void refuse(IntervalSearch search) {
        refuse(search, 0);
    }

    private void refuse(IntervalSearch search, long serverSeconds) {
        pass(1);
        search.rateLimited(serverSeconds, now);
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
            answers(search, 1);
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
        refuse(search);
        assertEquals(25, search.interval());
        assertEquals(20, search.refused());
        assertEquals("25 s (refused at 20 s)", search.describe());

        IntervalSearch hundred = IntervalSearch.of(100, null);
        refuse(hundred);
        assertEquals(110, hundred.interval());

        IntervalSearch odd = IntervalSearch.of(105, null);
        refuse(odd);
        assertEquals(120, odd.interval(), "115.5 rounded up to the next 5 is 120");

        IntervalSearch ten = IntervalSearch.of(10, null);
        refuse(ten);
        assertEquals(15, ten.interval(), "a tenth of 10 is 1: R + 5");
    }

    @Test
    void itNeverGoesAboveThreeHundred() {
        IntervalSearch search = IntervalSearch.of(290, null);
        refuse(search);
        assertEquals(300, search.interval());
        refuse(search);
        assertEquals(300, search.interval());
        assertEquals(300, search.refused());
        answers(search, 8);
        assertEquals(300, search.interval(), "never below R + 5, and R + 5 is above the ceiling: it stays");
    }

    @Test
    void afterEightAnswersWithoutARefusalItIsFiveSecondsShorterButNotAsShortAsTheRefusedOnePlusFive() {
        IntervalSearch search = IntervalSearch.of(100, null);
        refuse(search);
        assertEquals(110, search.interval());

        answers(search, 7);
        assertEquals(110, search.interval());
        answers(search, 1);
        assertEquals(105, search.interval());
        answers(search, 8 * 10);
        assertEquals(105, search.interval(), "one step above the refused interval, 100, and no lower, however many answers come");

        pass(600);
        answers(search, 7);
        assertEquals(95, search.refused(), "ten minutes without a 429: the refused interval is forgotten a step");
        assertEquals(105, search.interval(), "and the interval follows by its own steps, of eight answers");
        answers(search, 1);
        assertEquals(100, search.interval());
    }

    @Test
    void aRefusalStartsTheCountOfAnswersAgain() {
        IntervalSearch search = IntervalSearch.of(100, null);
        refuse(search);
        answers(search, 7);

        refuse(search);

        assertEquals(110, search.refused());
        assertEquals(125, search.interval());
        answers(search, 7);
        assertEquals(125, search.interval());
        answers(search, 1);
        assertEquals(120, search.interval());
    }

    // ---- the refused interval is forgotten by time: 5 seconds for every ten minutes without a 429

    @Test
    void theRefusedIntervalIsNotForgottenBeforeTenMinutesHoweverManyAnswersCome() {
        IntervalSearch search = IntervalSearch.of(40, null);
        refuse(search);

        answers(search, 599);

        assertEquals(40, search.refused(), "599 answers in 599 seconds: sixty of them once forgot a step");
        assertEquals(45, search.interval(), "so the interval stays a step above it");
    }

    @Test
    void itIsForgottenAStepWithTheFirstAnswerAfterTenMinutesAndOnlyOneHoweverLongItWas() {
        IntervalSearch search = IntervalSearch.of(40, null);
        refuse(search);

        pass(599);
        search.succeeded(now);
        assertEquals(40, search.refused(), "a second short of ten minutes");
        pass(1);
        search.succeeded(now);
        assertEquals(35, search.refused(), "ten minutes: one step");

        // An hour in which no answer came, the machine asleep for one: a single step for the answer that ends it.
        pass(3600);
        search.succeeded(now);
        assertEquals(30, search.refused(), "one step for one answer, not six for the hour");
        pass(599);
        search.succeeded(now);
        assertEquals(30, search.refused(), "and the ten minutes begin again with that step");
        pass(1);
        search.succeeded(now);
        assertEquals(25, search.refused());
    }

    @Test
    void aRefusalBeginsTheTenMinutesAgain() {
        IntervalSearch search = IntervalSearch.of(40, null);
        refuse(search);
        pass(590);
        search.succeeded(now);

        refuse(search);
        assertEquals(45, search.refused(), "the interval that was refused this time");
        pass(590);
        search.succeeded(now);

        assertEquals(45, search.refused(), "590 seconds since the second 429, though 1,180 since the first");
        pass(10);
        search.succeeded(now);
        assertEquals(40, search.refused());
    }

    @Test
    void belowTenThereIsNothingLeftToRemember() {
        IntervalSearch low = IntervalSearch.of(10, null);
        refuse(low);
        assertEquals(10, low.refused());

        pass(600);
        low.succeeded(now);

        assertNull(low.refused());
        assertEquals("15 s (no refusal yet)", low.describe());
        answers(low, 1);
        assertEquals(10, low.interval(), "and the search goes down as before a refusal");
    }

    @Test
    void theTenMinutesOfASearchThatIsMadeBeginWithItsFirstAnswer() {
        // A search that goes on from what was saved, at the start of the program or when Automatic is switched on: the time
        // is not saved with it, so whatever the clock says then, nothing is forgotten with the first answer.
        pass(100_000);
        IntervalSearch search = IntervalSearch.of(200, 195);

        search.succeeded(now);
        assertEquals(195, search.refused(), "the first answer begins the ten minutes");
        pass(599);
        search.succeeded(now);
        assertEquals(195, search.refused());
        pass(1);
        search.succeeded(now);
        assertEquals(190, search.refused());
    }

    @Test
    void anIntervalOfTwoHundredSecondsWithNothingRefusedComesDownWhereItStoodStillForHours() {
        // What was seen: 200 s with the refused interval at 195, and no request failing. By the count of sixty answers the
        // refused interval was forgotten a step in three hours and twenty minutes; by the time it is in ten minutes.
        IntervalSearch search = IntervalSearch.of(200, 195);

        answersAtTheInterval(search, 8);
        assertEquals(195, search.interval(), "after eight answers, 27 minutes: a step down, since the refused interval is 185 by then");
        assertEquals(185, search.refused());

        answersAtTheInterval(search, 32);
        assertTrue(search.interval() <= 180, "and after forty answers, some two hours, it is at " + search.interval());
    }

    @Test
    void everyIntervalIsAMultipleOfFive() {
        IntervalSearch search = IntervalSearch.fresh();
        for (int i = 0; i < 3000; i++) {
            pass(search.interval());
            if (i % 37 == 0) {
                search.rateLimited(i % 3 == 0 ? 0 : 47, now);
            } else {
                search.succeeded(now);
            }
            assertEquals(0, search.interval() % 5, "interval " + search.interval());
            assertTrue(search.interval() >= 10 && search.interval() <= 300);
            assertTrue(search.refused() == null || search.refused() % 5 == 0, "refused " + search.refused());
        }
    }

    @Test
    void aWaitTheServerNamesLongerThanTheStepBecomesTheIntervalRoundedUpToFiveAtMostThreeHundred() {
        IntervalSearch search = IntervalSearch.of(10, null);
        refuse(search, 300);
        assertEquals(300, search.interval());
        assertEquals(10, search.refused(), "the interval that was refused, not the server's wait");

        IntervalSearch above = IntervalSearch.of(10, null);
        refuse(above, 3600);
        assertEquals(300, above.interval());

        IntervalSearch odd = IntervalSearch.of(10, null);
        refuse(odd, 47);
        assertEquals(50, odd.interval(), "47 seconds, rounded up to the next 5");

        IntervalSearch shorter = IntervalSearch.of(50, null);
        refuse(shorter, 20);
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

    /**
     * A server that refuses any interval shorter than a limit, as the endpoint was seen to behave, asked for the given
     * time as the program asks it: each request an interval after the one before.
     *
     * @return how many requests were refused
     */
    private int refusals(int limit, long seconds, IntervalSearch search) {
        IntPredicate refuses = interval -> interval < limit;
        int refused = 0;
        long until = now + seconds * SECOND;
        while (now < until) {
            pass(search.interval());
            if (refuses.test(search.interval())) {
                search.rateLimited(0, now);
                refused++;
            } else {
                search.succeeded(now);
            }
        }
        return refused;
    }

    @Test
    void againstAServerWithAFixedLimitItSettlesJustAboveItWithFewRefusals() {
        for (int limit : new int[] {12, 25, 47, 90, 180}) {
            IntervalSearch search = IntervalSearch.fresh();
            int first = refusals(limit, 4 * 3600, search);
            int later = refusals(limit, 24 * 3600, search);

            assertTrue(search.interval() >= limit - 5 && search.interval() <= limit + Math.max(10, limit / 8),
                    "limit " + limit + ": settled at " + search.interval());
            // Once it has settled it cannot be refused before the refused interval has been forgotten a step, which takes ten
            // minutes: fewer than six refusals an hour, whatever the interval.
            assertTrue(later < 24 * 6, "limit " + limit + ": " + later + " refusals in 24 hours (" + first + " in the first four)");
            assertTrue(later >= 1, "limit " + limit + ": it goes on trying a shorter interval");
        }
    }

    @Test
    void whenTheLimitFallsTheSearchFollowsItDown() {
        IntervalSearch search = IntervalSearch.fresh();
        refusals(60, 6 * 3600, search);
        assertTrue(search.interval() >= 50, "near the limit of 60: " + search.interval());

        refusals(20, 6 * 3600, search);

        assertTrue(search.interval() <= 30, "settled at " + search.interval());
    }

    @Test
    void aLimitThatFallsIsFollowedDownAtTheSamePaceWhateverTheInterval() {
        // From a refusal at 300 seconds the limit is gone. The refused interval is forgotten in ten minutes a step, so what
        // holds the interval is its own pace alone, a step for eight answers, which at this interval is forty minutes: six
        // steps in four hours. By the count of sixty answers, five hours for a step, it had not moved at all in that time.
        IntervalSearch search = IntervalSearch.of(295, null);
        refuse(search);
        assertEquals(300, search.interval());

        refusals(0, 4 * 3600, search);

        assertEquals(270, search.interval(), "four hours with nothing refused");
        // A step with the first answer after each ten minutes, which at these intervals is every third answer, some fifteen minutes.
        assertTrue(search.refused() == null || search.refused() <= 210, "the refused interval is far below it: " + search.refused());
    }
}
