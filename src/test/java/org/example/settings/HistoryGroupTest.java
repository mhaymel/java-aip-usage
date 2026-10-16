package org.example.settings;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryGroupTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 14, 23, 41);

    @Test
    void anIntervalBeginsOnTheClock() {
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 20), HistoryGroup.MIN5.start(AT));
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 15), HistoryGroup.MIN15.start(AT));
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), HistoryGroup.MIN30.start(AT));
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), HistoryGroup.HOUR.start(AT));
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), HistoryGroup.DAY.start(AT));
        assertEquals(AT, HistoryGroup.NONE.start(AT), "not grouped, a reading is its own");
    }

    @Test
    void aWeekBeginsOnItsMondayAcrossAMonthAndAYear() {
        assertEquals(LocalDateTime.of(2026, 10, 12, 0, 0), HistoryGroup.WEEK.start(AT), "the 16th is a Friday");
        assertEquals(LocalDateTime.of(2026, 9, 28, 0, 0), HistoryGroup.WEEK.start(LocalDateTime.of(2026, 10, 2, 9, 0)));
        assertEquals(LocalDateTime.of(2025, 12, 29, 0, 0), HistoryGroup.WEEK.start(LocalDateTime.of(2026, 1, 1, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 12, 0, 0), HistoryGroup.WEEK.start(LocalDateTime.of(2026, 10, 12, 0, 0)), "a Monday is its own");
    }

    @Test
    void aMonthBeginsOnItsFirstDay() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), HistoryGroup.MONTH.start(AT));
    }

    @Test
    void theKeysAreThoseOfTheFile() {
        assertEquals(Optional.of(HistoryGroup.MIN15), HistoryGroup.of("15m"));
        assertEquals(Optional.of(HistoryGroup.MONTH), HistoryGroup.of("month"));
        assertTrue(HistoryGroup.isKey("1w"));
        assertFalse(HistoryGroup.isKey("2h"));
        assertFalse(HistoryGroup.isKey(null));
        assertTrue(HistoryGroup.HOUR.withinADay());
        assertFalse(HistoryGroup.DAY.withinADay());
    }

    @Test
    void aGroupThatIsNoneOfThemIsNone() {
        assertEquals("none", Settings.defaults().historyGroup());
        assertEquals("none", Settings.defaults().withHistoryGroup("2h").historyGroup());
        assertEquals("1d", Settings.defaults().withHistoryGroup("1d").historyGroup());
    }
}
