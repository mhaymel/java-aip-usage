package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Runs the application itself, window and all, against its fake backend, in the working directory it
 * is started in, with the title bar switched off in the settings file. It presses the buttons of the
 * page by running the page's own script and reports each time whether the window has a title bar, how
 * many windows there are and what the page asks for. It is started in debug mode with the fake backend on, so it
 * also reports the red border and the debug button, opens the debug settings, and switches the fake backend off and
 * on again while the program runs. Then it closes the window, which must end the program.
 *
 * <p>Run by {@link TitleBarWindowTest}, only when asked for. Each line it prints begins {@code PROBE}.
 */
public final class UsageAppProbe {

    public static void main(String[] args) throws Exception {
        // The fake backend is a debug setting now, and counts only in debug mode.
        Files.writeString(Path.of("settings.json"), "{\"showTitleBar\": false, \"fakeBackend\": true}");
        // The probe starts the window class itself, which logs to the console only, so the log panel gets a file of its own.
        Files.writeString(Path.of("java-aip-usage.log"), String.join("\n",
                "2026-10-11 13:24:01 INFO    [A] one",
                "2026-10-11 13:24:02 WARNING [A] two",
                "\tat a.B.c(B.java:1)",
                "2026-10-11 13:24:03 SEVERE  [A] three",
                "2026-10-11 13:24:04 INFO    [A] four") + "\n");
        Thread driver = new Thread(UsageAppProbe::drive, "probe-driver");
        driver.setDaemon(true);
        driver.start();
        // An address nothing listens at, for when the fake backend is switched off: nothing real is asked, and no login is needed.
        Application.launch(UsageApp.class, "--debug", "--anthropic-url=http://127.0.0.1:9");
        System.out.println("PROBE the program ended after the window was closed");
    }

    private static void drive() {
        try {
            Thread.sleep(5000);
            step("start", null);
            step("settings", "settings-button");
            step("settings-left", "settings-cancel");
            step("history", "history-button");
            step("history-closed", "history-button");
            step("log", "log-button");
            // The two choices of the log panel: the date on, then warnings and worse only.
            onFx(() -> page().getEngine().executeScript(
                    "var d = document.getElementById('log-date'); d.checked = true; d.dispatchEvent(new Event('change'));"));
            step("log-date", null);
            onFx(() -> page().getEngine().executeScript(
                    "var l = document.getElementById('log-level'); l.value = 'warning'; l.dispatchEvent(new Event('change'));"));
            step("log-warning", null);
            onFx(() -> page().getEngine().executeScript(
                    "var l = document.getElementById('log-level'); l.value = 'info'; l.dispatchEvent(new Event('change'));"
                            + " var d = document.getElementById('log-date'); d.checked = false; d.dispatchEvent(new Event('change'));"));
            step("log-back", null);
            step("errors", "errors-button");
            step("errors-closed", "errors-button");
            // The settings with every element of the row hidden: the view is far wider than the row then.
            step("settings-again", "settings-button");
            onFx(() -> page().getEngine().executeScript(
                    "Array.prototype.forEach.call(document.querySelectorAll(\"#settings-form input[id^='set-show']\"), function (box) { if (box.checked && box.id !== 'set-showTitleBar' && box.id !== 'set-showCurrency') { box.click(); } });"));
            step("settings-narrow", null);
            step("settings-narrow-left", "settings-cancel");
            // Debug mode: the debug settings, and the fake backend switched off and on again while the program runs.
            step("debug", "debug-button");
            onFx(() -> page().getEngine().executeScript(
                    "document.getElementById('debug-fakeBackend').checked = false; document.getElementById('debug-fakeToken').checked = true;"));
            step("fake-off", "debug-apply");
            Thread.sleep(2500);
            step("fake-off-settled", null);
            step("debug-again", "debug-button");
            onFx(() -> page().getEngine().executeScript("document.getElementById('debug-fakeBackend').checked = true;"));
            step("fake-on", "debug-button");
            Thread.sleep(2500);
            step("fake-on-settled", null);
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
        Thread.sleep(1500);
        onFx(() -> {
            List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
            System.out.println("PROBE " + name + "=titleBar:" + StageSwap.hasTitleBar(window())
                    + " windows:" + showing.size()
                    + " size:" + page().getEngine().executeScript("window.contentSize()")
                    + " content:" + Math.round(window().getScene().getWidth()) + "x" + Math.round(window().getScene().getHeight())
                    + " app:[" + page().getEngine().executeScript("document.getElementById('app').className.trim()") + "]"
                    + " border:" + page().getEngine().executeScript("getComputedStyle(document.getElementById('app')).borderTopWidth")
                    + " debugButton:" + page().getEngine().executeScript("!document.getElementById('debug-button').hidden")
                    + " used:[" + page().getEngine().executeScript(
                            "document.getElementById('spend').hidden ? '' : document.getElementById('used').textContent") + "]"
                    + " overflow:" + page().getEngine().executeScript(
                            "Math.max(0, Math.ceil(document.getElementById('app').getBoundingClientRect().right - window.innerWidth))")
                    // How far the right edge of the app, where its border is, is from the right edge of the window, and how far
                    // the view that is shown sticks out of the app's border.
                    + " gap:" + page().getEngine().executeScript(
                            "Math.round(window.innerWidth - document.getElementById('app').getBoundingClientRect().right)")
                    + " outside:" + page().getEngine().executeScript(
                            "(function () { var a = document.getElementById('app').getBoundingClientRect(), out = 0;"
                                    + " ['settings-form', 'debug-form'].forEach(function (id) { var f = document.getElementById(id);"
                                    + " if (f.offsetParent !== null) { out = Math.max(out, Math.ceil(f.getBoundingClientRect().right - (a.right - 3))); } });"
                                    + " return out; })()")
                    // The log panel: whether its line of choices is shown, how many lines it has, how many of them are
                    // information, and how the newest begins.
                    + " logControls:" + page().getEngine().executeScript("!document.getElementById('log-controls').hidden")
                    + " logLines:" + page().getEngine().executeScript(
                            "(function () { var rows = document.querySelectorAll('#panel-lines .row'), info = 0;"
                                    + " Array.prototype.forEach.call(rows, function (r) { if (/ INFO /.test(r.textContent)) { info++; } });"
                                    + " return rows.length + '/' + info + '/' + (rows.length ? rows[0].textContent.slice(0, 11) : ''); })()")
                    + " end");
            System.out.flush();
        });
    }

    private static Stage window() {
        return (Stage) Window.getWindows().stream().filter(Window::isShowing).findFirst().orElseThrow();
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

    private UsageAppProbe() {
    }
}
