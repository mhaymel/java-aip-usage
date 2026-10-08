'use strict';

// Run with: node --test src/test/frontend   (or ./gradlew frontendTest)
process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../main/resources/web/view.js');

const LOCALE = { locale: 'en-US' };
const FETCHED = '2026-10-08T14:24:53Z';
const AT_FETCH = Date.parse(FETCHED);

function status(overrides) {
    return Object.assign({ refreshing: false, stale: false, error: null, usage: null }, overrides);
}

function usage(overrides) {
    return Object.assign({ source: 'anthropic-oauth-usage', fetched_at: FETCHED, spend: null, windows: [] }, overrides);
}

const SPEND = { used: 186.02, limit: 1000, currency: 'USD', percent: 19, severity: 'normal' };

// ---- time: local time of day only

test('a time is shown as the local time of day, 24-hour, never the date', () => {
    assert.equal(view.formatTime('2026-10-08T14:24:53Z'), '14:24:53');
    assert.equal(view.formatTime('2026-10-08T14:24:53.123456Z'), '14:24:53');
    assert.equal(view.formatTime('2026-10-08T00:05:09Z'), '00:05:09', 'midnight is 00, not 24');
    assert.equal(view.formatTime('2026-10-08T23:59:59Z'), '23:59:59');
    assert.equal(view.formatTime('2026-01-02T03:04:05Z'), '03:04:05', 'single digits are padded');
});

test('a time is converted to the local time zone', () => {
    try {
        process.env.TZ = 'Asia/Tokyo';
        assert.equal(view.formatTime('2026-10-08T14:24:53Z'), '23:24:53');
        process.env.TZ = 'America/New_York';
        assert.equal(view.formatTime('2026-10-08T14:24:53Z'), '10:24:53');
    } finally {
        process.env.TZ = 'UTC';
    }
});

test('a time that cannot be read is shown as received', () => {
    assert.equal(view.formatTime('not a time'), 'not a time');
});

test('nothing the strip shows contains a date', () => {
    const v = view.describeStatus(status({
        stale: true,
        error: { message: 'Anthropic returned HTTP 503.', at: '2026-10-08T14:25:01Z' },
        usage: usage({ spend: SPEND, windows: [{ window: 'five_hour', utilization: 1, resets_at: '2026-10-12T00:00:00Z' }] }),
    }), AT_FETCH, LOCALE);

    const shown = [v.time, v.spend.amounts, v.message.text, ...v.windows.flatMap(w => [w.name, w.utilizationText, w.resetsText])].join(' ');
    assert.doesNotMatch(shown, /2026|Oct|\d{4}-\d{2}/, shown);
});

// ---- the row

test('before the first reading the row says it is loading', () => {
    const v = view.describeStatus(status({ refreshing: true }), AT_FETCH, LOCALE);
    assert.equal(v.placeholder, 'Loading…');
    assert.equal(v.time, null);
    assert.equal(v.spend, null);
    assert.deepEqual(v.windows, []);
    assert.equal(v.message, null);
    assert.equal(v.refreshing, true);
    assert.equal(v.stale, false);
});

test('a spend reading shows the refresh time, spent and budget, percent and severity', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);
    assert.equal(v.time, '14:24:53');
    assert.equal(v.spend.amounts, '$186.02 / $1,000.00');
    assert.equal(v.spend.percentText, '19%');
    assert.equal(v.spend.severityKind, 'normal');
    assert.equal(v.spend.severityText, 'normal');
    assert.deepEqual(v.windows, []);
    assert.equal(v.placeholder, null);
    assert.equal(v.message, null);
    assert.equal(v.stale, false);
});

test('a plan reading shows each window and no spend', () => {
    const v = view.describeStatus(status({
        usage: usage({
            windows: [
                { window: 'five_hour', utilization: 12.34, resets_at: '2026-10-08T16:29:53Z' },
                { window: 'seven_day', utilization: 80.0, resets_at: '2026-10-10T02:24:53Z' },
                { window: 'seven_day_opus', utilization: 0, resets_at: null },
            ],
        }),
    }), AT_FETCH, LOCALE);

    assert.equal(v.spend, null);
    assert.equal(v.time, '14:24:53');
    assert.deepEqual(v.windows.map(w => w.name), ['five_hour', 'seven_day', 'seven_day_opus'], 'names as received');
    assert.deepEqual(v.windows.map(w => w.utilizationText), ['12.3%', '80%', '0%']);
    assert.deepEqual(v.windows.map(w => w.resetsText), ['in 2 h 5 min', 'in 1 d 12 h', 'reset unknown']);
    assert.equal(v.placeholder, null);
});

