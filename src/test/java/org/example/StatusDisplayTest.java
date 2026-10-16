package org.example;

import org.example.money.Amount;
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

    private static final Spend SPEND = new Spend(Amount.parse("186.02"), Amount.parse("1000.0"), "USD", 19, "normal");

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
        return StatusDisplay.build(state, countdown, change, OptionalLong.empty(), settings, FETCHED, ZoneOffset.UTC);
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

        assertEquals("19% of the monthly limit spent", v.spend().percentTooltip(), "the severity is named nowhere (since 0.83)");
        assertEquals("Monthly credit used, in USD", v.spend().usedTooltip());
        assertEquals("Monthly limit sent by the server, in USD", v.spend().limitTooltip());
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
        assertEquals("Monthly credit used, in USD", with.spend().usedTooltip(), "the tooltips are the same");
        assertEquals(true, with.show().currency());
        assertEquals(false, plain.show().currency());
    }

    @Test
    void anotherCurrencyIsItsCodeAndASpaceAndNoCurrencyIsNoSymbol() {
        Settings symbol = Settings.defaults().withShowCurrency(true);

        StatusDisplay.View eur = build(state(spend(new Spend(Amount.parse("186.02"), Amount.parse("1000.0"), "EUR", 19, "normal")), null, null), OptionalLong.empty(), null, symbol);
        StatusDisplay.View none = build(state(spend(new Spend(Amount.parse("186.02"), Amount.parse("1000.0"), null, 19, "normal")), null, null), OptionalLong.empty(), null, symbol);
        StatusDisplay.View missing = build(state(spend(new Spend(null, Amount.parse("1000.0"), "USD", null, null)), null, null), OptionalLong.empty(), null, symbol);

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

            assertEquals("Monthly credit used, in USD", v.spend().usedTooltip(), "percentage " + percentage);
            assertEquals("Monthly limit sent by the server, in USD", v.spend().limitTooltip(), "percentage " + percentage);
            assertEquals(percentage, v.show().percentage());
        }
    }

    @Test
    void withoutACurrencyOrAPercentTheTooltipsStillSayWhatTheNumbersAre() {
        StatusDisplay.View v = build(state(spend(new Spend(Amount.parse("1.0"), Amount.parse("2.0"), null, null, null)), null, null));

        assertEquals("Monthly credit used", v.spend().usedTooltip());
        assertEquals("Monthly limit sent by the server", v.spend().limitTooltip());
        assertEquals("Share of the monthly limit spent", v.spend().percentTooltip());
        assertNull(v.spend().percentText());
    }

    @Test
    void missingAmountsAreADashRatherThanZero() {
        StatusDisplay.View v = build(state(spend(new Spend(null, Amount.parse("1000.0"), "USD", null, null)), null, null));

        assertEquals("—", v.spend().used());
        assertEquals("1,000.00", v.spend().limit());
    }

    @Test
    void anAccountThatReportsNothingSaysSo() {
        assertEquals("No usage reported", build(state(new UsageSnapshot(FETCHED, null), null, null)).placeholder());
    }

    @Test
    void beforeTheFirstReadingTheRowIsLoadingWithoutAWordOrHasNoData() {
        StatusDisplay.View waiting = build(state(null, null, null));
        assertTrue(waiting.loading(), "the page draws its gray boxes from this");
        assertNull(waiting.placeholder(), "and there is no word");
        assertNull(waiting.time());
        assertNull(waiting.variants());

        StatusDisplay.View failed = build(state(null, "boom", FETCHED));
        assertEquals("No data", failed.placeholder());
        assertFalse(failed.loading(), "a failure is not loading");
        assertFalse(build(state(spend(SPEND), null, null)).loading());
    }

    @Test
    void theTimeAndTheAmountsAreSentInBothFormsForThePreviewOfTheSettings() {
        StatusDisplay.Variants v = build(state(spend(SPEND), null, null)).variants();

        assertEquals("14:24", v.timeShort());
        assertEquals("14:24:53", v.timeLong());
        assertEquals("186.02", v.usedPlain());
        assertEquals("$186.02", v.usedSymbol());
        assertEquals("1,000.00", v.limitPlain());
        assertEquals("$1,000.00", v.limitSymbol());

        StatusDisplay.Variants none = build(state(new UsageSnapshot(FETCHED, null), null, null)).variants();
        assertEquals("14:24", none.timeShort(), "a reading with no spend still has its time");
        assertNull(none.usedPlain());
        assertNull(none.limitSymbol());
    }

    @Test
    void withTheCountdownHiddenAnHttp429HasAMessageLineLikeAnyOtherFailure() {
        String message = "Anthropic is rate limiting usage requests (HTTP 429).";
        UsageState limited = new UsageState(spend(SPEND), message, Instant.parse("2026-10-08T14:25:01Z"), false, true);
        Settings hidden = Settings.defaults().withRowElements(true, true, true, true, false);

        StatusDisplay.View v = build(limited, OptionalLong.of(120), null, hidden);

        assertNull(v.countdownAlert(), "there is no countdown to be red");
        assertEquals("Refresh failed at 14:25: " + message, v.message().text());
        assertFalse(v.show().countdown());
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
        String message = "Anthropic is rate limiting usage requests (HTTP 429).";
        UsageState limited = new UsageState(spend(SPEND), message, Instant.parse("2026-10-08T14:25:01Z"), false, true);

        StatusDisplay.View v = build(limited, OptionalLong.of(120), null, Settings.defaults());

        assertNull(v.message(), "no message line");
        assertEquals(message, v.countdownAlert(), "the hover line and the red come from this");
        assertEquals("120s", v.countdown().text());
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

        assertEquals("42s", build(s, OptionalLong.of(42), null, Settings.defaults()).countdown().text());
        assertEquals("-3s", build(s, OptionalLong.of(-3), null, Settings.defaults()).countdown().text());
        assertEquals("Seconds until the next refresh\nAutomatic interval is off: the configured interval is 60 s",
                build(s, OptionalLong.of(1), null, Settings.defaults()).countdown().tooltip());
        assertNull(build(s, OptionalLong.empty(), null, Settings.defaults()).countdown(), "nothing to count to");
    }

    @Test
    void theChangesAreFinishedTextsAndLeftOutWhenTheyCannotBeWorkedOut() {
        UsageState s = state(spend(SPEND), null, null);

        StatusDisplay.View v = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("0.05"), 61L), Settings.defaults());
        assertEquals("+0.05", v.deltaUsed().text());
        assertEquals("Change in the amount used since the previous reading, in USD", v.deltaUsed().tooltip());
        assertEquals("61s", v.deltaTime().text(), "in seconds, never minutes, and no space");
        assertEquals("Time since the previous reading", v.deltaTime().tooltip());

        StatusDisplay.View partly = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(null, 60L), Settings.defaults());
        assertNull(partly.deltaUsed());
        assertNotNull(partly.deltaTime());

        StatusDisplay.View none = build(s, OptionalLong.empty(), null, Settings.defaults());
        assertNull(none.deltaUsed());
        assertNull(none.deltaTime());
    }

    @Test
    void theCountdownAndTheIntervalSayWhetherTheAutomaticIntervalIsOnAndTheIntervalNow() {
        UsageState s = state(spend(SPEND), null, null);
        Settings on = Settings.defaults().withAutoInterval(true).withUsageIntervalSeconds(35);

        StatusDisplay.View v = build(s, OptionalLong.of(7), null, on);

        assertTrue(v.countdownAuto(), "the page makes the countdown blue");
        assertEquals("Seconds until the next refresh\nAutomatic interval is on: the interval is now 35 s", v.countdown().tooltip());
        assertEquals("Time between usage requests\nAutomatic interval is on: the interval is now 35 s", v.interval().tooltip());
        assertFalse(build(s, OptionalLong.of(7), null, Settings.defaults()).countdownAuto());
    }

    @Test
    void theIntervalIsTheOneInForceAsFinishedText() {
        UsageState s = state(spend(SPEND), null, null);
        Settings every120 = Settings.defaults().withUsageIntervalSeconds(120);

        StatusDisplay.View v = build(s, OptionalLong.empty(), null, every120);

        assertEquals("120s", v.interval().text());
        assertEquals("Time between usage requests\nAutomatic interval is off: the configured interval is 120 s", v.interval().tooltip());
        assertEquals("60s", build(s).interval().text());
    }

    @Test
    void aChangeOfZeroIsWrittenInTheRowWithoutASignAndAChangeThatIsNotKnownHasNoItem() {
        UsageState s = state(spend(SPEND), null, null);

        StatusDisplay.View none = build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("0.0"), 60L), Settings.defaults());
        assertEquals("0.00", none.deltaUsed().text(), "written, with no sign and no currency symbol");
        assertEquals("Change in the amount used since the previous reading, in USD", none.deltaUsed().tooltip());
        assertEquals("60s", none.deltaTime().text(), "in seconds, never minutes");
        assertNull(ApiHandler.DeltaBody.of(Amount.parse("0.0"), 60L).deltaUsedText(), "the history's cell stays empty for it");

        assertEquals("0.00", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("0.004"), 60L), Settings.defaults()).deltaUsed().text(),
                "rounds to zero");
        assertEquals("0.00", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("-0.004"), 60L), Settings.defaults()).deltaUsed().text(),
                "and never -0.00");
        assertNull(build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(null, 60L), Settings.defaults()).deltaUsed(),
                "no reading before, or no amount: not known, which the row shows as a gray box");
        assertEquals("+0.01", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("0.01"), 60L), Settings.defaults()).deltaUsed().text());
        assertEquals("-0.01", build(s, OptionalLong.empty(), ApiHandler.DeltaBody.of(Amount.parse("-0.01"), 60L), Settings.defaults()).deltaUsed().text());
    }

    @Test
    void theSettingsSayWhichOptionalItemsAreSwitchedOn() {
        Settings on = new Settings(60, false, true, false, true, true, true, true, true, false, TimeFormat.HOURS_MINUTES, false, false, false, true, true);

        assertEquals(
                new StatusDisplay.Show(true, false, true, true, false, true, true, true, true, true, true, true, true, true, false, false, true, false, false, false, true, true),
                build(state(spend(SPEND), null, null), OptionalLong.empty(), null, on).show());
        assertEquals(
                new StatusDisplay.Show(false, false, false, false, false, true, true, true, true, true, true, true, true, true, false, false, true, false, false, false, true, true),
                build(state(spend(SPEND), null, null)).show(), "the optional ones off by default, the five that were always there on");
    }

    @Test
    void theRequestDurationIsATipOnceARequestHasEndedAndItsSwitchIsOffByDefault() {
        UsageState s = state(spend(SPEND), null, null);

        assertNull(build(s).duration(), "none before a request has ended");
        assertFalse(build(s).show().duration());

        StatusDisplay.View v = StatusDisplay.build(
                s, OptionalLong.empty(), null, OptionalLong.of(1034), Settings.defaults().withShowDuration(true), FETCHED, ZoneOffset.UTC);
        assertEquals("1s034ms", v.duration().text());
        assertEquals("How long the latest usage request took", v.duration().tooltip());
        assertTrue(v.show().duration());

        UsageState failed = state(spend(SPEND), "Anthropic returned HTTP 503.", FETCHED);
        assertEquals("87ms", StatusDisplay.build(failed, OptionalLong.empty(), null, OptionalLong.of(87), Settings.defaults(), FETCHED, ZoneOffset.UTC)
                .duration().text(), "a failed request has a duration too");
    }

    @Test
    void theFiveElementsThatWereAlwaysThereCanBeSwitchedOffEachByItself() {
        boolean[][] cases = {
                {false, true, true, true, true}, {true, false, true, true, true}, {true, true, false, true, true},
                {true, true, true, false, true}, {true, true, true, true, false}};
        for (boolean[] c : cases) {
            Settings settings = Settings.defaults().withRowElements(c[0], c[1], c[2], c[3], c[4]);

            StatusDisplay.Show show = build(state(spend(SPEND), null, null), OptionalLong.empty(), null, settings).show();

            assertEquals(c[0], show.time());
            assertEquals(c[1], show.used());
            assertEquals(c[2], show.limit());
            assertEquals(c[3], show.refresh());
            assertEquals(c[4], show.countdown());
        }
    }

    @Test
    void theViewSaysWhetherTheSettingGivesTheWindowItsTitleBar() {
        UsageState s = state(spend(SPEND), null, null);

        assertTrue(build(s, OptionalLong.empty(), null, Settings.defaults()).titleBar(), "on by default");
        assertFalse(build(s, OptionalLong.empty(), null, Settings.defaults().withShowTitleBar(false)).titleBar());
    }

    @Test
    void aReadingWithNoSpendSaysSoInAFieldOfItsOwnAndNotAsAMessageThatIsAlwaysShown() {
        UsageState seat = state(new UsageSnapshot(FETCHED, null, true), null, null);
        StatusDisplay.View v = build(seat);
        assertNull(v.message(), "no line that is always there");
        assertEquals("Received, but this account reports no spend: it looks like a seat-based account, which is not supported yet.", v.noSpend());
        assertEquals("No usage reported", v.placeholder(), "the gray boxes and their tooltips are as before");

        UsageState plain = state(new UsageSnapshot(FETCHED, null, false), null, null);
        assertEquals("Received, but Anthropic reports no spend for this account (spend is not enabled).", build(plain).noSpend());

        assertNull(build(state(spend(SPEND), null, null)).noSpend(), "a reading with spend has nothing to say of it");
        assertNull(build(state(null, null, null)).noSpend(), "nor has the row before any reading");
    }

    @Test
    void aFailureAfterAReadingWithNoSpendHasItsMessageAndTheReadingStillSaysWhatItLacks() {
        UsageState failed = state(new UsageSnapshot(FETCHED, null, true), "Anthropic returned HTTP 500.", FETCHED);

        StatusDisplay.View v = build(failed);

        assertEquals("stale", v.message().kind());
        assertEquals(UsageSnapshot.NO_SPEND_SEAT_BASED, v.noSpend());
    }
}
