package org.example;

import org.example.settings.CostControl;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * @param over whether the limit is exceeded, which a warning sign shows
     * @param noLimit whether there is no limit to compare with: the bar is gray and empty
     */
    record Row(String label, String text, String tooltip, double fraction, boolean over, boolean noLimit) {
    }

    record View(List<Row> rows, String note) {
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
            return new View(List.of(empty("Today"), empty("This month")), NO_READING);
        }
        List<String> newest = readings.get(0);
        BigDecimal used = amount(newest);
        String currency = newest.get(3);
        YearMonth month = YearMonth.from(today);
        BigDecimal spentToday = used.subtract(baseline(readings, today, month, used)).max(BigDecimal.ZERO);
        Optional<Long> daily = limits.dailyIn(month);
        return new View(List.of(
                row("Today", spentToday, daily.orElse(null), currency, withSymbol),
                row("This month", used, limits.monthlyCents(), currency, withSymbol)), null);
    }

    /**
     * The amount that today's spending is counted from: the last reading before today in the same month, else the first reading of today,
     * so that a program started today shows what was spent since it started and the first day of a month begins at its first reading.
     * With neither, today has nothing to count from and nothing was spent.
     */
    private static BigDecimal baseline(List<List<String>> readings, LocalDate today, YearMonth month, BigDecimal newestAmount) {
        BigDecimal beforeToday = null;
        BigDecimal firstToday = null;
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

    private static Row row(String label, BigDecimal used, Long limitCents, String currency, boolean withSymbol) {
        String usedText = money(used, currency, withSymbol);
        if (limitCents == null || limitCents <= 0) {
            return new Row(label, usedText + " · no limit set", label + ": " + usedText + ", no limit set", 0, false, true);
        }
        BigDecimal limit = BigDecimal.valueOf(limitCents, 2);
        BigDecimal ratio = used.divide(limit, 6, RoundingMode.HALF_UP);
        long percent = ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        String limitText = money(limit, currency, withSymbol);
        return new Row(label,
                usedText + " of " + limitText + " · " + percent + "%",
                label + ": " + usedText + " of " + limitText + " (" + percent + "%)",
                Math.min(1.0, ratio.doubleValue()),
                used.compareTo(limit) > 0,
                false);
    }

    private static String money(BigDecimal amount, String currency, boolean withSymbol) {
        String text = Formatting.amount(amount.doubleValue());
        return withSymbol ? Formatting.currencySymbol(currency) + text : text;
    }

    private static BigDecimal amount(List<String> row) {
        try {
            return row.get(1).isBlank() ? null : new BigDecimal(row.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime time(List<String> row) {
        try {
            return LocalDateTime.parse(row.get(0), TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
