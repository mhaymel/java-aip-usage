'use strict';

// Drives app.js with a fake DOM and a fake backend. The fake document only knows
// the ids that index.html really declares, so a misspelt id fails here rather
// than silently doing nothing in the window.
process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const { withDisplay } = require('./fake-display.cjs');

const WEB = path.join(__dirname, '../../main/resources/web');
const INDEX_HTML = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
const INDEX_IDS = [...INDEX_HTML.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
// What index.html declares hidden, so the fake page starts the way the real one does.
const HIDDEN_AT_START = new Set([...INDEX_HTML.matchAll(/<[^>]*\sid="([^"]+)"[^>]*\shidden[\s>]/g)].map(m => m[1]));

const CONFIG = {
    usageIntervalSeconds: 60,
    pollIntervalSeconds: 1,
    limits: { usageIntervalSeconds: { min: 5, max: 3600 } },
};
const NOW = '2026-10-08T14:24:53Z';
const SPEND_STATUS = {
    refreshing: false, stale: false, nextRefreshInSeconds: 42, error: null,
    usage: {
        source: 'anthropic-oauth-usage', fetched_at: NOW,
        spend: { used: 186.02, limit: 1000, currency: 'USD', percent: 19, severity: 'normal' }, windows: [],
    },
};
// A reading of an account that reports no usage: no spend.
const NO_USAGE_STATUS = {
    refreshing: false, stale: false, nextRefreshInSeconds: 17, error: null,
    usage: { source: 'anthropic-oauth-usage', fetched_at: NOW, spend: null, windows: [] },
};

const scrollbarOf = { width: 0 };

function element(id) {
    const el = {
        id, hidden: HIDDEN_AT_START.has(id), textContent: '', className: '', value: '', title: '', children: [], attrs: {}, listeners: {},
        focused: false, scrollTop: 0, scrollHeight: 0, style: {}, rect: id === 'table-probe' ? { width: 0, height: 0 } : { width: 399.2, height: 41.5 },
        setAttribute(k, v) { this.attrs[k] = String(v); },
        removeAttribute(k) { delete this.attrs[k]; },
        addEventListener(type, fn) { this.listeners[type] = fn; },
        append(...nodes) { this.children.push(...nodes); },
        // Emptying a scrolled box sends it back to the top, as in a browser.
        replaceChildren(...nodes) { this.children = [...nodes]; this.scrollTop = 0; },
        focus() { this.focused = true; },
        getBoundingClientRect() { return this.rect; },
    };
    // The page must never hand backend text to the HTML parser.
    Object.defineProperty(el, 'innerHTML', { set() { throw new Error('innerHTML must not be used'); } });
    Object.defineProperty(el, 'offsetWidth', { get() { return 100; } });
    Object.defineProperty(el, 'clientWidth', { get() { return 100 - (this.className === 'scrollbar-probe' ? scrollbarOf.width : 0); } });
    return el;
}

/** Builds a fresh page and backend, loads app.js into them, and returns handles to both. */
async function load(backend, options = {}) {
    scrollbarOf.width = options.scrollbar || 0;
    const elements = new Map(INDEX_IDS.map(id => [id, element(id)]));
    const timers = [];
    const calls = [];
    let nextTimer = 1;

    global.document = {
        getElementById(id) {
            if (!elements.has(id)) {
                throw new Error('index.html has no element with id "' + id + '"');
            }
            return elements.get(id);
        },
        createElement: tag => element('<' + tag + '>'),
        // Only the scrollbar probe reads these: a box that always has a scrollbar of the width the test gives it.
        body: { append() {}, removeChild() {} },
        // The page listens here for the key that selects everything, and for a selection that is begun.
        listeners: {},
        addEventListener(type, fn) { this.listeners[type] = fn; },
        // The page sets the size of the lists' text here, as a property of the style sheet.
        documentElement: { style: { properties: {}, setProperty(name, value) { this.properties[name] = value; } } },
    };
    const opened = [];
    global.window = { UsageView: require(path.join(WEB, 'view.js')), open: (...args) => { opened.push(args); } };
    global.setTimeout = (fn, ms) => { timers.push({ fn, ms, id: nextTimer, live: true }); return nextTimer++; };
    global.clearTimeout = id => { const t = timers.find(x => x.id === id); if (t) t.live = false; };
    global.setInterval = () => 0;
    global.fetch = async (url, options) => {
        const call = { url, method: (options && options.method) || 'GET', headers: (options && options.headers) || {}, body: options && options.body };
        calls.push(call);
        const r = await backend(call);
        return { ok: r.status < 400, status: r.status, json: async () => r.body };
    };

    delete require.cache[require.resolve(path.join(WEB, 'app.js'))];
    require(path.join(WEB, 'app.js'));
    await settle();
    const page = {
        el: id => elements.get(id), calls, timers, opened, window: global.window,
        posts: () => calls.filter(c => c.method === 'POST' && c.url === '/api/config'),
        firePoll: async () => {
            const next = timers.filter(t => t.live).pop();
            next.live = false;
            next.fn();
            await settle();
        },
        /** Types into a field the way a user would, then presses a key in it. */
        type: (id, value) => { elements.get(id).value = value; },
        press: async (id, key) => {
            let prevented = false;
            elements.get(id).listeners.keydown({ key, preventDefault() { prevented = true; } });
            await settle();
            return prevented;
        },
        click: async id => { await elements.get(id).listeners.click(); await settle(); },
    };
    return page;
}

async function settle() {
    for (let i = 0; i < 10; i++) {
        await new Promise(resolve => setImmediate(resolve));
    }
}

function backendOf(state) {
    return async call => {
        if (state.down) {
            throw new TypeError('Failed to fetch');
        }
        if (call.url === '/api/config' && call.method === 'GET') return { status: 200, body: state.config };
        if (call.url === '/api/config' && call.method === 'POST') return state.postConfig(JSON.parse(call.body));
        if (call.url === '/api/settings' && call.method === 'GET') return state.settings ? state.settings() : { status: 200, body: SETTINGS };
        if (call.url === '/api/settings' && call.method === 'POST') {
            return state.postSettings ? state.postSettings(JSON.parse(call.body)) : { status: 200, body: { ...SETTINGS, settings: JSON.parse(call.body) } };
        }
        if (call.url === '/api/debug' && call.method === 'GET') return state.debug ? state.debug() : { status: 200, body: DEBUG };
        if (call.url === '/api/debug' && call.method === 'POST') {
            return state.postDebug ? state.postDebug(JSON.parse(call.body)) : { status: 200, body: { ...DEBUG, settings: JSON.parse(call.body) } };
        }
        if (call.url === '/api/status') return { status: 200, body: withDisplay(state.status) };
        if (call.url === '/api/refresh') return { status: 202, body: { started: true } };
        if (call.url.split('?')[0] === '/api/log' && call.method === 'POST') {
            return state.postLog ? state.postLog(JSON.parse(call.body)) : { status: 200, body: JSON.parse(call.body) };
        }
        if (call.url.split('?')[0] === '/api/log') return state.log ? state.log(call) : { status: 200, body: LOG };
        if (call.url === '/api/text-size') return state.postTextSize ? state.postTextSize(JSON.parse(call.body)) : { status: 200, body: JSON.parse(call.body) };
        if (call.url === '/api/about') return state.about ? state.about() : { status: 200, body: ABOUT };
        if (call.url === '/api/about/open' || call.url === '/api/about/copy') {
            return state.aboutAction ? state.aboutAction(call) : { status: 200, body: { done: true } };
        }
        if (call.url === '/api/errors') return state.errors ? state.errors() : { status: 200, body: ERRORS };
        if (call.url === '/api/history') return state.history ? state.history() : { status: 200, body: HISTORY };
        return { status: 404, body: { error: 'No such endpoint.' } };
    };
}

const DEFAULT_SETTINGS = {
    usageIntervalSeconds: 60, logResponse: false, showPercentage: false, showCurrency: false, showHistoryIcon: true, showLogIcon: true, showErrorIcon: true,
    showInterval: false, showDeltaUsed: false, showDeltaTime: false,
    timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false, historyDate: false, historyZeroLines: true, historyFailedLines: true,
    showTime: true, showUsed: true, showLimit: true, showRefresh: true, showCountdown: true, showDuration: false, stayOnTop: false, showTitleBar: true, autoInterval: false,
};
const SETTINGS = {
    settings: { ...DEFAULT_SETTINGS, usageIntervalSeconds: 120, showInterval: true, timeFormat: 'hh:mm:ss' },
    defaults: DEFAULT_SETTINGS,
    limits: { usageIntervalSeconds: { min: 5, max: 3600 } },
};

// The debug settings as the backend gives them in debug mode, with the scenarios it knows.
const DEBUG = {
    settings: { fakeBackend: false, fakeScenario: 'normal', fakeToken: false },
    scenarios: ['normal', 'http-401', 'http-429', 'slow'],
};
const DEBUG_CONFIG = { ...CONFIG, debug: true };

const ERRORS = {
    entries: [
        { time: '11:34:42', message: 'Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min.' },
        { time: '11:20:01', message: 'Claude Code is not logged in. Log in, then refresh.' },
    ],
};

// A line of the history as the backend sends it, finished: the cells the table shows, whether it begins a run, whether it failed, its hover text.
const line = (cells, extra = {}) => ({ cells, start: false, failed: false, title: '', ...extra });

const HISTORY = {
    file: 'java-aip-usage.csv', exists: true, columns: ['time', 'used', 'limit', 'Cur.'], wide: false, note: null, total: 2,
    lines: [line(['20:46:11', '260.66', '1000.00', 'USD']), line(['20:44:12', '260.36', '1000.00', 'USD'])],
};

const LOG = {
    file: 'java-aip-usage.log', exists: true, truncated: false, hidden: false, level: 'info', date: true,
    lines: ['2026-10-08 19:00:00 INFO    [UsageApp] Starting', '2026-10-08 19:00:01 INFO    [UsageService] Usage refresh succeeded'],
};

// What the backend says for the About view.
const ABOUT = {
    name: 'aip usage', version: '0.57',
    files: { settings: 'C:\\work\\aip\\settings.json', log: 'C:\\work\\aip\\java-aip-usage.log', history: 'C:\\work\\aip\\java-aip-usage.csv' },
    java: '25.0.1', javafx: '25.0.4', system: 'Windows 11 10.0', debug: false, fake: false,
};

const accepting = body => ({ status: 200, body: { ...CONFIG, ...body } });

// ---- the page and the backend

test('every id the page script looks up is declared in index.html', () => {
    const script = fs.readFileSync(path.join(WEB, 'app.js'), 'utf8');
    const used = new Set([...script.matchAll(/\$\('([^']+)'\)/g)].map(m => m[1]));
    for (const m of script.matchAll(/(?:input|id): '([^']+)'/g)) used.add(m[1]);
    for (const m of script.matchAll(/(?:show|setNote)\('([^']+)'/g)) used.add(m[1]);
    for (const id of used) {
        assert.ok(INDEX_IDS.includes(id), 'missing id in index.html: ' + id);
    }
});

test('at startup it asks for the intervals first, then polls the status, and fetches nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.deepEqual(page.calls.map(c => c.url).slice(0, 2), ['/api/config', '/api/status']);
    assert.equal(page.calls.filter(c => c.url === '/api/refresh').length, 0, 'startup must not trigger a refresh');
});

test('it polls at the interval the backend reported', async () => {
    const page = await load(backendOf({ config: { ...CONFIG, pollIntervalSeconds: 7 }, status: SPEND_STATUS }));

    assert.equal(page.timers.filter(t => t.live).pop().ms, 7000);
    const before = page.calls.length;
    await page.firePoll();
    assert.equal(page.calls.length, before + 1);
    assert.equal(page.calls.at(-1).url, '/api/status');
    assert.equal(page.calls.at(-1).method, 'GET');
});

// ---- the row

test('a spend reading shows the time, spent and budget, and percent, and no class for the severity', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('time').hidden, false);
    assert.equal(page.el('time').textContent, '14:24:53');
    assert.equal(page.el('spend').hidden, false);
    assert.equal(page.el('used').textContent, '186.02');
    assert.equal(page.el('limit').textContent, '1,000.00');
    assert.equal(page.el('percent').textContent, '19%');
    assert.doesNotMatch(page.el('spend').className, /sev-/, 'the green is the style sheet\'s, the same for every severity');
    assert.doesNotMatch(page.el('spend').className, /boxes/);
    assert.equal(page.el('note').hidden, true);
    assert.equal(page.el('app').className, '');
});

test('the amounts carry no currency sign anywhere on the page', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.doesNotMatch(page.el('used').textContent + page.el('limit').textContent + page.el('percent').textContent, /[$A-Za-z]/);
});

test('hovering shows what each value is: the time, the used amount, the budget and the percentage', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('time').title, 'Last update: 8 Oct 2026, 14:24:53');
    assert.equal(page.el('used').title, 'Credits used, in USD');
    assert.equal(page.el('limit').title, 'Credit budget, in USD');
    assert.equal(page.el('percent').title, '19% of the budget spent. Severity: normal');
});

test('the tooltips name the currency the response gives', async () => {
    const status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, spend: { ...SPEND_STATUS.usage.spend, currency: 'EUR' } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('used').title, 'Credits used, in EUR');
    assert.equal(page.el('limit').title, 'Credit budget, in EUR');
});

// ---- the countdown

test('the countdown shows the seconds the backend sent, with the unit, after the refresh button', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('countdown').hidden, false);
    assert.equal(page.el('countdown').textContent, '42s');
    assert.equal(page.el('countdown').title, 'Seconds until the next refresh (negative when overdue)');
});

test('the countdown follows the backend at each update, and does no counting of its own', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 41 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '41s');

    // Time passes on the page, but nothing arrives: the number stays what it was told.
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '41s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 40 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '40s');
});

test('an overdue refresh is shown as a negative number', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, nextRefreshInSeconds: -3 } };
    const page = await load(backendOf(state));

    assert.equal(page.el('countdown').textContent, '-3s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: -4 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '-4s');
});

test('the countdown restarts when the backend says so, for instance after a manual refresh', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, nextRefreshInSeconds: 3 } };
    const page = await load(backendOf(state));
    assert.equal(page.el('countdown').textContent, '3s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 60 };
    await page.click('refresh');

    assert.equal(page.el('countdown').textContent, '60s');
});

test('with nothing to count to the countdown is left out', async () => {
    const page = await load(backendOf({ config: CONFIG, status: { ...SPEND_STATUS, nextRefreshInSeconds: null } }));

    assert.equal(page.el('countdown').hidden, true);
    assert.equal(page.el('countdown').textContent, '');
});

test('the countdown goes away again if the backend stops sending one', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    assert.equal(page.el('countdown').hidden, false);

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: null };
    await page.firePoll();

    assert.equal(page.el('countdown').hidden, true);
});

test('a reading with no usage shows the countdown and its time, and gray boxes for the amounts that are switched on, with no word', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(withDisplay(NO_USAGE_STATUS), { percentage: true }) }));

    assert.equal(page.el('countdown').textContent, '17s');
    assert.equal(page.el('time').textContent, '14:24:53', 'the reading was taken, so it has its time');
    assert.doesNotMatch(page.el('time').className, /skeleton/);
    assert.equal(page.el('spend').hidden, false);
    assert.match(page.el('spend').className, /\bboxes\b/, 'so that the group is not dimmed with old figures');
    const tooltips = { percent: 'Share of the budget spent', used: 'Credits used', limit: 'Credit budget' };
    for (const [id, tooltip] of Object.entries(tooltips)) {
        assert.equal(page.el(id).hidden, false, id);
        assert.match(page.el(id).className, /\bskeleton\b/, id + ' is a gray box');
        assert.equal(page.el(id).textContent.trim(), '', id);
        assert.equal(page.el(id).title, tooltip + ': no usage reported', 'the tooltip says why it is a box');
    }
    assert.equal(page.el('of').hidden, false, 'between the two boxes as between the two figures');
    assert.doesNotMatch(INDEX_HTML, /id="placeholder"/, 'the row has no place for the words any more');
});

test('with no usage an amount that is switched off has no box, and the figures are back with a reading that has usage', async () => {
    const state = { config: CONFIG, status: withShow(withDisplay(NO_USAGE_STATUS), { used: false }) };
    const page = await load(backendOf(state));
    assert.equal(page.el('used').hidden, true);
    assert.equal(page.el('percent').hidden, true, 'off by default');
    assert.match(page.el('limit').className, /skeleton/);
    assert.equal(page.el('of').hidden, true);

    state.status = SPEND_STATUS;
    await page.firePoll();

    assert.equal(page.el('used').textContent, '186.02');
    assert.doesNotMatch(page.el('used').className, /skeleton/);
    assert.doesNotMatch(page.el('spend').className, /boxes/);
    assert.equal(page.el('used').title.indexOf('no usage'), -1);
});

