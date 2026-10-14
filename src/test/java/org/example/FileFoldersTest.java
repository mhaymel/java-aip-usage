package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileFoldersTest {

    @TempDir
    Path dir;

    @Test
    void withNoFolderNamedEverythingIsInTheProgramsFolder() {
        FileFolders folders = FileFolders.atStart(AppFiles.in(dir), "", "");
        assertEquals(dir.resolve("java-aip-usage.log"), folders.log());
        assertEquals(dir.resolve("java-aip-usage.csv"), folders.history());
        assertEquals(dir.resolve("java-aip-usage.fake.csv"), folders.fakeHistory(), "the invented readings go with the history");
        assertEquals(dir.resolve("settings.json"), folders.settings());
    }

    @Test
    void aFolderThatIsThereIsUsedAndOneThatIsGoneIsNotAtAStart() throws IOException {
        Path logs = Files.createDirectory(dir.resolve("logs"));
        FileFolders folders = FileFolders.atStart(AppFiles.in(dir), logs.toString(), dir.resolve("gone").toString());
        assertEquals(logs.resolve("java-aip-usage.log"), folders.log());
        assertEquals(dir.resolve("java-aip-usage.csv"), folders.history(), "the program's folder, for this run");
    }

    @Test
    void aFolderIsOneAFileCanBeWrittenIn() throws IOException {
        assertNull(FileFolders.fault(dir));
        assertEquals("the file or its folder is not there", FileFolders.fault(dir.resolve("gone")));
        Path file = Files.writeString(dir.resolve("a-file"), "x");
        assertEquals("the file or its folder is not there", FileFolders.fault(file), "a file is no folder");
        try (var listed = Files.list(dir)) {
            assertEquals(1, listed.count(), "the file written to try is gone again");
        }
    }

    @Test
    void theLogAndItsOldLogsAreMovedAndAFileOfTheSameNameIsLeft() throws IOException {
        Path log = Files.writeString(dir.resolve("java-aip-usage.log"), "now\n");
        Files.writeString(dir.resolve("java-aip-usage.2026-10-13_14-30-05.log"), "old\n");
        Files.writeString(dir.resolve("java-aip-usage.notes.log"), "not an old log\n");
        Path to = Files.createDirectory(dir.resolve("to"));
        Files.writeString(to.resolve("java-aip-usage.2026-10-13_14-30-05.log"), "there already\n");

        List<Path> moved = Logging.moveFiles(log, to);

        assertEquals(List.of(to.resolve("java-aip-usage.log")), moved, "the old log of that name was there already");
        assertEquals("there already\n", Files.readString(to.resolve("java-aip-usage.2026-10-13_14-30-05.log")), "and is not overwritten");
        assertTrue(Files.exists(dir.resolve("java-aip-usage.2026-10-13_14-30-05.log")), "the one of the old folder stays");
        assertTrue(Files.exists(dir.resolve("java-aip-usage.notes.log")), "what is no old log is not touched");
    }

    @Test
    void anOpenLogIsWrittenInTheNewFolderAfterTheMove() throws IOException {
        Path to = Files.createDirectory(dir.resolve("to"));
        try (Logging logging = Logging.install(dir.resolve("java-aip-usage.log"))) {
            org.example.log.Log.of(FileFoldersTest.class).info("before");
            logging.moveTo(to);
            org.example.log.Log.of(FileFoldersTest.class).info("after");
        }
        String moved = Files.readString(to.resolve("java-aip-usage.log"));
        assertTrue(moved.contains("before") && moved.contains("after"), moved);
        assertFalse(Files.exists(dir.resolve("java-aip-usage.log")));
    }

    @Test
    void theHistoryMovesItselfOrUsesTheFileThatIsThere() throws IOException {
        Path file = Files.writeString(dir.resolve("java-aip-usage.csv"), "old\n");
        org.example.usage.UsageHistory history = new org.example.usage.UsageHistory(file);
        Path to = Files.createDirectory(dir.resolve("to"));

        assertTrue(history.moveTo(to));
        assertEquals(to.resolve("java-aip-usage.csv"), history.file());
        assertFalse(Files.exists(file));

        Path third = Files.createDirectory(dir.resolve("third"));
        Files.writeString(third.resolve("java-aip-usage.csv"), "there\n");
        assertFalse(history.moveTo(third), "one of that name is there");
        assertEquals("there\n", Files.readString(third.resolve("java-aip-usage.csv")));
        assertTrue(Files.exists(to.resolve("java-aip-usage.csv")), "the one it had stays");
    }

    @Test
    void aMoveThatFailsPutsBackWhatWasMoved() throws IOException {
        Path log = Files.writeString(dir.resolve("java-aip-usage.log"), "now\n");
        assertThrows(IOException.class, () -> Logging.moveFiles(log, dir.resolve("gone")));
        assertTrue(Files.exists(log), "nothing was moved");
    }
}
