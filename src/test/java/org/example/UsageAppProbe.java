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
            step("errors", "errors-button");
            step("errors-closed", "errors-button");
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
                            "Math.max(0, Math.ceil(document.getElementById('app').getBoundingClientRect().right - window.innerWidth))"));
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
