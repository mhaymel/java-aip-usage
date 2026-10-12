package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.settings.Settings;
import org.example.settings.TimeFormat;
import org.example.usage.Spend;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;

import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalLong;

/**
 * What the strip shows, finished: the texts, the tooltips and the choices of which items there are, worked out
 * here so that the window only places them. See {@code docs/api.md}, under {@code display}.
 */
final class StatusDisplay {

    private StatusDisplay() {
    }

    record Tip(String text, String tooltip) {
    }

    record SpendView(
            String percentText, String percentTooltip, String used, String limit, String usedTooltip,
            String limitTooltip, String severityText) {
    }

    record Message(String kind, String text) {
    }

    /** Which elements of the row are switched on; all twelve can be hidden, and {@code currency} is the symbol of the amounts. */
    record Show(
            boolean percentage, boolean currency, boolean interval, boolean deltaUsed, boolean deltaTime,
            boolean historyIcon, boolean logIcon, boolean errorIcon,
            boolean time, boolean used, boolean limit, boolean refresh, boolean countdown, boolean duration) {
    }

    /**
     * The time in both formats and the amounts with and without the symbol, whatever the settings say, so that the settings view
     * can show a change of the time format or of the symbol in the row before it is applied. A member is {@code null} with its figure.
     */
    record Variants(String timeShort, String timeLong, String usedPlain, String usedSymbol, String limitPlain, String limitSymbol) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record View(
            String time,
            String timeTooltip,
            SpendView spend,
            String placeholder,
            boolean loading,
            Variants variants,
            Tip countdown,
            String countdownAlert,
            boolean countdownAuto,
            Tip interval,
            Tip deltaUsed,
            Tip deltaTime,
            Tip duration,
            Message message,
            Show show,
            boolean titleBar,
            String noSpend) {
    }

    /**
     * @param change the change since the previous row for the newest reading, or {@code null}
     * @param duration how long the latest usage request that has ended took, in milliseconds, a failed one included; empty before one has
     * @param now kept for the callers; nothing shown is counted from it any more
     */
    static View build(
            UsageState state, OptionalLong countdown, ApiHandler.DeltaBody change, OptionalLong duration, Settings settings, Instant now,
            ZoneId zone) {
        UsageSnapshot usage = state.snapshot();
        String time = null;
        String timeTooltip = null;
        SpendView spend = null;
        String placeholder = null;
        if (usage != null) {
            time = Formatting.time(usage.fetchedAt(), settings.timeFormat(), zone);
            timeTooltip = "Last update: " + Formatting.dateTime(usage.fetchedAt(), zone);
            if (usage.spend() != null) {
                spend = spend(usage.spend(), settings.showCurrency());
            }
            if (spend == null) {
                placeholder = "No usage reported";
            }
        } else {
            // Before the first reading the row has no word: the page draws a gray box for each element that is switched on. It
            // draws them for the two words as well, which it shows as the tooltips of the boxes and not in the row.
            placeholder = state.error() != null ? "No data" : null;
        }
        boolean loading = usage == null && state.error() == null;
        Spend amounts = usage == null ? null : usage.spend();
        Variants variants = usage == null ? null : new Variants(
                Formatting.time(usage.fetchedAt(), TimeFormat.HOURS_MINUTES, zone),
                Formatting.time(usage.fetchedAt(), TimeFormat.HOURS_MINUTES_SECONDS, zone),
                amounts == null ? null : amountText(amounts.used(), amounts.currency(), false),
                amounts == null ? null : amountText(amounts.used(), amounts.currency(), true),
                amounts == null ? null : amountText(amounts.limit(), amounts.currency(), false),
                amounts == null ? null : amountText(amounts.limit(), amounts.currency(), true));

        // An HTTP 429 has no message line while the countdown is shown: the countdown is red and carries the message for its
        // hover line instead. With the countdown hidden there is nothing to be red, so the 429 has a line like any other failure.
        boolean alert = state.rateLimited() && settings.showCountdown();
        Message message = state.error() == null || alert ? null : new Message(
                usage != null ? "stale" : "error",
                "Refresh failed at " + Formatting.time(state.errorAt(), settings.timeFormat(), zone) + ": " + state.error());

        return new View(
                time,
                timeTooltip,
                spend,
                placeholder,
                loading,
                variants,
                countdown.isPresent()
                        ? new Tip(Formatting.rowSeconds(countdown.getAsLong()),
                                "Seconds until the next refresh (negative when overdue)\n" + modeLine(settings))
                        : null,
                alert ? state.error() : null,
                // The page makes the countdown blue while this is on, unless it is red after an HTTP 429.
                settings.autoInterval(),
                new Tip(Formatting.rowSeconds(settings.usageIntervalSeconds()), "Time between usage requests\n" + modeLine(settings)),
                deltaUsed(change, usage),
                change == null || change.deltaTime() == null ? null
                        // Never less than 0s, and without the space the history's cell has.
                        : new Tip(Formatting.rowSeconds(Math.max(0, change.deltaTime())), "Time since the previous reading"),
                duration.isPresent() ? new Tip(Formatting.duration(duration.getAsLong()), "How long the latest usage request took") : null,
                message,
                new Show(
                        settings.showPercentage(), settings.showCurrency(), settings.showInterval(), settings.showDeltaUsed(),
                        settings.showDeltaTime(), settings.showHistoryIcon(), settings.showLogIcon(), settings.showErrorIcon(),
                        settings.showTime(), settings.showUsed(), settings.showLimit(), settings.showRefresh(), settings.showCountdown(),
                        settings.showDuration()),
                // Whether the setting gives the window its title bar. The page draws a border round a window that has none,
                // which is one with this off and no panel open.
                settings.showTitleBar(),
                // What is to be said of a reading that came and has no spend. It is no message that is always shown: the
                // page shows it below the row while the mouse is on one of the gray boxes of the amounts.
                usage == null ? null : usage.noSpendMessage());
    }

