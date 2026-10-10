package org.example;

import org.example.settings.Settings;
import org.example.settings.TimeFormat;
import org.example.usage.Spend;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new UsageSnapshot(FETCHED, spend);
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
        assertNull(v.placeholder());
    }

    @Test
    void theTimeFormatSettingCutsTheTime() {
        Settings seconds = new Settings(60, false, false, false, true, true, true, false, false, false, TimeFormat.HOURS_MINUTES_SECONDS, false, false, false, true, true);

        assertEquals("14:24:53", build(state(spend(SPEND), null, null), OptionalLong.empty(), null, seconds).time());
        assertEquals("Last update: 8 Oct 2026, 14:24:53", build(state(spend(SPEND), null, null)).timeTooltip(), "the tooltip always has the seconds");
    }

    @Test
    void theTooltipsNameWhatTheNumbersAreAndTheUnitTheResponseGives() {
        StatusDisplay.View v = build(state(spend(SPEND), null, null));

        assertEquals("19% of the budget spent. Severity: normal", v.spend().percentTooltip(), "the percentage names the severity");
        assertEquals("Credits used, in USD", v.spend().usedTooltip());
        assertEquals("Credit budget, in USD", v.spend().limitTooltip());
    }

    @Test
    void theAmountsAreWithTheCurrencySymbolOnlyWhenThatIsSwitchedOn() {
        UsageState usd = state(spend(SPEND), null, null);
        Settings symbol = Settings.defaults().withShowCurrency(true);

        StatusDisplay.View plain = build(usd);
        StatusDisplay.View with = build(usd, OptionalLong.empty(), null, symbol);

        assertEquals("186.02", plain.spend().used(), "off by default");
        assertEquals("$186.02", with.spend().used());
        assertEquals("$1,000.00", with.spend().limit());
        assertEquals("Credits used, in USD", with.spend().usedTooltip(), "the tooltips are the same");
        assertEquals(true, with.show().currency());
        assertEquals(false, plain.show().currency());
    }

    @Test
    void anotherCurrencyIsItsCodeAndASpaceAndNoCurrencyIsNoSymbol() {
        Settings symbol = Settings.defaults().withShowCurrency(true);

        StatusDisplay.View eur = build(state(spend(new Spend(186.02, 1000.0, "EUR", 19, "normal")), null, null), OptionalLong.empty(), null, symbol);
        StatusDisplay.View none = build(state(spend(new Spend(186.02, 1000.0, null, 19, "normal")), null, null), OptionalLong.empty(), null, symbol);
        StatusDisplay.View missing = build(state(spend(new Spend(null, 1000.0, "USD", null, null)), null, null), OptionalLong.empty(), null, symbol);

        assertEquals("EUR 186.02", eur.spend().used());
        assertEquals("EUR 1,000.00", eur.spend().limit());
        assertEquals("186.02", none.spend().used());
        assertEquals("\u2014", missing.spend().used(), "a missing amount has no symbol");
        assertEquals("$1,000.00", missing.spend().limit());
    }

    @Test
    void theShowFlagsSayWhichButtonsThereAre() {
        Settings noIcons = Settings.defaults().withShowHistoryIcon(false).withShowLogIcon(false).withShowErrorIcon(false);

        StatusDisplay.View all = build(state(spend(SPEND), null, null));
        StatusDisplay.View none = build(state(spend(SPEND), null, null), OptionalLong.empty(), null, noIcons);

        assertEquals(true, all.show().historyIcon());
        assertEquals(true, all.show().logIcon());
        assertEquals(true, all.show().errorIcon());
        assertEquals(false, none.show().historyIcon());
        assertEquals(false, none.show().logIcon());
        assertEquals(false, none.show().errorIcon());
    }

    @Test
    void theTooltipsOfTheAmountsNeverNameTheSeverityWhetherOrNotThePercentageIsShown() {
        for (boolean percentage : new boolean[] {false, true}) {
            Settings settings = Settings.defaults().withShowPercentage(percentage);
            StatusDisplay.View v = build(state(spend(SPEND), null, null), OptionalLong.empty(), null, settings);

            assertEquals("Credits used, in USD", v.spend().usedTooltip(), "percentage " + percentage);
            assertEquals("Credit budget, in USD", v.spend().limitTooltip(), "percentage " + percentage);
            assertEquals("normal", v.spend().severityKind(), "the colour is still there");
            assertEquals(percentage, v.show().percentage());
        }
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
    void anAccountThatReportsNothingSaysSo() {
        assertEquals("No usage reported", build(state(new UsageSnapshot(FETCHED, null), null, null)).placeholder());
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
    void anHttp429HasNoMessageLineButTheCountdownCarriesTheMessage() {
        String message = "Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min.";
        UsageState limited = new UsageState(spend(SPEND), message, Instant.parse("2026-10-08T14:25:01Z"), false, true);

        StatusDisplay.View v = build(limited, OptionalLong.of(120), null, Settings.defaults());

        assertNull(v.message(), "no message line");
        assertEquals(message, v.countdownAlert(), "the hover line and the red come from this");
        assertEquals("120 s", v.countdown().text());
        assertEquals("186.02", v.spend().used(), "the reading stays on show");
    }

    @Test
    void otherFailuresKeepTheirMessageLineAndTheCountdownIsNotAlerted() {
        UsageState failed = new UsageState(spend(SPEND), "Anthropic returned HTTP 503.", Instant.parse("2026-10-08T14:25:01Z"), false, false);

        StatusDisplay.View v = build(failed, OptionalLong.of(30), null, Settings.defaults());

        assertEquals("Refresh failed at 14:25: Anthropic returned HTTP 503.", v.message().text());
        assertNull(v.countdownAlert());
        assertNull(build(state(spend(SPEND), null, null)).countdownAlert(), "no error, no alert");
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
        assertEquals("61 s", v.deltaTime().text(), "in seconds, never minutes");
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
    void aChangeOfZeroHasNoItemInTheRowButItsTimeStillHas() {
        UsageState s = state(spend(SPEND), null, null);

        StatusDisplay.View none = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(0.0, 60L), Settings.defaults());
        assertNull(none.deltaUsed(), "no 0.00");
        assertEquals("60 s", none.deltaTime().text(), "in seconds, never minutes");

        assertNull(build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(0.004, 60L), Settings.defaults()).deltaUsed(),
                "rounds to zero");
        assertEquals("+0.01", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(0.01, 60L), Settings.defaults()).deltaUsed().text());
        assertEquals("-0.01", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(-0.01, 60L), Settings.defaults()).deltaUsed().text());
    }

    @Test
    void theSettingsSayWhichOptionalItemsAreSwitchedOn() {
        Settings on = new Settings(60, false, true, false, true, true, true, true, true, false, TimeFormat.HOURS_MINUTES, false, false, false, true, true);

        assertEquals(new StatusDisplay.Show(true, false, true, true, false, true, true, true), build(state(spend(SPEND), null, null), OptionalLong.empty(), null, on).show());
        assertEquals(new StatusDisplay.Show(false, false, false, false, false, true, true, true), build(state(spend(SPEND), null, null)).show(), "all off by default");
    }

    // ---- the seat-based format

    private static final UsageSnapshot SEAT = UsageSnapshot.seatBased(FETCHED, new org.example.usage.PlanLimits(
            new org.example.usage.PlanLimits.Limit(12.34, "2026-10-08T16:30:53Z"),
            new org.example.usage.PlanLimits.Limit(80, "2026-10-10T02:00:00+00:00")));

    @Test
    void aSeatBasedReadingShowsTheTwoLimitsWithTheTimeUntilEachIsSetBack() {
        StatusDisplay.View v = build(state(SEAT, null, null));

        assertEquals("seat-based", v.format());
        assertNull(v.spend());
        assertNull(v.placeholder());
        assertEquals(new StatusDisplay.LimitView(
                "5h", "12.3%", "Five-hour session limit: 12.34% used. Resets 8 Oct 2026, 16:30:53", "in 2 h 6 min"), v.seat().fiveHour());
        assertEquals(new StatusDisplay.LimitView(
                "7d", "80%", "Weekly limit: 80% used. Resets 10 Oct 2026, 02:00:00", "in 1 d 11 h"), v.seat().sevenDay());
    }

    @Test
    void theResetTimesOfTheRowHaveASwitchAndALimitWithNoneSaysSo() {
        UsageSnapshot odd = UsageSnapshot.seatBased(FETCHED, new org.example.usage.PlanLimits(
                new org.example.usage.PlanLimits.Limit(150.5, null), new org.example.usage.PlanLimits.Limit(5, "2026-10-08T10:00:00Z")));

        StatusDisplay.View shown = build(state(odd, null, null));
        StatusDisplay.View hidden = build(state(odd, null, null), OptionalLong.empty(), null,
                Settings.defaults().withSeat(false, false, true, false));

        assertEquals("150.5%", shown.seat().fiveHour().text(), "over 100 is shown as it is");
        assertEquals("reset unknown", shown.seat().fiveHour().resetsText());
        assertEquals("Five-hour session limit: 150.5% used", shown.seat().fiveHour().tooltip(), "no second sentence without a reset time");
        assertEquals("reset due", shown.seat().sevenDay().resetsText());
        assertNull(hidden.seat().fiveHour().resetsText(), "switched off: the row has no reset time");
        assertEquals("5%", hidden.seat().sevenDay().text());
    }

    @Test
    void theChangeOfASeatBasedReadingIsBothPercentagesInOneItemWithItsOwnSwitch() {
        ApiHandler.DeltaBody change = ApiHandler.DeltaBody.ofSeat(0.6, 0.0, 60L);

        StatusDisplay.View off = build(state(SEAT, null, null), OptionalLong.empty(), change, Settings.defaults());
        StatusDisplay.View on = build(state(SEAT, null, null), OptionalLong.empty(), change,
                Settings.defaults().withSeat(true, true, true, false));

        assertEquals("+0.6 / 0.0", on.deltaUsed().text());
        assertEquals("Change of the session limit and of the weekly limit since the previous reading, in percentage points",
                on.deltaUsed().tooltip());
        assertTrue(on.show().deltaUsed());
        assertFalse(off.show().deltaUsed(), "off by default, whatever the switch of the other format says");
        assertNull(build(state(SEAT, null, null), OptionalLong.empty(), ApiHandler.DeltaBody.ofSeat(0.0, 0.01, 60L), Settings.defaults())
                .deltaUsed(), "neither changed: no item");
    }

    @Test
    void aUsageBasedReadingSaysItsFormatAndHasNoLimits() {
        StatusDisplay.View v = build(state(spend(SPEND), null, null));

        assertEquals("usage-based", v.format());
        assertNull(v.seat());
        assertNull(build(state(null, null, null)).format(), "no reading, no format");
    }
}
