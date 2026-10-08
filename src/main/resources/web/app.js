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

        $('app').className = v.stale ? 'stale' : '';
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

    function openConfig() {
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

    // ---- the window host

    /**
     * The size, in CSS pixels, the window needs to show all of this page. The
     * Java host asks for it and resizes the window to match; see docs/api.md.
     * It is the page's own size, not the window's, so asking does not change it.
     */
    window.contentSize = function () {
        var box = $('app').getBoundingClientRect();
        return Math.ceil(box.width) + ',' + Math.ceil(box.height);
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
