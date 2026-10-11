// The strip: asks the backend for its settings once, then polls its status at
// the interval it reported. What to show is decided in view.js; this file talks
// to the backend and puts text on the page. Text from the backend is only ever
// assigned with textContent, never parsed as HTML.
(function () {
    'use strict';

    var view = window.UsageView;
    var REQUEST_TIMEOUT_MS = 10000;
    var RETRY_START_MS = 2000;

    var config = null;
    var lastStatus = null;
    var pollTimer = null;
    var polling = false;
    var lastStale = false;
    // Which panel is shown below the strip: 'history', 'log', or none.
    var openPanel = null;
    var panelLoading = false;
    // The two choices of the log panel: the least severe level shown and whether the lines have their date. They are the
    // backend's, saved in the settings file; the page has them from the first answer after the panel was opened.
    var logLevel = null;
    var logDate = false;
    var logKnown = false;
    // A choice the backend could not save still holds for the panel: the level is then asked for by name.
    var logUnsaved = false;
    // After a change of either the log stands at its newest line again.
    var logToTop = false;
    // Which reading the history panel shows up to: a new one means it is read again.
    var panelMark = null;
    // What the panel shows, to leave it alone when a read finds nothing new.
    var panelKey = null;
    // The rows on show, to tell how many came in above what the person was reading.
    var panelRows = [];
    var panelHasHeader = false;
    // Whether the pointer is over the countdown, which then shows the message of an HTTP 429 under the row.
    var hoveringCountdown = false;
    // The panel that was shown when the settings were opened, which they give back when they are left; null if none.
    var returnTo = null;
    // The height the window has while a log or history is shown, and the width the vertical scrollbar of their panel takes.
    var panelHeight = 0;
    var scrollbarWidth = 0;
    // How wide the history table needs to be, as last measured; kept while the panel is closed so that it opens at once as wide as it was.
    var tableWidth = 0;
    // What the settings view shows of the row before it is applied: {show, timeFormat}, or null when the row is the backend's.
    var preview = null;

    function $(id) {
        return document.getElementById(id);
    }

    function show(id, visible) {
        $(id).hidden = !visible;
    }

    function setNote(id, text, className) {
        var note = $(id);
        note.hidden = !text;
        note.textContent = text || '';
        if (className) {
            note.className = className;
        }
    }

    // ---- talking to the backend

    async function request(path, options) {
        var controller = new AbortController();
        var timer = setTimeout(function () { controller.abort(); }, REQUEST_TIMEOUT_MS);
        try {
            var response = await fetch(path, Object.assign({ cache: 'no-store', signal: controller.signal }, options));
            var body = null;
            try {
                body = await response.json();
            } catch (ignored) {
                // Not JSON; the status code below is all there is to report.
            }
            if (!response.ok) {
                throw new Error(body && body.error ? body.error : 'The application answered HTTP ' + response.status + '.');
            }
            return body;
        } finally {
            clearTimeout(timer);
        }
    }

    function postJson(path, payload) {
        return request(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
    }

    // ---- polling

    async function poll() {
        if (polling) {
            return;
        }
        polling = true;
        try {
            lastStatus = await request('/api/status');
            // What was applied is in this status; a preview is for the open settings only.
            if (openPanel !== 'settings') {
                preview = null;
            }
            showConnection(false);
            render();
            refreshPanel();
        } catch (e) {
            showConnection(true);
        } finally {
            polling = false;
            scheduleNextPoll();
        }
    }

    function scheduleNextPoll() {
        clearTimeout(pollTimer);
        pollTimer = setTimeout(poll, config.pollIntervalSeconds * 1000);
    }

    function pollNow() {
        clearTimeout(pollTimer);
        poll();
    }

    // ---- showing the status

    function render() {
        if (!lastStatus) {
            return;
        }
        var v = view.describeStatus(lastStatus);
        // The settings view shows a change in the row before it is applied; then its switches count, not the backend's.
        var s = preview ? preview.show : v.show;
        var variants = preview && v.variants ? v.variants : null;
        var loading = v.loading;

        lastStale = v.stale;
        applyAppClass();
        show('refresh', on(s.refresh));
        $('refresh').className = 'icon' + (v.refreshing ? ' busy' : '');

        // With no usage reported, and with no reading at all, the row has no word for it: the figures that are switched on and
        // have no value are gray boxes, as at the start, and their tooltips say which of the two it is.
        var why = v.placeholder ? ': ' + v.placeholder.charAt(0).toLowerCase() + v.placeholder.slice(1) : '';
        var boxes = loading || Boolean(v.placeholder);

        var timeText = variants ? (preview.timeFormat === 'hh:mm:ss' ? variants.timeLong : variants.timeShort) : v.time;
        figure('time', 'time', on(s.time) && (boxes || Boolean(timeText)), timeText, timeText ? v.timeTooltip || 'Last update' : 'Last update' + why,
            boxes && !timeText);

        var spend = v.spend;
        var spendBoxes = boxes && !spend;
        var percentShown = Boolean(s.percentage) && (spendBoxes || Boolean(spend && spend.percentText));
        var usedShown = on(s.used) && (spendBoxes || Boolean(spend));
        var limitShown = on(s.limit) && (spendBoxes || Boolean(spend));
        show('spend', percentShown || usedShown || limitShown);
        // Boxes are not dimmed with the old figures, and the dimming is the whole group's.
        $('spend').className = 'spend' + (spendBoxes ? ' boxes' : '');
        figure('percent', 'percent', percentShown, spend && spend.percentText, spend ? spend.percentTooltip : 'Share of the budget spent' + why, spendBoxes);
        figure('used', 'used', usedShown, spend && amount(spend.used, variants, 'used', s.currency), spend ? spend.usedTooltip : 'Credits used' + why, spendBoxes);
        figure('limit', 'limit', limitShown, spend && amount(spend.limit, variants, 'limit', s.currency), spend ? spend.limitTooltip : 'Credit budget' + why, spendBoxes);
        show('slash', usedShown && limitShown);

        // While the server asks us to slow down (HTTP 429) the countdown is red and, hovered, says why in bold red.
        figure('countdown', 'countdown' + (v.countdownAlert ? ' alert' : ''), on(s.countdown) && (loading || Boolean(v.countdown)),
            v.countdown && v.countdown.text, v.countdown ? v.countdown.tooltip : 'Seconds until the next refresh (negative when overdue)',
            loading && !v.countdown);
        setNote('alert-note', hoveringCountdown && v.countdownAlert ? v.countdownAlert : '');
        renderOptional('interval', s.interval, v.interval, 'Time between usage requests');
        renderButtons(s);
        renderOptional('delta-used', s.deltaUsed, v.deltaUsed, 'Change in the amount used since the previous reading');
        renderOptional('delta-time', s.deltaTime, v.deltaTime, 'Time since the previous reading');
        renderOptional('duration', s.duration, v.duration, 'How long the latest usage request took');

        setNote('note', v.message && v.message.text, v.message ? 'note note-' + v.message.kind : null);
        markNoted();
    }

    /**
     * Says on the row's box whether a message line is shown below the row, any of the three: the style sheet gives the
     * window a least width then, when no panel is shown, so that a message is not broken into a column as narrow as a row
     * of few elements.
     */
    function markNoted() {
        var noted = ['note', 'alert-note', 'connection'].some(function (id) { return !$(id).hidden; });
        $('top').className = 'top' + (noted ? ' noted' : '');
    }

    /** Shows or hides the line that says contact is lost, and what follows from a message line being there. */
    function showConnection(lost) {
        show('connection', Boolean(lost));
        markNoted();
    }

    /** A switch of the row is on unless it says otherwise: the five elements that were always there are shown when nothing is said. */
    function on(flag) {
        return flag !== false;
    }

    /**
     * A figure of the row: there or not, with its text and tooltip. Before the first reading, with no usage reported and with no
     * reading at all it is a gray box instead, with the tooltip that needs no value, so that the row always has its shape.
     */
    function figure(id, className, visible, text, tooltip, skeleton) {
        show(id, visible);
        $(id).className = className + (skeleton ? ' skeleton' : '');
        $(id).textContent = skeleton ? '\u00a0' : (text || '');
        $(id).title = tooltip || '';
    }

    /** An amount as the backend wrote it, or, while the settings preview the symbol, the one of its two forms the checkbox asks for. */
    function amount(text, variants, name, withSymbol) {
        var form = variants && variants[name + (withSymbol ? 'Symbol' : 'Plain')];
        return form === null || form === undefined ? text : form;
    }

    /**
     * The buttons of the history, the log and the error log are there when their settings say so; the gear never goes. They follow
     * their switches while the settings are shown as well, so that a click on a checkbox is seen in the row. The button of the panel
     * that is shown is always there, so that the panel can be closed: a button that is switched off is never taken away from a panel
     * that is open.
     */
    function renderButtons(show) {
        var flags = { history: show.historyIcon, log: show.logIcon, errors: show.errorIcon };
        Object.keys(flags).forEach(function (name) {
            $(PANELS[name].button).hidden = !(flags[name] !== false || openPanel === name);
        });
    }

    // The classes of the optional items of the row, which are a gray box of their own width when they have no value.
    var OPTIONAL_CLASSES = { 'interval': 'delta delta-interval', 'delta-used': 'delta delta-used', 'delta-time': 'delta delta-time', 'duration': 'delta delta-duration' };

    /**
     * An optional item of the row. Its setting off, it is not there and takes no room. Its setting on, it is its value, or, when the
     * backend has none for it (no reading before this one, no request that has ended), a gray box with its tooltip: at the start
     * and at any time after. The box is as wide as the item's room, so the row does not change width from one refresh to the next.
     */
    function renderOptional(id, wanted, item, tooltip) {
        show(id, Boolean(wanted));
        $(id).className = OPTIONAL_CLASSES[id] + (item ? '' : ' skeleton');
        $(id).textContent = item ? item.text : '\u00a0';
        $(id).title = item ? item.tooltip : tooltip;
    }

    /** Whether the backend says that the fake backend is in use, so that the figures are invented. */
    function fakeInUse() {
        return Boolean(lastStatus && lastStatus.fake);
    }

    function applyAppClass() {
        // The log and the history fill the window; the settings do not, so that the page's own height is the content's.
        $('app').className = (lastStale ? 'stale' : '')
            + (openPanel === 'log' || openPanel === 'history' || openPanel === 'errors' ? ' open' : '')
            + (FORMS[openPanel] ? ' fit' : '')
            // The readings are invented while the fake backend is in use: the red border says so.
            + (fakeInUse() ? ' fake' : '')
            // A window with no title bar has no frame of the system to show where it ends: the page draws one.
            + (bare() ? ' bare' : '');
    }

    /**
     * Whether the window has no title bar and the page draws its border: the setting is off and no panel is shown, since a
     * panel brings the title bar back. Not while the red border of the fake backend is round the window.
     */
    function bare() {
        return Boolean(lastStatus && lastStatus.display && lastStatus.display.titleBar === false) && !openPanel && !fakeInUse();
    }

    // ---- the settings view: a form the backend fills in each time it is opened

    /** The settings as the backend gave them when the view was opened, to tell whether the form has unapplied changes. */
    var settingsShown = null;
    var settingsDefaults = null;

    // The setting that switches the button of each panel on and off; the settings have none, as their button is always there.
    var ICON_SETTINGS = { history: 'showHistoryIcon', log: 'showLogIcon', errors: 'showErrorIcon' };

    // The switches of the main view, in the order of the row; the currency symbol is a property of the amounts, beside the first of them.
    var MAIN_FLAGS = ['showTime', 'showPercentage', 'showUsed', 'showCurrency', 'showLimit', 'showRefresh', 'showCountdown', 'showInterval', 'showDeltaUsed', 'showDeltaTime', 'showDuration', 'showLogIcon', 'showHistoryIcon', 'showErrorIcon'];
    // The thirteen elements of the row that have a checkbox: the flags of Main view but the currency symbol, which is a property of the amounts.
    var ELEMENT_FLAGS = MAIN_FLAGS.filter(function (key) { return key !== 'showCurrency'; });
    var SETTING_FLAGS = MAIN_FLAGS.concat(['showTitleBar', 'stayOnTop', 'historyDate', 'historyZeroLines', 'historyFailedLines', 'historyDeltaUsed', 'historyDeltaTime', 'logResponse']);

    /**
     * Shows in the row what the main-view part of the form says, at once and before Apply, so that it can be seen which element a
     * checkbox belongs to. Nothing is sent: leaving the settings without Apply gives the row back as the backend has it.
     */
    function previewNow() {
        if (openPanel !== 'settings' || settingsShown === null) {
            return;
        }
        var checked = function (key) { return Boolean($('set-' + key).checked); };
        preview = {
            timeFormat: $('set-timeFormat').value,
            show: {
                time: checked('showTime'), percentage: checked('showPercentage'), used: checked('showUsed'), currency: checked('showCurrency'),
                limit: checked('showLimit'), refresh: checked('showRefresh'), countdown: checked('showCountdown'),
                interval: checked('showInterval'), deltaUsed: checked('showDeltaUsed'), deltaTime: checked('showDeltaTime'),
                duration: checked('showDuration'),
                logIcon: checked('showLogIcon'), historyIcon: checked('showHistoryIcon'), errorIcon: checked('showErrorIcon')
            }
        };
        render();
    }

    /**
     * The button in the heading of Main view: switches every element of the row on, or, when every one is on already, all of
     * them off. The two properties, the currency symbol and the time format, are left as they are. The row shows it at once,
     * like a click on a checkbox, and it is saved when the view is.
     */
    function selectAllOrNone() {
        if (openPanel !== 'settings' || settingsShown === null) {
            return;
        }
        var allOn = ELEMENT_FLAGS.every(function (key) { return Boolean($('set-' + key).checked); });
        ELEMENT_FLAGS.forEach(function (key) { $('set-' + key).checked = !allOn; });
        previewNow();
    }

    /** The interval is one entry field for a whole number of seconds; it shows the value in force, which is what the backend has. */
    function fillInterval(current) {
        $('set-usageIntervalSeconds').value = String(current);
    }

    function fillForm(values) {
        SETTING_FLAGS.forEach(function (key) {
            $('set-' + key).checked = Boolean(values[key]);
        });
        $('set-timeFormat').value = values.timeFormat;
        $('set-usageIntervalSeconds').value = String(values.usageIntervalSeconds);
    }

    function readForm() {
        var values = {
            usageIntervalSeconds: Number($('set-usageIntervalSeconds').value),
            timeFormat: $('set-timeFormat').value
        };
        SETTING_FLAGS.forEach(function (key) {
            values[key] = $('set-' + key).checked;
        });
        return values;
    }

    /** Reads the settings from the backend and fills the form; the form is hidden, with the reason, if they cannot be read. */
    async function loadSettings() {
        setNote('settings-error', '');
        try {
            var body = await request('/api/settings');
            settingsShown = body.settings;
            settingsDefaults = body.defaults;
            fillInterval(body.settings.usageIntervalSeconds);
            fillForm(body.settings);
            show('settings-form', true);
        } catch (e) {
            // The frontend keeps no settings of its own, so with none to show there is no form.
            settingsShown = null;
            settingsDefaults = null;
            show('settings-form', false);
            setNote('settings-error', 'The settings could not be read: ' + e.message);
        }
        setApplyEnabled(settingsShown !== null);
    }

    function setApplyEnabled(enabled) {
        ['settings-apply', 'settings-restore'].forEach(function (id) {
            if (enabled) {
                $(id).removeAttribute('disabled');
            } else {
                $(id).setAttribute('disabled', '');
            }
        });
    }

    /** Whether the form holds anything other than what the backend gave when the view was opened or last saved. */
    function settingsChanged() {
        if (settingsShown === null) {
            return false;
        }
        var form = readForm();
        return Object.keys(form).some(function (key) { return form[key] !== settingsShown[key]; });
    }

    /**
     * Sends what the form holds to the backend, which keeps and saves it. True when that was done; when a value is refused or the
     * settings cannot be saved it says why in red and is false, and the caller leaves the view open.
     */
    async function saveSettings() {
        // Only that it is a number is checked here; whether it is in range is the backend's to say.
        if (!/^\d+$/.test($('set-usageIntervalSeconds').value.trim())) {
            setNote('settings-error', 'The interval must be a whole number of seconds.');
            return false;
        }
        try {
            var body = await postJson('/api/settings', readForm());
            settingsShown = body.settings;
            settingsDefaults = body.defaults;
            // A panel whose button has just been switched off is not given back.
            if (returnTo && body.settings[ICON_SETTINGS[returnTo]] === false) {
                returnTo = null;
            }
            return true;
        } catch (e) {
            setNote('settings-error', e.message);
            return false;
        }
    }

    /** Apply: saves, and then the view closes and the row shows the change at once. */
    async function applySettings() {
        if (settingsShown === null || !(await saveSettings())) {
            return;
        }
        leaveForm(null, true);
    }

    /**
     * Leaves the settings, or the debug settings, for the named panel, or for what they replaced when none is named. After a save the
     * row keeps what was previewed until the poll brings it back as applied, so that it does not flick to the old state; without one
     * the row is the backend's again.
     */
    function leaveForm(name, saved) {
        var applied = saved ? preview : null;
        var back = name || returnTo;
        returnTo = null;
        showPanelNamed(back);
        if (saved) {
            preview = applied;
            render();
            pollNow();
        }
    }

    /** Fills the form with the defaults the backend gave; they take effect only when Apply is pressed. */
    function restoreDefaults() {
        if (settingsDefaults) {
            fillForm(settingsDefaults);
            previewNow();
        }
    }

    /** Cancel: closes the view and changes nothing; it asks nothing, since nothing was sent. It is the one way to leave without saving. */
    function cancelSettings() {
        if (openPanel === 'settings') {
            leaveForm(null, false);
        }
    }

    // ---- the debug settings view, in debug mode only: a form like the settings, with its own endpoint

    /** The debug settings as the backend gave them when the view was opened or last saved; null when they could not be read. */
    var debugShown = null;

    function readDebugForm() {
        return {
            fakeBackend: Boolean($('debug-fakeBackend').checked),
            fakeScenario: $('debug-fakeScenario').value,
            fakeToken: Boolean($('debug-fakeToken').checked)
        };
    }

    async function loadDebug() {
        setNote('debug-error', '');
        try {
            var body = await request('/api/debug');
            debugShown = body.settings;
            // The scenarios are the backend's to name; the list is made from what it sends.
            $('debug-fakeScenario').replaceChildren.apply($('debug-fakeScenario'), body.scenarios.map(function (name) {
                var option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                return option;
            }));
            $('debug-fakeBackend').checked = Boolean(body.settings.fakeBackend);
            $('debug-fakeScenario').value = body.settings.fakeScenario;
            $('debug-fakeToken').checked = Boolean(body.settings.fakeToken);
            show('debug-form', true);
        } catch (e) {
            debugShown = null;
            show('debug-form', false);
            setNote('debug-error', 'The debug settings could not be read: ' + e.message);
        }
        if (debugShown !== null) {
            $('debug-apply').removeAttribute('disabled');
        } else {
            $('debug-apply').setAttribute('disabled', '');
        }
    }

    function debugChanged() {
        if (debugShown === null) {
            return false;
        }
        var form = readDebugForm();
        return Object.keys(form).some(function (key) { return form[key] !== debugShown[key]; });
    }

    /** Sends the debug settings; the backend saves them and they take effect at once. False, with the reason in red, when it could not. */
    async function saveDebug() {
        try {
            var body = await postJson('/api/debug', readDebugForm());
            debugShown = body.settings;
            return true;
        } catch (e) {
            setNote('debug-error', e.message);
            return false;
        }
    }

    async function applyDebug() {
        if (debugShown === null || !(await saveDebug())) {
            return;
        }
        leaveForm(null, true);
    }

    function cancelDebug() {
        if (openPanel === 'debug') {
            leaveForm(null, false);
        }
    }

    // The views that are forms: leaving one by a button of the row saves it first, when anything in it was changed.
    var FORMS = {
        settings: { changed: settingsChanged, save: saveSettings },
        debug: { changed: debugChanged, save: saveDebug }
    };

    // ---- the panel below the strip: the usage history or the log, one at a time

    var PANELS = {
        history: {
            button: 'history-button',
            path: '/api/history',
            describe: view.describeHistory,
            show: 'Show the usage history',
            hide: 'Hide the usage history',
            failure: 'The usage history could not be read: '
        },
        settings: {
            button: 'settings-button',
            show: 'Show the settings',
            hide: 'Hide the settings'
        },
        debug: {
            button: 'debug-button',
            show: 'Debug mode: show the debug settings',
            hide: 'Debug mode: hide the debug settings',
            // Yellow, shown or not: the sign of debug mode.
            extra: ' debug'
        },
        errors: {
            button: 'errors-button',
            path: '/api/errors',
            describe: view.describeErrors,
            show: 'Show the error log',
            hide: 'Hide the error log',
            failure: 'The error log could not be read: '
        },
        log: {
            button: 'log-button',
            path: '/api/log',
            describe: view.describeLog,
            show: 'Show the log',
            hide: 'Hide the log',
            failure: 'The log could not be read: '
        }
    };

    /** What the backend says the history file is now: it changes when a row is added, a failed one included. */
    function historyStamp() {
        return lastStatus && lastStatus.historyStamp ? lastStatus.historyStamp : '';
    }

    function panelIsOpen() {
        return openPanel !== null;
    }

    /**
     * Reads what the open panel shows and puts it on the page. The lines are replaced only when they
     * changed, and what the person has scrolled to stays where it is, though lines come in above it.
     */
    async function loadPanel() {
        if (panelLoading || !openPanel || FORMS[openPanel]) {
            return;
        }
        var name = openPanel;
        var spec = PANELS[name];
        var stamp = historyStamp();
        var level = logLevel;
        panelLoading = true;
        try {
            var body = await request(spec.path + (name === 'log' && logUnsaved ? '?level=' + level : ''));
            if (name === 'log') {
                takeLogChoices(body);
                // The date is the page's to cut: the lines come as the file has them.
                body = Object.assign({}, body, { date: logDate });
            }
            var shown = spec.describe(body);
            // A read that was begun for another level than the one chosen meanwhile is not shown: the one that follows is.
            if (name === openPanel && !(name === 'log' && level !== null && level !== logLevel)) {
                showPanel(shown);
                if (name === 'log' && logToTop) {
                    $('panel-lines').scrollTop = 0;
                    logToTop = false;
                }
                // Noted only now that the read has been shown: a read that failed, or was for a panel that
                // has gone, leaves the mark as it was, so the history is read again at the next poll.
                panelMark = stamp;
            }
        } catch (e) {
            // What was shown stays; only the error is added.
            if (name === openPanel) {
                setNote('panel-error', spec.failure + e.message);
            }
        } finally {
            panelLoading = false;
        }
        // Another panel was opened while this read was under way and could not start its own: it starts now. So does the
        // read for a level that was chosen while this one was under way.
        if (name !== openPanel || (name === 'log' && level !== null && level !== logLevel)) {
            loadPanel();
        }
    }

    /** Takes the two choices of the log panel from the first answer after it was opened, and shows them in its controls. */
    function takeLogChoices(body) {
        if (logKnown || openPanel !== 'log') {
            return;
        }
        logKnown = true;
        logLevel = body.level || 'info';
        logDate = Boolean(body.date);
        $('log-level').value = logLevel;
        $('log-date').checked = logDate;
        show('log-controls', true);
    }

    /**
     * A choice of the log panel was changed: it takes effect at once, on the log that is shown, which stands at its newest
     * line again, and it is saved. If it cannot be saved it holds all the same, for as long as the panel is shown.
     */
    async function changeLogChoices() {
        logLevel = $('log-level').value;
        logDate = Boolean($('log-date').checked);
        logToTop = true;
        try {
            await postJson('/api/log', { level: logLevel, date: logDate });
            logUnsaved = false;
            setNote('log-error', '');
        } catch (e) {
            logUnsaved = true;
            setNote('log-error', /^The log settings could not be saved: /.test(e.message) ? e.message : 'The log settings could not be saved: ' + e.message);
        }
        if (openPanel === 'log') {
            // Read again whatever was read last: the lines are other ones, or are drawn another way.
            panelKey = null;
            await loadPanel();
        }
    }

    function cells(className, values, marked, failed, title, wide, markClass) {
        var row = document.createElement('div');
        row.className = className + ' cols-' + values.length + (marked ? ' ' + (markClass || 'mark') : '') + (wide ? ' date' : '');
        if (title) {
            row.title = title;
        }
        values.forEach(function (value, i) {
            var cell = document.createElement('span');
            cell.textContent = value;
            if (failed && i === 1) {
                cell.className = 'failed';
            }
            row.append(cell);
        });
        return row;
    }

    function showPanel(shown) {
        var key = JSON.stringify(shown);
        setNote('panel-error', '');
        if (key === panelKey) {
            return;
        }
        panelKey = key;
        var box = $('panel-lines');
        var scrolled = box.scrollTop;
        var perRow = box.scrollHeight && panelRows.length ? box.scrollHeight / (panelRows.length + (panelHasHeader ? 1 : 0)) : 0;
        var rowKeys = shown.rows.map(function (row) { return row.join('\u0000'); });
        box.replaceChildren();
        if (shown.header) {
            // The first child of the box, kept at its top as the rows scroll under it.
            box.append(cells('row head', shown.header, false, false, '', shown.wide));
        }
        shown.rows.forEach(function (row, i) {
            box.append(cells('row', row, shown.marks && shown.marks[i], shown.failed && shown.failed[i], shown.titles && shown.titles[i], shown.wide, shown.markClass));
        });
        // Rows that came in above what the person was reading push it down; follow it.
        var added = panelRows.length ? rowKeys.indexOf(panelRows[0]) : 0;
        box.scrollTop = scrolled > 0 && added > 0 ? scrolled + added * perRow : scrolled;
        panelRows = rowKeys;
        panelHasHeader = Boolean(shown.header);
        if (openPanel === 'history') {
            measureTable(shown);
        }
        show('panel-lines', shown.rows.length > 0);
        // A note that says something is left out, or that there is nothing to see, is in the info colour.
        setNote('panel-note', shown.note, 'panel-note' + (shown.noteHighlight ? ' highlight' : ''));
    }

    /**
     * Measures how wide the history table needs to be, in a copy of it that nothing constrains: a row in the panel
     * stretches to the window, so it could only report the window's own width. The copy has the header and the
     * few rows with the most text, in the same classes, so each grid takes its content's width.
     */
    function measureTable(shown) {
        var probe = $('table-probe');
        probe.replaceChildren();
        if (!shown.header || shown.rows.length === 0) {
            return;
        }
        probe.append(cells('row head', shown.header, false, false, '', shown.wide));
        shown.rows
            .map(function (row) { return { row: row, size: row.join('').length }; })
            .sort(function (a, b) { return b.size - a.size; })
            .slice(0, 3)
            .forEach(function (entry) { probe.append(cells('row', entry.row, false, false, '', shown.wide)); });
        tableWidth = Math.ceil(probe.getBoundingClientRect().width);
    }

    /**
     * The table's width as it is now. It is read again every time the host asks for the size, not only when the rows were
     * put on the page: a font that is loaded after the first layout changes the width, and the window must follow.
     */
    function currentTableWidth() {
        var probe = $('table-probe');
        if (probe.children.length > 0) {
            tableWidth = Math.ceil(probe.getBoundingClientRect().width);
        }
        return tableWidth;
    }

    /** Reads the open panel again when there may be more to show: a new reading, or any time for the log. */
    function refreshPanel() {
        if (openPanel === 'log' || openPanel === 'errors' || (openPanel === 'history' && historyStamp() !== panelMark)) {
            loadPanel();
        }
    }

    function labelButtons() {
        Object.keys(PANELS).forEach(function (name) {
            var spec = PANELS[name];
            var label = openPanel === name ? spec.hide : spec.show;
            var button = $(spec.button);
            button.title = label;
            button.setAttribute('aria-label', label);
            button.setAttribute('aria-expanded', String(openPanel === name));
            // The button of the panel that is shown is green; closing it, or showing another, gives it back its colour.
            button.className = 'icon tiny' + (spec.extra || '') + (openPanel === name ? ' active' : '');
        });
    }

    /**
     * Opens the panel, or closes it if it is the one shown; opening one replaces the other. The settings are a panel that remembers
     * what it replaced: leaving them by Apply, Cancel or the gear shows that again, freshly loaded so that it has the new settings.
     * Another panel's button while the settings are shown shows that one, and what the settings replaced is forgotten.
     *
     * Leaving the settings, or the debug settings, by a button of the row, their own or another panel's, saves them first, as
     * Apply does, when anything in them was changed. If that fails they stay, with the reason, and the other panel is not shown.
     */
    async function togglePanel(name) {
        var form = FORMS[openPanel];
        if (form) {
            var changed = form.changed();
            if (changed && !(await form.save())) {
                return;
            }
            leaveForm(name === openPanel ? null : name, changed);
            return;
        }
        if (FORMS[name]) {
            returnTo = openPanel;
            showPanelNamed(name);
            return;
        }
        returnTo = null;
        showPanelNamed(openPanel === name ? null : name);
    }

    /** Shows the named panel, or none for null, in the one panel area, loading what it shows afresh. */
    function showPanelNamed(name) {
        openPanel = name;
        // A preview belongs to the settings as they were opened: it ends when they are left, and a new one begins with the first click.
        preview = null;
        panelKey = null;
        // Nothing of this panel has been read yet, whatever stamp the one before was read at.
        panelMark = null;
        panelRows = [];
        panelHasHeader = false;
        $('panel-lines').replaceChildren();
        setNote('panel-note', '');
        setNote('panel-error', '');
        // The log panel comes up with what is saved: its controls are shown when the first answer has said what that is.
        logKnown = false;
        logUnsaved = false;
        logToTop = false;
        logLevel = null;
        show('log-controls', false);
        setNote('log-error', '');
        labelButtons();
        show('panel', panelIsOpen());
        // The settings are a form in the same place, not lines; the others are lines.
        show('settings-view', openPanel === 'settings');
        show('debug-view', openPanel === 'debug');
        if (openPanel === 'debug') {
            show('panel-lines', false);
            loadDebug();
        } else {
            debugShown = null;
        }
        if (openPanel === 'settings') {
            show('panel-lines', false);
            loadSettings();
        } else {
            settingsShown = null;
            settingsDefaults = null;
        }
        // The window's height while a log or history is shown is fixed at what it was when it opened (ten rows),
        // so that a message line coming or going does not move it; the panel takes up the difference.
        panelHeight = 10 * Math.ceil($('top').getBoundingClientRect().height);
        applyAppClass();
        // The row changes with the panel (the buttons that are switched off come and go), at once and not at the next poll.
        render();
        loadPanel();
    }

    // ---- the window host

    /**
     * The size, in CSS pixels, the window needs to show all of this page, and what of it the person
     * may drag: `width,height,resizable`. The Java host asks for it and resizes the window to
     * match; see docs/api.md. It is the page's own size, not the window's, so asking does not
     * change it. With a panel shown the height is ten times the row's, and the height is the
     * person's to change from there; with the log the width is three times the row's, and that is
     * theirs too. The page never reports the size the window was dragged to.
     */
    /**
     * Whether a press of the mouse at this place of the page, in CSS pixels, may begin a move of the window. The host asks
     * when the window has no title bar, and moves it by a drag that begins where this says yes: anywhere but on something
     * that is pressed for its own sake, a button of the row or a control. A press there does what it always did.
     */
    window.dragStartsAt = function (x, y) {
        var at = document.elementFromPoint(x, y);
        return !(at && at.closest && at.closest('button, select, input, textarea, label, a'));
    };

    window.contentSize = function () {
        markNoted();
        // While a panel is shown the row is stretched to the window, so its own width is measured with the stretch let go
        // of for the moment: the size asked for must never follow from the size the window has.
        var top = $('top');
        var stretched = openPanel !== null;
        if (stretched) {
            top.style.width = 'max-content';
            top.style.maxWidth = '900px';
        }
        var box = top.getBoundingClientRect();
        box = { width: box.width, height: box.height };
        if (stretched) {
            top.style.width = '';
            top.style.maxWidth = '';
        }
        // The red border of the fake backend is round everything: the window is larger by it, so that nothing is covered.
        // So is the border of a window with no title bar, which is one pixel.
        var edge = fakeInUse() ? 6 : bare() ? 2 : 0;
        var width = Math.ceil(box.width) + edge;
        var height = Math.ceil(box.height) + edge;
        if (openPanel === 'debug') {
            return Math.max(width, formWidth('debug-form', 'debug-buttons')) + ',' + Math.ceil($('app').getBoundingClientRect().height) + ',3,debug';
        }
        if (openPanel === 'settings') {
            // As tall as the row, its message lines and the settings need, whole, and never narrower than the settings need
            // for every text to be on one line and the buttons in one row; the flag says it is not resizable, and the name that a panel is open.
            return Math.max(width, formWidth('settings-form', 'settings-buttons')) + ',' + Math.ceil($('app').getBoundingClientRect().height) + ',3,settings';
        }
        // The last field names the panel, so that the host remembers the height of the history and of the log.
        if (openPanel === 'log') {
            return (3 * width + scrollbarWidth) + ',' + panelHeight + ',2,log';
        }
        if (openPanel === 'history') {
            // As wide as the table needs (16 px is the panel's padding), never narrower than the row.
            return (Math.max(width, currentTableWidth() + 16) + scrollbarWidth) + ',' + panelHeight + ',1,history';
        }
        if (openPanel === 'errors') {
            // Half as wide as the log; a long message scrolls sideways.
            return (Math.ceil(1.5 * width) + scrollbarWidth) + ',' + panelHeight + ',2,errors';
        }
        return width + ',' + height + ',0';
    };

    /**
     * How wide the window has to be for the settings, or the debug settings: the form at the width of its own content, and the buttons of the view, each
     * with the room there is at its left again at its right. The form is as wide as the window when the window is the wider, so it
     * is let go of that for the moment of the measuring, and the answer does not depend on how wide the window happens to be.
     */
    function formWidth(formId, buttonsId) {
        var left = $('app').getBoundingClientRect().left || 0;
        var form = $(formId);
        form.style.minWidth = '0';
        var widths = [form, $(buttonsId)].map(function (element) {
            var box = element.getBoundingClientRect();
            return box.width + 2 * Math.max(0, (box.left || 0) - left);
        });
        form.style.minWidth = '';
        return Math.ceil(Math.max(widths[0], widths[1]));
    }

    /**
     * How wide the vertical scrollbar of the log and the history is, measured with a box that always has one. The
     * panel reserves that room, so the window is that much wider than the row and no column is covered.
     */
    function measureScrollbar() {
        if (!document.body) {
            return 0;
        }
        var probe = document.createElement('div');
        probe.className = 'scrollbar-probe';
        document.body.append(probe);
        var width = probe.offsetWidth - probe.clientWidth;
        document.body.removeChild(probe);
        return width > 0 && width < 100 ? width : 0;
    }

    // ---- start

    async function start() {
        try {
            config = await request('/api/config');
        } catch (e) {
            showConnection(true);
            setTimeout(start, RETRY_START_MS);
            return;
        }
        showConnection(false);
        scrollbarWidth = measureScrollbar();
        // The debug button is there in debug mode, always, and never otherwise.
        show('debug-button', Boolean(config.debug));
        $('debug-button').addEventListener('click', function () { togglePanel('debug'); });
        $('debug-apply').addEventListener('click', applyDebug);
        $('debug-cancel').addEventListener('click', cancelDebug);

        $('log-button').addEventListener('click', function () { togglePanel('log'); });
        $('log-level').addEventListener('change', changeLogChoices);
        $('log-date').addEventListener('change', changeLogChoices);
        $('history-button').addEventListener('click', function () { togglePanel('history'); });
        $('settings-button').addEventListener('click', function () { togglePanel('settings'); });
        $('settings-apply').addEventListener('click', applySettings);
        $('errors-button').addEventListener('click', function () { togglePanel('errors'); });
        $('countdown').addEventListener('mouseenter', function () { hoveringCountdown = true; render(); });
        $('countdown').addEventListener('mouseleave', function () { hoveringCountdown = false; render(); });
        $('settings-restore').addEventListener('click', restoreDefaults);
        $('set-all').addEventListener('click', selectAllOrNone);
        $('settings-cancel').addEventListener('click', cancelSettings);
        MAIN_FLAGS.forEach(function (key) { $('set-' + key).addEventListener('change', previewNow); });
        $('set-timeFormat').addEventListener('change', previewNow);
        $('refresh').addEventListener('click', async function () {
            try {
                await postJson('/api/refresh', {});
            } catch (e) {
                showConnection(true);
            }
            pollNow();
        });
        // Keeps the reset countdowns moving between polls, without asking the backend.
        setInterval(render, 1000);
        poll();
    }

    start();
})();
