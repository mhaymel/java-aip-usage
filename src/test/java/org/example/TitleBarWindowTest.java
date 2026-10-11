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
        // The log panel has its line of two choices, and each takes effect on the lines that are shown.
        assertTrue(line(all, "log").contains(" logControls:true "), all);
        assertTrue(line(all, "log").matches(".* logLines:\\d+/[1-9]\\d*/\\d\\d:\\d\\d:\\d\\d .*"), "information is shown, and the lines begin with the time:\n" + all);
        assertTrue(line(all, "log-date").matches(".* logLines:\\d+/\\d+/\\d{4}-\\d\\d-\\d\\d .*"), "with the date switched on they begin with it:\n" + all);
        assertTrue(line(all, "log-warning").matches(".* logLines:\\d+/0/.*"), "warnings and worse: no line of information:\n" + all);
        assertTrue(line(all, "log-back").matches(".* logLines:\\d+/[1-9]\\d*/\\d\\d:\\d\\d:\\d\\d .*"), all);
        for (String without : List.of("start", "history", "errors", "settings")) {
            assertTrue(line(all, without).contains(" logControls:false "), without + " has no such line:\n" + all);
        }
        assertEquals(all.lines().filter(l -> l.contains("=titleBar:")).count(), all.lines().filter(l -> l.contains(" windows:1 ")).count(),
                "one window at every step:\n" + all);
        assertTrue(!all.contains("PROBE failed"), all);
        // The row has its size again after each panel, in the window that has no frame.
        String row = line(all, "start").replaceAll(".* content:", "");
        for (String bare : List.of("settings-left", "history-closed", "errors-closed")) {
            assertEquals(row, line(all, bare).replaceAll(".* content:", ""), bare + ":\n" + all);
        }
        assertTrue(all.contains("PROBE the program ended after the window was closed"), all);
        assertEquals(0, exit, all);
    }

    /**
     * Debug mode in the application itself: the yellow button, the red border while the fake backend is in use, the debug
     * settings as a panel, and the fake backend switched off and on again while the program runs.
     */
    @Test
    @Timeout(90)
    void inDebugModeTheFakeBackendIsSwitchedWhileTheProgramRunsAndTheBorderFollowsIt() throws Exception {
        List<String> output = new ArrayList<>();
        int exit = runProbe(UsageAppProbe.class, output);
        String all = String.join("\n", output.stream().filter(line -> line.startsWith("PROBE")).toList());
        System.out.println(all);
        // The probe starts the window class itself, which logs to the console; the file is set up by the program's own entry point.
        String log = String.join("\n", output);

        // Started with the fake backend on: the border, the invented figures, the debug button.
        assertTrue(line(all, "start").contains(" app:[fake] "), all);
        assertTrue(line(all, "start").contains(" border:3px "), "a border of three pixels:\n" + all);
        assertTrue(line(all, "start").contains(" debugButton:true "), all);
        assertTrue(line(all, "start").matches(".* used:\\[\\d+\\.\\d\\d\\] .*"), "the figures of the fake backend:\n" + all);
        assertTrue(all.lines().filter(l -> l.contains("=titleBar:")).allMatch(l -> l.contains(" overflow:0")), "the border never pushes the page past the window:\n" + all);

        // The debug settings are a panel: named, and with the title bar.
        assertTrue(line(all, "debug").contains(",3,debug "), all);
        assertTrue(line(all, "debug").contains("titleBar:true"), all);
        // The border goes round the view, not round the row alone: its right side is at the right edge of the window, and
        // the view is inside it.
        for (String view : List.of("settings", "settings-narrow", "debug")) {
            assertTrue(line(all, view).contains(" border:3px "), view + ":\n" + all);
            assertTrue(line(all, view).contains(" gap:0 "), view + ": the border reaches the right edge of the window:\n" + all);
            assertTrue(line(all, view).contains(" outside:0 "), view + ": and the view is inside it:\n" + all);
        }

        // Switched off: no border, and the invented figures are gone with their source.
        assertTrue(line(all, "fake-off-settled").contains(" border:0px "), "the border goes with the fake backend:\n" + all);
        assertTrue(line(all, "fake-off-settled").contains(" app:[] "), all);
        assertTrue(line(all, "fake-off-settled").contains(" used:[] "), "nothing invented is shown as real:\n" + all);
        assertTrue(line(all, "fake-off-settled").contains(" debugButton:true "), "the debug button is the one sign of debug mode then:\n" + all);

        // Switched on again, by leaving the debug settings through their own button, which saves them.
        assertTrue(line(all, "fake-on-settled").contains(" border:3px "), all);
        assertTrue(line(all, "fake-on-settled").matches(".* used:\\[\\d+\\.\\d\\d\\] .*"), all);

        // The readings went to the file of their own, and the log says each change.
        assertTrue(java.nio.file.Files.exists(dir.resolve("java-aip-usage.fake.csv")), "the invented readings have their file");
        assertTrue(!java.nio.file.Files.exists(dir.resolve("java-aip-usage.csv"))
                        || java.nio.file.Files.readAllLines(dir.resolve("java-aip-usage.csv")).stream().skip(1).allMatch(row -> row.contains("failed,")),
                "and the real history holds nothing but the failed queries of the address nothing listens at");
        assertTrue(log.contains("Debug mode (--debug)"), log);
        assertTrue(log.contains("The fake backend is in use: these readings are invented"), log);
        assertTrue(log.contains("Usage is fetched from http://127.0.0.1:9"), log);
        assertEquals(1, log.lines().filter(l -> l.contains("Frontend loaded")).count(), "the page was loaded once through all of it");
        assertTrue(!all.contains("PROBE failed"), all);
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
