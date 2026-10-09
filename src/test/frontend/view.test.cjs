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

test('a date and time reads day, month, year and 24-hour time, whatever the language', () => {
    assert.equal(view.formatDateTime('2026-10-08T14:24:53Z'), '8 Oct 2026, 14:24:53');
    assert.equal(view.formatDateTime('2026-01-02T03:04:05Z'), '2 Jan 2026, 03:04:05', 'the day is not padded, the time is');
    assert.equal(view.formatDateTime('2026-12-31T23:59:59Z'), '31 Dec 2026, 23:59:59');
    assert.equal(view.formatDateTime('2026-10-08T00:05:09Z'), '8 Oct 2026, 00:05:09', 'midnight is 00, not 24');
    assert.equal(view.formatDateTime('not a time'), 'not a time');
});

test('a date and time is the local one, and the date moves with the time zone', () => {
    try {
        process.env.TZ = 'Asia/Tokyo';
        assert.equal(view.formatDateTime('2026-10-08T23:30:00Z'), '9 Oct 2026, 08:30:00');
        process.env.TZ = 'America/New_York';
        assert.equal(view.formatDateTime('2026-10-08T02:30:00Z'), '7 Oct 2026, 22:30:00');
    } finally {
        process.env.TZ = 'UTC';
    }
});

test('the tooltip on the time is the one place a date appears', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);

    assert.equal(v.timeTooltip, 'Last update: 8 Oct 2026, 14:24:53');
    assert.equal(v.time, '14:24:53', 'while the time itself stays time of day only');
});

test('nothing the strip shows contains a date', () => {
    const v = view.describeStatus(status({
        stale: true,
        error: { message: 'Anthropic returned HTTP 503.', at: '2026-10-08T14:25:01Z' },
        usage: usage({ spend: SPEND, windows: [{ window: 'five_hour', utilization: 1, resets_at: '2026-10-12T00:00:00Z' }] }),
    }), AT_FETCH, LOCALE);

    const shown = [v.time, v.spend.used, v.spend.limit, v.spend.percentText, v.message.text, v.countdown && v.countdown.text,
        ...v.windows.flatMap(w => [w.name, w.utilizationText, w.resetsText])].join(' ');
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
    assert.equal(v.spend.used, '186.02');
    assert.equal(v.spend.limit, '1,000.00');
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
    assert.equal(v.spend.used, '\u2014');
    assert.equal(v.spend.limit, '1,000.00');
    assert.equal(v.spend.percentText, null);
    assert.equal(v.spend.percentTooltip, 'Share of the budget spent');
    assert.equal(v.spend.severityKind, null);
    assert.equal(v.spend.severityText, null);
});

test('amounts are plain numbers with two decimals and no currency sign', () => {
    assert.equal(view.formatAmount(186.02, LOCALE), '186.02');
    assert.equal(view.formatAmount(1000, LOCALE), '1,000.00');
    assert.equal(view.formatAmount(0, LOCALE), '0.00');
    assert.equal(view.formatAmount(1234567.891, LOCALE), '1,234,567.89');
    assert.equal(view.formatAmount(null, LOCALE), '\u2014');
    assert.equal(view.formatAmount(undefined, LOCALE), '\u2014');
});

test('no currency sign appears whatever the currency is', () => {
    for (const currency of ['USD', 'EUR', 'JPY', 'GBP', 'XXXX', null]) {
        const v = view.describeStatus(status({ usage: usage({ spend: { ...SPEND, currency } }) }), AT_FETCH, LOCALE);
        assert.doesNotMatch(v.spend.used + v.spend.limit, /[$\u20ac\u00a3\u00a5A-Za-z]/, String(currency));
    }
});

test('each amount has a tooltip naming what it is and its unit', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);

    assert.equal(v.spend.usedTooltip, 'Credits used, in USD');
    assert.equal(v.spend.limitTooltip, 'Credit budget, in USD');
});

test('the unit in the tooltips is the currency the response names', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: { ...SPEND, currency: 'EUR' } }) }), AT_FETCH, LOCALE);

    assert.equal(v.spend.usedTooltip, 'Credits used, in EUR');
    assert.equal(v.spend.limitTooltip, 'Credit budget, in EUR');
});

test('without a currency the tooltips still say what the numbers are', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: { ...SPEND, currency: null } }) }), AT_FETCH, LOCALE);

    assert.equal(v.spend.usedTooltip, 'Credits used');
    assert.equal(v.spend.limitTooltip, 'Credit budget');
});

test('the percentage tooltip says what it is, and the severity that its colour stands for', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE);

    assert.equal(v.spend.percentTooltip, '19% of the budget spent. Severity: normal');
});

// ---- the countdown

test('the countdown is the backend\'s seconds, shown with the unit', () => {
    assert.deepEqual(view.describeCountdown(42), { text: '42 s', tooltip: 'Seconds until the next refresh (negative when overdue)' });
    assert.equal(view.describeCountdown(0).text, '0 s');
    assert.equal(view.describeCountdown(3600).text, '3600 s');
});

test('an overdue refresh counts below zero, with the minus sign', () => {
    assert.equal(view.describeCountdown(-3).text, '-3 s');
    assert.equal(view.describeCountdown(-120).text, '-120 s');
});

