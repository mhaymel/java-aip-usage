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
// A reading of an account that reports no usage: no spend. (A response with plan windows never becomes a
// reading: the backend refuses the seat-based format, so the page is never sent one.)
const NO_USAGE_STATUS = {
    refreshing: false, stale: false, nextRefreshInSeconds: 17, error: null,
    usage: { source: 'anthropic-oauth-usage', fetched_at: NOW, spend: null, windows: [] },
};

const scrollbarOf = { width: 0 };

function element(id) {
    const el = {
        id, hidden: HIDDEN_AT_START.has(id), textContent: '', className: '', value: '', title: '', children: [], attrs: {}, listeners: {},
        focused: false, style: {}, scrollTop: 0, scrollHeight: 0, rect: id === 'table-probe' ? { width: 0, height: 0 } : { width: 399.2, height: 41.5 },
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
        if (call.url === '/api/status') return { status: 200, body: withDisplay(state.status) };
        if (call.url === '/api/refresh') return { status: 202, body: { started: true } };
        if (call.url === '/api/log') return state.log ? state.log() : { status: 200, body: LOG };
        if (call.url === '/api/errors') return state.errors ? state.errors() : { status: 200, body: ERRORS };
        if (call.url === '/api/history') return state.history ? state.history() : { status: 200, body: HISTORY };
        return { status: 404, body: { error: 'No such endpoint.' } };
    };
}

const DEFAULT_SETTINGS = {
    usageIntervalSeconds: 60, logResponse: false, showPercentage: false, showCurrency: false, showHistoryIcon: true, showLogIcon: true, showErrorIcon: true,
    showInterval: false, showDeltaUsed: false, showDeltaTime: false,
    timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false, historyDate: false, historyZeroLines: true, historyFailedLines: true,
    seatShowResets: true, seatShowDelta: false, seatHistoryResets: true, seatHistoryDelta: false,
};
const SETTINGS = {
    settings: { ...DEFAULT_SETTINGS, usageIntervalSeconds: 120, showInterval: true, timeFormat: 'hh:mm:ss' },
    defaults: DEFAULT_SETTINGS,
    limits: { usageIntervalSeconds: { min: 5, max: 3600 } },
};

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
    file: 'java-aip-usage.log', exists: true, truncated: false,
    lines: ['2026-10-08 19:00:00 INFO    [UsageApp] Starting', '2026-10-08 19:00:01 INFO    [UsageService] Usage refresh succeeded'],
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

test('a spend reading shows the time, spent and budget, and percent, with severity as colour', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('time').hidden, false);
    assert.equal(page.el('time').textContent, '14:24:53');
    assert.equal(page.el('spend').hidden, false);
    assert.equal(page.el('used').textContent, '186.02');
    assert.equal(page.el('limit').textContent, '1,000.00');
    assert.equal(page.el('percent').textContent, '19%');
    assert.equal(page.el('spend').className, 'spend sev-normal');
    assert.equal(page.el('placeholder').hidden, true);
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
    assert.equal(page.el('countdown').textContent, '42 s');
    assert.equal(page.el('countdown').title, 'Seconds until the next refresh (negative when overdue)');
});

test('the countdown follows the backend at each update, and does no counting of its own', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 41 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '41 s');

    // Time passes on the page, but nothing arrives: the number stays what it was told.
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '41 s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 40 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '40 s');
});

test('an overdue refresh is shown as a negative number', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, nextRefreshInSeconds: -3 } };
    const page = await load(backendOf(state));

    assert.equal(page.el('countdown').textContent, '-3 s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: -4 };
    await page.firePoll();
    assert.equal(page.el('countdown').textContent, '-4 s');
});

