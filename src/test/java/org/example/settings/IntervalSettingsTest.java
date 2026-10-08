package org.example.settings;

import org.example.settings.SettingsStore.Saved;
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

    private IntervalSettings load() {
        return load(OptionalInt.empty(), OptionalInt.empty());
    }

    @Test
    void defaultsApplyWithNeitherFileNorCommandLine() {
        IntervalSettings settings = load();

        assertEquals(30, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
        assertEquals(Duration.ofSeconds(30), settings.usageInterval());
    }

    @Test
    void theFileOverridesTheDefaults() throws IOException {
        store().save(new Saved(45, 4));

        IntervalSettings settings = load();

        assertEquals(45, settings.usageSeconds());
        assertEquals(4, settings.pollSeconds());
    }

    @Test
    void theCommandLineOverridesTheFile() throws IOException {
        store().save(new Saved(45, 4));

        IntervalSettings settings = load(OptionalInt.of(10), OptionalInt.of(2));

        assertEquals(10, settings.usageSeconds());
        assertEquals(2, settings.pollSeconds());
    }

    @Test
    void eachIntervalIsResolvedOnItsOwn() throws IOException {
        store().save(new Saved(45, null));

        IntervalSettings settings = load(OptionalInt.empty(), OptionalInt.of(8));

        assertEquals(45, settings.usageSeconds());
        assertEquals(8, settings.pollSeconds());
    }

    @Test
    void aCommandLineValueIsNotSavedOnItsOwn() {
        load(OptionalInt.of(10), OptionalInt.of(2));

        assertFalse(Files.exists(dir.resolve("settings.json")));
    }

    @Test
    void aValueCommittedInTheUiReplacesTheCommandLineOneAndIsSaved() {
        IntervalSettings settings = load(OptionalInt.of(10), OptionalInt.of(2));

        settings.update(60L, null);
        settings.update(null, 5L);

        assertEquals(60, settings.usageSeconds());
        assertEquals(5, settings.pollSeconds());
        assertEquals(new Saved(60, 5), store().load());
    }

    @Test
    void savingOneSettingDoesNotSaveTheCommandLineValueOfTheOther() {
        IntervalSettings settings = load(OptionalInt.of(10), OptionalInt.of(2));

        settings.update(null, 5L);

        assertEquals(new Saved(null, 5), store().load());
        assertEquals(10, settings.usageSeconds());
    }

    @Test
    void savingOneSettingKeepsTheOtherThatWasAlreadySaved() throws IOException {
        store().save(new Saved(45, 4));
        IntervalSettings settings = load();

        settings.update(null, 9L);

        assertEquals(new Saved(45, 9), store().load());
    }

    @Test
    void aSavedValueSurvivesARestart() {
        load().update(120L, 7L);

        IntervalSettings restarted = load();

        assertEquals(120, restarted.usageSeconds());
        assertEquals(7, restarted.pollSeconds());
    }

    @Test
    void theUsageListenerHearsAboutUsageChangesOnly() {
        IntervalSettings settings = load();

        settings.update(null, 5L);
        settings.update(90L, null);

        assertEquals(List.of(Duration.ofSeconds(90)), usageChanges);
    }

    @Test
    void recommittingTheSameUsageValueIsNotAChange() {
        IntervalSettings settings = load();

        settings.update(30L, null);

        assertEquals(List.of(), usageChanges);
    }

    @Test
    void bothValuesAreAppliedTogether() {
        IntervalSettings settings = load();

        settings.update(75L, 6L);

        assertEquals(75, settings.usageSeconds());
        assertEquals(6, settings.pollSeconds());
        assertEquals(List.of(Duration.ofSeconds(75)), usageChanges);
    }

    @Test
    void theBoundariesAreAccepted() {
        IntervalSettings settings = load();

        settings.update(5L, 1L);
        assertEquals(5, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());

        settings.update(3600L, 60L);
        assertEquals(3600, settings.usageSeconds());
        assertEquals(60, settings.pollSeconds());
    }

    @Test
    void valuesJustOutsideTheRangesAreRefusedAndChangeNothing() {
        IntervalSettings settings = load();

        for (long usage : new long[] {-1, 0, 4, 3601, Long.MAX_VALUE}) {
            assertThrows(InvalidSettingException.class, () -> settings.update(usage, null), "usage " + usage);
        }
        for (long poll : new long[] {-1, 0, 61, Long.MIN_VALUE}) {
            assertThrows(InvalidSettingException.class, () -> settings.update(null, poll), "poll " + poll);
        }

        assertEquals(30, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
        assertEquals(List.of(), usageChanges);
        assertFalse(Files.exists(dir.resolve("settings.json")));
    }

    @Test
    void oneBadValueRefusesTheWholeUpdate() {
        IntervalSettings settings = load();

        assertThrows(InvalidSettingException.class, () -> settings.update(45L, 999L));
        assertThrows(InvalidSettingException.class, () -> settings.update(4L, 5L));

        assertEquals(30, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
        assertEquals(List.of(), usageChanges);
    }

    @Test
    void anUpdateWithNothingInItIsRefused() {
        assertThrows(InvalidSettingException.class, () -> load().update(null, null));
    }

    @Test
    void theRefusalNamesTheAcceptedRange() {
        InvalidSettingException e = assertThrows(InvalidSettingException.class, () -> load().update(1L, null));

        assertEquals("The usage interval must be a whole number of seconds from 5 to 3600.", e.getMessage());
    }

    @Test
    void ifTheFileCannotBeWrittenNothingChanges() throws IOException {
        SettingsStore unwritable = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));
        IntervalSettings settings = IntervalSettings.load(unwritable, OptionalInt.empty(), OptionalInt.empty());
        settings.onUsageIntervalChange(usageChanges::add);

        SettingsException e = assertThrows(SettingsException.class, () -> settings.update(60L, 5L));

        assertEquals(30, settings.usageSeconds());
        assertEquals(1, settings.pollSeconds());
        assertEquals(List.of(), usageChanges);
        assertEquals(true, e.getMessage().startsWith("The setting could not be saved"));
    }
}
