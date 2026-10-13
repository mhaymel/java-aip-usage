package org.example;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.settings.LaunchOptions;
import org.example.usage.UsageSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Opens a real window: the application's page in a real web view, with a real {@link AppRuntime}
 * and a stand-in for the network. It presses the history button and the log button by running the
 * page's own script, reports how tall the page needs to be and what the panel shows each time, hides
 * the panel again, and then closes the main window, which must end the program.
 *
 * <p>Started the way the application is, through {@link Application#launch}, so that "the program
 * ends" means the same here: the call returns and the process exits. If closing the main window
 * left it running, this process would never end, and {@link PanelWindowTest} would say so.
 *
 * <p>Run by that test, only when asked for, since it puts a window on the screen. Each line it prints
 * begins {@code PROBE}. The one argument is a directory to keep its files in.
 */
public final class PanelProbe {

    private static AppRuntime runtime;

    public static void main(String[] args) throws Exception {
        AppFiles files = AppFiles.in(Path.of(args[0]));
        // More lines than the panel shows at once, so that what is at its top matters.
        List<String> logLines = new java.util.ArrayList<>();
        // In the form of the log, so that each is an entry of its own: the panel puts entries, not lines, newest first.
        logLines.add(logLine(1, "one"));
        for (int i = 2; i <= 60; i++) {
            logLines.add(logLine(i, i == 60 ? "last" : String.valueOf(i)));
        }
        Files.write(files.log(), logLines);
        Files.write(files.history(), List.of(
                "datetime,used,limit",
                "2026-10-08 14:24:53,1.00,2.00",
                "2026-10-08 14:26:53,3.00,4.00",
                "2026-10-08 14:25:53,2.00,2.00"));
        // A reading with no usage has no amounts, so the stand-in leaves the history as written above.
        UsageSnapshot reading = new UsageSnapshot(Instant.now(), null);
        runtime = AppRuntime.start(files, LaunchOptions.none(), () -> reading);
        try {
            Application.launch(Probe.class, args);
            System.out.println("PROBE the program ended after the main window was closed");
        } finally {
            runtime.close();
        }
    }

    private static String logLine(int second, String name) {
        return String.format("2026-10-08 19:00:%02d INFO    [Probe] probe log line %s", second % 60, name);
    }

    /** The window side of the probe; kept apart from {@code main}, as in the application itself. */
    public static final class Probe extends Application {

        @Override
        public void start(Stage main) {
            WebView view = new WebView();
            main.setScene(new Scene(view, 500, 120));
            main.show();
            view.getEngine().load(runtime.baseUri().toString());
            // Time for the page to load and attach its buttons.
            after(1.5, () -> steps(view.getEngine(), main));
        }
    }

    /** What the page reports as its size: {@code width,height,resizable}. */
    private static String size(WebEngine page) {
        return String.valueOf(page.executeScript("window.contentSize()"));
    }

    private static String lines(WebEngine page) {
        return String.valueOf(page.executeScript(
                "Array.from(document.querySelectorAll('#panel-lines .row:not(.head)')).map(function (row) {"
                        + " return Array.from(row.children).map(function (cell) { return cell.textContent; }).join(' '); }).join('|')"));
    }

    /** The text of the row at the top edge of the panel's box, as the person sees it. */
    private static String topRow() {
        return "(function () { var box = document.getElementById('panel-lines').getBoundingClientRect();"
                + " var at = document.elementFromPoint(box.left + 20, box.top + 3);"
                + " return at ? at.textContent : 'nothing'; })()";
    }

    /**
     * Which of the typefaces the lists name the window has, and which of them the list is drawn in. A page cannot ask for
     * the name of the typeface it was given, so a text is measured: set in a named typeface with another behind it, it is
     * as wide as in that other alone when the named one is not there. The list is drawn in the one whose width it has.
     */
    private static String typefaces() {
        return "(function () { var names = ['ui-monospace', 'Cascadia Mono', 'SF Mono', 'SFMono-Regular', 'Menlo', 'Consolas'];"
                + " var s = document.createElement('span'); s.style.cssText = 'position:absolute;left:-9999px;font-size:72px;white-space:nowrap';"
                + " s.textContent = 'mmmmiiiillllWW00.,'; document.body.appendChild(s);"
                + " function w(f) { s.style.fontFamily = f; return s.getBoundingClientRect().width; }"
                + " var bases = ['monospace', 'serif', 'sans-serif']; var plain = bases.map(w);"
                + " function q(n) { return n === 'ui-monospace' ? n : '\"' + n + '\"'; }"
                + " var has = names.filter(function (n) { return bases.some(function (b, i) { return w(q(n) + ',' + b) !== plain[i]; }); });"
                + " var list = w(getComputedStyle(document.getElementById('panel')).fontFamily);"
                + " var used = has.filter(function (n) { return w(q(n) + ',serif') === list; })[0] || (list === plain[0] ? 'monospace' : 'another');"
                + " document.body.removeChild(s); return 'has:[' + has.join('|') + '] used:' + used; })()";
    }

    private static void click(WebEngine page, String button) {
        page.executeScript("document.getElementById('" + button + "').click()");
    }

    /** Closed, history, log in its place, closed again; says the page size and the lines each time. */
    private static void steps(WebEngine page, Stage main) {
        System.out.println("PROBE closed=" + size(page));
        click(page, "history-button");
        after(1.5, () -> {
            System.out.println("PROBE history=" + size(page));
            System.out.println("PROBE history-lines=" + lines(page));
            System.out.println("PROBE list-typefaces=" + page.executeScript(typefaces()));
            click(page, "log-button");
            after(1.5, () -> {
                System.out.println("PROBE log=" + size(page));
                // The first lines only: the test reads the output when the probe has ended, and a pipe holds little.
                String shown = lines(page);
                System.out.println("PROBE log-lines=" + shown.substring(0, Math.min(200, shown.length())));
                System.out.println("PROBE log-scroll=" + page.executeScript("document.getElementById('panel-lines').scrollTop"));
                System.out.println("PROBE log-top=" + page.executeScript(topRow()));
                click(page, "log-button");
                after(0.5, () -> {
                    System.out.println("PROBE closed-again=" + size(page));
                    settings(page, main);
                });
            });
        });
    }

    /**
     * The settings: how wide the page says the window must be for them, and, with the window made that wide as the
     * application would make it, whether any text wraps, whether the two buttons are in one row and whether anything
     * sticks out at the right.
     */
    private static void settings(WebEngine page, Stage main) {
        click(page, "settings-button");
        after(1.5, () -> {
            // The tab of the row's elements, whose lines are the ones measured below; the settings open on General.
            click(page, "tabbtn-main");
            // Every element of the row off, as a person would click them, so that the row is at its narrowest: the gear alone.
            page.executeScript(
                    "Array.from(document.querySelectorAll('#settings-form input[id^=\"set-show\"]')).forEach(function (box) {"
                            + " box.checked = false; box.dispatchEvent(new Event('change')); })");
            // The row's own width: while a panel is open the row is stretched to the window, so it is let go of for the measuring.
            System.out.println("PROBE settings-row=" + page.executeScript(
                    "(function () { var t = document.getElementById('top'); t.style.width = 'max-content';"
                            + " var w = Math.ceil(t.getBoundingClientRect().width); t.style.width = ''; return w; })()"));
            String size = size(page);
            System.out.println("PROBE settings=" + size);
            main.setWidth(main.getWidth() - main.getScene().getWidth() + Integer.parseInt(size.split(",")[0]));
            after(0.7, () -> {
                System.out.println("PROBE settings-fitted=" + size(page));
                System.out.println("PROBE settings-lines=" + page.executeScript(
                        "(function () { var one = document.getElementById('set-showPercentage').parentElement.getBoundingClientRect().height;"
                                + " return Array.from(document.querySelectorAll('#settings-view label, #settings-view .line'))"
                                + ".filter(function (line) { return line.getBoundingClientRect().height > one * 1.5; }).length; })()"));
                System.out.println("PROBE settings-buttons=" + page.executeScript(
                        "['settings-save', 'settings-cancel'].map(function (id) {"
                                + " return Math.round(document.getElementById(id).getBoundingClientRect().top); }).join(',')"));
                System.out.println("PROBE settings-overflow=" + page.executeScript(
                        "Math.max(0, Math.ceil(Math.max.apply(null, Array.from(document.querySelectorAll('#settings-view label, #settings-view .line,"
                                + " #settings-view button:not(.tab), #settings-view fieldset')).map(function (e) { return e.getBoundingClientRect().right; }))"
                                + " - window.innerWidth))"));
                click(page, "settings-cancel");
                after(0.5, () -> {
                    System.out.println("PROBE settings-closed=" + size(page));
                    settingsAfterAWideWindow(page, main);
                });
            });
        });
    }

    /** Gives the window the width of content the page asks for, as the application would. */
    private static void fitWidth(Stage main, int content) {
        main.setWidth(main.getWidth() - main.getScene().getWidth() + content);
    }

    /**
     * The settings opened in a window as narrow as they ask for, and again in one as wide as the log leaves it: what the
     * page asks for must be the same, since it is the settings' own width and not the window's.
     */
    private static void settingsAfterAWideWindow(WebEngine page, Stage main) {
        click(page, "settings-button");
        after(1.5, () -> {
            String narrow = size(page);
            System.out.println("PROBE settings-narrow=" + narrow);
            click(page, "settings-cancel");
            fitWidth(main, 1200);
            after(0.7, () -> {
                click(page, "settings-button");
                after(1.5, () -> {
                    System.out.println("PROBE settings-after-wide=" + size(page));
                    click(page, "settings-cancel");
                    after(0.5, () -> historyWithTheTimeChangeLast(page, main));
                });
            });
        });
    }

    /**
     * The history with both change columns and without the currency, so that its last column is the time change: with
     * the window as wide as the page asks for, nothing of the table may stick out at the right of the list.
     */
    private static void historyWithTheTimeChangeLast(WebEngine page, Stage main) {
        page.executeScript(
                "fetch('/api/settings').then(function (r) { return r.json(); }).then(function (b) { var s = b.settings;"
                        + " s.historyDeltaUsed = true; s.historyDeltaTime = true; s.historyCurrent = false;"
                        + " return fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(s) }); })");
        after(1.0, () -> {
            click(page, "history-button");
            after(1.5, () -> {
                String size = size(page);
                System.out.println("PROBE history-columns=" + size);
                fitWidth(main, Integer.parseInt(size.split(",")[0]));
                after(0.7, () -> {
                    // What each column is, as its cells are marked: the titles have a letter the console may not have.
                    System.out.println("PROBE history-kinds=" + page.executeScript(
                            "Array.from(document.querySelectorAll('#panel-lines .head > *')).map(function (cell) { return cell.className; }).join('|')"));
                    System.out.println("PROBE history-overflow=" + page.executeScript(
                            "(function () { var box = document.getElementById('panel-lines'); return Math.max(0, box.scrollWidth - box.clientWidth); })()"));
                    System.out.println("PROBE history-cell-overflow=" + page.executeScript(
                            "Math.max.apply(null, Array.from(document.querySelectorAll('#panel-lines .row > *')).map(function (cell) {"
                                    + " return Math.max(0, cell.scrollWidth - cell.clientWidth); }))"));
                    // A list that lacks room at the width the page asked for, as where its scrollbar is wider than the one
                    // that was measured: here its lines are pushed 24 pixels to the right. The page is to ask for that much more.
                    page.executeScript("document.getElementById('panel-lines').style.paddingLeft = '24px'");
                    after(0.3, () -> {
                        System.out.println("PROBE history-lacking=" + page.executeScript(
                                "(function () { var box = document.getElementById('panel-lines'); return Math.max(0, box.scrollWidth - box.clientWidth); })()"));
                        String wider = size(page);
                        System.out.println("PROBE history-wider=" + wider);
                        fitWidth(main, Integer.parseInt(wider.split(",")[0]));
                        after(0.7, () -> {
                            System.out.println("PROBE history-settled=" + size(page));
                            System.out.println("PROBE history-overflow-after=" + page.executeScript(
                                    "(function () { var box = document.getElementById('panel-lines'); return Math.max(0, box.scrollWidth - box.clientWidth); })()"));
                            click(page, "history-button");
                            after(0.5, () -> report(main));
                        });
                    });
                });
            });
        });
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private static void report(Stage main) {
        long others = Window.getWindows().stream().filter(w -> w instanceof Stage && w != main && w.isShowing()).count();
        System.out.println("PROBE other-windows=" + others);
        System.out.flush();
        main.close();
    }

    private PanelProbe() {
    }
}
