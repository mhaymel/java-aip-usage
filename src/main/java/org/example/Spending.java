package org.example;

import org.example.json.Json;
import org.example.money.Amount;
import org.example.settings.CostControl;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * How the day and the month stand against their limits (since 0.82): what is spent, the day's projection over a working day,
 * the month's pace over its working days, and the state of each, green, amber, red or gray, with the status circle's state and
 * tooltip. It is given the readings of the history in use, the settings of cost control and the time, and has no clock of its
 * own, so that it can be tested. What is spent today is counted as the cost control counts it.
 */
final class Spending {

    static final String GREEN = "green";

    static final String AMBER = "amber";

    static final String RED = "red";

    static final String GRAY = "gray";

    /** No projection is made before the working day has gone on this long: there is too little to go on. */
    static final Duration LEAST_FOR_PROJECTION = Duration.ofMinutes(15);

    static final String NO_LIMITS = "No limits are set: set them in the settings, under Cost control.";

    static final String NO_READING = "No usage has been read yet.";

    /**
     * The day or the month.
     *
     * @param state green, amber, red or gray
     * @param used what is spent, or null with no reading
     * @param limitCents the limit, or null for none
     * @param line what the circle's tooltip says of it
     */
    record Part(String state, Amount used, Long limitCents, String line) {

        Json toJson() {
            Json percent = Json.NULL;
            if (used != null && limitCents != null && limitCents > 0) {
                long millionths = used.shareOf(Amount.ofCents(limitCents), 6);
                percent = Json.text(((millionths + (millionths >= 0 ? 5_000 : -5_000)) / 10_000) + "%");
            }
            return Json.object()
                    .put("state", state)
                    .put("used", used == null ? Json.NULL : Json.text(Formatting.amount(used)))
                    .put("limit", limitCents == null ? Json.NULL : Json.text(Formatting.amount(Amount.ofCents(limitCents))))
                    .put("percent", percent);
        }
    }

    /** The state of the circle, its tooltip, and the day and the month. */
    record Result(String state, String tooltip, Part day, Part month) {

        Json toJson() {
            return Json.object().put("state", state).put("tooltip", tooltip).put("day", day.toJson()).put("month", month.toJson());
        }
    }

    private Spending() {
    }

    /**
     * @param rows the rows of the history in use, newest first, as {@code HistoryReader} gives them
     * @param cost the settings of cost control
     * @param now the local date and time
     */
    static Result compute(List<List<String>> rows, CostControl cost, LocalDateTime now) {
        List<List<String>> readings = rows.stream().filter(row -> CostPanel.amount(row) != null && CostPanel.time(row) != null).toList();
        if (readings.isEmpty()) {
            Part none = new Part(GRAY, null, null, NO_READING);
            return new Result(GRAY, NO_READING, none, none);
        }
        LocalDate today = now.toLocalDate();
        YearMonth thisMonth = YearMonth.from(today);
        List<String> newest = readings.get(0);
        Amount used = CostPanel.amount(newest);
        Long server = CostPanel.serverLimit(newest);
        Amount spentToday = CostPanel.spentSince(used, CostPanel.baseline(readings, today, thisMonth, used));
        Part day = day(spentToday, cost.dailyIn(thisMonth, server), dayStart(readings, today), cost.workdayHours(), now);
        Part month = month(used, cost.monthlyCounted(server), cost, today);
        return new Result(circle(day, month), day.state().equals(GRAY) && month.state().equals(GRAY) ? NO_LIMITS : day.line() + "\n" + month.line(),
                day, month);
    }

