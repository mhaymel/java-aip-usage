'use strict';

// Run with: node --test src/test/frontend   (or ./gradlew frontendTest)
process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../main/resources/web/view.js');

const DISPLAY = {
    time: '14:24', timeTooltip: 'Last update: 8 Oct 2026, 14:24:53',
    spend: { percentText: '19%', percentTooltip: '19% of the budget spent. Severity: normal', used: '186.02', limit: '1,000.00',
             usedTooltip: 'Credits used, in USD', limitTooltip: 'Credit budget, in USD', severityText: 'normal', severityKind: 'normal' },
    windows: [], placeholder: null,
    countdown: { text: '42 s', tooltip: 'Seconds until the next refresh (negative when overdue)' },
    interval: { text: '60 s', tooltip: 'Time between usage requests' },
    deltaUsed: { text: '+0.05', tooltip: 'Change in the amount used since the previous reading, in USD' },
    deltaTime: { text: '1 m', tooltip: 'Time since the previous reading' },
    show: { percentage: false, interval: false, deltaUsed: true, deltaTime: true },
    message: null,
};

test('the status is read from what the backend finished, and nothing is worked out', () => {
    const v = view.describeStatus({ refreshing: false, stale: false, display: DISPLAY });

    assert.equal(v.time, '14:24');
    assert.equal(v.timeTooltip, 'Last update: 8 Oct 2026, 14:24:53');
    assert.deepEqual(v.spend, DISPLAY.spend);
    assert.deepEqual(v.countdown, DISPLAY.countdown);
    assert.deepEqual(v.deltaUsed, DISPLAY.deltaUsed);
    assert.deepEqual(v.deltaTime, DISPLAY.deltaTime);
    assert.deepEqual(v.interval, DISPLAY.interval);
    assert.deepEqual(v.show, { percentage: false, interval: false, deltaUsed: true, deltaTime: true });
    assert.equal(v.message, null);
});

test('the stale and refreshing flags come from the status as they are', () => {
    const v = view.describeStatus({ refreshing: true, stale: true, display: DISPLAY });

    assert.equal(v.stale, true);
    assert.equal(v.refreshing, true);
});

test('a status with no display shows nothing and every optional item off', () => {
    const v = view.describeStatus({ refreshing: false, stale: false });

    assert.equal(v.time, null);
    assert.equal(v.spend, null);
    assert.deepEqual(v.windows, []);
    assert.deepEqual(v.show, { percentage: false, interval: false, deltaUsed: false, deltaTime: false });
});

test('windows, placeholder and message are passed on as the backend wrote them', () => {
    const windows = [{ name: 'five_hour', utilizationText: '12.3%', resetsText: 'in 2 h 5 min' }];
    const message = { kind: 'stale', text: 'Refresh failed at 14:25: Anthropic returned HTTP 503.' };
    const v = view.describeStatus({ stale: true, display: { ...DISPLAY, spend: null, windows, placeholder: 'No data', message } });

    assert.deepEqual(v.windows, windows);
    assert.equal(v.placeholder, 'No data');
    assert.deepEqual(v.message, message);
});

test('the page script contains no arithmetic on readings: no date parsing, rounding or number formatting', () => {
    const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../main/resources/web/view.js'), 'utf8')
        + require('node:fs').readFileSync(require('node:path').join(__dirname, '../../main/resources/web/app.js'), 'utf8');

    for (const forbidden of ['new Date', 'Date.parse', 'toFixed', 'Intl.', 'toLocale', 'getHours']) {
        assert.equal(source.includes(forbidden), false, forbidden);
    }
});

// ---- the log panel and the history panel

test('the log panel shows the lines newest first, the reverse of the order the log sends them, each a row of one cell', () => {
    const v = view.describeLog({ exists: true, truncated: false, lines: ['2026-10-08 13:20:01 INFO    [X] a', '2026-10-08 13:20:02 INFO    [X] b', '2026-10-08 13:20:03 INFO    [X] c'] });

    assert.deepEqual(v.rows, [['2026-10-08 13:20:03 INFO    [X] c'], ['2026-10-08 13:20:02 INFO    [X] b'], ['2026-10-08 13:20:01 INFO    [X] a']]);
    assert.equal(v.header, null);
    assert.equal(v.note, null);
});