test('the countdown restarts when the backend says so, for instance after a manual refresh', async () => {
    const state = { config: CONFIG, status: { ...SPEND_STATUS, nextRefreshInSeconds: 3 } };
    const page = await load(backendOf(state));
    assert.equal(page.el('countdown').textContent, '3 s');

    state.status = { ...SPEND_STATUS, nextRefreshInSeconds: 60 };
    await page.click('refresh');

    assert.equal(page.el('countdown').textContent, '60 s');
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

test('a reading with no usage shows the countdown too, and no amounts', async () => {
    const page = await load(backendOf({ config: CONFIG, status: NO_USAGE_STATUS }));

    assert.equal(page.el('countdown').textContent, '17 s');
    assert.equal(page.el('spend').hidden, true);
});

test('the page has nothing to draw a plan window with, and ignores one a status might carry', async () => {
    assert.ok(!INDEX_IDS.includes('windows'), 'no element for plan windows');
    const status = withDisplay(SPEND_STATUS);
    status.display.windows = [{ name: 'five_hour', utilizationText: '12.3%', resetsText: 'in 2 h 5 min' }];
    // Loading would throw on an id the page does not have, so getting here means nothing looked for one.
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('used').textContent, '186.02');
    assert.equal(page.el('placeholder').hidden, true);
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

    assert.equal(page.el('placeholder').hidden, false);
    assert.equal(page.el('placeholder').textContent, 'No data');
    assert.equal(page.el('note').textContent, 'Refresh failed at 14:17:11: Claude Code could not be found on the PATH.');
    assert.equal(page.el('note').className, 'note note-error');
    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('time').hidden, true);
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
        seatShowResets: true, seatShowDelta: false, seatHistoryResets: true, seatHistoryDelta: false,
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

test('Maximum view turns every main-view item on and Minimum view off, and nothing else changes', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-logResponse').checked = true;
    page.el('set-historyDeltaUsed').checked = true;

    await page.click('settings-maximum');
    assert.equal(page.el('set-showPercentage').checked, true);
    assert.equal(page.el('set-showInterval').checked, true);
    assert.equal(page.el('set-showDeltaUsed').checked, true);
    assert.equal(page.el('set-showDeltaTime').checked, true);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm:ss');

    await page.click('settings-minimum');
    assert.equal(page.el('set-showPercentage').checked, false, 'the minimum view has no percentage');
    assert.equal(page.el('set-showInterval').checked, false);
    assert.equal(page.el('set-showDeltaUsed').checked, false);
    assert.equal(page.el('set-showDeltaTime').checked, false);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm');

    assert.equal(page.el('set-logResponse').checked, true, 'the log setting is left alone');
    assert.equal(page.el('set-historyDeltaUsed').checked, true, 'so are the history columns');
    assert.equal(page.el('set-usageIntervalSeconds').value, '120', 'and the interval');
    assert.equal(settingsPosts(page).length, 0, 'neither applies by itself');
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

    assert.equal(page.window.contentSize(), '400,613,3', 'the whole page, rounded up; flag 3: fitted and not resizable');
    assert.match(page.el('app').className, /\bfit\b/);
    assert.doesNotMatch(page.el('app').className, /\bopen\b/, 'it does not fill the window, or its height would be the window\'s');
});

test('the settings window follows the page when a message line appears, grows or goes', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    page.el('top').rect = { width: 399.2, height: 41.5 };
    page.el('app').rect = { width: 399.2, height: 600 };
    await page.click('settings-button');
    assert.equal(page.window.contentSize(), '400,600,3');

    // A failed refresh puts a message line in the row: the page is taller by it.
    page.el('app').rect = { width: 399.2, height: 636 };
    assert.equal(page.window.contentSize(), '400,636,3');

    page.el('app').rect = { width: 399.2, height: 600 };
    assert.equal(page.window.contentSize(), '400,600,3', 'and shorter again when it goes');
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
    assert.equal(page.window.contentSize(), '400,500,3', 'the settings do not scroll, so they take no room for one');
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
        spend: { percentText: '19%', percentTooltip: 'p', used: '186.02', limit: '1,000.00', usedTooltip: 'u', limitTooltip: 'l', severityText: 'normal', severityKind: 'normal' },
        countdown: { text: '42 s', tooltip: 'c' },
        interval: { text: '60 s', tooltip: 'Time between usage requests' },
        deltaUsed: { text: '+0.05', tooltip: 'Change in the amount used since the previous reading, in USD' },
        deltaTime: { text: '1 m', tooltip: 'Time since the previous reading' },
        show: { interval: false, deltaUsed: true, deltaTime: false },
    },
};

test('the countdown is always in the row; the interval and the two changes only when their settings are on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WITH_CHANGE }));

    assert.equal(page.el('countdown').hidden, false, 'not optional');
    assert.equal(page.el('countdown').textContent, '42 s');
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

    assert.equal(page.el('interval').textContent, '60 s');
    assert.equal(page.el('interval').title, 'Time between usage requests');
    assert.equal(page.el('delta-time').textContent, '1 m');
    assert.equal(page.el('delta-time').title, 'Time since the previous reading');
});

