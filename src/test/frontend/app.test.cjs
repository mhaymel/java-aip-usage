'use strict';

// Drives app.js with a fake DOM and a fake backend. The fake document only knows
// the ids that index.html really declares, so a misspelt id fails here rather
// than silently doing nothing in the window.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WEB = path.join(__dirname, '../../main/resources/web');
const INDEX_IDS = [...fs.readFileSync(path.join(WEB, 'index.html'), 'utf8').matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);

const CONFIG = {
    usageIntervalSeconds: 30,
    pollIntervalSeconds: 1,
    limits: { usageIntervalSeconds: { min: 5, max: 3600 }, pollIntervalSeconds: { min: 1, max: 60 } },
};
const SPEND_STATUS = {
    refreshing: false, stale: false, error: null,
    usage: {
        source: 'anthropic-oauth-usage', fetched_at: new Date().toISOString(),
        spend: { used: 186.02, limit: 1000, currency: 'USD', percent: 19, severity: 'normal' }, windows: [],
    },
};
const WINDOWS_STATUS = {
    refreshing: false, stale: false, error: null,
    usage: {
        source: 'anthropic-oauth-usage', fetched_at: new Date().toISOString(), spend: null,
        windows: [
            { window: 'five_hour', utilization: 12.34, resets_at: null },
            { window: 'seven_day', utilization: 80, resets_at: '2099-01-01T00:00:00Z' },
        ],
    },
};

function element(id) {
    const el = {
        id, hidden: false, textContent: '', className: '', value: '', style: {}, children: [], attrs: {}, listeners: {},
        setAttribute(k, v) { this.attrs[k] = String(v); },
        removeAttribute(k) { delete this.attrs[k]; },
        addEventListener(type, fn) { this.listeners[type] = fn; },
        append(...nodes) { this.children.push(...nodes); },
        replaceChildren(...nodes) { this.children = [...nodes]; },
        querySelector() { return element('(bar)'); },
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
    return { el: id => elements.get(id), calls, timers, settle, firePoll: async () => {
        const next = timers.filter(t => t.live).pop();
        next.live = false;
        next.fn();
        await settle();
    } };
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

test('every id the page script looks up is declared in index.html', () => {
    const script = fs.readFileSync(path.join(WEB, 'app.js'), 'utf8');
    const used = new Set([...script.matchAll(/\$\('([^']+)'\)/g)].map(m => m[1]));
    for (const m of script.matchAll(/(?:input|hint): '([^']+)'/g)) used.add(m[1]);
    for (const id of used) {
        assert.ok(INDEX_IDS.includes(id), 'missing id in index.html: ' + id);
    }
});

test('at startup it asks for the intervals first, shows them, then polls the status', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS };
    const page = await load(backendOf(state));

    assert.deepEqual(page.calls.map(c => c.url).slice(0, 2), ['/api/config', '/api/status']);
    assert.equal(page.el('usage-interval').value, '30');
    assert.equal(page.el('poll-interval').value, '1');
    assert.match(page.el('usage-hint').textContent, /5 to 3600 seconds/);
    assert.match(page.el('poll-hint').textContent, /1 to 60 seconds/);
    assert.equal(page.calls.filter(c => c.url === '/api/refresh').length, 0, 'startup must not trigger a refresh');
});

test('it polls at the interval the backend reported', async () => {
    const state = { config: { ...CONFIG, pollIntervalSeconds: 7 }, status: SPEND_STATUS };
    const page = await load(backendOf(state));

    assert.equal(page.timers.filter(t => t.live).pop().ms, 7000);
    const before = page.calls.length;
    await page.firePoll();
    assert.equal(page.calls.length, before + 1);
    assert.equal(page.calls.at(-1).url, '/api/status');
    assert.equal(page.calls.at(-1).method, 'GET');
});

test('a spend reading is shown without any plan windows', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    assert.equal(page.el('badge').textContent, 'Up to date');
    assert.equal(page.el('spend').hidden, false);
    assert.equal(page.el('spend-used').textContent, '$186.02');
    assert.equal(page.el('spend-limit').textContent, '$1,000.00');
    assert.equal(page.el('spend-percent').textContent, '19% used');
    assert.equal(page.el('spend-bar').style.width, '19%');
    assert.equal(page.el('spend-severity').textContent, 'normal');
    assert.equal(page.el('spend-severity').className, 'pill pill-normal');
    assert.equal(page.el('windows').hidden, true);
    assert.equal(page.el('empty').hidden, true);
    assert.equal(page.el('fetched').hidden, false);
});

test('a plan reading shows each window and no spend', async () => {
    const page = await load(backendOf({ config: CONFIG, status: WINDOWS_STATUS }));

    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('windows').hidden, false);
    const items = page.el('window-list').children;
    assert.equal(items.length, 2);
    const nameOf = item => item.children[0].children[0].textContent;
    assert.deepEqual(items.map(nameOf), ['five_hour', 'seven_day']);
    assert.equal(items[0].children[0].children[1].textContent, '12.3%');
    assert.equal(items[0].children[2].textContent, 'Reset time unknown');
    assert.match(items[1].children[2].textContent, /^Resets in /);
});

