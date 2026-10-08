package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens the main window for real, in a child JVM with JavaFX, and checks what the panels in it hold and
 * how tall the window's page asks to be. It puts a window on the screen for a few seconds, so it runs only when asked for:
 *
 * <pre>./gradlew test --tests '*PanelWindowTest' -Daipusage.windows=true</pre>
 */
@EnabledIfSystemProperty(named = "aipusage.windows", matches = "true")
class PanelWindowTest {

    @TempDir
    Path dir;

    @Test
    @Timeout(90)
    void theButtonsShowThePanelsInTheMainWindowAndClosingItEndsTheProgram() throws Exception {
        List<String> output = new ArrayList<>();
        int exit = runProbe(output);

        String all = String.join("\n", output);
        int closed = height(all, "closed");
        assertEquals(10 * closed, height(all, "history"), "ten times as tall with the history shown:\n" + all);
        assertEquals(10 * closed, height(all, "log"), "and the same with the log shown instead");
        assertEquals(width(all, "closed"), width(all, "history"), "as wide as the row for the history");
        assertEquals(3 * width(all, "closed"), width(all, "log"), "and three times as wide for the log");
        assertEquals(closed, height(all, "closed-again"), "and back to its size when the panel is hidden");
        assertTrue(all.contains("PROBE history-lines=2026-10-08 14:26:53 3.00 4.00 |2026-10-08 14:25:53 2.00 2.00 |2026-10-08 14:24:53 1.00 2.00 "),
                "the history panel shows the rows, newest first:\n" + all);
        assertTrue(all.contains("PROBE log-lines=probe log line last|probe log line 59|"),
                "the log panel has the lines newest first:\n" + all);
        assertTrue(all.contains("PROBE log-scroll=0"), "and is scrolled to the top:\n" + all);
        assertTrue(all.contains("PROBE log-top=probe log line last"), "so the newest line is the first the person sees:\n" + all);
        assertTrue(all.contains("PROBE other-windows=0"), "no window besides the main one:\n" + all);
        assertTrue(all.contains("PROBE the program ended after the main window was closed"), all);
        assertEquals(0, exit, "the program ended by itself once the main window was closed:\n" + all);
    }

    /** The height the page reported, from a line {@code PROBE name=width,height,resizable}. */
    private static int height(String all, String name) {
        return Integer.parseInt(size(all, name)[1]);
    }

    private static int width(String all, String name) {
        return Integer.parseInt(size(all, name)[0]);
    }

    private static String[] size(String all, String name) {
        String prefix = "PROBE " + name + "=";
        String line = all.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
        return line.substring(prefix.length()).split(",");
    }

    private int runProbe(List<String> output) throws IOException, InterruptedException {
        List<String> modulePath = new ArrayList<>();
        List<String> classPath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            (Path.of(entry).getFileName().toString().startsWith("javafx-") ? modulePath : classPath).add(entry);
        }
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path", String.join(java.io.File.pathSeparator, modulePath),
                "--add-modules", "javafx.controls,javafx.web",
                "--enable-native-access=javafx.graphics,javafx.web",
                "-cp", String.join(java.io.File.pathSeparator, classPath),
                PanelProbe.class.getName(), dir.toString())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        boolean ended = process.waitFor(60, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        output.addAll(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        assertTrue(ended, "the probe did not end:\n" + String.join("\n", output));
        return process.exitValue();
    }
}