test('a stale reading stays in the row, looks stale, and gets a message line', async () => {
    const status = { ...SPEND_STATUS, stale: true, error: { message: 'Anthropic returned HTTP 503.', at: '2026-10-08T14:25:01Z' } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('app').className, 'stale');
    assert.equal(page.el('used').textContent, '186.02');
    assert.equal(page.el('limit').textContent, '1,000.00');
    assert.equal(page.el('time').textContent, '14:24:53');
    assert.equal(page.el('note').hidden, false);
    assert.equal(page.el('note').textContent, 'Refresh failed at 14:25:01: Anthropic returned HTTP 503.');
    assert.equal(page.el('note').className, 'note note-stale');
});

test('an error before any reading is shown, such as Claude Code not being found', async () => {
    const status = { refreshing: false, stale: false, usage: null, error: { message: 'Claude Code could not be found on the PATH.', at: '2026-10-08T14:17:11Z' } };
    const page = await load(backendOf({ config: CONFIG, status }));

    // No word in the row: gray boxes, the time among them, and the message line says what went wrong.
    for (const [id, tooltip] of Object.entries({ time: 'Last update', used: 'Credits used', limit: 'Credit budget' })) {
        assert.equal(page.el(id).hidden, false, id);
        assert.match(page.el(id).className, /\bskeleton\b/, id);
        assert.equal(page.el(id).title, tooltip + ': no data', id);
    }
    assert.equal(page.el('note').textContent, 'Refresh failed at 14:17:11: Claude Code could not be found on the PATH.');
    assert.equal(page.el('note').className, 'note note-error');
    assert.equal(page.el('spend').hidden, false);
});

test('the message line goes away again after a successful refresh', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, stale: true, error: { message: 'boom', at: NOW } } };
    const page = await load(backendOf(state));
    assert.equal(page.el('note').hidden, false);

    state.status = SPEND_STATUS;
    await page.firePoll();

    assert.equal(page.el('note').hidden, true);
    assert.equal(page.el('app').className, '');
});

test('no severity gives the amounts a class: warning and critical look like normal', async () => {
    for (const severity of ['warning', 'critical', 'something new']) {
        const status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, spend: { ...SPEND_STATUS.usage.spend, severity } } };
        const page = await load(backendOf({ config: CONFIG, status }));

        assert.doesNotMatch(page.el('spend').className, /sev-|warn|critical/, severity);
        assert.ok(page.el('percent').title.endsWith('Severity: ' + severity), 'the tooltip still names it');
    }
});

test('backend text is never handed to the HTML parser', async () => {
    const hostile = '<img src=x onerror=alert(1)>';
    const status = {
        refreshing: false, stale: true, error: { message: hostile, at: NOW },
        usage: { ...SPEND_STATUS.usage, spend: { ...SPEND_STATUS.usage.spend, severity: hostile } },
    };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('note').textContent, 'Refresh failed at 14:24:53: ' + hostile);
    assert.ok(page.el('percent').title.endsWith(hostile), 'in a tooltip as well, as text: ' + page.el('percent').title);
});

test('the refresh button shows when a refresh is running', async () => {
    const page = await load(backendOf({ config: CONFIG, status: { ...SPEND_STATUS, refreshing: true } }));

    assert.equal(page.el('refresh').className, 'icon busy');
});

test('it keeps polling and warns when the backend cannot be reached, then recovers', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    assert.equal(page.el('connection').hidden, true);

    state.down = true;
    await page.firePoll();
    assert.equal(page.el('connection').hidden, false);
    assert.ok(page.timers.some(t => t.live), 'a further poll is scheduled');

    state.down = false;
    await page.firePoll();
    assert.equal(page.el('connection').hidden, true);
});

test('if the settings cannot be loaded at startup it says so and retries', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, down: true };
    const page = await load(backendOf(state));

    assert.equal(page.el('connection').hidden, false);
    const retry = page.timers.filter(t => t.live).pop();
    assert.ok(retry, 'a retry is scheduled');

    state.down = false;
    retry.fn();
    await settle();
    assert.equal(page.el('connection').hidden, true);
    assert.equal(page.el('used').textContent, '186.02');
    assert.equal(page.el('limit').textContent, '1,000.00');
});

// ---- refresh

test('the refresh button asks the backend to fetch, then shows the result, and stays enabled', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const before = page.calls.length;

    await page.click('refresh');

    const sent = page.calls.slice(before);
    assert.equal(sent[0].url, '/api/refresh');
    assert.equal(sent[0].method, 'POST');
    assert.equal(sent[0].headers['Content-Type'], 'application/json');
    assert.equal(sent[1].url, '/api/status');
    assert.notEqual(page.el('refresh').disabled, true, 'extra clicks stay possible');
});

// ---- config: two fields, on demand, confirmed together

// ---- the window host

// The rows of the panel, each as its cells; the header, which is the first child, not among them.
const rows = page => page.el('panel-lines').children.filter(c => !/\bhead\b/.test(c.className)).map(c => c.children.map(x => x.textContent));
const head = page => page.el('panel-lines').children.filter(c => /\bhead\b/.test(c.className)).map(c => c.children.map(x => x.textContent));
const lines = page => rows(page).map(cells => cells.join(' '));

test('the history panel is closed at the start and asks the backend for nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 0);
});

test('the history button opens the panel below the strip with a line for each reading, newest first', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('history-button');

    assert.equal(page.opened.length, 0, 'no new window');
    assert.equal(page.el('panel').hidden, false);
    assert.deepEqual(rows(page), [['20:46:11', '260.66', '1000.00', 'USD'], ['20:44:12', '260.36', '1000.00', 'USD']]);
    assert.deepEqual(head(page), [['time', 'used', 'limit', 'Cur.']], 'with a header row');
    assert.match(page.el('panel-lines').children[0].className, /\bhead\b/, 'which comes first');
    assert.match(page.el('panel-lines').children[1].className, /\bcols-4\b/);
    assert.equal(page.el('panel-lines').hidden, false);
    assert.equal(page.el('panel-error').hidden, true);
});

test('pressed again, the history button closes the panel; tooltip and state follow', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const button = page.el('history-button');

    await page.click('history-button');
    assert.equal(button.title, 'Hide the usage history');
    assert.equal(button.attrs['aria-label'], 'Hide the usage history');
    assert.equal(button.attrs['aria-expanded'], 'true');

    await page.click('history-button');
    assert.equal(page.el('panel').hidden, true);
    assert.equal(button.title, 'Show the usage history');
    assert.equal(button.attrs['aria-label'], 'Show the usage history');
    assert.equal(button.attrs['aria-expanded'], 'false');
});

test('a new reading while the panel is open puts a new line on top and keeps the scroll position', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('history-button');
    page.el('panel-lines').scrollTop = 45;

    state.status = { ...SPEND_STATUS, historyStamp: 'b' };
    state.history = () => ({ status: 200, body: { ...HISTORY, total: 3, lines: [line(['20:47:11', '260.90', '1000.00', 'USD']), ...HISTORY.lines] } });
    await page.firePoll();

    assert.equal(lines(page)[0], '20:47:11 260.90 1000.00 USD');
    assert.equal(lines(page).length, 3);
    assert.equal(page.el('panel-lines').scrollTop, 45);
});

test('the panel is read again only when a new reading has arrived, not on every poll', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    const reads = () => page.calls.filter(c => c.url === '/api/history').length;
    assert.equal(reads(), 1);

    await page.firePoll();
    await page.firePoll();

    assert.equal(reads(), 1);
});

test('a closed panel is not read when a new reading arrives; opening it reads it then', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    state.status = { ...SPEND_STATUS, historyStamp: 'b' };

    await page.firePoll();
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 0);

    await page.click('history-button');
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1);
});

test('a history that was cut says so in a note above the lines', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, total: 5000, note: 'Showing 2 of 5,000 lines: 4,998 older not shown.' } }) }));

    await page.click('history-button');

    assert.equal(page.el('panel-note').hidden, false);
    assert.equal(page.el('panel-note').textContent, 'Showing 2 of 5,000 lines: 4,998 older not shown.', 'the backend wrote it; the page shows it as it is');
});

test('no history, or no rows, is one line of text and no list', async () => {
    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, exists: false, total: 0, lines: [], note: 'There is no usage history yet.' } }) }));
    await none.click('history-button');
    assert.equal(none.el('panel-note').textContent, 'There is no usage history yet.');
    assert.equal(none.el('panel-lines').hidden, true);

    const empty = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, total: 0, lines: [], note: 'The history has no rows yet.' } }) }));
    await empty.click('history-button');
    assert.equal(empty.el('panel-note').textContent, 'The history has no rows yet.');
    assert.equal(empty.el('panel-lines').hidden, true);
});

test('an unreadable history shows a red error and keeps the lines it had; the error goes after a good read', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('history-button');

    state.status = { ...SPEND_STATUS, historyStamp: 'b' };
    state.history = () => ({ status: 500, body: { error: 'The usage history could not be read.' } });
    await page.firePoll();

    assert.equal(page.el('panel-error').hidden, false);
    assert.equal(page.el('panel-error').textContent, 'The usage history could not be read: The usage history could not be read.');
    assert.equal(lines(page).length, 2);

    state.status = { ...SPEND_STATUS, historyStamp: 'c' };
    state.history = undefined;
    await page.firePoll();
    assert.equal(page.el('panel-error').hidden, true);
});

test('history text is never handed to the HTML parser', async () => {
    const hostile = '<img src=x onerror=alert(1)>';
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, lines: [line([hostile, '1', '2', 'USD'], { title: hostile })] } }) }));

    await page.click('history-button');

    assert.equal(rows(page)[0][0], hostile);
});

// ---- the log panel, and the two panels together

test('the log button shows the log in the panel, newest line first, in the main window and not a new one', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('log-button');

    assert.equal(page.opened.length, 0, 'no new window');
    assert.equal(page.el('panel').hidden, false);
    assert.deepEqual(lines(page), [
        '2026-10-08 19:00:01 INFO    [UsageService] Usage refresh succeeded',
        '2026-10-08 19:00:00 INFO    [UsageApp] Starting']);
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 0);
    assert.equal(page.el('log-button').title, 'Hide the log');
    assert.equal(page.el('log-button').attrs['aria-expanded'], 'true');
    assert.equal(page.el('history-button').title, 'Show the usage history');
});

test('the log button closes the panel when it is shown, and the window goes back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 400, height: 34 };

    await page.click('log-button');
    await page.click('log-button');

    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.el('log-button').title, 'Show the log');
    assert.equal(page.el('log-button').attrs['aria-expanded'], 'false');
    assert.equal(page.window.contentSize(), '400,34,0');
});

test('showing the log while the history is shown replaces it, and the window takes the size of the log', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 400, height: 34 };
    await page.click('history-button');

    await page.click('log-button');

    assert.equal(page.window.contentSize(), '1200,340,2,log', 'the window takes the size the log has');
    assert.equal(lines(page).length, 2);
    assert.match(lines(page)[0], /Usage refresh succeeded/);
    assert.equal(page.el('history-button').title, 'Show the usage history');
    assert.equal(page.el('history-button').attrs['aria-expanded'], 'false');
    assert.equal(page.el('log-button').title, 'Hide the log');

    await page.click('history-button');
    assert.match(lines(page)[0], /^20:46:11/);
    assert.equal(page.el('log-button').title, 'Show the log');
});

test('the open log is read again on every poll, and the lines change only when the log grew', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('log-button');
    const reads = () => page.calls.filter(c => c.url === '/api/log').length;
    assert.equal(reads(), 1);

    await page.firePoll();
    assert.equal(reads(), 2);
    assert.equal(lines(page).length, 2);

    state.log = () => ({ status: 200, body: { ...LOG, lines: [...LOG.lines, '2026-10-08 19:00:02 INFO    [UsageApp] Later'] } });
    await page.firePoll();
    assert.equal(lines(page).length, 3);
    assert.match(lines(page)[0], /Later/);
});

test('a closed log is not read on polls', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.firePoll();
    await page.firePoll();

    assert.equal(page.calls.filter(c => c.url === '/api/log').length, 0);
});

test('a log that was cut says so, and a missing or empty one says that, with no lines', async () => {
    const cut = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, truncated: true } }) }));
    await cut.click('log-button');
    assert.equal(cut.el('panel-note').textContent, 'Showing the newest 2 lines of the log.');

    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, exists: false, lines: [] } }) }));
    await none.click('log-button');
    assert.equal(none.el('panel-note').textContent, 'There is no log file yet.');
    assert.equal(none.el('panel-lines').hidden, true);

    const empty = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, lines: [] } }) }));
    await empty.click('log-button');
    assert.equal(empty.el('panel-note').textContent, 'The log is empty.');
});

test('an unreadable log shows a red error, keeps the lines it had, and loses the error after a good read', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('log-button');

    state.log = () => ({ status: 500, body: { error: 'The log could not be read.' } });
    await page.firePoll();
    assert.equal(page.el('panel-error').textContent, 'The log could not be read: The log could not be read.');
    assert.equal(lines(page).length, 2);

    state.log = undefined;
    await page.firePoll();
    assert.equal(page.el('panel-error').hidden, true);
});

test('log text is never handed to the HTML parser', async () => {
    const hostile = '<img src=x onerror=alert(1)>';
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, lines: [hostile] } }) }));

    await page.click('log-button');

    assert.equal(lines(page)[0], hostile);
});

test('lines coming in above what the person is reading move the scroll with it, and at the top it stays at the top', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('log-button');
    const box = page.el('panel-lines');
    box.scrollHeight = 30; // two lines of 15 px

    box.scrollTop = 15;
    state.log = () => ({ status: 200, body: { ...LOG, lines: [...LOG.lines, '2026-10-08 19:00:05 INFO    [X] new one', '2026-10-08 19:00:06 INFO    [X] new two'] } });
    await page.firePoll();
    assert.equal(box.scrollTop, 15 + 2 * 15, 'two lines were added above');

    box.scrollHeight = 60;
    box.scrollTop = 0;
    state.log = () => ({ status: 200, body: { ...LOG, lines: [...LOG.lines, '2026-10-08 19:00:05 INFO    [X] new one', '2026-10-08 19:00:06 INFO    [X] new two', '2026-10-08 19:00:07 INFO    [X] three'] } });
    await page.firePoll();
    assert.equal(box.scrollTop, 0);
});


test('the page reports the size it needs, in whole pixels, for the window to match, and that it is not resizable', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };

    assert.equal(page.window.contentSize(), '400,42,0');

    page.el('app').rect = { width: 512, height: 66 };
    page.el('top').rect = { width: 512, height: 66 };
    assert.equal(page.window.contentSize(), '512,66,0');
});

test('with the history shown the window is ten times as tall as the row, as wide as the row, and may be resized in height only', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 400, height: 34 };
    page.el('app').rect = { width: 400, height: 34 };

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,340,1,history');
    assert.match(page.el('app').className, /\bopen\b/);

    // Dragging the window makes the page bigger; the report does not follow it.
    page.el('app').rect = { width: 900, height: 700 };
    assert.equal(page.window.contentSize(), '400,340,1,history');

    await page.click('history-button');
    page.el('app').rect = { width: 400, height: 34 };
    assert.equal(page.window.contentSize(), '400,34,0');
    assert.doesNotMatch(page.el('app').className, /\bopen\b/);
});

test('with the log shown the window is also three times as wide as the row, and may be resized in both directions', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 400, height: 34 };

    await page.click('log-button');
    assert.equal(page.window.contentSize(), '1200,340,2,log');

    page.el('app').rect = { width: 1700, height: 900 };
    assert.equal(page.window.contentSize(), '1200,340,2,log', 'not the size it was dragged to');

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,340,1,history', 'back to the row\'s width for the history');

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,34,0');
});

test('the stale look and the open panel keep each other\'s class on the page', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, stale: true, error: { message: 'boom', at: NOW } } };
    const page = await load(backendOf(state));
    await page.click('history-button');
    await page.firePoll();

    assert.match(page.el('app').className, /\bstale\b/);
    assert.match(page.el('app').className, /\bopen\b/);
});

// ---- the settings view

const settingsPosts = page => page.calls.filter(c => c.method === 'POST' && c.url === '/api/settings');
const settingsGets = page => page.calls.filter(c => c.method === 'GET' && c.url === '/api/settings');

test('the settings button shows the view in the panel area, filled with what the backend has', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');

    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('panel-lines').hidden, true, 'not the lines of the log or the history');
    assert.equal(page.el('settings-button').title, 'Hide the settings');
    assert.equal(page.el('set-usageIntervalSeconds').value, '120');
    assert.equal(page.el('set-showInterval').checked, true);
    assert.equal(page.el('set-showDeltaUsed').checked, false);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm:ss');
    assert.equal(page.el('set-logResponse').checked, false);
    assert.equal(page.el('settings-error').hidden, true);
});