test('an item the backend could not work out is left out even though its setting is on', async () => {
    const status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, deltaUsed: null, show: { interval: true, deltaUsed: true, deltaTime: true } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('delta-used').textContent, '', 'it has nothing to say');
    assert.match(page.el('delta-used').className, /\bempty\b/, 'and is empty and invisible');
    assert.equal(page.el('delta-time').hidden, false);
    assert.doesNotMatch(page.el('delta-time').className, /\bempty\b/);
});

test('an optional item whose setting is on keeps its room when it has no value, so the row does not change width; with the setting off it takes none', async () => {
    const withValue = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { interval: true, deltaUsed: true, deltaTime: true } } };
    const state = { config: CONFIG, status: withValue };
    const page = await load(backendOf(state));
    assert.equal(page.el('delta-used').hidden, false);

    state.status = { ...withValue, display: { ...withValue.display, deltaUsed: null, deltaTime: null } };
    await page.firePoll();
    for (const id of ['delta-used', 'delta-time']) {
        assert.equal(page.el(id).hidden, false, id + ' is still in the row');
        assert.match(page.el(id).className, /\bempty\b/, id);
        assert.equal(page.el(id).title, '');
    }

    state.status = { ...withValue, display: { ...withValue.display, deltaUsed: null, deltaTime: null, show: { interval: true, deltaUsed: false, deltaTime: false } } };
    await page.firePoll();
    assert.equal(page.el('delta-used').hidden, true, 'switched off: no room');
    assert.equal(page.el('delta-time').hidden, true);
});

test('the item comes back with its text and loses the empty class when the value returns', async () => {
    const withValue = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { interval: false, deltaUsed: true, deltaTime: false } } };
    const state = { config: CONFIG, status: { ...withValue, display: { ...withValue.display, deltaUsed: null } } };
    const page = await load(backendOf(state));
    assert.match(page.el('delta-used').className, /empty/);

    state.status = withValue;
    await page.firePoll();

    assert.equal(page.el('delta-used').textContent, '+0.05');
    assert.doesNotMatch(page.el('delta-used').className, /empty/);
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

test('the percentage is in the row only when its setting is on, and the amounts keep their colour either way', async () => {
    const hidden = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { percentage: false, interval: false, deltaUsed: false, deltaTime: false } } };
    const state = { config: CONFIG, status: hidden };
    const page = await load(backendOf(state));

    assert.equal(page.el('percent').hidden, true);
    assert.equal(page.el('spend').hidden, false, 'the amounts are still there');
    assert.match(page.el('spend').className, /sev-normal/, 'and still coloured');

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
    assert.match(page.window.contentSize(), /,3$/, 'the settings have no remembered height');
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
        spend: { percentText: '19%', percentTooltip: 'p', used: '186.02', limit: '1,000.00', usedTooltip: 'u', limitTooltip: 'l', severityText: 'normal', severityKind: 'normal' },
        countdown: { text: '118 s', tooltip: 'c' },
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
    assert.equal(page.window.contentSize(), '400,500,3');

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

test('Maximum view and Minimum view leave the two history lines alone', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-historyZeroLines').checked = false;

    await page.click('settings-maximum');
    await page.click('settings-minimum');

    assert.equal(page.el('set-historyZeroLines').checked, false);
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

test('Maximum view turns the symbol and the three icons on, Minimum view turns them off, and neither touches the history or the log', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-historyDate').checked = true;
    page.el('set-logResponse').checked = true;

    await page.click('settings-minimum');
    for (const id of ['set-showCurrency', 'set-showHistoryIcon', 'set-showLogIcon', 'set-showErrorIcon', 'set-showPercentage', 'set-showInterval']) {
        assert.equal(page.el(id).checked, false, 'minimum: ' + id);
    }
    await page.click('settings-maximum');
    for (const id of ['set-showCurrency', 'set-showHistoryIcon', 'set-showLogIcon', 'set-showErrorIcon', 'set-showPercentage', 'set-showInterval']) {
        assert.equal(page.el(id).checked, true, 'maximum: ' + id);
    }
    assert.equal(page.el('set-historyDate').checked, true, 'the history is left alone');
    assert.equal(page.el('set-logResponse').checked, true, 'so is the log setting');
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

test('with all three switched off only the gear is there; opening the settings shows all four', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    assert.deepEqual(visible(page), ['settings-button']);

    await page.click('settings-button');

    assert.deepEqual(visible(page), ['log-button', 'history-button', 'errors-button', 'settings-button']);
});

test('the extra buttons are shown at the click, not at the next poll', async () => {
    const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, NO_ICONS) }));
    const polls = page.calls.filter(c => c.url === '/api/status').length;

    await page.click('settings-button');

    assert.equal(page.calls.filter(c => c.url === '/api/status').length, polls, 'no poll was needed');
    assert.equal(page.el('log-button').hidden, false);
});

