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
    @Timeout(240)
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
    @Timeout(240)
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
        // With no title bar the window is moved by dragging it, from a figure and from its edge, and not from a button; with
        // the title bar a drag inside it moves nothing.
        assertEquals("moved:40,25 hasTitleBar:false panel:false", line(all, "drag-figure").substring("PROBE drag-figure=".length()), all);
        assertEquals("moved:40,25 hasTitleBar:false panel:false", line(all, "drag-gap").substring("PROBE drag-gap=".length()), all);
        assertTrue(line(all, "drag-button").contains("moved:0,0 "), "a press on the gear begins no move:\n" + all);
        assertTrue(line(all, "drag-refresh").contains("moved:0,0 "), "nor does one on the refresh button:\n" + all);
        assertEquals("moved:0,0 hasTitleBar:true panel:true", line(all, "drag-with-title-bar").substring("PROBE drag-with-title-bar=".length()), all);

        // The button of Main view: a small icon in the line of the heading, which switches every element on, and all off when all are on.
        assertTrue(line(all, "settings-again").matches(".* allButton:1[2-8]x1[2-8]/[01]/\\d+/8 .*"), "some elements are on, and the button is in the middle of its heading:\n" + all);
        assertTrue(line(all, "settings-all").contains("/13 note:"), "one press: all thirteen on:\n" + all);
        assertTrue(line(all, "settings-narrow").contains("/0 note:"), "another: all off:\n" + all);

        // The window has the program's icon in its four sizes, with the title bar and without, in each window that took the place of another.
        assertEquals(all.lines().filter(l -> l.contains("=titleBar:")).count(), all.lines().filter(l -> l.contains(" icons:16/32/48/256 ")).count(),
                "the icon at every step:\n" + all);
        assertTrue(line(all, "start").contains("titleBar:false") && line(all, "history").contains("titleBar:true"), "both kinds of window were among them:\n" + all);

        // The tooltip of the amount used comes when the mouse rests on it, and is 14 pixels.
        assertEquals("PROBE tooltip=shown:[Credits used, in USD|14.0] sheets:1", line(all, "tooltip"), all);

        // The word of between the amount used and the budget: small, light and gray, and near the foot of the figures.
        assertTrue(line(all, "start").matches(".* of:\\[ of \\|14px\\|400\\|rgb\\(138, 143, 152\\)\\|[0-3]] .*"), all);
        assertTrue(line(all, "start").contains("187.39 of 1,000.00"), "the row reads so:\n" + all);

        // The zoom of the lists: the text of the history is 12 pixels with a line of 15, eight presses make it 20, the largest, with a
        // line of 25, the window is wider for the wider table, and eight presses back give what there was.
        Matcher usual = Pattern.compile(" size:(\\d+),.* list:\\[12px\\|15\\|true\\|20]").matcher(line(all, "history"));
        Matcher larger = Pattern.compile(" size:(\\d+),.* list:\\[20px\\|25\\|true\\|20]").matcher(line(all, "history-larger"));
        Matcher back = Pattern.compile(" size:(\\d+),.* list:\\[12px\\|15\\|true\\|20]").matcher(line(all, "history-usual"));
        assertTrue(usual.find() && larger.find() && back.find(), all);
        assertTrue(Integer.parseInt(larger.group(1)) > Integer.parseInt(usual.group(1)), "the window is wider for the larger text:\n" + all);
        assertEquals(usual.group(1), back.group(1), "and as wide as it was again:\n" + all);
        assertTrue(line(all, "start").contains("|false|"), "with no list there is no line of the zoom:\n" + all);
        assertTrue(line(all, "errors").contains(" list:[12px|") && line(all, "errors").contains("|true|20]"), "the error log has the line too:\n" + all);

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
        String row = line(all, "start").replaceAll(".* content:(\\S+) .*", "$1");
        for (String bare : List.of("settings-left", "history-closed", "errors-closed")) {
            assertEquals(row, line(all, bare).replaceAll(".* content:(\\S+) .*", "$1"), bare + ":\n" + all);
        }
        assertTrue(all.contains("PROBE the program ended after the window was closed"), all);
        assertEquals(0, exit, all);
    }

    /**
     * Debug mode in the application itself: the yellow button, the red border while the fake backend is in use, the debug
     * settings as a panel, and the fake backend switched off and on again while the program runs.
     */
    @Test
    @Timeout(240)
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
        // The red border goes with the fake backend. The title bar is off and no panel is shown, so the window has the border
        // of a window with no title bar instead: one pixel, and the window is larger by it and no more.
        assertTrue(line(all, "fake-off-settled").contains(" border:1px "), "the thin border of a window with no title bar:\n" + all);
        assertTrue(line(all, "fake-off-settled").contains(" app:[bare] "), all);
        for (String bordered : List.of("start", "fake-off-settled", "fake-on-settled")) {
            assertTrue(line(all, bordered).contains(" gap:0 below:0 "), bordered + ": the border is at the right and the bottom edge of the window:\n" + all);
        }
        assertTrue(line(all, "fake-off-settled").contains("titleBar:false"), all);
        assertTrue(line(all, "debug-again").contains(" border:0px "), "a panel is shown and the title bar is there: no border of the page:\n" + all);
        assertTrue(line(all, "debug-again").contains("titleBar:true"), all);
        assertTrue(line(all, "fake-off-settled").contains(" used:[] "), "nothing invented is shown as real:\n" + all);
        // Nothing listens at the address, so there is no reading at all: gray boxes with a thin green border and their tooltip,
        // and no word in the row.
        String noData = line(all, "fake-off-settled");
        assertTrue(noData.contains(" usedBox:[used skeleton|1px|rgb(26, 127, 55)|Credits used: no data|")
                        || noData.contains(" usedBox:[used skeleton|1px|rgb(63, 185, 80)|Credits used: no data|"),
                "the amount used is a gray box, with a green border of one pixel:\n" + all);
        assertTrue(!noData.contains("No data"), "and the row does not say it in words:\n" + all);
        assertTrue(line(all, "start").contains(" usedBox:[used|0px|"), "a figure has no such border:\n" + all);
        assertTrue(line(all, "fake-off-settled").contains(" debugButton:true "), "the debug button is the one sign of debug mode then:\n" + all);

        // The message line of the failed refresh with a row of two buttons. With the settings shown the row's box and the
        // message are as wide as the window, not as the two buttons, and the message is a few lines, not a column.
        Matcher narrow = Pattern.compile(" note:\\[(\\d+)x(\\d+)\\|top:(\\d+)\\|elements:(\\d+)\\|window:(\\d+)]").matcher(line(all, "message-narrow-settings"));
        assertTrue(narrow.find(), all);
        assertTrue(Integer.parseInt(narrow.group(4)) < 80, "the row is two buttons:\n" + all);
        assertEquals(narrow.group(5), narrow.group(3), "its box is as wide as the window:\n" + all);
        assertTrue(Integer.parseInt(narrow.group(1)) > 200, "and so is the message, less the room at its sides:\n" + all);
        assertTrue(Integer.parseInt(narrow.group(2)) < 100, "which is a few lines tall:\n" + all);
        // With no panel the window has the least width a message gives it: 300 pixels, and the border of a window with no title bar.
        Matcher bare = Pattern.compile(" size:(\\d+),(\\d+),0 .* note:\\[(\\d+)x(\\d+)\\|top:(\\d+)\\|").matcher(line(all, "message-narrow-row"));
        assertTrue(bare.find(), all);
        assertEquals("302", bare.group(1), "300 pixels and the border:\n" + all);
        assertEquals("300", bare.group(5), all);
        assertTrue(Integer.parseInt(bare.group(4)) < 100, "the message is a few lines, and the window as tall as it needs:\n" + all);
        // A row that is wider than that keeps its own width.
        Matcher wide = Pattern.compile(" size:(\\d+),").matcher(line(all, "message-wide-row"));
        assertTrue(wide.find() && Integer.parseInt(wide.group(1)) > 400, "every element on: the row's own width:\n" + all);

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
        // The probe of the application takes about a minute, with its steps and their waits.
        boolean ended = process.waitFor(180, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        output.addAll(java.nio.file.Files.readAllLines(dir.resolve("probe-output.txt"), StandardCharsets.UTF_8));
        assertTrue(ended, "the probe did not end:\n" + String.join("\n", output));
        return process.exitValue();
    }
}