test('the interval field shows the value in force, whatever it is: there is no list to pick from', async () => {
    const odd = { ...SETTINGS, settings: { ...SETTINGS.settings, usageIntervalSeconds: 45 } };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: odd }) }));

    await page.click('settings-button');

    assert.equal(page.el('set-usageIntervalSeconds').value, '45', 'the field shows what is in force');
    assert.equal(INDEX_IDS.includes('set-intervalChoices'), false, 'and the page has no dropdown of values');
});

test('any whole number can be typed in the interval box, and is sent as a number', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = ' 47 ';

    await page.click('settings-apply');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).usageIntervalSeconds, 47);
});

test('a box that is not a whole number of seconds is refused in the view, in red, and sends nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    for (const bad of ['', 'abc', '4.5', '-60', '1e3', '60 s']) {
        page.el('set-usageIntervalSeconds').value = bad;
        await page.click('settings-apply');
        assert.equal(page.el('settings-error').hidden, false, JSON.stringify(bad));
        assert.match(page.el('settings-error').textContent, /whole number of seconds/);
    }
    assert.equal(settingsPosts(page).length, 0);
    assert.equal(page.el('settings-view').hidden, false, 'the view stays open');
});

test('a number the backend refuses, such as 4, stays in the box with the backend\'s reason', async () => {
    const page = await load(backendOf({
        config: CONFIG, status: SPEND_STATUS,
        postSettings: () => ({ status: 400, body: { error: 'The usage interval must be a whole number of seconds from 5 to 3600.' } }),
    }));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = '4';

    await page.click('settings-apply');

    assert.match(page.el('settings-error').textContent, /from 5 to 3600/);
    assert.equal(page.el('set-usageIntervalSeconds').value, '4');
});

test('the view asks the backend every time it is opened, and keeps nothing of its own in between', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    assert.equal(settingsGets(page).length, 1);
    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, true, 'pressed again, it closes');

    state.settings = () => ({ status: 200, body: { ...SETTINGS, settings: { ...SETTINGS.settings, usageIntervalSeconds: 300, showDeltaTime: true } } });
    await page.click('settings-button');

    assert.equal(settingsGets(page).length, 2);
    assert.equal(page.el('set-usageIntervalSeconds').value, '300');
    assert.equal(page.el('set-showDeltaTime').checked, true);
});

test('Apply sends every setting together, closes the view, and the row follows at once', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = '180';
    page.el('set-logResponse').checked = true;
    page.el('set-timeFormat').value = 'hh:mm';
    const statusCalls = page.calls.filter(c => c.url === '/api/status').length;

    await page.click('settings-apply');

    assert.equal(settingsPosts(page).length, 1);
    assert.deepEqual(JSON.parse(settingsPosts(page)[0].body), {
        usageIntervalSeconds: 180, logResponse: true, showPercentage: false, showCurrency: false, showHistoryIcon: true, showLogIcon: true, showErrorIcon: true, showInterval: true, showDeltaUsed: false, showDeltaTime: false,
        timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false, historyDate: false, historyZeroLines: true, historyFailedLines: true,
        showTime: true, showUsed: true, showLimit: true, showRefresh: true, showCountdown: true, showDuration: false, stayOnTop: false, showTitleBar: true, autoInterval: false,
    });
    assert.equal(settingsPosts(page)[0].headers['Content-Type'], 'application/json');
    assert.equal(page.el('settings-view').hidden, true, 'Apply closes the view');
    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.el('settings-button').title, 'Show the settings');
    assert.ok(page.calls.filter(c => c.url === '/api/status').length > statusCalls, 'the status is asked for at once, so the row shows the change');
});

test('changes do nothing until Apply: editing the form sends nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    page.el('set-showDeltaUsed').checked = true;
    page.el('set-usageIntervalSeconds').value = '300';
    await page.firePoll();

    assert.equal(settingsPosts(page).length, 0);
    assert.equal(page.el('set-showDeltaUsed').checked, true, 'a poll does not disturb what is being edited');
});

test('a setting the backend refuses stays in the form, with the reason in red', async () => {
    const page = await load(backendOf({
        config: CONFIG, status: SPEND_STATUS,
        postSettings: () => ({ status: 400, body: { error: 'The usage interval must be a whole number of seconds from 5 to 3600.' } }),
    }));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = '180';

    await page.click('settings-apply');

    assert.equal(page.el('settings-error').hidden, false);
    assert.match(page.el('settings-error').textContent, /from 5 to 3600/);
    assert.match(INDEX_HTML, /id="settings-error" class="panel-error"/, 'the same red as the panel\'s errors');
    assert.equal(page.el('set-usageIntervalSeconds').value, '180', 'what was typed stays');
});

test('Cancel closes the view at once and sends nothing, whatever was edited', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-logResponse').checked = true;
    page.el('set-usageIntervalSeconds').value = '300';

    await page.click('settings-cancel');

    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(settingsPosts(page).length, 0, 'no value is changed');
    assert.equal(page.el('settings-button').title, 'Show the settings');
});

test('there is no Close and no question about unapplied edits any more', () => {
    assert.doesNotMatch(INDEX_HTML, /settings-close|settings-confirm|settings-discard|settings-keep/);
    assert.match(INDEX_HTML, /id="settings-cancel"[^>]*>Cancel</);
});

test('what was edited and cancelled is gone when the view is opened again', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-logResponse').checked = true;
    await page.click('settings-cancel');

    await page.click('settings-button');

    assert.equal(page.el('set-logResponse').checked, false, 'what the backend has, not what was typed');
});

test('Restore defaults fills in the defaults and sends nothing until Apply', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    await page.click('settings-restore');

    assert.equal(page.el('set-usageIntervalSeconds').value, '60');
    assert.equal(page.el('set-showInterval').checked, false);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm');
    assert.equal(settingsPosts(page).length, 0);

    await page.click('settings-apply');
    assert.deepEqual(JSON.parse(settingsPosts(page)[0].body), DEFAULT_SETTINGS);
});

test('if the settings cannot be read the view says so and has no form to edit', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) }));

    await page.click('settings-button');

    assert.equal(page.el('settings-form').hidden, true);
    assert.match(page.el('settings-error').textContent, /^The settings could not be read: /);
    assert.equal(page.el('settings-apply').attrs.disabled, '');
    assert.equal(page.el('settings-restore').attrs.disabled, '');
});

test('the settings, the log and the history share the one panel area', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);

    await page.click('log-button');
    assert.equal(page.el('settings-view').hidden, true, 'the log replaces the settings');
    assert.equal(page.el('settings-button').title, 'Show the settings');
    assert.equal(page.el('log-button').title, 'Hide the log');

    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('log-button').title, 'Show the log');
    assert.equal(page.el('panel-lines').hidden, true);
});

// ---- the window sizes the page asks for

test('with the settings shown the window is exactly as tall as the page needs, the row and its messages included, and cannot be resized', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('app').rect = { width: 399.2, height: 612.2 };

    await page.click('settings-button');

    assert.equal(page.window.contentSize(), '400,613,3,settings', 'the whole page, rounded up; flag 3: fitted and not resizable');
    assert.match(page.el('app').className, /\bfit\b/);
    assert.doesNotMatch(page.el('app').className, /\bopen\b/, 'it does not fill the window, or its height would be the window\'s');
});

test('the settings window follows the page when a message line appears, grows or goes', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('app').rect = { width: 399.2, height: 600 };
    await page.click('settings-button');
    assert.equal(page.window.contentSize(), '400,600,3,settings');

    // A failed refresh puts a message line in the row: the page is taller by it.
    page.el('app').rect = { width: 399.2, height: 636 };
    assert.equal(page.window.contentSize(), '400,636,3,settings');

    page.el('app').rect = { width: 399.2, height: 600 };
    assert.equal(page.window.contentSize(), '400,600,3,settings', 'and shorter again when it goes');
});

test('with the history shown the height is what it was when it opened, whatever the main view does after', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,420,1,history', 'ten times the row');

    page.el('top').rect = { width: 399.2, height: 77 }; // a message line has come
    assert.equal(page.window.contentSize(), '400,420,1,history', 'the window keeps its height; the panel takes the difference');

    page.el('top').rect = { width: 399.2, height: 41.5 };
    assert.equal(page.window.contentSize(), '400,420,1,history', 'and when it goes');
});

test('with the log shown the height is fixed the same way, and the width is three rows', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };
    await page.click('log-button');

    page.el('top').rect = { width: 399.2, height: 90 };

    assert.equal(page.window.contentSize(), '1200,420,2,log');
});

test('opening a panel again starts from the row as it is then, not as it was', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };
    await page.click('history-button');
    await page.click('history-button');
    page.el('top').rect = { width: 399.2, height: 60 }; // a message line is showing

    await page.click('history-button');

    assert.equal(page.window.contentSize(), '400,600,1,history');
});

test('the window is as wide as the row plus the scrollbar of the history and the log, which is measured, so no column is covered', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '415,420,1,history', '400 and the scrollbar');

    await page.click('log-button');
    assert.equal(page.window.contentSize(), '1215,420,2,log', 'three rows and the scrollbar');

    await page.click('settings-button');
    page.el('app').rect = { width: 399.2, height: 500 };
    assert.equal(page.window.contentSize(), '400,500,3,settings', 'the settings do not scroll, so they take no room for one');
});

test('no scrollbar width is added when the platform has none to measure', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 0 });
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('history-button');

    assert.equal(page.window.contentSize(), '400,420,1,history');
});

// ---- the optional items of the row

const WITH_CHANGE = {
    ...SPEND_STATUS,
    display: {
        time: '14:24', timeTooltip: 'Last update: x', placeholder: null, message: null,
        spend: { percentText: '19%', percentTooltip: 'p', used: '186.02', limit: '1,000.00', usedTooltip: 'u', limitTooltip: 'l', severityText: 'normal' },
        countdown: { text: '42s', tooltip: 'c' },
        interval: { text: '60s', tooltip: 'Time between usage requests' },
        deltaUsed: { text: '+0.05', tooltip: 'Change in the amount used since the previous reading, in USD' },
        deltaTime: { text: '1 m', tooltip: 'Time since the previous reading' },
        show: { interval: false, deltaUsed: true, deltaTime: false },
    },
};

test('the countdown is always in the row; the interval and the two changes only when their settings are on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WITH_CHANGE }));

    assert.equal(page.el('countdown').hidden, false, 'not optional');
    assert.equal(page.el('countdown').textContent, '42s');
    assert.equal(page.el('interval').hidden, true, 'off');
    assert.equal(page.el('delta-used').hidden, false, 'on');
    assert.equal(page.el('delta-used').textContent, '+0.05');
    assert.equal(page.el('delta-used').title, 'Change in the amount used since the previous reading, in USD');
    assert.equal(page.el('delta-time').hidden, true, 'off');
});

test('turning the settings on shows the items at the next poll, as the backend says', async () => {
    const state = { config: CONFIG, status: WITH_CHANGE };
    const page = await load(backendOf(state));
    state.status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { interval: true, deltaUsed: true, deltaTime: true } } };

    await page.firePoll();

    assert.equal(page.el('interval').textContent, '60s');
    assert.equal(page.el('interval').title, 'Time between usage requests');
    assert.equal(page.el('delta-time').textContent, '1 m');
    assert.equal(page.el('delta-time').title, 'Time since the previous reading');
});

test('an item the backend could not work out is a gray box with its tooltip, at any time and not only at the start', async () => {
    const status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, deltaUsed: null, show: { interval: true, deltaUsed: true, deltaTime: true } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('delta-used').textContent.trim(), '', 'it has nothing to say');
    assert.equal(page.el('delta-used').className, 'delta delta-used skeleton', 'and is a gray box, though a reading is there');
    assert.equal(page.el('delta-used').title, 'Change in the amount used since the previous reading');
    assert.equal(page.el('delta-time').hidden, false);
    assert.doesNotMatch(page.el('delta-time').className, /\bskeleton\b/, 'the one that has a value shows it');
    assert.doesNotMatch(page.el('used').className, /\bskeleton\b/, 'and the amounts are figures');
});

test('an optional item whose setting is on is a gray box in its room when it has no value, so the row does not change width; with the setting off it takes none', async () => {
    const withValue = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { interval: true, deltaUsed: true, deltaTime: true } } };
    const state = { config: CONFIG, status: withValue };
    const page = await load(backendOf(state));
    assert.equal(page.el('delta-used').hidden, false);

    state.status = { ...withValue, display: { ...withValue.display, deltaUsed: null, deltaTime: null } };
    await page.firePoll();
    for (const id of ['delta-used', 'delta-time']) {
        assert.equal(page.el(id).hidden, false, id + ' is still in the row');
        assert.match(page.el(id).className, /\bskeleton\b/, id);
        assert.doesNotMatch(page.el(id).className, /\bempty\b/, id);
        assert.notEqual(page.el(id).title, '', id + ' has its tooltip');
    }

    state.status = { ...withValue, display: { ...withValue.display, deltaUsed: null, deltaTime: null, show: { interval: true, deltaUsed: false, deltaTime: false } } };
    await page.firePoll();
    assert.equal(page.el('delta-used').hidden, true, 'switched off: no room');
    assert.equal(page.el('delta-time').hidden, true);
});

test('the item comes back with its text and is no gray box when the value returns, and an unchanged amount is a figure', async () => {
    const withValue = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { interval: false, deltaUsed: true, deltaTime: false } } };
    const state = { config: CONFIG, status: { ...withValue, display: { ...withValue.display, deltaUsed: null } } };
    const page = await load(backendOf(state));
    assert.match(page.el('delta-used').className, /skeleton/);

    state.status = withValue;
    await page.firePoll();

    assert.equal(page.el('delta-used').textContent, '+0.05');
    assert.doesNotMatch(page.el('delta-used').className, /skeleton/);

    state.status = { ...withValue, display: { ...withValue.display, deltaUsed: { text: '0.00', tooltip: 'Change in the amount used since the previous reading, in USD' } } };
    await page.firePoll();
    assert.equal(page.el('delta-used').textContent, '0.00', 'what the backend writes for a change of nothing');
    assert.doesNotMatch(page.el('delta-used').className, /skeleton/);
    assert.match(page.el('delta-used').className, /\bdelta-used\b/, 'its own class is kept');
});

// ---- the history is read again when a row is added, a failed one too

test('a row of a failed refresh makes the open history read again, though no new reading arrived', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } };
    const page = await load(backendOf(state));
    await page.click('history-button');
    const reads = () => page.calls.filter(c => c.url === '/api/history').length;
    assert.equal(reads(), 1);

    // The same reading (the same fetched_at), a failed refresh has added a row to the file.
    state.status = { ...SPEND_STATUS, historyStamp: 'b' };
    await page.firePoll();

    assert.equal(reads(), 2);
});

test('the history is not read again while the file is the same, however many polls', async () => {
    const page = await load(backendOf({ config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } }));
    await page.click('history-button');

    await page.firePoll();
    await page.firePoll();
    await page.firePoll();

    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1);
});

test('a new reading with the same file stamp does not read the history: the stamp is what says it changed', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } };
    const page = await load(backendOf(state));
    await page.click('history-button');

    state.status = { ...SPEND_STATUS, historyStamp: 'a', usage: { ...SPEND_STATUS.usage, fetched_at: '2026-10-08T14:30:00Z' } };
    await page.firePoll();

    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1);
});

test('a history read that failed is tried again at the next poll, though no row was added', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } };
    const page = await load(backendOf(state));
    state.history = () => ({ status: 500, body: { error: 'The usage history could not be read.' } });
    await page.click('history-button');
    assert.match(page.el('panel-error').textContent, /could not be read/);

    // The file is the same; the read is tried again all the same, and this time it works.
    state.history = null;
    await page.firePoll();

    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 2);
    assert.equal(page.el('panel-error').textContent, '');
    assert.equal(page.el('panel-lines').children.length, 3, 'the header and the two lines');

    // Once it has been read, the same stamp is left alone again.
    await page.firePoll();
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 2);
});

test('a panel opened while another is still being read is read as soon as that read ends', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } };
    const page = await load(backendOf(state));
    let answerLog;
    state.log = () => new Promise(resolve => { answerLog = () => resolve({ status: 200, body: LOG }); });
    await page.click('log-button');

    // The log has not answered yet, and the person goes on to the history.
    await page.click('history-button');
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 0, 'one read at a time');

    answerLog();
    await settle();

    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1, 'read without waiting for a new row');
    assert.equal(page.el('panel-lines').children.length, 3, 'the history, not the log that answered late');
    assert.equal(page.el('panel-lines').children[1].children[0].textContent, '20:46:11');
});

test('the percentage is in the row only when its setting is on, and the amounts stay either way', async () => {
    const hidden = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { percentage: false, interval: false, deltaUsed: false, deltaTime: false } } };
    const state = { config: CONFIG, status: hidden };
    const page = await load(backendOf(state));

    assert.equal(page.el('percent').hidden, true);
    assert.equal(page.el('spend').hidden, false, 'the amounts are still there');

    state.status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { percentage: true, interval: false, deltaUsed: false, deltaTime: false } } };
    await page.firePoll();

    assert.equal(page.el('percent').hidden, false);
    assert.equal(page.el('percent').textContent, '19%');
});

