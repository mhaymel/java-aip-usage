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
    // Each view has a box of its own in the page, and what a list has of its own carries the name of its view: panel-lines-log,
    // panel-note-history. The tests that show one view at a time ask for these by the names they had when there was one list:
    // the name stands for the part of the view that is shown, or of the one that was shown last. A part is hidden when its box is.
    const SHARED = ['log-controls', 'zoom-in', 'zoom-out', 'log-error', 'panel-note', 'panel-error', 'panel-lines'];
    const BOXES = { log: 'view-log', history: 'view-history', errors: 'view-errors', cost: 'cost-view' };
    let lastView = 'history';
    const partOf = id => {
        if (!SHARED.includes(id)) {
            return elements.get(id);
        }
        const shown = Object.keys(BOXES).filter(name => !elements.get(BOXES[name]).hidden && elements.has(id + '-' + name));
        if (shown.length > 0) {
            lastView = shown[0];
        }
        const name = elements.has(id + '-' + lastView) ? lastView : 'history';
        const box = elements.get(BOXES[name]);
        return new Proxy(elements.get(id + '-' + name), {
            get(target, key) {
                if (key === 'hidden') {
                    return target.hidden || box.hidden;
                }
                const value = target[key];
                return typeof value === 'function' ? value.bind(target) : value;
            },
            set(target, key, value) {
                target[key] = value;
                return true;
            },
        });
    };
    const page = {
        el: partOf, calls, timers, opened, window: global.window,
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
        click: async id => { await partOf(id).listeners.click(); await settle(); },
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
        if (call.url === '/api/log/open') {
            return state.logOpen ? state.logOpen(JSON.parse(call.body)) : { status: 200, body: { done: true } };
        }
        if (call.url === '/api/log/reset' || call.url === '/api/log/merge') {
            return state.logFiles ? state.logFiles(call) : { status: 200, body: { oldLog: call.url === '/api/log/reset' } };
        }
        if (call.url.split('?')[0] === '/api/log' && call.method === 'POST') {
            return state.postLog ? state.postLog(JSON.parse(call.body)) : { status: 200, body: JSON.parse(call.body) };
        }
        if (call.url.split('?')[0] === '/api/log') return state.log ? state.log(call) : { status: 200, body: LOG };
        if (call.url === '/api/text-size') return state.postTextSize ? state.postTextSize(JSON.parse(call.body)) : { status: 200, body: JSON.parse(call.body) };
        if (call.url === '/api/about') return state.about ? state.about() : { status: 200, body: ABOUT };
        if (call.url === '/api/folder') return state.folder ? state.folder(JSON.parse(call.body)) : { status: 200, body: { folder: null } };
        if (call.url === '/api/about/open' || call.url === '/api/about/copy') {
            return state.aboutAction ? state.aboutAction(call) : { status: 200, body: { done: true } };
        }
        if (call.url.split('?')[0] === '/api/cost/daily') return state.costDaily ? state.costDaily(call) : { status: 200, body: { daily: null, weekdays: 22 } };
        if (call.url === '/api/cost') return state.cost ? state.cost() : { status: 200, body: COST };
        if (call.url === '/api/errors') return state.errors ? state.errors() : { status: 200, body: ERRORS };
        if (call.url.split('?')[0] === '/api/history' && call.method === 'POST') {
            return state.postHistory ? state.postHistory(JSON.parse(call.body)) : { status: 200, body: JSON.parse(call.body) };
        }
        if (call.url.split('?')[0] === '/api/history') return state.history ? state.history(call) : { status: 200, body: HISTORY };
        return { status: 404, body: { error: 'No such endpoint.' } };
    };
}

const COST = {
    rows: [
        { label: 'Today', text: '12.40 of 25.00 \u00b7 50%', tooltip: 'Today: 12.40 of 25.00 (50%)', fraction: 0.5, over: false, noLimit: false },
        { label: 'Month', text: '300.00 of 250.00 \u00b7 120%', tooltip: 'Month: 300.00 of 250.00 (120%)', fraction: 1, over: true, noLimit: false },
    ],
    note: null,
};

const DEFAULT_SETTINGS = {
    usageIntervalSeconds: 60, logResponse: false, showPercentage: false, showCurrency: false, showHistoryIcon: true, showLogIcon: true, showErrorIcon: true, showCostIcon: true,
    showInterval: false, showDeltaUsed: false, showDeltaTime: false,
    timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false, historyDate: false, historyZeroLines: true, historyFailedLines: true,
    showTime: true, showUsed: true, showLimit: true, showRefresh: true, showCountdown: true, showDuration: false, stayOnTop: false, restoreWindow: false, severalViews: false, logFolder: '', historyFolder: '', costCurrency: false, costLimitSource: 'monthly', costWorkdayHours: 8, costWorkdays: null, showStatus: true, showDayPercentage: false, showDayUsed: false, showDayLimit: false, historyMonthly: false, historyDaily: false, historyDailyBy: false, showTitleBar: true, autoInterval: false,
    costMonthlyLimit: null, costDailyLimit: null, costDailyAutomatic: true,
    historyTime: true, historyUsed: true, historyLimit: true, historyCurrent: true,
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
        { time: '11:34:42', message: 'Anthropic is rate limiting usage requests (HTTP 429).' },
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
    runtime: { name: 'Java', version: '25.0.1' }, toolkit: { name: 'JavaFX', version: '25.0.4' }, system: 'Windows 11 10.0', debug: false, fake: false,
};

const accepting = body => ({ status: 200, body: { ...CONFIG, ...body } });

// ---- the page and the backend

test('every id the page script looks up is declared in index.html', () => {
    const script = fs.readFileSync(path.join(WEB, 'app.js'), 'utf8');
    const used = new Set([...script.matchAll(/\$\('([^']+)'\)/g)].map(m => m[1]));
    for (const m of script.matchAll(/(?:input|id): '([^']+)'/g)) used.add(m[1]);
    for (const m of script.matchAll(/(?:show|setNote)\('([^']+)'/g)) used.add(m[1]);
    // What a list has of its own is looked up with the name of its view after it: 'panel-lines-' + name.
    const VIEW_NAMES = ['log', 'history', 'errors', 'cost'];
    for (const id of used) {
        if (id.endsWith('-')) {
            assert.ok(VIEW_NAMES.some(name => INDEX_IDS.includes(id + name)), 'no view has an id that begins ' + id);
            continue;
        }
        assert.ok(INDEX_IDS.includes(id), 'missing id in index.html: ' + id);
    }
    for (const part of ['log-controls-', 'zoom-in-', 'zoom-out-', 'log-error-', 'panel-note-', 'panel-error-', 'panel-lines-']) {
        for (const name of ['log', 'history', 'errors']) {
            assert.ok(INDEX_IDS.includes(part + name), 'every list has its own ' + part + name);
        }
    }
    assert.ok(INDEX_IDS.includes('panel-error-cost'), 'and the cost control its own red line');
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
    assert.equal(page.el('countdown').title, 'Seconds until the next refresh');
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

    await page.click('settings-save');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).usageIntervalSeconds, 47);
});

test('a box that is not a whole number of seconds is refused in the view, in red, and sends nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    for (const bad of ['', 'abc', '4.5', '-60', '1e3', '60 s']) {
        page.el('set-usageIntervalSeconds').value = bad;
        await page.click('settings-save');
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

    await page.click('settings-save');

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

    await page.click('settings-save');

    assert.equal(settingsPosts(page).length, 1);
    assert.deepEqual(JSON.parse(settingsPosts(page)[0].body), {
        usageIntervalSeconds: 180, logResponse: true, showPercentage: false, showCurrency: false, showHistoryIcon: true, showLogIcon: true, showErrorIcon: true, showCostIcon: true, showInterval: true, showDeltaUsed: false, showDeltaTime: false,
        timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false, historyDate: false, historyZeroLines: true, historyFailedLines: true,
        showTime: true, showUsed: true, showLimit: true, showRefresh: true, showCountdown: true, showDuration: false, stayOnTop: false, restoreWindow: false, severalViews: false, logFolder: '', historyFolder: '', costCurrency: false, costLimitSource: 'monthly', costWorkdayHours: 8, costWorkdays: null, showStatus: true, showDayPercentage: false, showDayUsed: false, showDayLimit: false, historyMonthly: false, historyDaily: false, historyDailyBy: false, showTitleBar: true, autoInterval: false,
        costMonthlyLimit: null, costDailyLimit: null, costDailyAutomatic: true,
        historyTime: true, historyUsed: true, historyLimit: true, historyCurrent: true,
    });
    assert.equal(settingsPosts(page)[0].headers['Content-Type'], 'application/json');
    assert.equal(page.el('settings-view').hidden, true, 'Save closes the view');
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

    await page.click('settings-save');

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

test('if the settings cannot be read the view says so and has no form to edit', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) }));

    await page.click('settings-button');

    assert.equal(page.el('settings-form').hidden, true);
    assert.match(page.el('settings-error').textContent, /^The settings could not be read: /);
    assert.equal(page.el('settings-save').attrs.disabled, '');
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

test('a view opened while another is still being read is read at once, by itself, and the late answer of the other is dropped', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, historyStamp: 'a' } };
    const page = await load(backendOf(state));
    let answerLog;
    state.log = () => new Promise(resolve => { answerLog = () => resolve({ status: 200, body: LOG }); });
    await page.click('log-button');

    // The log has not answered yet, and the person goes on to the history.
    await page.click('history-button');
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1, 'each view is read by itself: it does not wait for the log');
    assert.equal(page.el('panel-lines').children.length, 3, 'and is drawn');

    answerLog();
    await settle();

    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1, 'and is not read a second time for it');
    assert.equal(page.el('panel-lines-log').children.length, 0, 'the log that answered late is not drawn: it is not shown');
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
        ['11:34:42', 'Anthropic is rate limiting usage requests (HTTP 429).'],
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
        countdownAlert: 'Anthropic is rate limiting usage requests (HTTP 429).',
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

test('with the automatic interval on the countdown is blue; the red of a 429 wins; off it is plain', async () => {
    const auto = { ...LIMITED, display: { ...LIMITED.display, countdownAlert: null, countdownAuto: true } };
    const state = { config: CONFIG, status: auto };
    const page = await load(backendOf(state));
    assert.match(page.el('countdown').className, /\bauto\b/);
    assert.doesNotMatch(page.el('countdown').className, /\balert\b/);

    state.status = { ...LIMITED, display: { ...LIMITED.display, countdownAuto: true } };
    await page.firePoll();
    assert.match(page.el('countdown').className, /\balert\b/, 'a 429: red');
    assert.doesNotMatch(page.el('countdown').className, /\bauto\b/, 'and not blue');

    state.status = { ...LIMITED, display: { ...LIMITED.display, countdownAlert: null, countdownAuto: false } };
    await page.firePoll();
    assert.doesNotMatch(page.el('countdown').className, /\bauto\b|\balert\b/, 'off: the colour of the text');
});

test('the tooltip of the countdown may have two lines, and the page shows them as they are', async () => {
    const status = { ...LIMITED, display: { ...LIMITED.display, countdownAlert: null,
        countdown: { text: '30s', tooltip: 'Seconds until the next refresh\nAutomatic interval is on: the interval is now 35 s' } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('countdown').title, 'Seconds until the next refresh\nAutomatic interval is on: the interval is now 35 s');
});

test('hovering the red countdown shows the message in a line under the row, and leaving takes it away', async () => {
    const page = await load(backendOf({ config: CONFIG, status: LIMITED }));

    page.el('countdown').listeners.mouseenter();
    assert.equal(page.el('alert-note').hidden, false);
    assert.equal(page.el('alert-note').textContent, 'Anthropic is rate limiting usage requests (HTTP 429).');
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
    await page.click('settings-save');

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
    await page.click('settings-save');
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

    await page.click('settings-save');

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

    await page.click('settings-save');

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
    await page.click('settings-save');

    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.historyZeroLines, false);
    assert.equal(sent.historyFailedLines, false);
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

test('a view whose icon is unchecked in the settings is not given back by Save, by the gear or by another icon; Cancel gives it back', async () => {
    for (const leave of ['settings-save', 'settings-button', 'log-button']) {
        const state = { config: CONFIG, status: SPEND_STATUS };
        const page = await load(backendOf(state));
        await page.click('history-button');
        await page.click('settings-button');
        await change(page, 'set-showHistoryIcon', false);

        // The backend says what was saved from the next status on.
        state.status = withShow(WITH_CHANGE, { ...ALL_ICONS, historyIcon: false });
        await page.click(leave);

        assert.equal(page.el('settings-view').hidden, true, leave);
        assert.equal(JSON.parse(settingsPosts(page)[0].body).showHistoryIcon, false, leave + ' saved it');
        assert.equal(page.el('history-button').hidden, true, leave + ': the icon is gone');
        assert.notEqual(page.el('history-button').title, 'Hide the usage history', leave + ': and the history is not shown again');
        assert.equal(page.el('panel').hidden, leave !== 'log-button', leave + ': the row alone, or the view that was asked for');
    }

    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    await page.click('settings-button');
    await change(page, 'set-showHistoryIcon', false);
    await page.click('settings-cancel');
    assert.equal(page.el('history-button').title, 'Hide the usage history', 'Cancel changes nothing, so the history comes back');
    assert.equal(settingsPosts(page).length, 0);
});

test('Apply that switches off a different panel\'s button still gives the earlier panel back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    await page.click('settings-button');
    page.el('set-showLogIcon').checked = false;

    await page.click('settings-save');

    assert.equal(page.el('history-button').title, 'Hide the usage history');
});

test('the four new switches are in the form, and posted', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-showCurrency').checked, false, 'the symbol is off by default');
    for (const id of ['set-showHistoryIcon', 'set-showLogIcon', 'set-showErrorIcon']) {
        assert.equal(page.el(id).checked, true, id);
    }

    page.el('set-showCurrency').checked = true;
    page.el('set-showErrorIcon').checked = false;
    await page.click('settings-save');
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.showCurrency, true);
    assert.equal(sent.showErrorIcon, false);
    assert.equal(sent.showHistoryIcon, true);

    await page.click('settings-button');});

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

test('the history\'s notes about there being nothing are not blue; the log\'s note that not everything is shown is', async () => {
    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, exists: false, total: 0, lines: [],
        note: 'There is no usage history yet.', noteHighlight: false } }) }));
    await none.click('history-button');
    assert.doesNotMatch(none.el('panel-note').className, /highlight/);

    const cut = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, truncated: true } }) }));
    await cut.click('log-button');
    assert.equal(cut.el('panel-note').hidden, false);
    assert.match(cut.el('panel-note').className, /\bhighlight\b/, 'it says that lines are left out, like the history\'s line');

    const whole = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LOG, exists: false, lines: [] } }) }));
    await whole.click('log-button');
    assert.equal(whole.el('panel-note').textContent, 'There is no log file yet.');
    assert.doesNotMatch(whole.el('panel-note').className, /highlight/, 'a note that there is nothing stays gray');
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

