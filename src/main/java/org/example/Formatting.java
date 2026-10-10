package org.example;

import org.example.settings.TimeFormat;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Every figure and text the window shows that is worked out from something else. The frontend does no
 * arithmetic and no formatting of readings: it places what the status gives it. The forms are the same
 * whatever the machine's language.
 */
final class Formatting {

    static final String DASH = "—";

    private static final DateTimeFormatter HOURS_MINUTES = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private static final DateTimeFormatter HOURS_MINUTES_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** {@code 8 Oct 2026, 14:24:53}, with the English month, which the one place that shows a date uses. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH);

    private static final Map<String, String> SEVERITY_KINDS = Map.of(
            "normal", "normal",
            "warning", "warning",
            "warn", "warning",
            "critical", "critical",
            "exceeded", "critical",
            "error", "critical");

    private Formatting() {
    }

    /** The local time of day, never the date: {@code 14:24} or {@code 14:24:53}. */
    static String time(Instant at, TimeFormat format, ZoneId zone) {
        return (format == TimeFormat.HOURS_MINUTES_SECONDS ? HOURS_MINUTES_SECONDS : HOURS_MINUTES).format(at.atZone(zone));
    }

    /** The full local date and time, as in the tooltip on the time, which is the one place a date appears. */
    static String dateTime(Instant at, ZoneId zone) {
        return DATE_TIME.format(at.atZone(zone));
    }

    /** The time since the previous reading, in whole seconds with the unit and never in minutes: {@code 63 s}, {@code 3600 s}. */
    static String seconds(long seconds) {
        return Math.max(0, seconds) + " s";
    }

    /**
     * An amount as a plain number with two decimals and no currency sign: 1000 reads {@code 1,000.00}. A third decimal is
     * rounded as the history file rounds it, on the decimal form and upwards at half, so the row and the file agree.
     */
    static String amount(Double amount) {
        return amount == null
                ? DASH
                : decimal("#,##0.00").format(BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * The symbol that goes before an amount of this currency in the row: {@code $} for US dollars, and for any other currency its code
     * and a space ({@code EUR }); nothing when the response named no currency.
     */
    static String currencySymbol(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return code.equals("USD") ? "$" : code + " ";
    }

    /** What the history's currency column shows: {@code $} for US dollars, the code for any other currency, nothing for none. */
    static String historyCurrency(String code) {
        return currencySymbol(code).strip();
    }

    /** A change in an amount, with its sign: {@code +0.05}, {@code -0.05}, {@code 0.00}. */
    static String signedAmount(BigDecimal change) {
        BigDecimal rounded = change.setScale(2, java.math.RoundingMode.HALF_UP);
        String text = decimal("#,##0.00").format(rounded.abs());
        return rounded.signum() > 0 ? "+" + text : rounded.signum() < 0 ? "-" + text : text;
    }

    /** One of a fixed set of style names, whatever the endpoint sends; the window puts it into a class name. */
    static String severityKind(String severity) {
        if (severity == null) {
            return "other";
        }
        return SEVERITY_KINDS.getOrDefault(severity.toLowerCase(Locale.ROOT), "other");
    }

    private static DecimalFormat decimal(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
    }
}
