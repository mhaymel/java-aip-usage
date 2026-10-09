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
const WINDOWS_STATUS = {
    refreshing: false, stale: false, nextRefreshInSeconds: 17, error: null,
    usage: {
        source: 'anthropic-oauth-usage', fetched_at: NOW, spend: null,
        windows: [
            { window: 'five_hour', utilization: 12.34, resets_at: null },
            { window: 'seven_day', utilization: 80, resets_at: '2099-01-01T00:00:00Z' },
        ],
    },
};

function element(id) {
    const el = {
        id, hidden: HIDDEN_AT_START.has(id), textContent: '', className: '', value: '', title: '', children: [], attrs: {}, listeners: {},
        focused: false, scrollTop: 0, scrollHeight: 0, rect: { width: 399.2, height: 41.5 },
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
    return el;
}

/** Builds a fresh page and backend, loads app.js into them, and returns handles to both. */
async function load(backend) {
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
        if (call.url === '/api/history') return state.history ? state.history() : { status: 200, body: HISTORY };
        return { status: 404, body: { error: 'No such endpoint.' } };
    };
}

const DEFAULT_SETTINGS = {
    usageIntervalSeconds: 60, logResponse: false, showCountdown: false, showDeltaUsed: false, showDeltaTime: false,
    timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false,
};
const SETTINGS = {
    settings: { ...DEFAULT_SETTINGS, usageIntervalSeconds: 120, showCountdown: true, timeFormat: 'hh:mm:ss' },
    defaults: DEFAULT_SETTINGS,
    intervalChoices: [60, 120, 180, 240, 300],
    limits: { usageIntervalSeconds: { min: 5, max: 3600 } },
};

const HISTORY = {
    file: 'java-aip-usage.csv', exists: true, columns: ['datetime', 'used', 'limit', 'currency'], total: 2,
    rows: [['2026-10-08 20:46:11', '260.66', '1000.00', 'USD'], ['2026-10-08 20:44:12', '260.36', '1000.00', 'USD']],
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
    assert.equal(page.el('windows').hidden, true);
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

test('a plan reading shows the countdown too, and no amount tooltips because there are no amounts', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WINDOWS_STATUS }));

    assert.equal(page.el('countdown').textContent, '17 s');
    assert.equal(page.el('spend').hidden, true);
});

test('a plan reading shows each window in the row, in place of spent and budget', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WINDOWS_STATUS }));

    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('windows').hidden, false);
    const items = page.el('windows').children;
    // Each window reads: utilization first, then its name, then when it resets.
    assert.deepEqual(items.map(item => item.children.map(part => part.textContent)), [
        ['12.3%', 'five_hour', 'reset unknown'],
        ['80%', 'seven_day', items[1].children[2].textContent],
    ]);
    assert.match(items[1].children[2].textContent, /^in \d+ d/);
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
        usage: { ...WINDOWS_STATUS.usage, windows: [{ window: hostile, utilization: 1, resets_at: null }] },
    };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('note').textContent, 'Refresh failed at 14:24:53: ' + hostile);
    assert.equal(page.el('windows').children[0].children[1].textContent, hostile);
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
    assert.deepEqual(rows(page), [['2026-10-08 20:46:11', '260.66', '1000.00', 'USD'], ['2026-10-08 20:44:12', '260.36', '1000.00', 'USD']]);
    assert.deepEqual(head(page), [['datetime', 'used', 'limit', 'currency']], 'with a header row');
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

    state.status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, fetched_at: '2026-10-08T14:25:53Z' } };
    state.history = () => ({ status: 200, body: { ...HISTORY, total: 3, rows: [['2026-10-08 20:47:11', '260.90', '1000.00', 'USD'], ...HISTORY.rows] } });
    await page.firePoll();

    assert.equal(lines(page)[0], '2026-10-08 20:47:11 260.90 1000.00 USD');
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
    state.status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, fetched_at: '2026-10-08T14:25:53Z' } };

    await page.firePoll();
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 0);

    await page.click('history-button');
    assert.equal(page.calls.filter(c => c.url === '/api/history').length, 1);
});

test('a history that was cut says so in a note above the lines', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, total: 5000 } }) }));

    await page.click('history-button');

    assert.equal(page.el('panel-note').hidden, false);
    assert.equal(page.el('panel-note').textContent, 'Showing the newest 2 of 5000 rows.');
});

test('no history, or no rows, is one line of text and no list', async () => {
    const none = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, exists: false, total: 0, rows: [] } }) }));
    await none.click('history-button');
    assert.equal(none.el('panel-note').textContent, 'There is no usage history yet.');
    assert.equal(none.el('panel-lines').hidden, true);

    const empty = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, total: 0, rows: [] } }) }));
    await empty.click('history-button');
    assert.equal(empty.el('panel-note').textContent, 'The history has no rows yet.');
    assert.equal(empty.el('panel-lines').hidden, true);
});

