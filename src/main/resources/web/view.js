// Turns the backend's status document into what the strip shows. Pure functions
// with no DOM access, so they run under Node as well as in the browser.
(function (root) {
    'use strict';

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

    function formatMoney(amount, currency, options) {
        if (amount === null || amount === undefined) {
            return DASH;
        }
        if (currency) {
            try {
                return new Intl.NumberFormat((options || {}).locale, { style: 'currency', currency: currency }).format(amount);
            } catch (e) {
                return amount.toFixed(2) + ' ' + currency;
            }
        }
        return amount.toFixed(2);
    }

    /** 12.34 reads "12.3%", 80 reads "80%". */
    function formatPercent(value) {
        return (Math.round(value * 10) / 10) + '%';
    }

    function severityKind(severity) {
        var key = typeof severity === 'string' ? severity.toLowerCase() : '';
        return Object.prototype.hasOwnProperty.call(SEVERITY_KINDS, key) ? SEVERITY_KINDS[key] : 'other';
    }

    function describeSpend(spend, options) {
        return {
            amounts: formatMoney(spend.used, spend.currency, options) + ' / ' + formatMoney(spend.limit, spend.currency, options),
            percentText: typeof spend.percent === 'number' ? spend.percent + '%' : null,
            severityText: spend.severity || null,
            severityKind: spend.severity ? severityKind(spend.severity) : null
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
     * @param options {locale}, optional; only the currency format depends on it
     * @returns what the strip shows:
     *   time        local time of day of the last reading, or null
     *   spend       {amounts, percentText, severityText, severityKind}, or null
     *   windows     [{name, utilizationText, resetsText}]
     *   placeholder text for a row with nothing else to show, or null
     *   message     {kind: 'error'|'stale', text} for the line under the row, or null
     *   stale       the figures predate a failed refresh
     *   refreshing  a refresh is running
     */
    function describeStatus(status, nowMs, options) {
        var usage = status.usage;
        var error = status.error;
        var view = {
            time: null,
            spend: null,
            windows: [],
            placeholder: null,
            message: null,
            stale: Boolean(usage && error),
            refreshing: Boolean(status.refreshing)
        };

        if (usage) {
            view.time = formatTime(usage.fetched_at);
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
            view.placeholder = error ? 'No data' : 'Loading…';
        }

        if (error) {
            view.message = {
                kind: usage ? 'stale' : 'error',
                text: 'Refresh failed at ' + formatTime(error.at) + ': ' + error.message
            };
        }
        return view;
    }

    /** Checks a typed interval against the limits the backend reported. Returns an error text, or null. */
    function checkInterval(text, limits, label) {
        var trimmed = String(text).trim();
        if (!/^[0-9]+$/.test(trimmed)) {
            return label + ' must be a whole number of seconds.';
        }
        var value = Number(trimmed);
        if (value < limits.min || value > limits.max) {
            return label + ' must be from ' + limits.min + ' to ' + limits.max + ' seconds.';
        }
        return null;
    }

    root.UsageView = {
        describeStatus: describeStatus,
        checkInterval: checkInterval,
        formatTime: formatTime,
        formatSpan: formatSpan,
        formatPercent: formatPercent,
        formatMoney: formatMoney,
        severityKind: severityKind
    };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = root.UsageView;
    }
})(typeof window !== 'undefined' ? window : globalThis);
