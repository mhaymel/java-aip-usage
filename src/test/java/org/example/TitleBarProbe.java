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

import java.nio.file.Path;
import java.time.Instant;

/**
 * Opens a real window with the application's page in it and takes the title bar away and gives it
 * back, the way the application does ({@link StageSwap}). It reports, each time, whether the page is
 * still the one that was loaded (a mark set in it before the first exchange is still there, and it
 * was not loaded again), where the content is on the screen, and how many windows there are. Then it
 * closes the window, which must end the program.
 *
 * <p>Run by {@link TitleBarWindowTest}, only when asked for. Each line it prints begins {@code PROBE}.
 */
public final class TitleBarProbe {

    private static AppRuntime runtime;

    private static int loads;

    public static void main(String[] args) throws Exception {
        UsageSnapshot reading = new UsageSnapshot(Instant.now(), null);
        runtime = AppRuntime.start(AppFiles.in(Path.of(args[0])), LaunchOptions.none(), () -> reading);
        try {
            Application.launch(Probe.class, args);
            System.out.println("PROBE the program ended after the window was closed");
        } finally {
            runtime.close();
        }
    }

    public static final class Probe extends Application {

        @Override
        public void start(Stage main) {
            WebView view = new WebView();
            view.getEngine().getLoadWorker().stateProperty().addListener((observable, before, now) -> {
                if (now == javafx.concurrent.Worker.State.SUCCEEDED) {
                    loads++;
                }
            });
            main.setTitle("title bar probe");
            main.setScene(new Scene(view, 500, 120));
            main.setX(300);
            main.setY(300);
            main.show();
            view.getEngine().load(runtime.baseUri().toString());
            after(1.5, () -> steps(view, main));
        }
    }

    private static void steps(WebView view, Stage first) {
        WebEngine page = view.getEngine();
        page.executeScript("window.__probeMark = 'kept'");
        report("with", first, view);
        Stage bare = StageSwap.replace(first, false);
        after(1.0, () -> {
            report("without", bare, view);
            System.out.println("PROBE first-still-showing=" + first.isShowing());
            Stage again = StageSwap.replace(bare, true);
            after(1.0, () -> {
                report("with-again", again, view);
                System.out.flush();
                again.close();
            });
        });
    }

    private static void report(String name, Stage stage, WebView view) {
        Scene scene = stage.getScene();
        long windows = Window.getWindows().stream().filter(w -> w.isShowing() && !Locator.isLocator(w)).count();
        System.out.println("PROBE " + name + "=titleBar:" + StageSwap.hasTitleBar(stage)
                + " mark:" + view.getEngine().executeScript("window.__probeMark")
                + " loads:" + loads
                + " size:" + view.getEngine().executeScript("window.contentSize()")
                + " contentAt:" + Math.round(stage.getX() + scene.getX()) + "," + Math.round(stage.getY() + scene.getY())
                + " content:" + Math.round(scene.getWidth()) + "x" + Math.round(scene.getHeight())
                + " frameTop:" + Math.round(scene.getY())
                + " sameScene:" + (scene == view.getScene())
                + " windows:" + windows);
    }

    private static void after(double seconds, Runnable action) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private TitleBarProbe() {
    }
}
