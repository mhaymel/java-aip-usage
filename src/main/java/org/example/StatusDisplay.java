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
            boolean time, boolean used, boolean limit, boolean refresh, boolean countdown) {
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
            Tip interval,
            Tip deltaUsed,
            Tip deltaTime,
            Message message,
            Show show) {
    }

    /**
     * @param change the change since the previous row for the newest reading, or {@code null}
     * @param now kept for the callers; nothing shown is counted from it any more
     */
    static View build(
            UsageState state, OptionalLong countdown, ApiHandler.DeltaBody change, Settings settings, Instant now, ZoneId zone) {
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
            // Before the first reading the row has no word: the page draws a gray box for each element that is switched on.
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
                        ? new Tip(Formatting.rowSeconds(countdown.getAsLong()), "Seconds until the next refresh (negative when overdue)")
                        : null,
                alert ? state.error() : null,
                new Tip(Formatting.rowSeconds(settings.usageIntervalSeconds()), "Time between usage requests"),
                deltaUsed(change, usage),
                change == null || change.deltaTime() == null ? null
                        // Never less than 0s, and without the space the history's cell has.
                        : new Tip(Formatting.rowSeconds(Math.max(0, change.deltaTime())), "Time since the previous reading"),
                message,
                new Show(
                        settings.showPercentage(), settings.showCurrency(), settings.showInterval(), settings.showDeltaUsed(),
                        settings.showDeltaTime(), settings.showHistoryIcon(), settings.showLogIcon(), settings.showErrorIcon(),
                        settings.showTime(), settings.showUsed(), settings.showLimit(), settings.showRefresh(), settings.showCountdown()));
    }

    private static Tip deltaUsed(ApiHandler.DeltaBody change, UsageSnapshot usage) {
        if (change == null || change.deltaUsed() == null || change.deltaUsedText() == null) {
            return null;
        }
        String currency = usage != null && usage.spend() != null && usage.spend().currency() != null
                ? ", in " + usage.spend().currency() : "";
        return new Tip(
                change.deltaUsedText(),
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
