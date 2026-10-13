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
        // The fake backend is a debug setting now, and counts only in debug mode. Its amount does not grow here, so that
        // what the row shows can be said in advance: 186.02 of 1,000.00.
        Files.writeString(Path.of("settings.json"), "{\"showTitleBar\": false, \"fakeBackend\": true, \"fakeIncreaseCents\": 0}");
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
            // The mouse rests on the amount used: its tooltip comes, and is of the size the window's style sheet gives it.
            hover("tooltip", "used");
            // No title bar: the window is moved by a drag that begins on a figure, and not by one that begins on a button.
            drag("drag-figure", "time");
            drag("drag-gap", null);
            drag("drag-button", "settings-button");
            drag("drag-refresh", "refresh");
            step("settings", "settings-button");
            // The Request group is in the General tab.
            step("settings-general", "tabbtn-general");
            // Automatic is checked: the field of the interval is disabled at once and shows the interval in force.
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-autoInterval').click()"));
            step("settings-auto", null);
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-autoInterval').click()"));
            step("settings-manual", null);
            // Cost control, a tab of its own: a monthly limit is typed, and the daily one is worked out by the program and shown, not to be typed in.
            step("settings-cost", "tabbtn-cost");
            onFx(() -> page().getEngine().executeScript(
                    "var m = document.getElementById('set-costMonthlyLimit'); m.value = '440'; m.dispatchEvent(new Event('change'));"));
            step("cost", null);
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-costDailyAutomatic').click()"));
            step("cost-typed", null);
            onFx(() -> page().getEngine().executeScript(
                    "document.getElementById('set-costDailyAutomatic').click();"
                            + " var m = document.getElementById('set-costMonthlyLimit'); m.value = ''; m.dispatchEvent(new Event('change'));"));
            step("cost-none", null);
            // The About tab, in the place of the others, and the first tab again.
            step("about", "tabbtn-about");
            copyPath("about-copy");
            step("about-back", "tabbtn-main");
            step("settings-left", "settings-cancel");
            step("history", "history-button");
            // The zoom: eight presses make the text of the list as large as it gets, and the window as wide as the table needs then.
            onFx(() -> page().getEngine().executeScript("for (var i = 0; i < 8; i++) { document.getElementById('zoom-in').click(); }"));
            step("history-larger", null);
            onFx(() -> page().getEngine().executeScript("for (var i = 0; i < 8; i++) { document.getElementById('zoom-out').click(); }"));
            step("history-usual", null);
            // A panel is shown, so the title bar is there: a drag inside the window moves nothing.
            drag("drag-with-title-bar", "time");
            // And it selects nothing: not the row it began in, not the lines of the list it went across, and nor does selecting everything.
            select("select-history");
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
            // The settings open on General, the first tab: the button of Main view is measured with its own tab shown.
            step("settings-open", "settings-button");
            step("settings-again", "tabbtn-main");
            // By the button of Main view: not every element is on, so the first press switches all on, and the second all off.
            step("settings-all", "set-all");
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-all').click()"));
            step("settings-narrow", null);
            step("settings-narrow-left", "settings-cancel");
            // Debug mode: the Debug tab of the settings, and the fake backend switched off and on again while the program runs.
            debugTab("debug");
            // How the fake backend answers is shaped here. Another starting amount: the amount begins there, and the row shows it.
            onFx(() -> page().getEngine().executeScript("document.getElementById('debug-fakeStartAmount').value = '50.00';"));
            step("fake-start-applied", "settings-save");
            Thread.sleep(2000);
            step("fake-start", null);
            // Refusing within an hour: the request that follows the change comes too soon, and is refused with a 429.
            debugTab("debug-refuse");
            onFx(() -> page().getEngine().executeScript("document.getElementById('debug-fakeRefuseSeconds').value = '3600';"));
            step("fake-refuse-applied", "settings-save");
            Thread.sleep(2000);
            step("fake-refuse", null);
            debugTab("debug-seat-open");
            // The fake backend answers as for a seat-based account: gray boxes, and the line that says so only while the
            // mouse is on one of them. It refuses nothing again.
            onFx(() -> page().getEngine().executeScript(
                    "document.getElementById('debug-fakeRefuseSeconds').value = '0'; document.getElementById('debug-fakeAccount').value = 'seat';"));
            step("seat-applied", "settings-save");
            Thread.sleep(2500);
            step("seat", null);
            point("seat-hover", "used");
            point("seat-away", "refresh");
            debugTab("debug-seat");
            onFx(() -> page().getEngine().executeScript("document.getElementById('debug-fakeAccount').value = 'usage';"));
            onFx(() -> page().getEngine().executeScript(
                    "document.getElementById('debug-fakeBackend').checked = false; document.getElementById('debug-fakeToken').checked = true;"));
            step("fake-off", "settings-save");
            Thread.sleep(2500);
            step("fake-off-settled", null);
            // A failed refresh has a message line. With every element of the row hidden the row is the gear alone: the message must not stand in a column of that width, with the settings shown or with no panel.
            step("message-settings", "settings-button");
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-all').click(); document.getElementById('set-all').click();"));
            step("message-narrow-settings", null);
            step("message-narrow-row", "settings-save");
            step("message-settings-again", "settings-button");
            onFx(() -> page().getEngine().executeScript("document.getElementById('set-all').click();"));
            step("message-wide-row", "settings-save");
            debugTab("debug-again");
            onFx(() -> page().getEngine().executeScript("document.getElementById('debug-fakeBackend').checked = true;"));
            step("fake-on", "settings-save");
            Thread.sleep(2500);
            step("fake-on-settled", null);
            onFx(() -> window().close());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opens the settings, shows their Debug tab, and says what there is. */
    private static void debugTab(String name) throws InterruptedException {
        step(name + "-settings", "settings-button");
        step(name, "tabbtn-debug");
    }

    /** Presses the button, if one is named, waits for the window to follow, and says what there is. */
    private static void step(String name, String button) throws InterruptedException {
        if (button != null) {
            onFx(() -> page().getEngine().executeScript("document.getElementById('" + button + "').click()"));
        }
        Thread.sleep(1500);
        // The title bar is switched off in this probe, so the window has it exactly while the page shows a panel. On a
        // machine that is busy the exchange of the window can take longer than the wait above: it is given some more.
        for (int waited = 0; waited < 10; waited++) {
            boolean[] settled = new boolean[1];
            onFx(() -> settled[0] = StageSwap.hasTitleBar(window())
                    == Boolean.TRUE.equals(page().getEngine().executeScript("!document.getElementById('panel').hidden")));
            if (settled[0]) {
                break;
            }
            Thread.sleep(500);
        }
        if (button != null) {
            // And a moment for the size, which the window is given after the exchange.
            Thread.sleep(300);
        }
        onFx(() -> {
            List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
            System.out.println("PROBE " + name + "=titleBar:" + StageSwap.hasTitleBar(window())
                    + " windows:" + showing.size()
                    + " size:" + page().getEngine().executeScript("window.contentSize()")
                    + " content:" + Math.round(window().getScene().getWidth()) + "x" + Math.round(window().getScene().getHeight())
                    + " app:[" + page().getEngine().executeScript("document.getElementById('app').className.trim()") + "]"
                    + " border:" + page().getEngine().executeScript("getComputedStyle(document.getElementById('app')).borderTopWidth")
                    + " debugTab:" + page().getEngine().executeScript("!document.getElementById('tabbtn-debug').hidden")
                    + " used:[" + page().getEngine().executeScript(
                            "document.getElementById('spend').hidden ? '' : document.getElementById('used').textContent.replace(/\\u00a0/g, '')") + "]"
                    + " overflow:" + page().getEngine().executeScript(
                            "Math.max(0, Math.ceil(document.getElementById('app').getBoundingClientRect().right - window.innerWidth))")
                    // How far the right edge of the app, where its border is, is from the right edge of the window, and how far
                    // the view that is shown sticks out of the app's border.
                    + " gap:" + page().getEngine().executeScript(
                            "Math.round(window.innerWidth - document.getElementById('app').getBoundingClientRect().right)")
                    // How far the bottom edge of the app is from that of the window.
                    + " below:" + page().getEngine().executeScript(
                            "Math.round(window.innerHeight - document.getElementById('app').getBoundingClientRect().bottom)")
                    + " outside:" + page().getEngine().executeScript(
                            "(function () { var a = document.getElementById('app').getBoundingClientRect(), out = 0;"
                                    + " ['settings-form', 'debug-form'].forEach(function (id) { var f = document.getElementById(id);"
                                    + " if (f.offsetParent !== null) { out = Math.max(out, Math.ceil(f.getBoundingClientRect().right - (a.right - 3))); } });"
                                    + " return out; })()")
                    // The log panel: whether its line of choices is shown, how many lines it has, how many of them are
                    // information, and how the newest begins.
                    + " logControls:" + page().getEngine().executeScript("!document.getElementById('log-choices').hidden")
                    + " logLines:" + page().getEngine().executeScript(
                            "(function () { var rows = document.querySelectorAll('#panel-lines .row'), info = 0;"
                                    + " Array.prototype.forEach.call(rows, function (r) { if (/ INFO /.test(r.textContent)) { info++; } });"
                                    + " return rows.length + '/' + info + '/' + (rows.length ? rows[0].textContent.slice(0, 11) : ''); })()")
                    // The amount used: its classes, the width of its border and its colour, its tooltip, and what the row has as text.
                    + " usedBox:[" + page().getEngine().executeScript(
                            "(function () { var u = document.getElementById('used'), c = getComputedStyle(u);"
                                    + " return u.className + '|' + c.borderTopWidth + '|' + c.borderTopColor + '|' + u.title + '|'"
                                    + " + document.querySelector('.strip').textContent.replace(/\\u00a0/g, '').replace(/\\s+/g, ' ').trim(); })()") + "]"
                    // The button of Main view: its size, and how far its middle is from the middle of the heading it is in.
                    + " allButton:" + page().getEngine().executeScript(
                            "(function () { var b = document.getElementById('set-all').getBoundingClientRect(), l = document.getElementById('set-all').parentNode.getBoundingClientRect();"
                                    + " return Math.round(b.width) + 'x' + Math.round(b.height) + '/' + Math.round(Math.abs((b.top + b.bottom) / 2 - (l.top + l.bottom) / 2)) + '/' + Math.round(l.height)"
                                    + " + '/' + document.querySelectorAll('#settings-form input[id^=set-show]:checked').length; })()")
                    // The message line: how wide and how tall it is, how wide the row's box and its elements are, and the window.
                    + " note:[" + page().getEngine().executeScript(
                            "(function () { var n = document.getElementById('note'), t = document.getElementById('top').getBoundingClientRect(),"
                                    + " s = document.querySelector('.strip'), last = s.querySelector('.tools').getBoundingClientRect(), b = n.getBoundingClientRect();"
                                    + " return (n.hidden ? 'none' : Math.round(b.width) + 'x' + Math.round(b.height)) + '|top:' + Math.round(t.width)"
                                    + " + '|elements:' + Math.round(last.right - s.getBoundingClientRect().left) + '|window:' + window.innerWidth; })()") + "]"
                    // The word between the amount used and the budget: its text, its size and whether it is on the figures' baseline.
                    + " of:[" + page().getEngine().executeScript(
                            "(function () { var o = document.getElementById('of'), c = getComputedStyle(o), u = document.getElementById('used').getBoundingClientRect(), b = o.getBoundingClientRect();"
                                    + " return o.textContent + '|' + c.fontSize + '|' + c.fontWeight + '|' + c.color + '|' + Math.round(Math.abs(u.bottom - b.bottom)); })()") + "]"
                    // The icons of the window: their sizes.
                    + " icons:" + window().getIcons().stream().map(icon -> String.valueOf((int) icon.getWidth())).collect(java.util.stream.Collectors.joining("/"))
                    // The lists: the size of their text, the height of a line, and whether the line of the zoom is shown.
                    + " list:[" + page().getEngine().executeScript(
                            "(function () { var row = document.querySelector('#panel-lines .row'), c = getComputedStyle(document.getElementById('panel-lines'));"
                                    + " return c.fontSize + '|' + (row ? Math.round(row.getBoundingClientRect().height) : 0) + '|' + !document.getElementById('log-controls').hidden"
                                    + " + '|' + Math.round(document.getElementById('zoom-in').getBoundingClientRect().width); })()") + "]"
                    // The automatic interval in the settings: whether it is checked, whether the field is disabled, and what the field shows.
                    + " auto:[" + page().getEngine().executeScript(
                            "(function () { var f = document.getElementById('set-usageIntervalSeconds'), a = document.getElementById('set-autoInterval');"
                                    + " return a.checked + '|' + f.disabled + '|' + f.value + '|' + Math.round(Math.abs(a.getBoundingClientRect().top - f.getBoundingClientRect().top)); })()") + "]"
                    // The About view: whether it is shown, how large the logo is drawn and how large its file is, the version, the
                    // line of debug mode, the end of the path of the settings, and how wide the view is.
                    + " about:[" + page().getEngine().executeScript(
                            "(function () { var v = document.getElementById('about-view'), i = document.getElementById('about-logo'), b = i.getBoundingClientRect();"
                                    + " return !v.hidden + '|' + Math.round(b.width) + 'x' + Math.round(b.height) + '|' + i.naturalWidth + '|' + document.getElementById('about-version').textContent"
                                    + " + '|' + document.getElementById('about-debug').textContent + '|' + document.getElementById('about-settings').textContent.slice(-13)"
                                    + " + '|' + document.getElementById('about-history').textContent.slice(-23) + '|' + !document.getElementById('settings-form').hidden"
                                    + " + '|' + Math.round(v.getBoundingClientRect().width) + '/' + window.innerWidth + '|' + (document.documentElement.scrollWidth - window.innerWidth); })()") + "]"
                    // The history's table: the left edge of each cell of the first row beside the list's own edges, so that the
                    // room between the columns can be worked out.
                    + " columns:[" + page().getEngine().executeScript(
                            "(function () { var row = document.querySelector('#panel-lines .row:not(.head)'); if (!row || row.children.length < 4) { return ''; }"
                                    + " var box = row.getBoundingClientRect(), out = [Math.round(box.width)];"
                                    + " Array.prototype.forEach.call(row.children, function (c) { var b = c.getBoundingClientRect(); out.push(Math.round(b.left - box.left) + '-' + Math.round(b.right - box.left)); });"
                                    + " var head = document.querySelector('#panel-lines .head'); out.push(head ? Math.round(head.children[1].getBoundingClientRect().right - box.left) : 0);"
                                    + " return out.join(' '); })()") + "]"
                    // The fake backend's fields in the debug view, and whether the countdown says that requests are refused.
                    + " fake:[" + page().getEngine().executeScript(
                            "(function () { var v = function (id) { return document.getElementById(id).value; };"
                                    + " return v('debug-fakeAccount') + '|' + v('debug-fakeIncreaseCents') + '|' + v('debug-fakeBudget') + '|' + v('debug-fakeStartAmount')"
                                    + " + '|' + v('debug-fakeRefuseSeconds') + '|' + v('debug-fakeDelayMillis') + '|' + document.getElementById('countdown').className; })()") + "]"
                    // Cost control in the settings: the monthly limit, the daily one, whether its field is disabled, and Automatic.
                    + " cost:[" + page().getEngine().executeScript(
                            "(function () { var m = document.getElementById('set-costMonthlyLimit'), d = document.getElementById('set-costDailyLimit'), a = document.getElementById('set-costDailyAutomatic');"
                                    + " return m.value + '|' + d.value + '|' + d.disabled + '|' + a.checked + '|' + Math.round(Math.abs(a.getBoundingClientRect().top - d.getBoundingClientRect().top)); })()") + "]"
                    + " end");
            System.out.flush();
        });
    }

    /**
     * Presses the mouse button in the middle of the named element of the page, or at the left edge of the window for
     * none, moves the mouse by 40 and 25 pixels in three steps with the button held, releases it, and says by how much
     * the window moved and whether the page has a panel open afterwards (a button that was pressed would have opened one).
     */
    private static void drag(String name, String id) throws InterruptedException {
        double[] before = new double[2];
        onFx(() -> {
            WebView view = page();
            Object at = id == null ? "1,10" : view.getEngine().executeScript(
                    "(function () { var b = document.getElementById('" + id + "').getBoundingClientRect();"
                            + " return Math.round(b.left + b.width / 2) + ',' + Math.round(b.top + b.height / 2); })()");
            double x = Double.parseDouble(at.toString().split(",")[0]);
            double y = Double.parseDouble(at.toString().split(",")[1]);
            before[0] = window().getX();
            before[1] = window().getY();
            javafx.geometry.Point2D screen = view.localToScreen(x, y);
            fire(view, javafx.scene.input.MouseEvent.MOUSE_PRESSED, x, y, screen.getX(), screen.getY(), true);
            for (int i = 1; i <= 3; i++) {
                double dx = 40.0 * i / 3;
                double dy = 25.0 * i / 3;
                fire(view, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, x + dx, y + dy, screen.getX() + dx, screen.getY() + dy, true);
            }
            fire(view, javafx.scene.input.MouseEvent.MOUSE_RELEASED, x + 40, y + 25, screen.getX() + 40, screen.getY() + 25, false);
        });
        Thread.sleep(500);
        onFx(() -> {
            System.out.println("PROBE " + name + "=moved:" + Math.round(window().getX() - before[0]) + "," + Math.round(window().getY() - before[1])
                    + " hasTitleBar:" + StageSwap.hasTitleBar(window())
                    + " panel:" + page().getEngine().executeScript("!document.getElementById('panel').hidden"));
            System.out.flush();
        });
    }

    /**
     * Moves the mouse onto the middle of the named element and lets it rest there, then says what tooltip is shown: its
     * text and the size of its font. The tooltip is a window of its own, so it is looked for among the windows.
     */
    private static void hover(String name, String id) throws InterruptedException {
        String[] shown = {"none"};
        // The mouse of the test is not the system's, and a tooltip does not always come for it at the first try: it is
        // moved off and on again a few times before the answer is that none came.
        for (int attempt = 1; attempt <= 5 && shown[0].equals("none"); attempt++) {
            for (int step = 0; step < 3; step++) {
                int nudge = step;
                onFx(() -> {
                    WebView view = page();
                    Object at = view.getEngine().executeScript(
                            "(function () { var b = document.getElementById('" + id + "').getBoundingClientRect();"
                                    + " return Math.round(b.left + b.width / 2) + ',' + Math.round(b.top + b.height / 2); })()");
                    double x = Double.parseDouble(at.toString().split(",")[0]) + nudge - 1;
                    double y = Double.parseDouble(at.toString().split(",")[1]);
                    javafx.geometry.Point2D screen = view.localToScreen(x, y);
                    if (nudge == 0) {
                        fire(view, javafx.scene.input.MouseEvent.MOUSE_ENTERED, x, y, screen.getX(), screen.getY(), false);
                    }
                    fire(view, javafx.scene.input.MouseEvent.MOUSE_MOVED, x, y, screen.getX(), screen.getY(), false);
                });
                Thread.sleep(300);
            }
            Thread.sleep(2000);
            onFx(() -> shown[0] = Window.getWindows().stream()
                    .filter(window -> window instanceof javafx.scene.control.Tooltip && window.isShowing())
                    .map(window -> (javafx.scene.control.Tooltip) window)
                    .map(tip -> tip.getText() + "|" + tip.getFont().getSize())
                    .findFirst().orElse("none"));
            // Off the page again, and the tooltip away, so that nothing of it is left for the steps that follow.
            onFx(() -> {
                fire(page(), javafx.scene.input.MouseEvent.MOUSE_EXITED, -5, -5, 0, 0, false);
                Window.getWindows().stream().filter(window -> window instanceof javafx.scene.control.Tooltip).toList()
                        .forEach(Window::hide);
            });
            Thread.sleep(400);
        }
        onFx(() -> {
            System.out.println("PROBE " + name + "=shown:[" + shown[0] + "] sheets:" + page().getScene().getStylesheets().size());
            System.out.flush();
        });
    }

    /**
     * Drags the mouse from the time of the row down across the list that is shown, then asks the page to select
     * everything, and says each time how many characters are selected, and what the style of the row and of a line says.
     */
    private static void select(String name) throws InterruptedException {
        String[] dragged = new String[1];
        onFx(() -> {
            WebView view = page();
            Object at = view.getEngine().executeScript(
                    "(function () { var b = document.getElementById('time').getBoundingClientRect(); return Math.round(b.left + 2) + ',' + Math.round(b.top + b.height / 2); })()");
            double x = Double.parseDouble(at.toString().split(",")[0]);
            double y = Double.parseDouble(at.toString().split(",")[1]);
            javafx.geometry.Point2D screen = view.localToScreen(x, y);
            fire(view, javafx.scene.input.MouseEvent.MOUSE_PRESSED, x, y, screen.getX(), screen.getY(), true);
            for (int i = 1; i <= 6; i++) {
                fire(view, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, x + 40 * i, y + 20 * i, screen.getX() + 40 * i, screen.getY() + 20 * i, true);
            }
            fire(view, javafx.scene.input.MouseEvent.MOUSE_RELEASED, x + 240, y + 120, screen.getX() + 240, screen.getY() + 120, false);
            dragged[0] = String.valueOf(view.getEngine().executeScript("String(window.getSelection()).length"));
        });
        Thread.sleep(300);
        onFx(() -> {
            Object all = page().getEngine().executeScript(
                    "(function () { document.execCommand('selectAll'); var n = String(window.getSelection()).length; window.getSelection().removeAllRanges(); return n; })()");
            Object styles = page().getEngine().executeScript(
                    "(function () { var row = document.querySelector('#panel-lines .row');"
                            + " return getComputedStyle(document.getElementById('time')).webkitUserSelect + '/' + (row ? getComputedStyle(row).webkitUserSelect : '-')"
                            + " + '/' + getComputedStyle(document.getElementById('set-usageIntervalSeconds')).webkitUserSelect; })()");
            System.out.println("PROBE " + name + "=dragged:" + dragged[0] + " all:" + all + " styles:" + styles);
            System.out.flush();
        });
    }

    /**
     * Moves the mouse onto the middle of the named element, lets the page follow, and says what the line of a reading
     * with no spend shows and how tall the window is.
     */
    private static void point(String name, String id) throws InterruptedException {
        for (int nudge = 0; nudge < 3; nudge++) {
            int by = nudge;
            onFx(() -> {
                WebView view = page();
                Object at = view.getEngine().executeScript(
                        "(function () { var b = document.getElementById('" + id + "').getBoundingClientRect();"
                                + " return Math.round(b.left + b.width / 2) + ',' + Math.round(b.top + b.height / 2); })()");
                double x = Double.parseDouble(at.toString().split(",")[0]) + by - 1;
                double y = Double.parseDouble(at.toString().split(",")[1]);
                javafx.geometry.Point2D screen = view.localToScreen(x, y);
                if (by == 0) {
                    fire(view, javafx.scene.input.MouseEvent.MOUSE_ENTERED, x, y, screen.getX(), screen.getY(), false);
                }
                fire(view, javafx.scene.input.MouseEvent.MOUSE_MOVED, x, y, screen.getX(), screen.getY(), false);
            });
            Thread.sleep(200);
        }
        Thread.sleep(900);
        onFx(() -> {
            System.out.println("PROBE " + name + "=line:[" + page().getEngine().executeScript(
                    "(function () { var n = document.getElementById('hover-note'); return n.hidden ? '' : n.textContent; })()") + "]"
                    + " colour:" + page().getEngine().executeScript("getComputedStyle(document.getElementById('hover-note')).color")
                    + " used:" + page().getEngine().executeScript("document.getElementById('used').className")
                    + " height:" + Math.round(window().getScene().getHeight())
                    + " width:" + Math.round(window().getScene().getWidth()));
            System.out.flush();
            Window.getWindows().stream().filter(window -> window instanceof javafx.scene.control.Tooltip).toList().forEach(Window::hide);
        });
    }

    /**
     * Presses the button that copies the path of the settings file and says what is on the clipboard then, how the link
     * of the path is drawn, and what the button says. What was on the clipboard before is put back afterwards. The link
     * itself is not pressed: it would open a window of the file manager on the desktop the test runs on.
     */
    private static void copyPath(String name) throws InterruptedException {
        String[] before = new String[1];
        onFx(() -> {
            before[0] = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            page().getEngine().executeScript("document.getElementById('about-copy-settings').click()");
        });
        Thread.sleep(700);
        onFx(() -> {
            String copied = javafx.scene.input.Clipboard.getSystemClipboard().getString();
            Object link = page().getEngine().executeScript(
                    "(function () { var a = document.getElementById('about-settings'), c = getComputedStyle(a), b = document.getElementById('about-copy-settings');"
                            + " return c.fontWeight + '|' + c.color + '|' + c.textDecorationLine + '|' + c.cursor + '|' + (a.textContent === arguments.length ? '' : a.textContent.slice(-13))"
                            + " + '|' + b.className + '|' + b.title + '|' + Math.round(b.getBoundingClientRect().width); })()");
            System.out.println("PROBE " + name + "=clipboard:" + (copied != null && copied.endsWith("settings.json") && java.nio.file.Path.of(copied).isAbsolute())
                    + " same:" + String.valueOf(copied).equals(String.valueOf(page().getEngine().executeScript("document.getElementById('about-settings').textContent")))
                    + " link:[" + link + "]");
            System.out.flush();
            javafx.scene.input.ClipboardContent back = new javafx.scene.input.ClipboardContent();
            back.putString(before[0] == null ? "" : before[0]);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(back);
        });
    }

    private static void fire(WebView view, javafx.event.EventType<javafx.scene.input.MouseEvent> type, double x, double y,
            double screenX, double screenY, boolean down) {
        javafx.event.Event.fireEvent(view, new javafx.scene.input.MouseEvent(type, x, y, screenX, screenY,
                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, down, false, false, true, false, false, null));
    }

    private static Stage window() {
        // A tooltip is a window too, and not this one.
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

    private UsageAppProbe() {
    }
}
