package org.example;

import org.example.settings.Settings;
import org.example.settings.TimeFormat;
import org.example.usage.Spend;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
import org.example.usage.UsageWindow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusDisplayTest {

    private static final Instant FETCHED = Instant.parse("2026-10-08T14:24:53Z");

    private static final Spend SPEND = new Spend(186.02, 1000.0, "USD", 19, "normal");

    private static UsageState state(UsageSnapshot snapshot, String error, Instant errorAt) {
        try {
            Constructor<UsageState> constructor =
                    UsageState.class.getDeclaredConstructor(UsageSnapshot.class, String.class, Instant.class, boolean.class);
            return constructor.newInstance(snapshot, error, errorAt, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static UsageSnapshot spend(Spend spend) {
        return new UsageSnapshot(FETCHED, spend, List.of());
    }

    private static StatusDisplay.View build(UsageState state, OptionalLong countdown, ApiHandler.DeltaBody change, Settings settings) {
        return StatusDisplay.build(state, countdown, change, settings, FETCHED, ZoneOffset.UTC);
    }

    private static StatusDisplay.View build(UsageState state) {
        return build(state, OptionalLong.empty(), null, Settings.defaults());
    }

    @Test
    void aSpendReadingShowsTheTimeSpentAndBudgetPercentAndSeverity() {
        StatusDisplay.View v = build(state(spend(SPEND), null, null));

        assertEquals("14:24", v.time());
        assertEquals("Last update: 8 Oct 2026, 14:24:53", v.timeTooltip());
        assertEquals("19%", v.spend().percentText());
        assertEquals("186.02", v.spend().used());
        assertEquals("1,000.00", v.spend().limit());
        assertEquals("normal", v.spend().severityKind());
        assertEquals("normal", v.spend().severityText());
        assertEquals(List.of(), v.windows());
        assertNull(v.placeholder());
    }

    @Test
    void theTimeFormatSettingCutsTheTime() {
        Settings seconds = new Settings(60, false, false, false, false, false, TimeFormat.HOURS_MINUTES_SECONDS, false, false);

        assertEquals("14:24:53", build(state(spend(SPEND), null, null), OptionalLong.empty(), null, seconds).time());
        assertEquals("Last update: 8 Oct 2026, 14:24:53", build(state(spend(SPEND), null, null)).timeTooltip(), "the tooltip always has the seconds");
    }

    @Test
    void theTooltipsNameWhatTheNumbersAreAndTheUnitTheResponseGives() {
        StatusDisplay.View v = build(state(spend(SPEND), null, null));

        assertEquals("19% of the budget spent. Severity: normal", v.spend().percentTooltip());

        Settings shown = Settings.defaults().withShowPercentage(true);
        StatusDisplay.View withPercentage = build(state(spend(SPEND), null, null), OptionalLong.empty(), null, shown);
        assertEquals("Credits used, in USD", withPercentage.spend().usedTooltip());
        assertEquals("Credit budget, in USD", withPercentage.spend().limitTooltip());
    }

    @Test
    void withThePercentageHiddenTheAmountsNameTheSeverityTheColourStandsFor() {
        StatusDisplay.View v = build(state(spend(SPEND), null, null));

        assertEquals("Credits used, in USD. Severity: normal", v.spend().usedTooltip());
        assertEquals("Credit budget, in USD. Severity: normal", v.spend().limitTooltip());
        assertEquals("normal", v.spend().severityKind(), "and they still carry the colour");
        assertEquals(false, v.show().percentage());
    }

    @Test
    void withoutACurrencyOrAPercentTheTooltipsStillSayWhatTheNumbersAre() {
        StatusDisplay.View v = build(state(spend(new Spend(1.0, 2.0, null, null, null)), null, null));

        assertEquals("Credits used", v.spend().usedTooltip());
        assertEquals("Credit budget", v.spend().limitTooltip());
        assertEquals("Share of the budget spent", v.spend().percentTooltip());
        assertNull(v.spend().percentText());
        assertNull(v.spend().severityKind());
    }

    @Test
    void missingAmountsAreADashRatherThanZero() {
        StatusDisplay.View v = build(state(spend(new Spend(null, 1000.0, "USD", null, null)), null, null));

        assertEquals("—", v.spend().used());
        assertEquals("1,000.00", v.spend().limit());
    }

    @Test
    void aPlanReadingShowsEachWindowWithTheTimeUntilItResets() {
        UsageSnapshot plan = new UsageSnapshot(FETCHED, null, List.of(
                new UsageWindow("five_hour", 12.34, "2026-10-08T16:30:53Z"),
                new UsageWindow("seven_day", 80, null),
                new UsageWindow("old", 5, "2026-10-08T10:00:00Z"),
                new UsageWindow("odd", 5, "not a date")));

        StatusDisplay.View v = build(state(plan, null, null));

        assertNull(v.spend());
        assertEquals(new StatusDisplay.WindowView("five_hour", "12.3%", "in 2 h 6 min"), v.windows().get(0));
        assertEquals("reset unknown", v.windows().get(1).resetsText());
        assertEquals("reset due", v.windows().get(2).resetsText());
        assertEquals("resets not a date", v.windows().get(3).resetsText());
    }

    @Test
    void anAccountThatReportsNothingSaysSo() {
        assertEquals("No usage reported", build(state(new UsageSnapshot(FETCHED, null, List.of()), null, null)).placeholder());
    }

    @Test
    void beforeTheFirstReadingTheRowIsLoadingOrHasNoData() {
        assertEquals("Loading…", build(state(null, null, null)).placeholder());
        assertEquals("No data", build(state(null, "boom", FETCHED)).placeholder());
        assertNull(build(state(null, null, null)).time());
    }

    @Test
    void aFailedRefreshGivesAMessageWithItsTimeAndTheLastReadingStays() {
        StatusDisplay.View stale = build(state(spend(SPEND), "Anthropic returned HTTP 503.", Instant.parse("2026-10-08T14:25:01Z")));

        assertEquals("stale", stale.message().kind());
        assertEquals("Refresh failed at 14:25: Anthropic returned HTTP 503.", stale.message().text());
        assertEquals("186.02", stale.spend().used());

        StatusDisplay.View none = build(state(null, "Claude Code could not be found.", Instant.parse("2026-10-08T14:17:11Z")));
        assertEquals("error", none.message().kind());
        assertTrue(none.message().text().startsWith("Refresh failed at 14:17: "));
        assertNull(build(state(spend(SPEND), null, null)).message());
    }

    @Test
    void theCountdownIsTheBackendsSecondsWithTheUnitAndMayBeNegative() {
        UsageState s = state(spend(SPEND), null, null);

        assertEquals("42 s", build(s, OptionalLong.of(42), null, Settings.defaults()).countdown().text());
        assertEquals("-3 s", build(s, OptionalLong.of(-3), null, Settings.defaults()).countdown().text());
        assertEquals("Seconds until the next refresh (negative when overdue)",
                build(s, OptionalLong.of(1), null, Settings.defaults()).countdown().tooltip());
        assertNull(build(s, OptionalLong.empty(), null, Settings.defaults()).countdown(), "nothing to count to");
    }

    @Test
    void theChangesAreFinishedTextsAndLeftOutWhenTheyCannotBeWorkedOut() {
        UsageState s = state(spend(SPEND), null, null);

        StatusDisplay.View v = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(0.05, 61L), Settings.defaults());
        assertEquals("+0.05", v.deltaUsed().text());
        assertEquals("Change in the amount used since the previous reading, in USD", v.deltaUsed().tooltip());
        assertEquals("1 m", v.deltaTime().text());
        assertEquals("Time since the previous reading", v.deltaTime().tooltip());

        StatusDisplay.View partly = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(null, 60L), Settings.defaults());
        assertNull(partly.deltaUsed());
        assertNotNull(partly.deltaTime());

        StatusDisplay.View none = build(s, OptionalLong.empty(), null, Settings.defaults());
        assertNull(none.deltaUsed());
        assertNull(none.deltaTime());
    }

    @Test
    void theIntervalIsTheOneInForceAsFinishedText() {
        UsageState s = state(spend(SPEND), null, null);
        Settings every120 = Settings.defaults().withUsageIntervalSeconds(120);

        StatusDisplay.View v = build(s, OptionalLong.empty(), null, every120);

        assertEquals("120 s", v.interval().text());
        assertEquals("Time between usage requests", v.interval().tooltip());
        assertEquals("60 s", build(s).interval().text());
    }

    @Test
    void theSettingsSayWhichOptionalItemsAreSwitchedOn() {
        Settings on = new Settings(60, false, true, true, true, false, TimeFormat.HOURS_MINUTES, false, false);

        assertEquals(new StatusDisplay.Show(true, true, true, false), build(state(spend(SPEND), null, null), OptionalLong.empty(), null, on).show());
        assertEquals(new StatusDisplay.Show(false, false, false, false), build(state(spend(SPEND), null, null)).show(), "all off by default");
    }
}