test('the history\'s start line is green text with the start class (the light blue is its rule); the failed word is still red', async () => {
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

test('the log\'s start line has the mark class, the very light blue, and is not green', async () => {
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

const OFF_LINE = 'Switched off: this button goes when the settings are closed.';
const dimmed = page => ['log-button', 'history-button', 'errors-button', 'cost-button'].filter(id => /\boff\b/.test(page.el(id).className));

test('with the panel buttons switched off only the gear is there, and opening the settings shows them all, dimmed, until the settings are left', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...NO_ICONS, costIcon: false }) }));
    assert.deepEqual(visible(page), ['settings-button']);
    assert.equal(page.el('cost-button').hidden, true);

    await page.click('settings-button');

    assert.deepEqual(visible(page), BUTTONS, 'every panel button is in the row while the settings are shown');
    assert.equal(page.el('cost-button').hidden, false, 'the cost button too');
    assert.deepEqual(dimmed(page), ['log-button', 'history-button', 'errors-button', 'cost-button'], 'each is dimmed, since it is switched off');
    assert.equal(page.el('log-button').title, 'Show the log\n' + OFF_LINE, 'the tooltip says that it will go, on a second line');
    assert.equal(page.el('log-button').attrs['aria-label'], 'Show the log', 'its name is the first line alone');
    assert.doesNotMatch(page.el('settings-button').className, /\boff\b/, 'the gear is never dimmed');

    await page.click('settings-cancel');

    assert.deepEqual(visible(page), ['settings-button'], 'left, the buttons that are switched off go');
    assert.deepEqual(dimmed(page), [], 'and nothing is dimmed');
    assert.equal(page.el('log-button').title, 'Show the log');
});

test('a panel button that is switched on is not dimmed while the settings are shown, and has its usual tooltip', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...NO_ICONS, logIcon: true }) }));

    await page.click('settings-button');

    assert.deepEqual(dimmed(page), ['history-button', 'errors-button'], 'the two that are off; the log and the cost button are on');
    assert.equal(page.el('log-button').title, 'Show the log');
    assert.equal(page.el('cost-button').title, 'Show the cost control');
});

test('a click on the checkbox of a panel button dims the button, or brings it back to full strength, at once and without moving the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    const before = page.window.contentSize();
    assert.deepEqual(dimmed(page), []);

    await change(page, 'set-showLogIcon', false);
    assert.equal(page.el('log-button').hidden, false, 'still in the row');
    assert.deepEqual(dimmed(page), ['log-button']);
    assert.equal(page.el('log-button').title, 'Show the log\n' + OFF_LINE);

    await change(page, 'set-showLogIcon', true);
    assert.deepEqual(dimmed(page), [], 'on again: full strength');
    assert.equal(page.el('log-button').title, 'Show the log');

    await page.click('set-all');
    await page.click('set-all');
    assert.deepEqual(dimmed(page), ['log-button', 'history-button', 'errors-button', 'cost-button'], 'the button of Main view dims all four');
    assert.equal(page.el('refresh').hidden, true, 'the refresh button still goes with its checkbox: only the panel buttons stay');
    assert.equal(page.el('time').hidden, true, 'and so do the figures');
    assert.equal(settingsPosts(page).length, 0, 'a preview sends nothing');
    assert.equal(before.split(',')[2] + before.split(',')[3], '3settings');
});

test('a click on a dimmed button saves the settings and opens its panel, whose button then stays until the panel is closed', async () => {
    const state = { config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    for (const key of ['showLogIcon', 'showHistoryIcon', 'showErrorIcon']) {
        await change(page, 'set-' + key, false);
    }
    await change(page, 'set-showDeltaUsed', true);
    assert.match(page.el('log-button').className, /\boff\b/);

    await page.click('log-button');

    assert.equal(settingsPosts(page).length, 1, 'the settings are saved, as by any button of the row');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).showLogIcon, false, 'the button stays switched off');
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('log-button').title, 'Hide the log', 'the log is shown');
    assert.match(page.el('log-button').className, /\bactive\b/);
    assert.doesNotMatch(page.el('log-button').className, /\boff\b/, 'the button of the open panel is not dimmed');
    assert.deepEqual(visible(page), ['log-button', 'settings-button'], 'the other two went with the settings');

    await page.click('log-button');
    assert.deepEqual(visible(page), ['settings-button'], 'closed, its button goes');
});

test('a view reached through a dimmed icon is not given back when the settings are left again, since its icon is still switched off; Cancel gives it back', async () => {
    const off = { ...SETTINGS, settings: { ...SETTINGS.settings, showLogIcon: false, showHistoryIcon: false, showErrorIcon: false } };
    for (const [leave, back] of [['settings-save', false], ['settings-button', false], ['settings-cancel', true]]) {
        const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS), settings: () => ({ status: 200, body: off }) }));
        await page.click('settings-button');
        await page.click('log-button');
        assert.equal(page.el('log-button').title, 'Hide the log', 'the dimmed icon opens its view');
        assert.equal(page.el('log-button').hidden, false, 'whose icon stays while it is open');

        await page.click('settings-button');
        assert.match(page.el('log-button').className, /\boff\b/, 'in the settings it is one of the dimmed icons again');
        await page.click(leave);

        assert.equal(page.el('settings-view').hidden, true, leave);
        assert.equal(page.el('log-button').title === 'Hide the log', back, leave + (back ? ': the log is back' : ': the log is not shown again'));
        assert.equal(page.el('log-button').hidden, !back);
    }
});

test('a view whose icon is on is given back as ever, however the settings are left', async () => {
    for (const leave of ['settings-save', 'settings-cancel', 'settings-button']) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
        await page.click('history-button');
        await page.click('settings-button');
        await change(page, 'set-showLogIcon', false);
        await page.click(leave);
        assert.equal(page.el('history-button').title, 'Hide the usage history', leave + ': another icon was unchecked, not this one');
    }
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
    showLogIcon: 'log-button', showHistoryIcon: 'history-button', showErrorIcon: 'errors-button', showCostIcon: 'cost-button',
};

test('a click on a checkbox of the main view shows in the row at once, and nothing is sent', async () => {
    const everything = { percentage: true, interval: true, deltaUsed: true, deltaTime: true, historyIcon: true, logIcon: true, errorIcon: true, costIcon: true };
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, everything) }));
    await page.click('settings-button');
    for (const key of Object.keys(ROW_ELEMENTS)) {
        await change(page, 'set-' + key, true);
    }
    const polls = page.calls.filter(c => c.url === '/api/status').length;

    for (const [key, id] of Object.entries(ROW_ELEMENTS)) {
        const panelButton = /-button$/.test(id);
        assert.equal(page.el(id).hidden, false, id + ' is there to begin with');
        await change(page, 'set-' + key, false);
        // A panel button stays while the settings are shown, dimmed; every other element goes.
        assert.equal(page.el(id).hidden, !panelButton, key + ' off takes ' + id + ' away at once, unless it is a panel button');
        assert.equal(/\boff\b/.test(page.el(id).className), panelButton, key + ' off dims ' + id + ' if it is a panel button');
        await change(page, 'set-' + key, true);
        assert.equal(page.el(id).hidden, false, key + ' on brings ' + id + ' back');
        assert.doesNotMatch(page.el(id).className, /\boff\b/);
    }
    assert.equal(settingsPosts(page).length, 0, 'a preview sends nothing');
    assert.equal(page.calls.filter(c => c.url === '/api/status').length, polls, 'and needs no poll');
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
    await page.click('settings-save');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).stayOnTop, true);
});

test('Title bar is a setting of the window: on by default, sent with the others, and not previewed in the row', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-showTitleBar').checked, true, 'on by default');
    assert.equal(page.el('set-showTitleBar').listeners.change, undefined);

    page.el('set-showTitleBar').checked = false;
    await page.click('settings-save');

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
    await page.click('settings-save');
    assert.equal(page.el('time').hidden, true, 'applied, and the backend says so as well');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).showTime, false);
});

test('every element of the row can be switched off by the backend, down to the gear', async () => {
    const nothing = {
        time: false, percentage: false, used: false, limit: false, refresh: false, countdown: false, interval: false,
        deltaUsed: false, deltaTime: false, duration: false, logIcon: false, historyIcon: false, errorIcon: false, costIcon: false,
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
        'countdown': 'Seconds until the next refresh',
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

// ---- debug mode: a tab of the settings, and a red border while the fake backend is in use

const debugPosts = page => page.calls.filter(c => c.method === 'POST' && c.url === '/api/debug');

test('the Debug tab is there in debug mode and never otherwise, and there is no debug button', async () => {
    assert.ok(!INDEX_IDS.includes('debug-button') && !INDEX_IDS.includes('debug-view'), 'neither the button nor a view of its own');
    const plain = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(plain.el('tabbtn-debug').hidden, true);
    await plain.click('settings-button');
    assert.equal(plain.calls.filter(c => c.url === '/api/debug').length, 0, 'and nothing is asked about debug settings');
    assert.equal(plain.el('tabbtn-debug').hidden, true, 'no title in the strip for it');
    await plain.click('tabbtn-about');
    assert.equal(plain.el('about-view').hidden, false, 'the strip goes from the first tab to About with no Debug tab between');

    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    assert.equal(page.el('tabbtn-debug').hidden, false);
});
test('the Debug tab shows the debug settings, as the backend has them, with the scenarios it names', async () => {
    const state = { config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 200, body: { ...DEBUG, settings: { fakeBackend: true, fakeScenario: 'http-429', fakeToken: false } } }) };
    const page = await load(backendOf(state));

    await page.click('settings-button');
    await page.click('tabbtn-debug');

    assert.equal(page.el('tab-debug').hidden, false);
    assert.equal(page.el('tab-main').hidden, true);
    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('debug-fakeBackend').checked, true);
    assert.equal(page.el('debug-fakeScenario').value, 'http-429');
    assert.deepEqual(page.el('debug-fakeScenario').children.map(o => o.value), DEBUG.scenarios);
    assert.equal(page.el('debug-fakeToken').checked, false);
    assert.ok(page.window.contentSize().endsWith(',3,settings'), page.window.contentSize());
});
test('Save sends the debug settings of the Debug tab with the others and closes the view; Cancel sends nothing', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-debug');
    page.el('debug-fakeBackend').checked = true;
    page.el('debug-fakeScenario').value = 'slow';

    await page.click('settings-save');

    assert.deepEqual(JSON.parse(debugPosts(page)[0].body), { fakeBackend: true, fakeScenario: 'slow', fakeToken: false });
    assert.equal(settingsPosts(page).length, 1, 'the settings go with them');
    assert.equal(page.el('settings-view').hidden, true);
    assert.equal(page.el('panel').hidden, true);

    await page.click('settings-button');
    await page.click('tabbtn-debug');
    page.el('debug-fakeToken').checked = true;
    await page.click('settings-cancel');
    assert.equal(debugPosts(page).length, 1, 'Cancel sent nothing');
    assert.equal(page.el('settings-view').hidden, true);
});
test('leaving the settings by a button of the row saves the debug settings too, and sends nothing when nothing was changed', async () => {
    for (const button of ['settings-button', 'log-button']) {
        const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
        await page.click('settings-button');
        await page.click(button);
        assert.equal(debugPosts(page).length, 0, button + ': nothing changed, nothing sent');
        assert.equal(settingsPosts(page).length, 0, button);

        const changed = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
        await changed.click('settings-button');
        changed.el('debug-fakeBackend').checked = true;
        await changed.click(button);
        assert.equal(debugPosts(changed).length, 1, button + ': saved on leaving');
        assert.equal(JSON.parse(debugPosts(changed)[0].body).fakeBackend, true);
        assert.equal(settingsPosts(changed).length, 0, button + ': the settings were not changed, so not sent');
        assert.equal(changed.el('settings-view').hidden, true, button);
    }
});
test('a Debug tab left by Cancel takes the changes back and sends nothing', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-debug');
    page.el('debug-fakeBackend').checked = true;
    page.el('set-logResponse').checked = true;

    await page.click('settings-cancel');
    await page.click('settings-button');

    assert.equal(page.el('debug-fakeBackend').checked, false);
    assert.equal(page.el('set-logResponse').checked, false);
    assert.equal(debugPosts(page).length + settingsPosts(page).length, 0);
});
test('debug settings that cannot be saved keep the view open on the Debug tab with the reason, and the other panel shut', async () => {
    const refusing = { config: DEBUG_CONFIG, status: SPEND_STATUS, postDebug: () => ({ status: 500, body: { error: 'The debug settings could not be applied: no port.' } }) };
    const page = await load(backendOf(refusing));
    await page.click('settings-button');
    page.el('debug-fakeBackend').checked = true;

    await page.click('history-button');

    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('tab-debug').hidden, false, 'the tab where the reason is said is shown');
    assert.equal(page.el('debug-error').textContent, 'The debug settings could not be applied: no port.');
    assert.equal(page.el('history-button').title, 'Show the usage history');

    page.el('debug-fakeBackend').checked = true;
    await page.click('tabbtn-main');
    await page.click('settings-save');
    assert.equal(page.el('tab-debug').hidden, false, 'Save shows the Debug tab as well');
    assert.equal(page.el('settings-view').hidden, false);
});
test('debug settings that cannot be read say so and have no form', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) }));

    await page.click('settings-button');

    assert.equal(page.el('debug-form').hidden, true);
    assert.match(page.el('debug-error').textContent, /^The debug settings could not be read: /);
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
    'showDeltaTime', 'showDuration', 'showLogIcon', 'showHistoryIcon', 'showErrorIcon', 'showCostIcon'];
const checkedOf = page => ELEMENTS.filter(key => page.el('set-' + key).checked);

test('the button switches every element on when not all are on, and all off when all are, and leaves the two properties alone', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.ok(checkedOf(page).length > 0 && checkedOf(page).length < 14, 'some are on at the start');
    const currency = page.el('set-showCurrency').checked;
    const format = page.el('set-timeFormat').value;

    await page.click('set-all');
    assert.deepEqual(checkedOf(page), ELEMENTS, 'all fourteen on');

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
    for (const id of ['time', 'spend', 'refresh', 'countdown', 'interval', 'delta-used', 'delta-time', 'duration']) {
        assert.equal(page.el(id).hidden, true, id + ' is gone from the row');
    }
    for (const id of ['log-button', 'history-button', 'errors-button', 'cost-button']) {
        assert.equal(page.el(id).hidden, false, id + ' stays while the settings are shown');
        assert.match(page.el(id).className, /\boff\b/, id + ' is dimmed');
    }
    assert.equal(page.el('settings-button').hidden, false, 'the gear is always there');

    await page.click('settings-cancel');
    assert.equal(page.el('time').hidden, false, 'Cancel gives the row back');
    assert.equal(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').length, 0);

    await page.click('settings-button');
    await page.click('set-all');
    await page.click('settings-save');
    const sent = JSON.parse(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST')[0].body);
    assert.deepEqual(ELEMENTS.filter(key => sent[key] === true), ELEMENTS, 'saved as any other click is');
});

const HISTORY_BOXES = ['historyDate', 'historyZeroLines', 'historyFailedLines', 'historyTime', 'historyUsed', 'historyLimit', 'historyDeltaUsed', 'historyDeltaTime', 'historyCurrent'];
const historyChecked = page => HISTORY_BOXES.filter(key => page.el('set-' + key).checked);

test('the button of History view switches all nine checkboxes on when not all are on, and all off when all are', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    const before = historyChecked(page);
    const elements = checkedOf(page);
    assert.ok(before.length > 0 && before.length < 9, 'some are on at the start');

    await page.click('set-all-history');
    assert.deepEqual(historyChecked(page), HISTORY_BOXES, 'all nine on: the date, the two kinds of lines and the six columns');

    await page.click('set-all-history');
    assert.deepEqual(historyChecked(page), [], 'all on, so all off');

    await page.click('set-all-history');
    page.el('set-historyUsed').checked = false;
    await page.click('set-all-history');
    assert.deepEqual(historyChecked(page), HISTORY_BOXES, 'one off: all on');

    assert.deepEqual(checkedOf(page), elements, 'the elements of Main view are not touched');
    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0, 'nothing is sent by the button');

    await page.click('settings-cancel');
    await page.click('settings-button');
    assert.deepEqual(historyChecked(page), before, 'Cancel leaves the settings as they were');
});

