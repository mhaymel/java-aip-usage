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
 * @param currency whether the figures of the cost control have the currency symbol before the amounts (since 0.82; off by default)
 * @param source which limit is counted as the monthly one: {@code server}, the one the server sends with each reading, or
 *     {@code monthly}, the one typed, and the server's while none is typed (since 0.82; {@code monthly} by default)
 * @param workdayHours how long a working day is, in hours, from 1 to 24: what today's spend is projected over (since 0.82)
 * @param workdays the working days typed for a month, or {@code null} for Monday to Friday; they count for {@code workdaysMonth} alone
 * @param workdaysMonth the month the typed working days are for, as {@code 2026-10}, or {@code null}
 */
public record CostControl(Long monthlyCents, Long dailyCents, boolean dailyAutomatic, boolean currency, String source, int workdayHours,
        Integer workdays, String workdaysMonth) {

    public static final String SERVER = "server";

    public static final String MONTHLY = "monthly";

    public static final int DEFAULT_WORKDAY_HOURS = 8;

    /** A source that is none of the two is the default; hours out of range are the default. */
    public CostControl {
        source = SERVER.equals(source) ? SERVER : MONTHLY;
        workdayHours = workdayHours >= 1 && workdayHours <= 24 ? workdayHours : DEFAULT_WORKDAY_HOURS;
        if (workdays == null || workdays < 1 || workdays > 31 || workdaysMonth == null) {
            workdays = null;
            workdaysMonth = null;
        }
    }

    public CostControl(Long monthlyCents, Long dailyCents, boolean dailyAutomatic, boolean currency) {
        this(monthlyCents, dailyCents, dailyAutomatic, currency, MONTHLY, DEFAULT_WORKDAY_HOURS, null, null);
    }

    /** No limits, and the daily one worked out once there is a monthly one: as it is when nobody has set anything. */
    public static final CostControl NONE = new CostControl(null, null, true);

    /** The limits, with what came after them as it is by default. */
    public CostControl(Long monthlyCents, Long dailyCents, boolean dailyAutomatic) {
        this(monthlyCents, dailyCents, dailyAutomatic, false);
    }

    public CostControl withCurrency(boolean on) {
        return new CostControl(monthlyCents, dailyCents, dailyAutomatic, on, source, workdayHours, workdays, workdaysMonth);
    }

    /** These limits with the choices of 0.82 as given: the limit counted, the hours of a working day and the working days typed. */
    public CostControl withCounting(String counted, int hours, Integer days, String daysMonth) {
        return new CostControl(monthlyCents, dailyCents, dailyAutomatic, currency, counted, hours, days, daysMonth);
    }

    /** Whether the server's limit is counted as the monthly one, whatever is typed. */
    public boolean serverCounted() {
        return SERVER.equals(source);
    }

    /** The working days typed for this month, or empty when they are Monday to Friday: a number typed holds for its month alone. */
    public Optional<Integer> workdaysTyped(YearMonth month) {
        return workdays != null && month.toString().equals(workdaysMonth) ? Optional.of(workdays) : Optional.empty();
    }

    /** The working days of the month: the number typed for it, else Monday to Friday. */
    public int workdays(YearMonth month) {
        return workdaysTyped(month).orElseGet(() -> weekdays(month));
    }

    /**
     * The monthly limit counted: the server's with {@code server}; the one typed with {@code monthly}, and the server's while
     * none is typed. Empty when there is none.
     *
     * @param serverCents the limit the server sent with the newest reading, or null
     */
    public Optional<Long> monthlyCounted(Long serverCents) {
        if (serverCounted()) {
            return Optional.ofNullable(serverCents);
        }
        return Optional.ofNullable(monthlyCents != null ? monthlyCents : serverCents);
    }

    /**
     * The daily limit in force in the given month: the one worked out from the monthly limit counted and the working days while
     * that is asked for, and else the one typed in.
     */
    public Optional<Long> dailyIn(YearMonth month, Long serverCents) {
        if (dailyAutomatic) {
            return monthlyCounted(serverCents).map(monthly -> monthly / workdays(month));
        }
        return Optional.ofNullable(dailyCents);
    }

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
        return dailyIn(month, null);
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
