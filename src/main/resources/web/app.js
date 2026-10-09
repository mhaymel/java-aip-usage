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

    // The one setting the window offers. How often the window itself updates is not
    // a setting: it comes from the command line and is only read here.
    var INTERVAL = { input: 'usage-interval', key: 'usageIntervalSeconds', label: 'The usage interval' };

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
        var v = view.describeStatus(lastStatus, Date.now());

        lastStale = v.stale;
        applyAppClass();
        $('refresh').className = 'icon' + (v.refreshing ? ' busy' : '');

        show('time', Boolean(v.time));
        $('time').textContent = v.time || '';
        $('time').title = v.timeTooltip || 'Last update';

        show('spend', Boolean(v.spend));
        if (v.spend) {
            $('spend').className = 'spend' + (v.spend.severityKind ? ' sev-' + v.spend.severityKind : '');
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

        setNote('note', v.message && v.message.text, v.message ? 'note note-' + v.message.kind : null);
    }

    function applyAppClass() {
        $('app').className = (lastStale ? 'stale' : '') + (panelIsOpen() ? ' open' : '');
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

    // ---- config: a field that appears on demand

    function configIsOpen() {
        return !$('config').hidden;
    }

    /**
     * Asks the backend for its interval every time, since it may have changed since the page
     * started. If it cannot be asked, the last value known is shown.
     */
    async function openConfig() {
        try {
            config = await request('/api/config');
        } catch (e) {
            // Keep what we had; the lost-contact banner is the poll's to show.
        }
        $(INTERVAL.input).value = String(config[INTERVAL.key]);
        $(INTERVAL.input).removeAttribute('aria-invalid');
        setNote('config-note', '');
        show('config', true);
        $('config-toggle').setAttribute('aria-expanded', 'true');
        $(INTERVAL.input).focus();
    }

    function closeConfig() {
        show('config', false);
        setNote('config-note', '');
        $(INTERVAL.input).removeAttribute('aria-invalid');
        $('config-toggle').setAttribute('aria-expanded', 'false');
    }

    function toggleConfig() {
        if (configIsOpen()) {
            closeConfig();
        } else {
            openConfig();
        }
    }

    /** Checks the value, then sends it if it changed. */
    async function confirmConfig() {
        var problem = view.checkInterval($(INTERVAL.input).value, config.limits[INTERVAL.key], INTERVAL.label);
        if (problem) {
            $(INTERVAL.input).setAttribute('aria-invalid', 'true');
            setNote('config-note', problem);
            return;
        }
        $(INTERVAL.input).removeAttribute('aria-invalid');

        var value = Number($(INTERVAL.input).value.trim());
        if (value === config[INTERVAL.key]) {
            closeConfig();
            return;
        }

        try {
            var change = {};
            change[INTERVAL.key] = value;
            config = await postJson('/api/config', change);
            closeConfig();
        } catch (e) {
            setNote('config-note', e.message);
        }
    }

    function onConfigKey(event) {
        if (event.key === 'Enter') {
            event.preventDefault();
            confirmConfig();
        } else if (event.key === 'Escape') {
            event.preventDefault();
            closeConfig();
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
        if (panelLoading || !openPanel) {
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

    function cells(className, values, marked) {
        var row = document.createElement('div');
        row.className = className + ' cols-' + values.length + (marked ? ' mark' : '');
        values.forEach(function (value) {
            var cell = document.createElement('span');
            cell.textContent = value;
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
            box.append(cells('row head', shown.header));
        }
        shown.rows.forEach(function (row, i) {
            box.append(cells('row', row, shown.marks && shown.marks[i]));
        });
        // Rows that came in above what the person was reading push it down; follow it.
        var added = panelRows.length ? rowKeys.indexOf(panelRows[0]) : 0;
        box.scrollTop = scrolled > 0 && added > 0 ? scrolled + added * perRow : scrolled;
        panelRows = rowKeys;
        panelHasHeader = Boolean(shown.header);
        show('panel-lines', shown.rows.length > 0);
        setNote('panel-note', shown.note);
    }

    /** Reads the open panel again when there may be more to show: a new reading, or any time for the log. */
    function refreshPanel() {
        if (openPanel === 'log' || (openPanel === 'history' && fetchedAt() !== panelMark)) {
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
        });
    }

    /** Opens the panel, or closes it if it is the one shown; opening one replaces the other. */
    function togglePanel(name) {
        openPanel = openPanel === name ? null : name;
        panelKey = null;
        panelRows = [];
        panelHasHeader = false;
        $('panel-lines').replaceChildren();
        setNote('panel-note', '');
        setNote('panel-error', '');
        labelButtons();
        show('panel', panelIsOpen());
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
        if (openPanel === 'log') {
            return 3 * width + ',' + 10 * height + ',2';
        }
        if (openPanel === 'history') {
            return width + ',' + 10 * height + ',1';
        }
        return width + ',' + height + ',0';
    };

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

        $('log-button').addEventListener('click', function () { togglePanel('log'); });
        $('history-button').addEventListener('click', function () { togglePanel('history'); });
        $('config-toggle').addEventListener('click', toggleConfig);
        $('config-ok').addEventListener('click', confirmConfig);
        $(INTERVAL.input).addEventListener('keydown', onConfigKey);
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
