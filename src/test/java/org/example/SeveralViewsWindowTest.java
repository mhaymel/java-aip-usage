package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several views at once, in the real window: the program is started twice in the same directory, by {@link SeveralViewsProbe}.
 * It puts a window on the screen for about a minute, so it runs only when asked for:
 *
 * <pre>./gradlew test --tests '*SeveralViewsWindowTest' -Daipusage.windows=true</pre>
 */
@EnabledIfSystemProperty(named = "aipusage.windows", matches = "true")
class SeveralViewsWindowTest {

    private static final Pattern STEP = Pattern.compile(
            "view:(\\S*) size:(\\d+),(\\d+),(\\d)(?:,(\\S+?))?(?:,(\\d+))? titleBar:(\\w+) drag:(w?h?)\\. content:(\\d+)x(\\d+) views:(\\S*) below:(-?\\d+) screen:(\\d+) end");

    @TempDir
    Path dir;

    @Test
    @Timeout(240)
    void viewsShownTogetherAreStackedInTheOrderOfTheIconsAndTheLowestListTakesTheDraggedHeight() throws Exception {
        List<String> first = new ArrayList<>();
        assertEquals(0, runProbe("first", first), String.join("\n", first));
        String all = String.join("\n", first);
        System.out.println(all.lines().filter(l -> l.startsWith("PROBE")).reduce("", (a, b) -> a + b + "\n"));

        // The cost control alone: nothing can be dragged, no title bar.
        // The figures of the cost control in four columns (since 0.83): the amounts end at one place, and so do the limits.
        String columns = all.lines().filter(l -> l.startsWith("PROBE cost-columns=")).findFirst().orElseThrow().substring("PROBE cost-columns=".length());
        String[] rows = columns.split("/");
        assertEquals(2, rows.length, columns);
        assertTrue(rows[0].matches("cost-used:\\d+,cost-of:\\d+,cost-limit:\\d+,cost-percent:\\d+"), "the day in four cells: " + columns);
        assertTrue(rows[1].matches("cost-used:\\d+,cost-of:\\d+,cost-limit:\\d+,cost-percent:\\d+"), "the month in four cells: " + columns);
        java.util.function.Function<String, String[]> rights = row -> java.util.Arrays.stream(row.split(",")).map(c -> c.split(":")[1]).toArray(String[]::new);
        assertEquals(rights.apply(rows[0])[0], rights.apply(rows[1])[0], "the amounts end under one another: " + columns);
        assertEquals(rights.apply(rows[0])[2], rights.apply(rows[1])[2], "and so do the limits: " + columns);
        Matcher cost = step(all, "cost");
        assertEquals("cost,", cost.group(1), all);
        assertEquals("false", cost.group(7), "the cost control alone has no title bar");
        assertEquals("", cost.group(8));

        // The history added: both shown, the history above the cost control, the height may be dragged, and there is a title bar.
        Matcher both = step(all, "cost-history");
        assertEquals("cost+history,", both.group(1), all);
        assertEquals("1", both.group(4));
        assertEquals("cost+history", both.group(5));
        assertEquals("true", both.group(7), "something can be dragged: the title bar is there");
        assertEquals("h", both.group(8), "the height, not the width");
        near(Integer.parseInt(both.group(3)), Integer.parseInt(both.group(10)), "the window is as tall as the page asked, but for the pixel a frame rounds up:\n" + all);
        assertEquals("0", both.group(12), "and the page fills it:\n" + all);
        String[] views = both.group(11).split("/");
        assertEquals(2, views.length, all);
        assertTrue(views[0].startsWith("cost-view:") && views[1].startsWith("view-history:"), "the cost control above the history (since 0.81):\n" + all);
        int costHeight = Integer.parseInt(views[0].split(":")[2]);
        int historyHeight = Integer.parseInt(views[1].split(":")[2]);

        // Dragged 150 taller: the history takes it, the cost control keeps its height, and the history's height alone is remembered.
        Matcher dragged = step(all, "dragged");
        near(Integer.parseInt(both.group(10)) + 150, Integer.parseInt(dragged.group(10)), all);
        String[] draggedViews = dragged.group(11).split("/");
        near(historyHeight + 150, Integer.parseInt(draggedViews[1].split(":")[2]), "the history takes the height:\n" + all);
        assertEquals(costHeight, Integer.parseInt(draggedViews[0].split(":")[2]), "the cost control keeps its own:\n" + all);
        Matcher settled = step(all, "dragged-settled");
        near(Integer.parseInt(dragged.group(10)), Integer.parseInt(settled.group(3)), "the page asks for the height it was given:\n" + all);
        near(Integer.parseInt(both.group(6)) + 150, Integer.parseInt(settled.group(6)), "and says the history has 150 more alone:\n" + all);
        assertEquals(Integer.parseInt(dragged.group(10)), Integer.parseInt(settled.group(10)), "and the window stays as it was dragged:\n" + all);

        // The log added below the history: the width may be dragged now too, and the window is as wide as the log needs. The
        // history keeps the height it was dragged to, and the log is the lowest list now.
        Matcher three = step(all, "log-history-cost");
        assertEquals("cost+history+log,", three.group(1), all);
        assertEquals("2", three.group(4));
        assertEquals("wh", three.group(8));
        assertTrue(Integer.parseInt(three.group(9)) > Integer.parseInt(both.group(9)), "wider for the log:\n" + all);
        assertTrue(three.group(11).matches("cost-view:\\d+:\\d+/view-history:\\d+:\\d+/view-log:\\d+:\\d+"), all);
        near(Integer.parseInt(draggedViews[1].split(":")[2]), Integer.parseInt(three.group(11).split("/")[1].split(":")[2]), "the history above the log keeps its dragged height:\n" + all);

        // The settings stand above the views, which stay (since 0.81), and leaving them closes them alone.
        Matcher settings = step(all, "settings");
        assertEquals("settings+cost+history+log,general", settings.group(1), all);
        assertTrue(settings.group(11).matches("settings-view:37:\\d+/cost-view:\\d+:\\d+/view-history:\\d+:\\d+/view-log:\\d+:\\d+"),
                "the settings first, right below the row:\n" + all);
        assertEquals("cost+history+log,", step(all, "settings-left").group(1), all);

        // Closed one by one: each icon closes its own view.
        assertEquals("cost+history,", step(all, "history-cost").group(1), all);
        Matcher alone = step(all, "cost-alone");
        assertEquals("cost,", alone.group(1), all);
        assertEquals("false", alone.group(7), "nothing to drag: no title bar again");
        Matcher again = step(all, "history-again");
        assertEquals(Integer.parseInt(dragged.group(10)), Integer.parseInt(again.group(10)), "the history comes back at the height it was dragged to:\n" + all);

        // All four views: the window within the screen, and the error log, the lowest, seen (since 0.82; img_36).
        Matcher four = step(all, "four");
        assertEquals("cost+history+log+errors,", four.group(1), all);
        assertTrue(Integer.parseInt(four.group(10)) <= Integer.parseInt(four.group(13)), "no taller than the screen:\n" + all);
        String[] fourViews = four.group(11).split("/");
        assertTrue(fourViews[3].startsWith("view-errors:") && Integer.parseInt(fourViews[3].split(":")[2]) >= 100, "the error log has room:\n" + all);
        // A screen of 600: the lists share it, and the window is 600 tall.
        Matcher small = step(all, "four-small");
        near(600, Integer.parseInt(small.group(10)), "the window as tall as the room:\n" + all);
        assertEquals(null, small.group(6), "no height alone is said while the lists share:\n" + all);
        String[] smallViews = small.group(11).split("/");
        int sharedHistory = Integer.parseInt(smallViews[1].split(":")[2]);
        int sharedLog = Integer.parseInt(smallViews[2].split(":")[2]);
        int sharedErrors = Integer.parseInt(smallViews[3].split(":")[2]);
        assertTrue(Math.abs(sharedHistory - sharedLog) <= 1 && sharedErrors >= 100,
                "equal parts, the lowest the rest: " + sharedHistory + ", " + sharedLog + ", " + sharedErrors + "\n" + all);
        assertEquals("cost+history,", step(all, "four-closed").group(1), all);

        // The settings beside the two: above them, the history the lowest list at the height it has.
        Matcher beside = step(all, "settings-beside");
        assertEquals("settings+cost+history,general", beside.group(1), all);
        assertEquals("true", beside.group(7));
        assertEquals("h", beside.group(8), "the history's height may be dragged");
        String[] withSettings = beside.group(11).split("/");
        assertTrue(withSettings[0].startsWith("settings-view:37:") && withSettings[1].startsWith("cost-view:") && withSettings[2].startsWith("view-history:"), all);
        near(Integer.parseInt(draggedViews[1].split(":")[2]), Integer.parseInt(withSettings[2].split(":")[2]), "the history as tall as it was:\n" + all);
        // img_50: the elements of the row unchecked but the icons, and saved: the settings close alone.
        assertEquals("cost+history,", step(all, "beside-saved").group(1), "the views beside the settings stay:\n" + all);
        assertEquals("settings+cost+history,general", step(all, "settings-beside-final").group(1), all);
        assertTrue(all.contains("PROBE stored=settings,cost,history|"), "all three are stored, the settings first, with commas:\n" + all);

        // The second start: both come back, at the same height.
        List<String> second = new ArrayList<>();
        assertEquals(0, runProbe("second", second), String.join("\n", second));
        String next = String.join("\n", second);
        System.out.println(next.lines().filter(l -> l.startsWith("PROBE")).reduce("", (a, b) -> a + b + "\n"));
        Matcher restored = step(next, "restored");
        assertEquals("settings+cost+history,general", restored.group(1), next);
        near(Integer.parseInt(beside.group(10)), Integer.parseInt(restored.group(10)), "at the height it had:\n" + next);
        assertEquals("cost,", step(next, "off").group(1), "switched off: the first of them stays:\n" + next);
        assertEquals("history,", step(next, "off-history").group(1), "and an icon now takes the place of what is shown:\n" + next);
        // img_53: with the row the cost icon and the gear alone, the cost control's figures have their room, 8 pixels at the right.
        assertEquals("cost,", step(next, "narrow-cost").group(1), next);
        int room = Integer.parseInt(next.lines().filter(l -> l.startsWith("PROBE narrow-room=")).findFirst().orElseThrow().substring("PROBE narrow-room=".length()));
        assertTrue(room >= 8, "nothing cut at the right edge: " + room + " pixels of room\n" + next);
        // img_45 to img_47: the cost control as wide after the history was shown as before.
        assertEquals(step(all, "cost").group(2), step(all, "cost-alone").group(2), "the same width before and after the history:\n" + all);
        assertTrue(!all.contains("PROBE failed") && !next.contains("PROBE failed"), all + "\n" + next);
    }

