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
            // The figures of the cost control in their columns (since 0.83): the right edges of the amounts and of the limits of
            // the two rows, which are to be under one another, and the classes of the cells.
            onFx(() -> {
                System.out.println("PROBE cost-columns=" + page().getEngine().executeScript(
                        "(function () { var out = []; Array.prototype.forEach.call(document.querySelectorAll('#cost-rows .cost-row'), function (row) {"
                                + " var cells = Array.prototype.slice.call(row.children, 2); out.push(cells.map(function (c) {"
                                + " return c.className + ':' + Math.round(c.getBoundingClientRect().right); }).join(',')); }); return out.join('/'); })()"));
                System.out.flush();
            });
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
            // All three (four until 0.90, with the error log), and then a screen of 600 pixels, as the host would say for a small
            // one: the lists share it.
            step("four", "log-button");
            onFx(() -> page().getEngine().executeScript("window.screenRoom(600)"));
            step("four-small", null);
            onFx(() -> page().getEngine().executeScript("window.screenRoom(100000)"));
            step("four-closed", "log-button");
            // The settings beside the two, above them; the program stops with all three shown.
            step("settings-beside", "settings-button");
            // The fault seen in 0.83: every element of Main view unchecked but the icons, and Save: the settings close, the views beside them stay.
            onFx(() -> page().getEngine().executeScript(
                    "window.probeUnchecked = Array.from(document.querySelectorAll('#tab-main input[id^=set-show]')).filter(function (b) {"
                            + " return b.checked && !/Icon$/.test(b.id); });"
                            + " window.probeUnchecked.forEach(function (b) { b.checked = false; b.dispatchEvent(new Event('change')); });"));
            step("beside-saved", "settings-save");
            // And everything as it was, the settings beside the two again, as the program is to stop with them.
            step("beside-again", "settings-button");
            onFx(() -> page().getEngine().executeScript(
                    "window.probeUnchecked.forEach(function (b) { b.checked = true; b.dispatchEvent(new Event('change')); });"));
            step("beside-back", "settings-save");
            step("settings-beside-final", "settings-button");
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
            // The settings came back beside the views, on General: several views at once is unchecked there and saved.
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-severalViews').checked = false;"));
            step("off", "settings-save");
            step("off-history", "history-button");
            // Seen in 0.83, the figures cut: the row with no element but the cost icon, and the cost control: no figure is cut at the window's edge.
            step("narrow-settings", "settings-button");
            onFx(() -> page().getEngine().executeScript(
                    "Array.from(document.querySelectorAll('#tab-main input[id^=set-show]')).forEach(function (b) {"
                            + " b.checked = b.id === 'set-showCostIcon'; b.dispatchEvent(new Event('change')); });"));
            step("narrow-saved", "settings-save");
            step("narrow-cost", "cost-button");
            onFx(() -> {
                System.out.println("PROBE narrow-room=" + page().getEngine().executeScript(
                        "(function () { var most = 0; Array.prototype.forEach.call(document.querySelectorAll('#cost-rows .cost-row > *'), function (c) {"
                                + " most = Math.max(most, c.getBoundingClientRect().right); }); return Math.round(window.innerWidth - most); })()"));
                System.out.flush();
            });
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
                                    + " if (!v.hidden && !document.getElementById('panel').hidden) { out.push([Math.round(v.getBoundingClientRect().top), v.id + ':' + Math.round(v.getBoundingClientRect().top) + ':' + Math.round(v.getBoundingClientRect().height)]); } });"
                                    // In the order they are drawn, from the top down, which is not the order of the page for the settings.
                                    + " out.sort(function (a, b) { return a[0] - b[0]; }); return out.map(function (v) { return v[1]; }).join('/'); })()")
                    + " below:" + view.getEngine().executeScript(
                            "Math.round(window.innerHeight - document.getElementById('app').getBoundingClientRect().bottom)")
                    + " screen:" + Math.round(javafx.stage.Screen.getPrimary().getVisualBounds().getHeight())
                    + " end");
            System.out.flush();
        });
    }

    private static String field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|\\d+)").matcher(json);
        return m.find() ? m.group(1).replace("\"", "") : "";
    }

    private static Stage window() {
        return (Stage) Window.getWindows().stream().filter(window -> window.isShowing() && window instanceof Stage && !Locator.isLocator(window)).findFirst().orElseThrow();
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
