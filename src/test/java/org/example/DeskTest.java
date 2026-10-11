package org.example;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The command that shows a file in its folder; that it does is seen only on a desktop. */
class DeskTest {

    private static final Path FILE = Path.of("work", "aip", "settings.json").toAbsolutePath().normalize();

    @Test
    void onWindowsTheExplorerIsAskedToMarkTheFile() {
        assertEquals(List.of("explorer.exe", "/select," + FILE), Desk.revealCommand("Windows 11", FILE, true));
    }

    @Test
    void onMacOsTheFinderIsAskedToMarkTheFile() {
        assertEquals(List.of("open", "-R", FILE.toString()), Desk.revealCommand("Mac OS X", FILE, true));
    }

    @Test
    void elsewhereTheFolderIsOpened() {
        assertEquals(List.of("xdg-open", FILE.getParent().toString()), Desk.revealCommand("Linux", FILE, true));
    }

    @Test
    void aFileThatIsNotThereCannotBeMarkedSoItsFolderIsOpened() {
        assertEquals(List.of("explorer.exe", FILE.getParent().toString()), Desk.revealCommand("Windows 11", FILE, false));
        assertEquals(List.of("open", FILE.getParent().toString()), Desk.revealCommand("Mac OS X", FILE, false));
        assertEquals(List.of("xdg-open", FILE.getParent().toString()), Desk.revealCommand("Linux", FILE, false));
    }

    @Test
    void aRelativePathIsMadeFullSoThatTheFileManagerFindsIt() {
        Path relative = Path.of("settings.json");

        List<String> command = Desk.revealCommand("Windows 11", relative, true);

        assertEquals("/select," + relative.toAbsolutePath().normalize(), command.get(1));
    }

    @Test
    void thePathIsOneArgumentHoweverItIsSpelled() {
        Path odd = Path.of("my files", "a&b", "settings.json").toAbsolutePath();

        assertEquals(2, Desk.revealCommand("Windows 11", odd, true).size(), "nothing of the path becomes an argument of its own");
        assertEquals(3, Desk.revealCommand("Mac OS X", odd, true).size());
    }
}
