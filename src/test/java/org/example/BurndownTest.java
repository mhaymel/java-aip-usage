package org.example;

import org.example.json.Json;
import org.example.settings.CostControl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurndownTest {

    /** Wednesday, 14 October 2026, at noon: 13.5 days of October's 31 are gone, and 17.5 are left. */
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 14, 12, 0);

    /** 1,000.00 a month, typed. */
    private static final CostControl LIMITS = new CostControl(100_000L, null, true);

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

    private static Burndown.View build(List<List<String>> rows) {
        return Burndown.build(rows, rows, LIMITS, false, NOON);
    }

    @Test
    void withNoReadingOrNoLimitThereIsNothingToDrawAndANoteSaysWhy() {
        Burndown.View none = build(List.of());
        assertTrue(none.noLimit());
        assertEquals("No usage has been read yet.", none.note());

        List<List<String>> history = rows("2026-10-13 12:00:00", "100.00");
        List<List<String>> noServerLimit = List.of(List.of("2026-10-13 12:00:00", "100.00", "", "USD", "", "60", "400"));
        Burndown.View unlimited = Burndown.build(noServerLimit, noServerLimit, CostControl.NONE, false, NOON);
        assertTrue(unlimited.noLimit());
        assertEquals("No limit is set: set it in the settings, under Cost control.", unlimited.note());
        assertTrue(unlimited.points().isEmpty());

        Burndown.View server = Burndown.build(history, history, CostControl.NONE, false, NOON);
        assertFalse(server.noLimit(), "with none typed the server's limit is counted");
        assertEquals("1,000.00", server.limit());
    }

    @Test
    void thePointsAreTheLastReadingOfEachDayAfterTheLimitAtTheStartOfTheMonth() {
        Burndown.View view = build(rows(
                "2026-09-30 17:00:00", "990.00",
                "2026-10-01 09:00:00", "10.00",
                "2026-10-01 17:00:00", "50.00",
                "2026-10-03 12:00:00", "140.00",
                "2026-10-14 08:00:00", "300.00",
                "2026-10-14 11:30:00", "320.50"));

        assertEquals(31, view.days());
        assertEquals(1000.0, view.limitValue());
        List<Burndown.Point> points = view.points();
        assertEquals(4, points.size(), "the start of the month, and Oct 1, 3 and 14; September is not this month and Oct 2 has no reading");
        assertEquals(new Burndown.Point(31, 1000.0, null), points.get(0), "the limit at the start of the 1st, no dot");
        assertEquals(31 - 17 / 24.0, points.get(1).x(), 1e-9, "Oct 1 at 17:00, its last reading");
        assertEquals(950.0, points.get(1).credit());
        assertEquals("Oct 1: 950.00 left", points.get(1).tooltip());
        assertEquals("Oct 3: 860.00 left", points.get(2).tooltip());
        assertEquals(17.5 + 0.5 / 24, points.get(3).x(), 1e-9, "today's point is the newest reading, at 11:30");
        assertEquals("Oct 14: 679.50 left", points.get(3).tooltip());
        assertEquals(List.of("0.00", "500.00", "1,000.00"), view.yLabels().stream().map(Burndown.Label::text).toList());
    }

    @Test
    void theForecastGoesOnAtTheMonthsAverageDailySpendToTheEndOfTheMonth() {
        // 135.00 in 13.5 days is 10.00 a day; 17.5 days left take 175.00 of the 865.00 left.
        Burndown.View view = build(rows("2026-10-14 12:00:00", "135.00"));

        List<Burndown.Point> forecast = view.forecast();
        assertEquals(2, forecast.size());
        assertEquals(17.5, forecast.get(0).x(), 1e-9, "from the last point");
        assertEquals(865.0, forecast.get(0).credit());
        assertEquals(0.0, forecast.get(1).x());
        assertEquals(690.0, forecast.get(1).credit(), 1e-9);
        assertEquals("Forecast for Oct 31: 690.00 left", forecast.get(1).tooltip());
    }

    @Test
    void creditNeverGoesBelowZeroAndAForecastThatRunsOutMeetsZeroAndStaysThere() {
        // 675.00 in 13.5 days is 50.00 a day: the 325.00 left last 6.5 days, until 11 days are left.
        Burndown.View running = build(rows("2026-10-14 12:00:00", "675.00"));
        List<Burndown.Point> forecast = running.forecast();
        assertEquals(3, forecast.size());
        assertEquals(11.0, forecast.get(1).x(), 1e-9);
        assertEquals(0.0, forecast.get(1).credit());
        assertNull(forecast.get(1).tooltip());
        assertEquals("Forecast for Oct 31: 0.00 left", forecast.get(2).tooltip());

        Burndown.View over = build(rows("2026-10-14 12:00:00", "1200.00"));
        assertEquals(0.0, over.points().get(1).credit(), "over the limit is drawn as 0");
        assertEquals("Oct 14: 0.00 left", over.points().get(1).tooltip());
        assertEquals(2, over.forecast().size());
        assertEquals(0.0, over.forecast().get(1).credit());
    }

    @Test
    void theRowsShownGiveThePointsAndTheNewestOfAllTheServersLimit() {
        // The history hides a reading, as its filters or its grouping do: it gives no point.
        List<List<String>> all = rows("2026-10-05 12:00:00", "100.00", "2026-10-06 12:00:00", "100.00");
        List<List<String>> shown = rows("2026-10-05 12:00:00", "100.00");

        Burndown.View view = Burndown.build(shown, all, CostControl.NONE, false, NOON);

        assertEquals(2, view.points().size());
        assertEquals("Oct 5: 900.00 left", view.points().get(1).tooltip());
    }

    @Test
    void theMonthHasItsOwnNumberOfDaysAndTheCurrencySymbolWhenItIsOn() {
        LocalDateTime february = LocalDateTime.of(2027, 2, 10, 12, 0);
        List<List<String>> history = rows("2027-02-10 12:00:00", "100.00");

        Burndown.View view = Burndown.build(history, history, LIMITS, true, february);

        assertEquals(28, view.days());
        assertEquals(28 - 9.5, view.points().get(1).x(), 1e-9);
        assertEquals("Feb 10: $900.00 left", view.points().get(1).tooltip());
        assertEquals("$1,000.00", view.limit());
    }

    @Test
    void itIsWrittenAsTheApiSays() {
        Json json = build(rows("2026-10-14 12:00:00", "135.00")).toJson();

        assertEquals(31, json.get("days").intValue());
        assertFalse(json.get("noLimit").bool());
        assertEquals("1,000.00", json.get("limit").text());
        assertTrue(json.get("note").isNull());
        assertEquals(3, json.get("yLabels").size());
        assertEquals(2, json.get("points").size());
        assertEquals(17.5, json.at("/points/1/x").doubleValue());
        assertEquals("Oct 14: 865.00 left", json.at("/points/1/tooltip").text());
        assertEquals(2, json.get("forecast").size());
    }
}
