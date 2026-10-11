package org.example.settings;

/**
 * Every setting the user can change, as the settings file and the settings API hold them.
 *
 * @param usageIntervalSeconds the time between usage requests, from 5 to 3600 seconds ({@link IntervalRange#USAGE})
 * @param logResponse whether each response's JSON is written to the log, pretty printed
 * @param showPercentage whether the percentage spent is in the row
 * @param showCurrency whether the amounts of the row have the currency symbol before them
 * @param showHistoryIcon whether the row has the button that shows the usage history
 * @param showLogIcon whether the row has the button that shows the log
 * @param showErrorIcon whether the row has the button that shows the error log
 * @param showInterval whether the time between usage requests is in the row
 * @param showDeltaUsed whether the change in the amount used is in the row
 * @param showDeltaTime whether the time since the previous reading is in the row
 * @param timeFormat how the time of day is cut in the row and its messages
 * @param historyDeltaUsed whether the history table has a column for the change in the amount used
 * @param historyDeltaTime whether the history table has a column for the time since the previous reading
 * @param historyDate whether the history table shows the date as well as the time
 * @param historyZeroLines whether the history shows its zero usage lines: the readings with no change since the one before
 * @param historyFailedLines whether the history shows the lines of failed queries
 * @param showTime whether the time of the reading is in the row
 * @param showUsed whether the amount used is in the row
 * @param showLimit whether the budget is in the row
 * @param showRefresh whether the row has the refresh button
 * @param showCountdown whether the countdown to the next refresh is in the row
 */
public record Settings(
        int usageIntervalSeconds,
        boolean logResponse,
        boolean showPercentage,
        boolean showCurrency,
        boolean showHistoryIcon,
        boolean showLogIcon,
        boolean showErrorIcon,
        boolean showInterval,
        boolean showDeltaUsed,
        boolean showDeltaTime,
        TimeFormat timeFormat,
        boolean historyDeltaUsed,
        boolean historyDeltaTime,
        boolean historyDate,
        boolean historyZeroLines,
        boolean historyFailedLines,
        boolean showTime,
        boolean showUsed,
        boolean showLimit,
        boolean showRefresh,
        boolean showCountdown) {

    /** The settings with the five elements of the row that were always there, all shown, as they are by default. */
    public Settings(
            int usageIntervalSeconds, boolean logResponse, boolean showPercentage, boolean showCurrency, boolean showHistoryIcon,
            boolean showLogIcon, boolean showErrorIcon, boolean showInterval, boolean showDeltaUsed, boolean showDeltaTime,
            TimeFormat timeFormat, boolean historyDeltaUsed, boolean historyDeltaTime, boolean historyDate, boolean historyZeroLines,
            boolean historyFailedLines) {
        this(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines,
                historyFailedLines, true, true, true, true, true);
    }

    public static Settings defaults() {
        return new Settings(
                IntervalRange.USAGE.defaultValue(), false, false, false, true, true, true, false, false, false, TimeFormat.HOURS_MINUTES, false, false, false, true, true);
    }

    public Settings withShowPercentage(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, on, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withShowCurrency(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, on, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withShowHistoryIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, on, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withShowLogIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, on, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withShowErrorIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, on, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withLogResponse(boolean on) {
        return new Settings(usageIntervalSeconds, on, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    public Settings withUsageIntervalSeconds(int seconds) {
        return new Settings(seconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown);
    }

    /** These settings with the five elements of the row that are shown by default switched as given. */
    public Settings withRowElements(boolean time, boolean used, boolean limit, boolean refresh, boolean countdown) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                time, used, limit, refresh, countdown);
    }
}