test('an unreadable history shows a red error and keeps the lines it had; the error goes after a good read', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));
    await page.click('history-button');

    state.status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, fetched_at: '2026-10-08T14:25:53Z' } };
    state.history = () => ({ status: 500, body: { error: 'The usage history could not be read.' } });
    await page.firePoll();

    assert.equal(page.el('panel-error').hidden, false);
    assert.equal(page.el('panel-error').textContent, 'The usage history could not be read: The usage history could not be read.');
    assert.equal(lines(page).length, 2);

    state.status = { ...SPEND_STATUS, usage: { ...SPEND_STATUS.usage, fetched_at: '2026-10-08T14:26:53Z' } };
    state.history = undefined;
    await page.firePoll();
    assert.equal(page.el('panel-error').hidden, true);
});

test('history text is never handed to the HTML parser', async () => {
    const hostile = '<img src=x onerror=alert(1)>';
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, history: () => ({ status: 200, body: { ...HISTORY, rows: [[hostile, '1', '2', 'USD']] } }) }));

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

    assert.equal(page.window.contentSize(), '1200,340,2', 'the window takes the size the log has');
    assert.equal(lines(page).length, 2);
    assert.match(lines(page)[0], /Usage refresh succeeded/);
    assert.equal(page.el('history-button').title, 'Show the usage history');
    assert.equal(page.el('history-button').attrs['aria-expanded'], 'false');
    assert.equal(page.el('log-button').title, 'Hide the log');

    await page.click('history-button');
    assert.match(lines(page)[0], /^2026-10-08 20:46:11/);
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
    assert.equal(page.window.contentSize(), '400,340,1');
    assert.match(page.el('app').className, /\bopen\b/);

    // Dragging the window makes the page bigger; the report does not follow it.
    page.el('app').rect = { width: 900, height: 700 };
    assert.equal(page.window.contentSize(), '400,340,1');

    await page.click('history-button');
    page.el('app').rect = { width: 400, height: 34 };
    assert.equal(page.window.contentSize(), '400,34,0');
    assert.doesNotMatch(page.el('app').className, /\bopen\b/);
});

test('with the log shown the window is also three times as wide as the row, and may be resized in both directions', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    page.el('top').rect = { width: 400, height: 34 };

    await page.click('log-button');
    assert.equal(page.window.contentSize(), '1200,340,2');

    page.el('app').rect = { width: 1700, height: 900 };
    assert.equal(page.window.contentSize(), '1200,340,2', 'not the size it was dragged to');

    await page.click('history-button');
    assert.equal(page.window.contentSize(), '400,340,1', 'back to the row\'s width for the history');

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
const option = page => page.el('set-usageIntervalSeconds').children.map(o => o.value);

test('the settings button shows the view in the panel area, filled with what the backend has', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');

    assert.equal(page.el('panel').hidden, false);
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('panel-lines').hidden, true, 'not the lines of the log or the history');
    assert.equal(page.el('settings-button').title, 'Hide the settings');
    assert.equal(page.el('set-usageIntervalSeconds').value, '120');
    assert.equal(page.el('set-showCountdown').checked, true);
    assert.equal(page.el('set-showDeltaUsed').checked, false);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm:ss');
    assert.equal(page.el('set-logResponse').checked, false);
    assert.deepEqual(option(page), ['60', '120', '180', '240', '300']);
    assert.equal(page.el('settings-error').hidden, true);
});

test('the interval the backend has is offered even when it is not one of the five', async () => {
    const odd = { ...SETTINGS, settings: { ...SETTINGS.settings, usageIntervalSeconds: 45 } };
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, settings: () => ({ status: 200, body: odd }) }));

    await page.click('settings-button');

    assert.equal(page.el('set-usageIntervalSeconds').value, '45');
    assert.deepEqual(option(page), ['45', '60', '120', '180', '240', '300']);
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

test('Apply sends every setting together, and nothing else is sent', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-usageIntervalSeconds').value = '180';
    page.el('set-logResponse').checked = true;
    page.el('set-timeFormat').value = 'hh:mm';

    await page.click('settings-apply');

    assert.equal(settingsPosts(page).length, 1);
    assert.deepEqual(JSON.parse(settingsPosts(page)[0].body), {
        usageIntervalSeconds: 180, logResponse: true, showCountdown: true, showDeltaUsed: false, showDeltaTime: false,
        timeFormat: 'hh:mm', historyDeltaUsed: false, historyDeltaTime: false,
    });
    assert.equal(settingsPosts(page)[0].headers['Content-Type'], 'application/json');
    assert.equal(page.el('settings-view').hidden, false, 'Apply does not close the view');
    assert.equal(page.el('settings-error').hidden, true);
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

test('Close with nothing changed closes at once', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    await page.click('settings-close');

    assert.equal(page.el('panel').hidden, true);
    assert.equal(page.el('settings-confirm').hidden, true);
    assert.equal(settingsPosts(page).length, 0);
});

