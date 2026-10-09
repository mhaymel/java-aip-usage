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
     *   show         {percentage, currency, interval, deltaUsed, deltaTime, historyIcon, logIcon, errorIcon}: which optional items and buttons there are
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
            show: d.show || { percentage: false, currency: false, interval: false, deltaUsed: false, deltaTime: false, historyIcon: true, logIcon: true, errorIcon: true },
            message: d.message || null,
            stale: Boolean(status.stale),
            refreshing: Boolean(status.refreshing)
        };
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
     * What the history panel shows, from GET /api/history. The backend has finished every line (its cells, whether it begins a run,
     * whether it failed, its hover text) and the one line of explanation, so this only reads them into the shape the page
     * draws: nothing is worked out, sorted or chosen here.
     * @returns {note, noteHighlight, header, wide, rows, marks, failed, titles}
     */
    function describeHistory(data) {
        var lines = data.lines || [];
        return {
            note: data.note || null,
            // The backend says whether the note is the one that tells something is left out.
            noteHighlight: Boolean(data.noteHighlight),
            header: lines.length > 0 ? data.columns : null,
            // A time with the date is wider than one without.
            wide: Boolean(data.wide),
            rows: lines.map(function (line) { return line.cells; }),
            marks: lines.map(function (line) { return Boolean(line.start); }),
            failed: lines.map(function (line) { return Boolean(line.failed); }),
            titles: lines.map(function (line) { return line.title || ''; })
        };
    }

    /**
     * What the error log panel shows, from GET /api/errors: the errors of this run, newest first as the backend
     * sent them, in two columns.
     * @returns {note, header, rows}
     */
    function describeErrors(data) {
        if (!data.entries || data.entries.length === 0) {
            return { note: 'There are no errors in this run.', noteHighlight: true, header: null, rows: [] };
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
