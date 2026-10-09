// Turns the backend's status document into what the strip shows. Pure functions
// with no DOM access, so they run under Node as well as in the browser.
(function (root) {
    'use strict';

    /**
     * What the strip shows. The backend has finished every text, tooltip and figure, in the `display`
     * part of its status, so nothing is worked out here: no time cut, no sum, no remaining time. This
     * only reads the document into the shape the page places.
     *
     * @param status the document from GET /api/status
     * @returns what the strip shows:
     *   time         local time of the last reading, or null
     *   timeTooltip  "Last update: " and the full date and time, or null
     *   spend        {percentText, percentTooltip, used, limit, usedTooltip, limitTooltip,
     *                 severityText, severityKind}, or null
     *   windows      [{name, utilizationText, resetsText}]
     *   placeholder  text for a row with nothing else to show, or null
     *   countdown    {text, tooltip} for the seconds to the next refresh, or null
     *   countdownAlert  the message of an HTTP 429, for the red countdown and its hover line, or null
     *   interval     {text, tooltip} for the time between usage requests
     *   deltaUsed    {text, tooltip} for the change in the amount used, or null
     *   deltaTime    {text, tooltip} for the time since the previous reading, or null
     *   show         {percentage, interval, deltaUsed, deltaTime}: which optional items are switched on
     *   message      {kind: 'error'|'stale', text} for the line under the row, or null
     *   stale        the figures predate a failed refresh
     *   refreshing   a refresh is running
     */
    function describeStatus(status) {
        var d = status.display || {};
        return {
            time: d.time || null,
            timeTooltip: d.timeTooltip || null,
            spend: d.spend || null,
            windows: d.windows || [],
            placeholder: d.placeholder || null,
            countdown: d.countdown || null,
            countdownAlert: d.countdownAlert || null,
            interval: d.interval || null,
            deltaUsed: d.deltaUsed || null,
            deltaTime: d.deltaTime || null,
            show: d.show || { percentage: false, interval: false, deltaUsed: false, deltaTime: false },
            message: d.message || null,
            stale: Boolean(status.stale),
            refreshing: Boolean(status.refreshing)
        };
    }

    /** Whether the status field of a history row (the fifth) says `word`: start, failed, or both as start-failed. */
    function hasStatus(row, word) {
        return typeof row[4] === 'string' && row[4].split('-').indexOf(word) !== -1;
    }

    /** Whether a log line is the one that records the start of a run. */
    function isRunStart(line) {
        return /\] Starting java-aip-usage( |$)/.test(line);
    }

    /** Whether a log line begins an entry: it starts with its time, `2026-10-08 16:24:53`. The others continue the one before. */
    function beginsEntry(line) {
        return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} /.test(line);
    }

    /**
     * What the log panel shows, from GET /api/log, which sends the lines oldest first: the same
     * lines, newest entry first, each a row of one cell. An entry can take several lines (a response's JSON,
     * a stack trace); they stay together and in the order they were written, so only the entries are reversed.
     * @returns {note, header, rows, marks}: a line of explanation or null, no header, the rows, and which are marked
     */
    function describeLog(data) {
        if (!data.exists) {
            return { note: 'There is no log file yet.', header: null, rows: [] };
        }
        if (data.lines.length === 0) {
            return { note: 'The log is empty.', header: null, rows: [] };
        }
        var entries = [];
        data.lines.forEach(function (line) {
            if (beginsEntry(line) || entries.length === 0) {
                entries.push([line]);
            } else {
                entries[entries.length - 1].push(line);
            }
        });
        var ordered = [].concat.apply([], entries.reverse());
        return {
            note: data.truncated ? 'Showing the newest ' + data.lines.length + ' lines of the log.' : null,
            header: null,
            rows: ordered.map(function (line) { return [line]; }),
            marks: ordered.map(isRunStart)
        };
    }

    /**
     * What the history panel shows, from GET /api/history: a table with the file's columns as its
     * header and a row of cells for each reading, newest first as the backend sorted them.
     * @returns {note, header, rows, marks}
     */
    function describeHistory(data) {
        if (!data.exists) {
            return { note: 'There is no usage history yet.', header: null, rows: [] };
        }
        if (data.total === 0) {
            return { note: 'The history has no rows yet.', header: null, rows: [] };
        }
        // The two change columns come last, when the settings switch them on; their texts are the backend's.
        var show = data.show || {};
        var header = data.columns.slice();
        if (show.deltaUsed) {
            header.push('delta used');
        }
        if (show.deltaTime) {
            header.push('delta time');
        }
        return {
            note: data.rows.length < data.total ? 'Showing the newest ' + data.rows.length + ' of ' + data.total + ' rows.' : null,
            header: header,
            // A time with the date is wider than one without.
            wide: Boolean(show.date),
            // The first line of a run says so when hovered.
            titles: data.rows.map(function (row) { return hasStatus(row, 'start') ? (data.startTooltip || '') : ''; }),
            // The fifth field is the row's status: `start` and `start-failed` begin a run and are marked,
            // `failed` and `start-failed` are queries that did not succeed. The status, the interval and the
            // duration are not shown; a failed row says so in the place of its amount.
            rows: data.rows.map(function (row, i) {
                var cells = row.slice(0, data.columns.length);
                if (hasStatus(row, 'failed')) {
                    cells[1] = 'failed';
                }
                var delta = (data.deltas || [])[i] || {};
                if (show.deltaUsed) {
                    cells.push(delta.delta_used_text || '');
                }
                if (show.deltaTime) {
                    cells.push(delta.delta_time_text || '');
                }
                return cells;
            }),
            marks: data.rows.map(function (row) { return hasStatus(row, 'start'); }),
            failed: data.rows.map(function (row) { return hasStatus(row, 'failed'); })
        };
    }

    /**
     * What the error log panel shows, from GET /api/errors: the errors of this run, newest first as the backend
     * sent them, in two columns.
     * @returns {note, header, rows}
     */
    function describeErrors(data) {
        if (!data.entries || data.entries.length === 0) {
            return { note: 'There are no errors in this run.', header: null, rows: [] };
        }
        return {
            note: null,
            header: ['time', 'message'],
            rows: data.entries.map(function (entry) { return [entry.time, entry.message]; })
        };
    }

    root.UsageView = {
        describeStatus: describeStatus,
        describeLog: describeLog,
        describeErrors: describeErrors,
        describeHistory: describeHistory
    };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = root.UsageView;
    }
})(typeof window !== 'undefined' ? window : globalThis);
