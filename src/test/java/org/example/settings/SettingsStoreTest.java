package org.example.settings;

import org.example.json.Json;
import org.example.json.JsonReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("settings.json");
    }

    private static Settings interval(int seconds) {
        return Settings.defaults().withUsageIntervalSeconds(seconds);
    }

    private Settings loadFrom(String content) throws IOException {
        Files.writeString(file(), content);
        return new SettingsStore(file()).load();
    }

    @Test
    void theDefaultsAreWhatTheRequirementsSay() {
        Settings d = Settings.defaults();

        assertEquals(60, d.usageIntervalSeconds());
        assertFalse(d.logResponse());
        assertFalse(d.showPercentage(), "the percentage is off until switched on");
        assertFalse(d.historyDate(), "the history shows the time of day only until the date is switched on");
        assertTrue(d.historyZeroLines(), "nothing is hidden until a switch is turned off");
        assertTrue(d.historyFailedLines());
        assertFalse(d.showCurrency(), "the amounts are plain numbers until the symbol is switched on");
        assertTrue(d.showHistoryIcon(), "the icons are all there until one is switched off");
        assertTrue(d.showLogIcon());
        assertTrue(d.showErrorIcon());
        assertFalse(d.showInterval());
        assertFalse(d.showDeltaUsed());
        assertFalse(d.showDeltaTime());
        assertEquals(TimeFormat.HOURS_MINUTES, d.timeFormat());
        assertFalse(d.historyDeltaUsed());
        assertFalse(d.historyDeltaTime());
    }

    @Test
    void aMissingFileIsCreatedWithTheDefaults() throws IOException {
        Settings loaded = new SettingsStore(file()).load();

        assertEquals(Settings.defaults(), loaded);
        assertTrue(Files.exists(file()));
        assertEquals(Settings.defaults(), new SettingsStore(file()).load(), "and what was written reads back");
    }

    @Test
    void aMissingFileThatCannotBeCreatedStillGivesTheDefaults() {
        SettingsStore store = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));

        assertEquals(Settings.defaults(), store.load());
    }

    @Test
    void everySettingComesBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        Settings all = new Settings(120, true, true, true, false, false, true, true, true, true, TimeFormat.HOURS_MINUTES_SECONDS, true, true, true, false, false);

        store.save(all);

        assertEquals(all, store.load());
    }

    @Test
    void theFiveSwitchesOfTheRowThatAreOnByDefaultComeBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        Settings some = Settings.defaults().withRowElements(false, true, false, true, false);

        store.save(some);

        assertEquals(some, store.load());
        assertFalse(store.load().showTime());
        assertTrue(store.load().showUsed());
    }

    @Test
    void theSwitchOfTheRequestDurationIsOffByDefaultAndComesBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertFalse(Settings.defaults().showDuration());

        store.save(Settings.defaults().withShowDuration(true));

        assertTrue(store.load().showDuration());
    }

    @Test
    void stayOnTopIsOffByDefaultAndComesBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertFalse(Settings.defaults().stayOnTop());

        store.save(Settings.defaults().withStayOnTop(true));

        assertTrue(store.load().stayOnTop());
        assertFalse(store.load().showDuration(), "and nothing else changed with it");
    }

    @Test
    void theColumnsOfFiguresOfTheCostControlAreAllShownByDefaultAndComeBackAsSaved() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90}");
        SettingsStore store = new SettingsStore(file());
        CostControl read = store.load().cost();
        assertTrue(read.showUsed() && read.showLimit() && read.showPercent(), "a file of 0.88 or before");

        store.save(Settings.defaults().withCost(CostControl.NONE.withFigures(true, false, true)));

        CostControl back = store.load().cost();
        assertTrue(back.showUsed());
        assertFalse(back.showLimit());
        assertTrue(back.showPercent());
    }

    @Test
    void theStatusCircleBlinksByDefaultAlsoForAFileThatSaysNothingOfItAndComesBackAsSaved() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90}");
        SettingsStore store = new SettingsStore(file());
        assertTrue(store.load().statusBlink(), "a file of 0.87 or before");

        store.save(Settings.defaults().withStatusBlink(false));

        assertFalse(store.load().statusBlink());
        assertTrue(store.load().showStatus(), "and nothing else changed with it");
    }

    @Test
    void theTitleBarIsOnByDefaultAlsoForAFileThatSaysNothingOfItAndComesBackAsSaved() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90}");
        SettingsStore store = new SettingsStore(file());
        assertTrue(store.load().showTitleBar(), "an older file keeps the title bar");

        store.save(Settings.defaults().withShowTitleBar(false));

        assertFalse(store.load().showTitleBar());
    }

    @Test
    void theDebugSettingsAreOffByDefaultAndComeBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        Settings defaults = Settings.defaults();
        assertFalse(defaults.fakeBackend());
        assertEquals(org.example.fake.Scenario.NORMAL, defaults.fakeScenario());
        assertFalse(defaults.fakeToken());

        store.save(defaults.withDebug(true, org.example.fake.Scenario.HTTP_429, true));

        Settings loaded = store.load();
        assertTrue(loaded.fakeBackend());
        assertEquals(org.example.fake.Scenario.HTTP_429, loaded.fakeScenario());
        assertTrue(loaded.fakeToken());
        assertTrue(Files.readString(file()).contains("\"fakeScenario\" : \"http-429\""), "the scenario by its name: " + Files.readString(file()));
    }

    @Test
    void aScenarioTheProgramDoesNotKnowIsPassedOverAndTheRestOfTheFileIsRead() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90, \"fakeBackend\": true, \"fakeScenario\": \"nope\"}");

        Settings loaded = new SettingsStore(file()).load();

        assertEquals(org.example.fake.Scenario.NORMAL, loaded.fakeScenario());
        assertTrue(loaded.fakeBackend());
        assertEquals(90, loaded.usageIntervalSeconds());
    }

    @Test
    void aFileFromBeforeTheFiveSwitchesHasThemAllOn() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90, \"showPercentage\": true}");

        Settings loaded = new SettingsStore(file()).load();

        assertEquals(90, loaded.usageIntervalSeconds());
        assertTrue(loaded.showTime() && loaded.showUsed() && loaded.showLimit() && loaded.showRefresh() && loaded.showCountdown(),
                "what was always shown is still shown");
    }

    @Test
    void savingReplacesTheWholeFile() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(interval(45));

        store.save(interval(90));

        assertEquals(interval(90), store.load());
    }

    @Test
    void theFileHoldsTheSettingsTheTwoHeightsAndTheWidthOfTheLogAndNothingElse() throws IOException {
        new SettingsStore(file()).save(interval(45));

        Json content = JsonReader.read(Files.readString(file()));

        assertEquals(
                List.of("usageIntervalSeconds", "logResponse", "showPercentage", "showCurrency", "showHistoryIcon", "showLogIcon", "showInterval", "showDeltaUsed", "showDeltaTime",
                        "timeFormat", "historyDeltaUsed", "historyDeltaTime", "historyDate", "historyZeroLines", "historyFailedLines", "showTime", "showUsed", "showLimit", "showRefresh", "showCountdown", "showDuration", "stayOnTop", "showTitleBar", "fakeBackend", "fakeScenario", "fakeToken", "fakeAccount", "fakeIncreaseCents", "fakeBudget", "fakeStartAmount",
                        "fakeRefuseSeconds", "fakeDelayMillis", "logLevel", "logDate", "listFontSize", "autoInterval",
                        "costMonthlyLimit", "costDailyLimit", "costDailyAutomatic", "costCurrency", "costLimitSource", "costWorkdayHours", "costWorkdays", "costWorkdaysMonth", "costShowUsed", "costShowLimit", "costShowPercent", "autoIntervalSeconds", "historyTime", "historyUsed", "historyLimit", "historyCurrent", "showCostIcon", "restoreWindow", "severalViews", "logFolder", "historyFolder", "showStatus", "showDayPercentage", "showDayUsed", "showDayLimit", "historyMonthly", "historyDaily", "historyDailyBy", "statusBlink", "locateWindow", "tooltipMillis", "showExitIcon", "historyGroup", "showBurnupIcon", "historyHeight", "logHeight", "logWidth", "burnupHeight", "burnupWidth"),
                List.copyOf(content.keys()));
        assertEquals("hh:mm", content.get("timeFormat").asText());
    }

    @Test
    void savingLeavesNoTemporaryFileBehind() throws IOException {
        new SettingsStore(file()).save(interval(45));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("settings.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailedSaveLeavesTheExistingFileUntouched() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(interval(45));
        // A directory where the temporary file must go makes writing it fail.
        Files.createDirectory(dir.resolve("settings.json.tmp"));

        assertThrows(IOException.class, () -> store.save(interval(99)));

        assertEquals(interval(45), store.load());
    }

    @Test
    void savingIntoAMissingDirectoryFails() {
        SettingsStore store = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));

        assertThrows(IOException.class, () -> store.save(interval(45)));
    }

    // ---- an older file, from when the usage interval was all it held

    @Test
    void aFileWithOnlyTheIntervalGivesTheDefaultsForTheRest() throws IOException {
        assertEquals(interval(45), loadFrom("{\"usageIntervalSeconds\": 45}"));
    }

    @Test
    void anUpdateIntervalLeftInTheFileByAnEarlierVersionIsIgnored() throws IOException {
        assertEquals(interval(45), loadFrom("{\"usageIntervalSeconds\": 45, \"pollIntervalSeconds\": 5}"));
        assertEquals(Settings.defaults(), loadFrom("{\"pollIntervalSeconds\": 5}"));
    }

    @Test
    void savingDropsTheOldUpdateIntervalFromTheFile() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 45, \"pollIntervalSeconds\": 5}");
        SettingsStore store = new SettingsStore(file());

        store.save(interval(90));

        assertFalse(Files.readString(file()).contains("pollIntervalSeconds"), Files.readString(file()));
        assertEquals(interval(90), store.load());
    }

    // ---- reading is forgiving

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        assertEquals(interval(45), loadFrom("{\"usageIntervalSeconds\": 45, \"theme\": \"dark\"}"));
    }

    @Test
    void anOutOfRangeIntervalIsIgnored() throws IOException {
        for (String bad : List.of("4", "3601", "0", "-30")) {
            assertEquals(Settings.defaults(), loadFrom("{\"usageIntervalSeconds\": " + bad + "}"), bad);
        }
    }

    @Test
    void valuesOfTheWrongTypeAreIgnoredOneByOne() throws IOException {
        for (String bad : List.of("\"45\"", "45.5", "null", "true", "99999999999")) {
            assertEquals(Settings.defaults(), loadFrom("{\"usageIntervalSeconds\": " + bad + "}"), bad);
        }
        Settings partly = loadFrom("{\"logResponse\": \"yes\", \"showInterval\": true, \"timeFormat\": \"12h\"}");
        assertFalse(partly.logResponse(), "not a boolean: the default");
        assertTrue(partly.showInterval(), "the rest is kept");
        assertEquals(TimeFormat.HOURS_MINUTES, partly.timeFormat(), "not a known format: the default");
    }

    @Test
    void aDamagedFileGivesTheDefaultsAndIsLeftAlone() throws IOException {
        // The last is an object with text after it: a file is its one object and nothing else.
        for (String bad : List.of("not json", "{\"usageIntervalSeconds\": ", "[1, 2]", "", "{\"usageIntervalSeconds\": 45} x")) {
            assertEquals(Settings.defaults(), loadFrom(bad), bad);
            assertEquals(bad, Files.readString(file()), "not overwritten by loading");
        }
    }

    @Test
    void theBoundariesAreAccepted() throws IOException {
        assertEquals(interval(5), loadFrom("{\"usageIntervalSeconds\": 5}"));
        assertEquals(interval(3600), loadFrom("{\"usageIntervalSeconds\": 3600}"));
    }

    // ---- the remembered heights, which live in the same file

    @Test
    void noHeightIsRememberedUntilOneIsStored() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults());

        assertEquals(SettingsStore.Heights.NONE, store.loadHeights());
        assertEquals(SettingsStore.Heights.NONE, new SettingsStore(dir.resolve("missing.json")).loadHeights());
    }

    @Test
    void theHeightsAreStoredOneForEachPanelAndKeepTheSettings() throws IOException {
        SettingsStore store = new SettingsStore(file());
        Settings changed = Settings.defaults().withUsageIntervalSeconds(180).withLogResponse(true);
        store.save(changed);

        store.saveHeights(SettingsStore.Heights.NONE.withHeight("history", 640));
        store.saveHeights(store.loadHeights().withHeight("log", 500));

        assertEquals(new SettingsStore.Heights(640, 500), store.loadHeights());
        assertEquals(changed, store.load(), "the settings are as they were");
    }

    @Test
    void savingSettingsKeepsTheHeights() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults());
        store.saveHeights(new SettingsStore.Heights(640, 500));

        store.save(interval(90));

        assertEquals(new SettingsStore.Heights(640, 500), store.loadHeights());
        assertEquals(interval(90), store.load());
    }

    @Test
    void aHeightThatIsNotAPlainNumberOfPixelsIsIgnored() throws IOException {
        for (String bad : List.of("-5", "10001", "\"tall\"", "6.5", "null", "true")) {
            Files.writeString(file(), "{\"historyHeight\": " + bad + ", \"logHeight\": 400}");
            assertEquals(new SettingsStore.Heights(0, 400), new SettingsStore(file()).loadHeights(), bad);
        }
    }

    @Test
    void theWidthOfTheLogIsStoredBesideTheHeightsAndKeptBySavingSettings() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults());
        store.saveHeights(new SettingsStore.Heights(640, 500));

        store.saveHeights(store.loadHeights().withLogWidth(1300));
        store.save(interval(90));

        assertEquals(new SettingsStore.Heights(640, 500, 1300), store.loadHeights());
        assertTrue(Files.readString(file()).contains("\"logWidth\""), "under its own key (since 0.93)");
        assertEquals(new SettingsStore.Heights(640, 520, 1300), new SettingsStore(file()).loadHeights().withHeight("log", 520),
                "a height stored keeps the width");
    }

    @Test
    void aChangeOfTheSettingsNamesWhatChangedAndNoMore() {
        Settings before = Settings.defaults();

        assertEquals("listFontSize 12 -> 13, historyGroup none -> 15m",
                SettingsStore.changes(before, before.withListFontSize(13).withHistoryGroup("15m")), "in the order of the file, by its keys (since 0.95)");
        assertEquals("logFolder \"\" -> C:\\logs", SettingsStore.changes(before, before.withFolders("C:\\logs", "")), "an empty text as two quotes");
        assertEquals("logLevel info -> fine", SettingsStore.changes(before, before.withLog(LogLevel.FINE, false)));
        assertEquals("", SettingsStore.changes(before, before), "nothing when nothing changed");
    }

    @Test
    void theCrossAndTheGroupingOfTheHistoryAreReadAndWritten() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults().withShowExitIcon(false).withHistoryGroup("1w"));

        assertFalse(store.load().showExitIcon());
        assertEquals("1w", store.load().historyGroup());
        assertTrue(Settings.defaults().showExitIcon(), "the cross is shown by default (since 0.94)");

        Files.writeString(file(), "{\"historyGroup\": \"2h\"}");
        assertEquals("none", new SettingsStore(file()).load().historyGroup(), "a grouping that is none of them is none");
        Files.writeString(file(), "{}");
        assertTrue(new SettingsStore(file()).load().showExitIcon(), "a file of 0.93 shows it");
    }

    @Test
    void theBurnupIconAndItsHeightAreReadAndWritten() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults().withShowBurnupIcon(false));
        store.saveHeights(SettingsStore.Heights.NONE.withHeight("burnup", 380));

        assertFalse(store.load().showBurnupIcon());
        assertEquals(380, store.loadHeights().of("burnup"));
        assertEquals(new SettingsStore.Heights(0, 0, 0, 380), store.loadHeights());
        assertTrue(Settings.defaults().showBurnupIcon(), "shown by default (since 0.97)");
        Files.writeString(file(), "{\"historyHeight\": 500}");
        assertTrue(new SettingsStore(file()).load().showBurnupIcon(), "a file of 0.96 shows it");
        assertEquals(new SettingsStore.Heights(500, 0, 0, 0), new SettingsStore(file()).loadHeights(), "and has no height for it");
    }

    @Test
    void theWidthOfTheBurnupIsStoredUnderItsOwnKeyBesideTheLogs() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults());
        store.saveHeights(SettingsStore.Heights.NONE.withWidth("log", 1300).withWidth("burnup", 260).withHeight("burnup", 380));

        assertEquals(new SettingsStore.Heights(0, 0, 1300, 380, 260), store.loadHeights());
        assertEquals(260, store.loadHeights().widthOf("burnup"));
        assertTrue(Files.readString(file()).contains("\"burnupWidth\""), "under its own key (since 0.99)");
        Files.writeString(file(), "{\"burndownHeight\": 380}");
        assertEquals(0, new SettingsStore(file()).loadHeights().widthOf("burnup"), "a file of 0.98 has none");
        assertEquals(0, SettingsStore.Heights.NONE.widthOf("history"), "the history keeps no width");
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.Heights.NONE.withWidth("history", 300));
    }

    @Test
    void theBurnupIsAViewThatIsStoredAsOpenFirstOfAll() throws IOException {
        Files.writeString(file(), "{\"lastPanel\": \"history,burnup\"}");

        assertEquals("burnup,history", new SettingsStore(file()).loadLast().panel(), "in the order of the icons");
    }

    @Test
    void aFileOfTheBurndownIsReadAsTheBurnupAndWrittenWithTheNewKeysOnly() throws IOException {
        // A settings file of 0.99, before the burndown became the burnup (since 0.100).
        Files.writeString(file(), "{\"showBurndownIcon\": false, \"burndownHeight\": 420, \"burndownWidth\": 260, \"lastPanel\": \"burndown,cost\"}");
        SettingsStore store = new SettingsStore(file());

        assertFalse(store.load().showBurnupIcon());
        assertEquals(new SettingsStore.Heights(0, 0, 0, 420, 260), store.loadHeights());
        assertEquals("burnup,cost", store.loadLast().panel());

        store.save(store.load());
        String written = Files.readString(file());
        assertFalse(written.contains("burndown"), "the old keys are not written:\n" + written);
        assertTrue(written.contains("\"showBurnupIcon\"") && written.contains("\"burnupHeight\"") && written.contains("\"burnupWidth\""), written);
        assertFalse(store.load().showBurnupIcon());
        assertEquals(new SettingsStore.Heights(0, 0, 0, 420, 260), store.loadHeights());
        assertEquals("burnup,cost", store.loadLast().panel());
    }

    @Test
    void theNewKeysWinOverTheBurndownsWhenBothAreThere() throws IOException {
        Files.writeString(file(), "{\"showBurndownIcon\": false, \"showBurnupIcon\": true, \"burndownHeight\": 420, \"burnupHeight\": 380,"
                + " \"burndownWidth\": 260, \"burnupWidth\": 300, \"lastPanel\": \"burnup,burndown,history\"}");
        SettingsStore store = new SettingsStore(file());

        assertTrue(store.load().showBurnupIcon());
        assertEquals(new SettingsStore.Heights(0, 0, 0, 380, 300), store.loadHeights());
        assertEquals("burnup,history", store.loadLast().panel(), "the burnup once");
    }

    @Test
    void aMissingOrUnusableWidthOfTheLogIsNone() throws IOException {
        for (String bad : List.of("-5", "10001", "\"wide\"", "null")) {
            Files.writeString(file(), "{\"logHeight\": 400, \"logWidth\": " + bad + "}");
            assertEquals(new SettingsStore.Heights(0, 400, 0), new SettingsStore(file()).loadHeights(), bad);
        }
        Files.writeString(file(), "{\"logHeight\": 400}");
        assertEquals(new SettingsStore.Heights(0, 400, 0), new SettingsStore(file()).loadHeights(), "a file of 0.92 has none");
    }

    @Test
    void aHeightIsNotWorthReplacingADamagedFileFor() throws IOException {
        Files.writeString(file(), "not json");

        assertThrows(IOException.class, () -> new SettingsStore(file()).saveHeights(new SettingsStore.Heights(640, 0)));

        assertEquals("not json", Files.readString(file()));
    }

    @Test
    void aPanelThatIsNeitherTheHistoryNorTheLogHasNoHeight() {
        assertEquals(0, new SettingsStore.Heights(640, 500).of("errors"));
        assertThrows(IllegalArgumentException.class, () -> SettingsStore.Heights.NONE.withHeight("errors", 300));
    }

    @Test
    void theLogPanelShowsEverythingAndNoDateByDefaultAndItsChoicesComeBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        Settings defaults = Settings.defaults();
        assertEquals(LogLevel.INFO, defaults.logLevel());
        assertFalse(defaults.logDate());
        assertEquals(defaults, store.load(), "no file: the defaults");

        store.save(defaults.withLog(LogLevel.WARNING, true));

        Settings loaded = store.load();
        assertEquals(LogLevel.WARNING, loaded.logLevel());
        assertTrue(loaded.logDate());
        assertTrue(Files.readString(file()).contains("\"logLevel\" : \"warning\""), "the level by its name: " + Files.readString(file()));
        for (LogLevel level : LogLevel.values()) {
            store.save(defaults.withLog(level, false));
            assertEquals(level, store.load().logLevel());
        }
    }

    @Test
    void aLogLevelThatIsNoneOfTheFourHasTheDefaultAndTheOtherKeysAreKept() throws IOException {
        for (String bad : List.of("\"trace\"", "\"INFO\"", "3", "true")) {
            Files.writeString(file(), "{\"logLevel\": " + bad + ", \"logDate\": true, \"usageIntervalSeconds\": 90}");

            Settings loaded = new SettingsStore(file()).load();

            assertEquals(LogLevel.INFO, loaded.logLevel(), bad);
            assertTrue(loaded.logDate(), bad);
            assertEquals(90, loaded.usageIntervalSeconds(), bad);
        }
    }

    @Test
    void theTextOfTheListsIsTwelvePixelsByDefaultAndComesBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertEquals(12, Settings.defaults().listFontSize());
        assertEquals(12, store.load().listFontSize(), "no file: the default");

        for (int size : List.of(10, 15, 20)) {
            store.save(Settings.defaults().withListFontSize(size));
            assertEquals(size, store.load().listFontSize());
        }
        assertTrue(Files.readString(file()).contains("\"listFontSize\" : 20"), Files.readString(file()));
    }

    @Test
    void aTextSizeOutOfItsRangeHasTheDefaultAndTheOtherKeysAreKept() throws IOException {
        for (String bad : List.of("9", "21", "12.5", "\"12\"", "true", "-3")) {
            Files.writeString(file(), "{\"listFontSize\": " + bad + ", \"logDate\": true}");

            Settings loaded = new SettingsStore(file()).load();

            assertEquals(12, loaded.listFontSize(), bad);
            assertTrue(loaded.logDate(), bad);
        }
    }

    @Test
    void theAutomaticIntervalIsOffByDefaultAndComesBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertFalse(Settings.defaults().autoInterval());

        store.save(Settings.defaults().withAutoInterval(true));

        assertTrue(store.load().autoInterval());
        assertTrue(Files.readString(file()).contains("\"autoInterval\" : true"), Files.readString(file()));
    }

    @Test
    void theSearchIsKeptBesideTheSettingsAndASaveOfEitherLeavesTheOtherAsItIs() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertEquals(new SettingsStore.Search(60, null), store.loadSearch(), "no file: where a search begins");

        store.saveSearch(new SettingsStore.Search(29, 28));
        assertEquals(new SettingsStore.Search(29, 28), store.loadSearch());
        assertEquals(Settings.defaults(), store.load(), "the settings are the defaults still");

        store.save(interval(45).withAutoInterval(true));
        assertEquals(new SettingsStore.Search(29, 28), store.loadSearch(), "saving the settings keeps the search");

        store.saveHeights(new SettingsStore.Heights(640, 500));
        assertEquals(new SettingsStore.Search(29, 28), store.loadSearch(), "and so does saving a height");

        store.saveSearch(new SettingsStore.Search(26, 25));
        assertEquals(45, store.load().usageIntervalSeconds(), "saving the search keeps the settings");
        assertTrue(store.load().autoInterval());
        assertEquals(new SettingsStore.Heights(640, 500), store.loadHeights(), "and the heights");
    }

    @Test
    void aSearchWithoutAWholeIntervalIsTheBeginningOfOne() throws IOException {
        for (String bad : List.of(
                "{\"autoIntervalSeconds\": \"29\"}",
                "{\"autoIntervalSeconds\": 29.5, \"autoRefusedSeconds\": 23}",
                "{\"autoRefusedSeconds\": 23}",
                "[1, 2, 3]", "not json")) {
            Files.writeString(file(), bad);

            assertEquals(SettingsStore.Search.BEGINNING, new SettingsStore(file()).loadSearch(), bad);
        }
    }

    @Test
    void aFileOfAnEarlierVersionWithBoundsHasTheIntervalAndNoRefusal() throws IOException {
        Files.writeString(file(), "{\"autoIntervalSeconds\": 29, \"autoLowerSeconds\": 23, \"autoUpperSeconds\": 35}");

        assertEquals(new SettingsStore.Search(29, null), new SettingsStore(file()).loadSearch());

        new SettingsStore(file()).saveSearch(new SettingsStore.Search(29, null));
        String written = Files.readString(file());
        assertFalse(written.contains("autoLowerSeconds") || written.contains("autoUpperSeconds") || written.contains("autoRefusedSeconds"), written);
    }

    @Test
    void theHistoryColumnsAreSettingsOnByDefaultAndKept() throws IOException {
        Settings d = Settings.defaults();
        assertTrue(d.historyTime() && d.historyUsed() && d.historyLimit() && d.historyCurrent());

        SettingsStore store = new SettingsStore(file());
        store.save(d.withHistoryColumns(false, true, false, true));

        Settings back = store.load();
        assertFalse(back.historyTime());
        assertTrue(back.historyUsed());
        assertFalse(back.historyLimit());
        assertTrue(back.historyCurrent());
    }

    @Test
    void theWindowIsRestoredOnlyWhenAskedAndTheLastWindowIsKeptBesideTheSettings() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertFalse(Settings.defaults().restoreWindow(), "off by default");
        assertEquals(SettingsStore.LastWindow.NONE, store.loadLast());

        SettingsStore.LastWindow last = new SettingsStore.LastWindow("settings", "general", 420, 380, 100, -20);
        store.saveLast(last);
        store.save(interval(45).withRestoreWindow(true));

        assertEquals(last, store.loadLast(), "saving the settings keeps the last window");
        assertTrue(store.load().restoreWindow());
        store.saveHeights(new SettingsStore.Heights(640, 500));
        assertEquals(last, store.loadLast(), "and so does saving a height");
        assertTrue(store.load().restoreWindow(), "and saving the last window keeps the settings");
    }

    @Test
    void aLastWindowValueThatCannotBeIsLeftOutAndTheOthersAreKept() throws IOException {
        Files.writeString(file(), "{\"lastPanel\": \"history\", \"lastTab\": \"general\", \"windowWidth\": \"wide\", \"windowHeight\": 500, "
                + "\"windowX\": 1.5, \"windowY\": -30}");

        assertEquals(new SettingsStore.LastWindow("history", "general", null, 500, null, -30), new SettingsStore(file()).loadLast());

        Files.writeString(file(), "{\"lastPanel\": \"calendar\", \"lastTab\": \"general\", \"windowWidth\": 300}");
        assertEquals(new SettingsStore.LastWindow(null, null, 300, null, null, null), new SettingsStore(file()).loadLast(),
                "a panel that is not known leaves its tab out too");
    }

    @Test
    void theTabOfTheSettingsIsNotKeptAKeyOfAnEarlierVersionIsNotReadAndGoesWithTheNextSave() throws IOException {
        Files.writeString(file(), "{\"settingsTab\": \"general\", \"usageIntervalSeconds\": 45, \"lastPanel\": \"settings\", \"lastTab\": \"about\"}");
        SettingsStore store = new SettingsStore(file());

        assertEquals(45, store.load().usageIntervalSeconds(), "the file is read as it is, the key passed over like any that is none");
        assertEquals("about", store.loadLast().tab(), "the tab of the window that is restored at start is another thing, and stays");
        store.saveHeights(new SettingsStore.Heights(640, 500));
        assertFalse(Files.readString(file()).contains("settingsTab"), "and it is not written again");
        assertEquals("about", store.loadLast().tab());
    }

    @Test
    void theNamesOfTheTabsAreTheSix() {
        assertTrue(SettingsStore.isTab("about") && SettingsStore.isTab("debug") && SettingsStore.isTab("general"));
        assertFalse(SettingsStore.isTab("calendar") || SettingsStore.isTab(null));
    }

    // ---- how the fake backend answers, beside the settings

    @Test
    void theFakeBackendsOptionsAreTheDefaultsUntilTheyAreSavedAndComeBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertEquals(org.example.fake.FakeOptions.DEFAULTS, store.loadFake(), "no file: as the fake backend always was");

        org.example.fake.FakeOptions shaped = new org.example.fake.FakeOptions(org.example.fake.FakeOptions.Account.SEAT, 50, 25_000, 1_234, 30, 400);
        store.saveFake(shaped);

        assertEquals(shaped, store.loadFake());
        String content = Files.readString(file());
        assertTrue(content.contains("\"fakeAccount\" : \"seat\""), content);
        assertTrue(content.contains("\"fakeIncreaseCents\" : 50"), content);
        assertTrue(content.contains("\"fakeBudget\" : 250.00"), "in dollars: " + content);
        assertTrue(content.contains("\"fakeStartAmount\" : 12.34"), content);
        assertTrue(content.contains("\"fakeRefuseSeconds\" : 30"), content);
        assertTrue(content.contains("\"fakeDelayMillis\" : 400"), content);
    }

    @Test
    void savingTheSettingsTheHeightsOrTheSearchLeavesTheFakeBackendsOptionsAsTheyAreAndTheOtherWayRound() throws IOException {
        SettingsStore store = new SettingsStore(file());
        org.example.fake.FakeOptions shaped = new org.example.fake.FakeOptions(org.example.fake.FakeOptions.Account.USAGE, 5, 30_000, 100, 10, 20);
        store.saveFake(shaped);

        store.save(interval(45));
        store.saveHeights(new SettingsStore.Heights(640, 500));
        store.saveSearch(new SettingsStore.Search(29, 28));
        assertEquals(shaped, store.loadFake());

        store.saveFake(org.example.fake.FakeOptions.DEFAULTS);
        assertEquals(45, store.load().usageIntervalSeconds());
        assertEquals(new SettingsStore.Heights(640, 500), store.loadHeights());
        assertEquals(new SettingsStore.Search(29, 28), store.loadSearch());
    }

    @Test
    void aValueOfTheFakeBackendThatCannotBeHasItsDefault() throws IOException {
        Files.writeString(file(), "{\"fakeAccount\": \"team\", \"fakeIncreaseCents\": \"many\", \"fakeBudget\": 12.345, \"fakeRefuseSeconds\": 2.5, \"fakeDelayMillis\": 250}");

        org.example.fake.FakeOptions read = new SettingsStore(file()).loadFake();

        assertEquals(new org.example.fake.FakeOptions(org.example.fake.FakeOptions.Account.USAGE, 137, 100_000, 18_602, 0, 250), read,
                "each value that is not one has its default, and the one that is stays");
    }

    @Test
    void optionsOfTheFakeBackendThatDoNotGoTogetherAreTheDefaults() throws IOException {
        Files.writeString(file(), "{\"fakeAccount\": \"seat\", \"fakeBudget\": 10.00, \"fakeStartAmount\": 20.00}");

        org.example.fake.FakeOptions read = new SettingsStore(file()).loadFake();

        assertEquals(org.example.fake.FakeOptions.Account.SEAT, read.account(), "the kind of account is kept");
        assertEquals(100_000, read.budgetCents(), "a starting amount above the budget: neither is believed");
        assertEquals(18_602, read.startCents());
    }

    @Test
    void aFileThatStillNamesTheScenarioForASeatBasedAccountIsTheOrdinaryAnswerOfSuchAnAccount() throws IOException {
        Files.writeString(file(), "{\"fakeBackend\": true, \"fakeScenario\": \"seat-based\"}");
        SettingsStore store = new SettingsStore(file());

        assertEquals(org.example.fake.Scenario.NORMAL, store.load().fakeScenario());
        assertEquals(org.example.fake.FakeOptions.Account.SEAT, store.loadFake().account());
        assertTrue(store.load().fakeBackend());
    }

    // ---- cost control

    @Test
    void thereAreNoLimitsByDefaultAndTheDailyOneIsWorkedOutAndTheyComeBackAsSaved() throws IOException {
        SettingsStore store = new SettingsStore(file());
        assertEquals(CostControl.NONE, Settings.defaults().cost());
        assertEquals(CostControl.NONE, store.load().cost(), "no file");

        store.save(Settings.defaults().withCost(new CostControl(25_000L, 1_250L, false)));

        assertEquals(new CostControl(25_000L, 1_250L, false), store.load().cost());
        String content = Files.readString(file());
        assertTrue(content.contains("\"costMonthlyLimit\" : 250.00"), "in dollars: " + content);
        assertTrue(content.contains("\"costDailyLimit\" : 12.50"), content);
        assertTrue(content.contains("\"costDailyAutomatic\" : false"), content);

        store.save(Settings.defaults());
        assertTrue(Files.readString(file()).contains("\"costMonthlyLimit\" : null"), "no limit is written as none, and the key is there");
        assertEquals(CostControl.NONE, store.load().cost());
    }

    @Test
    void aLimitInTheFileThatIsNoAmountInItsRangeIsNoLimitAndADailyOneAboveTheMonthlyOneIsDropped() throws IOException {
        for (String bad : List.of("0", "-5", "12.345", "\"250\"", "true", "1000000.01")) {
            Files.writeString(file(), "{\"costMonthlyLimit\": " + bad + ", \"usageIntervalSeconds\": 90}");
            Settings loaded = new SettingsStore(file()).load();
            assertEquals(null, loaded.cost().monthlyCents(), bad);
            assertEquals(90, loaded.usageIntervalSeconds(), bad + ": the other keys are kept");
        }

        Files.writeString(file(), "{\"costMonthlyLimit\": 100, \"costDailyLimit\": 150, \"costDailyAutomatic\": false}");
        assertEquals(new CostControl(10_000L, null, false), new SettingsStore(file()).load().cost());
    }

    @Test
    void severalViewsAtOnceIsASettingOffByDefaultAndKept() throws IOException {
        assertFalse(Settings.defaults().severalViews(), "off by default");
        SettingsStore store = new SettingsStore(file());
        assertFalse(store.load().severalViews(), "and off with no file");

        store.save(Settings.defaults().withSeveralViews(true));

        assertTrue(new SettingsStore(file()).load().severalViews());
        assertTrue(JsonReader.read(Files.readString(file())).get("severalViews").asBoolean());
        assertFalse(Settings.defaults().withSeveralViews(true).withSeveralViews(false).severalViews());
    }

    @Test
    void theViewsThatWereOpenAtOnceAreStoredWithCommasAndReadInTheOrderOfTheIcons() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults());
        store.saveLast(new SettingsStore.LastWindow("cost,history", null, 520, 640, 10, 20));

        assertEquals("cost,history", JsonReader.read(Files.readString(file())).get("lastPanel").asText());
        assertEquals("cost,history", new SettingsStore(file()).loadLast().panel());

        Files.writeString(file(), "{\"lastPanel\": \"history,cost\"}");
        assertEquals("cost,history", new SettingsStore(file()).loadLast().panel(), "a file of 0.80, in the old order, is read in the new one");

        Files.writeString(file(), "{\"lastPanel\": \"cost,calendar,log,cost\", \"lastTab\": \"general\"}");
        assertEquals("cost,log", new SettingsStore(file()).loadLast().panel(), "an unknown name is left out, each view once, in the order of the icons");

        Files.writeString(file(), "{\"lastPanel\": \"history,settings\", \"lastTab\": \"about\"}");
        assertEquals(new SettingsStore.LastWindow("settings,history", "about", null, null, null, null), new SettingsStore(file()).loadLast(),
                "the settings first, with the views that were beside them (since 0.81), and their tab");

        Files.writeString(file(), "{\"lastPanel\": \"settings\", \"lastTab\": \"cost\"}");
        assertEquals(new SettingsStore.LastWindow("settings", "cost", null, null, null, null), new SettingsStore(file()).loadLast(), "alone");

        Files.writeString(file(), "{\"lastPanel\": \"calendar,\"}");
        assertEquals(SettingsStore.LastWindow.NONE, new SettingsStore(file()).loadLast());
    }

    @Test
    void aWindowShowsWhatWasStoredWhenItShowsAllOfItOrTheFirstOfItAlone() {
        assertTrue(SettingsStore.LastWindow.shows("cost,history", List.of("cost", "history")));
        assertTrue(SettingsStore.LastWindow.shows("cost,history", List.of("cost")), "several views at once are switched off");
        assertFalse(SettingsStore.LastWindow.shows("cost,history", List.of("history")));
        assertFalse(SettingsStore.LastWindow.shows("cost,history", List.of("cost", "history", "log")));
        assertTrue(SettingsStore.LastWindow.shows("log", List.of("log")));
        assertTrue(SettingsStore.LastWindow.shows("settings", List.of("settings")));
        assertTrue(SettingsStore.LastWindow.shows("settings,cost,history", List.of("settings", "cost", "history")));
        assertTrue(SettingsStore.LastWindow.shows("settings,cost,history", List.of("settings")), "several views at once are switched off");
        assertFalse(SettingsStore.LastWindow.shows("log", List.of()), "nothing is open yet");
        assertFalse(SettingsStore.LastWindow.shows(null, List.of("log")));
    }

    @Test
    void theTwoFoldersAreTextsEmptyForTheProgramsFolder() throws IOException {
        assertEquals("", Settings.defaults().logFolder());
        assertEquals("", Settings.defaults().historyFolder());
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults().withFolders("/home/me/logs", "/home/me/history"));
        Settings read = new SettingsStore(file()).load();
        assertEquals("/home/me/logs", read.logFolder(), "a folder each again since 0.90");
        assertEquals("/home/me/history", read.historyFolder());
        Json written = JsonReader.read(Files.readString(file()));
        assertFalse(written.has("dataFolder"), "the one folder of 0.87 to 0.89 is no longer written");

        Files.writeString(file(), "{\"logFolder\": 12, \"historyFolder\": null}");
        assertEquals("", new SettingsStore(file()).load().logFolder(), "not a text: the program's folder");
        assertEquals("", new SettingsStore(file()).load().historyFolder());
    }

    @Test
    void aFileOf087To089GivesItsOneFolderToBoth() throws IOException {
        Files.writeString(file(), "{\"dataFolder\": \"/home/me/data\"}");
        Settings read = new SettingsStore(file()).load();
        assertEquals("/home/me/data", read.logFolder());
        assertEquals("/home/me/data", read.historyFolder());

        Files.writeString(file(), "{\"dataFolder\": \"/data\", \"logFolder\": \"/logs\"}");
        assertEquals("/logs", new SettingsStore(file()).load().logFolder(), "the keys of 0.90 win");
        assertEquals("", new SettingsStore(file()).load().historyFolder());
    }

    @Test
    void theRectangleThatShowsWhereTheWindowIsIsOnByDefaultAndComesBackAsSavedAndSoDoesTheTooltipDuration() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 90}");
        SettingsStore store = new SettingsStore(file());
        assertTrue(store.load().locateWindow(), "on by default since 0.91");
        store.save(Settings.defaults().withLocateWindow(false));
        assertFalse(store.load().locateWindow());
        assertEquals(0, store.load().tooltipMillis(), "a tooltip stays for as long as the pointer is on it");
        store.save(Settings.defaults().withTooltipMillis(15000));
        assertEquals(15000, store.load().tooltipMillis());
    }

    @Test
    void theChoicesOfCostControlAreKept() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(Settings.defaults().withCost(new CostControl(15_000L, null, true, true).withCounting(CostControl.SERVER, 6, 19, "2026-10")));
        CostControl read = new SettingsStore(file()).load().cost();
        assertEquals(CostControl.SERVER, read.source());
        assertEquals(6, read.workdayHours());
        assertEquals(Integer.valueOf(19), read.workdays());
        assertEquals("2026-10", read.workdaysMonth());
        assertTrue(read.currency());
    }
}
