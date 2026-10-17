package org.example;

import org.example.json.Json;
import org.example.money.Amount;
import org.example.settings.CostControl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The burnup of the month's spending (since 0.100; from 0.97 to 0.99 the burndown of the month's credit), finished: the page
 * only draws it. The x-axis is the days gone in the month, from 0 at its start to the month's length at its end; the y-axis is
 * the amount used, not held at the limit. One point a day, the last reading of the day, with 0 at the start of the 1st before
 * them; and a forecast from the last point to the end of the month at the month's average daily spend. Pure arithmetic on the
 * rows of the history, with no clock of its own, so that it can be tested for any day. See {@code docs/api.md}, under
 * {@code GET /api/burnup}.
 */
final class Burnup {

    static final String NO_LIMIT = "No limit is set: set it in the settings, under Cost control.";

    static final String NO_READING = "No usage has been read yet.";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private static final double SECONDS_A_DAY = 24 * 60 * 60;

    /** Less than a cent's hundredth: a point on the ideal line, as the start of the month is, is not above it. */
    private static final double EPSILON = 1e-4;

    private Burnup() {
    }

    /**
     * A point of the chart.
     *
     * @param x the days gone in the month at its time, 0 at the start of the month
     * @param used the amount used, never below 0 and not held at the limit
     * @param tooltip what hovering over it says, or null for a point that is no reading (the start of the month, the start of
     *     the forecast, a crossing of the ideal line)
     * @param above whether the amount used is above the ideal line at {@code x}, which the page draws red (since 0.100; from
     *     0.99 the burndown's {@code below}); never for the forecast
     */
    record Point(double x, double used, String tooltip, boolean above) {

        Point(double x, double used, String tooltip) {
            this(x, used, tooltip, false);
        }

        Json toJson() {
            return Json.object().put("x", round(x)).put("used", used).put("tooltip", tooltip).put("above", above);
        }

        private static double round(double value) {
            return Math.round(value * 10_000) / 10_000.0;
        }
    }

    /** A label of the y-axis: where it stands, and what it says. */
    record Label(double value, String text) {

        Json toJson() {
            return Json.object().put("value", value).put("text", text);
        }
    }

    /**
     * @param days the days of the month
     * @param noLimit whether there is no limit, and so nothing to draw
     * @param limit the limit counted, as written, or null with none
     * @param top the top of the y-axis: the largest of the limit, the points and the forecast's end, or null with no limit
     * @param note what the panel says in the place of the chart, or null
     * @param points the actual line, the start of the month first
     * @param forecast the forecast, from the last point to the end of the month; empty with no reading of the month
     * @param forecastOver whether the forecast ends above the limit, which the page draws orange, else green
     */
    record View(int days, boolean noLimit, String limit, Double limitValue, Double top, String note, List<Label> yLabels,
            List<Point> points, List<Point> forecast, boolean forecastOver) {

        Json toJson() {
            Json labels = Json.array();
            yLabels.forEach(label -> labels.add(label.toJson()));
            Json line = Json.array();
            points.forEach(point -> line.add(point.toJson()));
            Json ahead = Json.array();
            forecast.forEach(point -> ahead.add(point.toJson()));
            return Json.object()
                    .put("days", days)
                    .put("noLimit", noLimit)
                    .put("limit", limit)
                    .put("limitValue", limitValue)
                    .put("top", top)
                    .put("note", note)
                    .put("yLabels", labels)
                    .put("points", line)
                    .put("forecast", ahead)
                    .put("forecastOver", forecastOver);
        }
    }

