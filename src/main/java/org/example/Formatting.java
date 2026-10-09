package org.example;

import org.example.settings.TimeFormat;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
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

    /** {@code 5 s}, {@code 3 min}, {@code 2 h 5 min}, {@code 1 d 4 h}: a span of time, coarse on purpose. */
    static String span(Duration span) {
        long seconds = Math.max(0, span.toSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h" + (minutes % 60 != 0 ? " " + (minutes % 60) + " min" : "");
        }
        long days = hours / 24;
        return days + " d" + (hours % 24 != 0 ? " " + (hours % 24) + " h" : "");
    }

    /** The time since the previous reading, in whole seconds with the unit and never in minutes: {@code 63 s}, {@code 3600 s}. */
    static String seconds(long seconds) {
        return Math.max(0, seconds) + " s";
    }

    /** An amount as a plain number with two decimals and no currency sign: 1000 reads {@code 1,000.00}. */
    static String amount(Double amount) {
        return amount == null ? DASH : decimal("#,##0.00").format(amount);
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

    /** 12.34 reads {@code 12.3%}, 80 reads {@code 80%}. */
    static String percent(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return (rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded)) + "%";
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
