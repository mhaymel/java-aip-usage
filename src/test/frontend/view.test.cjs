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
    placeholder: null,
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
    assert.deepEqual(v.show, { percentage: false, currency: false, interval: false, deltaUsed: false, deltaTime: false, historyIcon: true, logIcon: true, errorIcon: true },
        'the buttons are there until the backend says otherwise');
});

test('placeholder and message are passed on as the backend wrote them', () => {
    const message = { kind: 'stale', text: 'Refresh failed at 14:25: Anthropic returned HTTP 503.' };
    const v = view.describeStatus({ stale: true, display: { ...DISPLAY, spend: null, placeholder: 'No data', message } });

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

test('the page makes no decision about the lines of the history: no status text, no pick of which are failed, no cutting of columns', () => {
    const view = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../main/resources/web/view.js'), 'utf8');

    for (const forbidden of ['hasStatus', 'row[4]', "split('-')", 'data.columns.length', "'failed'", 'delta_seconds_text', 'delta_used_text', 'startTooltip']) {
        assert.equal(view.includes(forbidden), false, forbidden);
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

const COLUMNS = ['time', 'used', 'limit', 'Cur.'];
const line = (cells, extra = {}) => ({ cells, start: false, failed: false, title: '', ...extra });

test('the history panel is a table: the columns as the header and a row of cells for each line, as the backend finished them', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, wide: false, note: null, total: 2,
        lines: [line(['14:26:53', '186.12', '1000.00', 'USD']), line(['14:25:53', '186.07', '1000.00', ''])] });

    assert.deepEqual(v.header, COLUMNS);
    assert.deepEqual(v.rows, [['14:26:53', '186.12', '1000.00', 'USD'], ['14:25:53', '186.07', '1000.00', '']]);
    assert.equal(v.note, null);
    assert.equal(v.wide, false);
});

test('the note is the backend\'s, shown as it is, and the table is there with it', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 5000, note: 'Showing 1 of 5,000 lines: 4,999 older not shown.',
        lines: [line(['14:26:53', '1.00', '2.00', 'USD'])] });

    assert.equal(v.note, 'Showing 1 of 5,000 lines: 4,999 older not shown.');
    assert.equal(v.rows.length, 1);
});

test('with no lines there is a note and no header and no rows, whatever the reason', () => {
    for (const note of ['There is no usage history yet.', 'The history has no rows yet.', 'Showing 0 of 2 lines: 2 failed hidden.']) {
        const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 2, note, lines: [] });
        assert.equal(v.note, note);
        assert.deepEqual(v.rows, []);
        assert.equal(v.header, null);
    }
});

test('a line that begins a run, one that failed, and the hover text are the backend\'s flags, not worked out here', () => {
    const v = view.describeHistory({ exists: true, columns: COLUMNS, total: 3, lines: [
        line(['14:27:53', '186.12', '1000.00', 'USD']),
        line(['14:26:53', 'failed', '', ''], { failed: true }),
        line(['14:25:53', '186.07', '1000.00', 'USD'], { start: true, title: 'The program started here' })] });

    assert.deepEqual(v.marks, [false, false, true]);
    assert.deepEqual(v.failed, [false, true, false]);
    assert.deepEqual(v.titles, ['', '', 'The program started here']);
    assert.equal(v.rows[1][1], 'failed', 'its cells are as they came');
});

test('the wide time of a table with the date is the backend\'s say', () => {
    const v = view.describeHistory({ exists: true, columns: ['date time', 'used', 'limit', 'Cur.'], wide: true, total: 1,
        lines: [line(['2026-10-08 14:26:53', '1.00', '2.00', 'USD'])] });

    assert.equal(v.wide, true);
    assert.deepEqual(v.header[0], 'date time');
});

test('the change columns are simply more cells and more titles: nothing is added up or cut here', () => {
    const cols = [...COLUMNS, '\u0394 used', '\u0394 time'];
    const v = view.describeHistory({ exists: true, columns: cols, total: 1,
        lines: [line(['14:26:53', '186.12', '1000.00', 'USD', '+0.05', '63 s'])] });

    assert.deepEqual(v.header, cols);
    assert.deepEqual(v.rows[0], ['14:26:53', '186.12', '1000.00', 'USD', '+0.05', '63 s']);
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


test('the history note is highlighted when the backend says so, and only then', () => {
    const base = { exists: true, columns: ['time', 'used', 'limit', 'Cur.'], total: 2, lines: [] };

    assert.equal(view.describeHistory({ ...base, note: 'Showing 0 of 2 lines: 2 failed hidden.', noteHighlight: true }).noteHighlight, true);
    assert.equal(view.describeHistory({ ...base, note: 'There is no usage history yet.', noteHighlight: false }).noteHighlight, false);
    assert.equal(view.describeHistory({ ...base, note: null }).noteHighlight, false, 'a missing flag is no highlight');
});

test('the error log\'s empty note is highlighted; with errors there is no note', () => {
    assert.equal(view.describeErrors({ entries: [] }).noteHighlight, true);
    assert.equal(view.describeErrors({ entries: [{ time: '11:34:42', message: 'x' }] }).note, null);
});

test('the page decides nothing about the history note from its text', () => {
    const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../main/resources/web/view.js'), 'utf8');

    assert.equal(source.includes("indexOf('Showing"), false);
    assert.equal(source.includes("startsWith('Showing"), false);
});

test('the history marks its first lines with the green class and the log with the gray one', () => {
    assert.equal(view.describeHistory({ exists: true, columns: ['time'], total: 1, lines: [{ cells: ['14:00:00'], start: true, failed: false, title: '' }] }).markClass, 'start');
    assert.equal(view.describeLog({ exists: true, truncated: false, lines: ['2026-10-08 13:20:56 INFO    [Main] Starting java-aip-usage'] }).markClass, 'mark');
});
