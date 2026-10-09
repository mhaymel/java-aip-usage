package org.example.settings;

import java.util.List;

/**
 * Every setting the user can change, as the settings file and the settings API hold them.
 *
 * @param usageIntervalSeconds the time between usage requests; the settings view offers {@link #INTERVAL_CHOICES}
 * @param logResponse whether each response's JSON is written to the log, pretty printed
 * @param showPercentage whether the percentage spent is in the row
 * @param showInterval whether the time between usage requests is in the row
 * @param showDeltaUsed whether the change in the amount used is in the row
 * @param showDeltaTime whether the time since the previous reading is in the row
 * @param timeFormat how the time of day is cut in the row and its messages
 * @param historyDeltaUsed whether the history table has a column for the change in the amount used
 * @param historyDeltaTime whether the history table has a column for the time since the previous reading
 */
public record Settings(
        int usageIntervalSeconds,
        boolean logResponse,
        boolean showPercentage,
        boolean showInterval,
        boolean showDeltaUsed,
        boolean showDeltaTime,
        TimeFormat timeFormat,
        boolean historyDeltaUsed,
        boolean historyDeltaTime) {

    /** What the settings view offers for the time between usage requests; the backend accepts the wider range of {@link IntervalRange#USAGE}. */
    public static final List<Integer> INTERVAL_CHOICES = List.of(60, 120, 180, 240, 300);

    public static Settings defaults() {
        return new Settings(
                IntervalRange.USAGE.defaultValue(), false, false, false, false, false, TimeFormat.HOURS_MINUTES, false, false);
    }

    public Settings withShowPercentage(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, on, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime);
    }

    public Settings withLogResponse(boolean on) {
        return new Settings(usageIntervalSeconds, on, showPercentage, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime);
    }

    public Settings withUsageIntervalSeconds(int seconds) {
        return new Settings(seconds, logResponse, showPercentage, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime);
    }
}