    /**
     * @param shown the rows the history shows, newest first, as {@code HistoryReader} gives them: its filters and its grouping applied
     * @param all every row of the history, newest first: the newest reading of all says the server's limit
     * @param cost the settings of cost control
     * @param withSymbol whether the amounts have the currency symbol before them
     * @param now the local date and time
     */
    static View build(List<List<String>> shown, List<List<String>> all, CostControl cost, boolean withSymbol, LocalDateTime now) {
        YearMonth month = YearMonth.from(now);
        int days = month.lengthOfMonth();
        List<List<String>> readings = all.stream().filter(row -> CostPanel.amount(row) != null && CostPanel.time(row) != null).toList();
        if (readings.isEmpty()) {
            return empty(days, NO_READING);
        }
        List<String> newest = readings.get(0);
        Optional<Long> counted = cost.monthlyCounted(CostPanel.serverLimit(newest));
        if (counted.isEmpty() || counted.get() <= 0) {
            return empty(days, NO_LIMIT);
        }
        long limitCents = counted.get();
        String currency = newest.size() > 3 ? newest.get(3) : "";
        LocalDateTime start = month.atDay(1).atStartOfDay();

        // The last reading of each day of this month, as the history shows them; the rows come newest first.
        Map<LocalDate, List<String>> lastOfDay = new LinkedHashMap<>();
        for (List<String> row : shown) {
            Amount amount = CostPanel.amount(row);
            LocalDateTime at = CostPanel.time(row);
            if (amount == null || at == null || !YearMonth.from(at).equals(month)) {
                continue;
            }
            lastOfDay.putIfAbsent(at.toLocalDate(), row);
        }
        List<List<String>> daily = new ArrayList<>(lastOfDay.values());
        daily.sort(Comparator.comparing(CostPanel::time));

        List<Point> points = new ArrayList<>();
        points.add(new Point(0, 0, null));
        for (List<String> row : daily) {
            LocalDateTime at = CostPanel.time(row);
            long used = Math.max(0, CostPanel.amount(row).cents());
            points.add(new Point(daysGone(start, days, at), cents(used),
                    DAY.format(at) + ": " + money(used, currency, withSymbol) + " used"));
        }

        List<Point> forecast = new ArrayList<>();
        long endCents = 0;
        if (!daily.isEmpty()) {
            List<String> last = daily.get(daily.size() - 1);
            LocalDateTime at = CostPanel.time(last);
            long used = Math.max(0, CostPanel.amount(last).cents());
            double elapsed = Duration.between(start, at).toSeconds() / SECONDS_A_DAY;
            Point from = points.get(points.size() - 1);
            // Nothing spent yet, or no time gone: at no spend the amount stays as it is. It does not stop at the limit.
            endCents = elapsed <= 0 || used == 0 ? used : Math.round(used / elapsed * days);
            forecast.add(new Point(from.x(), from.used(), null));
            forecast.add(new Point(days, cents(endCents),
                    "Forecast for " + DAY.format(month.atEndOfMonth()) + ": " + money(endCents, currency, withSymbol) + " used"));
        }

        // The labels are written short, so that they leave the chart its room (since 0.99).
        List<Label> labels = List.of(
                new Label(0, label(0, currency, withSymbol)),
                new Label(cents(limitCents) / 2, label(limitCents / 2, currency, withSymbol)),
                new Label(cents(limitCents), label(limitCents, currency, withSymbol)));
        double top = cents(limitCents);
        for (Point point : points) {
            top = Math.max(top, point.used());
        }
        if (!forecast.isEmpty()) {
            top = Math.max(top, cents(endCents));
        }
        return new View(days, false, money(limitCents, currency, withSymbol), cents(limitCents), top, null, labels,
                againstIdeal(points, cents(limitCents), days), forecast, !forecast.isEmpty() && endCents > limitCents);
    }

    /**
     * The points marked where they are above the ideal line, with a point of its own where the line crosses it between two of
     * them, so that each piece between two points lies wholly on one side of it and the page can colour it by its ends (since
     * 0.99; above, not below, since 0.100).
     */
    static List<Point> againstIdeal(List<Point> points, double limit, int days) {
        List<Point> marked = new ArrayList<>();
        Point before = null;
        for (Point point : points) {
            double off = offIdeal(point, limit, days);
            if (before != null) {
                double was = offIdeal(before, limit, days);
                if ((was > EPSILON && off < -EPSILON) || (was < -EPSILON && off > EPSILON)) {
                    // Where the distance to the ideal line, which changes evenly along the piece, is 0.
                    double share = was / (was - off);
                    marked.add(new Point(before.x() + share * (point.x() - before.x()),
                            before.used() + share * (point.used() - before.used()), null, false));
                }
            }
            marked.add(new Point(point.x(), point.used(), point.tooltip(), off > EPSILON));
            before = point;
        }
        return marked;
    }

    /** How far the point's amount used is above the ideal line at its day, negative below it. */
    private static double offIdeal(Point point, double limit, int days) {
        return point.used() - limit * point.x() / days;
    }

    private static View empty(int days, String note) {
        return new View(days, true, null, null, null, note, List.of(), List.of(), List.of(), false);
    }

    /** The days gone in the month at this time: 0 at the start of the 1st, the month's length at the end of its last day. */
    static double daysGone(LocalDateTime start, int days, LocalDateTime at) {
        return Math.min(days, Math.max(0, Duration.between(start, at).toSeconds() / SECONDS_A_DAY));
    }

    private static double cents(long cents) {
        return cents / 100.0;
    }

    private static String money(long cents, String currency, boolean withSymbol) {
        String text = Formatting.amount(Amount.ofCents(cents));
        return withSymbol ? Formatting.currencySymbol(currency) + text : text;
    }

    /** An amount of the y-axis, short: {@code 1k}, {@code $500} (since 0.99). */
    private static String label(long cents, String currency, boolean withSymbol) {
        String text = Formatting.shortAmount(Amount.ofCents(cents));
        return withSymbol ? Formatting.currencySymbol(currency) + text : text;
    }
}