test('Cancel, Apply and the gear again take the extra buttons away, and leave the configured ones', async () => {
    for (const leave of ['settings-cancel', 'settings-apply', 'settings-button']) {
        const page = await load(backendOf({ config: CONFIG, status: withShow(WITH_CHANGE, { ...NO_ICONS, logIcon: true }) }));
        await page.click('settings-button');
        assert.equal(visible(page).length, 4, leave);

        await page.click(leave);

        assert.deepEqual(visible(page), ['log-button', 'settings-button'], leave + ': only the one that is switched on, and the gear');
    }
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
    status.display = { ...status.display, deltaTime: { text: '105 s', tooltip: 'Time since the previous reading' } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('delta-time').textContent, '105 s');
});

// ---- the seat-based format: the row, the history table, the settings tabs

const SEAT_DISPLAY = {
    time: '14:24', timeTooltip: 'Last update: 8 Oct 2026, 14:24:53', format: 'seat-based', spend: null,
    seat: {
        fiveHour: { label: '5h', text: '12.3%', tooltip: 'Five-hour session limit: 12.34% used. Resets 8 Oct 2026, 18:00:00', resetsText: 'in 2 h 5 min' },
        sevenDay: { label: '7d', text: '80%', tooltip: 'Weekly limit: 80% used', resetsText: null },
    },
    placeholder: null, countdown: { text: '42 s', tooltip: 'Seconds until the next refresh (negative when overdue)' }, countdownAlert: null,
    interval: { text: '60 s', tooltip: 'Time between usage requests' },
    deltaUsed: { text: '+0.6 / 0.0', tooltip: 'Change of the session limit and of the weekly limit since the previous reading, in percentage points' },
    deltaTime: null, message: null,
    show: { percentage: false, currency: false, interval: false, deltaUsed: true, deltaTime: false, historyIcon: true, logIcon: true, errorIcon: true },
};
const SEAT_STATUS = { refreshing: false, stale: false, display: SEAT_DISPLAY, historyStamp: 's' };

test('a seat-based reading shows the two limits, each with its label, its percentage and the time until it is set back', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SEAT_STATUS }));

    assert.equal(page.el('seat').hidden, false);
    assert.equal(page.el('spend').hidden, true, 'the figures of the other format are not there');
    assert.equal(page.el('five-hour-label').textContent, '5h');
    assert.equal(page.el('five-hour').textContent, '12.3%');
    assert.equal(page.el('five-hour').title, 'Five-hour session limit: 12.34% used. Resets 8 Oct 2026, 18:00:00');
    assert.equal(page.el('five-hour-resets').textContent, 'in 2 h 5 min');
    assert.equal(page.el('five-hour-resets').hidden, false);
    assert.equal(page.el('seven-day-label').textContent, '7d');
    assert.equal(page.el('seven-day').textContent, '80%');
    assert.equal(page.el('seven-day-resets').hidden, true, 'no reset text sent: nothing there, and no room taken');
    assert.equal(page.el('delta-used').textContent, '+0.6 / 0.0');
    assert.equal(page.el('placeholder').hidden, true);
});

test('a change of format changes the row at the next reading, with nothing to press', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    assert.equal(page.el('spend').hidden, false);
    assert.equal(page.el('seat').hidden, true);

    state.status = SEAT_STATUS;
    await page.firePoll();

    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('seat').hidden, false);

    state.status = SPEND_STATUS;
    await page.firePoll();

    assert.equal(page.el('spend').hidden, false);
    assert.equal(page.el('seat').hidden, true);
});

test('the limits are dimmed with the rest when the figures are old', async () => {
    const page = await load(backendOf({ config: CONFIG, status: { ...SEAT_STATUS, stale: true } }));

    assert.match(page.el('app').className, /stale/);
    assert.equal(page.el('seat').hidden, false);
});

const SEAT_HISTORY = {
    file: 'java-aip-usage.csv', exists: true, format: 'seat-based',
    columns: ['time', '5h %', '5h resets', '7d %', '7d resets', '\u0394 time'],
    kinds: ['time', 'percent', 'clock', 'percent', 'day', 'seconds'],
    wide: false, note: null, noteHighlight: false, total: 2,
    lines: [
        line(['16:25:53', '12.90', '18:00', '80.10', '10-10 02:00', '60 s']),
        line(['16:24:53', 'failed', '', '', '', ''], { failed: true }),
    ],
};

