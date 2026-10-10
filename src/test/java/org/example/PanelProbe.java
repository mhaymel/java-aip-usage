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
        logLines.add("probe log line one");
        for (int i = 2; i <= 60; i++) {
            logLines.add("probe log line " + (i == 60 ? "last" : String.valueOf(i)));
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
            click(page, "log-button");
            after(1.5, () -> {
                System.out.println("PROBE log=" + size(page));
                System.out.println("PROBE log-lines=" + lines(page));
                System.out.println("PROBE log-scroll=" + page.executeScript("document.getElementById('panel-lines').scrollTop"));
                System.out.println("PROBE log-top=" + page.executeScript(topRow()));
                click(page, "log-button");
                after(0.5, () -> {
                    System.out.println("PROBE closed-again=" + size(page));
                    report(main);
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
