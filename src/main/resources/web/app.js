// The page: asks the backend for its settings once, then polls its status at
// the interval it reported. All the logic about what to show is in view.js;
// this file only talks to the backend and puts text on the page. Text from the
// backend is only ever assigned with textContent, never parsed as HTML.
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

    function $(id) {
        return document.getElementById(id);
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
            $('connection').hidden = true;
            render();
        } catch (e) {
            $('connection').hidden = false;
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

        var badge = $('badge');
        badge.className = 'badge badge-' + v.badge.kind;
        badge.textContent = v.badge.text;

        $('fetched').hidden = !v.fetched;
        $('fetched').textContent = v.fetched || '';
        $('refreshing').textContent = v.refreshing ? 'Refreshing…' : '';

        var notice = $('notice');
        notice.hidden = !v.notice;
        if (v.notice) {
            notice.className = 'notice' + (v.notice.kind === 'error' ? ' notice-error' : '');
            $('notice-title').textContent = v.notice.title;
            $('notice-detail').textContent = v.notice.detail;
            $('notice-at').textContent = v.notice.at;
        }

        renderSpend(v.spend);
        renderWindows(v.windows);
        $('empty').hidden = !v.empty;
    }

    function renderSpend(spend) {
        $('spend').hidden = !spend;
        if (!spend) {
            return;
        }
        $('spend-used').textContent = spend.used;
        $('spend-limit').textContent = spend.limit;
        $('spend-percent').textContent = spend.percentText + ' used';
        $('spend-bar').style.width = (spend.barPercent === null ? 0 : spend.barPercent) + '%';
        var bar = $('spend').querySelector('.bar');
        if (spend.barPercent === null) {
            bar.removeAttribute('aria-valuenow');
        } else {
            bar.setAttribute('aria-valuenow', String(spend.barPercent));
        }
        var pill = $('spend-severity');
        pill.hidden = !spend.severityText;
        pill.textContent = spend.severityText || '';
        pill.className = 'pill' + (spend.severityKind ? ' pill-' + spend.severityKind : '');
    }

    function renderWindows(windows) {
        $('windows').hidden = windows.length === 0;
        // Rebuilt only when something changed, so the once-a-second clock refresh
        // does not disturb the page.
        var key = JSON.stringify(windows);
        if (key === lastWindowsKey) {
            return;
        }
        lastWindowsKey = key;

        var list = $('window-list');
        list.replaceChildren();
        windows.forEach(function (w) {
            var item = document.createElement('li');

            var head = document.createElement('div');
            head.className = 'window-head';
            var name = document.createElement('span');
            name.className = 'window-name';
            name.textContent = w.name;
            var value = document.createElement('span');
            value.className = 'window-value';
            value.textContent = w.utilizationText;
            head.append(name, value);

            var bar = document.createElement('div');
            bar.className = 'bar';
            bar.setAttribute('role', 'progressbar');
            bar.setAttribute('aria-label', w.name);
            bar.setAttribute('aria-valuemin', '0');
            bar.setAttribute('aria-valuemax', '100');
            bar.setAttribute('aria-valuenow', String(w.barPercent));
            var fill = document.createElement('div');
            fill.className = 'bar-fill';
            fill.style.width = w.barPercent + '%';
            bar.append(fill);

            var resets = document.createElement('p');
            resets.className = 'window-resets';
            resets.textContent = w.resetsText;

            item.append(head, bar, resets);
            list.append(item);
        });
    }

    // ---- settings

    var FIELDS = [
        { input: 'usage-interval', hint: 'usage-hint', key: 'usageIntervalSeconds', limits: 'usageIntervalSeconds',
          label: 'The usage interval', note: 'How often the application asks Anthropic. ' },
        { input: 'poll-interval', hint: 'poll-hint', key: 'pollIntervalSeconds', limits: 'pollIntervalSeconds',
          label: 'The update interval', note: 'How often this window asks the application. ' }
    ];

    function showConfig() {
        FIELDS.forEach(function (field) {
            var limits = config.limits[field.limits];
            $(field.input).value = String(config[field.key]);
            $(field.hint).textContent = field.note + limits.min + ' to ' + limits.max + ' seconds.';
        });
    }

    function message(text, kind) {
        var element = $('settings-message');
        element.textContent = text;
        element.className = 'settings-message' + (kind ? ' ' + kind : '');
    }

    async function commit(field) {
        var input = $(field.input);
        var problem = view.checkInterval(input.value, config.limits[field.limits], field.label);
        if (problem) {
            input.setAttribute('aria-invalid', 'true');
            message(problem, 'error');
            return;
        }
        input.removeAttribute('aria-invalid');

        var value = Number(input.value.trim());
        if (value === config[field.key]) {
            input.value = String(value);
            message('', '');
            return;
        }
        try {
            var change = {};
            change[field.key] = value;
            var updated = await postJson('/api/config', change);
            var pollChanged = updated.pollIntervalSeconds !== config.pollIntervalSeconds;
            config = updated;
            showConfig();
            message('Saved.', 'saved');
            if (pollChanged) {
                // The new rhythm takes effect now, not after the old wait runs out.
                pollNow();
            }
        } catch (e) {
            input.value = String(config[field.key]);
            message(e.message, 'error');
        }
    }

    // ---- start

    async function start() {
        try {
            config = await request('/api/config');
        } catch (e) {
            $('connection').hidden = false;
            setTimeout(start, RETRY_START_MS);
            return;
        }
        $('connection').hidden = true;
        showConfig();
        FIELDS.forEach(function (field) {
            $(field.input).addEventListener('change', function () { commit(field); });
        });
        $('refresh').addEventListener('click', async function () {
            try {
                await postJson('/api/refresh', {});
            } catch (e) {
                $('connection').hidden = false;
            }
            pollNow();
        });
        // Keeps "5 s ago" and the reset countdowns moving between polls, without asking the backend.
        setInterval(render, 1000);
        poll();
    }

    start();
})();
