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
    var lastWindowsKey = null;
    var lastStale = false;
    // Which panel is shown below the strip: 'history', 'log', or none.
    var openPanel = null;
    var panelLoading = false;
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
            show('connection', false);
            render();
            refreshPanel();
        } catch (e) {
            show('connection', true);
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

        lastStale = v.stale;
        applyAppClass();
        $('refresh').className = 'icon' + (v.refreshing ? ' busy' : '');

        show('time', Boolean(v.time));
        $('time').textContent = v.time || '';
        $('time').title = v.timeTooltip || 'Last update';

        show('spend', Boolean(v.spend));
        if (v.spend) {
            $('spend').className = 'spend' + (v.spend.severityKind ? ' sev-' + v.spend.severityKind : '');
            show('percent', Boolean(v.show.percentage && v.spend.percentText));
            $('percent').textContent = v.spend.percentText || '';
            $('percent').title = v.spend.percentTooltip;
            $('used').textContent = v.spend.used;
            $('used').title = v.spend.usedTooltip;
            $('limit').textContent = v.spend.limit;
            $('limit').title = v.spend.limitTooltip;
        }

        renderWindows(v.windows);

        show('placeholder', Boolean(v.placeholder));
        $('placeholder').textContent = v.placeholder || '';

        show('countdown', Boolean(v.countdown));
        $('countdown').textContent = v.countdown ? v.countdown.text : '';
        $('countdown').title = v.countdown ? v.countdown.tooltip : '';
        // While the server asks us to slow down (HTTP 429) the countdown is red and, hovered, says why in bold red.
        $('countdown').className = 'countdown' + (v.countdownAlert ? ' alert' : '');
        setNote('alert-note', hoveringCountdown && v.countdownAlert ? v.countdownAlert : '');
        renderOptional('interval', v.show.interval && v.interval);
        renderOptional('delta-used', v.show.deltaUsed && v.deltaUsed);
        renderOptional('delta-time', v.show.deltaTime && v.deltaTime);

        setNote('note', v.message && v.message.text, v.message ? 'note note-' + v.message.kind : null);
    }

    /** An optional item of the row: shown only when its setting is on and the backend has a value for it. */
    function renderOptional(id, item) {
        show(id, Boolean(item));
        $(id).textContent = item ? item.text : '';
        $(id).title = item ? item.tooltip : '';
    }

    function applyAppClass() {
        // The log and the history fill the window; the settings do not, so that the page's own height is the content's.
        $('app').className = (lastStale ? 'stale' : '')
            + (openPanel === 'log' || openPanel === 'history' || openPanel === 'errors' ? ' open' : '')
            + (openPanel === 'settings' ? ' fit' : '');
    }

    function renderWindows(windows) {
        show('windows', windows.length > 0);
        // Rebuilt only when something changed, so the once-a-second clock refresh
        // does not disturb the page.
        var key = JSON.stringify(windows);
        if (key === lastWindowsKey) {
            return;
        }
        lastWindowsKey = key;

        var container = $('windows');
        container.replaceChildren();
        windows.forEach(function (w) {
            var item = document.createElement('span');
            item.className = 'win';
            var name = document.createElement('span');
            name.className = 'win-name';
            name.textContent = w.name;
            var value = document.createElement('span');
            value.className = 'win-value';
            value.textContent = w.utilizationText;
            var resets = document.createElement('span');
            resets.className = 'win-reset';
            resets.textContent = w.resetsText;
            item.append(value, name, resets);
            container.append(item);
        });
    }

    // ---- the settings view: a form the backend fills in each time it is opened

    /** The settings as the backend gave them when the view was opened, to tell whether the form has unapplied changes. */
    var settingsShown = null;
    var settingsDefaults = null;

    var SETTING_FLAGS = ['showPercentage', 'showInterval', 'showDeltaUsed', 'showDeltaTime', 'historyDate', 'historyZeroLines', 'historyFailedLines', 'historyDeltaUsed', 'historyDeltaTime', 'logResponse'];

    /**
     * The interval is typed in a box; a dropdown beside it offers the usual values, and picking one fills the box. The box
     * always shows the value in force, even one that is not a choice, which is what the backend has.
     */
    function fillInterval(choices, current) {
        var select = $('set-intervalChoices');
        select.replaceChildren();
        var prompt = document.createElement('option');
        prompt.value = '';
        prompt.textContent = 'choose';
        select.append(prompt);
        choices.forEach(function (seconds) {
            var option = document.createElement('option');
            option.value = String(seconds);
            option.textContent = seconds + ' s';
            select.append(option);
        });
        select.value = '';
        $('set-usageIntervalSeconds').value = String(current);
    }

    /** Picking a choice puts it in the box; the dropdown goes back to its prompt. */
    function onIntervalChoice() {
        var select = $('set-intervalChoices');
        if (select.value !== '') {
            $('set-usageIntervalSeconds').value = select.value;
        }
        select.value = '';
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
            fillInterval(body.intervalChoices, body.settings.usageIntervalSeconds);
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

    async function applySettings() {
        if (settingsShown === null) {
            return;
        }
        // Only that it is a number is checked here; whether it is in range is the backend's to say.
        if (!/^\d+$/.test($('set-usageIntervalSeconds').value.trim())) {
            setNote('settings-error', 'The interval must be a whole number of seconds.');
            return;
        }
        try {
            var body = await postJson('/api/settings', readForm());
            settingsShown = body.settings;
            settingsDefaults = body.defaults;
            // Applied: the view closes and the row shows the change at once.
            if (openPanel === 'settings') {
                togglePanel('settings');
            }
            pollNow();
        } catch (e) {
            setNote('settings-error', e.message);
        }
    }

    /** Fills the form with the defaults the backend gave; they take effect only when Apply is pressed. */
    function restoreDefaults() {
        if (settingsDefaults) {
            fillForm(settingsDefaults);
        }
    }

    /** The main-view switches all on, or all off; the form only, until Apply. */
    function setMainView(all) {
        ['showPercentage', 'showInterval', 'showDeltaUsed', 'showDeltaTime'].forEach(function (key) {
            $('set-' + key).checked = all;
        });
        $('set-timeFormat').value = all ? 'hh:mm:ss' : 'hh:mm';
    }

    /** Cancel: closes the view and changes nothing; it asks nothing, since nothing was sent. */
    function cancelSettings() {
        if (openPanel === 'settings') {
            togglePanel('settings');
        }
    }

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

    function fetchedAt() {
        return lastStatus && lastStatus.usage ? lastStatus.usage.fetched_at : null;
    }

    function panelIsOpen() {
        return openPanel !== null;
    }

    /**
     * Reads what the open panel shows and puts it on the page. The lines are replaced only when they
     * changed, and what the person has scrolled to stays where it is, though lines come in above it.
     */
    async function loadPanel() {
        if (panelLoading || !openPanel || openPanel === 'settings') {
            return;
        }
        var name = openPanel;
        var spec = PANELS[name];
        panelLoading = true;
        panelMark = fetchedAt();
        try {
            var shown = spec.describe(await request(spec.path));
            if (name === openPanel) {
                showPanel(shown);
            }
        } catch (e) {
            // What was shown stays; only the error is added.
            if (name === openPanel) {
                setNote('panel-error', spec.failure + e.message);
            }
        } finally {
            panelLoading = false;
        }
    }

    function cells(className, values, marked, failed, title, wide) {
        var row = document.createElement('div');
        row.className = className + ' cols-' + values.length + (marked ? ' mark' : '') + (wide ? ' date' : '');
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
            box.append(cells('row', row, shown.marks && shown.marks[i], shown.failed && shown.failed[i], shown.titles && shown.titles[i], shown.wide));
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
        setNote('panel-note', shown.note);
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
        if (openPanel === 'log' || openPanel === 'errors' || (openPanel === 'history' && fetchedAt() !== panelMark)) {
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
            button.className = 'icon tiny' + (openPanel === name ? ' active' : '');
        });
    }

    /**
     * Opens the panel, or closes it if it is the one shown; opening one replaces the other. The settings are a panel that remembers
     * what it replaced: leaving them (Apply, Cancel, the button again) shows that again, freshly loaded so that it has the new settings.
     * Another panel's button while the settings are shown shows that one, and what the settings replaced is forgotten.
     */
    function togglePanel(name) {
        if (name === 'settings') {
            if (openPanel === 'settings') {
                var back = returnTo;
                returnTo = null;
                showPanelNamed(back);
            } else {
                returnTo = openPanel;
                showPanelNamed('settings');
            }
            return;
        }
        returnTo = null;
        showPanelNamed(openPanel === name ? null : name);
    }

    /** Shows the named panel, or none for null, in the one panel area, loading what it shows afresh. */
    function showPanelNamed(name) {
        openPanel = name;
        panelKey = null;
        panelRows = [];
        panelHasHeader = false;
        $('panel-lines').replaceChildren();
        setNote('panel-note', '');
        setNote('panel-error', '');
        labelButtons();
        show('panel', panelIsOpen());
        // The settings are a form in the same place, not lines; the others are lines.
        show('settings-view', openPanel === 'settings');
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
    window.contentSize = function () {
        var box = $('top').getBoundingClientRect();
        var width = Math.ceil(box.width);
        var height = Math.ceil(box.height);
        if (openPanel === 'settings') {
            // As tall as the row, its message lines and the settings need, whole; the flag says it is not resizable.
            return width + ',' + Math.ceil($('app').getBoundingClientRect().height) + ',3';
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
            show('connection', true);
            setTimeout(start, RETRY_START_MS);
            return;
        }
        show('connection', false);
        scrollbarWidth = measureScrollbar();

        $('log-button').addEventListener('click', function () { togglePanel('log'); });
        $('history-button').addEventListener('click', function () { togglePanel('history'); });
        $('settings-button').addEventListener('click', function () { togglePanel('settings'); });
        $('settings-apply').addEventListener('click', applySettings);
        $('set-intervalChoices').addEventListener('change', onIntervalChoice);
        $('errors-button').addEventListener('click', function () { togglePanel('errors'); });
        $('countdown').addEventListener('mouseenter', function () { hoveringCountdown = true; render(); });
        $('countdown').addEventListener('mouseleave', function () { hoveringCountdown = false; render(); });
        $('settings-restore').addEventListener('click', restoreDefaults);
        $('settings-cancel').addEventListener('click', cancelSettings);
        $('settings-maximum').addEventListener('click', function () { setMainView(true); });
        $('settings-minimum').addEventListener('click', function () { setMainView(false); });
        $('refresh').addEventListener('click', async function () {
            try {
                await postJson('/api/refresh', {});
            } catch (e) {
                show('connection', true);
            }
            pollNow();
        });
        // Keeps the reset countdowns moving between polls, without asking the backend.
        setInterval(render, 1000);
        poll();
    }

    start();
})();
