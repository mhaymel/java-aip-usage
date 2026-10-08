'use strict';

// Run with: node --test src/test/frontend   (or ./gradlew frontendTest)
const test = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../main/resources/web/view.js');

const LOCALE = { locale: 'en-US', timeZone: 'UTC' };
const FETCHED = '2026-10-08T12:00:00Z';
const AT_FETCH = Date.parse(FETCHED);

function status(overrides) {
    return Object.assign({ refreshing: false, stale: false, error: null, usage: null }, overrides);
}

function usage(overrides) {
    return Object.assign({ source: 'anthropic-oauth-usage', fetched_at: FETCHED, spend: null, windows: [] }, overrides);
}

const SPEND = { used: 186.02, limit: 1000, currency: 'USD', percent: 19, severity: 'normal' };

test('before the first reading the page is loading, with nothing to show', () => {
    const v = view.describeStatus(status({ refreshing: true }), AT_FETCH, LOCALE);
    assert.deepEqual(v.badge, { text: 'Loading…', kind: 'loading' });
    assert.equal(v.fetched, null);
    assert.equal(v.notice, null);
    assert.equal(v.spend, null);
    assert.deepEqual(v.windows, []);
    assert.equal(v.empty, false);
    assert.equal(v.refreshing, true);
});

test('a spend reading shows used, limit, currency, percent and severity', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH + 5000, LOCALE);
    assert.deepEqual(v.badge, { text: 'Up to date', kind: 'ok' });
    assert.equal(v.spend.used, '$186.02');
    assert.equal(v.spend.limit, '$1,000.00');
    assert.equal(v.spend.currency, 'USD');
    assert.equal(v.spend.percentText, '19%');
    assert.equal(v.spend.barPercent, 19);
    assert.equal(v.spend.severityText, 'normal');
    assert.equal(v.spend.severityKind, 'normal');
    assert.deepEqual(v.windows, []);
    assert.equal(v.empty, false);
    assert.equal(v.notice, null);
});

test('a spend reading shows no plan windows, and a plan reading shows no spend', () => {
    const spendOnly = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);
    assert.equal(spendOnly.windows.length, 0);

    const windowsOnly = view.describeStatus(
        status({ usage: usage({ windows: [{ window: 'five_hour', utilization: 12.34, resets_at: null }] }) }),
        AT_FETCH, LOCALE);
    assert.equal(windowsOnly.spend, null);
    assert.equal(windowsOnly.windows.length, 1);
});

test('fetched_at is always shown, with its age', () => {
    const fresh = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH + 2000, LOCALE);
    assert.match(fresh.fetched, /^Fetched .*12:00:00.* \(just now\)$/);

    const older = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH + 125000, LOCALE);
    assert.match(older.fetched, /\(2 min ago\)$/);
});