    private static Part day(Amount spent, Optional<Long> limit, LocalDateTime start, int hours, LocalDateTime now) {
        if (limit.isEmpty()) {
            return new Part(GRAY, spent, null, "Today: no daily limit is set");
        }
        long spentCents = spent.cents();
        long limitCents = limit.get();
        String of = Formatting.amount(Amount.ofCents(limitCents));
        if (spentCents > limitCents) {
            return new Part(RED, spent, limitCents, "Today: over the daily limit: " + Formatting.amount(spent) + " of " + of);
        }
        Long projected = projection(spentCents, start, hours, now);
        if (projected != null && projected > limitCents) {
            return new Part(AMBER, spent, limitCents, "Today: on course to pass the daily limit: " + Formatting.amount(spent) + " spent, about "
                    + Formatting.amount(Amount.ofCents(projected)) + " by the end of the day, of " + of);
        }
        return new Part(GREEN, spent, limitCents, "Today: within the limit");
    }

    /**
     * What will have been spent by the end of the working day, in cents, at the rate since it began; null when no projection is
     * made: on a Saturday or a Sunday, before the day has had a rise, and in its first 15 minutes. Once the working day is over
     * it is what was spent.
     */
    static Long projection(long spentCents, LocalDateTime start, int hours, LocalDateTime now) {
        DayOfWeek weekday = now.getDayOfWeek();
        if (start == null || weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY) {
            return null;
        }
        long minutes = Duration.between(start, now).toMinutes();
        if (minutes < LEAST_FOR_PROJECTION.toMinutes()) {
            return null;
        }
        long workday = hours * 60L;
        if (minutes >= workday) {
            return spentCents;
        }
        return (spentCents * workday + minutes / 2) / minutes;
    }

    /** The start of today's working day: the first reading of today whose amount rose over the reading before it, or null. */
    static LocalDateTime dayStart(List<List<String>> readings, LocalDate today) {
        Amount before = null;
        for (int i = readings.size() - 1; i >= 0; i--) {
            List<String> row = readings.get(i);
            Amount amount = CostPanel.amount(row);
            LocalDateTime at = CostPanel.time(row);
            if (at.toLocalDate().equals(today) && before != null && amount.compareTo(before) > 0) {
                return at;
            }
            before = amount;
        }
        return null;
    }

    private static Part month(Amount used, Optional<Long> limit, CostControl cost, LocalDate today) {
        if (limit.isEmpty()) {
            return new Part(GRAY, used, null, "Month: no limit is set");
        }
        long usedCents = used.cents();
        long limitCents = limit.get();
        String of = Formatting.amount(Amount.ofCents(limitCents));
        if (usedCents > limitCents) {
            return new Part(RED, used, limitCents, "Month: over the monthly limit: " + Formatting.amount(used) + " of " + of);
        }
        long pace = pace(limitCents, cost, today);
        if (usedCents > pace) {
            return new Part(AMBER, used, limitCents, "Month: ahead of pace: " + Formatting.amount(used) + " spent, "
                    + Formatting.amount(Amount.ofCents(pace)) + " by now, of " + of);
        }
        return new Part(GREEN, used, limitCents, "Month: within the limit and on pace");
    }

    /**
     * What may have been spent by now to be on pace: the monthly limit times the working days begun, today counted as begun,
     * over the working days of the month. With a typed number of working days, the days begun are the weekdays begun, at
     * most that number.
     */
    static long pace(long limitCents, CostControl cost, LocalDate today) {
        YearMonth month = YearMonth.from(today);
        int total = cost.workdays(month);
        int begun = 0;
        for (LocalDate day = month.atDay(1); !day.isAfter(today); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                begun++;
            }
        }
        begun = Math.min(begun, total);
        return limitCents * begun / total;
    }

    /** The circle: red when either is red, amber when either is amber, gray when both are gray, and green otherwise. */
    static String circle(Part day, Part month) {
        if (day.state().equals(RED) || month.state().equals(RED)) {
            return RED;
        }
        if (day.state().equals(AMBER) || month.state().equals(AMBER)) {
            return AMBER;
        }
        if (day.state().equals(GRAY) && month.state().equals(GRAY)) {
            return GRAY;
        }
        return GREEN;
    }
}