// ---- the error log panel

test('the error log button opens the panel with a line for each error, newest first, as time and message', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('errors-button');

    assert.equal(page.el('panel').hidden, false);
    assert.deepEqual(rows(page), [
        ['11:34:42', 'Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min.'],
        ['11:20:01', 'Claude Code is not logged in. Log in, then refresh.'],
    ]);
    assert.deepEqual(head(page), [['time', 'message']]);
    assert.match(page.el('panel-lines').children[1].className, /\bcols-2\b/);
    assert.equal(page.el('errors-button').title, 'Hide the error log');
    assert.equal(page.el('log-button').title, 'Show the log');
    assert.equal(page.el('panel-lines').hidden, false);
});

test('the error log is half as wide as the log: ten rows tall, one and a half rows and the scrollbar wide, both ways resizable, panel named', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('errors-button');

    assert.equal(page.window.contentSize(), '615,420,2,errors', 'half as wide as the log: one and a half rows (600) and the scrollbar');
    page.el('top').rect = { width: 399.2, height: 90 };
    assert.equal(page.window.contentSize(), '615,420,2,errors', 'not moved by the main view');
});

test('the history and the log name themselves to the host, which remembers their heights', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('history-button');
    assert.match(page.window.contentSize(), /,1,history$/);
    await page.click('log-button');
    assert.match(page.window.contentSize(), /,2,log$/);
    await page.click('settings-button');
    assert.match(page.window.contentSize(), /,3,settings$/, 'the settings name themselves too, though no height of theirs is remembered');
});

test('a new error appears at the top of the open error log at the next poll', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('errors-button');
    state.errors = () => ({ status: 200, body: { entries: [{ time: '11:40:00', message: 'Cannot reach api.anthropic.com' }, ...ERRORS.entries] } });

    await page.firePoll();

    assert.equal(rows(page).length, 3);
    assert.deepEqual(rows(page)[0], ['11:40:00', 'Cannot reach api.anthropic.com']);
});

test('an error log with nothing in it says so in one line', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, errors: () => ({ status: 200, body: { entries: [] } }) }));

    await page.click('errors-button');

    assert.equal(rows(page).length, 0);
    assert.equal(page.el('panel-note').textContent, 'There are no errors in this run.');
});

test('the error log shares the one panel area with the others', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('errors-button');
    await page.click('history-button');

    assert.equal(page.el('errors-button').title, 'Show the error log');
    assert.equal(page.el('history-button').title, 'Hide the usage history');
    assert.equal(rows(page)[0][1], '260.66');
});

// ---- an HTTP 429: a red countdown, and its message when hovered

const LIMITED = {
    ...SPEND_STATUS,
    stale: false,
    display: {
        time: '14:24', timeTooltip: 'Last update: x', placeholder: null, message: null,
        spend: { percentText: '19%', percentTooltip: 'p', used: '186.02', limit: '1,000.00', usedTooltip: 'u', limitTooltip: 'l', severityText: 'normal' },
        countdown: { text: '118s', tooltip: 'c' },
        countdownAlert: 'Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min.',
        show: { percentage: true, interval: false, deltaUsed: false, deltaTime: false },
    },
};

test('while the server asks us to slow down the countdown is red, with no message line and the figures not dimmed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: LIMITED }));

    assert.match(page.el('countdown').className, /\balert\b/);
    assert.equal(page.el('note').hidden, true, 'no message line');
    assert.doesNotMatch(page.el('app').className, /stale/);
    assert.equal(page.el('alert-note').hidden, true, 'and the message is not shown until hovered');
});

test('hovering the red countdown shows the message in a line under the row, and leaving takes it away', async () => {
    const page = await load(backendOf({ config: CONFIG, status: LIMITED }));

    page.el('countdown').listeners.mouseenter();
    assert.equal(page.el('alert-note').hidden, false);
    assert.equal(page.el('alert-note').textContent, 'Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min.');
    assert.match(INDEX_HTML, /id="alert-note" class="note note-alert"/, 'in the red, bold note style');

    page.el('countdown').listeners.mouseleave();
    assert.equal(page.el('alert-note').hidden, true);
});

test('the hover line stays while polls come in and goes when the 429 does', async () => {
    const state = { config: CONFIG, status: LIMITED };
    const page = await load(backendOf(state));
    page.el('countdown').listeners.mouseenter();

    await page.firePoll();
    assert.equal(page.el('alert-note').hidden, false, 'still hovered');

    state.status = SPEND_STATUS;
    await page.firePoll();
    assert.equal(page.el('alert-note').hidden, true, 'no message any more');
    assert.doesNotMatch(page.el('countdown').className, /alert/);
});

test('hovering a countdown that is not red shows nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    page.el('countdown').listeners.mouseenter();

    assert.equal(page.el('alert-note').hidden, true);
});

// ---- the history table: time, date, hover and settings

test('a line that begins a run says so when hovered, and the others say nothing: the text is the backend\'s', async () => {
    const history = { ...HISTORY, lines: [
        line(['20:46:11', '260.66', '1000.00', 'USD']),
        line(['20:44:12', '260.36', '1000.00', 'USD'], { start: true, title: 'The program started here' })] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const shown = page.el('panel-lines').children.filter(c => !/\bhead\b/.test(c.className));
    assert.equal(shown[0].title, '');
    assert.equal(shown[1].title, 'The program started here');
    assert.match(shown[1].className, /\bstart\b/, 'and it is the green one');
    assert.doesNotMatch(shown[1].className, /\bmark\b/, 'not the gray background of the log');
    assert.doesNotMatch(shown[0].className, /\b(start|mark)\b/);
});

test('a failed line is marked as the backend says, and its cells are the backend\'s', async () => {
    const history = { ...HISTORY, lines: [line(['20:46:11', 'failed', '', ''], { failed: true })] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const shown = page.el('panel-lines').children.filter(c => !/\bhead\b/.test(c.className));
    assert.equal(shown[0].children[1].textContent, 'failed');
    assert.equal(shown[0].children[1].className, 'failed', 'in red');
});

test('with the date on, the history rows take the wide time class', async () => {
    const history = { ...HISTORY, columns: ['date time', 'used', 'limit', 'Cur.'], wide: true,
        lines: [line(['2026-10-08 20:46:11', '260.66', '1000.00', 'USD'])] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    assert.match(page.el('panel-lines').children[1].className, /\bdate\b/);
    assert.match(page.el('panel-lines').children[0].className, /\bdate\b/, 'and so does the header');
    assert.deepEqual(head(page), [['date time', 'used', 'limit', 'Cur.']]);
});

test('the date setting is in the settings view and is sent with the rest', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    page.el('set-historyDate').checked = true;
    await page.click('settings-apply');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).historyDate, true);
});

// ---- the history window is as wide as its table

test('the history window is as wide as the table needs plus the scrollbar, when that is more than the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('table-probe').rect = { width: 563.4, height: 60 };

    await page.click('history-button');

    // 564 (the table) and 16 (the panel's padding) and 15 (the scrollbar).
    assert.equal(page.window.contentSize(), '595,420,1,history');
});

test('a table narrower than the row leaves the window the width of the row and the scrollbar', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('table-probe').rect = { width: 300, height: 60 };

    await page.click('history-button');

    assert.equal(page.window.contentSize(), '415,420,1,history');
});

test('the table is measured in a copy of it: the header and the rows with the most text, in the classes of the real ones', async () => {
    const history = { ...HISTORY, columns: ['date time', 'used', 'limit', 'Cur.', '\u0394 used'], wide: true, lines: [
        line(['2026-10-08 20:46:11', '1.00', '2.00', 'USD', '+0.05']),
        line(['2026-10-08 20:45:11', '1234567.89', '10000000.00', 'USD', '']),
        line(['2026-10-08 20:44:11', '1.00', '2.00', '', '+1,234.50']),
        line(['2026-10-08 20:43:11', '999999.99', '9999999.99', 'EUR', '']),
        line(['2026-10-08 20:42:11', '5.00', '6.00', 'USD', '']),
    ] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const probe = page.el('table-probe').children;
    assert.equal(probe.length, 4, 'the header and three rows');
    assert.match(probe[0].className, /\bhead\b/);
    assert.match(probe[0].className, /\bcols-5\b/, 'the same columns as the table');
    assert.match(probe[0].className, /\bdate\b/);
    assert.deepEqual(probe[0].children.map(c => c.textContent), ['date time', 'used', 'limit', 'Cur.', '\u0394 used']);
    const texts = probe.slice(1).map(r => r.children[1].textContent);
    assert.ok(texts.includes('1234567.89') && texts.includes('999999.99'), 'the widest rows are in it: ' + texts);
});

test('the table is measured again at every ask, since a font that loads late changes its width and the window must follow', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('table-probe').rect = { width: 300, height: 60 };
    await page.click('history-button');
    assert.equal(page.window.contentSize(), '415,420,1,history', 'first measured while the narrow fallback font was in use');

    page.el('table-probe').rect = { width: 507.2, height: 60 };

    assert.equal(page.window.contentSize(), '539,420,1,history', '508 and 16 and 15, with no new rows to prompt it');
});

test('a table that has not been measured, or has no rows, adds nothing to the width', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { exists: false, columns: [], wide: false, note: 'There is no usage history yet.', total: 0, lines: [] } }) }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };

    await page.click('history-button');

    assert.equal(page.window.contentSize(), '415,420,1,history');
});

test('the width is the history\'s own: the log and the error log keep theirs, the error log half the log\'s', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('table-probe').rect = { width: 700, height: 60 };

    await page.click('log-button');
    assert.equal(page.window.contentSize(), '1215,420,2,log');
    await page.click('errors-button');
    assert.equal(page.window.contentSize(), '615,420,2,errors');
});

test('the last width measured is kept while the history is closed, so it opens at once as wide as it was', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('table-probe').rect = { width: 563.4, height: 60 };
    await page.click('history-button');
    await page.click('history-button');

    await page.click('history-button');

    assert.equal(page.window.contentSize(), '595,420,1,history');
});

// ---- the button of the open panel is green

const classOf = (page, id) => page.el(id).className.split(/\s+/);

test('no button is green while no panel is shown', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    for (const id of ['log-button', 'history-button', 'errors-button', 'settings-button']) {
        assert.ok(!classOf(page, id).includes('active'), id);
    }
});

test('the button of the panel that is shown is green, and goes back when it is closed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('history-button');
    assert.ok(classOf(page, 'history-button').includes('active'));
    assert.ok(classOf(page, 'history-button').includes('icon') && classOf(page, 'history-button').includes('tiny'), 'its other classes stay');

    await page.click('history-button');
    assert.ok(!classOf(page, 'history-button').includes('active'), 'back to gray');
});

test('opening another panel turns the new button green and gives the old one its colour back; never two', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const greens = () => ['log-button', 'history-button', 'errors-button', 'settings-button'].filter(id => classOf(page, id).includes('active'));

    await page.click('log-button');
    assert.deepEqual(greens(), ['log-button']);
    await page.click('errors-button');
    assert.deepEqual(greens(), ['errors-button']);
    await page.click('settings-button');
    assert.deepEqual(greens(), ['settings-button']);
    await page.click('history-button');
    assert.deepEqual(greens(), ['history-button']);
    await page.click('history-button');
    assert.deepEqual(greens(), []);
});

test('Apply and Cancel close the settings, so its button is no longer green', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');
    assert.deepEqual(classOf(page, 'settings-button').includes('active'), true);
    await page.click('settings-cancel');
    assert.ok(!classOf(page, 'settings-button').includes('active'));

    await page.click('settings-button');
    await page.click('settings-apply');
    assert.ok(!classOf(page, 'settings-button').includes('active'));
});

// ---- leaving the settings brings back the view that was shown before

test('Cancel brings back the history that was open when the settings were opened, and asks for it afresh', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    const loads = () => page.calls.filter(c => c.url === '/api/history').length;
    const before = loads();

    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);
    await page.click('settings-cancel');

    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('panel').hidden, false, 'the panel is still there');
    assert.equal(page.el('history-button').title, 'Hide the usage history');
    assert.ok(classOf(page, 'history-button').includes('active'), 'and its button is green again');
    assert.ok(!classOf(page, 'settings-button').includes('active'));
    assert.ok(loads() > before, 'read again, so that it shows what the settings now say');
    assert.equal(rows(page).length, 2);
});

test('Apply brings back the view that was open, and reads it with the new settings', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('history-button');
    await page.click('settings-button');
    page.el('set-historyDeltaTime').checked = true;
    state.history = () => ({ status: 200, body: { ...HISTORY, columns: ['time', 'used', 'limit', 'Cur.', '\u0394 time'],
        lines: [line(['20:46:11', '260.66', '1000.00', 'USD', '63 s']), line(['20:44:12', '260.36', '1000.00', 'USD', ''])] } });

    await page.click('settings-apply');

    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('history-button').title, 'Hide the usage history');
    assert.deepEqual(head(page)[0].slice(4), ['\u0394 time']);
    assert.equal(rows(page)[0][4], '63 s', 'the new column is there');
});

test('the settings button again also brings the earlier view back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('log-button');
    await page.click('settings-button');

    await page.click('settings-button');

    assert.equal(page.el('log-button').title, 'Hide the log');
    assert.equal(page.el('settings-view').hidden, true);
});

test('the log and the error log come back too', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    for (const [button, title] of [['log-button', 'Hide the log'], ['errors-button', 'Hide the error log']]) {
        await page.click(button);
        await page.click('settings-button');
        await page.click('settings-cancel');
        assert.equal(page.el(button).title, title, button);
        await page.click(button); // close it again
    }
});

test('with nothing shown before the settings, nothing is shown after them', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');
    await page.click('settings-cancel');

    assert.equal(page.el('panel').hidden, true);
    for (const id of ['log-button', 'history-button', 'errors-button', 'settings-button']) {
        assert.ok(!classOf(page, id).includes('active'), id);
    }
});

test('another panel\'s button while the settings are shown shows that panel, and what was shown before is forgotten', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    await page.click('settings-button');

    await page.click('log-button');
    assert.equal(page.el('log-button').title, 'Hide the log');
    assert.equal(page.el('settings-view').hidden, true);

    await page.click('settings-button');
    await page.click('settings-cancel');
    assert.equal(page.el('log-button').title, 'Hide the log', 'back to the log, which was shown just before the settings, not the history');
    assert.equal(page.el('history-button').title, 'Show the usage history');

    await page.click('log-button');
    await page.click('settings-button');
    await page.click('log-button'); // forgets the history that was never open again
    await page.click('log-button');
    assert.equal(page.el('panel').hidden, true);
});

test('the window asks for the remembered panel again when it comes back: the history with its own size string', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }), { scrollbar: 15 });
    page.el('top').rect = { width: 399.2, height: 41.5 };
    await page.click('history-button');
    await page.click('settings-button');
    page.el('app').rect = { width: 399.2, height: 500 };
    assert.equal(page.window.contentSize(), '400,500,3,settings');

    await page.click('settings-cancel');

    assert.equal(page.window.contentSize(), '415,420,1,history', 'ten rows tall again, with the panel named for the host to restore its height');
});

test('a failed Apply keeps the settings open and does not bring anything back', async () => {
    const page = await load(backendOf({
        config: CONFIG, status: SPEND_STATUS,
        postSettings: () => ({ status: 400, body: { error: 'The usage interval must be a whole number of seconds from 5 to 3600.' } }),
    }));
    await page.click('history-button');
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = '4';

    await page.click('settings-apply');

    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('history-button').title, 'Show the usage history');
});

test('the two new history switches are in the settings, are posted, and are on in the defaults', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-historyZeroLines').checked, true);
    assert.equal(page.el('set-historyFailedLines').checked, true);

    page.el('set-historyZeroLines').checked = false;
    page.el('set-historyFailedLines').checked = false;
    await page.click('settings-apply');

    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.historyZeroLines, false);
    assert.equal(sent.historyFailedLines, false);
});

test('Restore defaults switches the two history lines back on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-historyZeroLines').checked = false;
    page.el('set-historyFailedLines').checked = false;

    await page.click('settings-restore');

    assert.equal(page.el('set-historyZeroLines').checked, true);
    assert.equal(page.el('set-historyFailedLines').checked, true);
});

// ---- the buttons of the history, log and error log can be switched off; the gear never goes

const withShow = (status, show) => ({ ...status, display: { ...status.display, show: { percentage: false, interval: false, deltaUsed: false, deltaTime: false, ...show } } });
const ALL_ICONS = { historyIcon: true, logIcon: true, errorIcon: true };

test('every button is there when the backend says all three are on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, ALL_ICONS) }));

    for (const id of ['log-button', 'history-button', 'errors-button', 'settings-button']) {
        assert.equal(page.el(id).hidden, false, id);
    }
});

