package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.settings.Settings;
import org.example.usage.PlanLimits;
import org.example.usage.Spend;
import org.example.usage.UsageFormat;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
            String limitTooltip, String severityText, String severityKind) {
    }

    /**
     * One limit of the seat-based format, as the row shows it.
     *
     * @param label {@code 5h} or {@code 7d}
     * @param text the utilization, {@code 12.3%}
     * @param resetsText the time until it is set back, {@code in 2 h 5 min}, or {@code null} when the row does not show it
     */
    record LimitView(String label, String text, String tooltip, String resetsText) {
    }

    record SeatView(LimitView fiveHour, LimitView sevenDay) {
    }

    record Message(String kind, String text) {
    }

    /** Which optional items of the row are switched on. The countdown is not optional. */
    record Show(
            boolean percentage, boolean currency, boolean interval, boolean deltaUsed, boolean deltaTime,
            boolean historyIcon, boolean logIcon, boolean errorIcon) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record View(
            String time,
            String timeTooltip,
            String format,
            SpendView spend,
            SeatView seat,
            String placeholder,
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
     * @param now the time the remaining times of the seat-based format are counted to
     */
    static View build(
            UsageState state, OptionalLong countdown, ApiHandler.DeltaBody change, Settings settings, Instant now, ZoneId zone) {
        UsageSnapshot usage = state.snapshot();
        String time = null;
        String timeTooltip = null;
        SpendView spend = null;
        SeatView seat = null;
        String placeholder = null;
        if (usage != null) {
            time = Formatting.time(usage.fetchedAt(), settings.timeFormat(), zone);
            timeTooltip = "Last update: " + Formatting.dateTime(usage.fetchedAt(), zone);
            if (usage.spend() != null) {
                spend = spend(usage.spend(), settings.showCurrency());
            }
            if (usage.limits() != null) {
                seat = new SeatView(
                        limit("5h", "Five-hour session limit", usage.limits().fiveHour(), settings.seatShowResets(), now, zone),
                        limit("7d", "Weekly limit", usage.limits().sevenDay(), settings.seatShowResets(), now, zone));
            }
            if (spend == null && seat == null) {
                placeholder = "No usage reported";
            }
        } else {
            placeholder = state.error() != null ? "No data" : "Loading…";
        }

        // An HTTP 429 has no message line: the countdown is red and carries the message for its hover line instead.
        Message message = state.error() == null || state.rateLimited() ? null : new Message(
                usage != null ? "stale" : "error",
                "Refresh failed at " + Formatting.time(state.errorAt(), settings.timeFormat(), zone) + ": " + state.error());

        return new View(
                time,
                timeTooltip,
                usage == null || usage.format() == null ? null : usage.format().text(),
                spend,
                seat,
                placeholder,
                countdown.isPresent()
                        ? new Tip(countdown.getAsLong() + " s", "Seconds until the next refresh (negative when overdue)")
                        : null,
                state.rateLimited() ? state.error() : null,
                new Tip(settings.usageIntervalSeconds() + " s", "Time between usage requests"),
                deltaUsed(change, usage),
                change == null || change.deltaTime() == null ? null
                        : new Tip(change.deltaSecondsText(), "Time since the previous reading"),
                message,
                // The change is one item of the row in both formats, with a switch of its own in each.
                new Show(
                        settings.showPercentage(), settings.showCurrency(), settings.showInterval(),
                        seat != null ? settings.seatShowDelta() : settings.showDeltaUsed(),
                        settings.showDeltaTime(), settings.showHistoryIcon(), settings.showLogIcon(), settings.showErrorIcon()));
    }

    private static Tip deltaUsed(ApiHandler.DeltaBody change, UsageSnapshot usage) {
        if (usage != null && usage.format() == UsageFormat.SEAT_BASED) {
            // Both percentages in one item; one that did not change is written as no change, and with neither there is no item.
            if (change == null || (change.deltaUsedText() == null && change.deltaOtherText() == null)) {
                return null;
            }
            return new Tip(
                    (change.deltaUsedText() == null ? "0.0" : change.deltaUsedText())
                            + " / " + (change.deltaOtherText() == null ? "0.0" : change.deltaOtherText()),
                    "Change of the session limit and of the weekly limit since the previous reading, in percentage points");
        }
        if (change == null || change.deltaUsed() == null || change.deltaUsedText() == null) {
            return null;
        }
        String currency = usage != null && usage.spend() != null && usage.spend().currency() != null
                ? ", in " + usage.spend().currency() : "";
        return new Tip(
                change.deltaUsedText(),
                "Change in the amount used since the previous reading" + currency);
    }

    /**
     * One limit: its utilization, and how long until it is set back. Remaining time rather than a clock time, because the
     * weekly limit is days away and a time of day alone would mislead; the exact date and time is in the tooltip.
     */
    private static LimitView limit(String label, String name, PlanLimits.Limit limit, boolean showResets, Instant now, ZoneId zone) {
        Instant at = null;
        if (limit.resetsAt() != null) {
            try {
                at = OffsetDateTime.parse(limit.resetsAt()).toInstant();
            } catch (DateTimeException e) {
                // A reset time that cannot be read is no reset time.
            }
        }
        String resets = at == null ? "reset unknown" : at.isAfter(now) ? "in " + Formatting.span(Duration.between(now, at)) : "reset due";
        return new LimitView(
                label,
                Formatting.percent(limit.utilization()),
                name + ": " + Formatting.plain(BigDecimal.valueOf(limit.utilization())) + "% used"
                        + (at == null ? "" : ". Resets " + Formatting.dateTime(at, zone)),
                showResets ? resets : null);
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
                severity,
                severity == null ? null : Formatting.severityKind(severity));
    }
}
