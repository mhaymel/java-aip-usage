package org.example;

import org.example.log.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can be held of the program's name on macOS without a Mac and without a window: when its steps are taken, that
 * the name is the one the program has and is written nowhere a second time, and that a step that cannot be taken says
 * so and stops nothing. What the system then shows is for a person to see; the name the toolkit's application has is
 * checked in a real window by {@link TitleBarWindowTest}.
 */
class MacNameTest {

    private final String switchBefore = System.getProperty(MacName.SWITCH);

    private final String nameBefore = System.getProperty("apple.awt.application.name");

    @AfterEach
    void putTheSwitchBack() {
        restore(MacName.SWITCH, switchBefore);
        restore("apple.awt.application.name", nameBefore);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void theSystemIsMacOsByTheNameItGivesItself() {
        assertTrue(MacName.isMac("Mac OS X"));
        assertTrue(MacName.isMac("macOS"));
        assertFalse(MacName.isMac("Windows 11"));
        assertFalse(MacName.isMac("Linux"));
        assertFalse(MacName.isMac(null));
    }

    @Test
    void theStepsAreTakenOnMacOsAndNowhereElseUnlessTheSwitchSaysOtherwise() {
        System.clearProperty(MacName.SWITCH);
        assertEquals(MacName.isMac(System.getProperty("os.name")), MacName.applies(), "by the system when nothing is said");

        System.setProperty(MacName.SWITCH, "true");
        assertTrue(MacName.applies());
        System.setProperty(MacName.SWITCH, "false");
        assertFalse(MacName.applies(), "the switch takes them away on macOS as well");
        System.setProperty(MacName.SWITCH, "yes");
        assertFalse(MacName.applies(), "anything but true is no");
    }

    @Test
    void switchedOffNothingIsDoneAndNothingIsSaid() {
        System.setProperty(MacName.SWITCH, "false");
        System.clearProperty("apple.awt.application.name");
        List<String> lines = new CopyOnWriteArrayList<>();
        Log.Sink capture = entry -> lines.add(entry.message());
        Log.addSink(capture);
        try {
            MacName.beforeLaunch();
            assertNull(MacName.nameTheApplication());
            MacName.showTheIconInTheDock();
        } finally {
            Log.removeSink(capture);
        }
        assertNull(System.getProperty("apple.awt.application.name"));
        assertEquals(List.of(), lines);
    }

    @Test
    void theNameGivenToTheSystemIsTheProgramsOwn() {
        System.setProperty(MacName.SWITCH, "true");

        MacName.beforeLaunch();

        assertEquals(AppInfo.NAME, System.getProperty("apple.awt.application.name"));
        assertEquals("aip usage", AppInfo.NAME);
    }

    @Test
    void theNameIsWrittenInOnePlaceOfTheProgramAndTheStepsOnlyReferToIt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/org/example/MacName.java"));
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertFalse(code.contains("\"aip usage\""), "the name is AppInfo's, not written here a second time");
        assertTrue(code.contains("AppInfo.NAME"));
    }

    @Test
    void aStepThatCannotBeTakenIsADebugLineAndStopsNothing() {
        // No toolkit is running in this test, so its application cannot be named: that must be said, at the debug level, and
        // must not be a fault of the caller.
        System.setProperty(MacName.SWITCH, "true");
        List<String> lines = new CopyOnWriteArrayList<>();
        Log.Sink capture = entry -> lines.add(entry.level().word() + " " + entry.message());
        Log.addSink(capture);
        try {
            assertNull(MacName.nameTheApplication());
        } finally {
            Log.removeSink(capture);
        }
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("DEBUG The application could not be named for the system: "), lines.get(0));
    }
}