test('each button goes when its own flag is off, and the others stay', async () => {
    for (const [flag, id] of [['historyIcon', 'history-button'], ['logIcon', 'log-button'], ['errorIcon', 'errors-button']]) {
        const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, [flag]: false }) }));

        for (const other of ['log-button', 'history-button', 'errors-button']) {
            assert.equal(page.el(other).hidden, other === id, flag + ' / ' + other);
        }
        assert.equal(page.el('settings-button').hidden, false, 'the gear is always there');
    }
});

test('with all three off only the gear is left, and it still opens the settings', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { historyIcon: false, logIcon: false, errorIcon: false }) }));

    for (const id of ['log-button', 'history-button', 'errors-button']) {
        assert.equal(page.el(id).hidden, true, id);
    }
    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);
});

test('a button that was off comes back at the next poll when it is switched on', async () => {
    const state = { config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, logIcon: false }) };
    const page = await load(backendOf(state));
    assert.equal(page.el('log-button').hidden, true);

    state.status = withShow(WITH_CHANGE, ALL_ICONS);
    await page.firePoll();

    assert.equal(page.el('log-button').hidden, false);
});

test('Apply that switches off the button of the panel the settings would give back gives nothing back; Cancel still does', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    await page.click('settings-button');
    page.el('set-showHistoryIcon').checked = false;

    await page.click('settings-apply');

    assert.equal(page.el('panel').hidden, true, 'the history is not shown again');
    assert.equal(page.el('settings-view').hidden, true);

    await page.click('log-button');
    await page.click('settings-button');
    page.el('set-showHistoryIcon').checked = false;
    await page.click('settings-cancel');
    assert.equal(page.el('log-button').title, 'Hide the log', 'Cancel changes nothing, so the log comes back');
});

test('Apply that switches off a different panel\'s button still gives the earlier panel back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    await page.click('settings-button');
    page.el('set-showLogIcon').checked = false;

    await page.click('settings-apply');

    assert.equal(page.el('history-button').title, 'Hide the usage history');
});

test('the four new switches are in the form, posted, and Restore defaults gives the defaults back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-showCurrency').checked, false, 'the symbol is off by default');
    for (const id of ['set-showHistoryIcon', 'set-showLogIcon', 'set-showErrorIcon']) {
        assert.equal(page.el(id).checked, true, id);
    }

    page.el('set-showCurrency').checked = true;
    page.el('set-showErrorIcon').checked = false;
    await page.click('settings-apply');
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.showCurrency, true);
    assert.equal(sent.showErrorIcon, false);
    assert.equal(sent.showHistoryIcon, true);

    await page.click('settings-button');
    page.el('set-showCurrency').checked = true;
    page.el('set-showLogIcon').checked = false;
    await page.click('settings-restore');
    assert.equal(page.el('set-showCurrency').checked, false);
    assert.equal(page.el('set-showLogIcon').checked, true);
});

test('the amounts are shown as the backend gives them, with or without the symbol', async () => {
    const status = withShow(WITH_CHANGE, ALL_ICONS);
    status.display = { ...status.display, spend: { ...status.display.spend, used: '$186.02', limit: '$1,000.00' } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('used').textContent, '$186.02');
    assert.equal(page.el('limit').textContent, '$1,000.00');
});

// ---- the blue notes

test('the history note that says lines are left out is blue: it gets the highlight class from the backend\'s flag', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, total: 236,
        note: 'Showing 136 of 236 lines: 82 zero usage and 18 failed hidden.', noteHighlight: true } }) }));

    await page.click('history-button');

    assert.equal(page.el('panel-note').textContent, 'Showing 136 of 236 lines: 82 zero usage and 18 failed hidden.');
    assert.match(page.el('panel-note').className, /\bhighlight\b/);
});

test('the history\'s notes about there being nothing, and the log\'s note, are not blue', async () => {
    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, exists: false, total: 0, lines: [],
        note: 'There is no usage history yet.', noteHighlight: false } }) }));
    await none.click('history-button');
    assert.doesNotMatch(none.el('panel-note').className, /highlight/);

    const cut = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, truncated: true } }) }));
    await cut.click('log-button');
    assert.equal(cut.el('panel-note').hidden, false);
    assert.doesNotMatch(cut.el('panel-note').className, /highlight/);
});

test('the error log\'s empty note is blue', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, errors: () => ({ status: 200, body: { entries: [] } }) }));

    await page.click('errors-button');

    assert.equal(page.el('panel-note').textContent, 'There are no errors in this run.');
    assert.match(page.el('panel-note').className, /\bhighlight\b/);
});

test('the blue goes when the note does: switching from a blue note to a panel with another note or none', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, errors: () => ({ status: 200, body: { entries: [] } }) }));
    await page.click('errors-button');
    assert.match(page.el('panel-note').className, /highlight/);

    await page.click('history-button');

    assert.doesNotMatch(page.el('panel-note').className, /highlight/);
});

// ---- the start line is green in the history and gray in the log

test('the history\'s start line is green text and has no gray background class; the failed word is still red', async () => {
    const history = { ...HISTORY, lines: [
        line(['20:46:11', 'failed', '', ''], { failed: true }),
        line(['20:44:12', 'failed', '', ''], { failed: true, start: true, title: 'The program started here' })] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const shown = page.el('panel-lines').children.filter(c => !/\bhead\b/.test(c.className));
    assert.match(shown[1].className, /\bstart\b/);
    assert.doesNotMatch(shown[1].className, /\bmark\b/);
    assert.equal(shown[1].children[1].className, 'failed', 'the failed startup line keeps its red word');
});

test('the log\'s start line keeps the light gray background class and is not green', async () => {
    const log = { ...LOG, lines: ['2026-10-08 19:00:00 INFO    [UsageApp] Starting java-aip-usage v0.20', '2026-10-08 19:00:01 INFO    [UsageService] Usage refresh succeeded'] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: log }) }));

    await page.click('log-button');

    const shown = page.el('panel-lines').children;
    const start = shown.find(c => /Starting java-aip-usage/.test(c.children.map(x => x.textContent).join(' ')));
    assert.match(start.className, /\bmark\b/);
    assert.doesNotMatch(start.className, /\bstart\b/);
});

// ---- every button while the settings are open

const BUTTONS = ['log-button', 'history-button', 'errors-button', 'settings-button'];
const NO_ICONS = { historyIcon: false, logIcon: false, errorIcon: false };
const visible = page => BUTTONS.filter(id => !page.el(id).hidden);

test('with all three switched off only the gear is there, and opening the settings does not bring them back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    assert.deepEqual(visible(page), ['settings-button']);

    await page.click('settings-button');

    assert.deepEqual(visible(page), ['settings-button'], 'the buttons follow their switches while the settings are shown too');
});

/** Sets a control of the settings as a click would, and tells the page. */
async function change(page, id, value) {
    if (typeof value === 'boolean') {
        page.el(id).checked = value;
    } else {
        page.el(id).value = value;
    }
    await page.el(id).listeners.change();
    await settle();
}

const ROW_ELEMENTS = {
    showTime: 'time', showPercentage: 'percent', showUsed: 'used', showLimit: 'limit', showRefresh: 'refresh', showCountdown: 'countdown',
    showInterval: 'interval', showDeltaUsed: 'delta-used', showDeltaTime: 'delta-time', showDuration: 'duration',
    showLogIcon: 'log-button', showHistoryIcon: 'history-button', showErrorIcon: 'errors-button',
};

test('a click on a checkbox of the main view shows in the row at once, and nothing is sent', async () => {
    const everything = { percentage: true, interval: true, deltaUsed: true, deltaTime: true, historyIcon: true, logIcon: true, errorIcon: true };
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, everything) }));
    await page.click('settings-button');
    for (const key of Object.keys(ROW_ELEMENTS)) {
        await change(page, 'set-' + key, true);
    }
    const polls = page.calls.filter(c => c.url === '/api/status').length;

    for (const [key, id] of Object.entries(ROW_ELEMENTS)) {
        assert.equal(page.el(id).hidden, false, id + ' is there to begin with');
        await change(page, 'set-' + key, false);
        assert.equal(page.el(id).hidden, true, key + ' off takes ' + id + ' away at once');
        await change(page, 'set-' + key, true);
        assert.equal(page.el(id).hidden, false, key + ' on brings ' + id + ' back');
    }
    assert.equal(settingsPosts(page).length, 0, 'a preview sends nothing');
    assert.equal(page.calls.filter(c => c.url === '/api/status').length, polls, 'and needs no poll');
});

test('Restore defaults shows in the row at once as well', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    await page.click('settings-button');
    assert.deepEqual(visible(page), ['settings-button']);

    await page.click('settings-restore');

    assert.deepEqual(visible(page), BUTTONS, 'the defaults have the three buttons');
});

test('there are no buttons that set the whole main view at once', () => {
    assert.ok(!INDEX_IDS.includes('settings-maximum') && !INDEX_IDS.includes('settings-minimum'));
});

// ---- leaving the settings by a button of the row saves them

test('the gear again saves what was changed, as Apply does, and closes the view', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await change(page, 'set-showDeltaUsed', true);
    page.el('set-usageIntervalSeconds').value = '300';

    await page.click('settings-button');

    assert.equal(settingsPosts(page).length, 1);
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.showDeltaUsed, true);
    assert.equal(sent.usageIntervalSeconds, 300);
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('panel').hidden, true);
});

test('the button of another panel saves the settings and then shows its panel', async () => {
    for (const [button, title] of [['log-button', 'Hide the log'], ['history-button', 'Hide the usage history'], ['errors-button', 'Hide the error log']]) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
        await page.click('settings-button');
        page.el('set-logResponse').checked = true;

        await page.click(button);

        assert.equal(settingsPosts(page).length, 1, button);
        assert.equal(JSON.parse(settingsPosts(page)[0].body).logResponse, true, button);
        assert.equal(page.el('settings-view').hidden, true, button);
        assert.equal(page.el(button).title, title, 'its panel is shown');
    }
});

test('leaving settings in which nothing was changed sends nothing', async () => {
    for (const button of ['settings-button', 'log-button']) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
        await page.click('settings-button');

        await page.click(button);

        assert.equal(settingsPosts(page).length, 0, button);
        assert.equal(page.el('settings-view').hidden, true, button);
    }
});

test('when the save fails on leaving, the settings stay with the reason and the other panel is not shown', async () => {
    const refusing = { config: CONFIG, status: SPEND_STATUS, postSettings: () => ({ status: 400, body: { error: 'The usage interval must be a whole number of seconds from 5 to 3600.' } }) };
    for (const button of ['settings-button', 'history-button']) {
        const page = await load(backendOf(refusing));
        await page.click('settings-button');
        page.el('set-usageIntervalSeconds').value = '4';

        await page.click(button);

        assert.equal(page.el('settings-view').hidden, false, button + ': the view stays');
        assert.equal(page.el('settings-error').textContent, 'The usage interval must be a whole number of seconds from 5 to 3600.');
        assert.equal(page.el('history-button').title, 'Show the usage history', 'no other panel');
    }
});

test('an interval that is no number keeps the settings open on leaving, and sends nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = 'abc';

    await page.click('log-button');

    assert.equal(settingsPosts(page).length, 0);
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('settings-error').textContent, 'The interval must be a whole number of seconds.');
    assert.equal(page.el('log-button').title, 'Show the log');
});

test('Cancel is the one way to leave without saving, whatever was changed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await change(page, 'set-showTime', false);
    page.el('set-usageIntervalSeconds').value = '300';

    await page.click('settings-cancel');

    assert.equal(settingsPosts(page).length, 0);
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('time').hidden, false, 'and the row is as the backend has it');
});

test('after a save on leaving the row keeps what was previewed', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    await change(page, 'set-showTime', false);
    state.status = withShow(withDisplay(SPEND_STATUS), { time: false });

    await page.click('settings-button');

    assert.equal(page.el('time').hidden, true);
});

// ---- the least width of the settings

test('while the settings are shown the window is as wide as they need, when that is more than the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('app').rect = { left: 0, width: 400, height: 300 };
    page.el('settings-form').rect = { left: 8, width: 500, height: 200 };
    page.el('settings-buttons').rect = { left: 8, width: 260, height: 20 };

    assert.equal(page.window.contentSize(), '516,300,3,settings', 'the form and the room at its left again at its right');

    page.el('settings-buttons').rect = { left: 8, width: 540, height: 20 };
    assert.equal(page.window.contentSize(), '556,300,3,settings', 'the buttons in one row, when they are the wider');
    assert.equal(page.el('settings-form').style.minWidth, '', 'the form is given back its width after the measuring');
});

test('the settings never make the window narrower than the row, and the width goes back when they are left', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('app').rect = { left: 0, width: 400, height: 300 };
    page.el('settings-form').rect = { left: 8, width: 200, height: 200 };
    page.el('settings-buttons').rect = { left: 8, width: 200, height: 20 };

    assert.equal(page.window.contentSize(), '400,300,3,settings', 'the row is wider');

    page.el('settings-form').rect = { left: 8, width: 900, height: 200 };
    await page.click('settings-cancel');
    assert.equal(page.window.contentSize(), '400,42,0', 'the row alone again');
});

test('the request duration is in the row when its switch is on, as the backend wrote it, and off by default', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(page.el('duration').hidden, true, 'off unless the backend says so');

    const status = withShow(withDisplay(SPEND_STATUS), { duration: true });
    status.display = { ...status.display, duration: { text: '1s034ms', tooltip: 'How long the latest usage request took' } };
    const shown = await load(backendOf({ config: CONFIG, status }));

    assert.equal(shown.el('duration').hidden, false);
    assert.equal(shown.el('duration').textContent, '1s034ms');
    assert.equal(shown.el('duration').title, 'How long the latest usage request took');
    assert.equal(shown.el('duration').className, 'delta delta-duration');
});

test('the request duration with its switch on and no request ended yet is a gray box, with a reading on show as well', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(withDisplay(SPEND_STATUS), { duration: true }) }));

    assert.equal(page.el('duration').hidden, false);
    assert.equal(page.el('duration').className, 'delta delta-duration skeleton');
    assert.equal(page.el('duration').textContent.trim(), '');
    assert.equal(page.el('duration').title, 'How long the latest usage request took');
});

test('Stay on top is a setting of the window: sent with the others, and not previewed in the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-stayOnTop').checked, false, 'off by default');
    assert.equal(page.el('set-stayOnTop').listeners.change, undefined, 'the row has nothing to show of it');

    page.el('set-stayOnTop').checked = true;
    await page.click('settings-apply');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).stayOnTop, true);
});

test('Title bar is a setting of the window: on by default, sent with the others, and not previewed in the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-showTitleBar').checked, true, 'on by default');
    assert.equal(page.el('set-showTitleBar').listeners.change, undefined);

    page.el('set-showTitleBar').checked = false;
    await page.click('settings-apply');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).showTitleBar, false);
});

test('the page names every open panel in its size, the settings too, so that the host can give the window its title bar', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.match(page.window.contentSize(), /^\d+,\d+,0$/, 'no panel, no name');

    for (const [button, name] of [['settings-button', 'settings'], ['log-button', 'log'], ['history-button', 'history'], ['errors-button', 'errors']]) {
        await page.click(button);
        assert.ok(page.window.contentSize().endsWith(',' + name), button + ': ' + page.window.contentSize());
        await page.click(button);
        assert.match(page.window.contentSize(), /^\d+,\d+,0$/, button + ' closed again');
    }
});

test('the word of is there only when both amounts are, and the amounts go when all three of their figures are off', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(page.el('of').hidden, false);
    await page.click('settings-button');

    await change(page, 'set-showUsed', false);
    assert.equal(page.el('of').hidden, true);
    assert.equal(page.el('limit').hidden, false, 'the budget alone');

    await change(page, 'set-showLimit', false);
    await change(page, 'set-showPercentage', false);
    assert.equal(page.el('spend').hidden, true);
});

test('the time format and the currency symbol are previewed too, from the forms the backend sent', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    await change(page, 'set-timeFormat', 'hh:mm');
    assert.equal(page.el('time').textContent, '14:24');
    await change(page, 'set-timeFormat', 'hh:mm:ss');
    assert.equal(page.el('time').textContent, '14:24:53');

    await change(page, 'set-showCurrency', true);
    assert.equal(page.el('used').textContent, '$186.02');
    assert.equal(page.el('limit').textContent, '$1,000.00');
    await change(page, 'set-showCurrency', false);
    assert.equal(page.el('used').textContent, '186.02');
});

test('Cancel gives the row back as the backend has it; Apply keeps what was previewed until the poll brings it', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    await change(page, 'set-showTime', false);
    assert.equal(page.el('time').hidden, true);

    await page.click('settings-cancel');
    assert.equal(page.el('time').hidden, false, 'nothing was changed');

    await page.click('settings-button');
    await change(page, 'set-showTime', false);
    state.status = withShow(withDisplay(SPEND_STATUS), { time: false });
    await page.click('settings-apply');
    assert.equal(page.el('time').hidden, true, 'applied, and the backend says so as well');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).showTime, false);
});