test('a stale reading keeps its figures and shows the error', async () => {
    const status = { ...SPEND_STATUS, stale: true, error: { message: 'Anthropic returned HTTP 503.', at: new Date().toISOString() } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('badge').textContent, 'Stale');
    assert.equal(page.el('notice').hidden, false);
    assert.equal(page.el('notice-detail').textContent, 'Anthropic returned HTTP 503.');
    assert.equal(page.el('spend-used').textContent, '$186.02');
});

test('an error before any reading is shown, such as Claude Code not being logged in', async () => {
    const status = { refreshing: false, stale: false, usage: null, error: { message: 'Run claude and log in.', at: new Date().toISOString() } };
    const page = await load(backendOf({ config: CONFIG, status }));

    assert.equal(page.el('badge').textContent, 'Error');
    assert.equal(page.el('notice-detail').textContent, 'Run claude and log in.');
    assert.equal(page.el('spend').hidden, true);
    assert.equal(page.el('windows').hidden, true);
});

test('backend text is never handed to the HTML parser', async () => {
    const hostile = '<img src=x onerror=alert(1)>';
    const status = { refreshing: false, stale: false, usage: null, error: { message: hostile, at: new Date().toISOString() } };
    const page = await load(backendOf({ config: CONFIG, status: { ...status, usage: { ...WINDOWS_STATUS.usage, windows: [{ window: hostile, utilization: 1, resets_at: null }] } } }));

    assert.equal(page.el('notice-detail').textContent, hostile);
    assert.equal(page.el('window-list').children[0].children[0].children[0].textContent, hostile);
});

test('it keeps polling and shows a warning when the backend cannot be reached, then recovers', async () => {
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

test('if the settings cannot be loaded at startup it tells the user and retries', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, down: true };
    const page = await load(backendOf(state));

    assert.equal(page.el('connection').hidden, false);
    const retry = page.timers.filter(t => t.live).pop();
    assert.ok(retry, 'a retry is scheduled');

    state.down = false;
    retry.fn();
    await settle();
    assert.equal(page.el('connection').hidden, true);
    assert.equal(page.el('usage-interval').value, '30');
    assert.equal(page.el('spend-used').textContent, '$186.02');
});

