package org.example;

import org.example.settings.CostControl;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpendingTest {

    /** Wednesday, 14 October 2026: October has 22 weekdays, and this is the tenth. */
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 14, 12, 0);

    private static List<String> row(String time, String used) {
        return List.of(time, used, "1000.00", "USD", "", "60", "400");
    }

    /** Rows newest first, as the history gives them. */
    private static List<List<String>> rows(String... timesAndAmounts) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = timesAndAmounts.length - 2; i >= 0; i -= 2) {
            rows.add(row(timesAndAmounts[i], timesAndAmounts[i + 1]));
        }
        return rows;
    }

    /** 220.00 a month: 10.00 a day over October's 22 weekdays. */
    private static final CostControl LIMITS = new CostControl(22_000L, null, true);

    @Test
    void theDayBeginsAtItsFirstRiseAndIsProjectedOverTheWorkingDay() {
        // Yesterday 50.00; today unchanged at 08:00, then a rise at 09:00, and 3.00 spent by 12:00: 1.00 an hour, 8.00 by the end.
        List<List<String>> history = rows("2026-10-13 18:00:00", "50.00", "2026-10-14 08:00:00", "50.00", "2026-10-14 09:00:00", "50.50",
                "2026-10-14 12:00:00", "53.00");
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), Spending.dayStart(history, NOON.toLocalDate()));
        Spending.Result result = Spending.compute(history, LIMITS, NOON);
        assertEquals(Spending.GREEN, result.day().state(), "8.00 of 10.00");

        // 6.00 by 12:00 is 2.00 an hour: 16.00 by the end, more than 10.00.
        List<List<String>> faster = rows("2026-10-13 18:00:00", "50.00", "2026-10-14 09:00:00", "51.00", "2026-10-14 12:00:00", "56.00");
        Spending.Result amber = Spending.compute(faster, LIMITS, NOON);
        assertEquals(Spending.AMBER, amber.day().state());
        assertEquals("Today: on course to pass the daily limit: 6.00 spent, about 16.00 by the end of the day, of 10.00", amber.day().line());
        assertEquals(Spending.AMBER, amber.state());
    }

    @Test
    void noProjectionInTheFirstFifteenMinutesBeforeARiseOnAWeekendOrOnceTheDayIsOver() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 9, 0);
        assertNull(Spending.projection(500, start, 8, start.plusMinutes(14)));
        assertEquals(Long.valueOf(16_000), Spending.projection(500, start, 8, start.plusMinutes(15)), "5.00 in 15 minutes is 160.00 in 8 hours");
        assertNull(Spending.projection(500, null, 8, NOON), "no rise yet");
        assertNull(Spending.projection(500, LocalDateTime.of(2026, 10, 17, 9, 0), 8, LocalDateTime.of(2026, 10, 17, 12, 0)), "a Saturday");
        assertEquals(Long.valueOf(500), Spending.projection(500, start, 8, start.plusHours(9)), "the working day is over: what was spent");
        assertEquals(Long.valueOf(1_000), Spending.projection(500, start, 4, start.plusHours(2)), "four hours a day: twice what was spent by half");
    }

    @Test
    void overTheDailyLimitIsRed() {
        Spending.Result result = Spending.compute(rows("2026-10-13 18:00:00", "50.00", "2026-10-14 09:00:00", "61.00"), LIMITS, NOON);
        assertEquals(Spending.RED, result.day().state());
        assertEquals("Today: over the daily limit: 11.00 of 10.00", result.day().line());
        assertEquals(Spending.RED, result.state());
    }

    @Test
    void theMonthIsAheadOfItsPaceWhenMoreIsUsedThanTheDaysBegunAllow() {
        // The tenth weekday of 22: 100.00 of 220.00 by now.
        assertEquals(10_000, Spending.pace(22_000, LIMITS, NOON.toLocalDate()));
        Spending.Result ahead = Spending.compute(rows("2026-10-14 09:00:00", "120.00"), LIMITS, NOON);
        assertEquals(Spending.AMBER, ahead.month().state());
        assertEquals("Month: ahead of pace: 120.00 spent, 100.00 by now, of 220.00", ahead.month().line());
        Spending.Result on = Spending.compute(rows("2026-10-14 09:00:00", "90.00"), LIMITS, NOON);
        assertEquals("Month: within the limit and on pace", on.month().line());
        Spending.Result over = Spending.compute(rows("2026-10-14 09:00:00", "230.00"), LIMITS, NOON);
        assertEquals("Month: over the monthly limit: 230.00 of 220.00", over.month().line());
    }

    @Test
    void typedWorkingDaysCountAndTheDaysBegunAreNoMoreThanThem() {
        CostControl eight = LIMITS.withCounting(CostControl.MONTHLY, 8, 8, "2026-10");
        assertEquals(22_000, Spending.pace(22_000, eight, NOON.toLocalDate()), "ten weekdays begun, but only eight to work");
        assertEquals(22_000 * 5 / 8, Spending.pace(22_000, eight, LocalDate.of(2026, 10, 7)));
    }

    @Test
    void theCircleIsTheWorseOfTheTwoAndGrayWithNoLimits() {
        Spending.Result none = Spending.compute(rows("2026-10-14 09:00:00", "120.00").stream()
                .map(r -> List.of(r.get(0), r.get(1), "", "USD", "", "60", "400")).toList(), CostControl.NONE, NOON);
        assertEquals(Spending.GRAY, none.state());
        assertEquals(Spending.NO_LIMITS, none.tooltip());
        Spending.Result green = Spending.compute(rows("2026-10-13 18:00:00", "50.00", "2026-10-14 12:00:00", "50.00"), LIMITS, NOON);
        assertEquals(Spending.GREEN, green.state());
        assertEquals("Today: within the limit\nMonth: within the limit and on pace", green.tooltip());
        assertEquals(Spending.GRAY, Spending.compute(List.of(), LIMITS, NOON).state(), "no reading yet");
    }

    @Test
    void theServersLimitIsCountedWhenNoneIsTyped() {
        Spending.Result result = Spending.compute(rows("2026-10-14 09:00:00", "120.00"), CostControl.NONE, NOON);
        assertEquals(Long.valueOf(100_000), result.month().limitCents(), "1,000.00 from the server");
        assertEquals("12%", result.month().toJson().get("percent").asText());
    }
}