test('every element of the row can be switched off by the backend, down to the gear', async () => {
    const nothing = {
        time: false, percentage: false, used: false, limit: false, refresh: false, countdown: false, interval: false,
        deltaUsed: false, deltaTime: false, duration: false, logIcon: false, historyIcon: false, errorIcon: false,
    };
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, nothing) }));

    for (const id of Object.values(ROW_ELEMENTS)) {
        assert.equal(page.el(id).hidden, true, id);
    }
    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('settings-button').hidden, false, 'the gear is always there');
});

test('before the first reading each element that is switched on is a gray box with its tooltip, and there is no word', async () => {
    const waiting = { refreshing: true, stale: false, nextRefreshInSeconds: null, error: null, usage: null };
    const everything = { percentage: true, interval: true, deltaUsed: true, deltaTime: true, duration: true };
    const page = await load(backendOf({ config: CONFIG, status: withShow(withDisplay(waiting), everything) }));

    const tooltips = {
        'time': 'Last update', 'percent': 'Share of the budget spent', 'used': 'Credits used', 'limit': 'Credit budget',
        'countdown': 'Seconds until the next refresh (negative when overdue)',
        'delta-used': 'Change in the amount used since the previous reading', 'delta-time': 'Time since the previous reading',
        'duration': 'How long the latest usage request took',
    };
    for (const [id, tooltip] of Object.entries(tooltips)) {
        assert.equal(page.el(id).hidden, false, id);
        assert.match(page.el(id).className, /\bskeleton\b/, id + ' is a gray box');
        assert.equal(page.el(id).title, tooltip, id);
        assert.equal(page.el(id).textContent.trim(), '', id + ' has no text');
    }
    assert.doesNotMatch(page.el('time').title, /:/, 'at the start the tooltip is the element\'s own, with no reason after it');
    assert.doesNotMatch(page.el('interval').className, /skeleton/, 'the interval is known from the settings');
    assert.equal(page.el('refresh').hidden, false);
});

test('an element that is switched off has no gray box, and the boxes go with the first reading', async () => {
    const waiting = { refreshing: true, stale: false, nextRefreshInSeconds: null, error: null, usage: null };
    const state = { config: CONFIG, status: withShow(withDisplay(waiting), { percentage: false, time: false }) };
    const page = await load(backendOf(state));
    assert.equal(page.el('time').hidden, true);
    assert.equal(page.el('percent').hidden, true);
    assert.match(page.el('used').className, /skeleton/);

    state.status = SPEND_STATUS;
    await page.firePoll();

    assert.equal(page.el('used').textContent, '186.02');
    assert.doesNotMatch(page.el('used').className, /skeleton/);
    assert.doesNotMatch(page.el('countdown').className, /skeleton/);
});

test('an extra button pressed in the settings opens its panel, the settings go, and its button stays while the panel is shown', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    await page.click('settings-button');

    await page.click('history-button');

    assert.equal(page.el('settings-view').hidden, true, 'the panel replaces the settings');
    assert.equal(page.el('history-button').title, 'Hide the usage history');
    assert.deepEqual(visible(page), ['history-button', 'settings-button'], 'the button of the shown panel stays, the other extras go');
    assert.ok(classOf(page, 'history-button').includes('active'));
});

test('closing that panel takes its button away again, since it is switched off', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    await page.click('settings-button');
    await page.click('history-button');

    await page.click('history-button');

    assert.equal(page.el('panel').hidden, true);
    assert.deepEqual(visible(page), ['settings-button']);
});

test('the button of a shown panel stays through the polls, whatever its switch says', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    await page.click('settings-button');
    await page.click('log-button');

    await page.firePoll();
    await page.firePoll();

    assert.equal(page.el('log-button').hidden, false);
    assert.equal(page.el('panel').hidden, false, 'and the panel is not closed');
});

test('a panel whose switch is turned off at a poll while it is shown is not closed, and its button stays', async () => {
    const state = { config: CONFIG, status: withShow(WITH_CHANGE, ALL_ICONS) };
    const page = await load(backendOf(state));
    await page.click('history-button');

    state.status = withShow(WITH_CHANGE, { ...ALL_ICONS, historyIcon: false });
    await page.firePoll();

    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('history-button').hidden, false);
    assert.equal(page.el('log-button').hidden, false, 'the others are as configured');
    await page.click('history-button');
    assert.equal(page.el('history-button').hidden, true, 'it goes when the panel is closed');
});

test('the row\'s time since the previous reading is the backend\'s seconds', async () => {
    const status = withShow(WITH_CHANGE, { ...ALL_ICONS, deltaTime: true });
    status.display = { ...status.display, deltaTime: { text: '105s', tooltip: 'Time since the previous reading' } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('delta-time').textContent, '105s');
});

// ---- debug mode: a yellow button, a view of its own, and a red border while the fake backend is in use

const debugPosts = page => page.calls.filter(c => c.method === 'POST' && c.url === '/api/debug');

test('the debug button is there in debug mode and never otherwise', async () => {
    const plain = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(plain.el('debug-button').hidden, true);
    assert.equal(plain.calls.filter(c => c.url === '/api/debug').length, 0, 'and nothing is asked about debug settings');

    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    assert.equal(page.el('debug-button').hidden, false);
});

test('the debug button shows the debug settings, as the backend has them, with the scenarios it names', async () => {
    const state = { config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 200, body: { ...DEBUG, settings: { fakeBackend: true, fakeScenario: 'http-429', fakeToken: false } } }) };
    const page = await load(backendOf(state));

    await page.click('debug-button');

    assert.equal(page.el('debug-view').hidden, false);
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('debug-fakeBackend').checked, true);
    assert.equal(page.el('debug-fakeScenario').value, 'http-429');
    assert.deepEqual(page.el('debug-fakeScenario').children.map(o => o.value), DEBUG.scenarios);
    assert.equal(page.el('debug-fakeToken').checked, false);
    assert.equal(page.el('debug-button').title, 'Debug mode: hide the debug settings');
    assert.match(page.el('debug-button').className, /\bdebug\b/, 'yellow');
    assert.match(page.el('debug-button').className, /\bactive\b/);
    assert.ok(page.window.contentSize().endsWith(',3,debug'), page.window.contentSize());
});

test('Apply in the debug settings sends the three of them and closes the view; Cancel sends nothing', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('debug-button');
    page.el('debug-fakeBackend').checked = true;
    page.el('debug-fakeScenario').value = 'slow';

    await page.click('debug-apply');

    assert.deepEqual(JSON.parse(debugPosts(page)[0].body), { fakeBackend: true, fakeScenario: 'slow', fakeToken: false });
    assert.equal(page.el('debug-view').hidden, true);
    assert.equal(page.el('panel').hidden, true);

    await page.click('debug-button');
    page.el('debug-fakeToken').checked = true;
    await page.click('debug-cancel');
    assert.equal(debugPosts(page).length, 1, 'Cancel sent nothing');
    assert.equal(page.el('debug-view').hidden, true);
});

test('leaving the debug settings by a button of the row saves them, and sends nothing when nothing was changed', async () => {
    for (const button of ['debug-button', 'settings-button', 'log-button']) {
        const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
        await page.click('debug-button');
        await page.click(button);
        assert.equal(debugPosts(page).length, 0, button + ': nothing changed, nothing sent');
        assert.equal(page.el('debug-view').hidden, true, button);

        const changed = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
        await changed.click('debug-button');
        changed.el('debug-fakeBackend').checked = true;
        await changed.click(button);
        assert.equal(debugPosts(changed).length, 1, button + ': saved on leaving');
        assert.equal(JSON.parse(debugPosts(changed)[0].body).fakeBackend, true);
        assert.equal(changed.el('debug-view').hidden, true, button);
    }
});

test('the gear pressed in the debug settings shows the settings, and the debug button pressed in the settings saves them', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('debug-button');
    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('debug-view').hidden, true);

    page.el('set-logResponse').checked = true;
    await page.click('debug-button');

    assert.equal(settingsPosts(page).length, 1, 'the settings were saved on the way');
    assert.equal(page.el('debug-view').hidden, false);
    assert.equal(page.el('settings-view').hidden, true);
});

test('debug settings that cannot be saved keep the view open with the reason, and the other panel shut', async () => {
    const refusing = { config: DEBUG_CONFIG, status: SPEND_STATUS, postDebug: () => ({ status: 500, body: { error: 'The debug settings could not be applied: no port.' } }) };
    const page = await load(backendOf(refusing));
    await page.click('debug-button');
    page.el('debug-fakeBackend').checked = true;

    await page.click('history-button');

    assert.equal(page.el('debug-view').hidden, false);
    assert.equal(page.el('debug-error').textContent, 'The debug settings could not be applied: no port.');
    assert.equal(page.el('history-button').title, 'Show the usage history');
});

test('debug settings that cannot be read say so and have no form', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) }));

    await page.click('debug-button');

    assert.equal(page.el('debug-form').hidden, true);
    assert.match(page.el('debug-error').textContent, /^The debug settings could not be read: /);
    assert.equal(page.el('debug-apply').attrs.disabled, '');
});

test('while the fake backend is in use the window has its red border, and is larger by it', async () => {
    const state = { config: DEBUG_CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    assert.doesNotMatch(page.el('app').className, /\bfake\b/);
    assert.equal(page.window.contentSize(), '400,42,0');

    state.status = { ...SPEND_STATUS, fake: true };
    await page.firePoll();

    assert.match(page.el('app').className, /\bfake\b/);
    assert.equal(page.window.contentSize(), '406,48,0', '3 pixels of border on every side');

    state.status = SPEND_STATUS;
    await page.firePoll();
    assert.doesNotMatch(page.el('app').className, /\bfake\b/, 'and it goes when the fake backend is switched off');
    assert.equal(page.window.contentSize(), '400,42,0');
});

test('the border stays while a panel is open and while the figures are old', async () => {
    const stale = { ...SPEND_STATUS, fake: true, stale: true, error: { message: 'Anthropic returned HTTP 500.', at: NOW } };
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: stale }));
    assert.match(page.el('app').className, /stale/);
    assert.match(page.el('app').className, /\bfake\b/);

    await page.click('history-button');
    assert.match(page.el('app').className, /open/);
    assert.match(page.el('app').className, /\bfake\b/);
});

// ---- the log panel: the level and the date, chosen in the panel and saved at once

const LEVEL_LINES = {
    error: ['2026-10-08 19:00:03 SEVERE  [A] three'],
    warning: ['2026-10-08 19:00:02 WARNING [A] two', '2026-10-08 19:00:03 SEVERE  [A] three'],
    info: ['2026-10-08 19:00:01 INFO    [A] one', '2026-10-08 19:00:02 WARNING [A] two', '2026-10-08 19:00:03 SEVERE  [A] three'],
};
LEVEL_LINES.debug = LEVEL_LINES.info;

/** A backend that keeps the two choices as the real one does, and gives the lines of the level saved or asked for. */
function logBackend(saved, extra = {}) {
    const state = {
        config: CONFIG, status: SPEND_STATUS, saved,
        log: call => {
            const asked = /\?level=(\w+)/.exec(call.url);
            return { status: 200, body: { ...LOG, level: state.saved.level, date: state.saved.date, lines: LEVEL_LINES[asked ? asked[1] : state.saved.level] } };
        },
        postLog: body => { state.saved = body; return { status: 200, body }; },
        ...extra,
    };
    return state;
}
const logPosts = page => page.calls.filter(c => c.url === '/api/log' && c.method === 'POST');

test('the log panel comes up with the level and the date that are saved, and the line of the two is shown only with the log', async () => {
    const page = await load(backendOf(logBackend({ level: 'warning', date: false })));
    assert.equal(page.el('log-choices').hidden, true, 'no panel, no line');

    await page.click('log-button');

    assert.equal(page.el('log-choices').hidden, false);
    assert.equal(page.el('log-level').value, 'warning');
    assert.equal(page.el('log-date').checked, false);
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three', '19:00:02 WARNING [A] two'], 'the lines of the level, newest first, with no date');
    assert.equal(logPosts(page).length, 0, 'showing the panel saves nothing');

    await page.click('history-button');
    assert.equal(page.el('log-choices').hidden, true, 'the history has no such line');
    await page.click('errors-button');
    assert.equal(page.el('log-choices').hidden, true, 'nor has the error log');
    await page.click('errors-button');
    assert.equal(page.el('log-choices').hidden, true);
});

test('a level that is chosen takes effect at once on the log that is shown, is saved, and the log stands at its newest line', async () => {
    const state = logBackend({ level: 'info', date: false });
    const page = await load(backendOf(state));
    await page.click('log-button');
    assert.equal(lines(page).length, 3);
    page.el('panel-lines').scrollTop = 30;

    await change(page, 'log-level', 'error');

    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three']);
    assert.equal(page.el('panel-lines').scrollTop, 0);
    assert.deepEqual(JSON.parse(logPosts(page)[0].body), { level: 'error', date: false }, 'both are sent, at once, with no button');
    assert.deepEqual(state.saved, { level: 'error', date: false });
    assert.equal(page.el('log-error').hidden, true);

    await page.firePoll();
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three'], 'and the polls that follow keep to it');
    assert.equal(logPosts(page).length, 1, 'without saving again');
});

test('the date is switched on and off at once, and saved', async () => {
    const state = logBackend({ level: 'error', date: false });
    const page = await load(backendOf(state));
    await page.click('log-button');
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three']);
    page.el('panel-lines').scrollTop = 30;

    await change(page, 'log-date', true);

    assert.deepEqual(lines(page), ['2026-10-08 19:00:03 SEVERE  [A] three']);
    assert.equal(page.el('panel-lines').scrollTop, 0, 'at the newest line again');
    assert.deepEqual(state.saved, { level: 'error', date: true });

    await change(page, 'log-date', false);
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three']);
});

test('the choices are there again when the panel is shown the next time', async () => {
    const page = await load(backendOf(logBackend({ level: 'info', date: false })));
    await page.click('log-button');
    await change(page, 'log-level', 'warning');
    await change(page, 'log-date', true);
    await page.click('log-button');
    assert.equal(page.el('panel').hidden, true);

    await page.click('log-button');

    assert.equal(page.el('log-level').value, 'warning');
    assert.equal(page.el('log-date').checked, true);
    assert.deepEqual(lines(page), ['2026-10-08 19:00:03 SEVERE  [A] three', '2026-10-08 19:00:02 WARNING [A] two']);
});

test('a choice that cannot be saved holds for the panel as it is shown, and a red line says so', async () => {
    const state = logBackend({ level: 'info', date: false }, {
        postLog: () => ({ status: 500, body: { error: 'The log settings could not be saved: disk full' } }),
    });
    const page = await load(backendOf(state));
    await page.click('log-button');

    await change(page, 'log-level', 'error');

    assert.equal(page.el('log-error').hidden, false);
    assert.equal(page.el('log-error').textContent, 'The log settings could not be saved: disk full');
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three'], 'the level is asked for by name, since the saved one is another');
    assert.equal(page.el('log-level').value, 'error');
    await page.firePoll();
    assert.deepEqual(lines(page), ['19:00:03 SEVERE  [A] three'], 'and at the polls that follow');

    // The next change is saved: the line goes.
    state.postLog = body => { state.saved = body; return { status: 200, body }; };
    await change(page, 'log-level', 'warning');
    assert.equal(page.el('log-error').hidden, true);
    assert.equal(lines(page).length, 2);
});

test('a level with no lines says so in blue, and the line of the two choices stays', async () => {
    const state = logBackend({ level: 'error', date: false });
    state.log = () => ({ status: 200, body: { ...LOG, level: 'error', date: false, hidden: true, lines: [] } });
    const page = await load(backendOf(state));

    await page.click('log-button');

    assert.equal(page.el('panel-note').textContent, 'The log has no lines at this level.');
    assert.match(page.el('panel-note').className, /highlight/);
    assert.equal(page.el('panel-lines').hidden, true);
    assert.equal(page.el('log-choices').hidden, false);
});

