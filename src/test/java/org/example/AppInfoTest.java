package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately does not pin the version to a number: it is increased by hand, and a
 * test that named it would fail every time that is done.
 */
class AppInfoTest {

    @Test
    void theNameIsAipUsage() {
        assertEquals("aip usage", AppInfo.NAME);
    }

    @Test
    void theVersionLooksLikeAVersion() {
        assertTrue(AppInfo.VERSION.matches("\\d+\\.\\d+(\\.\\d+)?"), "version: " + AppInfo.VERSION);
    }

    @Test
    void theWindowTitleIsTheNameThenTheVersion() {
        assertEquals("aip usage v" + AppInfo.VERSION, AppInfo.windowTitle());
    }

    @Test
    void theVersionIsPartOfTheTitleBar() {
        assertTrue(AppInfo.windowTitle().endsWith(AppInfo.VERSION), AppInfo.windowTitle());
        assertTrue(AppInfo.windowTitle().startsWith(AppInfo.NAME), AppInfo.windowTitle());
    }

    @Test
    void thePageInTheWindowIsTitledWithTheNameAlone() throws Exception {
        // Bumping the version should mean changing one constant, so the page does not repeat it.
        String html = new String(AppInfoTest.class.getResourceAsStream("/web/index.html").readAllBytes());

        assertTrue(html.contains("<title>" + AppInfo.NAME + "</title>"));
        assertFalse(html.contains(AppInfo.VERSION), "the version is written in one place only");
    }
}
