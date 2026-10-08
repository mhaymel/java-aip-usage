'use strict';

// Drives app.js with a fake DOM and a fake backend. The fake document only knows
// the ids that index.html really declares, so a misspelt id fails here rather
// than silently doing nothing in the window.
process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WEB = path.join(__dirname, '../../main/resources/web');
const INDEX_HTML = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
const INDEX_IDS = [...INDEX_HTML.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
// What index.html declares hidden, so the fake page starts the way the real one does.
const HIDDEN_AT_START = new Set([...INDEX_HTML.matchAll(/<[^>]*\sid="([^"]+)"[^>]*\shidden[\s>]/g)].map(m => m[1]));

const CONFIG = {
    usageIntervalSeconds: 30,
    pollIntervalSeconds: 1,
    limits: { usageIntervalSeconds: { min: 5, max: 3600 }, pollIntervalSeconds: { min: 1, max: 60 } },
};
const NOW = '2026-10-08T14:24:53Z';
const SPEND_STATUS = {
    refreshing: false, stale: false, error: null,
    usage: {
        source: 'anthropic-oauth-usage', fetched_at: NOW,
        spend: { used: 186.02, limit: 1000, currency: 'USD', percent: 19, severity: 'normal' }, windows: [],
    },
};
const WINDOWS_STATUS = {
    refreshing: false, stale: false, error: null,
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
        focused: false, rect: { width: 399.2, height: 41.5 },
        setAttribute(k, v) { this.attrs[k] = String(v); },
        removeAttribute(k) { delete this.attrs[k]; },
        addEventListener(type, fn) { this.listeners[type] = fn; },
        append(...nodes) { this.children.push(...nodes); },
        replaceChildren(...nodes) { this.children = [...nodes]; },
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
    global.window = { UsageView: require(path.join(WEB, 'view.js')) };
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
        el: id => elements.get(id), calls, timers, window: global.window,
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
        if (call.url === '/api/status') return { status: 200, body: state.status };
        if (call.url === '/api/refresh') return { status: 202, body: { started: true } };
        return { status: 404, body: { error: 'No such endpoint.' } };
    };
}

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
    assert.equal(page.el('amounts').textContent, '$186.02 / $1,000.00');
    assert.equal(page.el('percent').textContent, '19%');
    assert.equal(page.el('spend').className, 'spend sev-normal');
    assert.equal(page.el('spend').title, 'Severity: normal');
    assert.equal(page.el('windows').hidden, true);
    assert.equal(page.el('placeholder').hidden, true);
    assert.equal(page.el('note').hidden, true);
    assert.equal(page.el('app').className, '');
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
    assert.equal(page.el('amounts').textContent, '$186.02 / $1,000.00');
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
    assert.equal(page.el('amounts').textContent, '$186.02 / $1,000.00');
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

test('the config fields are hidden until the config button is pressed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('config').hidden, true);
    assert.equal(page.el('config-toggle').attrs['aria-expanded'], undefined);
});

test('pressing the config button shows both fields with the current values', async () => {
    const page = await load(backendOf({ config: { ...CONFIG, pollIntervalSeconds: 5 }, status: SPEND_STATUS }));

    await page.click('config-toggle');

    assert.equal(page.el('config').hidden, false);
    assert.equal(page.el('usage-interval').value, '30');
    assert.equal(page.el('poll-interval').value, '5');
    assert.equal(page.el('config-toggle').attrs['aria-expanded'], 'true');
    assert.equal(page.el('usage-interval').focused, true, 'the first field is ready to type in');
});

test('confirming with Enter sends both values at once, then the fields disappear', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postConfig: accepting };
    const page = await load(backendOf(state));
    await page.click('config-toggle');

    page.type('usage-interval', '60');
    page.type('poll-interval', '5');
    const prevented = await page.press('poll-interval', 'Enter');

    assert.equal(prevented, true);
    assert.equal(page.posts().length, 1, 'one request for both values');
    assert.equal(page.posts()[0].headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(page.posts()[0].body), { usageIntervalSeconds: 60, pollIntervalSeconds: 5 });
    assert.equal(page.el('config').hidden, true);
    assert.equal(page.el('config-toggle').attrs['aria-expanded'], 'false');
});

test('Enter works in either field, and the confirm button does the same', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postConfig: accepting };
    const first = await load(backendOf(state));
    await first.click('config-toggle');
    first.type('usage-interval', '45');
    await first.press('usage-interval', 'Enter');
    assert.equal(first.posts().length, 1);
    assert.equal(first.el('config').hidden, true);

    const second = await load(backendOf(state));
    await second.click('config-toggle');
    second.type('usage-interval', '45');
    await second.click('config-ok');
    assert.equal(second.posts().length, 1);
    assert.equal(second.el('config').hidden, true);
});

