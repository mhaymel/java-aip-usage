package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Starts the application with several views at once switched on, and opens the cost control and the history together, drags
 * the window taller, adds the log, opens and leaves the settings, and closes the views one by one. Started a second time
 * with {@code second}, it says what the window came back as, switches several views at once off, and says what is left.
 *
 * <p>Run by {@link SeveralViewsWindowTest}, only when asked for. Each line it prints begins {@code PROBE}.
 */
public final class SeveralViewsProbe {

    private static boolean second;

    public static void main(String[] args) throws Exception {
        second = args.length > 1 && args[1].equals("second");
        if (!second) {
            // The fake backend, whose amount does not grow; the title bar off, so that it is there only while something can be dragged.
            Files.writeString(Path.of("settings.json"), "{\"showTitleBar\": false, \"fakeBackend\": true, \"fakeIncreaseCents\": 0,"
                    + " \"severalViews\": true, \"restoreWindow\": true}");
        }
        Files.writeString(Path.of("java-aip-usage.log"), "2026-10-11 13:24:01 INFO    [A] one\n2026-10-11 13:24:02 INFO    [A] two\n");
        Thread driver = new Thread(second ? SeveralViewsProbe::driveSecond : SeveralViewsProbe::drive, "probe-driver");
        driver.setDaemon(true);
        driver.start();
        Application.launch(UsageApp.class, "--debug", "--anthropic-url=http://127.0.0.1:9");
        // What the program stored of the window when it stopped.
        String file = Files.readString(Path.of("settings.json"));
        System.out.println("PROBE stored=" + field(file, "lastPanel") + "|" + field(file, "historyHeight") + "|" + field(file, "windowHeight"));
        System.out.println("PROBE ended");
    }

    private static void drive() {
        try {
            Thread.sleep(5000);
            step("start", null);
            step("cost", "cost-button");
            step("cost-history", "history-button");
            // The window is dragged 150 pixels taller, as by its lower edge, and left there a while.
            onFx(() -> window().setHeight(window().getHeight() + 150));
            Thread.sleep(1500);
            step("dragged", null);
            Thread.sleep(2500);
            step("dragged-settled", null);
            step("log-history-cost", "log-button");
            step("settings", "settings-button");
            step("settings-left", "settings-cancel");
            step("history-cost", "log-button");
            step("cost-alone", "history-button");
            step("history-again", "history-button");
            onFx(() -> window().close());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void driveSecond() {
        try {
            Thread.sleep(6000);
            step("restored", null);
            // Switched off while two views are shown: the first of them, in the order of the icons, stays.
            step("off-settings", "settings-button");
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-severalViews').checked = false;"));
            step("off", "settings-save");
            step("off-cost", "cost-button");
            onFx(() -> window().close());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Presses the button, if one is named, waits for the window to follow, and says what there is. */
    private static void step(String name, String button) throws InterruptedException {
        if (button != null) {
            onFx(() -> page().getEngine().executeScript("document.getElementById('" + button + "').click()"));
        }
        Thread.sleep(2000);
        onFx(() -> {
            WebView view = page();
            System.out.println("PROBE " + name + "=view:" + view.getEngine().executeScript("window.currentView()")
                    + " size:" + view.getEngine().executeScript("window.contentSize()")
                    + " titleBar:" + StageSwap.hasTitleBar(window())
                    + " drag:" + (window().getMaxWidth() > window().getMinWidth() + 1 ? "w" : "") + (window().getMaxHeight() > window().getMinHeight() + 1 ? "h" : "") + "."
                    + " content:" + Math.round(window().getScene().getWidth()) + "x" + Math.round(window().getScene().getHeight())
                    // The views that are shown, from the top down, each with its height.
                    + " views:" + view.getEngine().executeScript(
                            "(function () { var out = []; Array.prototype.forEach.call(document.querySelectorAll('#panel > .view, #panel > #cost-view, #panel > #settings-view'), function (v) {"
                                    + " if (!v.hidden && !document.getElementById('panel').hidden) { out.push(v.id + ':' + Math.round(v.getBoundingClientRect().top) + ':' + Math.round(v.getBoundingClientRect().height)); } });"
                                    + " return out.join('/'); })()")
                    + " below:" + view.getEngine().executeScript(
                            "Math.round(window.innerHeight - document.getElementById('app').getBoundingClientRect().bottom)")
                    + " end");
            System.out.flush();
        });
    }

    private static String field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|\\d+)").matcher(json);
        return m.find() ? m.group(1).replace("\"", "") : "";
    }

    private static Stage window() {
        return (Stage) Window.getWindows().stream().filter(window -> window.isShowing() && window instanceof Stage).findFirst().orElseThrow();
    }

    private static WebView page() {
        return (WebView) window().getScene().getRoot();
    }

    private static void onFx(Runnable action) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                System.out.println("PROBE failed: " + e);
            } finally {
                done.countDown();
            }
        });
        done.await();
    }

    private SeveralViewsProbe() {
    }
}
