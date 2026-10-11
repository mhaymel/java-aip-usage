package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takes the title bar of a real window away and gives it back, in a child JVM with JavaFX, and checks
 * that the page in it is still the one that was loaded, that the content stays where it was on the
 * screen, and that the program goes on until the window is closed. It puts a window on the screen for
 * a few seconds, so it runs only when asked for:
 *
 * <pre>./gradlew test --tests '*TitleBarWindowTest' -Daipusage.windows=true</pre>
 */
@EnabledIfSystemProperty(named = "aipusage.windows", matches = "true")
class TitleBarWindowTest {

    private static final Pattern REPORT = Pattern.compile(
            "titleBar:(\\w+) mark:(\\w+) loads:(\\d+) size:(\\S+) contentAt:(-?\\d+),(-?\\d+) content:(\\d+)x(\\d+) frameTop:(-?\\d+) sameScene:(\\w+) windows:(\\d+)");

    @TempDir
    Path dir;

    @Test
    @Timeout(90)
    void theTitleBarGoesAndComesBackAndThePageDoesNotNotice() throws Exception {
        List<String> output = new ArrayList<>();
        int exit = runProbe(TitleBarProbe.class, output);
        String all = String.join("\n", output);
        System.out.println(all);

        Matcher with = report(all, "with");
        Matcher without = report(all, "without");
        Matcher again = report(all, "with-again");

        assertEquals("true", with.group(1), all);
        assertEquals("false", without.group(1), "the title bar is gone:\n" + all);
        assertEquals("true", again.group(1), "and back:\n" + all);
        assertEquals("0", without.group(9), "a window with no title bar has no frame above its content:\n" + all);
        assertTrue(Integer.parseInt(again.group(9)) > 0, "and one with a title bar has:\n" + all);
        for (Matcher report : List.of(without, again)) {
            assertEquals("kept", report.group(2), "the page is the one that was loaded, with what was in it:\n" + all);
            assertEquals("1", report.group(3), "it was loaded once:\n" + all);
            assertEquals(with.group(4), report.group(4), "and still answers the size question the same:\n" + all);
            assertEquals(with.group(5), report.group(5), "the content has not moved sideways:\n" + all);
            assertEquals(with.group(6), report.group(6), "nor up or down:\n" + all);
            assertEquals(with.group(7), report.group(7), "it is as wide as it was:\n" + all);
            assertEquals(with.group(8), report.group(8), "and as tall:\n" + all);
            assertEquals("true", report.group(10), all);
            assertEquals("1", report.group(11), "one window, never two left behind:\n" + all);
        }
        assertTrue(all.contains("PROBE first-still-showing=false"), "the window that was replaced is closed:\n" + all);
        assertTrue(all.contains("PROBE the program ended after the window was closed"), all);
        assertEquals(0, exit, "the program went on through both exchanges and ended when the window was closed:\n" + all);
    }

    /**
     * The application itself, with the title bar switched off: the window is the row alone, has its title bar for as long as
     * any panel is open, and loses it again when the panel is closed.
     */
    @Test
    @Timeout(90)
    void withTheTitleBarOffTheWindowHasItOnlyWhileAPanelIsOpen() throws Exception {
        List<String> output = new ArrayList<>();
        int exit = runProbe(UsageAppProbe.class, output);
        String all = String.join("\n", output.stream().filter(line -> line.startsWith("PROBE")).toList());
        System.out.println(all);

        for (String bare : List.of("start", "settings-left", "history-closed", "errors-closed")) {
            assertTrue(line(all, bare).contains("titleBar:false"), bare + ": no panel, no title bar:\n" + all);
            assertTrue(line(all, bare).matches(".* size:\\d+,\\d+,0 .*"), bare + ": the row alone:\n" + all);
        }
        for (String open : List.of("settings", "history", "log", "errors")) {
            assertTrue(line(all, open).contains("titleBar:true"), open + ": a panel is open, so the title bar is there:\n" + all);
            assertTrue(line(all, open).contains("," + open + " "), open + ": and the page names it:\n" + all);
        }
        assertEquals(8, all.lines().filter(l -> l.contains(" windows:1 ")).count(), "one window at every step:\n" + all);
        assertTrue(!all.contains("PROBE failed"), all);
        // The row has its size again after each panel, in the window that has no frame.
        String row = line(all, "start").replaceAll(".* content:", "");
        for (String bare : List.of("settings-left", "history-closed", "errors-closed")) {
            assertEquals(row, line(all, bare).replaceAll(".* content:", ""), bare + ":\n" + all);
        }
        assertTrue(all.contains("PROBE the program ended after the window was closed"), all);
        assertEquals(0, exit, all);
    }

    private static String line(String all, String name) {
        String prefix = "PROBE " + name + "=";
        return all.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " in:\n" + all));
    }

    private static Matcher report(String all, String name) {
        String prefix = "PROBE " + name + "=";
        String line = all.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " in:\n" + all));
        Matcher matcher = REPORT.matcher(line.substring(prefix.length()));
        assertTrue(matcher.matches(), line);
        return matcher;
    }

    private int runProbe(Class<?> probe, List<String> output) throws Exception {
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
                probe.getName(), dir.toString())
                // The application keeps its files in the directory it is started in.
                .directory(dir.toFile())
                .redirectErrorStream(true)
                // To a file, not a pipe: the application logs to the console, and a pipe nobody reads fills up.
                .redirectOutput(dir.resolve("probe-output.txt").toFile())
                .start();
        process.getOutputStream().close();
        boolean ended = process.waitFor(60, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        output.addAll(java.nio.file.Files.readAllLines(dir.resolve("probe-output.txt"), StandardCharsets.UTF_8));
        assertTrue(ended, "the probe did not end:\n" + String.join("\n", output));
        return process.exitValue();
    }
}