test('what the button of History view did is saved with the view, and it does nothing before the settings have been read', async () => {
    const idle = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await idle.click('set-all-history');
    assert.deepEqual(historyChecked(idle), [], 'nothing before the settings are open');

    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('set-all-history');
    await page.click('settings-save');
    const sent = JSON.parse(page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST')[0].body);
    assert.deepEqual(HISTORY_BOXES.filter(key => sent[key] === true), HISTORY_BOXES);
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
    await page.click('settings-save');
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

    await page.click('settings-save');
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

    await page.click('settings-save');
    assert.equal(page.el('settings-error').hidden, true, 'the field is not the user\'s to fill, so it is not checked');
    assert.equal(autoPosts(page)[0].autoInterval, true);
    assert.equal(autoPosts(page)[0].usageIntervalSeconds, 120, 'the configured interval, which the nonsense did not replace');
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

// A path of About as it reads: the folders and the name of the file are two parts of its link.
const pathOf = (page, id) => page.el(id).children.map(part => part.textContent).join('');

test('the name of the file is a part of its own in each path of About, so that it alone can be bold', async () => {
    const files = { settings: 'C:\\work\\aip\\settings.json', log: '/Users/me/aip/java-aip-usage.log', history: 'java-aip-usage.csv' };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, about: () => ({ status: 200, body: { ...ABOUT, files } }) }));
    await page.click('settings-button');
    await page.click('tabbtn-about');

    const parts = id => page.el(id).children.map(part => part.className + '|' + part.textContent);
    assert.deepEqual(parts('about-settings'), ['about-folders|C:\\work\\aip\\', 'about-file|settings.json'], 'cut at the last backslash');
    assert.deepEqual(parts('about-log'), ['about-folders|/Users/me/aip/', 'about-file|java-aip-usage.log'], 'or at the last slash');
    assert.deepEqual(parts('about-history'), ['about-folders|', 'about-file|java-aip-usage.csv'], 'a bare name is all name');
    assert.equal(pathOf(page, 'about-settings'), files.settings, 'the two parts are the path, with nothing added or lost');
});

test('About is the last tab and shows what the backend says of the program', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('about-view').hidden, true, 'the settings open on the first tab');
    assert.equal(page.calls.filter(c => c.url === '/api/about').length, 0, 'nothing is asked before the tab is');

    await page.click('tabbtn-about');

    assert.equal(page.el('about-view').hidden, false);
    assert.equal(page.el('tab-main').hidden, true, 'in the place of the other tabs');
    assert.equal(page.el('settings-buttons').hidden, false, 'with Save and Cancel under it');
    assert.equal(page.el('settings-view').hidden, false, 'it is a part of the settings view');
    assert.equal(page.el('about-name').textContent, 'aip usage');
    assert.equal(page.el('about-version').textContent, 'v0.57');
    assert.equal(pathOf(page, 'about-settings'), 'C:\\work\\aip\\settings.json');
    assert.equal(pathOf(page, 'about-log'), 'C:\\work\\aip\\java-aip-usage.log');
    assert.equal(pathOf(page, 'about-history'), 'C:\\work\\aip\\java-aip-usage.csv');
    assert.equal(page.el('about-runtime-label').textContent, 'Java', 'the name of the runtime is what the backend says');
    assert.equal(page.el('about-runtime').textContent, '25.0.1');
    assert.equal(page.el('about-toolkit-label').textContent, 'JavaFX', 'and so is the name of the toolkit of the window');
    assert.equal(page.el('about-toolkit').textContent, '25.0.4');
    assert.equal(page.el('about-system').textContent, 'Windows 11 10.0');
    assert.equal(page.el('about-debug').textContent, 'off');
    assert.match(page.window.contentSize(), /,4,settings$/, 'for the window it is the settings still, with the title bar; its width alone may be dragged');
});
test('the line of debug mode says off, on, or on with the fake backend in use', async () => {
    for (const [about, text] of [[{ debug: false, fake: false }, 'off'], [{ debug: true, fake: false }, 'on'], [{ debug: true, fake: true }, 'on, the fake backend is in use']]) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, about: () => ({ status: 200, body: { ...ABOUT, ...about } }) }));
        await page.click('settings-button');
        await page.click('tabbtn-about');
        assert.equal(page.el('about-debug').textContent, text);
    }
});

test('a button of the row pressed while About is shown saves the settings as they stand, and the next opening shows About again, the tab it was left on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.type('set-usageIntervalSeconds', '45');
    await page.click('tabbtn-about');

    await page.click('settings-button');

    const sent = page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').map(c => JSON.parse(c.body));
    assert.equal(sent.length, 1, 'the gear saves, as it does from any tab');
    assert.equal(sent[0].usageIntervalSeconds, 45);
    assert.equal(page.el('panel').hidden, true);

    await page.click('settings-button');
    assert.equal(page.el('about-view').hidden, false, 'the settings open on the tab they were left on, About too');
    assert.equal(page.el('tab-main').hidden, true);
    assert.equal(page.el('settings-buttons').hidden, false);
});
test('when the details cannot be read the logo, the name and the version line are there and a red line says why', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, about: () => ({ status: 500, body: { error: 'Internal error; see the log.' } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await page.click('tabbtn-about');

    assert.equal(page.el('about-view').hidden, false);
    assert.equal(page.el('about-details').hidden, true);
    assert.equal(page.el('about-error').hidden, false);
    assert.equal(page.el('about-error').textContent, 'The details could not be read: Internal error; see the log.');
    assert.equal(page.el('about-name').textContent, '', 'the name is the page\'s own text in the real page; here nothing replaced it');

    state.about = null;
    await page.click('tabbtn-main');
    await page.click('tabbtn-about');
    assert.equal(page.el('about-error').hidden, true, 'asked again at each showing');
    assert.equal(page.el('about-details').hidden, false);
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
    await page.click('tabbtn-about');
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

// ---- the Debug tab shapes the fake backend's ordinary answer

const SHAPED = {
    settings: { fakeBackend: true, fakeScenario: 'normal', fakeToken: false,
        fakeAccount: 'usage', fakeIncreaseCents: 137, fakeBudget: '1000.00', fakeStartAmount: '186.02', fakeRefuseSeconds: 0, fakeDelayMillis: 0 },
    scenarios: ['normal', 'http-429', 'slow'],
    accounts: ['usage', 'seat'],
};

async function openDebug(state) {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS, debug: () => ({ status: 200, body: SHAPED }), ...state }));
    await page.click('settings-button');
    await page.click('tabbtn-debug');
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
    await page.click('settings-button');
    assert.equal(debugPosts(page).length, 0, 'nothing was changed');

    await page.click('settings-button');
    await page.click('tabbtn-debug');
    page.el('debug-fakeAccount').value = 'seat';
    page.type('debug-fakeIncreaseCents', '50');
    page.type('debug-fakeBudget', '250');
    page.type('debug-fakeStartAmount', '12.34');
    page.type('debug-fakeRefuseSeconds', '30');
    page.type('debug-fakeDelayMillis', '400');
    await page.click('settings-save');

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

    await page.click('settings-save');

    assert.equal(JSON.parse(debugPosts(page)[0].body).fakeIncreaseCents, 'many');
    assert.equal(page.el('debug-error').textContent, 'The largest increase must be a whole number of cents from 0 to 100000.');
    assert.equal(page.el('tab-debug').hidden, false);
});

test('a backend that says nothing of how the fake backend answers is sent nothing of it, and the group is not shown', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-debug');
    assert.equal(page.el('debug-fake').hidden, true);

    page.el('debug-fakeBackend').checked = true;
    await page.click('settings-save');

    assert.deepEqual(JSON.parse(debugPosts(page)[0].body), { fakeBackend: true, fakeScenario: 'normal', fakeToken: false });
});

// ---- cost control: a monthly and a daily limit, as settings

const WITH_LIMITS = {
    ...SETTINGS,
    settings: { ...SETTINGS.settings, costMonthlyLimit: '250.00', costDailyLimit: null, costDailyAutomatic: true },
    costDailyWorkedOut: '11.36',
};
const costPosts = page => page.calls.filter(c => c.url === '/api/settings' && c.method === 'POST').map(c => JSON.parse(c.body));
const dailyAsked = page => page.calls.filter(c => c.url.startsWith('/api/cost/daily')).map(c => c.url);

test('with no limits the fields are empty, the daily one is worked out, and nothing of it is sent when nothing was changed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    assert.equal(page.el('set-costMonthlyLimit').value, '');
    assert.equal(page.el('set-costDailyAutomatic').checked, true);
    assert.equal(page.el('set-costDailyLimit').attrs.disabled, '', 'not to be typed in while it is worked out');
    assert.equal(page.el('set-costDailyLimit').value, '', 'and nothing to work it out from');

    await page.click('settings-button');
    assert.equal(costPosts(page).length, 0);
});

test('with a monthly limit the daily field shows the limit the backend worked out', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: WITH_LIMITS }) }));
    await page.click('settings-button');

    assert.equal(page.el('set-costMonthlyLimit').value, '250.00');
    assert.equal(page.el('set-costDailyLimit').value, '11.36');
    assert.equal(page.el('set-costDailyLimit').attrs.disabled, '');
    assert.equal(dailyAsked(page).length, 0, 'the settings said it: nothing is asked');
});

test('a monthly limit that is typed is sent as typed, and the backend is asked what daily limit follows from it', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, costDaily: call => ({ status: 200, body: { daily: call.url.includes('monthly=440') ? '20.00' : null, weekdays: 22 } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await change(page, 'set-costMonthlyLimit', '440');

    assert.deepEqual(dailyAsked(page), ['/api/cost/daily?monthly=440&workdays='], 'the page works nothing out itself');
    assert.equal(page.el('set-costDailyLimit').value, '20.00');

    await page.click('settings-save');
    assert.equal(costPosts(page)[0].costMonthlyLimit, '440');
    assert.equal(costPosts(page)[0].costDailyLimit, null, 'worked out, so none is typed');
    assert.equal(costPosts(page)[0].costDailyAutomatic, true);
});

test('with Automatic unchecked the daily limit is typed in and sent, and checking it again shows the worked-out one and keeps what was typed', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: WITH_LIMITS }),
        costDaily: () => ({ status: 200, body: { daily: '11.36', weekdays: 22 } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await change(page, 'set-costDailyAutomatic', false);
    assert.equal(page.el('set-costDailyLimit').attrs.disabled, undefined, 'it can be typed in');
    assert.equal(page.el('set-costDailyLimit').value, '', 'nothing was typed before');
    page.type('set-costDailyLimit', '9.5');

    await change(page, 'set-costDailyAutomatic', true);
    assert.equal(page.el('set-costDailyLimit').attrs.disabled, '');
    assert.equal(page.el('set-costDailyLimit').value, '11.36', 'the one worked out again');

    await change(page, 'set-costDailyAutomatic', false);
    assert.equal(page.el('set-costDailyLimit').value, '9.5', 'and what was typed is back');

    await page.click('settings-save');
    assert.equal(costPosts(page)[0].costDailyAutomatic, false);
    assert.equal(costPosts(page)[0].costDailyLimit, '9.5');
    assert.equal(costPosts(page)[0].costMonthlyLimit, '250.00');
});

test('an emptied monthly limit is sent as no limit, and a limit the backend refuses keeps the view open in red', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: WITH_LIMITS }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    await change(page, 'set-costMonthlyLimit', '');
    assert.equal(page.el('set-costDailyLimit').value, '', 'nothing to work the daily one out from');
    state.postSettings = () => ({ status: 400, body: { error: 'The daily limit cannot be more than the monthly limit.' } });
    await page.click('settings-save');

    assert.equal(costPosts(page)[0].costMonthlyLimit, null);
    assert.equal(page.el('settings-error').textContent, 'The daily limit cannot be more than the monthly limit.');
    assert.equal(page.el('settings-view').hidden, false);
});

test('an answer about the daily limit that arrives after a later question was asked is not shown', async () => {
    const waiting = [];
    const state = { config: CONFIG, status: SPEND_STATUS, costDaily: call => new Promise(resolve => waiting.push({ url: call.url, resolve })) };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    page.el('set-costMonthlyLimit').value = '440';
    page.el('set-costMonthlyLimit').listeners.change();
    page.el('set-costMonthlyLimit').value = '';
    page.el('set-costMonthlyLimit').listeners.change();
    await settle();
    assert.equal(waiting.length, 2);

    // The later question is answered first, the earlier one after it.
    waiting[1].resolve({ status: 200, body: { daily: null, weekdays: 22 } });
    await settle();
    waiting[0].resolve({ status: 200, body: { daily: '20.00', weekdays: 22 } });
    await settle();

    assert.equal(page.el('set-costDailyLimit').value, '', 'the monthly limit is empty, so there is no daily limit, whatever arrived last');
});

// ---- the tabs of the settings

const TAB_PAGES = { main: 'tab-main', history: 'tab-history', cost: 'tab-cost', debug: 'tab-debug', general: 'tab-general', about: 'about-view' };
const shownTabs = page => Object.keys(TAB_PAGES).filter(tab => !page.el(TAB_PAGES[tab]).hidden);

test('the settings open on the first tab, General, with Save and Cancel under it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');

    assert.deepEqual(shownTabs(page), ['general']);
    assert.equal(page.el('tabbtn-general').attrs['aria-selected'], 'true');
    assert.equal(page.el('tabbtn-main').attrs['aria-selected'], 'false');
    assert.equal(page.el('tabbtn-history').attrs['aria-selected'], 'false');
    assert.equal(page.el('settings-buttons').hidden, false);
});

test('a title in the strip shows its tab, and only that one', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    for (const tab of ['history', 'cost', 'general', 'about', 'main']) {
        await page.click('tabbtn-' + tab);
        assert.deepEqual(shownTabs(page), [tab]);
        assert.equal(page.el('tabbtn-' + tab).attrs['aria-selected'], 'true');
        assert.equal(page.el('settings-buttons').hidden, false, 'Save and Cancel are under every tab, ' + tab);
        assert.equal(page.el('settings-save').hidden, false);
        assert.equal(page.el('settings-cancel').hidden, false);
    }
});

test('there are no arrows: a tab is reached by its title, and the tab shown is marked', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('tab-prev'), undefined, 'no arrow to the previous tab');
    assert.equal(page.el('tab-next'), undefined, 'no arrow to the next tab');

    const seen = [];
    for (const tab of ['history', 'cost', 'general', 'about', 'main']) {
        await page.click('tabbtn-' + tab);
        seen.push(shownTabs(page)[0]);
        assert.equal(page.el('tabbtn-' + tab).attrs['aria-selected'], 'true');
    }
    assert.deepEqual(seen, ['history', 'cost', 'general', 'about', 'main']);
});

