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

class BurnupTest {

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

    private static Burnup.View build(List<List<String>> rows) {
        return Burnup.build(rows, rows, LIMITS, false, NOON);
    }

    @Test
    void withNoReadingOrNoLimitThereIsNothingToDrawAndANoteSaysWhy() {
        Burnup.View none = build(List.of());
        assertTrue(none.noLimit());
        assertEquals("No usage has been read yet.", none.note());
        assertNull(none.top());
        assertFalse(none.forecastOver());

        List<List<String>> history = rows("2026-10-13 12:00:00", "100.00");
        List<List<String>> noServerLimit = List.of(List.of("2026-10-13 12:00:00", "100.00", "", "USD", "", "60", "400"));
        Burnup.View unlimited = Burnup.build(noServerLimit, noServerLimit, CostControl.NONE, false, NOON);
        assertTrue(unlimited.noLimit());
        assertEquals("No limit is set: set it in the settings, under Cost control.", unlimited.note());
        assertTrue(unlimited.points().isEmpty());

        Burnup.View server = Burnup.build(history, history, CostControl.NONE, false, NOON);
        assertFalse(server.noLimit(), "with none typed the server's limit is counted");
        assertEquals("1,000.00", server.limit());
    }

    @Test
    void thePointsRiseFromZeroAtTheStartOfTheMonthOneForTheLastReadingOfEachDay() {
        Burnup.View view = build(rows(
                "2026-09-30 17:00:00", "990.00",
                "2026-10-01 09:00:00", "10.00",
                "2026-10-01 17:00:00", "50.00",
                "2026-10-03 12:00:00", "140.00",
                "2026-10-14 08:00:00", "300.00",
                "2026-10-14 11:30:00", "320.50"));

        assertEquals(31, view.days());
        assertEquals(1000.0, view.limitValue());
        List<Burnup.Point> points = view.points();
        assertEquals(5, points.size(), "the start of the month, Oct 1, 3, the crossing of the ideal line, and Oct 14; "
                + "September is not this month and Oct 2 has no reading");
        assertEquals(new Burnup.Point(0, 0, null), points.get(0), "0 at the start of the 1st, no dot");
        assertEquals(17 / 24.0, points.get(1).x(), 1e-9, "Oct 1 at 17:00, its last reading");
        assertEquals(50.0, points.get(1).used());
        assertEquals("Oct 1: 50.00 used", points.get(1).tooltip());
        assertEquals("Oct 3: 140.00 used", points.get(2).tooltip());
        assertNull(points.get(3).tooltip(), "the crossing is no reading");
        assertEquals(13.5 - 0.5 / 24, points.get(4).x(), 1e-9, "today's point is the newest reading, at 11:30");
        assertEquals("Oct 14: 320.50 used", points.get(4).tooltip());
        assertEquals(List.of("0", "500", "1k"), view.yLabels().stream().map(Burnup.Label::text).toList());
    }

    @Test
    void aPointIsAboveWhereItsAmountIsMoreThanTheIdealLineAndACrossingHasAPointOfItsOwn() {
        // On Oct 3 at noon 2.5 days are gone: the ideal line stands at 1,000.00 × 2.5 ÷ 31 = 80.65.
        Burnup.View view = build(rows("2026-10-03 12:00:00", "140.00", "2026-10-14 12:00:00", "300.00"));

        List<Burnup.Point> points = view.points();
        assertFalse(points.get(0).above(), "the start of the month is on the line");
        assertTrue(points.get(1).above(), "140.00 is above 80.65");
        Burnup.Point crossing = points.get(2);
        assertNull(crossing.tooltip());
        assertFalse(crossing.above());
        assertEquals(1000.0 * crossing.x() / 31, crossing.used(), 1e-6, "the crossing lies on the ideal line");
        assertTrue(crossing.x() > 2.5 && crossing.x() < 13.5);
        assertFalse(points.get(3).above(), "300.00 is below 435.48");
        assertEquals(4, points.size());
        assertTrue(view.forecast().stream().noneMatch(Burnup.Point::above), "the forecast has its own colour");

        // Above throughout: no crossing.
        Burnup.View high = build(rows("2026-10-03 12:00:00", "140.00", "2026-10-14 12:00:00", "600.00"));
        assertEquals(3, high.points().size());
        assertTrue(high.points().get(2).above());

        // A point exactly on the line is not above it, and a piece from it needs no crossing.
        List<Burnup.Point> onTheLine = Burnup.againstIdeal(List.of(new Burnup.Point(0, 0, null),
                new Burnup.Point(15.5, 500, "on"), new Burnup.Point(21, 900, "over")), 1000, 31);
        assertEquals(List.of(false, false, true), onTheLine.stream().map(Burnup.Point::above).toList());
    }

    @Test
    void theLabelsAreShortWithTheSymbolBeforeThemAndTheTooltipsInFull() {
        List<List<String>> history = rows("2026-10-14 12:00:00", "135.00");
        CostControl odd = new CostControl(123_456L, null, true);

        Burnup.View view = Burnup.build(history, history, odd, true, NOON);

        assertEquals(List.of("$0", "$617", "$1.2k"), view.yLabels().stream().map(Burnup.Label::text).toList());
        assertEquals("$1,234.56", view.limit());
        assertEquals("Oct 14: $135.00 used", view.points().get(1).tooltip());
    }

