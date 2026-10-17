package org.example;

import org.example.money.Amount;
import org.example.settings.TimeFormat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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

    /** The time since the previous reading as the history writes it, in whole seconds with the unit and never in minutes: {@code 63 s}, {@code 3600 s}. */
    static String seconds(long seconds) {
        return Math.max(0, seconds) + " s";
    }

    /**
     * How long a request took, as the row writes it: the milliseconds under a second, {@code 87ms}, and from a second on the whole
     * seconds and the rest in three digits, {@code 1s034ms}.
     */
    static String duration(long millis) {
        long shown = Math.max(0, millis);
        return shown < 1000 ? shown + "ms" : String.format(Locale.ROOT, "%ds%03dms", shown / 1000, shown % 1000);
    }

    /** Seconds as the row writes them, with no space before the unit: {@code 63s}, {@code -3s}. */
    static String rowSeconds(long seconds) {
        return seconds + "s";
    }

    /**
     * An amount as a plain number with two decimals and no currency sign: 1000 reads {@code 1,000.00}. A third decimal is
     * rounded as the history file rounds it, on the decimal form and upwards at half, so the row and the file agree.
     */
    static String amount(Amount amount) {
        return amount == null ? DASH : amount.grouped();
    }

    /**
     * An amount written short, as the burnup's y-axis writes it (since 0.99): the whole number below a thousand, {@code 617};
     * from a thousand in thousands with one decimal at most, {@code 1k}, {@code 1.5k}, {@code 12.3k}; from a million in millions,
     * {@code 2M}. Each rounded half up, so that 999.60 is {@code 1k}.
     */
    static String shortAmount(Amount amount) {
        long cents = amount.cents();
        if (cents < 0) {
            return "-" + shortAmount(Amount.ofCents(-cents));
        }
        long units = (cents + 50) / 100;
        if (units < 1_000) {
            return Long.toString(units);
        }
        long thousandths = (cents + 5_000) / 10_000;
        if (thousandths < 10_000) {
            return tenths(thousandths, "k");
        }
        return tenths((cents + 5_000_000) / 10_000_000, "M");
    }

    /** A number of tenths with the unit after it, a decimal {@code .0} left out: {@code 15} and {@code k} is {@code 1.5k}. */
    private static String tenths(long tenths, String unit) {
        return tenths / 10 + (tenths % 10 == 0 ? "" : "." + tenths % 10) + unit;
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
    static String signedAmount(Amount change) {
        return change.signed();
    }
}
