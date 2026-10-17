package org.example.fake;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Invented readings of the month so far, which the history of the fake backend is begun with (since 0.97), so that the burnup
 * has a month to draw at once: every three hours from 8:00 to 17:00 on the working days from the 1st to yesterday, every fifth
 * working day left out, the amount used rising from nothing to a little under the amount the fake backend begins with, so that
 * its own readings go on from there.
 */
public final class FakeMonth {

    /** The times of a working day that have a reading. */
    static final List<LocalTime> TIMES = List.of(LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(14, 0), LocalTime.of(17, 0));

    /** Every this many working days one has no reading, so that the burnup shows a gap bridged. */
    static final int LEFT_OUT_EVERY = 5;

    /**
     * An invented reading.
     *
     * @param at its local time
     * @param usedCents the amount used, in cents
     */
    public record Reading(LocalDateTime at, long usedCents) {
    }

    private FakeMonth() {
    }

    /**
     * @param startCents the amount the fake backend begins with; the readings rise to 98 % of it
     * @param today the local date of today; the readings are of the days of its month before it
     * @return the readings in the order of time; none on the 1st, or when nothing is to be spent
     */
    public static List<Reading> readings(long startCents, LocalDate today) {
        List<LocalDateTime> times = new ArrayList<>();
        int workday = 0;
        for (LocalDate day = today.withDayOfMonth(1); day.isBefore(today); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            workday++;
            if (workday % LEFT_OUT_EVERY == 0) {
                continue;
            }
            for (LocalTime time : TIMES) {
                times.add(day.atTime(time));
            }
        }
        if (times.isEmpty() || startCents <= 0) {
            return List.of();
        }
        // Steps of different sizes, the same for the same month, that add up to the end amount.
        Random random = new Random(today.getYear() * 100L + today.getMonthValue());
        double[] steps = new double[times.size()];
        double sum = 0;
        for (int i = 0; i < steps.length; i++) {
            steps[i] = 0.5 + random.nextDouble();
            sum += steps[i];
        }
        long end = startCents * 98 / 100;
        List<Reading> readings = new ArrayList<>();
        double reached = 0;
        for (int i = 0; i < steps.length; i++) {
            reached += steps[i];
            readings.add(new Reading(times.get(i), Math.round(end * reached / sum)));
        }
        return readings;
    }
}
