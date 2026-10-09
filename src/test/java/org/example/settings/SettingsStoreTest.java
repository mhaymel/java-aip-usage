package org.example.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(d.showInterval());
        assertFalse(d.showDeltaUsed());
        assertFalse(d.showDeltaTime());
        assertEquals(TimeFormat.HOURS_MINUTES, d.timeFormat());
        assertFalse(d.historyDeltaUsed());
        assertFalse(d.historyDeltaTime());
        assertEquals(List.of(60, 120, 180, 240, 300), Settings.INTERVAL_CHOICES);
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
        Settings all = new Settings(120, true, true, true, true, TimeFormat.HOURS_MINUTES_SECONDS, true, true);

        store.save(all);

        assertEquals(all, store.load());
    }

    @Test
    void savingReplacesTheWholeFile() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(interval(45));

        store.save(interval(90));

        assertEquals(interval(90), store.load());
    }

    @Test
    void theFileHoldsTheEightSettingsAndNothingElse() throws IOException {
        new SettingsStore(file()).save(interval(45));

        JsonNode content = new ObjectMapper().readTree(Files.readString(file()));

        assertEquals(
                List.of("usageIntervalSeconds", "logResponse", "showInterval", "showDeltaUsed", "showDeltaTime",
                        "timeFormat", "historyDeltaUsed", "historyDeltaTime"),
                content.properties().stream().map(java.util.Map.Entry::getKey).toList());
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
        for (String bad : List.of("not json", "{\"usageIntervalSeconds\": ", "[1, 2]", "")) {
            assertEquals(Settings.defaults(), loadFrom(bad), bad);
            assertEquals(bad, Files.readString(file()), "not overwritten by loading");
        }
    }

    @Test
    void theBoundariesAreAccepted() throws IOException {
        assertEquals(interval(5), loadFrom("{\"usageIntervalSeconds\": 5}"));
        assertEquals(interval(3600), loadFrom("{\"usageIntervalSeconds\": 3600}"));
    }
}