test('the settings left for the log bring it back with its saved choices, and the settings do not send them', async () => {
    const state = logBackend({ level: 'warning', date: true });
    const page = await load(backendOf(state));
    await page.click('log-button');
    await page.click('settings-button');
    assert.equal(page.el('log-choices').hidden, true, 'the settings have no such line');
    page.el('set-logResponse').checked = true;

    await page.click('settings-button');

    const sent = JSON.parse(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST')[0].body);
    assert.equal('logLevel' in sent, false);
    assert.equal('logDate' in sent, false);
    assert.equal(page.el('log-choices').hidden, false);
    assert.equal(page.el('log-level').value, 'warning');
    assert.equal(page.el('log-date').checked, true);
});

// ---- a window with no title bar has a border of the page's own

const bareStatus = status => { const s = withDisplay(status); return { ...s, display: { ...s.display, titleBar: false } }; };

test('with the title bar off and no panel the window has its border, and is larger by it; a panel takes it away', async () => {
    const page = await load(backendOf({ config: CONFIG, status: bareStatus(SPEND_STATUS) }));

    assert.match(page.el('app').className, /\bbare\b/);
    assert.equal(page.window.contentSize(), '402,44,0', '1 pixel of border on every side');

    for (const button of ['history-button', 'log-button', 'errors-button', 'settings-button']) {
        await page.click(button);
        assert.doesNotMatch(page.el('app').className, /\bbare\b/, button + ': a panel is shown, so the window has its title bar');
    }
    await page.click('settings-cancel');
    await page.click('errors-button');
    assert.match(page.el('app').className, /\bbare\b/, 'and it is back when the panel is closed');
    assert.equal(page.window.contentSize(), '402,44,0');
});

test('with the title bar on, or with nothing said of it, there is no such border', async () => {
    const on = { ...withDisplay(SPEND_STATUS) };
    on.display = { ...on.display, titleBar: true };
    for (const status of [on, SPEND_STATUS]) {
        const page = await load(backendOf({ config: CONFIG, status }));
        assert.doesNotMatch(page.el('app').className, /\bbare\b/);
        assert.equal(page.window.contentSize(), '400,42,0');
    }
});

test('the border of a window with no title bar is left out while the red border of the fake backend is shown', async () => {
    const state = { config: DEBUG_CONFIG, status: { ...bareStatus(SPEND_STATUS), fake: true } };
    const page = await load(backendOf(state));
    assert.match(page.el('app').className, /\bfake\b/);
    assert.doesNotMatch(page.el('app').className, /\bbare\b/);
    assert.equal(page.window.contentSize(), '406,48,0', 'the red border alone');

    state.status = bareStatus(SPEND_STATUS);
    await page.firePoll();
    assert.match(page.el('app').className, /\bbare\b/, 'in debug mode with the fake backend off it is there');
    assert.doesNotMatch(page.el('app').className, /\bfake\b/);
    assert.equal(page.window.contentSize(), '402,44,0');
});

// ---- the window with no title bar is moved by dragging it: the page says where a drag may begin

test('a drag may begin anywhere but on a button or a control', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const hit = selectorHit => ({ closest: selector => (selector.split(/,\s*/).includes(selectorHit) ? {} : null) });
    const asked = [];
    const under = { '10,20': hit('span'), '200,20': hit('button'), '250,300': hit('select'), '260,300': hit('input'), '270,300': hit('label') };
    global.document.elementFromPoint = (x, y) => { asked.push(x + ',' + y); return under[x + ',' + y] || null; };

    assert.equal(page.window.dragStartsAt(10, 20), true, 'a figure of the row');
    assert.equal(page.window.dragStartsAt(200, 20), false, 'a button of the row, or the icon in it: it does what it does');
    assert.equal(page.window.dragStartsAt(250, 300), false, 'a dropdown');
    assert.equal(page.window.dragStartsAt(260, 300), false, 'a checkbox or a field');
    assert.equal(page.window.dragStartsAt(270, 300), false, 'the text of a checkbox');
    assert.equal(page.window.dragStartsAt(5, 5), true, 'the border, or the room between the elements: nothing of the page is there');
    assert.deepEqual(asked, ['10,20', '200,20', '250,300', '260,300', '270,300', '5,5'], 'the place is asked as given');
});

// ---- the button of Main view that selects every element of the row, or none

const ELEMENTS = ['showTime', 'showPercentage', 'showUsed', 'showLimit', 'showRefresh', 'showCountdown', 'showInterval', 'showDeltaUsed',
    'showDeltaTime', 'showDuration', 'showLogIcon', 'showHistoryIcon', 'showErrorIcon'];
const checkedOf = page => ELEMENTS.filter(key => page.el('set-' + key).checked);

test('the button switches every element on when not all are on, and all off when all are, and leaves the two properties alone', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.ok(checkedOf(page).length > 0 && checkedOf(page).length < 13, 'some are on at the start');
    const currency = page.el('set-showCurrency').checked;
    const format = page.el('set-timeFormat').value;

    await page.click('set-all');
    assert.deepEqual(checkedOf(page), ELEMENTS, 'all thirteen on');

    await page.click('set-all');
    assert.deepEqual(checkedOf(page), [], 'all on, so all off');

    await page.click('set-all');
    assert.deepEqual(checkedOf(page), ELEMENTS, 'none on is not all on: all on again');

    page.el('set-showInterval').checked = false;
    await page.click('set-all');
    assert.deepEqual(checkedOf(page), ELEMENTS, 'one off: all on');

    assert.equal(page.el('set-showCurrency').checked, currency, 'the currency symbol is not an element');
    assert.equal(page.el('set-timeFormat').value, format);
    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0, 'nothing is sent by the button');
});

test('what the button does is shown in the row at once, saved with the view, and put back by Cancel', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    assert.equal(page.el('delta-used').hidden, true, 'the row is the backend\'s until something is clicked');

    await page.click('set-all');
    assert.equal(page.el('percent').hidden, false, 'the percentage is in the row before anything is saved');
    assert.equal(page.el('delta-used').hidden, false);

    await page.click('set-all');
    for (const id of ['time', 'spend', 'refresh', 'countdown', 'interval', 'delta-used', 'delta-time', 'duration', 'log-button', 'history-button', 'errors-button']) {
        assert.equal(page.el(id).hidden, true, id + ' is gone from the row');
    }
    assert.equal(page.el('settings-button').hidden, false, 'the gear is always there');

    await page.click('settings-cancel');
    assert.equal(page.el('time').hidden, false, 'Cancel gives the row back');
    assert.equal(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').length, 0);

    await page.click('settings-button');
    await page.click('set-all');
    await page.click('settings-apply');
    const sent = JSON.parse(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST')[0].body);
    assert.deepEqual(ELEMENTS.filter(key => sent[key] === true), ELEMENTS, 'saved as any other click is');
});

test('the button does nothing before the settings have been read', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('set-all');

    assert.deepEqual(checkedOf(page), []);
    assert.equal(page.el('time').hidden, false, 'the row is as it was');
});

// ---- an answer that came and has no spend says so while the mouse is on one of its gray boxes

const SEAT = 'Received, but this account reports no spend: it looks like a seat-based account, which is not supported yet.';
const hover = async (page, id, type) => { page.el(id).listeners[type](); await settle(); };

test('a reading with no spend has no line of its own until the mouse is on a gray box, and loses it when the mouse leaves', async () => {
    const seat = { ...NO_USAGE_STATUS, usage: { ...NO_USAGE_STATUS.usage, otherLimits: true } };
    const state = { config: CONFIG, status: seat };
    const page = await load(backendOf(state));

    assert.equal(page.el('hover-note').hidden, true, 'nothing is said while the mouse is elsewhere');
    assert.equal(page.el('note').hidden, true, 'and it is no message that is always shown');
    assert.match(page.el('used').className, /skeleton/, 'the gray boxes are there');
    assert.equal(page.el('top').className, 'top');

    await hover(page, 'used', 'mouseenter');
    assert.equal(page.el('hover-note').hidden, false);
    assert.equal(page.el('hover-note').textContent, SEAT);
    assert.equal(page.el('hover-note').className, 'note note-info', 'muted, not red');
    assert.equal(page.el('top').className, 'top noted', 'a message line is shown, so the window has its least width');

    await hover(page, 'used', 'mouseleave');
    assert.equal(page.el('hover-note').hidden, true);
    assert.equal(page.el('top').className, 'top');
});

test('each gray box of the amounts shows the line, and going from one to the next keeps it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: NO_USAGE_STATUS }));

    for (const id of ['percent', 'used', 'limit']) {
        await hover(page, id, 'mouseenter');
        assert.equal(page.el('hover-note').textContent, 'Received, but Anthropic reports no spend for this account (spend is not enabled).', id);
        await hover(page, id, 'mouseleave');
        assert.equal(page.el('hover-note').hidden, true, id);
    }
    // The mouse is on the next box before the one it left has said so: the line stays.
    await hover(page, 'used', 'mouseenter');
    await hover(page, 'limit', 'mouseenter');
    await hover(page, 'used', 'mouseleave');
    assert.equal(page.el('hover-note').hidden, false);
});

test('a figure has no such line, and the line goes when the reading under the mouse has spend again', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await hover(page, 'used', 'mouseenter');
    assert.equal(page.el('hover-note').hidden, true, 'the amount used is a figure: there is nothing to say');

    state.status = NO_USAGE_STATUS;
    await page.firePoll();
    assert.equal(page.el('hover-note').hidden, false, 'the mouse is still there, and the box has come under it');

    state.status = SPEND_STATUS;
    await page.firePoll();
    assert.equal(page.el('hover-note').hidden, true);
});

test('a gray box at the start and one with no data have no such line', async () => {
    const waiting = { refreshing: true, stale: false, nextRefreshInSeconds: null, error: null, usage: null };
    const start = await load(backendOf({ config: CONFIG, status: waiting }));
    await hover(start, 'used', 'mouseenter');
    assert.equal(start.el('hover-note').hidden, true);

    const failed = { refreshing: false, stale: false, usage: null, error: { message: 'Claude Code could not be found on the PATH.', at: NOW } };
    const none = await load(backendOf({ config: CONFIG, status: failed }));
    await hover(none, 'used', 'mouseenter');
    assert.equal(none.el('hover-note').hidden, true);
    assert.equal(none.el('note').hidden, false, 'the failure has its own red line, which is always there');
});

test('a failed refresh after a reading with no spend has its red line, and the hover line beside it while the mouse is on a box', async () => {
    const status = { ...NO_USAGE_STATUS, stale: true, error: { message: 'Anthropic returned HTTP 500.', at: NOW } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('note').className, 'note note-stale');
    assert.match(page.el('note').textContent, /^Refresh failed at /);
    assert.equal(page.el('hover-note').hidden, true);
    await hover(page, 'limit', 'mouseenter');
    assert.equal(page.el('hover-note').hidden, false);
});

// ---- the row's box says when a message line is shown, and its own width is measured with the stretch of a panel let go

test('the row says that a message line is shown, for each of the three lines, and no longer when it is gone', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    assert.equal(page.el('top').className, 'top', 'no message, no least width');

    state.status = { ...SPEND_STATUS, stale: true, error: { message: 'Anthropic returned HTTP 500.', at: NOW } };
    await page.firePoll();
    assert.equal(page.el('top').className, 'top noted');

    state.status = SPEND_STATUS;
    await page.firePoll();
    assert.equal(page.el('top').className, 'top');

    state.status = NO_USAGE_STATUS;
    await page.firePoll();
    assert.equal(page.el('top').className, 'top', 'an answer with no spend has no line while the mouse is elsewhere');
    await page.el('used').listeners.mouseenter();
    await settle();
    assert.equal(page.el('top').className, 'top noted', 'and its line counts while the mouse is on a gray box');
    await page.el('used').listeners.mouseleave();
    await settle();

    state.status = SPEND_STATUS;
    state.down = true;
    await page.firePoll();
    assert.equal(page.el('connection').hidden, false);
    assert.equal(page.el('top').className, 'top noted', 'and so does the line of lost contact');

    state.down = false;
    await page.firePoll();
    assert.equal(page.el('top').className, 'top');
});

test('with a panel shown the row\'s width is measured with its stretch let go of, and the stretch is given back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const top = page.el('top');
    const seen = [];
    top.getBoundingClientRect = function () { seen.push(this.style.width + '/' + this.style.maxWidth); return this.rect; };

    page.window.contentSize();
    assert.deepEqual(seen, ['undefined/undefined'], 'no panel: the row is its own width already');

    for (const button of ['settings-button', 'history-button', 'log-button', 'errors-button']) {
        await page.click(button);
        seen.length = 0;
        page.window.contentSize();
        assert.equal(seen[0], 'max-content/900px', button + ': measured as a row of its own width');
        assert.equal(top.style.width, '', button + ': and stretched again afterwards');
        assert.equal(top.style.maxWidth, '');
    }
});

// ---- the zoom of the lists: two buttons at the top of each list, one size for all three, saved at once

const listSizeOf = () => global.document.documentElement.style.properties['--list-size'];
const sizePosts = page => page.calls.filter(c => c.url === '/api/text-size').map(c => JSON.parse(c.body).size);

test('the lists have the text size that is saved, twelve pixels when none is, and the line of the two buttons is at the top of each list', async () => {
    const usual = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(listSizeOf(), '12px');
    assert.equal(usual.el('log-controls').hidden, true, 'no list, no line');

    const page = await load(backendOf({ config: { ...CONFIG, listFontSize: 16 }, status: SPEND_STATUS }));
    assert.equal(listSizeOf(), '16px');
    for (const button of ['history-button', 'errors-button', 'log-button']) {
        await page.click(button);
        assert.equal(page.el('log-controls').hidden, false, button + ': the line with the two buttons');
        assert.equal(page.el('log-choices').hidden, button !== 'log-button', button + ': the level and the date are the log\'s alone');
    }
    await page.click('settings-button');
    assert.equal(page.el('log-controls').hidden, true, 'the settings are no list');
    await page.click('settings-cancel');
    await page.click('log-button');
    assert.equal(page.el('log-controls').hidden, true, 'and with no panel there is no line');
    assert.equal(sizePosts(page).length, 0, 'showing a list saves nothing');

    const odd = await load(backendOf({ config: { ...CONFIG, listFontSize: 99 }, status: SPEND_STATUS }));
    assert.equal(listSizeOf(), '12px', 'a size out of the range is the usual one');
    assert.equal(odd.el('zoom-in').attrs.disabled, undefined);
});

test('a press makes the text of all three lists a pixel larger or smaller at once, and saves it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');

    await page.click('zoom-in');
    assert.equal(listSizeOf(), '13px');
    await page.click('zoom-in');
    assert.equal(listSizeOf(), '14px');
    assert.deepEqual(sizePosts(page), [13, 14], 'each step is saved, with no button to press for it');

    await page.click('log-button');
    assert.equal(listSizeOf(), '14px', 'the log has the size the history was given');
    await page.click('zoom-out');
    await page.click('errors-button');
    assert.equal(listSizeOf(), '13px', 'and the error log the one the log was given');
    assert.deepEqual(sizePosts(page), [13, 14, 13]);
    assert.equal(page.el('log-error').hidden, true);
});

test('the text is never smaller than ten nor larger than twenty pixels, and the button that would go further is disabled', async () => {
    const small = await load(backendOf({ config: { ...CONFIG, listFontSize: 11 }, status: SPEND_STATUS }));
    await small.click('log-button');
    assert.equal(small.el('zoom-out').attrs.disabled, undefined);
    await small.click('zoom-out');
    assert.equal(listSizeOf(), '10px');
    assert.equal(small.el('zoom-out').attrs.disabled, '');
    assert.equal(small.el('zoom-in').attrs.disabled, undefined);
    await small.click('zoom-out');
    assert.equal(listSizeOf(), '10px');
    assert.deepEqual(sizePosts(small), [10], 'a press at the end changes and sends nothing');

    const large = await load(backendOf({ config: { ...CONFIG, listFontSize: 19 }, status: SPEND_STATUS }));
    await large.click('log-button');
    await large.click('zoom-in');
    await large.click('zoom-in');
    assert.equal(listSizeOf(), '20px');
    assert.equal(large.el('zoom-in').attrs.disabled, '');
    assert.equal(large.el('zoom-out').attrs.disabled, undefined);
    assert.deepEqual(sizePosts(large), [20]);
});

test('a size that cannot be saved holds for the run, and a red line says so until one is saved', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postTextSize: () => ({ status: 500, body: { error: 'The text size could not be saved: disk full' } }) };
    const page = await load(backendOf(state));
    await page.click('history-button');

    await page.click('zoom-in');

    assert.equal(listSizeOf(), '13px');
    assert.equal(page.el('log-error').hidden, false);
    assert.equal(page.el('log-error').textContent, 'The text size could not be saved: disk full');

    state.postTextSize = body => ({ status: 200, body });
    await page.click('zoom-in');
    assert.equal(listSizeOf(), '14px');
    assert.equal(page.el('log-error').hidden, true);
});

// ---- the automatic interval: a checkbox beside the interval, whose field then shows the interval the program found

const AUTO_ON = {
    ...SETTINGS,
    settings: { ...SETTINGS.settings, autoInterval: true, usageIntervalSeconds: 35 },
    configuredIntervalSeconds: 120,
};
const withInterval = (status, text) => { const s = withDisplay(status); return { ...s, display: { ...s.display, interval: { text, tooltip: 'Time between usage requests' } } }; };
const autoPosts = page => page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').map(c => JSON.parse(c.body));

test('with Automatic off the field of the interval is as it was, and the checkbox is sent with the rest', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    assert.equal(page.el('set-autoInterval').checked, false);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, undefined, 'it can be typed in');
    assert.equal(page.el('set-usageIntervalSeconds').value, '120');

    page.type('set-usageIntervalSeconds', '45');
    await page.click('settings-apply');
    assert.equal(autoPosts(page)[0].autoInterval, false);
    assert.equal(autoPosts(page)[0].usageIntervalSeconds, 45);
});