    /**
     * The line under the tooltip of the countdown and of the interval item that says whether the program finds the interval itself, and the
     * interval: the one it has found while it does, the configured one while it does not. The settings carry the interval in force.
     */
    static String modeLine(Settings settings) {
        return settings.autoInterval()
                ? "Automatic interval is on: the interval is now " + settings.usageIntervalSeconds() + " s"
                : "Automatic interval is off: the configured interval is " + settings.usageIntervalSeconds() + " s";
    }

    private static Tip deltaUsed(ApiHandler.DeltaBody change, UsageSnapshot usage) {
        if (change == null || change.deltaUsed() == null) {
            return null;
        }
        // A change of nothing has no text of its own, so that the history leaves its cell empty; the row writes it, as 0.00.
        String text = change.deltaUsedText() != null ? change.deltaUsedText() : Formatting.amount(0.0);
        String currency = usage != null && usage.spend() != null && usage.spend().currency() != null
                ? ", in " + usage.spend().currency() : "";
        return new Tip(
                text,
                "Change in the amount used since the previous reading" + currency);
    }

    /** An amount as the row shows it: the plain number, or with the currency symbol before it if that is switched on; a missing amount has none. */
    private static String amountText(Double amount, String currency, boolean withSymbol) {
        String text = Formatting.amount(amount);
        return withSymbol && amount != null ? Formatting.currencySymbol(currency) + text : text;
    }

    /**
     * The numbers carry no currency sign unless that is switched on, so the tooltips say what they are and in what unit. The unit
     * is the currency code the response names; without one they say what the numbers are and leave the unit out.
     */
    private static SpendView spend(Spend spend, boolean withSymbol) {
        String unit = spend.currency() != null && !spend.currency().isEmpty() ? ", in " + spend.currency() : "";
        String percentText = spend.percent() != null ? spend.percent() + "%" : null;
        String severity = spend.severity() == null || spend.severity().isEmpty() ? null : spend.severity();
        return new SpendView(
                percentText,
                (percentText != null ? percentText + " of the budget spent" : "Share of the budget spent")
                        + (severity != null ? ". Severity: " + severity : ""),
                amountText(spend.used(), spend.currency(), withSymbol),
                amountText(spend.limit(), spend.currency(), withSymbol),
                "Credits used" + unit,
                "Credit budget" + unit,
                severity);
    }
}