test('in debug mode the Debug tab is between Cost control and General', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    const order = [];
    for (const tab of ['history', 'cost', 'debug', 'general', 'about']) {
        await page.click('tabbtn-' + tab);
        order.push(shownTabs(page)[0]);
    }
    assert.deepEqual(order, ['history', 'cost', 'debug', 'general', 'about']);
});

test('the settings open on the tab they were left on, however they were left', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-general');
    await page.click('settings-cancel');
    await page.click('settings-button');
    assert.deepEqual(shownTabs(page), ['general'], 'after Cancel');

    await page.click('tabbtn-history');
    await page.click('settings-save');
    await page.click('settings-button');
    assert.deepEqual(shownTabs(page), ['history'], 'after Save');

    await page.click('tabbtn-cost');
    await page.click('settings-button');
    await page.click('settings-button');
    assert.deepEqual(shownTabs(page), ['cost'], 'after the gear');

    await page.click('history-button');
    await page.click('settings-button');
    assert.deepEqual(shownTabs(page), ['cost'], 'after the button of another panel');
});

test('the tab the settings are on is the page\'s own to remember: nothing of it is told to the backend', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-main');
    await page.click('tabbtn-history');
    await page.click('settings-cancel');

    assert.deepEqual(page.calls.filter(c => c.method === 'POST').map(c => c.url), [], 'no request for a tab, and none at all');
});

test('the first opening after a start is on General, whatever a backend says of a tab, and after that on the tab the settings were left on', async () => {
    // A backend of an earlier version said the tab the settings were last on; it is not looked at any more.
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: { ...SETTINGS, settingsTab: 'cost' } }) }));
    await page.click('settings-button');
    assert.deepEqual(shownTabs(page), ['general'], 'the first tab');

    for (const [tab, leave] of [['main', 'settings-cancel'], ['about', 'settings-save'], ['history', 'settings-button'], ['cost', 'log-button']]) {
        await page.click('tabbtn-' + tab);
        await page.click(leave);
        await page.click('settings-button');
        assert.deepEqual(shownTabs(page), [tab], 'left by ' + leave + ' on ' + tab + ': opened there again');
    }
});

test('what was changed on one tab is kept while another is shown, and sent together', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-showDeltaUsed').checked = true;
    await page.click('tabbtn-history');
    page.el('set-historyDate').checked = true;
    await page.click('tabbtn-main');
    assert.equal(page.el('set-showDeltaUsed').checked, true);

    await page.click('settings-save');

    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.showDeltaUsed, true);
    assert.equal(sent.historyDate, true);
});

test('the History view tab has a checkbox for every column, in the order of the view, and they are sent', async () => {
    const order = ['historyTime', 'historyUsed', 'historyLimit', 'historyDeltaUsed', 'historyDeltaTime', 'historyCurrent'];
    const ids = [...INDEX_HTML.matchAll(/id="set-(history[A-Za-z]+)"/g)].map(m => m[1]).filter(id => order.includes(id));
    assert.deepEqual(ids, order, 'in the page the columns are in the order of the table');
    const history = INDEX_HTML.slice(INDEX_HTML.indexOf('id="tab-history"'), INDEX_HTML.indexOf('id="tab-cost"'));
    for (const id of [...order, 'historyDate', 'historyZeroLines', 'historyFailedLines']) {
        assert.ok(history.includes('id="set-' + id + '"'), id + ' is on the tab');
    }

    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-history');
    const defaults = Object.fromEntries(order.map(id => [id, page.el('set-' + id).checked]));
    assert.deepEqual(defaults, { historyTime: true, historyUsed: true, historyLimit: true, historyDeltaUsed: false, historyDeltaTime: false, historyCurrent: true });

    page.el('set-historyTime').checked = false;
    page.el('set-historyCurrent').checked = false;
    page.el('set-historyDeltaTime').checked = true;
    await page.click('settings-save');

    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.deepEqual(order.map(id => sent[id]), [false, true, true, false, true, false]);
});

test('the window is as wide as the widest tab, whichever tab is shown', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    const widths = [];
    // The fake page lays out nothing: a page is as wide as its id says, and the form is as wide as the pages let into the layout.
    const WIDE = { 'tab-main': 500, 'tab-history': 480, 'tab-cost': 520, 'tab-debug': 640, 'tab-general': 560 };
    const form = page.el('settings-form');
    form.getBoundingClientRect = () => ({ left: 8, width: Math.max(...Object.keys(WIDE).filter(id => !page.el(id).hidden).map(id => WIDE[id])) });
    page.el('settings-buttons').rect = { left: 8, width: 120, height: 20 };

    for (const tab of ['main', 'history', 'cost', 'debug', 'general']) {
        await page.click('tabbtn-' + tab);
        widths.push(Number(page.window.contentSize().split(',')[0]));
    }

    assert.deepEqual(widths, [656, 656, 656, 656, 656], 'the widest tab, 640 and the room at both sides');
    assert.deepEqual(shownTabs(page), ['general'], 'and the tab that was shown is shown again after the measuring');
});

test('the window is the width of the widest tab outside debug mode without the Debug tab', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    const WIDE = { 'tab-main': 500, 'tab-history': 480, 'tab-cost': 520, 'tab-debug': 640, 'tab-general': 560 };
    page.el('settings-form').getBoundingClientRect = () => ({ left: 8, width: Math.max(...Object.keys(WIDE).filter(id => !page.el(id).hidden).map(id => WIDE[id])) });
    page.el('settings-buttons').rect = { left: 8, width: 120, height: 20 };

    assert.equal(Number(page.window.contentSize().split(',')[0]), 576, 'the Debug tab is not there to widen it');
});

test('with the whole strip of the titles wider than everything else the window is as wide as the strip, so the settings have no scrollbar and every title is seen', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('app').rect = { left: 0, width: 120, height: 300 };
    page.el('settings-form').rect = { left: 8, width: 200, height: 200 };
    page.el('settings-buttons').rect = { left: 8, width: 150, height: 20 };
    // The strip is as wide as the view lets it be, and as wide as its titles when it is let go of that.
    const strip = page.el('tab-strip');
    let titles = 600;
    strip.getBoundingClientRect = () => ({ left: 8, width: strip.style.width === 'max-content' ? titles : 120, height: 24 });

    assert.equal(page.window.contentSize(), '616,300,3,settings', 'the strip, with the room at its left again at its right');
    assert.equal(strip.style.width, '', 'let go of only for the moment of the measuring');
    assert.equal(strip.style.flex, '');

    titles = 150;
    assert.equal(page.window.contentSize(), '400,300,3,settings', 'a strip that fits changes nothing: the row is the widest');
});

test('currentView says which panel is open and, for the settings, the tab; and nothing before the page is ready or while none is open', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(page.window.currentView(), '', 'no panel');

    await page.click('history-button');
    assert.equal(page.window.currentView(), 'history,');
    await page.click('settings-button');
    assert.equal(page.window.currentView(), 'settings,general');
    await page.click('tabbtn-main');
    assert.equal(page.window.currentView(), 'settings,main');
    await page.click('settings-cancel');
    await page.click('log-button');
    assert.equal(page.window.currentView(), 'log,');
    await page.click('errors-button');
    assert.equal(page.window.currentView(), 'errors,');
});

test('restoreView opens the panel, and the tab of the settings, as if the buttons had been pressed, and ignores what it does not know', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.window.restoreView('history', ''), true);
    assert.equal(page.window.currentView(), 'history,');
    assert.equal(page.el('panel').hidden, false);

    assert.equal(page.window.restoreView('settings', 'general'), true);
    await settle();
    assert.equal(page.window.currentView(), 'settings,general');
    assert.equal(page.el('settings-view').hidden, false);

    assert.equal(page.window.restoreView('settings', 'nonsense'), true);
    assert.equal(page.window.currentView(), 'settings,general', 'an unknown tab: the one the settings were on');
    assert.equal(page.window.restoreView('settings', 'debug'), true);
    assert.equal(page.window.currentView(), 'settings,general', 'no Debug tab outside debug mode');

    assert.equal(page.window.restoreView('nothing', ''), true, 'nothing to open, so nothing to ask again');
    assert.equal(page.window.currentView(), 'settings,general', 'and what was shown stays');
});

test('in debug mode restoreView opens the Debug tab', async () => {
    const page = await load(backendOf({ config: DEBUG_CONFIG, status: SPEND_STATUS }));

    assert.equal(page.window.restoreView('settings', 'debug'), true);

    assert.equal(page.window.currentView(), 'settings,debug');
});

test('restoreView is false while the page has not its configuration, and currentView is empty then', async () => {
    const page = await load(async call => (call.url === '/api/config' ? { status: 500, body: {} } : { status: 200, body: {} }));

    assert.equal(page.window.restoreView('log', ''), false, 'not ready: ask again');
    assert.equal(page.window.currentView(), '');
});

test('the checkbox that restores the window at start is in the General tab, in the group Window, off by default, and sent with the rest', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-restoreWindow').checked, false, 'off by default');

    page.el('set-restoreWindow').checked = true;
    await page.click('settings-save');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).restoreWindow, true);
});

// ---- the cost control: two bars

const rowsOf = page => page.el('cost-rows').children.map(line => ({
    label: line.children[0].textContent, bar: line.children[1], fill: line.children[1].children[0], text: line.children[2].children[0].textContent,
    figures: line.children[2].children, tooltip: line.title, cells: line.children,
}));

test('the cost button is there unless the backend says otherwise, and opens and closes the panel with the blue, bigger button', async () => {
    const hidden = await load(backendOf({ config: CONFIG, status: withShow(SPEND_STATUS, { costIcon: false }) }));
    assert.equal(hidden.el('cost-button').hidden, true);

    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(page.el('cost-button').hidden, false);
    await page.click('cost-button');
    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('cost-view').hidden, false);
    assert.equal(page.el('panel-lines').hidden, true);
    assert.equal(page.el('log-controls').hidden, true, 'no buttons of the zoom: it has no text to size');
    assert.equal(page.el('cost-button').title, 'Hide the cost control');
    assert.match(page.el('cost-button').className, /\bactive\b/);

    await page.click('cost-button');
    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.el('cost-view').hidden, true);
    assert.doesNotMatch(page.el('cost-button').className, /active/);
    assert.equal(page.el('cost-button').title, 'Show the cost control');
});

test('the two bars are drawn from the backend\'s texts: the label, the share, the figures, and red and full over the limit with no sign', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('cost-button');

    const [day, month] = rowsOf(page);
    assert.equal(day.label, 'Today');
    assert.equal(day.text, '12.40 of 25.00 \u00b7 50%');
    assert.equal(day.fill.style.width, '50%');
    assert.doesNotMatch(day.bar.className, /\bover\b/, 'under the limit');
    assert.equal(day.tooltip, 'Today: 12.40 of 25.00 (50%)');
    assert.equal(day.cells.length, 3, 'three cells, as in every row: the label, the bar, and the figures');
    assert.equal(day.figures.length, 1, 'the figures alone: there is no warning sign since 0.82');
    assert.equal(day.cells[2].className, 'cost-figures');
    for (const cell of [day.cells[0], day.cells[1], day.cells[2].children[0]]) {
        assert.equal(cell.title, day.tooltip, 'each part of the row says the tooltip of the row');
    }
    assert.equal(month.label, 'Month');
    assert.equal(month.text, '300.00 of 250.00 \u00b7 120%', 'the true percentage, over the limit');
    assert.equal(month.fill.style.width, '100%', 'a full bar');
    assert.match(month.bar.className, /\bover\b/, 'red, every block');
    assert.equal(month.figures.length, 1, 'and no sign');
    assert.equal(page.el('cost-note').hidden, true);
    assert.equal(page.el('cost-rows').children.length, 2);
});

test('a bar with no limit is empty and gray, and a note under the bars is shown when there is one', async () => {
    const cost = { rows: [
        { label: 'Today', text: '1.10 \u00b7 no limit set', tooltip: 'Today: 1.10, no limit set', fraction: 0, over: false, noLimit: true },
        { label: 'Month', text: '0.00 \u00b7 no limit set', tooltip: 'Month: 0.00, no limit set', fraction: 0.7, over: false, noLimit: true },
    ], note: 'No usage has been read yet.' };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, cost: () => ({ status: 200, body: cost }) }));

    await page.click('cost-button');

    for (const row of rowsOf(page)) {
        assert.match(row.bar.className, /\bnone\b/);
        assert.equal(row.fill.style.width, '0%', 'whatever the share the backend sent');
        assert.doesNotMatch(row.bar.className, /\bover\b/);
    }
    assert.equal(page.el('cost-note').hidden, false);
    assert.equal(page.el('cost-note').textContent, 'No usage has been read yet.');
});

test('the cost control is read with each poll and drawn again only when what it says changed', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, cost: () => ({ status: 200, body: COST }) };
    const page = await load(backendOf(state));
    await page.click('cost-button');
    const reads = () => page.calls.filter(c => c.url === '/api/cost').length;
    const first = page.el('cost-rows').children[0];
    assert.equal(reads(), 1);

    await page.firePoll();
    assert.equal(reads(), 2, 'read again at the poll');
    assert.equal(page.el('cost-rows').children[0], first, 'the same bars: nothing was drawn again');

    state.cost = () => ({ status: 200, body: { ...COST, rows: [{ ...COST.rows[0], text: '13.40 of 25.00 \u00b7 54%', fraction: 0.54 }, COST.rows[1]] } });
    await page.firePoll();
    assert.notEqual(page.el('cost-rows').children[0], first, 'drawn again: the figures changed');
    assert.equal(rowsOf(page)[0].text, '13.40 of 25.00 \u00b7 54%');
    assert.equal(rowsOf(page)[0].fill.style.width, '54%');
});

test('a cost control that cannot be read keeps what was shown and says so in red', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('cost-button');
    state.cost = () => ({ status: 500, body: { error: 'Internal error; see the log.' } });

    await page.firePoll();

    assert.equal(rowsOf(page).length, 2, 'what was shown stays');
    assert.equal(page.el('panel-error').hidden, false);
    assert.match(page.el('panel-error').textContent, /^The cost control could not be read: /);
});

test('the cost control asks for a window as wide as the history or as its bars need, as tall as its content, and not resizable', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('cost-button');

    const parts = page.window.contentSize().split(',');

    assert.equal(parts[2], '3', 'fixed: no dragging');
    assert.equal(parts[3], 'cost');
    assert.ok(Number(parts[0]) >= 399, 'at least the row');
    assert.equal(Number(parts[1]), 42, 'the page\'s own height, 41.5 rounded up');
});

test('the width of the cost control follows the widest row and the history\'s table', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('cost-button');
    const [day, month] = page.el('cost-rows').children;
    day.children[0].rect = { width: 40 };
    day.children[2].rect = { width: 320 };
    month.children[0].rect = { width: 60 };
    month.children[2].rect = { width: 200 };

    const wide = Number(page.window.contentSize().split(',')[0]);

    // The rows share their columns: the wider label, the bar's least width, the wider figures (the sign is in their cell), two gaps of 6, and the padding.
    assert.equal(wide, 60 + 120 + 320 + 2 * 6 + 16);
});

