package org.example.settings;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void savedValuesComeBack() throws IOException {
        SettingsStore store = new SettingsStore(file());

        store.save(new Saved(45, 3));

        assertEquals(new Saved(45, 3), store.load());
    }

    @Test
    void aSettingNeverSetStaysAbsent() throws IOException {
        SettingsStore store = new SettingsStore(file());

        store.save(new Saved(null, 7));

        assertEquals(new Saved(null, 7), store.load());
        assertFalse(Files.readString(file()).contains("usageIntervalSeconds"));
    }

    @Test
    void savingReplacesTheWholeFile() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(new Saved(45, 3));

        store.save(new Saved(null, 9));

        assertEquals(new Saved(null, 9), store.load());
    }

    @Test
    void theFileHoldsOnlyTheTwoIntervals() throws IOException {
        new SettingsStore(file()).save(new Saved(45, 3));

        String content = Files.readString(file());

        assertEquals(List.of("pollIntervalSeconds", "usageIntervalSeconds"),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(content)
                        .properties().stream().map(java.util.Map.Entry::getKey).sorted().toList());
    }

    @Test
    void savingLeavesNoTemporaryFileBehind() throws IOException {
        new SettingsStore(file()).save(new Saved(45, 3));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("settings.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailedSaveLeavesTheExistingFileUntouched() throws IOException {
        SettingsStore store = new SettingsStore(file());
        store.save(new Saved(45, 3));
        // A directory where the temporary file must go makes writing it fail.
        Files.createDirectory(dir.resolve("settings.json.tmp"));

        assertThrows(IOException.class, () -> store.save(new Saved(99, 9)));

        assertEquals(new Saved(45, 3), store.load());
    }

    @Test
    void savingIntoAMissingDirectoryFails() {
        SettingsStore store = new SettingsStore(dir.resolve("no-such-dir").resolve("settings.json"));

        assertThrows(IOException.class, () -> store.save(new Saved(45, 3)));
    }

    @Test
    void unknownFieldsAreIgnored() throws IOException {
        assertEquals(new Saved(45, null), loadFrom("{\"usageIntervalSeconds\": 45, \"theme\": \"dark\"}"));
    }

    @Test
    void anOutOfRangeValueIsIgnoredWithoutDiscardingTheOther() throws IOException {
        assertEquals(new Saved(null, 3), loadFrom("{\"usageIntervalSeconds\": 4, \"pollIntervalSeconds\": 3}"));
        assertEquals(new Saved(null, null), loadFrom("{\"usageIntervalSeconds\": 3601, \"pollIntervalSeconds\": 61}"));
        assertEquals(new Saved(null, null), loadFrom("{\"usageIntervalSeconds\": 0, \"pollIntervalSeconds\": 0}"));
    }

    @Test
    void valuesOfTheWrongTypeAreIgnored() throws IOException {
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": \"45\", \"pollIntervalSeconds\": 2.5}"));
        assertEquals(Saved.NONE, loadFrom("{\"usageIntervalSeconds\": null, \"pollIntervalSeconds\": true}"));
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
        assertEquals(new Saved(5, 1), loadFrom("{\"usageIntervalSeconds\": 5, \"pollIntervalSeconds\": 1}"));
        assertEquals(new Saved(3600, 60), loadFrom("{\"usageIntervalSeconds\": 3600, \"pollIntervalSeconds\": 60}"));
        assertTrue(true);
    }
}
