package org.example.usage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalSearchTest {

    private static String state(IntervalSearch search) {
        return search.interval() + " in " + search.lower() + ".." + search.upper();
    }

    @Test
    void itBeginsAtSixtySecondsBetweenTenAndThreeHundred() {
        assertEquals("60 in 10..300", state(IntervalSearch.fresh()));
        assertEquals("60 s (between 10 s and 300 s)", IntervalSearch.fresh().describe());
    }

    @Test
    void whileNothingIsRefusedTheIntervalIsHalvedTowardsTheLowerBoundAndTheUpperBoundFollowsIt() {
        IntervalSearch search = IntervalSearch.fresh();
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            search.succeeded();
            steps.add(state(search));
        }

        // (10 + 60) / 2 = 35, (10 + 35) / 2 = 22.5 rounded up, and so on down to the least.
        assertEquals(List.of("35 in 10..60", "23 in 10..35", "17 in 10..23", "14 in 10..17", "12 in 10..14", "11 in 10..12", "11 in 10..11", "11 in 10..11"), steps,
                "rounded up, it stops a second above the least and stays there");
    }

    @Test
    void aRefusalMakesTheIntervalTheLowerBoundAndGoesHalfWayUpToTheUpperBound() {
        IntervalSearch search = IntervalSearch.fresh();
        search.succeeded();
        search.succeeded();
        assertEquals("23 in 10..35", state(search));

        assertFalse(search.rateLimited(), "there is room above: the bounds are not set back");

        assertEquals("29 in 23..35", state(search), "(35 + 23) / 2");
        search.succeeded();
        assertEquals("26 in 23..29", state(search));
        search.succeeded();
        assertEquals("25 in 23..26", state(search));
        search.succeeded();
        assertEquals("24 in 23..25", state(search), "it settles a second above the interval that was refused");
        search.succeeded();
        assertEquals("24 in 23..24", state(search));
    }

    @Test
    void aRefusalAtTheUpperBoundOrASecondBelowItSetsTheBoundsBackAndGoesHalfWayToThreeHundred() {
        IntervalSearch at = IntervalSearch.of(24, 23, 24);
        assertTrue(at.rateLimited(), "at the upper bound");
        assertEquals("162 in 10..300", state(at), "(300 + 24) / 2, and the bounds are 10 and 300 again");

        IntervalSearch near = IntervalSearch.of(24, 20, 25);
        assertTrue(near.rateLimited(), "within a second of it");
        assertEquals("162 in 10..300", state(near));

        IntervalSearch below = IntervalSearch.of(24, 20, 26);
        assertFalse(below.rateLimited(), "two seconds below it there is still a middle to go to");
        assertEquals("25 in 24..26", state(below));
    }

    @Test
    void theVeryFirstRefusalAtSixtySecondsGoesUpWithoutSettingAnythingBack() {
        IntervalSearch search = IntervalSearch.fresh();

        assertFalse(search.rateLimited());

        assertEquals("180 in 60..300", state(search));
        assertFalse(search.rateLimited());
        assertEquals("240 in 180..300", state(search));
    }

    @Test
    void theIntervalIsNeverLessThanTenNorMoreThanThreeHundred() {
        IntervalSearch up = IntervalSearch.fresh();
        for (int i = 0; i < 40; i++) {
            up.rateLimited();
            assertTrue(up.interval() >= 10 && up.interval() <= 300, state(up));
            assertTrue(up.lower() >= 10 && up.upper() <= 300 && up.lower() <= up.upper(), state(up));
        }
        assertEquals(300, up.interval(), "refused again and again it ends at the most");

        IntervalSearch down = IntervalSearch.fresh();
        for (int i = 0; i < 40; i++) {
            down.succeeded();
            assertTrue(down.interval() >= 10 && down.interval() <= 300, state(down));
        }
    }

    @Test
    void whateverHappensTheIntervalIsWithinItsBounds() {
        IntervalSearch search = IntervalSearch.fresh();
        java.util.Random random = new java.util.Random(65);
        for (int i = 0; i < 5_000; i++) {
            if (random.nextInt(4) == 0) {
                search.rateLimited();
            } else {
                search.succeeded();
            }
            assertTrue(search.lower() <= search.interval() && search.interval() <= search.upper(), i + ": " + state(search));
            assertTrue(search.lower() >= 10 && search.upper() <= 300, i + ": " + state(search));
        }
    }

    @Test
    void aSavedSearchGoesOnFromWhereItWasAndFiguresThatCannotBeOneBeginItAnew() {
        assertEquals("29 in 23..35", state(IntervalSearch.of(29, 23, 35)));
        assertEquals("10 in 10..10", state(IntervalSearch.of(10, 10, 10)));
        assertEquals("300 in 10..300", state(IntervalSearch.of(300, 10, 300)));

        for (int[] bad : new int[][] {{60, 9, 300}, {60, 10, 301}, {5, 10, 300}, {61, 10, 60}, {20, 30, 300}, {60, 80, 70}, {0, 0, 0}}) {
            assertEquals("60 in 10..300", state(IntervalSearch.of(bad[0], bad[1], bad[2])), bad[0] + ", " + bad[1] + ", " + bad[2]);
        }
    }
}