test('the checkbox Cost button is in Main view, is posted with the rest and shows or hides the button in the row at once', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    assert.equal(page.el('set-showCostIcon').checked, true, 'on by default');

    await change(page, 'set-showCostIcon', false);
    assert.equal(page.el('cost-button').hidden, false, 'while the settings are shown the button stays');
    assert.match(page.el('cost-button').className, /\boff\b/, 'dimmed at once');
    // The backend says what was saved from the next status on.
    state.status = withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: false });
    await page.click('settings-save');
    assert.equal(page.el('cost-button').hidden, true, 'and goes when they are left');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).showCostIcon, false);
});

test('the cost control is a panel like the others: another button replaces it, and the settings give it back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('cost-button');

    await page.click('history-button');
    assert.equal(page.el('cost-view').hidden, true, 'replaced by the history');
    assert.equal(page.window.currentView(), 'history,');

    await page.click('cost-button');
    await page.click('settings-button');
    assert.equal(page.el('cost-view').hidden, true);
    assert.equal(page.window.currentView(), 'settings,' + 'general');
    await page.click('settings-cancel');
    assert.equal(page.el('cost-view').hidden, false, 'given back');
    assert.equal(page.window.currentView(), 'cost,');
});

test('currentView and restoreView know the cost control', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.window.restoreView('cost', ''), true);

    assert.equal(page.window.currentView(), 'cost,');
    assert.equal(page.el('cost-view').hidden, false);
});

test('the status keeps the button of the open cost control even when its switch is off', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(SPEND_STATUS, { costIcon: false }) }));
    assert.equal(page.window.restoreView('cost', ''), true);

    assert.equal(page.el('cost-button').hidden, false, 'a button that is switched off is never taken from a panel that is open');
});

// ---- version 0.79: the button of the history that hides and shows the zero usage lines

const historyGets = page => page.calls.filter(c => c.url.split('?')[0] === '/api/history' && c.method === 'GET').map(c => c.url);
const historyPosts = page => page.calls.filter(c => c.url === '/api/history' && c.method === 'POST').map(c => JSON.parse(c.body));
// A backend that keeps the choice, as the program does: the history has its three lines, or the one that changed.
function historyBackend(extra = {}) {
    const state = { config: CONFIG, status: SPEND_STATUS, zeroLines: true, ...extra };
    const all = [line(['20:46:11', '260.66', '1000.00', 'USD']), line(['20:45:11', '260.36', '1000.00', 'USD']), line(['20:44:12', '260.36', '1000.00', 'USD'])];
    state.history = call => {
        const asked = /zeroLines=(true|false)/.exec(call.url);
        const zero = asked ? asked[1] === 'true' : state.zeroLines;
        return { status: 200, body: { ...HISTORY, zeroLines: state.zeroLines, total: 3, lines: zero ? all : [all[0]] } };
    };
    state.postHistory = state.postHistory || (body => { state.zeroLines = body.zeroLines; return { status: 200, body }; });
    return state;
}

test('the history has a button for its zero usage lines, shown with the history alone and when its first answer has come', async () => {
    const page = await load(backendOf(historyBackend()));
    assert.equal(page.el('history-choices').hidden, true, 'nothing before a panel is shown');

    await page.click('history-button');
    assert.equal(page.el('history-choices').hidden, false);
    assert.equal(page.el('zero-lines').title, 'Hide the zero usage lines and the failed lines', 'they are shown, so a press hides them');
    assert.equal(page.el('zero-lines').attrs['aria-label'], 'Hide the zero usage lines and the failed lines');
    assert.equal(page.el('zero-lines').attrs['aria-pressed'], 'false');
    assert.doesNotMatch(page.el('zero-lines').className, /\bon\b/, 'gray, like the zoom buttons');

    for (const other of ['log-button', 'errors-button', 'cost-button', 'settings-button']) {
        await page.click(other);
        assert.equal(page.el('history-choices').hidden, true, 'not with ' + other);
        if (other === 'settings-button') {
            await page.click('settings-cancel');
        }
    }
});

test('the button comes up as the setting is saved: blue, and offering to show them, when the zero usage lines are hidden', async () => {
    const page = await load(backendOf(historyBackend({ zeroLines: false })));

    await page.click('history-button');

    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines');
    assert.equal(page.el('zero-lines').attrs['aria-pressed'], 'true');
    assert.match(page.el('zero-lines').className, /^icon tiny on$/, 'blue, at the size it has: not the bigger button of an open panel');
    assert.equal(page.el('panel-lines').children.length, 2, 'the header and the one line that changed');
});

test('a press hides the zero usage lines at once, saves the setting, reads the history again and puts it at its top; another press shows them', async () => {
    const state = historyBackend();
    const page = await load(backendOf(state));
    await page.click('history-button');
    assert.equal(page.el('panel-lines').children.length, 4, 'the header and three lines');
    page.el('panel-lines').scrollTop = 40;
    const reads = historyGets(page).length;

    await page.click('zero-lines');

    assert.deepEqual(historyPosts(page), [{ zeroLines: false }], 'the setting is saved, with no Save to press');
    assert.equal(state.zeroLines, false);
    assert.equal(historyGets(page).length, reads + 1, 'the history is read again, though no row was added');
    assert.equal(historyGets(page).pop(), '/api/history', 'as the setting now says: nothing is named in the request');
    assert.equal(page.el('panel-lines').children.length, 2, 'the lines with no change are gone');
    assert.equal(page.el('panel-lines').scrollTop, 0, 'at its newest line');
    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines');
    assert.match(page.el('zero-lines').className, /\bon\b/);
    assert.equal(page.el('log-error').hidden, true);

    await page.click('zero-lines');

    assert.deepEqual(historyPosts(page), [{ zeroLines: false }, { zeroLines: true }]);
    assert.equal(page.el('panel-lines').children.length, 4, 'and back');
    assert.equal(page.el('zero-lines').title, 'Hide the zero usage lines and the failed lines');
    assert.doesNotMatch(page.el('zero-lines').className, /\bon\b/);
});

test('the checkbox of History view and the button are the one setting: a change saved in the settings is seen in the button', async () => {
    const state = historyBackend();
    const page = await load(backendOf(state));
    await page.click('history-button');
    assert.equal(page.el('zero-lines').title, 'Hide the zero usage lines and the failed lines');

    // Saved in the settings view, which gives the history back.
    await page.click('settings-button');
    state.zeroLines = false;
    await page.click('settings-cancel');

    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines', 'the history comes up with what is saved');
});

test('a choice that cannot be saved holds all the same, says so in red, and is asked for by name from then on', async () => {
    const state = historyBackend({ postHistory: () => ({ status: 500, body: { error: 'The history settings could not be saved: access is denied' } }) });
    const page = await load(backendOf(state));
    await page.click('history-button');

    await page.click('zero-lines');

    assert.equal(page.el('log-error').hidden, false);
    assert.equal(page.el('log-error').textContent, 'The history settings could not be saved: access is denied', 'the reason, said once');
    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines', 'it holds, though the backend still has the old value');
    assert.equal(historyGets(page).pop(), '/api/history?zeroLines=false', 'asked for by what was chosen, not by what is saved');
    assert.equal(page.el('panel-lines').children.length, 2);

    await page.click('history-button');
    await page.click('history-button');
    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines', 'for the run: also when the history is shown again');
    assert.equal(historyGets(page).pop(), '/api/history?zeroLines=false');

    state.postHistory = body => { state.zeroLines = body.zeroLines; return { status: 200, body }; };
    await page.click('zero-lines');
    assert.equal(page.el('log-error').hidden, true, 'the line goes with a change that is saved');
    assert.equal(historyGets(page).pop(), '/api/history', 'and the setting decides again');
});

test('an answer of the history that was asked for before a press is not shown, and does not put the button back', async () => {
    const state = historyBackend();
    const page = await load(backendOf(state));
    await page.click('history-button');
    let release;
    const held = new Promise(resolve => { release = resolve; });
    let reads = 0;
    // The first read from here on is held back and answers with the lines as they were; the one that follows it fails, so
    // that what the page shows afterwards is what it made of the held one alone.
    state.history = async () => {
        reads += 1;
        if (reads === 1) {
            await held;
            return { status: 200, body: { ...HISTORY, zeroLines: true, total: 0, lines: [] } };
        }
        return { status: 500, body: { error: 'The usage history could not be read.' } };
    };
    // A read is under way, begun by a new row, when the button is pressed.
    state.status = { ...SPEND_STATUS, historyStamp: 'another' };
    page.firePoll();
    await settle();

    page.click('zero-lines');
    await settle();
    release();
    await settle();
    await settle();

    assert.equal(reads, 2, 'the read that was overtaken is followed by one of its own');
    assert.equal(page.el('zero-lines').title, 'Show the zero usage lines and the failed lines', 'the old answer does not put the button back');
    assert.equal(page.el('panel-lines').children.length, 4, 'and its lines are not drawn: what was shown stays');
});

test('the button does nothing before the history has said what is saved, and nothing with another panel', async () => {
    const page = await load(backendOf(historyBackend()));

    await page.click('zero-lines');
    await page.click('log-button');
    await page.click('zero-lines');

    assert.deepEqual(historyPosts(page), []);
});

// ---- version 0.79: the settings have their own width, and the columns of the history have theirs

test('after a wide panel the settings ask for their own width: the strip of the titles is not measured as wide as the window has made it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('log-button');
    assert.equal(page.window.contentSize().split(',')[0], String(3 * 400), 'the log is three times as wide as the row');

    await page.click('settings-button');
    page.el('app').rect = { left: 0, width: 1200, height: 300 };
    page.el('settings-form').rect = { left: 8, width: 200, height: 200 };
    page.el('settings-buttons').rect = { left: 8, width: 150, height: 20 };
    // The window is still as wide as the log left it, and the strip fills it: 1,184 pixels, of which its titles need 380.
    const strip = page.el('tab-strip');
    strip.getBoundingClientRect = () => ({ left: 8, width: strip.style.width === 'max-content' ? 380 : 1184, height: 24 });
    strip.scrollWidth = 1184;

    assert.equal(page.window.contentSize(), '400,300,3,settings', 'the row is the widest thing: nothing of the 1,200 pixels is asked for again');
});

const kindsOf = row => row.children.map(cell => cell.className);

test('each cell of the history says what its column is, so that it has that column\'s width whichever columns are shown', async () => {
    // The currency is switched off and both changes are on: five columns, of which the last is the time change.
    const history = { ...HISTORY, columns: ['time', 'used', 'limit', '\u0394 used', '\u0394 time'], kinds: ['time', 'used', 'limit', 'deltaUsed', 'deltaTime'],
        lines: [line(['20:46:11', '260.66', '1000.00', '+0.30', '1464 s']), line(['20:44:12', 'failed', '', '', '119 s'], { failed: true })] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const [head, first, failed] = page.el('panel-lines').children;
    assert.match(head.className, /\bcolumns\b/);
    assert.deepEqual(kindsOf(head), ['c-time', 'c-used', 'c-limit', 'c-deltaUsed', 'c-deltaTime'], 'the titles as the cells under them');
    assert.deepEqual(kindsOf(first), ['c-time', 'c-used', 'c-limit', 'c-deltaUsed', 'c-deltaTime'], 'the last is the time change, not a currency');
    assert.deepEqual(kindsOf(failed), ['c-time', 'c-used failed', 'c-limit', 'c-deltaUsed', 'c-deltaTime']);
    assert.deepEqual(kindsOf(page.el('table-probe').children[1]), kindsOf(first), 'and the copy the width is measured on is the same');
});

test('the red of a failed line is on its amount used wherever that is, also when the time is switched off', async () => {
    const history = { ...HISTORY, columns: ['used', 'limit'], kinds: ['used', 'limit'], lines: [line(['failed', ''], { failed: true }), line(['260.36', '1000.00'])] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const [, failed, good] = page.el('panel-lines').children;
    assert.deepEqual(kindsOf(failed), ['c-used failed', 'c-limit'], 'the first cell here, not the second');
    assert.deepEqual(kindsOf(good), ['c-used', 'c-limit']);
});

test('the error log, which has no kinds of columns, is drawn as it was', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('errors-button');

    const row = page.el('panel-lines').children[1];
    assert.match(row.className, /\bcols-2\b/);
    assert.doesNotMatch(row.className, /\bcolumns\b/);
    assert.deepEqual(kindsOf(row), ['', '']);
});

test('a history list that can be scrolled sideways at the width the page asked for makes the page ask for that much more, once, and keep it', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    const box = page.el('panel-lines');
    const widthAsked = () => Number(page.window.contentSize().split(',')[0]);
    const asked = widthAsked();
    assert.equal(widthAsked(), asked, 'with nothing known of the window, nothing is added');

    // The window has the width asked for, and the list lacks 15 pixels: its own width is 100 in this stand-in.
    page.window.innerWidth = asked;
    box.scrollWidth = 115;
    assert.equal(widthAsked(), asked + 15, 'what the list lacks is added');
    assert.equal(widthAsked(), asked + 15, 'and not a second time while the window is still as wide as it was');

    page.window.innerWidth = asked + 15;
    box.scrollWidth = 100;
    assert.equal(widthAsked(), asked + 15, 'kept when the list has room: the width does not go back and forth');

    box.scrollWidth = 1000;
    assert.equal(widthAsked(), asked + 48, 'never by more than a scrollbar and a little, whatever the list says');
});

test('what the history list lacked is forgotten when the table is another, and is nothing for a window that is not as wide as was asked', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    const box = page.el('panel-lines');
    const widthAsked = () => Number(page.window.contentSize().split(',')[0]);
    const asked = widthAsked();

    page.window.innerWidth = asked + 200;
    box.scrollWidth = 130;
    assert.equal(widthAsked(), asked, 'a window of another width says nothing of the width that was asked for');

    page.window.innerWidth = asked;
    assert.equal(widthAsked(), asked + 30);
    // Another table: a column was switched on, so the copy it is measured on is wider.
    page.el('table-probe').rect = { width: 500, height: 0 };
    page.el('table-probe').children.push({});
    box.scrollWidth = 100;
    assert.equal(widthAsked(), 500 + 16, 'the new table by itself, with nothing carried over');
});

// ---- version 0.79: the cost control has no title bar when the setting is off

test('with the title bar off the cost control has the border of a window with no title bar, and is larger by it; every other panel takes it away', async () => {
    const page = await load(backendOf({ config: CONFIG, status: bareStatus(SPEND_STATUS) }));

    await page.click('cost-button');

    assert.match(page.el('app').className, /\bbare\b/, 'the cost control cannot be resized, so the window has no title bar with it');
    assert.match(page.el('app').className, /\bfit\b/, 'and is as tall as its content, as before');
    const [width, height, resize, panel] = page.window.contentSize().split(',');
    assert.equal(resize + ',' + panel, '3,cost');
    assert.ok(Number(width) >= 402, 'at least the row and a pixel of border at each side: ' + width);
    assert.equal(Number(height), 42, 'the height of the page, which has the border in it');

    for (const button of ['log-button', 'history-button', 'errors-button', 'settings-button']) {
        await page.click(button);
        assert.doesNotMatch(page.el('app').className, /\bbare\b/, button + ': a panel that can be resized, so the title bar is there');
        if (button === 'settings-button') {
            await page.click('settings-cancel');
        } else {
            await page.click('cost-button');
        }
        assert.match(page.el('app').className, /\bbare\b/, 'and the border is back with the cost control');
    }
});

