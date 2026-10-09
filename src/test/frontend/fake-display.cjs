'use strict';

// A stand-in for the backend's `display` block in the page tests. The real backend works these texts
// out in Java (StatusDisplay, Formatting, with their own tests); the page tests only need a status
// document that has one, so this derives it from the raw fields the same way, in the UTC zone the tests run in.
process.env.TZ = 'UTC';

(function () {
    var SEVERITY_KINDS = {
        normal: 'normal',
        warning: 'warning',
        warn: 'warning',
        critical: 'critical',
        exceeded: 'critical',
        error: 'critical'
    };

    var DASH = '—';

    function pad(n) {
        return n < 10 ? '0' + n : String(n);
    }

    /**
     * The local time of day of an ISO instant, as 24-hour HH:MM:SS, never the
     * date. Anything unparseable is shown as received.
     */
    function formatTime(iso) {
        var date = new Date(iso);
        if (isNaN(date.getTime())) {
            return String(iso);
        }
        return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
    }

    var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    /**
     * The full local date and time, as "8 Oct 2026, 14:24:53": day, month, year and
     * 24-hour time, the same whatever the machine's language. It appears only in the
     * tooltip on the time, which is the one place a date is shown.
     */
    function formatDateTime(iso) {
        var date = new Date(iso);
        if (isNaN(date.getTime())) {
            return String(iso);
        }
        return date.getDate() + ' ' + MONTHS[date.getMonth()] + ' ' + date.getFullYear() + ', ' + formatTime(iso);
    }

    /** "5 s", "3 min", "2 h 5 min", "1 d 4 h": a span of time, coarse on purpose. */
    function formatSpan(ms) {
        var seconds = Math.max(0, Math.floor(ms / 1000));
        if (seconds < 60) {
            return seconds + ' s';
        }
        var minutes = Math.floor(seconds / 60);
        if (minutes < 60) {
            return minutes + ' min';
        }
        var hours = Math.floor(minutes / 60);
        if (hours < 24) {
            return hours + ' h' + (minutes % 60 ? ' ' + (minutes % 60) + ' min' : '');
        }
        var days = Math.floor(hours / 24);
        return days + ' d' + (hours % 24 ? ' ' + (hours % 24) + ' h' : '');
    }

    /** An amount as a plain number with two decimals and no currency sign: 1000 reads "1,000.00". */
    function formatAmount(amount, options) {
        if (amount === null || amount === undefined) {
            return DASH;
        }
        try {
            return new Intl.NumberFormat((options || {}).locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
        } catch (e) {
            return amount.toFixed(2);
        }
    }

    /** 12.34 reads "12.3%", 80 reads "80%". */
    function formatPercent(value) {
        return (Math.round(value * 10) / 10) + '%';
    }

    function severityKind(severity) {
        var key = typeof severity === 'string' ? severity.toLowerCase() : '';
        return Object.prototype.hasOwnProperty.call(SEVERITY_KINDS, key) ? SEVERITY_KINDS[key] : 'other';
    }

    /**
     * The numbers carry no currency sign, so the tooltips say what they are and in
     * what unit. The unit is the currency code the response names; without one the
     * tooltips say what the numbers are and leave the unit out.
     */
    function describeSpend(spend, options) {
        var unit = spend.currency ? ', in ' + spend.currency : '';
        var percentText = typeof spend.percent === 'number' ? spend.percent + '%' : null;
        return {
            percentText: percentText,
            percentTooltip: (percentText ? percentText + ' of the budget spent' : 'Share of the budget spent') +
                (spend.severity ? '. Severity: ' + spend.severity : ''),
            used: formatAmount(spend.used, options),
            limit: formatAmount(spend.limit, options),
            usedTooltip: 'Credits used' + unit,
            limitTooltip: 'Credit budget' + unit,
            severityText: spend.severity || null,
            severityKind: spend.severity ? severityKind(spend.severity) : null
        };
    }

    /**
     * The seconds until the next refresh, exactly as the backend worked them out,
     * negative once the refresh is overdue. Nothing is counted here.
     */
    function describeCountdown(seconds) {
        if (typeof seconds !== 'number' || !isFinite(seconds)) {
            return null;
        }
        return {
            text: Math.round(seconds) + ' s',
            tooltip: 'Seconds until the next refresh (negative when overdue)'
        };
    }

    /**
     * A plan window: its name as received, its utilization, and how long until it
     * resets. Remaining time rather than a clock time, because a reset can be days
     * away and a time of day alone would mislead.
     */
    function describeWindow(window, nowMs) {
        var resets;
        if (window.resets_at === null || window.resets_at === undefined) {
            resets = 'reset unknown';
        } else {
            var at = new Date(window.resets_at).getTime();
            if (isNaN(at)) {
                resets = 'resets ' + window.resets_at;
            } else if (at > nowMs) {
                resets = 'in ' + formatSpan(at - nowMs);
            } else {
                resets = 'reset due';
            }
        }
        return {
            name: window.window,
            utilizationText: formatPercent(window.utilization),
            resetsText: resets
        };
    }

    /**
     * @param status the document from GET /api/status
     * @param nowMs the current time in milliseconds
     * @param options {locale}, optional; only the number format depends on it
     * @returns what the strip shows:
     *   time         local time of day of the last reading, or null
     *   timeTooltip  "Last update: " and the full date and time, or null
     *   spend        {percentText, percentTooltip, used, limit, usedTooltip, limitTooltip,
     *                 severityText, severityKind}, or null
     *   windows      [{name, utilizationText, resetsText}]
     *   placeholder  text for a row with nothing else to show, or null
     *   countdown    {text, tooltip} for the seconds to the next refresh, or null
     *   message      {kind: 'error'|'stale', text} for the line under the row, or null
     *   stale        the figures predate a failed refresh
     *   refreshing   a refresh is running
     */
    function describeStatus(status, nowMs, options) {
        var usage = status.usage;
        var error = status.error;
        var view = {
            time: null,
            timeTooltip: null,
            spend: null,
            windows: [],
            placeholder: null,
            countdown: describeCountdown(status.nextRefreshInSeconds),
            message: null,
            stale: Boolean(usage && error),
            refreshing: Boolean(status.refreshing)
        };

        if (usage) {
            view.time = formatTime(usage.fetched_at);
            view.timeTooltip = 'Last update: ' + formatDateTime(usage.fetched_at);
            if (usage.spend) {
                view.spend = describeSpend(usage.spend, options);
            }
            view.windows = (usage.windows || []).map(function (w) {
                return describeWindow(w, nowMs);
            });
            if (!view.spend && view.windows.length === 0) {
                view.placeholder = 'No usage reported';
            }
        } else {
            view.placeholder = error ? 'No data' : 'Loading\u2026';
        }

        if (error) {
            view.message = {
                kind: usage ? 'stale' : 'error',
                text: 'Refresh failed at ' + formatTime(error.at) + ': ' + error.message
            };
        }
        return view;
    }


    function withDisplay(status) {
        if (status.display) {
            return status;
        }
        var d = describeStatus(status, Date.now(), { locale: 'en-US' });
        return Object.assign({}, status, {
            display: {
                time: d.time, timeTooltip: d.timeTooltip, spend: d.spend, windows: d.windows, placeholder: d.placeholder,
                countdown: d.countdown, deltaUsed: null, deltaTime: null,
                show: { countdown: true, deltaUsed: false, deltaTime: false }, message: d.message
            }
        });
    }

    module.exports = { withDisplay: withDisplay };
})();
