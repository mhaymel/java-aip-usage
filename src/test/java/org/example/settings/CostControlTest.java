package org.example.settings;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostControlTest {

    @Test
    void theWeekdaysOfAMonthAreItsMondaysToFridays() {
        assertEquals(22, CostControl.weekdays(YearMonth.of(2026, 10)), "October 2026 begins on a Thursday and has 31 days");
        assertEquals(20, CostControl.weekdays(YearMonth.of(2026, 2)), "February 2026: four full weeks, beginning on a Sunday");
        assertEquals(21, CostControl.weekdays(YearMonth.of(2024, 2)), "February of a leap year, with its 29th on a Thursday");
        assertEquals(23, CostControl.weekdays(YearMonth.of(2026, 7)), "July 2026 begins on a Wednesday: the most a month can have");
        assertEquals(21, CostControl.weekdays(YearMonth.of(2026, 11)), "November 2026 begins on a Sunday");
        assertEquals(21, CostControl.weekdays(YearMonth.of(2026, 8)), "August 2026 begins on a Saturday and ends on a Monday");
    }

    @Test
    void everyMonthHasFromTwentyToTwentyThreeWeekdays() {
        for (int year = 2020; year <= 2032; year++) {
            for (int month = 1; month <= 12; month++) {
                int weekdays = CostControl.weekdays(YearMonth.of(year, month));
                assertTrue(weekdays >= 20 && weekdays <= 23, year + "-" + month + ": " + weekdays);
            }
        }
    }

    @Test
    void theDailyLimitIsTheMonthlyOneDividedByTheWeekdaysRoundedDownToTheCent() {
        assertEquals(1136, CostControl.dailyFor(25_000, YearMonth.of(2026, 10)), "250.00 / 22 = 11.3636...");
        assertEquals(1250, CostControl.dailyFor(25_000, YearMonth.of(2026, 2)), "250.00 / 20");
        assertEquals(1086, CostControl.dailyFor(25_000, YearMonth.of(2026, 7)), "250.00 / 23 = 10.869..., rounded down");
        assertEquals(0, CostControl.dailyFor(10, YearMonth.of(2026, 10)), "ten cents over 22 days is less than a cent a day");
    }

    @Test
    void theDailyLimitInForceIsWorkedOutWhileThatIsAskedForAndElseTheOneTypedIn() {
        YearMonth october = YearMonth.of(2026, 10);

        assertEquals(Optional.of(1136L), new CostControl(25_000L, null, true).dailyIn(october));
        assertEquals(Optional.of(1136L), new CostControl(25_000L, 500L, true).dailyIn(october), "what was typed waits while it is worked out");
        assertEquals(Optional.of(500L), new CostControl(25_000L, 500L, false).dailyIn(october));
        assertEquals(Optional.empty(), new CostControl(null, null, true).dailyIn(october), "no monthly limit: nothing to work it out from");
        assertEquals(Optional.empty(), new CostControl(25_000L, null, false).dailyIn(october), "not worked out, and nothing typed");
        assertEquals(Optional.empty(), CostControl.NONE.dailyIn(october));
    }

    @Test
    void limitsThatCannotBeSayWhy() {
        assertTrue(CostControl.NONE.fault().isEmpty());
        assertTrue(new CostControl(1L, 1L, false).fault().isEmpty(), "the least");
        assertTrue(new CostControl(100_000_000L, 100_000_000L, false).fault().isEmpty(), "the most, and a daily limit as large as the monthly one");
        assertEquals(CostControl.AMOUNT_FAULT, new CostControl(0L, null, true).fault().get());
        assertEquals(CostControl.AMOUNT_FAULT, new CostControl(100_000_001L, null, true).fault().get());
        assertEquals(CostControl.AMOUNT_FAULT, new CostControl(null, 0L, false).fault().get());
        assertEquals(CostControl.ORDER_FAULT, new CostControl(1_000L, 1_001L, false).fault().get());
        assertTrue(new CostControl(1_000L, 5_000L, true).fault().isEmpty(), "a typed limit that does not count is not compared");
        assertTrue(new CostControl(null, 5_000L, false).fault().isEmpty(), "with no monthly limit a daily one may be any amount in the range");
    }

    @Test
    void amountsAreWrittenWithTwoDecimalsAndReadAsTheyAreTyped() {
        assertEquals("250.00", CostControl.text(25_000));
        assertEquals("11.36", CostControl.text(1_136));
        assertEquals("0.05", CostControl.text(5));

        assertEquals(Optional.of(25_000L), CostControl.cents("250"));
        assertEquals(Optional.of(25_000L), CostControl.cents("250.00"));
        assertEquals(Optional.of(25_050L), CostControl.cents(" 250.5 "));
        assertEquals(Optional.of(1L), CostControl.cents("0.01"));
        for (String bad : new String[] {"", "abc", "-5", "1e3", "12.345", "1,000", "$250", ".5", "5.", null}) {
            assertEquals(Optional.empty(), CostControl.cents(bad), String.valueOf(bad));
        }
    }
}
