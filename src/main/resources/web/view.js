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
     *                 severityText}, or null
     *   placeholder  why the row has no amounts, `No usage reported` or `No data`, or null; the row shows gray boxes, and says it in their tooltips
     *   loading      no reading and no failure yet: the elements that are switched on are drawn as gray boxes
     *   variants     {timeShort, timeLong, usedPlain, usedSymbol, limitPlain, limitSymbol} for the preview of the settings, or null
     *   countdown    {text, tooltip} for the seconds to the next refresh, or null
     *   countdownAlert  the message of an HTTP 429, for the red countdown and its hover line, or null
     *   countdownAuto   whether the automatic interval is on, which makes the countdown blue unless it is red
     *   interval     {text, tooltip} for the time between usage requests
     *   deltaUsed    {text, tooltip} for the change in the amount used, or null
     *   deltaTime    {text, tooltip} for the time since the previous reading, or null
     *   duration     {text, tooltip} for how long the latest usage request took, or null
     *   show         {percentage, currency, interval, deltaUsed, deltaTime, historyIcon, logIcon, errorIcon, costIcon, time, used, limit,
     *                 refresh, countdown, duration}: which elements of the row there are; one the backend does not name is shown
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
            placeholder: d.placeholder || null,
            loading: Boolean(d.loading),
            variants: d.variants || null,
            countdown: d.countdown || null,
            countdownAlert: d.countdownAlert || null,
            countdownAuto: Boolean(d.countdownAuto),
            interval: d.interval || null,
            deltaUsed: d.deltaUsed || null,
            deltaTime: d.deltaTime || null,
            duration: d.duration || null,
            show: d.show || { percentage: false, currency: false, interval: false, deltaUsed: false, deltaTime: false, historyIcon: true, logIcon: true, errorIcon: true, costIcon: true,
                status: true, dayPercentage: false, dayUsed: false, dayLimit: false },
            message: d.message || null,
            // What is said of a reading with no spend, while the mouse is on one of its gray boxes; null for any other reading.
            noSpend: d.noSpend || null,
            // Whether the setting gives the window its title bar; when the backend does not say, it has one.
            titleBar: d.titleBar !== false,
            stale: Boolean(status.stale),
            refreshing: Boolean(status.refreshing)
        };
    }

    /** Whether a log line is the one that records the start of a run. */
    function isRunStart(line) {
        return /\] Starting java-aip-usage( |$)/.test(line);
    }

    /** How much of a line that begins an entry is its date, with the blank after it: `2026-10-08 `. */
    var DATE_LENGTH = 11;

    /** Whether a log line begins an entry: it starts with its time, `2026-10-08 16:24:53`. The others continue the one before. */
    function beginsEntry(line) {
        return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} /.test(line);
    }

    /**
     * What the log panel shows, from GET /api/log, which sends the lines oldest first: the same
     * lines, newest entry first, each a row of one cell. An entry can take several lines (a response's JSON,
     * a stack trace); they stay together and in the order they were written, so only the entries are reversed.
     * With `date` false the date is cut from the line that begins an entry, which then begins with its time; the lines that
     * follow it have none and are as written. `hidden` says that the file has lines and none of the level that was asked for.
     * @returns {note, noteHighlight, header, rows, marks}: a line of explanation or null, no header, the rows, and which are marked
     */
    function describeLog(data) {
        if (!data.exists) {
            return { note: 'There is no log file yet.', header: null, rows: [] };
        }
        if (data.lines.length === 0) {
            return data.hidden
                ? { note: 'The log has no lines at this level.', noteHighlight: true, header: null, rows: [] }
                : { note: 'The log is empty.', header: null, rows: [] };
        }
        var cut = data.date === false ? DATE_LENGTH : 0;
        var entries = [];
        // Each line with its place in what was sent, so that what the backend said of a line stays with it when the order is turned.
        data.lines.forEach(function (line, index) {
            var item = { text: line, index: index };
            if (beginsEntry(line) || entries.length === 0) {
                entries.push([item]);
            } else {
                entries[entries.length - 1].push(item);
            }
        });
        var ordered = [].concat.apply([], entries.reverse());
        // The paths the backend says are files of the program's own, for each line, moved by what is cut from its beginning.
        var linked = {};
        (data.links || []).forEach(function (link) {
            (linked[link.line] = linked[link.line] || []).push(link);
        });
        var linksOf = function (item) {
            var moved = cut && beginsEntry(item.text) ? cut : 0;
            return (linked[item.index] || [])
                .filter(function (link) { return link.from >= moved && link.to > link.from && link.to <= item.text.length; })
                .map(function (link) { return { from: link.from - moved, to: link.to - moved, file: link.file }; });
        };
        return {
            note: data.truncated ? 'Showing the newest ' + data.lines.length + ' lines of the log.' : null,
            // It says that not everything is shown, like the history's line: in the blue of such notes.
            noteHighlight: Boolean(data.truncated),
            header: null,
            rows: ordered.map(function (item) { return [cut && beginsEntry(item.text) ? item.text.slice(cut) : item.text]; }),
            links: ordered.map(linksOf),
            marks: ordered.map(function (item) { return isRunStart(item.text); }),
            // The log's start line has a light gray background; the history's is in green text (see describeHistory).
            markClass: 'mark'
        };
    }

    /**
     * What the history panel shows, from GET /api/history. The backend has finished every line (its cells, whether it begins a run,
     * whether it failed, its hover text) and the one line of explanation, so this only reads them into the shape the page
     * draws: nothing is worked out, sorted or chosen here.
     * @returns {note, noteHighlight, header, wide, rows, marks, markClass, failed, titles}
     */
    function describeHistory(data) {
        var lines = data.lines || [];
        return {
            note: data.note || null,
            // The backend says whether the note is the one that tells something is left out.
            noteHighlight: Boolean(data.noteHighlight),
            header: lines.length > 0 ? data.columns : null,
            // What each column is, as the backend names it: a column has its width by that, whichever columns are shown beside it.
            kinds: data.kinds || null,
            // The tooltip of each title, as the backend wrote it.
            columnTitles: data.columnTitles || null,
            // A time with the date is wider than one without.
            wide: Boolean(data.wide),
            rows: lines.map(function (line) { return line.cells; }),
            marks: lines.map(function (line) { return Boolean(line.start); }),
            // The first line of a run is drawn in green text, with no background of its own.
            markClass: 'start',
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

    /**
     * The two bars of the cost control, as the backend finished them: each row has the label, the figures as text, its hover text, the
     * share of the bar that is green (0 to 1), whether the limit is exceeded and whether there is no limit; and a note, or null.
     */
    function describeCost(data) {
        var rows = (data.rows || []).map(function (row) {
            var fraction = Number(row.fraction);
            return {
                label: row.label || '',
                text: row.text || '',
                tooltip: row.tooltip || '',
                fraction: Math.min(1, Math.max(0, isFinite(fraction) ? fraction : 0)),
                over: Boolean(row.over),
                noLimit: Boolean(row.noLimit),
                // The colour of the bar (since 0.82): one of the four, and green for anything else.
                state: ['green', 'amber', 'red', 'gray'].indexOf(row.state) >= 0 ? row.state : 'green',
                // The parts of the figures, each in a column of its own (since 0.83); without them the text is one cell.
                used: typeof row.used === 'string' ? row.used : null,
                limit: typeof row.limit === 'string' ? row.limit : null,
                percent: typeof row.percent === 'string' ? row.percent : null
            };
        });
        return { rows: rows, note: data.note || null };
    }

    root.UsageView = {
        describeCost: describeCost,
        describeStatus: describeStatus,
        describeLog: describeLog,
        describeErrors: describeErrors,
        describeHistory: describeHistory
    };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = root.UsageView;
    }
})(typeof window !== 'undefined' ? window : globalThis);
