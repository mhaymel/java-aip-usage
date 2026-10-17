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
 * The burndown of the month's credit (since 0.97), finished: the page only draws it. The x-axis is the days left in the month,
 * from the month's length at its start to 0 at its end; the y-axis is the credit left, the limit counted less the amount used,
 * never below 0. One point a day, the last reading of the day, with the limit at the start of the 1st before them; and a
 * forecast from the last point to the end of the month at the month's average daily spend. Pure arithmetic on the rows of the
 * history, with no clock of its own, so that it can be tested for any day. See {@code docs/api.md}, under {@code GET /api/burndown}.
 */
final class Burndown {

    static final String NO_LIMIT = "No limit is set: set it in the settings, under Cost control.";

    static final String NO_READING = "No usage has been read yet.";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private static final double SECONDS_A_DAY = 24 * 60 * 60;

    /** Less than a cent's hundredth: a point on the ideal line, as the start of the month is, is not below it. */
    private static final double EPSILON = 1e-4;

    private Burndown() {
    }

    /**
     * A point of the chart.
     *
     * @param x the days left in the month at its time, 0 at the end of the month
     * @param credit the credit left, never below 0
     * @param tooltip what hovering over it says, or null for a point that is no reading (the start of the month, a bend of the
     *     forecast, a crossing of the ideal line)
     * @param below whether the credit is below the ideal line at {@code x}, which the page draws red (since 0.99); never for the forecast
     */
    record Point(double x, double credit, String tooltip, boolean below) {

        Point(double x, double credit, String tooltip) {
            this(x, credit, tooltip, false);
        }

        Json toJson() {
            return Json.object().put("x", round(x)).put("credit", credit).put("tooltip", tooltip).put("below", below);
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
     * @param note what the panel says in the place of the chart, or null
     * @param points the actual line, the start of the month first
     * @param forecast the forecast, from the last point to the end of the month; empty with no reading of the month
     */
    record View(int days, boolean noLimit, String limit, Double limitValue, String note, List<Label> yLabels, List<Point> points,
            List<Point> forecast) {

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
                    .put("note", note)
                    .put("yLabels", labels)
                    .put("points", line)
                    .put("forecast", ahead);
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
        points.add(new Point(days, cents(limitCents), null));
        for (List<String> row : daily) {
            LocalDateTime at = CostPanel.time(row);
            long credit = Math.max(0, limitCents - CostPanel.amount(row).cents());
            points.add(new Point(daysLeft(start, days, at), cents(credit),
                    DAY.format(at) + ": " + money(credit, currency, withSymbol) + " left"));
        }

        List<Point> forecast = new ArrayList<>();
        if (!daily.isEmpty()) {
            List<String> last = daily.get(daily.size() - 1);
            LocalDateTime at = CostPanel.time(last);
            long used = Math.max(0, CostPanel.amount(last).cents());
            double elapsed = Duration.between(start, at).toSeconds() / SECONDS_A_DAY;
            double left = daysLeft(start, days, at);
            long credit = Math.max(0, limitCents - used);
            String end = "Forecast for " + DAY.format(month.atEndOfMonth()) + ": ";
            Point from = points.get(points.size() - 1);
            forecast.add(new Point(from.x(), from.credit(), null));
            if (elapsed <= 0 || used == 0) {
                // Nothing spent yet, or no time gone: at no spend the credit stays as it is.
                forecast.add(new Point(0, cents(credit), end + money(credit, currency, withSymbol) + " left"));
            } else {
                double perDay = used / elapsed;
                double atEnd = credit - perDay * left;
                if (atEnd >= 0) {
                    long endCents = Math.round(atEnd);
                    forecast.add(new Point(0, cents(endCents), end + money(endCents, currency, withSymbol) + " left"));
                } else {
                    // The credit runs out before the end of the month: the line meets 0 there and stays on it.
                    if (credit > 0) {
                        forecast.add(new Point(left - credit / perDay, 0, null));
                    }
                    forecast.add(new Point(0, 0, end + money(0, currency, withSymbol) + " left"));
                }
            }
        }

        // The labels are written short, so that they leave the chart its room (since 0.99).
        List<Label> labels = List.of(
                new Label(0, label(0, currency, withSymbol)),
                new Label(cents(limitCents) / 2, label(limitCents / 2, currency, withSymbol)),
                new Label(cents(limitCents), label(limitCents, currency, withSymbol)));
        return new View(days, false, money(limitCents, currency, withSymbol), cents(limitCents), null, labels,
                againstIdeal(points, cents(limitCents), days), forecast);
    }

    /**
     * The points marked where they are below the ideal line, with a point of its own where the line crosses it between two of
     * them, so that each piece between two points lies wholly on one side of it and the page can colour it by its ends (since 0.99).
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
                            before.credit() + share * (point.credit() - before.credit()), null, false));
                }
            }
            marked.add(new Point(point.x(), point.credit(), point.tooltip(), off < -EPSILON));
            before = point;
        }
        return marked;
    }

    /** How far the point's credit is above the ideal line at its day, negative below it. */
    private static double offIdeal(Point point, double limit, int days) {
        return point.credit() - limit * point.x() / days;
    }

    private static View empty(int days, String note) {
        return new View(days, true, null, null, note, List.of(), List.of(), List.of());
    }

    /** The days left in the month at this time: the month's length at the start of the 1st, 0 at the end of its last day. */
    static double daysLeft(LocalDateTime start, int days, LocalDateTime at) {
        return Math.max(0, days - Duration.between(start, at).toSeconds() / SECONDS_A_DAY);
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
