package org.example.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.settings.SettingsStore.Saved;
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

class SettingsStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("settings.json");
    }

    private Saved loadFrom(String content) throws IOException {
        Files.writeString(file(), content);
        return new SettingsStore(file()).load();
    }

    @Test
    void aMissingFileHoldsNothing() {
        assertEquals(Saved.NONE, new SettingsStore(file()).load());
    }

    @Test
    void aSavedValueComesBack() throws IOException {
        SettingsStore store = new SettingsStore(file());

        store.save(new Saved(45));

        assertEquals(new Saved(45), store.load());
    }

    @Test
    void savingReplacesTheWholeFile() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(new Saved(45));

        store.save(new Saved(90));

        assertEquals(new Saved(90), store.load());
    }

    @Test
    void theFileHoldsOnlyTheUsageInterval() throws IOException {
        new SettingsStore(file()).save(new Saved(45));

        JsonNode content = new ObjectMapper().readTree(Files.readString(file()));

        assertEquals(List.of("usageIntervalSeconds"), content.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }

    @Test
    void savingLeavesNoTemporaryFileBehind() throws IOException {
        new SettingsStore(file()).save(new Saved(45));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("settings.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailedSaveLeavesTheExistingFileUntouched() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(new Saved(45));
        // A directory where the temporary file must go makes writing it fail.
        Files.createDirectory(dir.resolve("settings.json.tmp"));

        assertThrows(IOException.class, () -> store.save(new Saved(99)));

        assertEquals(new Saved(45), store.load());
    }

    @Test
    void savingIntoAMissingDirectoryFails() {
        SettingsStore store = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));

        assertThrows(IOException.class, () -> store.save(new Saved(45)));
    }

    // ---- an older file, from when the update interval was a saved setting too

    @Test
    void anUpdateIntervalLeftInTheFileByAnEarlierVersionIsIgnored() throws IOException {
        assertEquals(new Saved(45), loadFrom("{\"usageIntervalSeconds\": 45, \"pollIntervalSeconds\": 5}"));
        assertEquals(Saved.NONE, loadFrom("{\"pollIntervalSeconds\": 5}"));
    }

    @Test
    void savingDropsTheOldUpdateIntervalFromTheFile() throws IOException {
        Files.writeString(file(), "{\"usageIntervalSeconds\": 45, \"pollIntervalSeconds\": 5}");
        SettingsStore store = new SettingsStore(file());

        store.save(new Saved(90));

        assertFalse(Files.readString(file()).contains("pollIntervalSeconds"), Files.readString(file()));
        assertEquals(new Saved(90), store.load());
    }

    // ---- reading is forgiving

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        assertEquals(new Saved(45), loadFrom("{\"usageIntervalSeconds\": 45, \"theme\": \"dark\"}"));
    }

    @Test
    void anOutOfRangeValueIsIgnored() throws IOException {
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": 4}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": 3601}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": 0}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": -30}"));
    }

    @Test
    void valuesOfTheWrongTypeAreIgnored() throws IOException {
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": \"45\"}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": 45.5}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": null}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": true}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": 99999999999}"));
    }

    @Test
    void aDamagedFileIsIgnoredNotFatal() throws IOException {
        assertEquals(Saved.NONE, loadFrom("not json"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": "));
        assertEquals(Saved.NONE, loadFrom("[1, 2]"));
        assertEquals(Saved.NONE, loadFrom(""));
    }

    @Test
    void theBoundariesAreAccepted() throws IOException {
        assertEquals(new Saved(5), loadFrom("{\"usageIntervalSeconds\": 5}"));
        assertEquals(new Saved(3600), loadFrom("{\"usageIntervalSeconds\": 3600}"));
    }
}
