package org.example;

import org.example.json.Json;
import org.example.money.Amount;
import org.example.settings.CostControl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the cost control panel shows, finished: two bars, one for what is spent today against the daily limit and one for the amount
 * used this month against the monthly limit. Pure arithmetic on the rows of the history, with no clock of its own, so that the rules
 * can be tested for any day. See {@code docs/api.md}, under {@code GET /api/cost}.
 */
final class CostPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** The text of the note when there is no reading to say what is used. */
    static final String NO_READING = "No usage has been read yet.";

    private CostPanel() {
    }

    /**
     * @param text what is written at the right of the bar
     * @param tooltip the hover text of the bar
     * @param fraction how much of the bar is green, from 0 to 1
     * @param over whether the limit is exceeded: the bar is full and red
     * @param noLimit whether there is no limit to compare with: the bar is gray and empty
     * @param state the colour of the bar (since 0.82): {@code green}, {@code amber} when the projection or the pace says the
     *     limit will be passed, {@code red} over the limit, {@code gray} with no limit
     */
    record Row(String label, String text, String tooltip, double fraction, boolean over, boolean noLimit, String state) {

        /** A row whose colour follows from its limit alone: red over it, gray with none, green otherwise. */
        Row(String label, String text, String tooltip, double fraction, boolean over, boolean noLimit) {
            this(label, text, tooltip, fraction, over, noLimit, noLimit ? Spending.GRAY : over ? Spending.RED : Spending.GREEN);
        }

        /** This row amber, with what the projection or the pace says added to its tooltip, when the part says so. */
        Row with(Spending.Part part) {
            if (part == null || !Spending.AMBER.equals(part.state()) || over || noLimit) {
                return this;
            }
            return new Row(label, text, tooltip + "\n" + part.line(), fraction, over, noLimit, Spending.AMBER);
        }

        Json toJson() {
            return Json.object()
                    .put("label", label)
                    .put("text", text)
                    .put("tooltip", tooltip)
                    .put("fraction", fraction)
                    .put("over", over)
                    .put("noLimit", noLimit)
                    .put("state", state);
        }
    }

    record View(List<Row> rows, String note) {

        /** The two bars with the states of the day and the month as Spending has them (since 0.82). */
        View with(Spending.Result spending) {
            if (spending == null || rows.size() != 2) {
                return this;
            }
            return new View(List.of(rows.get(0).with(spending.day()), rows.get(1).with(spending.month())), note);
        }


        Json toJson() {
            Json all = Json.array();
            for (Row row : rows) {
                all.add(row.toJson());
            }
            return Json.object().put("rows", all).put("note", note);
        }
    }

    /**
     * @param rows the rows of the history in use, newest first, each as {@code HistoryReader} gives them (time, used, limit, currency, status, ...);
     *     a failed row has no amount used and is passed over
     * @param limits the limits of cost control
     * @param withSymbol whether the amounts have the currency symbol before them, as in the row
     * @param today the local date of today
     */
    static View build(List<List<String>> rows, CostControl limits, boolean withSymbol, LocalDate today) {
        List<List<String>> readings = rows.stream().filter(row -> amount(row) != null && time(row) != null).toList();
        if (readings.isEmpty()) {
            return new View(List.of(empty("Today"), empty("Month")), NO_READING);
        }
        List<String> newest = readings.get(0);
        Amount used = amount(newest);
        String currency = newest.get(3);
        YearMonth month = YearMonth.from(today);
        Amount spentToday = spentSince(used, baseline(readings, today, month, used));
        // The monthly limit counted (since 0.82): the server's, sent with the newest reading, or the one typed.
        Long server = serverLimit(newest);
        Optional<Long> daily = limits.dailyIn(month, server);
        return new View(List.of(
                row("Today", spentToday, daily.orElse(null), currency, withSymbol),
                row("Month", used, limits.monthlyCounted(server).orElse(null), currency, withSymbol)), null);
    }

    /** What was spent since an earlier amount: the difference, and nothing where the amount has fallen. */
    static Amount spentSince(Amount used, Amount earlier) {
        try {
            return used.minus(earlier).max(Amount.ZERO);
        } catch (ArithmeticException e) {
            // Two amounts too far apart for their difference to be held, as rows changed by hand may be.
            return Amount.ZERO;
        }
    }

    /**
     * The amount that today's spending is counted from: the last reading before today in the same month, else the first reading of today,
     * so that a program started today shows what was spent since it started and the first day of a month begins at its first reading.
     * With neither, today has nothing to count from and nothing was spent.
     */
    static Amount baseline(List<List<String>> readings, LocalDate today, YearMonth month, Amount newestAmount) {
        Amount beforeToday = null;
        Amount firstToday = null;
        LocalDateTime latestBefore = null;
        LocalDateTime earliestToday = null;
        for (List<String> row : readings) {
            LocalDateTime at = time(row);
            if (at.toLocalDate().isBefore(today)) {
                if (YearMonth.from(at).equals(month) && (latestBefore == null || at.isAfter(latestBefore))) {
                    latestBefore = at;
                    beforeToday = amount(row);
                }
            } else if (at.toLocalDate().equals(today) && (earliestToday == null || at.isBefore(earliestToday))) {
                earliestToday = at;
                firstToday = amount(row);
            }
        }
        if (beforeToday != null) {
            return beforeToday;
        }
        return firstToday != null ? firstToday : newestAmount;
    }

    private static Row empty(String label) {
        return new Row(label, "", label, 0, false, true);
    }

    private static Row row(String label, Amount used, Long limitCents, String currency, boolean withSymbol) {
        String usedText = money(used, currency, withSymbol);
        if (limitCents == null || limitCents <= 0) {
            return new Row(label, usedText + " · no limit set", label + ": " + usedText + ", no limit set", 0, false, true);
        }
        Amount limit = Amount.ofCents(limitCents);
        long millionths = used.shareOf(limit, 6);
        // The percentage is the share to six decimals, rounded once more to a whole number, upwards at half.
        long percent = (millionths + (millionths >= 0 ? 5_000 : -5_000)) / 10_000;
        String limitText = money(limit, currency, withSymbol);
        return new Row(label,
                usedText + " of " + limitText + " · " + percent + "%",
                label + ": " + usedText + " of " + limitText + " (" + percent + "%)",
                Math.min(1.0, millionths / 1_000_000.0),
                used.compareTo(limit) > 0,
                false);
    }

    private static String money(Amount amount, String currency, boolean withSymbol) {
        String text = Formatting.amount(amount);
        return withSymbol ? Formatting.currencySymbol(currency) + text : text;
    }

    /** The limit the server sent with a reading, in cents, or null when the row has none. */
    static Long serverLimit(List<String> row) {
        try {
            return row.size() < 3 || row.get(2).isBlank() ? null : Amount.parse(row.get(2)).cents();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static Amount amount(List<String> row) {
        try {
            return row.get(1).isBlank() ? null : Amount.parse(row.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDateTime time(List<String> row) {
        try {
            return LocalDateTime.parse(row.get(0), TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