test('only the values that changed are sent, so an unchanged one is not saved by accident', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postConfig: accepting };
    const page = await load(backendOf(state));
    await page.click('config-toggle');

    page.type('poll-interval', '9');
    await page.click('config-ok');

    assert.deepEqual(JSON.parse(page.posts()[0].body), { pollIntervalSeconds: 9 });
});

test('a new update interval is polled at once, in the new rhythm', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postConfig: accepting };
    const page = await load(backendOf(state));
    await page.click('config-toggle');

    page.type('poll-interval', '5');
    await page.click('config-ok');

    assert.equal(page.calls.at(-1).url, '/api/status');
    assert.equal(page.timers.filter(t => t.live).pop().ms, 5000);
});

test('confirming values that did not change sends nothing and closes the fields', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');

    await page.press('usage-interval', 'Enter');

    assert.equal(page.posts().length, 0);
    assert.equal(page.el('config').hidden, true);
});

test('invalid values keep the fields open, flag the field, and send nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');

    for (const bad of ['4', '3601', '', 'abc', '1.5', '-3']) {
        page.type('usage-interval', bad);
        await page.press('usage-interval', 'Enter');

        assert.equal(page.el('config').hidden, false, bad);
        assert.equal(page.el('usage-interval').attrs['aria-invalid'], 'true', bad);
        assert.equal(page.el('config-note').hidden, false, bad);
        assert.match(page.el('config-note').textContent, /^The usage interval must be/, bad);
    }
    assert.equal(page.posts().length, 0);
});

test('a valid pair sent after a mistake closes the fields and clears the message', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');
    page.type('usage-interval', '4');
    await page.press('usage-interval', 'Enter');
    assert.equal(page.el('config-note').hidden, false);

    page.type('usage-interval', '45');
    await page.press('usage-interval', 'Enter');

    assert.equal(page.el('config').hidden, true);
    assert.equal(page.el('config-note').hidden, true);
    assert.equal(page.el('usage-interval').attrs['aria-invalid'], undefined);
});

test('one bad value stops both from being sent', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');

    page.type('usage-interval', '60');
    page.type('poll-interval', '99');
    await page.press('poll-interval', 'Enter');

    assert.equal(page.posts().length, 0);
    assert.equal(page.el('poll-interval').attrs['aria-invalid'], 'true');
    assert.equal(page.el('usage-interval').attrs['aria-invalid'], undefined);
    assert.equal(page.el('config').hidden, false);
});

test('Escape closes the fields without changing anything', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');
    page.type('usage-interval', '60');

    await page.press('usage-interval', 'Escape');

    assert.equal(page.el('config').hidden, true);
    assert.equal(page.posts().length, 0);
});

test('pressing the config button again closes the fields without changing anything', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');
    page.type('usage-interval', '60');

    await page.click('config-toggle');

    assert.equal(page.el('config').hidden, true);
    assert.equal(page.posts().length, 0);
});

test('reopening after a cancel shows the current values, not what was typed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');
    page.type('usage-interval', '60');
    await page.press('usage-interval', 'Escape');

    await page.click('config-toggle');

    assert.equal(page.el('usage-interval').value, '30');
});

test('a value the backend refuses keeps the fields open and shows the reason', async () => {
    const state = {
        config: CONFIG, status: SPEND_STATUS,
        postConfig: () => ({ status: 500, body: { error: 'The setting could not be saved: read-only file system' } }),
    };
    const page = await load(backendOf(state));
    await page.click('config-toggle');

    page.type('poll-interval', '9');
    await page.press('poll-interval', 'Enter');

    assert.equal(page.el('config').hidden, false);
    assert.equal(page.el('poll-interval').value, '9', 'what was typed is kept');
    assert.equal(page.el('config-note').textContent, 'The setting could not be saved: read-only file system');
});

test('a status poll does not close the fields or disturb what is being typed', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS, postConfig: accepting }));
    await page.click('config-toggle');
    page.type('usage-interval', '6');

    await page.firePoll();
    await page.firePoll();

    assert.equal(page.el('config').hidden, false);
    assert.equal(page.el('usage-interval').value, '6');
});

// ---- the window host

test('the page reports the size it needs, in whole pixels, for the window to match', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.window.contentSize(), '400,42');

    page.el('app').rect = { width: 512, height: 66 };
    assert.equal(page.window.contentSize(), '512,66');
});