    /** Equal but for the pixels a frame of a fractional width rounds up to whole ones. */
    private static void near(int expected, int actual, String message) {
        assertTrue(Math.abs(expected - actual) <= 2, "expected about " + expected + " but was " + actual + ": " + message);
    }

    private static Matcher step(String all, String name) {
        String prefix = "PROBE " + name + "=";
        String line = all.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " in:\n" + all));
        Matcher matcher = STEP.matcher(line.substring(prefix.length()));
        assertTrue(matcher.matches(), line);
        return matcher;
    }

    private int runProbe(String which, List<String> output) throws Exception {
        List<String> modulePath = new ArrayList<>();
        List<String> classPath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            (Path.of(entry).getFileName().toString().startsWith("javafx-") ? modulePath : classPath).add(entry);
        }
        Path out = dir.resolve("probe-" + which + ".txt");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path", String.join(java.io.File.pathSeparator, modulePath),
                "--add-modules", "javafx.controls,javafx.web",
                "--enable-native-access=javafx.graphics,javafx.web",
                "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED",
                "-cp", String.join(java.io.File.pathSeparator, classPath),
                SeveralViewsProbe.class.getName(), dir.toString(), which)
                // The application keeps its files in the directory it is started in.
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        process.getOutputStream().close();
        boolean ended = process.waitFor(100, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        output.addAll(Files.readAllLines(out, StandardCharsets.UTF_8));
        assertTrue(ended, "the probe did not end:\n" + String.join("\n", output));
        return process.exitValue();
    }
}