test('there is no countdown when the backend has nothing to count to', () => {
    for (const none of [null, undefined, 'soon', NaN, Infinity, {}]) {
        assert.equal(view.describeCountdown(none), null, String(none));
    }
});

test('a countdown that is not whole is rounded, not truncated, for display', () => {
    assert.equal(view.describeCountdown(41.6).text, '42 s');
    assert.equal(view.describeCountdown(41.4).text, '41 s');
});

test('the status carries the countdown into the view', () => {
    const v = view.describeStatus(status({ usage: usage({ spend: SPEND }), nextRefreshInSeconds: -3 }), AT_FETCH, LOCALE);

    assert.equal(v.countdown.text, '-3 s');
});

test('a status without a countdown has none in the view', () => {
    assert.equal(view.describeStatus(status({ usage: usage({ spend: SPEND }) }), AT_FETCH, LOCALE).countdown, null);
    assert.equal(view.describeStatus(status({ usage: usage({ spend: SPEND }), nextRefreshInSeconds: null }), AT_FETCH, LOCALE).countdown, null);
});

test('the countdown is shown even before the first reading, since the next refresh is still coming', () => {
    const v = view.describeStatus(status({ refreshing: true, nextRefreshInSeconds: 55 }), AT_FETCH, LOCALE);

    assert.equal(v.countdown.text, '55 s');
    assert.equal(v.placeholder, 'Loading\u2026');
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
    assert.equal(v.spend.used, '186.02', 'the last good figures stay in the row');
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

// ---- the log panel and the history panel

test('the log panel shows the lines newest first, the reverse of the order the log sends them, each a row of one cell', () => {
    const v = view.describeLog({ exists: true, truncated: false, lines: ['a', 'b', 'c'] });

    assert.deepEqual(v.rows, [['c'], ['b'], ['a']]);
    assert.equal(v.header, null);
    assert.equal(v.note, null);
});

test('describing the log does not change what it was given', () => {
    const lines = ['a', 'b'];
    view.describeLog({ exists: true, truncated: false, lines });
    assert.deepEqual(lines, ['a', 'b']);
});

test('a log that was cut says how much is shown', () => {
    const v = view.describeLog({ exists: true, truncated: true, lines: ['x', 'y', 'z'] });

    assert.equal(v.note, 'Showing the newest 3 lines of the log.');
    assert.deepEqual(v.rows, [['z'], ['y'], ['x']]);
});

test('a missing or empty log says so', () => {
    assert.equal(view.describeLog({ exists: false, truncated: false, lines: [] }).note, 'There is no log file yet.');
    assert.equal(view.describeLog({ exists: true, truncated: false, lines: [] }).note, 'The log is empty.');
});

const ROWS = [['2026-10-08 14:26:53', '186.12', '1000.00', 'USD'], ['2026-10-08 14:25:53', '186.07', '1000.00', '']];
const COLUMNS = ['datetime', 'used', 'limit', 'currency'];

test('the history panel is a table: the columns as the header and a row of four cells for each reading, newest first', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 2, rows: ROWS });

    assert.deepEqual(v.header, COLUMNS);
    assert.deepEqual(v.rows, ROWS);
    assert.equal(v.note, null);
});

test('a history that was cut says how much of it is shown', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 5000, rows: ROWS });

    assert.equal(v.note, 'Showing the newest 2 of 5000 rows.');
    assert.equal(v.rows.length, 2);
});

test('a missing or empty history says so, with no header and no rows', () => {
    const none = view.describeHistory({ exists: false, columns: COLUMNS, total: 0, rows: [] });
    assert.equal(none.note, 'There is no usage history yet.');
    assert.deepEqual(none.rows, []);
    assert.equal(none.header, null);
    const empty = view.describeHistory({ exists: true, columns: COLUMNS, total: 0, rows: [] });
    assert.equal(empty.note, 'The history has no rows yet.');
    assert.deepEqual(empty.rows, []);
    assert.equal(empty.header, null);
});

test('the first line of each run in the log is marked, and only that', () => {
    const v = view.describeLog({ exists: true, truncated: false, lines: [
        '2026-10-08T13:20:56.142Z INFO    [Main] Starting java-aip-usage v0.02',
        '2026-10-08T13:20:56.152Z INFO    [LocalWebServer] Frontend served at http://127.0.0.1:1/',
        '2026-10-08T13:21:00.000Z INFO    [Main] Starting java-aip-usage',
        '2026-10-08T13:21:01.000Z INFO    [Main] Starting java-aip-usage-extra'
    ] });

    // newest first
    assert.deepEqual(v.marks, [false, true, false, true]);
});

test('a history row is marked when its fifth field is 1, and that field is not shown', () => {
    const rows = [['2026-10-08 14:26:53', '186.12', '1000.00', 'USD', ''], ['2026-10-08 14:25:53', '186.07', '1000.00', 'USD', '1']];
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 2, rows });

    assert.deepEqual(v.marks, [false, true]);
    assert.deepEqual(v.rows, [rows[0].slice(0, 4), rows[1].slice(0, 4)]);
});