test('plan windows keep their names as received and show utilization and reset', () => {
    const now = AT_FETCH;
    const v = view.describeStatus(status({
        usage: usage({
            windows: [
                { window: 'five_hour', utilization: 12.34, resets_at: '2026-10-08T14:05:00Z' },
                { window: 'seven_day', utilization: 80.0, resets_at: '2026-10-10T00:00:00Z' },
                { window: 'seven_day_opus', utilization: 0, resets_at: '2026-10-10T00:00:00Z' },
            ],
        }),
    }), now, LOCALE);

    assert.deepEqual(v.windows.map(w => w.name), ['five_hour', 'seven_day', 'seven_day_opus']);
    assert.deepEqual(v.windows.map(w => w.utilizationText), ['12.3%', '80%', '0%']);
    assert.deepEqual(v.windows.map(w => w.barPercent), [12.34, 80, 0]);
    assert.match(v.windows[0].resetsText, /^Resets in 2 h 5 min \(/);
    assert.match(v.windows[1].resetsText, /^Resets in 1 d 12 h \(/);
});

test('a window with no reset time is shown as unknown, not dropped', () => {
    const v = view.describeStatus(status({
        usage: usage({ windows: [{ window: 'cedar_ember', utilization: 3.5, resets_at: null }] }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.windows.length, 1);
    assert.equal(v.windows[0].resetsText, 'Reset time unknown');
});

test('a reset time in the past, or not a date, is still reported', () => {
    const v = view.describeStatus(status({
        usage: usage({
            windows: [
                { window: 'a', utilization: 1, resets_at: '2026-10-08T11:00:00Z' },
                { window: 'b', utilization: 1, resets_at: 'next tuesday' },
            ],
        }),
    }), AT_FETCH, LOCALE);
    assert.match(v.windows[0].resetsText, /^Reset was due /);
    assert.equal(v.windows[1].resetsText, 'Resets at next tuesday');
});

test('utilization over 100 is shown as it is, with the bar capped', () => {
    const v = view.describeStatus(status({
        usage: usage({ windows: [{ window: 'w', utilization: 104.26, resets_at: null }] }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.windows[0].utilizationText, '104.3%');
    assert.equal(v.windows[0].barPercent, 100);
});

test('missing spend fields are shown as a dash rather than as zero', () => {
    const v = view.describeStatus(status({
        usage: usage({ spend: { used: null, limit: 1000, currency: 'USD', percent: null, severity: null } }),
    }), AT_FETCH, LOCALE);
    assert.equal(v.spend.used, '—');
    assert.equal(v.spend.limit, '$1,000.00');
    assert.equal(v.spend.percentText, '—');
    assert.equal(v.spend.barPercent, null);
    assert.equal(v.spend.severityText, null);
    assert.equal(v.spend.severityKind, null);
});

test('an unknown currency code falls back to the plain number and the code', () => {
    assert.equal(view.formatMoney(186.02, 'XXXX', LOCALE), '186.02 XXXX');
    assert.equal(view.formatMoney(186.02, null, LOCALE), '186.02');
});

test('a failed refresh keeps the last reading and marks it stale', () => {
    const v = view.describeStatus(status({
        stale: true,
        error: { message: 'Anthropic returned HTTP 503.', at: '2026-10-08T12:01:00Z' },
        usage: usage({ spend: SPEND }),
    }), AT_FETCH + 70000, LOCALE);
    assert.deepEqual(v.badge, { text: 'Stale', kind: 'stale' });
    assert.equal(v.notice.kind, 'stale');
    assert.equal(v.notice.detail, 'Anthropic returned HTTP 503.');
    assert.match(v.notice.title, /last successful reading/);
    assert.equal(v.spend.used, '$186.02', 'the last good figures are still shown');
});

test('an error before any reading is shown with no figures', () => {
    const v = view.describeStatus(status({
        error: { message: 'Log in with Claude Code.', at: '2026-10-08T12:00:00Z' },
    }), AT_FETCH, LOCALE);
    assert.deepEqual(v.badge, { text: 'Error', kind: 'error' });
    assert.equal(v.notice.kind, 'error');
    assert.equal(v.notice.detail, 'Log in with Claude Code.');
    assert.equal(v.spend, null);
    assert.deepEqual(v.windows, []);
});

test('an account that reports nothing says so', () => {
    const v = view.describeStatus(status({ usage: usage() }), AT_FETCH, LOCALE);
    assert.equal(v.empty, true);
    assert.deepEqual(v.badge, { text: 'Up to date', kind: 'ok' });
});

test('the next success clears the notice', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);
    assert.equal(v.notice, null);
    assert.equal(v.badge.kind, 'ok');
});

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

test('spans and ages read naturally', () => {
    assert.equal(view.formatSpan(0), '0 s');
    assert.equal(view.formatSpan(59000), '59 s');
    assert.equal(view.formatSpan(60000), '1 min');
    assert.equal(view.formatSpan(3600000), '1 h');
    assert.equal(view.formatSpan(3900000), '1 h 5 min');
    assert.equal(view.formatSpan(86400000), '1 d');
    assert.equal(view.formatSpan(90000000), '1 d 1 h');
    assert.equal(view.formatSpan(-5000), '0 s');
    assert.equal(view.formatAge(4999), 'just now');
    assert.equal(view.formatAge(-1000), 'just now');
    assert.equal(view.formatAge(5000), '5 s ago');
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