test('the seat-based history is drawn as the backend sent it, with the column widths its kinds give', async () => {
    const state = { config: CONFIG, status: SEAT_STATUS, history: () => ({ status: 200, body: SEAT_HISTORY }) };
    const page = await load(backendOf(state));

    await page.click('history-button');

    const rows = page.el('panel-lines').children;
    assert.equal(rows.length, 3, 'the header and the two lines');
    assert.deepEqual(rows[0].children.map(c => c.textContent), ['time', '5h %', '5h resets', '7d %', '7d resets', '\u0394 time']);
    assert.deepEqual(rows[1].children.map(c => c.textContent), ['16:25:53', '12.90', '18:00', '80.10', '10-10 02:00', '60 s']);
    assert.match(rows[1].className, /\bseat-table\b/);
    assert.equal(rows[1].style.gridTemplateColumns, 'minmax(8ch, 1fr) 7ch 9ch 7ch 12ch 8ch');
    assert.equal(rows[0].style.gridTemplateColumns, rows[1].style.gridTemplateColumns, 'the header has the same columns');
    assert.equal(rows[2].children[1].className, 'failed', 'the failed word is red, in the second cell as always');
});

test('the usage-based history keeps the column widths of the style sheet: no widths are set on its lines', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('history-button');

    const row = page.el('panel-lines').children[1];
    assert.doesNotMatch(row.className, /seat-table/);
    assert.equal(row.style.gridTemplateColumns, undefined);
});

test('the settings open on the tab of the format in force, which is marked, and the other tab can be shown', async () => {
    const state = { config: CONFIG, status: SEAT_STATUS, settings: () => ({ status: 200, body: { ...SETTINGS, format: 'seat-based' } }) };
    const page = await load(backendOf(state));

    await page.click('settings-button');

    assert.equal(page.el('tab-seat-panel').hidden, false);
    assert.equal(page.el('tab-usage-panel').hidden, true);
    assert.equal(page.el('tab-seat').attrs['aria-selected'], 'true');
    assert.match(page.el('tab-seat').className, /selected/);
    assert.match(page.el('tab-seat').className, /in-force/);
    assert.equal(page.el('tab-seat').title, 'The format of the current readings');
    assert.doesNotMatch(page.el('tab-usage').className, /in-force|selected/);

    await page.click('tab-usage');

    assert.equal(page.el('tab-usage-panel').hidden, false);
    assert.equal(page.el('tab-seat-panel').hidden, true);
    assert.match(page.el('tab-usage').className, /selected/);
    assert.match(page.el('tab-seat').className, /in-force/, 'the mark stays with the format in force, whichever tab is shown');
    assert.doesNotMatch(page.el('tab-seat').className, /selected/);
});

test('with no format said, the settings open on the usage-based tab', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');

    assert.equal(page.el('tab-usage-panel').hidden, false);
    assert.equal(page.el('tab-seat-panel').hidden, true);
    assert.match(page.el('tab-usage').className, /in-force/);
});

test('Apply sends the switches of both tabs, whichever is shown', async () => {
    const state = { config: CONFIG, status: SEAT_STATUS, settings: () => ({ status: 200, body: { ...SETTINGS, format: 'seat-based' } }) };
    const page = await load(backendOf(state));
    await page.click('settings-button');
    page.el('set-seatShowDelta').checked = true;
    page.el('set-seatHistoryResets').checked = false;
    await page.click('tab-usage');
    page.el('set-showCurrency').checked = true;

    await page.click('settings-apply');

    const sent = JSON.parse(settingsPosts(page)[0].body);
    assert.equal(sent.seatShowDelta, true);
    assert.equal(sent.seatHistoryResets, false);
    assert.equal(sent.seatShowResets, true, 'what was not touched keeps what it had');
    assert.equal(sent.showCurrency, true);
    assert.equal(Object.keys(sent).length, 20, 'every setting, in one request');
});

test('Maximum view and Minimum view switch the main-view items of both tabs', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    await page.click('settings-maximum');
    assert.equal(page.el('set-seatShowResets').checked, true);
    assert.equal(page.el('set-seatShowDelta').checked, true);
    assert.equal(page.el('set-showPercentage').checked, true);

    await page.click('settings-minimum');
    assert.equal(page.el('set-seatShowResets').checked, false);
    assert.equal(page.el('set-seatShowDelta').checked, false);
    assert.equal(page.el('set-seatHistoryResets').checked, true, 'the history is left as it is');
});