test('with the title bar on, and with the fake backend in use, the cost control has no such border', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('cost-button');
    assert.doesNotMatch(page.el('app').className, /\bbare\b/);

    const fake = await load(backendOf({ config: CONFIG, status: { ...bareStatus(SPEND_STATUS), fake: true } }));
    await fake.click('cost-button');
    assert.doesNotMatch(fake.el('app').className, /\bbare\b/, 'the red border is round the window then');
    assert.match(fake.el('app').className, /\bfake\b/);
});

// ---- version 0.79: the tab About can be dragged wider

test('the settings say that their width may be dragged while About is shown, and on no other tab', async () => {
    const page = await load(backendOf({ config: { ...CONFIG, debug: true }, status: SPEND_STATUS }));
    await page.click('settings-button');
    const flagOf = () => page.window.contentSize().split(',').slice(2).join(',');

    for (const tab of ['general', 'main', 'history', 'cost', 'debug']) {
        await page.click('tabbtn-' + tab);
        assert.equal(flagOf(), '3,settings', tab + ' cannot be dragged');
    }
    await page.click('tabbtn-about');
    assert.equal(flagOf(), '4,settings', 'About: the width only');
    await page.click('tabbtn-main');
    assert.equal(flagOf(), '3,settings', 'and not any more when another tab is chosen');

    await page.click('tabbtn-about');
    await page.click('settings-cancel');
    await page.click('settings-button');
    assert.equal(flagOf(), '4,settings', 'the settings open on the tab they were left on, which may be About');
    await page.click('settings-cancel');
    assert.equal(page.window.contentSize().split(',')[2], '0', 'with the settings left, nothing');
});

test('with About shown the width asked for is the least width whatever the window has, and the height is that of the page as it lies', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    await page.click('tabbtn-about');
    page.el('settings-form').rect = { left: 8, width: 200, height: 200 };
    page.el('settings-buttons').rect = { left: 8, width: 150, height: 20 };
    const strip = page.el('tab-strip');
    // The window was dragged to 900 pixels: the app and the strip are as wide, and the paths need fewer lines.
    const app = { left: 0, width: 400, height: 380 };
    page.el('app').getBoundingClientRect = () => app;
    strip.getBoundingClientRect = () => ({ left: 8, width: strip.style.width === 'max-content' ? 380 : app.width - 16, height: 24 });

    assert.equal(page.window.contentSize(), '400,380,4,settings');

    app.width = 900;
    app.height = 344;
    assert.equal(page.window.contentSize(), '400,344,4,settings', 'the least width still, and the lower height the wider window gives the page');
});

// ---- version 0.79: the log can be reset, and the reset undone

const logFilePosts = page => page.calls.filter(c => c.url === '/api/log/reset' || c.url === '/api/log/merge').map(c => c.method + ' ' + c.url + ' ' + c.body);
// A backend that keeps old logs as the program does: a reset keeps the lines under another name, a merge puts them back.
function logFilesBackend(extra = {}) {
    const state = { config: CONFIG, status: SPEND_STATUS, lines: [...LOG.lines], old: [], ...extra };
    state.log = () => ({ status: 200, body: { ...LOG, lines: state.lines, oldLog: state.old.length > 0 } });
    state.logFiles = state.logFiles || (call => {
        if (call.url === '/api/log/reset') {
            state.old.push(state.lines);
            state.lines = ['2026-10-08 19:05:00 INFO    [Logging] The log was reset: the lines before are in java-aip-usage.2026-10-08_19-05-00.log'];
        } else if (state.old.length === 0) {
            return { status: 409, body: { error: 'There is no old log.' } };
        } else {
            state.lines = [...state.old.pop(), ...state.lines, '2026-10-08 19:06:00 INFO    [Logging] The log was merged with java-aip-usage.2026-10-08_19-05-00.log'];
        }
        return { status: 200, body: { oldLog: state.old.length > 0 } };
    });
    return state;
}

test('the log has two buttons after the zoom buttons, shown with the log alone: one that resets it, and one that takes the reset back', async () => {
    const page = await load(backendOf(logFilesBackend()));
    assert.equal(page.el('log-files').hidden, true, 'nothing before a panel is shown');

    await page.click('log-button');
    assert.equal(page.el('log-files').hidden, false);
    assert.equal(page.el('log-merge').title, 'There is no old log', 'nothing to put back');
    assert.equal(page.el('log-merge').attrs['aria-label'], 'There is no old log');
    assert.ok('disabled' in page.el('log-merge').attrs, 'so the button cannot be pressed');

    for (const other of ['history-button', 'errors-button', 'cost-button', 'settings-button']) {
        await page.click(other);
        assert.equal(page.el('log-files').hidden, true, 'not with ' + other);
        if (other === 'settings-button') {
            await page.click('settings-cancel');
        }
    }
});

test('a press on the reset asks the program to reset the log, naming no file, and shows the new log at its newest line; the other button can then be pressed', async () => {
    const state = logFilesBackend();
    const page = await load(backendOf(state));
    await page.click('log-button');
    assert.equal(page.el('panel-lines').children.length, 2);
    page.el('panel-lines').scrollTop = 30;

    await page.click('log-reset');

    assert.deepEqual(logFilePosts(page), ['POST /api/log/reset {}'], 'no file and no path in the request');
    assert.equal(lines(page).length, 1, 'the new file has the one line that says where the others are');
    assert.match(lines(page)[0], /The log was reset: the lines before are in /);
    assert.equal(page.el('panel-lines').scrollTop, 0);
    assert.ok(!('disabled' in page.el('log-merge').attrs), 'there is an old log now');
    assert.equal(page.el('log-merge').title, 'Undo the reset: put the newest old log back in front of this one');
    assert.equal(page.el('log-error').hidden, true);
});

test('a press on the other button takes the reset back: the lines are there again, and with no old log left it cannot be pressed', async () => {
    const state = logFilesBackend();
    const page = await load(backendOf(state));
    await page.click('log-button');
    await page.click('log-reset');

    await page.click('log-merge');

    assert.deepEqual(logFilePosts(page), ['POST /api/log/reset {}', 'POST /api/log/merge {}']);
    assert.equal(page.el('panel-lines').children.length, 4, 'the two lines of before, the line of the reset and the line of the merge');
    assert.ok('disabled' in page.el('log-merge').attrs, 'the one old log is gone');
    assert.equal(page.el('log-merge').title, 'There is no old log');

    await page.click('log-reset');
    await page.click('log-reset');
    await page.click('log-merge');
    assert.ok(!('disabled' in page.el('log-merge').attrs), 'two resets, one taken back: one old log is left');
});

test('the button of the merge follows what each answer of the log says of an old log, also one that is there from an earlier run', async () => {
    const state = logFilesBackend({ old: [['2026-10-07 10:00:00 INFO    [A] of yesterday']] });
    const page = await load(backendOf(state));

    await page.click('log-button');
    assert.ok(!('disabled' in page.el('log-merge').attrs), 'an old log was there when the log was opened');

    state.old = [];
    await page.firePoll();
    assert.ok('disabled' in page.el('log-merge').attrs, 'and is gone at the next read');
});

test('a reset or a merge that cannot be done says so in red, once, and leaves the log as it is shown', async () => {
    const state = logFilesBackend({ logFiles: call => ({ status: 500, body: { error: (call.url === '/api/log/reset' ? 'The log could not be reset: ' : 'The log could not be merged: ') + 'access is denied' } }) });
    const page = await load(backendOf(state));
    await page.click('log-button');

    await page.click('log-reset');
    assert.equal(page.el('log-error').hidden, false);
    assert.equal(page.el('log-error').textContent, 'The log could not be reset: access is denied', 'the reason, said once');
    assert.equal(page.el('panel-lines').children.length, 2, 'the lines are as they were');

    state.old = [[]];
    await page.firePoll();
    await page.click('log-merge');
    assert.equal(page.el('log-error').textContent, 'The log could not be merged: access is denied');

    state.logFiles = () => ({ status: 409, body: { error: 'There is no old log.' } });
    await page.click('log-merge');
    assert.equal(page.el('log-error').textContent, 'The log could not be merged: There is no old log.', 'an old log that went meanwhile');
});

test('the two buttons of the log do nothing with another panel', async () => {
    const page = await load(backendOf(logFilesBackend()));
    await page.click('history-button');

    await page.click('log-reset');
    await page.click('log-merge');

    assert.deepEqual(logFilePosts(page), []);
});

// ---- version 0.80: a tooltip for each column title of the history

test('each title of the history has the tooltip the backend wrote for it, and the cells under it have none of it', async () => {
    const history = { ...HISTORY, columns: ['time', 'used', '\u0394 used'], kinds: ['time', 'used', 'deltaUsed'],
        columnTitles: ['When the usage was read', 'The amount used at that reading', 'The change in the amount used since the last earlier reading: + for a rise, - for a fall'],
        lines: [line(['20:46:11', '260.66', '-0.14']), line(['20:44:12', '260.80', '+0.30'], { start: true, title: 'The program started here' })] };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: history }) }));

    await page.click('history-button');

    const [head, first, start] = page.el('panel-lines').children;
    assert.deepEqual(head.children.map(cell => cell.title), history.columnTitles, 'as given, one for each title');
    assert.deepEqual(first.children.map(cell => cell.title), ['', '', ''], 'a cell of a line has none');
    assert.equal(first.children[2].textContent, '-0.14', 'a fall is drawn as the backend wrote it');
    assert.equal(start.title, 'The program started here', 'a line keeps the tooltip it has');
    assert.deepEqual(page.el('table-probe').children[0].children.map(cell => cell.title), ['', '', ''], 'the copy that is measured needs none');
});

test('a history that says no tooltips for its titles is drawn without them', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('history-button');
    assert.deepEqual(page.el('panel-lines').children[0].children.map(cell => cell.title), ['', '', '', '']);
});

// ---- version 0.80: the history with a note that says lines are shown, and none under it

test('a history with no column switched on shows its note, in blue, and no list of empty lines', async () => {
    const none = { ...HISTORY, columns: [], kinds: [], columnTitles: [], lines: [], total: 48,
        note: 'No column of the history is switched on. Switch one on in the settings, under History view.', noteHighlight: true };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: none }) }));

    await page.click('history-button');

    assert.equal(page.el('panel-note').hidden, false);
    assert.equal(page.el('panel-note').textContent, none.note);
    assert.match(page.el('panel-note').className, /\bhighlight\b/);
    assert.equal(page.el('panel-lines').hidden, true, 'no list, since there is no line to draw');
    assert.equal(page.el('history-choices').hidden, false, 'the button of the zero usage lines is still there');
});

test('the list of a view is shown whenever that view has lines, whatever view was shown before it', async () => {
    for (const before of ['cost-button', 'settings-button', 'log-button', 'errors-button']) {
        const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
        await page.click(before);
        if (before === 'settings-button') {
            await page.click('settings-cancel');
        }
        await page.click('history-button');
        assert.equal(page.el('panel-lines').hidden, false, 'after ' + before);
        assert.equal(page.el('panel-lines').children.length, 3, 'the header and the two lines, after ' + before);
    }
});

test('a view that is opened while the cost control is still being read is read at once, not at the next poll', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    let release;
    const held = new Promise(resolve => { release = resolve; });
    state.cost = async () => { await held; return { status: 200, body: COST }; };
    const page = await load(backendOf(state));
    page.click('cost-button');
    await settle();

    page.click('history-button');
    await settle();
    release();
    await settle();
    await settle();

    assert.equal(page.el('panel-lines').hidden, false, 'the history is drawn as soon as the read of the cost control has ended');
    assert.equal(page.el('panel-lines').children.length, 3);
    assert.equal(page.el('cost-view').hidden, true);
});

// ---- version 0.80: the paths in the log are links

// Where a path stands in its line is counted here as the backend counts it, not by hand.
const LINKED_LINES = ['2026-10-08 19:00:00 INFO    [Logging] Logging to C:\\work\\java-aip-usage.log', '2026-10-08 19:00:01 INFO    [A] nothing to see',
    '2026-10-08 19:00:02 INFO    [Logging] The log was reset: the lines before are in C:\\work\\java-aip-usage.2026-10-08_19-00-02.log now'];
const linkAt = (line, path, file) => ({ line, from: LINKED_LINES[line].indexOf(path), to: LINKED_LINES[line].indexOf(path) + path.length, file });
const LINKED_LOG = {
    ...LOG, date: true, lines: LINKED_LINES,
    links: [linkAt(0, 'C:\\work\\java-aip-usage.log', 'log'),
        linkAt(2, 'C:\\work\\java-aip-usage.2026-10-08_19-00-02.log', 'old:java-aip-usage.2026-10-08_19-00-02.log')],
};
const partsOf = row => row.children[0].children.map(part => (part.id === '<a>' ? 'link:' : 'text:') + part.textContent);

test('a path the backend marks in a line of the log is a link, and the rest of the line is text', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: LINKED_LOG }) }));

    await page.click('log-button');

    const [reset, plain, first] = page.el('panel-lines').children;
    assert.deepEqual(partsOf(first), ['text:2026-10-08 19:00:00 INFO    [Logging] Logging to ', 'link:C:\\work\\java-aip-usage.log'], 'the text before, and the path as a link');
    assert.deepEqual(partsOf(reset), ['text:2026-10-08 19:00:02 INFO    [Logging] The log was reset: the lines before are in ',
        'link:C:\\work\\java-aip-usage.2026-10-08_19-00-02.log', 'text: now'], 'and what follows the path is text again');
    assert.equal(plain.children[0].children.length, 0, 'a line with no path is drawn as it was');
    assert.equal(plain.children[0].textContent, '2026-10-08 19:00:01 INFO    [A] nothing to see');
    const link = first.children[0].children[1];
    assert.equal(link.className, 'about-link', 'it looks like a path of About');
    assert.equal(link.title, 'Open the folder of this file');
    assert.equal(link.attrs.href, '#');
});

test('a click on a link of the log asks the program for the file by the name the backend gave, and never by the path', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: LINKED_LOG }) }));
    await page.click('log-button');
    const reset = page.el('panel-lines').children[0];
    let prevented = false;

    await reset.children[0].children[1].listeners.click({ preventDefault() { prevented = true; } });
    await settle();

    const opens = page.calls.filter(c => c.url === '/api/log/open');
    assert.deepEqual(opens.map(c => c.method + ' ' + c.body), ['POST {"file":"old:java-aip-usage.2026-10-08_19-00-02.log"}']);
    assert.doesNotMatch(opens[0].body, /work/, 'no path in the request');
    assert.equal(prevented, true, 'the link leads nowhere by itself');
    assert.equal(page.el('log-error').hidden, true);
});

test('a folder that cannot be opened from the log says so in red, once', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: LINKED_LOG }),
        logOpen: () => ({ status: 500, body: { error: 'The folder could not be opened: the system did not start it' } }) }));
    await page.click('log-button');

    await page.el('panel-lines').children[2].children[0].children[1].listeners.click({ preventDefault() {} });
    await settle();

    assert.equal(page.el('log-error').hidden, false);
    assert.equal(page.el('log-error').textContent, 'The folder could not be opened: the system did not start it');
});

test('with the date cut from the lines of the log a link is where its path is', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: { ...LINKED_LOG, date: false } }) }));

    await page.click('log-button');

    assert.deepEqual(partsOf(page.el('panel-lines').children[2]), ['text:19:00:00 INFO    [Logging] Logging to ', 'link:C:\\work\\java-aip-usage.log']);
});

