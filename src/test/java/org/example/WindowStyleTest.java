package org.example;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The style sheet of the window, which sizes the tooltips; that they take it is seen only in a real window. */
class WindowStyleTest {

    @Test
    void theTooltipsOfTheWindowAreFourteenPixels() throws IOException {
        try (InputStream in = WindowStyleTest.class.getResourceAsStream("/window.css")) {
            assertNotNull(in, "the style sheet is a file of the program");
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(css.matches("(?s).*\\.tooltip\\s*\\{\\s*-fx-font-size:\\s*14px;\\s*}.*"), css);
        }
    }
}