test('with Automatic on the field is disabled and shows the interval the program is using, and follows it', async () => {
    const state = { config: CONFIG, status: withInterval(SPEND_STATUS, '35s'), settings: () => ({ status: 200, body: AUTO_ON }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    assert.equal(page.el('set-autoInterval').checked, true);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, '', 'not to be typed in');
    assert.equal(page.el('set-usageIntervalSeconds').value, '35', 'the interval in force, not the configured 120');

    state.status = withInterval(SPEND_STATUS, '23s');
    await page.firePoll();
    assert.equal(page.el('set-usageIntervalSeconds').value, '23', 'it follows the search while the settings are shown');

    await page.click('settings-button');
    assert.equal(autoPosts(page).length, 0, 'nothing was changed, so leaving sends nothing, though the field shows another number');
});

test('unchecking Automatic gives the field the configured interval back, and what is sent is the configured interval either way', async () => {
    const state = { config: CONFIG, status: withInterval(SPEND_STATUS, '35s'), settings: () => ({ status: 200, body: AUTO_ON }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await change(page, 'set-autoInterval', false);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, undefined);
    assert.equal(page.el('set-usageIntervalSeconds').value, '120', 'the configured interval, which was kept');

    page.type('set-usageIntervalSeconds', '90');
    await change(page, 'set-autoInterval', true);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, '');
    assert.equal(page.el('set-usageIntervalSeconds').value, '35', 'the interval in force again');

    await page.click('settings-apply');
    assert.equal(autoPosts(page)[0].autoInterval, true);
    assert.equal(autoPosts(page)[0].usageIntervalSeconds, 90, 'what was typed while it was off is the configured interval now');
});

test('checking Automatic disables the field at once and does not ask for a number in it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withInterval(SPEND_STATUS, '60s') }));
    await page.click('settings-button');
    page.type('set-usageIntervalSeconds', 'abc');

    await change(page, 'set-autoInterval', true);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, '');
    assert.equal(page.el('set-usageIntervalSeconds').value, '60');

    await page.click('settings-apply');
    assert.equal(page.el('settings-error').hidden, true, 'the field is not the user\'s to fill, so it is not checked');
    assert.equal(autoPosts(page)[0].autoInterval, true);
    assert.equal(autoPosts(page)[0].usageIntervalSeconds, 120, 'the configured interval, which the nonsense did not replace');
});

test('Restore defaults unchecks Automatic and gives the field the default interval, to be typed in', async () => {
    const state = { config: CONFIG, status: withInterval(SPEND_STATUS, '35s'), settings: () => ({ status: 200, body: AUTO_ON }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await page.click('settings-restore');

    assert.equal(page.el('set-autoInterval').checked, false);
    assert.equal(page.el('set-usageIntervalSeconds').attrs.disabled, undefined);
    assert.equal(page.el('set-usageIntervalSeconds').value, '60');
    await page.click('settings-apply');
    assert.equal(autoPosts(page)[0].usageIntervalSeconds, 60);
    assert.equal(autoPosts(page)[0].autoInterval, false);
});

// ---- nothing in the window can be selected

test('the key that selects everything is passed over, except in the field to type in', async () => {
    await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const press = (event) => { let prevented = false; global.document.listeners.keydown({ ...event, preventDefault() { prevented = true; } }); return prevented; };
    const row = { tagName: 'SPAN' };
    const field = { tagName: 'INPUT', type: 'text' };

    assert.equal(press({ key: 'a', ctrlKey: true, target: row }), true);
    assert.equal(press({ key: 'A', metaKey: true, target: row }), true, 'the key of a Mac too');
    assert.equal(press({ key: 'a', ctrlKey: true, target: field }), false, 'in the field it selects what is typed there');
    assert.equal(press({ key: 'a', target: row }), false, 'the letter alone is no such key');
    assert.equal(press({ key: 'c', ctrlKey: true, target: row }), false, 'other keys are left alone');
});

test('a selection that the mouse begins is refused, except in the field to type in', async () => {
    await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const begin = target => { let prevented = false; global.document.listeners.selectstart({ target, preventDefault() { prevented = true; } }); return prevented; };

    assert.equal(begin({ tagName: 'SPAN' }), true);
    assert.equal(begin({ tagName: 'DIV' }), true);
    assert.equal(begin({ tagName: 'INPUT', type: 'text' }), false);
    assert.equal(begin(null), true, 'with nothing under it, nothing is selected');
});

// ---- the About view: opened from the settings, shown in the place of their form, and left by Back

test('About shows the view in the place of the settings form, with what the backend says of the program', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('about-view').hidden, true, 'the settings are shown as their form');
    assert.equal(page.calls.filter(c => c.url === '/api/about').length, 0, 'nothing is asked before the view is');

    await page.click('settings-about');

    assert.equal(page.el('about-view').hidden, false);
    assert.equal(page.el('settings-form').hidden, true, 'in the place of the form');
    assert.equal(page.el('settings-buttons').hidden, true, 'and of its buttons');
    assert.equal(page.el('settings-view').hidden, false, 'it is a part of the settings view');
    assert.equal(page.el('about-name').textContent, 'aip usage');
    assert.equal(page.el('about-version').textContent, 'v0.57');
    assert.equal(page.el('about-settings').textContent, 'C:\\work\\aip\\settings.json');
    assert.equal(page.el('about-log').textContent, 'C:\\work\\aip\\java-aip-usage.log');
    assert.equal(page.el('about-history').textContent, 'C:\\work\\aip\\java-aip-usage.csv');
    assert.equal(page.el('about-java').textContent, '25.0.1');
    assert.equal(page.el('about-javafx').textContent, '25.0.4');
    assert.equal(page.el('about-system').textContent, 'Windows 11 10.0');
    assert.equal(page.el('about-debug').textContent, 'off');
    assert.match(page.window.contentSize(), /,3,settings$/, 'for the window it is the settings still: the title bar, and no resizing');
});

test('the line of debug mode says off, on, or on with the fake backend in use', async () => {
    for (const [about, text] of [[{ debug: false, fake: false }, 'off'], [{ debug: true, fake: false }, 'on'], [{ debug: true, fake: true }, 'on, the fake backend is in use']]) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, about: () => ({ status: 200, body: { ...ABOUT, ...about } }) }));
        await page.click('settings-button');
        await page.click('settings-about');
        assert.equal(page.el('about-debug').textContent, text);
    }
});

test('Back shows the settings form again with what was changed and not saved still in it, and nothing was sent', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.type('set-usageIntervalSeconds', '45');
    await change(page, 'set-showDeltaUsed', true);
    assert.equal(page.el('delta-used').hidden, false, 'the row previews the change');

    await page.click('settings-about');
    assert.equal(page.el('delta-used').hidden, false, 'the preview stays while About is shown');
    await page.click('about-back');

    assert.equal(page.el('about-view').hidden, true);
    assert.equal(page.el('settings-form').hidden, false);
    assert.equal(page.el('settings-buttons').hidden, false);
    assert.equal(page.el('set-usageIntervalSeconds').value, '45', 'what was typed is still there');
    assert.equal(page.el('set-showDeltaUsed').checked, true);
    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0, 'About saved nothing');

    await page.click('settings-cancel');
    assert.equal(page.el('delta-used').hidden, true, 'and Cancel still gives the row back');
    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0);
});

test('a button of the row pressed while About is shown saves the settings as from the form, and the next opening shows the form', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.type('set-usageIntervalSeconds', '45');
    await page.click('settings-about');

    await page.click('settings-button');

    const sent = page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').map(c => JSON.parse(c.body));
    assert.equal(sent.length, 1, 'the gear saves, as it does from the form');
    assert.equal(sent[0].usageIntervalSeconds, 45);
    assert.equal(page.el('panel').hidden, true);

    await page.click('settings-button');
    assert.equal(page.el('about-view').hidden, true, 'the settings open as their form, not as About');
    assert.equal(page.el('settings-buttons').hidden, false);
});

test('when the details cannot be read the logo, the name and the version line are there and a red line says why', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, about: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await page.click('settings-about');

    assert.equal(page.el('about-view').hidden, false);
    assert.equal(page.el('about-details').hidden, true);
    assert.equal(page.el('about-error').hidden, false);
    assert.equal(page.el('about-error').textContent, 'The details could not be read: Internal error; see the log.');
    assert.equal(page.el('about-name').textContent, '', 'the name is the page\'s own text in the real page; here nothing replaced it');

    state.about = null;
    await page.click('about-back');
    await page.click('settings-about');
    assert.equal(page.el('about-error').hidden, true, 'asked again at each opening');
    assert.equal(page.el('about-details').hidden, false);
});

test('the About view is as wide as the settings were, and the settings are measured again once it is left', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('settings-form').rect = { left: 8, width: 500, height: 600 };
    page.el('settings-buttons').rect = { left: 8, width: 260, height: 20 };
    assert.match(page.window.contentSize(), /^516,/, 'the form and its sides');

    await page.click('settings-about');
    page.el('settings-form').rect = { left: 0, width: 0, height: 0 };
    page.el('settings-buttons').rect = { left: 0, width: 0, height: 0 };
    assert.match(page.window.contentSize(), /^516,/, 'the hidden form is not measured: the width is the one it had');

    await page.click('about-back');
    page.el('settings-form').rect = { left: 8, width: 520, height: 600 };
    page.el('settings-buttons').rect = { left: 8, width: 260, height: 20 };
    assert.match(page.window.contentSize(), /^536,/);
});

// ---- the no-spend line is shown over every gray box of the row

test('with no spend the line is shown over the gray boxes of the two changes and of the request duration too', async () => {
    const status = withShow(withDisplay(NO_USAGE_STATUS), { percentage: true, deltaUsed: true, deltaTime: true, duration: true });
    const page = await load(backendOf({ config: CONFIG, status }));

    for (const id of ['percent', 'used', 'limit', 'delta-used', 'delta-time', 'duration']) {
        assert.match(page.el(id).className, /skeleton/, id + ' is a gray box');
        await hover(page, id, 'mouseenter');
        assert.equal(page.el('hover-note').hidden, false, id);
        await hover(page, id, 'mouseleave');
        assert.equal(page.el('hover-note').hidden, true, id);
    }
});

test('an element of the row that shows a figure has no such line, though the reading has no spend', async () => {
    const base = withShow(withDisplay(NO_USAGE_STATUS), { duration: true, deltaTime: true });
    const status = { ...base, display: { ...base.display, duration: { text: '87ms', tooltip: 'How long the latest usage request took' } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    await hover(page, 'duration', 'mouseenter');
    assert.equal(page.el('hover-note').hidden, true, 'the duration is a figure');
    await hover(page, 'delta-time', 'mouseenter');
    assert.equal(page.el('hover-note').hidden, false, 'the time since the previous reading is a box');
});

// ---- the paths of the About view: a click opens the folder, the button beside one copies it

const aboutActions = page => page.calls.filter(c => c.url.startsWith('/api/about/')).map(c => c.url.slice('/api/about/'.length) + ' ' + JSON.parse(c.body).file);

async function openAbout(state) {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, ...state }));
    await page.click('settings-button');
    await page.click('settings-about');
    return page;
}

test('a click on a path asks the program to open the folder of that file, by its name', async () => {
    const page = await openAbout({});

    let prevented = false;
    await page.el('about-log').listeners.click({ preventDefault() { prevented = true; } });
    await settle();
    await page.click('about-settings');
    await page.click('about-history');

    assert.equal(prevented, true, 'the link goes nowhere: the page stays where it is');
    assert.deepEqual(aboutActions(page), ['open log', 'open settings', 'open history'], 'the name of the file, never its path');
    assert.equal(page.el('about-error').hidden, true);
    assert.equal(page.el('about-view').hidden, false, 'and the view stays');
});

test('the button beside a path asks for it to be copied, and says for a moment that it was', async () => {
    const page = await openAbout({});
    const button = page.el('about-copy-settings');

    await page.click('about-copy-settings');

    assert.deepEqual(aboutActions(page), ['copy settings']);
    assert.equal(button.className, 'icon tiny copy done', 'the tick');
    assert.equal(button.title, 'Copied');
    const timer = page.timers.filter(t => t.live && t.ms === 1500).pop();
    assert.ok(timer, 'for a second and a half');
    timer.fn();
    assert.equal(button.className, 'icon tiny copy', 'then the icon of a copy again');
    assert.equal(button.title, 'Copy the path');

    await page.click('about-copy-history');
    assert.deepEqual(aboutActions(page), ['copy settings', 'copy history']);
    assert.equal(page.el('about-copy-log').className, '', 'a button that was not pressed says nothing');
});

test('when the folder cannot be opened or the path not copied a red line says so, and the button does not say Copied', async () => {
    const page = await openAbout({
        aboutAction: call => ({ status: 500, body: { error: (call.url.endsWith('/open') ? 'The folder could not be opened: ' : 'The path could not be copied: ') + 'no such program' } }),
    });

    await page.click('about-log');
    assert.equal(page.el('about-error').hidden, false);
    assert.equal(page.el('about-error').textContent, 'The folder could not be opened: no such program');

    await page.click('about-copy-log');
    assert.equal(page.el('about-error').textContent, 'The path could not be copied: no such program');
    assert.notEqual(page.el('about-copy-log').title, 'Copied');
    assert.equal(page.timers.filter(t => t.live && t.ms === 1500).length, 0);
});

// ---- the debug view shapes the fake backend's ordinary answer

const SHAPED = {
    settings: { fakeBackend: true, fakeScenario: 'normal', fakeToken: false,
        fakeAccount: 'usage', fakeIncreaseCents: 137, fakeBudget: '1000.00', fakeStartAmount: '186.02', fakeRefuseSeconds: 0, fakeDelayMillis: 0 },
    scenarios: ['normal', 'http-429', 'slow'],
    accounts: ['usage', 'seat'],
};

async function openDebug(state) {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 200, body: SHAPED }), ...state }));
    await page.click('debug-button');
    return page;
}

test('the debug view shows how the fake backend answers: the account by its name, the amounts as the backend wrote them, the rest as numbers', async () => {
    const page = await openDebug({});

    assert.equal(page.el('debug-fake').hidden, false);
    assert.deepEqual(page.el('debug-fakeAccount').children.map(o => o.value + ':' + o.textContent), ['usage:Usage-based', 'seat:Seat-based']);
    assert.equal(page.el('debug-fakeAccount').value, 'usage');
    assert.equal(page.el('debug-fakeIncreaseCents').value, '137');
    assert.equal(page.el('debug-fakeBudget').value, '1000.00');
    assert.equal(page.el('debug-fakeStartAmount').value, '186.02');
    assert.equal(page.el('debug-fakeRefuseSeconds').value, '0');
    assert.equal(page.el('debug-fakeDelayMillis').value, '0');
});

test('what is typed there is sent with the three switches, whole numbers as numbers and amounts as they were typed, and nothing unchanged is sent', async () => {
    const page = await openDebug({});
    await page.click('debug-button');
    assert.equal(debugPosts(page).length, 0, 'nothing was changed');

    await page.click('debug-button');
    page.el('debug-fakeAccount').value = 'seat';
    page.type('debug-fakeIncreaseCents', '50');
    page.type('debug-fakeBudget', '250');
    page.type('debug-fakeStartAmount', '12.34');
    page.type('debug-fakeRefuseSeconds', '30');
    page.type('debug-fakeDelayMillis', '400');
    await page.click('debug-apply');

    assert.deepEqual(JSON.parse(debugPosts(page)[0].body), {
        fakeBackend: true, fakeScenario: 'normal', fakeToken: false,
        fakeAccount: 'seat', fakeIncreaseCents: 50, fakeBudget: '250', fakeStartAmount: '12.34', fakeRefuseSeconds: 30, fakeDelayMillis: 400,
    });
});

test('what is no number is sent as it was typed, and the reason the backend gives keeps the view open in red', async () => {
    const page = await openDebug({
        postDebug: () => ({ status: 400, body: { error: 'The largest increase must be a whole number of cents from 0 to 100000.' } }),
    });
    page.type('debug-fakeIncreaseCents', 'many');

    await page.click('debug-apply');

    assert.equal(JSON.parse(debugPosts(page)[0].body).fakeIncreaseCents, 'many');
    assert.equal(page.el('debug-error').textContent, 'The largest increase must be a whole number of cents from 0 to 100000.');
    assert.equal(page.el('debug-view').hidden, false);
});

test('a backend that says nothing of how the fake backend answers is sent nothing of it, and the group is not shown', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('debug-button');
    assert.equal(page.el('debug-fake').hidden, true);

    page.el('debug-fakeBackend').checked = true;
    await page.click('debug-apply');

    assert.deepEqual(JSON.parse(debugPosts(page)[0].body), { fakeBackend: true, fakeScenario: 'normal', fakeToken: false });
});