test('the links of the log are not built again while the log says the same', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, log: () => ({ status: 200, body: LINKED_LOG }) }));
    await page.click('log-button');
    const link = page.el('panel-lines').children[2].children[0].children[1];

    await page.firePoll();

    assert.equal(page.el('panel-lines').children[2].children[0].children[1], link, 'the same link is under the mouse after a poll');
});

// ---- version 0.80: several views at once

const SEVERAL = withShow(WITH_CHANGE, { historyIcon: true, logIcon: true, errorIcon: true, costIcon: true, severalViews: true });
// In the order of the icons, since 0.81: the cost control, the history, the log, the error log.
const BOX_OF = { cost: 'cost-view', history: 'view-history', log: 'view-log', errors: 'view-errors' };
const shownViews = page => Object.keys(BOX_OF).filter(name => !page.el(BOX_OF[name]).hidden);
const blueIcons = page => ['cost', 'history', 'log', 'errors'].filter(name => /\bactive\b/.test(page.el(name + '-button').className));
const withHeights = (status, heights) => ({ ...status, heights });

test('with several views at once an icon opens its view and leaves the others open, and a blue icon closes its own view alone', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));

    await page.click('cost-button');
    await page.click('history-button');
    assert.deepEqual(shownViews(page), ['cost', 'history'], 'the history is added, the cost control stays');
    assert.deepEqual(blueIcons(page), ['cost', 'history'], 'both icons are blue');
    assert.equal(page.el('history-button').title, 'Hide the usage history');
    assert.equal(page.el('cost-button').title, 'Hide the cost control');
    assert.equal(page.el('history-button').attrs['aria-expanded'], 'true');
    assert.equal(page.el('panel-lines-history').children.length, 3, 'the history has its lines');
    assert.equal(page.el('cost-rows').children.length, 2, 'and the cost control its bars');

    await page.click('cost-button');
    assert.deepEqual(shownViews(page), ['history'], 'a press on a blue icon closes that view, and that view alone');
    assert.deepEqual(blueIcons(page), ['history']);
    assert.equal(page.el('cost-button').title, 'Show the cost control');
    assert.equal(page.el('cost-rows').children.length, 0, 'the view that is closed is emptied');
    assert.equal(page.el('panel-lines-history').children.length, 3, 'the one that stays is left as it is');

    await page.click('history-button');
    assert.deepEqual(shownViews(page), []);
    assert.equal(page.el('panel').hidden, true);
});

test('the views are stacked in the order of their icons, whatever order they were opened in', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));

    for (const icon of ['cost-button', 'errors-button', 'log-button', 'history-button']) {
        await page.click(icon);
    }

    assert.deepEqual(shownViews(page), ['cost', 'history', 'log', 'errors']);
    assert.equal(page.window.currentView(), 'cost+history+log+errors,', 'and are named in that order to the host');
    const boxes = INDEX_HTML.indexOf('id="cost-view"') < INDEX_HTML.indexOf('id="view-history"') && INDEX_HTML.indexOf('id="view-history"') < INDEX_HTML.indexOf('id="view-log"')
        && INDEX_HTML.indexOf('id="view-log"') < INDEX_HTML.indexOf('id="view-errors"');
    assert.ok(boxes, 'which is the order their boxes have in the page');
});

test('with several views at once switched off, an icon closes the view that is shown and opens its own, as before', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false }) }));
    await page.click('cost-button');
    await page.click('history-button');
    assert.deepEqual(shownViews(page), ['history']);
    assert.deepEqual(blueIcons(page), ['history'], 'no more than one icon is blue');
});

test('a view that stays open while another is opened beside it is not read or drawn again, and keeps what was scrolled to', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    await page.click('history-button');
    const first = page.el('panel-lines-history').children[1];
    page.el('panel-lines-history').scrollTop = 30;
    const reads = page.calls.filter(c => c.url === '/api/history').length;

    await page.click('log-button');

    assert.equal(page.el('panel-lines-history').children[1], first, 'the same lines');
    assert.equal(page.el('panel-lines-history').scrollTop, 30);
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, reads, 'it was not read again for it');
    assert.equal(page.el('log-choices').hidden, false, 'the log has its own controls');
    assert.equal(page.el('history-choices').hidden, false, 'and the history keeps its own');
});

test('each of several views keeps up by itself, and has its own note and its own red line', async () => {
    const state = { config: CONFIG, status: { ...SEVERAL, historyStamp: 'a' }, errors: () => ({ status: 200, body: { entries: [] } }) };
    const page = await load(backendOf(state));
    await page.click('log-button');
    await page.click('history-button');
    await page.click('errors-button');
    await page.click('cost-button');
    const reads = url => page.calls.filter(c => c.url.split('?')[0] === url).length;
    const before = { log: reads('/api/log'), history: reads('/api/history'), errors: reads('/api/errors'), cost: reads('/api/cost') };

    await page.firePoll();
    assert.deepEqual({ log: reads('/api/log'), history: reads('/api/history'), errors: reads('/api/errors'), cost: reads('/api/cost') },
        { log: before.log + 1, history: before.history, errors: before.errors + 1, cost: before.cost + 1 }, 'the log, the error log and the cost control with every poll; the history only for a new row');

    state.status = { ...SEVERAL, historyStamp: 'b' };
    state.history = () => ({ status: 500, body: { error: 'The usage history could not be read.' } });
    await page.firePoll();
    assert.equal(reads('/api/history'), before.history + 1, 'a row was added: the history is read again');
    assert.equal(page.el('panel-error-history').hidden, false, 'a read that failed says so in the history');
    assert.match(page.el('panel-error-history').textContent, /^The usage history could not be read: /);
    for (const other of ['log', 'errors', 'cost']) {
        assert.equal(page.el('panel-error-' + other).hidden, true, 'and not in ' + other);
    }
    assert.equal(page.el('panel-note-errors').textContent, 'There are no errors in this run.', 'the note of the error log is its own');
    assert.equal(page.el('panel-note-history').hidden, true);
});

test('the zoom is in the line of every list and changes the one size of all three', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    await page.click('log-button');
    await page.click('errors-button');

    await page.el('zoom-in-errors').listeners.click();
    await settle();
    assert.equal(global.document.documentElement.style.properties['--list-size'], '13px', 'one size for the page');
    await page.el('zoom-out-log').listeners.click();
    await page.el('zoom-out-log').listeners.click();
    await page.el('zoom-out-log').listeners.click();
    await settle();
    assert.equal(global.document.documentElement.style.properties['--list-size'], '10px');
    for (const name of ['log', 'history', 'errors']) {
        assert.ok('disabled' in page.el('zoom-out-' + name).attrs, 'at the smallest size the minus of ' + name + ' cannot be pressed');
        assert.ok(!('disabled' in page.el('zoom-in-' + name).attrs));
    }
});

test('with several views at once the settings are shown above the views that are open, and leaving them closes them alone', async () => {
    for (const leave of ['settings-cancel', 'settings-save', 'settings-button']) {
        const page = await load(backendOf({ config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: { ...SETTINGS.settings, severalViews: true } } }) }));
        await page.click('log-button');
        await page.click('cost-button');

        await page.click('settings-button');
        assert.deepEqual(shownViews(page), ['cost', 'log'], 'the views stay (since 0.81; in 0.80 the settings took their place)');
        assert.equal(page.el('settings-view').hidden, false);
        assert.deepEqual(blueIcons(page), ['cost', 'log'], 'and their icons stay blue');
        assert.equal(page.window.currentView(), 'settings+cost+log,general', 'the settings named first, with their tab');
        assert.match(page.window.contentSize(), /^\d+,\d+,2,settings\+cost\+log,\d+$/, 'the log among them: the width and the height may be dragged');

        await page.click(leave);
        assert.equal(page.el('settings-view').hidden, true, 'the settings are closed by ' + leave);
        assert.deepEqual(shownViews(page), ['cost', 'log'], 'and the views stay open after ' + leave);
        assert.deepEqual(blueIcons(page), ['cost', 'log']);
        assert.equal(page.el('panel-lines-log').children.length, 2);
        assert.equal(page.window.currentView(), 'cost+log,');
    }
});

test('leaving the settings gives back the views that were open, less those whose icons the saved settings switch off', async () => {
    const saved = { ...SETTINGS.settings, severalViews: true };
    const state = { config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: saved } }) };
    const page = await load(backendOf(state));
    await page.click('log-button');
    await page.click('history-button');
    await page.click('cost-button');
    await page.click('settings-button');
    await change(page, 'set-showHistoryIcon', false);

    state.status = withShow(WITH_CHANGE, { historyIcon: false, logIcon: true, errorIcon: true, costIcon: true, severalViews: true });
    await page.click('settings-save');

    assert.deepEqual(shownViews(page), ['cost', 'log'], 'the history, whose icon was unchecked, is not given back; the other two are');
});

test('with the settings beside the views, a press on the icon of a view opens or closes it and leaves the settings open, unsaved', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: { ...SETTINGS.settings, severalViews: true } } }) }));
    await page.click('cost-button');
    await page.click('settings-button');
    page.el('set-showDeltaUsed').checked = !page.el('set-showDeltaUsed').checked;
    const typed = page.el('set-showDeltaUsed').checked;

    await page.click('history-button');
    assert.equal(page.el('settings-view').hidden, false, 'the settings stay');
    assert.deepEqual(shownViews(page), ['cost', 'history'], 'and the history opens below them, with the cost control');
    assert.equal(settingsPosts(page).length, 0, 'nothing is saved');
    assert.equal(page.el('set-showDeltaUsed').checked, typed, 'and what was changed in the form is still there');

    await page.click('cost-button');
    assert.equal(page.el('settings-view').hidden, false);
    assert.deepEqual(shownViews(page), ['history'], 'the cost control closes, the settings stay');
    assert.match(page.window.contentSize(), /^\d+,\d+,1,settings\+history,420$/, 'the history is the lowest list, at the height it has alone');

    await page.click('settings-cancel');
    assert.equal(page.el('settings-view').hidden, true);
    assert.deepEqual(shownViews(page), ['history']);
    assert.equal(settingsPosts(page).length, 0, 'Cancel saves nothing');
});

test('with the settings beside the views, saving closes them alone, and a view whose icon was unchecked with them', async () => {
    const saved = { ...SETTINGS.settings, severalViews: true };
    const state = { config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: saved } }) };
    const page = await load(backendOf(state));
    await page.click('history-button');
    await page.click('settings-button');
    await page.click('log-button');
    await change(page, 'set-showLogIcon', false);

    state.status = withShow(WITH_CHANGE, { historyIcon: true, logIcon: false, errorIcon: true, costIcon: true, severalViews: true });
    await page.click('settings-save');

    assert.equal(page.el('settings-view').hidden, true);
    assert.deepEqual(shownViews(page), ['history'], 'the log, whose icon was unchecked, is closed; the history stays');
});

test('with several views at once off the settings still take the place of the view that is shown, and give it back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false }) }));
    await page.click('cost-button');
    await page.click('settings-button');
    assert.deepEqual(shownViews(page), [], 'as before');
    assert.equal(page.window.currentView(), 'settings,general');
    await page.click('settings-cancel');
    assert.deepEqual(shownViews(page), ['cost']);
});

test('the settings and the views beside them are restored together, on their tab; with several views at once off, the settings alone', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: { ...SETTINGS.settings, severalViews: true } } }) }));
    assert.equal(page.window.restoreView('settings,cost,history', 'main'), true);
    await settle();
    assert.equal(page.el('settings-view').hidden, false);
    assert.deepEqual(shownViews(page), ['cost', 'history']);
    assert.equal(page.window.currentView(), 'settings+cost+history,main');

    const one = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false }) }));
    assert.equal(one.window.restoreView('settings,cost,history', 'main'), true);
    await settle();
    assert.equal(one.el('settings-view').hidden, false);
    assert.deepEqual(shownViews(one), [], 'the settings alone');
    assert.equal(one.window.currentView(), 'settings,main');
});

test('several views at once switched off in the settings: the first of the views that were open is given back, and no other', async () => {
    const off = { ...SETTINGS.settings, severalViews: true };
    const state = { config: CONFIG, status: SEVERAL, settings: () => ({ status: 200, body: { ...SETTINGS, settings: off } }) };
    const page = await load(backendOf(state));
    await page.click('history-button');
    await page.click('cost-button');
    await page.click('settings-button');
    page.el('set-severalViews').checked = false;
    state.postSettings = body => ({ status: 200, body: { ...SETTINGS, settings: { ...body, severalViews: false } } });
    state.status = withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false });
    page.el('set-showDeltaUsed').checked = !page.el('set-showDeltaUsed').checked;

    await page.click('settings-save');

    assert.deepEqual(shownViews(page), ['cost'], 'the first in the order of the icons');
});

test('several views at once switched off from elsewhere while several are shown: the first stays and the others are closed', async () => {
    const state = { config: CONFIG, status: SEVERAL };
    const page = await load(backendOf(state));
    await page.click('errors-button');
    await page.click('log-button');
    await page.click('cost-button');

    state.status = withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false });
    await page.firePoll();

    assert.deepEqual(shownViews(page), ['cost'], 'the first in the order of the icons, not the first that was opened');
    assert.deepEqual(blueIcons(page), ['cost']);
});

test('the size asked for with several views: as wide as the widest needs, as tall as all of them, and what may be dragged', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,420,1,history', 'one view: as ever');

    await page.click('cost-button');
    // The row is 42 tall; the history alone is ten rows, of which nine are its own; the cost control is as tall as it is, 42 here.
    // The cost control asks for the width of its bars, 948 here, which is more than the history needs.
    assert.equal(page.window.contentSize(), '948,462,1,cost+history,420', 'the height may be dragged, for the history; after the names, the height the history has alone');

    await page.click('log-button');
    assert.equal(page.window.contentSize(), '1200,840,2,cost+history+log,420', 'with the log the width may be dragged too, and the window is three rows wide');

    await page.click('history-button');
    await page.click('log-button');
    assert.equal(page.window.contentSize().split(',').slice(2).join(','), '3,cost', 'the cost control alone: nothing to drag, as before');
});

test('a list that has another view below it keeps the height it has alone, and the lowest list takes what the window has', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withHeights(SEVERAL, { history: 640, log: 500 }) }));
    await page.click('log-button');
    await page.click('history-button');
    await page.click('errors-button');

    assert.equal(page.el('view-history').className, 'view fixed');
    assert.equal(page.el('view-history').style.height, '598px', 'the height the history was last given, 640, less the row');
    assert.equal(page.el('view-log').className, 'view fixed');
    assert.equal(page.el('view-log').style.height, '458px');
    assert.equal(page.el('view-errors').className, 'view', 'the lowest list has no height of its own');
    assert.equal(page.el('view-errors').style.height, '');
    assert.equal(page.window.contentSize(), '1200,' + (42 + 598 + 458 + 378) + ',2,history+log+errors,420', 'the error log is ten rows alone: its height is not remembered');

    await page.click('errors-button');
    assert.equal(page.el('view-log').className, 'view', 'with the error log closed the log is the lowest');
    assert.equal(page.el('view-log').style.height, '');
    assert.equal(page.window.contentSize(), '1200,' + (42 + 598 + 458) + ',2,history+log,500', 'and the height it has alone is the one it was last given');

    await page.click('log-button');
    assert.equal(page.el('view-history').className, 'view');
    assert.equal(page.window.contentSize(), '400,420,1,history', 'one view asks for ten rows, and the host gives it the height it remembers');
});