test('a reset is shown as time remaining, because a time of day alone would mislead', () => {
    const v = view.describeStatus(status({
        usage: usage({ windows: [{ window: 'seven_day', utilization: 5, resets_at: '2026-10-15T14:24:53Z' }] }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.windows[0].resetsText, 'in 7 d');
});

test('a reset in the past, or not a date, is still reported', () => {
    const v = view.describeStatus(status({
        usage: usage({
            windows: [
                { window: 'a', utilization: 1, resets_at: '2026-10-08T11:00:00Z' },
                { window: 'b', utilization: 1, resets_at: 'next tuesday' },
            ],
        }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.windows[0].resetsText, 'reset due');
    assert.equal(v.windows[1].resetsText, 'resets next tuesday');
});

test('utilization over 100 is shown as it is', () => {
    const v = view.describeStatus(status({
        usage: usage({ windows: [{ window: 'w', utilization: 104.26, resets_at: null }] }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.windows[0].utilizationText, '104.3%');
});

test('missing spend fields are shown as a dash rather than as zero', () => {
    const v = view.describeStatus(status({
        usage: usage({ spend: { used: null, limit: 1000, currency: 'USD', percent: null, severity: null } }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.spend.amounts, '— / $1,000.00');
    assert.equal(v.spend.percentText, null);
    assert.equal(v.spend.severityKind, null);
    assert.equal(v.spend.severityText, null);
});

test('an unknown currency code falls back to the plain number and the code', () => {
    assert.equal(view.formatMoney(186.02, 'XXXX', LOCALE), '186.02 XXXX');
    assert.equal(view.formatMoney(186.02, null, LOCALE), '186.02');
});

test('an account that reports nothing says so', () => {
    const v = view.describeStatus(status({ usage: usage() }), AT_FETCH, LOCALE);
    assert.equal(v.placeholder, 'No usage reported');
    assert.equal(v.time, '14:24:53');
});

// ---- messages and stale data

test('a failed refresh keeps the last reading, dims it as stale, and says what failed', () => {
    const v = view.describeStatus(status({
        stale: true,
        error: { message: 'Anthropic returned HTTP 503.', at: '2026-10-08T14:25:01Z' },
        usage: usage({ spend: SPEND }),
    }), AT_FETCH + 8000, LOCALE);
    assert.equal(v.stale, true);
    assert.equal(v.message.kind, 'stale');
    assert.equal(v.message.text, 'Refresh failed at 14:25:01: Anthropic returned HTTP 503.');
    assert.equal(v.spend.amounts, '$186.02 / $1,000.00', 'the last good figures stay in the row');
    assert.equal(v.time, '14:24:53', 'and so does the time they are from');
});

test('an error before any reading is shown with no figures', () => {
    const v = view.describeStatus(status({
        error: { message: 'Claude Code could not be found on the PATH.', at: '2026-10-08T14:17:11Z' },
    }), AT_FETCH, LOCALE);
    assert.equal(v.placeholder, 'No data');
    assert.equal(v.message.kind, 'error');
    assert.equal(v.message.text, 'Refresh failed at 14:17:11: Claude Code could not be found on the PATH.');
    assert.equal(v.stale, false, 'nothing to be stale');
    assert.equal(v.spend, null);
});

test('the next success clears the message and the stale look', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);
    assert.equal(v.message, null);
    assert.equal(v.stale, false);
});

// ---- helpers

test('severity maps to a fixed set of style names, whatever the backend sends', () => {
    assert.equal(view.severityKind('normal'), 'normal');
    assert.equal(view.severityKind('WARNING'), 'warning');
    assert.equal(view.severityKind('critical'), 'critical');
    assert.equal(view.severityKind('something new'), 'other');
    assert.equal(view.severityKind('"><script>alert(1)</script>'), 'other');
    assert.equal(view.severityKind('constructor'), 'other');
    assert.equal(view.severityKind('__proto__'), 'other');
    assert.equal(view.severityKind(null), 'other');
    assert.equal(view.severityKind(42), 'other');
});

test('spans read naturally', () => {
    assert.equal(view.formatSpan(0), '0 s');
    assert.equal(view.formatSpan(59000), '59 s');
    assert.equal(view.formatSpan(60000), '1 min');
    assert.equal(view.formatSpan(3600000), '1 h');
    assert.equal(view.formatSpan(3900000), '1 h 5 min');
    assert.equal(view.formatSpan(86400000), '1 d');
    assert.equal(view.formatSpan(90000000), '1 d 1 h');
    assert.equal(view.formatSpan(-5000), '0 s');
});

test('percentages drop a trailing .0', () => {
    assert.equal(view.formatPercent(80), '80%');
    assert.equal(view.formatPercent(12.34), '12.3%');
    assert.equal(view.formatPercent(0), '0%');
    assert.equal(view.formatPercent(99.96), '100%');
});

test('interval input is checked against the limits the backend reported', () => {
    const limits = { min: 5, max: 3600 };
    assert.equal(view.checkInterval('30', limits, 'The usage interval'), null);
    assert.equal(view.checkInterval(' 30 ', limits, 'The usage interval'), null);
    assert.equal(view.checkInterval('5', limits, 'x'), null);
    assert.equal(view.checkInterval('3600', limits, 'x'), null);
    assert.equal(view.checkInterval('4', limits, 'The usage interval'), 'The usage interval must be from 5 to 3600 seconds.');
    assert.equal(view.checkInterval('3601', limits, 'The usage interval'), 'The usage interval must be from 5 to 3600 seconds.');
    for (const bad of ['', '  ', 'abc', '1.5', '-1', '1e3', '+5', '0x10', '30s']) {
        assert.equal(view.checkInterval(bad, limits, 'The usage interval'), 'The usage interval must be a whole number of seconds.', JSON.stringify(bad));
    }
});