    @Test
    void theForecastGoesOnAtTheMonthsAverageDailySpendToTheEndOfTheMonth() {
        // 135.00 in 13.5 days is 10.00 a day: 310.00 at the end of the month's 31 days.
        Burnup.View view = build(rows("2026-10-14 12:00:00", "135.00"));

        List<Burnup.Point> forecast = view.forecast();
        assertEquals(2, forecast.size());
        assertEquals(13.5, forecast.get(0).x(), 1e-9, "from the last point");
        assertEquals(135.0, forecast.get(0).used());
        assertEquals(31.0, forecast.get(1).x());
        assertEquals(310.0, forecast.get(1).used(), 1e-9);
        assertEquals("Forecast for Oct 31: 310.00 used", forecast.get(1).tooltip());
        assertFalse(view.forecastOver());
        assertEquals(1000.0, view.top(), "the limit, when nothing is higher");
    }

    @Test
    void theForecastPassesTheLimitAndIsOverThenButNotWhenItEndsOnIt() {
        // 675.00 in 13.5 days is 50.00 a day: 1,550.00 at the end of the month, past the limit, with no stop at it.
        Burnup.View running = build(rows("2026-10-14 12:00:00", "675.00"));
        List<Burnup.Point> forecast = running.forecast();
        assertEquals(2, forecast.size(), "no bend at the limit");
        assertEquals(1550.0, forecast.get(1).used(), 1e-9);
        assertEquals("Forecast for Oct 31: 1,550.00 used", forecast.get(1).tooltip());
        assertTrue(running.forecastOver());
        assertEquals(1550.0, running.top(), "the axis grows to the forecast's end");

        // The same month with a limit of 1,550.00: a forecast that ends exactly on the limit is not over it.
        CostControl at1550 = new CostControl(155_000L, null, true);
        Burnup.View onTheLimit = Burnup.build(rows("2026-10-14 12:00:00", "675.00"), rows("2026-10-14 12:00:00", "675.00"),
                at1550, false, NOON);
        assertEquals(1550.0, onTheLimit.forecast().get(1).used(), 1e-9);
        assertFalse(onTheLimit.forecastOver(), "ending at the limit is green");
        assertEquals(1550.0, onTheLimit.top());
    }

    @Test
    void aMonthOverItsLimitIsNotHeldAtItAndTheTopIsTheHighestPoint() {
        // 1,200.00 used on Oct 14 at noon of a month of 1,000.00; the forecast is higher still.
        Burnup.View over = build(rows("2026-10-14 12:00:00", "1200.00"));
        assertEquals(1200.0, over.points().get(1).used(), "over the limit is drawn over it");
        assertEquals("Oct 14: 1,200.00 used", over.points().get(1).tooltip());
        assertTrue(over.points().get(1).above());
        assertTrue(over.forecastOver());
        assertEquals(over.forecast().get(1).used(), over.top(), 1e-9);

        // A point higher than the forecast's end: no reading of today, a fall in the amount since.
        Burnup.View fallen = build(rows("2026-10-05 12:00:00", "1100.00", "2026-10-14 12:00:00", "10.00"));
        assertEquals(1100.0, fallen.top(), "the highest point");
        assertFalse(fallen.forecastOver());
    }

    @Test
    void theRowsShownGiveThePointsAndTheNewestOfAllTheServersLimit() {
        // The history hides a reading, as its filters or its grouping do: it gives no point.
        List<List<String>> all = rows("2026-10-05 12:00:00", "100.00", "2026-10-06 12:00:00", "100.00");
        List<List<String>> shown = rows("2026-10-05 12:00:00", "100.00");

        Burnup.View view = Burnup.build(shown, all, CostControl.NONE, false, NOON);

        assertEquals(2, view.points().size());
        assertEquals("Oct 5: 100.00 used", view.points().get(1).tooltip());
    }

    @Test
    void theMonthHasItsOwnNumberOfDaysAndTheCurrencySymbolWhenItIsOn() {
        LocalDateTime february = LocalDateTime.of(2027, 2, 10, 12, 0);
        List<List<String>> history = rows("2027-02-10 12:00:00", "100.00");

        Burnup.View view = Burnup.build(history, history, LIMITS, true, february);

        assertEquals(28, view.days());
        assertEquals(9.5, view.points().get(1).x(), 1e-9);
        assertEquals("Feb 10: $100.00 used", view.points().get(1).tooltip());
        assertEquals("$1,000.00", view.limit());
        assertEquals(28.0, view.forecast().get(1).x());
    }

    @Test
    void itIsWrittenAsTheApiSays() {
        Json json = build(rows("2026-10-14 12:00:00", "135.00")).toJson();

        assertEquals(31, json.get("days").intValue());
        assertFalse(json.get("noLimit").bool());
        assertEquals("1,000.00", json.get("limit").text());
        assertEquals(1000.0, json.get("top").doubleValue());
        assertTrue(json.get("note").isNull());
        assertEquals(3, json.get("yLabels").size());
        assertEquals(2, json.get("points").size());
        assertEquals(13.5, json.at("/points/1/x").doubleValue());
        assertEquals(135.0, json.at("/points/1/used").doubleValue());
        assertEquals("Oct 14: 135.00 used", json.at("/points/1/tooltip").text());
        assertFalse(json.at("/points/1/above").bool(), "135.00 is below the ideal line's 435.48");
        assertEquals("1k", json.at("/yLabels/2/text").text());
        assertEquals(2, json.get("forecast").size());
        assertFalse(json.get("forecastOver").bool());
    }
}
