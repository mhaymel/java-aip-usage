package org.example.settings;

import org.example.money.Amount;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The two limits of cost control, for an account that is billed by usage: what may be spent in a month and in a day,
 * in US dollars, kept in cents. They are settings to be kept; nothing is compared with them yet.
 *
 * @param monthlyCents what may be spent in a month, or {@code null} for no limit
 * @param dailyCents the daily limit that was typed in, or {@code null}; it counts only while {@code dailyAutomatic} is off
 * @param dailyAutomatic whether the daily limit is worked out from the monthly one, which it is by default
 */
public record CostControl(Long monthlyCents, Long dailyCents, boolean dailyAutomatic) {

    /** No limits, and the daily one worked out once there is a monthly one: as it is when nobody has set anything. */
    public static final CostControl NONE = new CostControl(null, null, true);

    /** The least and the most a limit may be: 0.01 to 1,000,000 dollars. */
    public static final long MIN_CENTS = 1;

    public static final long MAX_CENTS = 100_000_000L;

    public static final String AMOUNT_FAULT = "A limit must be an amount from 0.01 to 1,000,000, with two decimals at most.";

    public static final String ORDER_FAULT = "The daily limit cannot be more than the monthly limit.";

    /** How many weekdays, Monday to Friday, the month has. */
    public static int weekdays(YearMonth month) {
        int count = 0;
        for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    /** The daily limit worked out from a monthly one: divided by the weekdays of the month, rounded down to the cent. */
    public static long dailyFor(long monthlyCents, YearMonth month) {
        return monthlyCents / weekdays(month);
    }

    /**
     * The daily limit in force in the given month: the one worked out while that is asked for, and else the one typed
     * in; none when there is nothing to work it out from, or nothing was typed.
     */
    public Optional<Long> dailyIn(YearMonth month) {
        if (dailyAutomatic) {
            return monthlyCents == null ? Optional.empty() : Optional.of(dailyFor(monthlyCents, month));
        }
        return Optional.ofNullable(dailyCents);
    }

    /** Why these cannot be the limits, in words for the person who set them, or nothing when they can. */
    public Optional<String> fault() {
        if (monthlyCents != null && (monthlyCents < MIN_CENTS || monthlyCents > MAX_CENTS)) {
            return Optional.of(AMOUNT_FAULT);
        }
        if (dailyCents != null && (dailyCents < MIN_CENTS || dailyCents > MAX_CENTS)) {
            return Optional.of(AMOUNT_FAULT);
        }
        if (!dailyAutomatic && monthlyCents != null && dailyCents != null && dailyCents > monthlyCents) {
            return Optional.of(ORDER_FAULT);
        }
        return Optional.empty();
    }

    /** An amount of cents as it is written in dollars, with two decimals: {@code 250.00}. */
    public static String text(long cents) {
        return Amount.ofCents(cents).plain();
    }

    /**
     * An amount as it is typed, in dollars with two decimals at most, as cents.
     *
     * @return empty if the text is not such an amount; whether it is in range is {@link #fault()}'s to say
     */
    public static Optional<Long> cents(String typed) {
        if (typed == null || !typed.trim().matches("\\d{1,9}(\\.\\d{1,2})?")) {
            return Optional.empty();
        }
        return Optional.of(Amount.parse(typed.trim()).exactCents());
    }
}
