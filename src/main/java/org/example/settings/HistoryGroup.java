package org.example.settings;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * The intervals the history can be grouped by (since 0.94), with the key each has in the settings file and the API, and where
 * an interval begins: on the clock in the machine's zone, a week on its Monday (ISO), a month on its first day.
 */
public enum HistoryGroup {
    NONE("none", 0),
    MIN5("5m", 5),
    MIN15("15m", 15),
    MIN30("30m", 30),
    HOUR("1h", 60),
    DAY("1d", 0),
    WEEK("1w", 0),
    MONTH("month", 0);

    private final String key;

    /** The length in minutes of an interval within an hour or of an hour; 0 for the others. */
    private final int minutes;

    HistoryGroup(String key, int minutes) {
        this.key = key;
        this.minutes = minutes;
    }

    public String key() {
        return key;
    }

    /** The interval of this key, if it is one. */
    public static Optional<HistoryGroup> of(String key) {
        for (HistoryGroup group : values()) {
            if (group.key.equals(key)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    public static boolean isKey(String key) {
        return of(key).isPresent();
    }

    /** Whether an interval is shorter than a day, so that a day holds several of them. */
    public boolean withinADay() {
        return minutes > 0;
    }

    /** The beginning of the interval this time of a reading is in; the time itself for {@link #NONE}. */
    public LocalDateTime start(LocalDateTime at) {
        return switch (this) {
            case NONE -> at;
            case MIN5, MIN15, MIN30, HOUR -> {
                LocalDateTime hour = at.truncatedTo(ChronoUnit.HOURS);
                yield hour.plusMinutes((at.getMinute() / minutes) * (long) minutes);
            }
            case DAY -> at.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> at.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> at.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
        };
    }
}
