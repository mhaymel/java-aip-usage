package org.example.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalSettingsTest {

    @TempDir
    Path dir;

    private final List<Duration> usageChanges = new ArrayList<>();

    private SettingsStore store() {
        return new SettingsStore(dir.resolve("settings.json"));
    }

    private IntervalSettings load(OptionalInt cliUsage, OptionalInt cliPoll) {
        IntervalSettings settings = IntervalSettings.load(store(), cliUsage, cliPoll);
        settings.onUsageIntervalChange(usageChanges::add);
        return settings;
    }

    private String readSettingsFile() {
        try {
            return Files.readString(dir.resolve("settings.json"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private IntervalSettings load() {
        return load(OptionalInt.empty(), OptionalInt.empty());
    }

    // ---- the usage interval: command line, else the file, else the default

    @Test
    void theDefaultsAreAMinuteForUsageAndASecondForTheUpdate() {
        IntervalSettings settings = load();

        assertEquals(60, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
        assertEquals(Duration.ofSeconds(60), settings.usageInterval());
    }

    @Test
    void theFileOverridesTheDefaultUsageInterval() throws IOException {
        store().save(Settings.defaults().withUsageIntervalSeconds(45));

        assertEquals(45, load().usageSeconds());
    }

    @Test
    void theCommandLineOverridesTheFile() throws IOException {
        store().save(Settings.defaults().withUsageIntervalSeconds(45));

        assertEquals(10, load(OptionalInt.of(10), OptionalInt.empty()).usageSeconds());
    }

    @Test
    void aCommandLineUsageIntervalIsNotSavedOnItsOwn() {
        load(OptionalInt.of(10), OptionalInt.empty());

        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
    }

    @Test
    void aValueCommittedInTheUiReplacesTheCommandLineOneAndIsSaved() {
        IntervalSettings settings = load(OptionalInt.of(10), OptionalInt.empty());

        settings.updateUsage(120);

        assertEquals(120, settings.usageSeconds());
        assertEquals(Settings.defaults().withUsageIntervalSeconds(120), store().load());
    }

    @Test
    void aSavedValueSurvivesARestart() {
        load().updateUsage(120);

        assertEquals(120, load().usageSeconds());
    }

    @Test
    void theUsageListenerHearsAboutAChange() {
        IntervalSettings settings = load();

        settings.updateUsage(90);

        assertEquals(List.of(Duration.ofSeconds(90)), usageChanges);
    }

    @Test
    void recommittingTheValueInForceIsNotAChange() {
        IntervalSettings settings = load();

        settings.updateUsage(60);

        assertEquals(List.of(), usageChanges);
    }

    @Test
    void theBoundariesAreAccepted() {
        IntervalSettings settings = load();

        settings.updateUsage(5);
        assertEquals(5, settings.usageSeconds());

        settings.updateUsage(3600);
        assertEquals(3600, settings.usageSeconds());
    }

    @Test
    void valuesJustOutsideTheRangeAreRefusedAndChangeNothing() {
        IntervalSettings settings = load();

        for (long usage : new long[] {-1, 0, 4, 3601, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertThrows(InvalidSettingException.class, () -> settings.updateUsage(usage), "usage " + usage);
        }

        assertEquals(60, settings.usageSeconds());
        assertEquals(List.of(), usageChanges);
        assertEquals(60, new SettingsStore(dir.resolve("settings.json")).load().usageIntervalSeconds(), "nothing was saved");
    }

    @Test
    void theRefusalNamesTheAcceptedRange() {
        InvalidSettingException e = assertThrows(InvalidSettingException.class, () -> load().updateUsage(1));

        assertEquals("The usage interval must be a whole number of seconds from 5 to 3600.", e.getMessage());
    }

    @Test
    void ifTheFileCannotBeWrittenNothingChanges() {
        SettingsStore unwritable = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));
        IntervalSettings settings = IntervalSettings.load(unwritable, OptionalInt.empty(), OptionalInt.empty());
        settings.onUsageIntervalChange(usageChanges::add);

        SettingsException e = assertThrows(SettingsException.class, () -> settings.updateUsage(90));

        assertEquals(60, settings.usageSeconds());
        assertEquals(List.of(), usageChanges);
        assertTrue(e.getMessage().startsWith("The settings could not be saved"));
    }

    // ---- the update interval: command line or default, and nothing else

    @Test
    void theCommandLineSetsTheUpdateInterval() {
        assertEquals(5, load(OptionalInt.empty(), OptionalInt.of(5)).pollSeconds());
    }

    @Test
    void theUpdateIntervalIsNeverSaved() {
        IntervalSettings settings = load(OptionalInt.empty(), OptionalInt.of(5));

        settings.updateUsage(90);

        assertFalse(readSettingsFile().contains("poll"));
    }

    @Test
    void theUpdateIntervalIsForgottenAtTheNextStart() {
        load(OptionalInt.empty(), OptionalInt.of(5)).updateUsage(90);

        assertEquals(1, load().pollSeconds());
    }

    @Test
    void anUpdateIntervalLeftInTheFileByAnEarlierVersionHasNoEffect() throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{\"usageIntervalSeconds\": 45, \"pollIntervalSeconds\": 5}");

        IntervalSettings settings = load();

        assertEquals(45, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
    }

    @Test
    void changingTheUsageIntervalDoesNotTouchTheUpdateInterval() {
        IntervalSettings settings = load(OptionalInt.empty(), OptionalInt.of(7));

        settings.updateUsage(90);

        assertEquals(7, settings.pollSeconds());
    }

    // ---- all the settings

    @Test
    void currentIsTheFileWithTheCommandLineInterval() {
        Settings settings = load(OptionalInt.of(10), OptionalInt.empty()).current();

        assertEquals(10, settings.usageIntervalSeconds());
        assertEquals(Settings.defaults().withUsageIntervalSeconds(10), settings);
    }

    @Test
    void appliedSettingsTakeEffectAndAreSavedAndTheListenerHearsOnlyAboutTheInterval() {
        IntervalSettings settings = load();
        Settings changed = new Settings(60, true, true, true, false, true, TimeFormat.HOURS_MINUTES_SECONDS, false, true, false, true, true);

        settings.apply(changed);

        assertEquals(changed, settings.current());
        assertEquals(changed, store().load());
        assertEquals(List.of(), usageChanges, "the interval did not change");

        settings.apply(changed.withUsageIntervalSeconds(180));

        assertEquals(List.of(Duration.ofSeconds(180)), usageChanges);
    }

    @Test
    void settingsWithAnInvalidIntervalAreRefusedWhole() {
        IntervalSettings settings = load();

        assertThrows(InvalidSettingException.class,
                () -> settings.apply(new Settings(4, true, true, true, true, true, TimeFormat.HOURS_MINUTES, true, true, false, true, true)));

        assertEquals(Settings.defaults(), settings.current());
        assertEquals(Settings.defaults(), store().load());
    }

    @Test
    void applyingTheIntervalTheRunAlreadyHasStillSavesTheRestOfTheSettings() {
        IntervalSettings settings = load(OptionalInt.of(10), OptionalInt.empty());

        settings.apply(settings.current().withUsageIntervalSeconds(60));

        assertEquals(60, settings.usageSeconds(), "the command-line value is replaced by what was applied");
        assertEquals(Settings.defaults(), store().load());
    }

    // ---- the remembered heights

    @Test
    void aStoredHeightIsRememberedAcrossARestartAndAnApplyDoesNotLoseIt() {
        IntervalSettings first = load();
        assertEquals(0, first.storedHeight("history"));

        first.storeHeight("history", 640);
        first.apply(first.current().withLogResponse(true));

        IntervalSettings second = load();
        assertEquals(640, second.storedHeight("history"));
        assertEquals(0, second.storedHeight("log"), "each panel has its own");
        assertTrue(second.current().logResponse(), "and the setting was saved too");
    }

    @Test
    void aHeightThatCannotBeSavedIsLoggedNotThrownAndNotRemembered() {
        SettingsStore unwritable = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));
        IntervalSettings settings = IntervalSettings.load(unwritable, OptionalInt.empty(), OptionalInt.empty());

        settings.storeHeight("log", 500);

        assertEquals(0, settings.storedHeight("log"));
    }
}
