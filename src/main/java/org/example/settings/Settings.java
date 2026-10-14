package org.example.settings;

import org.example.fake.Scenario;

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
 * @param showDuration whether the time the latest usage request took is in the row
 * @param stayOnTop whether the window stays above every other window
 * @param showTitleBar whether the window has its title bar while no panel is open; with a panel open it always has
 * @param fakeBackend a debug setting: usage comes from the fake backend the program starts itself
 * @param fakeScenario a debug setting: what that backend answers
 * @param fakeToken a debug setting: a placeholder bearer is sent rather than a real token being obtained
 * @param logLevel a choice of the log panel, not of the settings view: the least severe level it shows
 * @param logDate a choice of the log panel: whether its lines have the date before the time
 * @param autoInterval whether the program finds the time between usage requests by itself, between 10 and 300 seconds,
 *     in the place of the configured interval, which is kept
 * @param cost the two limits of cost control, what may be spent in a month and in a day; settings that are kept, and
 *     compared with nothing yet
 * @param historyTime whether the history table has the column of the time
 * @param historyUsed whether it has the column of the amount used
 * @param historyLimit whether it has the column of the limit
 * @param historyCurrent whether it has the column of the currency, titled {@code Cur.}
 * @param restoreWindow whether the program stores which panel was open and the size and place of the window when it stops, and starts as it was
 * @param showCostIcon whether the row has the button that shows the cost control panel
 * @param severalViews whether more than one view can be shown at the same time: an icon then opens its view and leaves the others open
 * @param listFontSize a choice made in the lists, not in the settings view: the size of the text of the log, the usage
 *     history and the error log, in pixels
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
        boolean showCountdown,
        boolean showDuration,
        boolean stayOnTop,
        boolean showTitleBar,
        boolean fakeBackend,
        Scenario fakeScenario,
        boolean fakeToken,
        LogLevel logLevel,
        boolean logDate,
        int listFontSize,
        boolean autoInterval,
        CostControl cost,
        boolean historyTime,
        boolean historyUsed,
        boolean historyLimit,
        boolean historyCurrent,
        boolean restoreWindow,
        boolean showCostIcon,
        boolean severalViews) {

    /** The size of the text of the lists, in pixels, when nobody has chosen one: what it always was. */
    public static final int DEFAULT_LIST_FONT_SIZE = 12;

    /** The smallest and the largest the text of the lists can be made. */
    public static final int MIN_LIST_FONT_SIZE = 10;

    public static final int MAX_LIST_FONT_SIZE = 20;

    /** Whether the text of the lists can be this many pixels. */
    public static boolean isListFontSize(int pixels) {
        return pixels >= MIN_LIST_FONT_SIZE && pixels <= MAX_LIST_FONT_SIZE;
    }


    /** The settings with the five elements of the row that were always there, all shown, and the request duration not, the window not kept on top and with its title bar, and the log panel showing everything and no date, as they are by default. */
    public Settings(
            int usageIntervalSeconds, boolean logResponse, boolean showPercentage, boolean showCurrency, boolean showHistoryIcon,
            boolean showLogIcon, boolean showErrorIcon, boolean showInterval, boolean showDeltaUsed, boolean showDeltaTime,
            TimeFormat timeFormat, boolean historyDeltaUsed, boolean historyDeltaTime, boolean historyDate, boolean historyZeroLines,
            boolean historyFailedLines) {
        this(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines,
                historyFailedLines, true, true, true, true, true, false, false, true, false, Scenario.NORMAL, false, LogLevel.INFO, false, DEFAULT_LIST_FONT_SIZE, false, CostControl.NONE,
                true, true, true, true, false, true, false);
    }

    public static Settings defaults() {
        return new Settings(
                IntervalRange.USAGE.defaultValue(), false, false, false, true, true, true, false, false, false, TimeFormat.HOURS_MINUTES, false, false, false, true, true);
    }

    public Settings withShowPercentage(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, on, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowCurrency(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, on, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowHistoryIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, on, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowLogIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, on, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowErrorIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, on, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withLogResponse(boolean on) {
        return new Settings(usageIntervalSeconds, on, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withUsageIntervalSeconds(int seconds) {
        return new Settings(seconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval, showDeltaUsed, showDeltaTime, timeFormat,
                historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the five elements of the row that are shown by default switched as given. */
    public Settings withRowElements(boolean time, boolean used, boolean limit, boolean refresh, boolean countdown) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                time, used, limit, refresh, countdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowDuration(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, on, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withStayOnTop(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, on, showTitleBar, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withShowTitleBar(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, on, fakeBackend, fakeScenario, fakeToken, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the three debug settings as given; they count only in a run that is in debug mode. */
    public Settings withDebug(boolean backend, Scenario scenario, boolean token) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, backend, scenario, token, logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the two choices of the log panel as given; the settings view does not have them and leaves them as they are. */
    public Settings withLog(LogLevel level, boolean date) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken, level, date, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the zero usage lines of the history shown or hidden, as the button of the history panel switches them. */
    public Settings withHistoryZeroLines(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, on, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the text of the lists at the size given; the settings view does not have it and leaves it as it is. */
    public Settings withListFontSize(int pixels) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, pixels, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withAutoInterval(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, on, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withCost(CostControl limits) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, limits, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, severalViews);
    }

    /** These settings with the columns of the history as given: time, used, limit and currency. */
    public Settings withHistoryColumns(boolean time, boolean used, boolean limit, boolean currency) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, cost, time, used, limit, currency, restoreWindow, showCostIcon, severalViews);
    }

    public Settings withRestoreWindow(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, on, showCostIcon, severalViews);
    }

    public Settings withShowCostIcon(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, on, severalViews);
    }

    public Settings withSeveralViews(boolean on) {
        return new Settings(usageIntervalSeconds, logResponse, showPercentage, showCurrency, showHistoryIcon, showLogIcon, showErrorIcon, showInterval,
                showDeltaUsed, showDeltaTime, timeFormat, historyDeltaUsed, historyDeltaTime, historyDate, historyZeroLines, historyFailedLines,
                showTime, showUsed, showLimit, showRefresh, showCountdown, showDuration, stayOnTop, showTitleBar, fakeBackend, fakeScenario, fakeToken,
                logLevel, logDate, listFontSize, autoInterval, cost, historyTime, historyUsed, historyLimit, historyCurrent, restoreWindow, showCostIcon, on);
    }
}