test('the host is given back every view that was open, and opens them again; with several views at once off, the first', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    assert.equal(page.window.restoreView('history+cost', ''), true, 'a file of 0.80, in the old order');
    await settle();
    assert.deepEqual(shownViews(page), ['cost', 'history'], 'shown in the new order');
    assert.equal(page.window.currentView(), 'cost+history,');

    const one = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true }) }));
    one.window.restoreView('history,cost', '');
    await settle();
    assert.deepEqual(shownViews(one), ['cost'], 'the first in the order of the icons');
    assert.equal(one.window.restoreView('calendar+nothing', ''), true, 'names that are no views open nothing');
    assert.deepEqual(shownViews(one), ['cost']);
});

test('with a list among several views the window has its title bar, and with the cost control alone it has none', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(bareStatus(WITH_CHANGE), { ...ALL_ICONS, costIcon: true, severalViews: true }) }));
    await page.click('cost-button');
    assert.match(page.el('app').className, /\bbare\b/);
    await page.click('history-button');
    assert.doesNotMatch(page.el('app').className, /\bbare\b/, 'a list can be dragged, so the title bar is there');
    assert.match(page.el('app').className, /\bopen\b/, 'and the page fills the window');
    await page.click('history-button');
    assert.match(page.el('app').className, /\bbare\b/);
    assert.match(page.el('app').className, /\bfit\b/);
});

test('the checkbox for several views at once is in the General tab, in the group Window, off by default, and sent with the rest', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-severalViews').checked, false, 'off by default');

    page.el('set-severalViews').checked = true;
    await page.click('settings-save');

    assert.equal(JSON.parse(settingsPosts(page)[0].body).severalViews, true);
});

test('the views that were open at once are restored together, named with commas or with plus signs, once the status has said they may be', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL, history: () => ({ status: 200, body: HISTORY }) }));

    assert.equal(page.window.restoreView('history,cost', ''), true);
    await settle();
    assert.equal(page.window.currentView(), 'cost+history,', 'both, in the order of the icons');

    assert.equal(page.window.restoreView('log+cost+calendar', ''), true, 'a name that is not known is left out');
    await settle();
    assert.equal(page.window.currentView(), 'cost+log,');
});

test('with several views at once switched off, only the first of the views that were stored is restored', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...ALL_ICONS, costIcon: true, severalViews: false }),
        history: () => ({ status: 200, body: HISTORY }) }));

    assert.equal(page.window.restoreView('history,cost', ''), true);
    await settle();

    assert.equal(page.window.currentView(), 'cost,', 'the first in the order of the icons');
});

// ---- version 0.81: the folders of the log and of the history

const WITH_PROGRAM = () => ({ status: 200, body: { ...SETTINGS, programFolder: 'C:\\aip' } });

test('the group Files shows each folder, the program\'s in gray while it is the default, and Default can then not be pressed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS,
        settings: () => ({ status: 200, body: { ...SETTINGS, programFolder: 'C:\\aip', settings: { ...SETTINGS.settings, logFolder: 'D:\\logs' } } }) }));
    await page.click('settings-button');

    assert.equal(page.el('set-logFolder').textContent, 'D:\\logs');
    assert.equal(page.el('set-logFolder').className, 'folder-path');
    assert.ok(!('disabled' in page.el('default-logFolder').attrs));
    assert.equal(page.el('set-historyFolder').textContent, 'C:\\aip', 'the program\'s folder');
    assert.equal(page.el('set-historyFolder').className, 'folder-path default');
    assert.ok('disabled' in page.el('default-historyFolder').attrs, 'it is the default already');
});

test('Choose… asks for a folder, opened at the one shown, and puts what was chosen in the form; cancelled, nothing changes', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, settings: WITH_PROGRAM };
    const page = await load(backendOf(state));
    await page.click('settings-button');

    state.folder = () => ({ status: 200, body: { folder: null } });
    await page.click('choose-historyFolder');
    assert.equal(page.el('set-historyFolder').textContent, 'C:\\aip', 'cancelled');
    assert.deepEqual(JSON.parse(page.calls.filter(c => c.url === '/api/folder').pop().body), { start: 'C:\\aip' });

    state.folder = () => ({ status: 200, body: { folder: 'E:\\history' } });
    await page.click('choose-historyFolder');
    assert.equal(page.el('set-historyFolder').textContent, 'E:\\history');
    assert.equal(settingsPosts(page).length, 0, 'nothing is saved by choosing');

    await page.click('settings-save');
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.historyFolder, 'E:\\history', 'it is saved with the settings');
    assert.equal(sent.logFolder, '', 'and the other is the program\'s');
});

test('Default puts back the program\'s folder, which is sent as the empty text', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS,
        settings: () => ({ status: 200, body: { ...SETTINGS, programFolder: 'C:\\aip', settings: { ...SETTINGS.settings, logFolder: 'D:\\logs' } } }) }));
    await page.click('settings-button');

    await page.click('default-logFolder');
    assert.equal(page.el('set-logFolder').textContent, 'C:\\aip');

    await page.click('settings-save');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).logFolder, '');
});

test('a folder that cannot be written is said in red under the group Files, and the settings stay open', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, settings: WITH_PROGRAM,
        folder: () => ({ status: 200, body: { folder: 'Z:\\gone' } }),
        postSettings: () => ({ status: 400, body: { error: 'The folder cannot be written: the file or its folder is not there.' } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    await page.click('choose-logFolder');

    await page.click('settings-save');

    assert.equal(page.el('folder-error').textContent, 'The folder cannot be written: the file or its folder is not there.');
    assert.equal(page.el('folder-error').hidden, false);
    assert.equal(page.el('settings-error').hidden, true, 'not at the top of the view');
    assert.equal(page.el('settings-view').hidden, false, 'the settings stay');
});

test('a folder picker that cannot be shown is said in red under the group Files', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: WITH_PROGRAM,
        folder: () => ({ status: 500, body: { error: 'The folder could not be chosen: there is no window to choose a folder in' } }) }));
    await page.click('settings-button');
    await page.click('choose-logFolder');
    assert.equal(page.el('folder-error').textContent, 'The folder could not be chosen: there is no window to choose a folder in');
});

test('the cost control has its own currency symbol, a checkbox in its tab, off by default, sent with the settings', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    assert.equal(page.el('set-costCurrency').checked, false);
    page.el('set-costCurrency').checked = true;
    await page.click('settings-save');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).costCurrency, true);
});

// ---- version 0.82: views that do not fit on the screen

test('views that need more than the screen has share it: each list an equal part, the lowest the rest, and no height alone is said', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    for (const icon of ['cost-button', 'history-button', 'log-button', 'errors-button']) {
        await page.click(icon);
    }
    // The row is 42, the cost control 42, each list 378 alone: 1218 in all.
    assert.equal(page.window.contentSize(), '1200,1218,2,cost+history+log+errors,420', 'no room said yet: as before');

    assert.equal(page.window.screenRoom(800), true);
    assert.equal(page.el('view-history').style.height, '238px', 'what the row and the cost control leave of 800, in three');
    assert.equal(page.el('view-log').style.height, '238px');
    assert.equal(page.el('view-errors').style.height, '', 'the lowest takes what the window has');
    assert.equal(page.window.contentSize(), '1200,798,2,cost+history+log+errors', 'no more than the screen, and no height alone');

    page.window.screenRoom(300);
    assert.equal(page.el('view-history').style.height, '129px', 'never less than five lines, its header, note and controls');
    assert.equal(page.window.contentSize(), '1200,300,2,cost+history+log+errors', 'the window is the screen; the lowest list is cut');

    page.window.screenRoom(3000);
    assert.equal(page.el('view-history').style.height, '378px', 'room enough: each its height alone again');
    assert.equal(page.window.contentSize(), '1200,1218,2,cost+history+log+errors,420');
});

test('one view does not share: it asks for its ten rows, and the host keeps the window on the screen', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEVERAL }));
    await page.click('history-button');
    page.window.screenRoom(200);
    assert.equal(page.window.contentSize(), '400,420,1,history');
});

// ---- version 0.82: the limit counted and the working days

test('Limit counted: Monthly limit by default; Server limit disables the monthly field, and the daily limit is worked out from the server\'s', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS,
        settings: () => ({ status: 200, body: { ...SETTINGS, serverLimit: '1000.00', weekdays: 22 } }),
        costDaily: call => ({ status: 200, body: { daily: call.url.includes('monthly=1000.00') ? '45.45' : null, weekdays: 22 } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    assert.equal(page.el('set-costLimitSource-monthly').checked, true);
    assert.ok(!('disabled' in page.el('set-costMonthlyLimit').attrs));

    page.el('set-costLimitSource-server').checked = true;
    page.el('set-costLimitSource-monthly').checked = false;
    await page.el('set-costLimitSource-server').listeners.change.call(page.el('set-costLimitSource-server'));
    await settle();
    assert.equal(page.el('set-costMonthlyLimit').attrs.disabled, '', 'the monthly field is disabled');
    assert.ok(dailyAsked(page).some(url => url.includes('monthly=1000.00')), 'the daily limit is asked for the server\'s limit');

    await page.click('settings-save');
    assert.equal(JSON.parse(settingsPosts(page)[0].body).costLimitSource, 'server');
});

test('Working days: Automatic shows the weekdays of the month; unchecked, a number is typed and sent, and the daily limit follows it', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS,
        settings: () => ({ status: 200, body: { ...SETTINGS, weekdays: 22, settings: { ...SETTINGS.settings, costMonthlyLimit: '440.00' } } }),
        costDaily: call => ({ status: 200, body: { daily: call.url.includes('workdays=20') ? '22.00' : '20.00', weekdays: 22 } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    assert.equal(page.el('set-costWorkdaysAutomatic').checked, true);
    assert.equal(page.el('set-costWorkdays').value, '22', 'Monday to Friday of the month');
    assert.equal(page.el('set-costWorkdays').attrs.disabled, '');
    assert.equal(page.el('set-costWorkdayHours').value, '8');

    page.el('set-costWorkdaysAutomatic').checked = false;
    await page.el('set-costWorkdaysAutomatic').listeners.change.call(page.el('set-costWorkdaysAutomatic'));
    page.el('set-costWorkdays').value = '20';
    await page.el('set-costWorkdays').listeners.change.call(page.el('set-costWorkdays'));
    await settle();
    assert.ok(dailyAsked(page).some(url => url.endsWith('workdays=20')), 'asked with the days typed');

    page.el('set-costWorkdayHours').value = '6';
    await page.click('settings-save');
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.costWorkdays, 20);
    assert.equal(sent.costWorkdayHours, 6);
});

// ---- version 0.82: the row, the status circle and the figures of the day

const SPENDING = {
    state: 'red', tooltip: 'Today: over the daily limit: 7.20 of 6.81\nMonth: within the limit and on pace',
    day: { state: 'red', used: '7.20', limit: '6.81', percent: '106%' },
    month: { state: 'green', used: '186.02', limit: '1,000.00', percent: '19%' },
};
const withSpending = (status, spending) => ({ ...status, spending });

test('the status circle is first in the row, shown by default, in the colour of the state, with the tooltip the backend wrote', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withSpending(SPEND_STATUS, SPENDING) }));
    assert.equal(page.el('status-dot').hidden, false, 'shown when the backend says nothing of it');
    assert.equal(page.el('status-dot').className, 'status-dot red');
    assert.equal(page.el('status-dot').title, SPENDING.tooltip);
    assert.ok(INDEX_HTML.indexOf('id="status-dot"') < INDEX_HTML.indexOf('id="time"'), 'before the time');

    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    assert.equal(none.el('status-dot').className, 'status-dot gray', 'nothing worked out: gray');
    const off = await load(backendOf({ config: CONFIG, status: withShow(withSpending(SPEND_STATUS, SPENDING), { status: false }) }));
    assert.equal(off.el('status-dot').hidden, true, 'switched off');
});

test('the figures of the day follow those of the month when switched on, red over the daily limit', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(withSpending(SPEND_STATUS, SPENDING), { dayPercentage: true, dayUsed: true, dayLimit: true }) }));
    assert.equal(page.el('day').hidden, false);
    assert.equal(page.el('day-percent').textContent, '106%');
    assert.equal(page.el('day-used').textContent, '7.20');
    assert.equal(page.el('day-limit').textContent, '6.81');
    assert.equal(page.el('day-of').hidden, false);
    assert.match(page.el('day-used').className, /\bover\b/, 'red over the daily limit');
    assert.match(page.el('day-percent').className, /\bover\b/);
    assert.doesNotMatch(page.el('used').className, /\bover\b/, 'the month is within its limit: green');

    const hidden = await load(backendOf({ config: CONFIG, status: withSpending(SPEND_STATUS, SPENDING) }));
    assert.equal(hidden.el('day').hidden, true, 'off by default');
});

test('over the monthly limit the amount used and its percentage are red; with no daily limit the day\'s figures are gray boxes that say so', async () => {
    const over = { ...SPENDING, day: { state: 'gray', used: '1.00', limit: null, percent: null }, month: { state: 'red', used: '186.02', limit: '150.00', percent: '124%' } };
    const page = await load(backendOf({ config: CONFIG, status: withShow(withSpending(SPEND_STATUS, over), { percentage: true, dayPercentage: true, dayUsed: true, dayLimit: true }) }));
    assert.match(page.el('used').className, /\bover\b/);
    assert.match(page.el('percent').className, /\bover\b/);
    assert.match(page.el('day-limit').className, /\bskeleton\b/);
    assert.equal(page.el('day-limit').title, 'No daily limit is set');
    assert.match(page.el('day-percent').className, /\bskeleton\b/);
    assert.equal(page.el('day-used').textContent, '1.00', 'what was spent is still said');
});

test('the four new checkboxes of Main view preview the row and are sent with the settings', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withSpending(SPEND_STATUS, SPENDING) }));
    await page.click('settings-button');
    assert.equal(page.el('set-showStatus').checked, true);
    assert.equal(page.el('set-showDayUsed').checked, false);
    await change(page, 'set-showDayUsed', true);
    assert.equal(page.el('day').hidden, false, 'the row shows it at once');
    await page.click('settings-save');
    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.showDayUsed, true);
    assert.equal(sent.showStatus, true);
});

test('a bar is amber when the backend says the limit is about to be passed, red over it, and green otherwise', async () => {
    const cost = { rows: [
        { label: 'Today', text: '6.00 of 10.00 · 60%', tooltip: 'Today: 6.00 of 10.00 (60%)\nToday: on course to pass the daily limit', fraction: 0.6, over: false, noLimit: false, state: 'amber' },
        { label: 'Month', text: '90.00 of 220.00 · 41%', tooltip: 'Month: 90.00 of 220.00 (41%)', fraction: 0.41, over: false, noLimit: false, state: 'green' },
    ], note: null };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, cost: () => ({ status: 200, body: cost }) }));
    await page.click('cost-button');
    const [day, month] = rowsOf(page);
    assert.match(day.bar.className, /\bamber\b/);
    assert.doesNotMatch(month.bar.className, /\bamber\b|\bover\b/);
    assert.match(day.tooltip, /on course to pass/);
});
