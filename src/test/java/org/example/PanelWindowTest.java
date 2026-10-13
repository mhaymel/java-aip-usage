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
        assertTrue(all.contains("PROBE history-lines=14:26:53 3.00 4.00 |14:25:53 2.00 2.00 |14:24:53 1.00 2.00 "),
                "the history panel shows the rows, newest first:\n" + all);
        assertTrue(all.contains("PROBE log-lines=19:00:00 INFO    [Probe] probe log line last|19:00:59 INFO    [Probe] probe log line 59|"),
                "the log panel has the lines newest first:\n" + all);
        assertTrue(all.contains("PROBE log-scroll=0"), "and is scrolled to the top:\n" + all);
        // The lists are drawn in one of the sturdy typefaces they name, not in the system's thin one: on Windows that is
        // Cascadia Mono or Consolas, on macOS the system's own fixed-width one, SF Mono or Menlo.
        String typefaces = all.lines().filter(l -> l.startsWith("PROBE list-typefaces=")).findFirst().orElseThrow();
        System.out.println(typefaces);
        assertTrue(typefaces.matches(".* used:(ui-monospace|Cascadia Mono|SF Mono|SFMono-Regular|Menlo|Consolas)"),
                "the window takes one of the typefaces that are named:\n" + all);
        assertTrue(all.contains("PROBE log-top=19:00:00 INFO    [Probe] probe log line last"), "so the newest line is the first the person sees:\n" + all);
        int row = Integer.parseInt(all.lines().filter(l -> l.startsWith("PROBE settings-row=")).findFirst().orElseThrow().substring("PROBE settings-row=".length()));
        assertTrue(width(all, "settings") > row, "with every element hidden the row is narrow, and the settings need more room than it has:\n" + all);
        assertEquals(width(all, "settings"), width(all, "settings-fitted"), "and ask for the same whatever the window's width is:\n" + all);
        assertTrue(all.contains("PROBE settings-lines=0"), "no text of the settings wraps:\n" + all);
        String[] tops = all.lines().filter(l -> l.startsWith("PROBE settings-buttons=")).findFirst().orElseThrow()
                .substring("PROBE settings-buttons=".length()).split(",");
        assertTrue(tops.length == 2 && tops[0].equals(tops[1]), "the two buttons are in one row:\n" + all);
        assertTrue(all.contains("PROBE settings-overflow=0"), "and nothing sticks out at the right:\n" + all);
        assertEquals(width(all, "closed"), width(all, "settings-closed"), "the row's width again when the settings are left:\n" + all);
        assertEquals(width(all, "settings-narrow"), width(all, "settings-after-wide"),
                "the settings ask for their own width also in a window as wide as the log left it:\n" + all);
        assertTrue(all.contains("PROBE history-kinds=c-time|c-used|c-limit|c-deltaUsed|c-deltaTime"), "both changes and no currency: the time change is the last column:\n" + all);
        assertTrue(all.contains("PROBE history-overflow=0"), "with the window as wide as the page asks, the history has nothing to scroll sideways:\n" + all);
        assertTrue(all.contains("PROBE history-cell-overflow=0"), "and no cell is wider than its column:\n" + all);
        // The probe pushes the lines 24 pixels to the right; what the list then lacks is that less the margin it keeps at its right.
        int lacking = Integer.parseInt(all.lines().filter(l -> l.startsWith("PROBE history-lacking=")).findFirst().orElseThrow().substring("PROBE history-lacking=".length()));
        assertTrue(lacking > 0 && lacking <= 24, "the list lacks room, and can be scrolled sideways:\n" + all);
        assertEquals(width(all, "history-columns") + lacking, width(all, "history-wider"), "the page asks for what the list lacks:\n" + all);
        assertEquals(width(all, "history-wider"), width(all, "history-settled"), "and keeps to it once the window has it:\n" + all);
        assertTrue(all.contains("PROBE history-overflow-after=0"), "so that the list is not scrolled sideways any more:\n" + all);
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
        boolean ended = process.waitFor(80, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        output.addAll(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        assertTrue(ended, "the probe did not end:\n" + String.join("\n", output));
        return process.exitValue();
    }
}
