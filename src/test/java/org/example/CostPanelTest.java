package org.example;

import org.example.settings.CostControl;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostPanelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    /** A row of the history; the newest comes first in the lists below. */
    private static List<String> row(String time, String used) {
        return List.of(time, used, used.isEmpty() ? "" : "1000.00", used.isEmpty() ? "" : "USD", used.isEmpty() ? "failed" : "", "60", "400");
    }

    private static final CostControl LIMITS = new CostControl(100_000L, 5_000L, false);

    private static CostPanel.View build(CostControl limits, boolean symbol, List<String>... newestFirst) {
        return CostPanel.build(List.of(newestFirst), limits, symbol, TODAY);
    }

    @SuppressWarnings("unchecked")
    @Test
    void todayIsCountedFromTheLastReadingBeforeTodayInTheSameMonth() {
        CostPanel.View view = build(LIMITS, false,
                row("2026-10-12 10:00:00", "262.50"), row("2026-10-12 08:00:00", "255.00"),
                row("2026-10-11 23:00:00", "250.00"), row("2026-10-10 12:00:00", "240.00"));

        assertEquals("Today", view.rows().get(0).label());
        assertEquals("12.50 of 50.00 · 25%", view.rows().get(0).text(), "262.50 less the 250.00 of yesterday's last reading");
        assertEquals(0.25, view.rows().get(0).fraction(), 0.0001);
        assertEquals("This month", view.rows().get(1).label());
        assertEquals("262.50 of 1,000.00 · 26%", view.rows().get(1).text(), "26.25 rounds to 26");
        assertNull(view.note());
    }

    @SuppressWarnings("unchecked")
    @Test
    void withNoReadingBeforeTodayInTheMonthItIsCountedFromTheFirstReadingOfToday() {
        CostPanel.View view = build(LIMITS, false,
                row("2026-10-12 10:00:00", "262.50"), row("2026-10-12 08:00:00", "260.00"), row("2026-09-30 22:00:00", "900.00"));

        assertEquals("2.50 of 50.00 · 5%", view.rows().get(0).text(), "the reading of September is another month's");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFallOfTheAmountIsNoSpendingAndAProgramStartedTodayWithOneReadingHasSpentNothing() {
        assertEquals("0.00 of 50.00 · 0%",
                build(LIMITS, false, row("2026-10-12 10:00:00", "5.00"), row("2026-10-11 10:00:00", "9.00")).rows().get(0).text());
        assertEquals("0.00 of 50.00 · 0%", build(LIMITS, false, row("2026-10-12 10:00:00", "5.00")).rows().get(0).text());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewestReadingFromAnEarlierDayHasSpentNothingToday() {
        CostPanel.View view = build(LIMITS, false, row("2026-10-09 10:00:00", "300.00"), row("2026-10-08 10:00:00", "290.00"));

        assertEquals("0.00 of 50.00 · 0%", view.rows().get(0).text());
        assertEquals("300.00 of 1,000.00 · 30%", view.rows().get(1).text());
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedRowsArePassedOver() {
        CostPanel.View view = build(LIMITS, false,
                row("2026-10-12 11:00:00", ""), row("2026-10-12 10:00:00", "262.50"), row("2026-10-11 23:00:00", "250.00"));

        assertEquals("12.50 of 50.00 · 25%", view.rows().get(0).text());
    }

    @SuppressWarnings("unchecked")
    @Test
    void noLimitIsAGrayEmptyBarWithTheAmountAndNoPercentage() {
        CostPanel.View view = build(CostControl.NONE, false, row("2026-10-12 10:00:00", "262.50"), row("2026-10-11 23:00:00", "250.00"));

        for (CostPanel.Row row : view.rows()) {
            assertTrue(row.noLimit());
            assertEquals(0.0, row.fraction());
            assertFalse(row.over());
        }
        assertEquals("12.50 · no limit set", view.rows().get(0).text());
        assertEquals("This month: 262.50, no limit set", view.rows().get(1).tooltip());
    }

    @SuppressWarnings("unchecked")
    @Test
    void overTheLimitTheBarIsFullAndTheTrueShareIsWritten() {
        CostPanel.View view = build(new CostControl(20_000L, 5_000L, false), false, row("2026-10-12 10:00:00", "224.00"));

        CostPanel.Row month = view.rows().get(1);
        assertEquals("224.00 of 200.00 · 112%", month.text());
        assertEquals(1.0, month.fraction());
        assertTrue(month.over());
        assertEquals("This month: 224.00 of 200.00 (112%)", month.tooltip());
        assertFalse(view.rows().get(0).over());
    }

    @SuppressWarnings("unchecked")
    @Test
    void exactlyTheLimitIsNotOver() {
        CostPanel.Row month = build(new CostControl(20_000L, null, true), false, row("2026-10-12 10:00:00", "200.00")).rows().get(1);

        assertEquals("200.00 of 200.00 · 100%", month.text());
        assertFalse(month.over());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theDailyLimitMayBeWorkedOutFromTheMonthlyOne() {
        // October 2026 has 22 weekdays: 1,000.00 / 22 = 45.45.
        CostPanel.View view = build(new CostControl(100_000L, null, true), false, row("2026-10-12 10:00:00", "260.00"), row("2026-10-11 10:00:00", "250.00"));

        assertEquals("10.00 of 45.45 · 22%", view.rows().get(0).text());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theCurrencySymbolFollowsTheSetting() {
        List<String> euro = List.of("2026-10-12 10:00:00", "12.00", "100.00", "EUR", "", "60", "400");

        assertEquals("EUR 12.00 of EUR 1,000.00 · 1%", build(new CostControl(100_000L, null, true), true, euro).rows().get(1).text());
        assertEquals("$262.50 of $1,000.00 · 26%", build(LIMITS, true, row("2026-10-12 10:00:00", "262.50")).rows().get(1).text());
        assertEquals("262.50 of 1,000.00 · 26%", build(LIMITS, false, row("2026-10-12 10:00:00", "262.50")).rows().get(1).text());
    }

    @SuppressWarnings("unchecked")
    @Test
    void withNoReadingBothBarsAreEmptyAndANoteSaysSo() {
        CostPanel.View view = build(LIMITS, false, row("2026-10-12 10:00:00", ""));

        assertEquals(CostPanel.NO_READING, view.note());
        assertEquals(List.of("Today", "This month"), view.rows().stream().map(CostPanel.Row::label).toList());
        assertTrue(view.rows().stream().allMatch(CostPanel.Row::noLimit));
        assertEquals(CostPanel.NO_READING, CostPanel.build(List.of(), LIMITS, false, TODAY).note());
    }
}