test('describing the log does not change what it was given', () => {
    const lines = ['2026-10-08 13:20:01 INFO    [X] a', '2026-10-08 13:20:02 INFO    [X] b'];
    view.describeLog({ exists: true, truncated: false, lines });
    assert.deepEqual(lines, ['2026-10-08 13:20:01 INFO    [X] a', '2026-10-08 13:20:02 INFO    [X] b']);
});

test('a log that was cut says how much is shown', () => {
    const v = view.describeLog({ exists: true, truncated: true, lines: ['2026-10-08 13:20:01 INFO    [X] x', '2026-10-08 13:20:02 INFO    [X] y', '2026-10-08 13:20:03 INFO    [X] z'] });

    assert.equal(v.note, 'Showing the newest 3 lines of the log.');
    assert.deepEqual(v.rows, [['2026-10-08 13:20:03 INFO    [X] z'], ['2026-10-08 13:20:02 INFO    [X] y'], ['2026-10-08 13:20:01 INFO    [X] x']]);
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
        '2026-10-08 13:20:56 INFO    [Main] Starting java-aip-usage v0.02',
        '2026-10-08 13:20:57 INFO    [LocalWebServer] Frontend served at http://127.0.0.1:1/',
        '2026-10-08 13:21:00 INFO    [Main] Starting java-aip-usage',
        '2026-10-08 13:21:01 INFO    [Main] Starting java-aip-usage-extra'
    ] });

    // newest first
    assert.deepEqual(v.marks, [false, true, false, true]);
});

test('a history row is marked as the start of a run by its status, and the status, interval and duration are not shown', () => {
    const rows = [
        ['2026-10-08 14:27:53', '186.12', '1000.00', 'USD', '', '60', '400'],
        ['2026-10-08 14:26:53', '186.07', '1000.00', 'USD', 'start', '60', '412'],
        ['2026-10-08 14:25:53', '', '', '', 'start-failed', '60', '20']
    ];
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 3, rows });

    assert.deepEqual(v.marks, [false, true, true]);
    assert.deepEqual(v.rows[0], ['2026-10-08 14:27:53', '186.12', '1000.00', 'USD']);
    assert.equal(v.rows[1].length, 4);
});

test('a failed query says failed in the place of its amount, whether or not it began the run', () => {
    const rows = [
        ['2026-10-08 14:28:53', '', '', '', 'failed', '60', '5003'],
        ['2026-10-08 14:27:53', '', '', '', 'start-failed', '60', '20'],
        ['2026-10-08 14:26:53', '186.07', '1000.00', 'USD', 'start', '60', '412']
    ];
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 3, rows });

    assert.deepEqual(v.failed, [true, true, false]);
    assert.equal(v.rows[0][1], 'failed');
    assert.equal(v.rows[1][1], 'failed');
    assert.equal(v.rows[2][1], '186.07');
});

test('rows of an older shape, with fewer fields, are neither marked nor failed', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 1, rows: [['2026-10-08 14:26:53', '1.00', '2.00', 'USD']] });

    assert.deepEqual(v.marks, [false]);
    assert.deepEqual(v.failed, [false]);
});

test('the lines of one log entry stay together and in order while the entries are newest first', () => {
    const lines = [
        '2026-10-08 13:20:01 INFO    [A] first',
        '2026-10-08 13:20:02 INFO    [UsageClient] Response of GET host/usage (HTTP 200):',
        '{',
        '  "spend" : {',
        '    "used" : 1.0',
        '  }',
        '}',
        '2026-10-08 13:20:03 WARNING [B] last'
    ];
    const v = view.describeLog({ exists: true, truncated: false, lines });

    assert.deepEqual(v.rows.map(r => r[0]), [lines[7], lines[1], lines[2], lines[3], lines[4], lines[5], lines[6], lines[0]]);
});

test('lines before the first entry start of what was read are kept as an entry of their own', () => {
    const v = view.describeLog({ exists: true, truncated: true, lines: ['  }', '}', '2026-10-08 13:20:03 INFO    [B] later'] });

    assert.deepEqual(v.rows.map(r => r[0]), ['2026-10-08 13:20:03 INFO    [B] later', '  }', '}']);
});

