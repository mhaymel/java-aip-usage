package org.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.settings.Settings;
import org.example.usage.Spend;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
import org.example.usage.UsageWindow;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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

    record WindowView(String name, String utilizationText, String resetsText) {
    }

    record Message(String kind, String text) {
    }

    /** Which optional items of the row are switched on. The countdown is not optional. */
    record Show(boolean interval, boolean deltaUsed, boolean deltaTime) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record View(
            String time,
            String timeTooltip,
            SpendView spend,
            List<WindowView> windows,
            String placeholder,
            Tip countdown,
            Tip interval,
            Tip deltaUsed,
            Tip deltaTime,
            Message message,
            Show show) {
    }

    /**
     * @param change the change since the previous row for the newest reading, or {@code null}
     * @param now the time the remaining times are counted to
     */
    static View build(
            UsageState state, OptionalLong countdown, ApiHandler.DeltaBody change, Settings settings, Instant now, ZoneId zone) {
        UsageSnapshot usage = state.snapshot();
        String time = null;
        String timeTooltip = null;
        SpendView spend = null;
        List<WindowView> windows = List.of();
        String placeholder = null;
        if (usage != null) {
            time = Formatting.time(usage.fetchedAt(), settings.timeFormat(), zone);
            timeTooltip = "Last update: " + Formatting.dateTime(usage.fetchedAt(), zone);
            if (usage.spend() != null) {
                spend = spend(usage.spend());
            }
            windows = usage.windows().stream().map(w -> window(w, now)).toList();
            if (spend == null && windows.isEmpty()) {
                placeholder = "No usage reported";
            }
        } else {
            placeholder = state.error() != null ? "No data" : "Loading…";
        }

        Message message = state.error() == null ? null : new Message(
                usage != null ? "stale" : "error",
                "Refresh failed at " + Formatting.time(state.errorAt(), settings.timeFormat(), zone) + ": " + state.error());

        return new View(
                time,
                timeTooltip,
                spend,
                windows,
                placeholder,
                countdown.isPresent()
                        ? new Tip(countdown.getAsLong() + " s", "Seconds until the next refresh (negative when overdue)")
                        : null,
                new Tip(settings.usageIntervalSeconds() + " s", "Time between usage requests"),
                deltaUsed(change, usage),
                change == null || change.deltaTime() == null ? null
                        : new Tip(change.deltaTimeText(), "Time since the previous reading"),
                message,
                new Show(settings.showInterval(), settings.showDeltaUsed(), settings.showDeltaTime()));
    }

    private static Tip deltaUsed(ApiHandler.DeltaBody change, UsageSnapshot usage) {
        if (change == null || change.deltaUsed() == null) {
            return null;
        }
        String currency = usage != null && usage.spend() != null && usage.spend().currency() != null
                ? ", in " + usage.spend().currency() : "";
        return new Tip(
                change.deltaUsedText(),
                "Change in the amount used since the previous reading" + currency);
    }

    /**
     * The numbers carry no currency sign, so the tooltips say what they are and in what unit. The unit
     * is the currency code the response names; without one they say what the numbers are and leave the unit out.
     */
    private static SpendView spend(Spend spend) {
        String unit = spend.currency() != null && !spend.currency().isEmpty() ? ", in " + spend.currency() : "";
        String percentText = spend.percent() != null ? spend.percent() + "%" : null;
        String severity = spend.severity() == null || spend.severity().isEmpty() ? null : spend.severity();
        return new SpendView(
                percentText,
                (percentText != null ? percentText + " of the budget spent" : "Share of the budget spent")
                        + (severity != null ? ". Severity: " + severity : ""),
                Formatting.amount(spend.used()),
                Formatting.amount(spend.limit()),
                "Credits used" + unit,
                "Credit budget" + unit,
                severity,
                severity == null ? null : Formatting.severityKind(severity));
    }

    /**
     * A plan window: its name as received, its utilization, and how long until it resets. Remaining time
     * rather than a clock time, because a reset can be days away and a time of day alone would mislead.
     */
    private static WindowView window(UsageWindow window, Instant now) {
        String resets;
        if (window.resetsAt() == null) {
            resets = "reset unknown";
        } else {
            try {
                Instant at = Instant.parse(window.resetsAt());
                resets = at.isAfter(now) ? "in " + Formatting.span(Duration.between(now, at)) : "reset due";
            } catch (DateTimeException e) {
                resets = "resets " + window.resetsAt();
            }
        }
        return new WindowView(window.key(), Formatting.percent(window.utilization()), resets);
    }
}