test('Close with unapplied changes asks first; Keep editing stays, Discard closes without sending', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-logResponse').checked = true;

    await page.click('settings-close');
    assert.equal(page.el('settings-confirm').hidden, false, 'it asks');
    assert.equal(page.el('panel').hidden, false, 'and the view stays');

    await page.click('settings-keep');
    assert.equal(page.el('settings-confirm').hidden, true);
    assert.equal(page.el('set-logResponse').checked, true, 'the edit is still there');

    await page.click('settings-close');
    await page.click('settings-discard');
    assert.equal(page.el('panel').hidden, true);
    assert.equal(settingsPosts(page).length, 0, 'nothing was sent');
});

test('after Apply there is nothing unapplied, so Close does not ask', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');
    page.el('set-logResponse').checked = true;
    await page.click('settings-apply');

    await page.click('settings-close');

    assert.equal(page.el('panel').hidden, true);
});

test('Restore defaults fills in the defaults and sends nothing until Apply', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    await page.click('settings-button');

    await page.click('settings-restore');

    assert.equal(page.el('set-usageIntervalSeconds').value, '60');
    assert.equal(page.el('set-showCountdown').checked, false);
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
    assert.equal(page.el('set-showCountdown').checked, true);
    assert.equal(page.el('set-showDeltaUsed').checked, true);
    assert.equal(page.el('set-showDeltaTime').checked, true);
    assert.equal(page.el('set-timeFormat').value, 'hh:mm:ss');

    await page.click('settings-minimum');
    assert.equal(page.el('set-showCountdown').checked, false);
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

test('the settings, the log and the history share the one panel area, and the settings are the size of the history', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    await page.click('settings-button');
    assert.equal(page.window.contentSize(), '400,420,1', 'ten times as tall as the row (400,42), the width of the row, height only');

    await page.click('log-button');
    assert.equal(page.el('settings-view').hidden, true, 'the log replaces the settings');
    assert.equal(page.el('settings-button').title, 'Show the settings');
    assert.equal(page.el('log-button').title, 'Hide the log');

    await page.click('settings-button');
    assert.equal(page.el('settings-view').hidden, false);
    assert.equal(page.el('log-button').title, 'Show the log');
    assert.equal(page.el('panel-lines').hidden, true);
});

// ---- the optional items of the row

const WITH_CHANGE = {
    ...SPEND_STATUS,
    display: {
        time: '14:24', timeTooltip: 'Last update: x', placeholder: null, windows: [], message: null,
        spend: { percentText: '19%', percentTooltip: 'p', used: '186.02', limit: '1,000.00', usedTooltip: 'u', limitTooltip: 'l', severityText: 'normal', severityKind: 'normal' },
        countdown: { text: '42 s', tooltip: 'c' },
        deltaUsed: { text: '+0.05', tooltip: 'Change in the amount used since the previous reading, in USD' },
        deltaTime: { text: '1 m', tooltip: 'Time since the previous reading' },
        show: { countdown: false, deltaUsed: true, deltaTime: false },
    },
};

test('the countdown and the two changes are in the row only when their settings are on', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WITH_CHANGE }));

    assert.equal(page.el('countdown').hidden, true, 'off');
    assert.equal(page.el('delta-used').hidden, false, 'on');
    assert.equal(page.el('delta-used').textContent, '+0.05');
    assert.equal(page.el('delta-used').title, 'Change in the amount used since the previous reading, in USD');
    assert.equal(page.el('delta-time').hidden, true, 'off');
});

test('turning the settings on shows the items at the next poll, as the backend says', async () => {
    const state = { config: CONFIG, status: WITH_CHANGE };
    const page = await load(backendOf(state));
    state.status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, show: { countdown: true, deltaUsed: true, deltaTime: true } } };

    await page.firePoll();

    assert.equal(page.el('countdown').textContent, '42 s');
    assert.equal(page.el('delta-time').textContent, '1 m');
    assert.equal(page.el('delta-time').title, 'Time since the previous reading');
});

test('an item the backend could not work out is left out even though its setting is on', async () => {
    const status = { ...WITH_CHANGE, display: { ...WITH_CHANGE.display, deltaUsed: null, show: { countdown: true, deltaUsed: true, deltaTime: true } } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('delta-used').hidden, true);
    assert.equal(page.el('delta-time').hidden, false);
});