test('the history table gets the two change columns, after the currency, only when the backend says they are on', () => {
    const rows = [['2026-10-08 14:26:53', '186.12', '1000.00', 'USD', '', '60', '400'], ['2026-10-08 14:25:53', '', '', '', 'failed', '60', '20']];
    const deltas = [
        { delta_used: 0.05, delta_time: 60, delta_used_text: '+0.05', delta_time_text: '1 m', delta_seconds_text: '60 s' },
        { delta_used: null, delta_time: 60, delta_used_text: null, delta_time_text: '1 m', delta_seconds_text: '60 s' },
    ];
    const base = { exists: true, columns: COLUMNS, total: 2, rows, deltas };

    const none = view.describeHistory({ ...base, show: { deltaUsed: false, deltaTime: false } });
    assert.deepEqual(none.header, COLUMNS);
    assert.equal(none.rows[0].length, 4);

    const both = view.describeHistory({ ...base, show: { deltaUsed: true, deltaTime: true } });
    assert.deepEqual(both.header, [...COLUMNS, '\u0394 used', '\u0394 time']);
    assert.deepEqual(both.rows[0], ['2026-10-08 14:26:53', '186.12', '1000.00', 'USD', '+0.05', '60 s'], 'the time in seconds, never in minutes');
    assert.deepEqual(both.rows[1], ['2026-10-08 14:25:53', 'failed', '', '', '', '60 s'], 'an empty value is an empty cell');

    const timeOnly = view.describeHistory({ ...base, show: { deltaUsed: false, deltaTime: true } });
    assert.deepEqual(timeOnly.header, [...COLUMNS, '\u0394 time']);
    assert.deepEqual(timeOnly.rows[0].slice(4), ['60 s']);
});

test('the history table is described as the backend sent it: its titles, the wide time, and a hover text for the first line of a run', () => {
    const rows = [['20:46:11', '260.66', '1000.00', 'USD', '', '60', '400'], ['20:44:12', '260.36', '1000.00', 'USD', 'start', '60', '412']];
    const v = view.describeHistory({ exists: true, columns: ['time', 'used', 'limit', 'currency'], total: 2, rows,
        show: { deltaUsed: false, deltaTime: false, date: false }, startTooltip: 'The program started here' });

    assert.deepEqual(v.header, ['time', 'used', 'limit', 'currency']);
    assert.equal(v.wide, false);
    assert.deepEqual(v.titles, ['', 'The program started here']);

    const withDate = view.describeHistory({ exists: true, columns: ['date time', 'used', 'limit', 'currency'], total: 2, rows,
        show: { date: true } });
    assert.equal(withDate.wide, true);
    assert.deepEqual(withDate.header[0], 'date time');
});

test('the error log is described as time and message, newest first as sent, or as empty', () => {
    const v = view.describeErrors({ entries: [{ time: '11:34:42', message: 'b' }, { time: '11:20:01', message: 'a' }] });

    assert.deepEqual(v.header, ['time', 'message']);
    assert.deepEqual(v.rows, [['11:34:42', 'b'], ['11:20:01', 'a']]);
    assert.equal(v.note, null);
    assert.equal(view.describeErrors({ entries: [] }).note, 'There are no errors in this run.');
    assert.deepEqual(view.describeErrors({ entries: [] }).rows, []);
});

test('the message of an HTTP 429 is read from the display for the red countdown', () => {
    const v = view.describeStatus({ stale: false, display: { ...DISPLAY, countdownAlert: 'Anthropic is rate limiting usage requests (HTTP 429).' } });

    assert.equal(v.countdownAlert, 'Anthropic is rate limiting usage requests (HTTP 429).');
    assert.equal(view.describeStatus({ display: DISPLAY }).countdownAlert, null);
});

test('the history takes the time in seconds from the backend, however long, and leaves an unknown one empty', () => {
    const deltas = [{ delta_seconds_text: '3600 s', delta_time_text: '1 h' }, { delta_seconds_text: '126 s', delta_time_text: '2 m' }, { delta_seconds_text: null, delta_time_text: null }];
    const rows = [0, 1, 2].map(i => ['14:0' + i + ':00', '1.00', '2.00', 'USD', '', '60', '1']);

    const v = view.describeHistory({ exists: true, columns: ['time', 'used', 'limit', 'Cur.'], total: 3, rows, deltas, show: { deltaUsed: false, deltaTime: true } });

    assert.deepEqual(v.rows.map(r => r[4]), ['3600 s', '126 s', '']);
    assert.deepEqual(v.header, ['time', 'used', 'limit', 'Cur.', '\u0394 time']);
});