test('a committed valid poll interval is sent, saved, applied and polled at once', async () => {
    const state = {
        config: CONFIG, status: SPEND_STATUS,
        postConfig: body => ({ status: 200, body: { ...CONFIG, ...body } }),
    };
    const page = await load(backendOf(state));
    const before = page.calls.length;

    page.el('poll-interval').value = '5';
    await page.el('poll-interval').listeners.change();
    await settle();

    const post = page.calls.slice(before).find(c => c.method === 'POST');
    assert.equal(post.url, '/api/config');
    assert.equal(post.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(post.body), { pollIntervalSeconds: 5 });
    assert.equal(page.el('settings-message').textContent, 'Saved.');
    assert.equal(page.el('poll-interval').value, '5');
    assert.equal(page.calls.at(-1).url, '/api/status', 'the new rhythm starts with a poll right away');
    assert.equal(page.timers.filter(t => t.live).pop().ms, 5000);
});

test('a committed valid usage interval is sent on its own', async () => {
    const state = {
        config: CONFIG, status: SPEND_STATUS,
        postConfig: body => ({ status: 200, body: { ...CONFIG, ...body } }),
    };
    const page = await load(backendOf(state));

    page.el('usage-interval').value = ' 90 ';
    await page.el('usage-interval').listeners.change();
    await settle();

    const post = page.calls.find(c => c.method === 'POST');
    assert.deepEqual(JSON.parse(post.body), { usageIntervalSeconds: 90 });
    assert.equal(page.el('usage-interval').value, '90');
});

test('an invalid value is not sent, and is flagged', async () => {
    const state = { config: CONFIG, status: SPEND_STATUS, postConfig: () => { throw new Error('must not be called'); } };
    const page = await load(backendOf(state));

    for (const bad of ['4', '3601', '', 'abc', '1.5', '-3']) {
        page.el('usage-interval').value = bad;
        await page.el('usage-interval').listeners.change();
        assert.equal(page.el('usage-interval').attrs['aria-invalid'], 'true', bad);
        assert.match(page.el('settings-message').textContent, /^The usage interval must be/, bad);
        assert.equal(page.el('settings-message').className, 'settings-message error');
    }
    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0);

    page.el('usage-interval').value = '45';
    state.postConfig = body => ({ status: 200, body: { ...CONFIG, ...body } });
    await page.el('usage-interval').listeners.change();
    await settle();
    assert.equal(page.el('usage-interval').attrs['aria-invalid'], undefined, 'the flag clears once the value is valid');
});

test('re-entering the current value changes nothing and sends nothing', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));

    page.el('poll-interval').value = '1';
    await page.el('poll-interval').listeners.change();

    assert.equal(page.calls.filter(c => c.method === 'POST').length, 0);
});

test('a value the backend refuses is put back and the reason shown', async () => {
    const state = {
        config: CONFIG, status: SPEND_STATUS,
        postConfig: () => ({ status: 500, body: { error: 'The setting could not be saved: read-only file system' } }),
    };
    const page = await load(backendOf(state));

    page.el('poll-interval').value = '9';
    await page.el('poll-interval').listeners.change();
    await settle();

    assert.equal(page.el('poll-interval').value, '1');
    assert.equal(page.el('settings-message').textContent, 'The setting could not be saved: read-only file system');
    assert.equal(page.el('settings-message').className, 'settings-message error');
});

test('the refresh button asks the backend to fetch, then shows the result, and stays enabled', async () => {
    const page = await load(backendOf({ config: CONFIG, status: SPEND_STATUS }));
    const before = page.calls.length;

    await page.el('refresh').listeners.click();
    await settle();

    const sent = page.calls.slice(before);
    assert.equal(sent[0].url, '/api/refresh');
    assert.equal(sent[0].method, 'POST');
    assert.equal(sent[0].headers['Content-Type'], 'application/json');
    assert.equal(sent[1].url, '/api/status');
    assert.notEqual(page.el('refresh').disabled, true, 'extra clicks stay possible');
});

test('the page says when a refresh is running', async () => {
    const page = await load(backendOf({ config: CONFIG, status: { ...SPEND_STATUS, refreshing: true } }));

    assert.equal(page.el('refreshing').textContent, 'Refreshing…');
});
