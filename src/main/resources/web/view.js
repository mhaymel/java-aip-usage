// Turns the backend's status document into what the page shows. Pure functions
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

    function pad(n) {
        return n < 10 ? '0' + n : String(n);
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

    function formatAge(ms) {
        return ms < 5000 ? 'just now' : formatSpan(ms) + ' ago';
    }

    /** The local date and time of an ISO instant; anything unparseable is shown as received. */
    function formatDateTime(iso, options) {
        var date = new Date(iso);
        if (isNaN(date.getTime())) {
            return String(iso);
        }
        var opts = options || {};
        try {
            return date.toLocaleString(opts.locale, {
                dateStyle: 'medium',
                timeStyle: 'medium',
                timeZone: opts.timeZone
            });
        } catch (e) {
            return date.toLocaleString();
        }
    }

    function formatMoney(amount, currency, options) {
        if (amount === null || amount === undefined) {
            return '—';
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

    function clampPercent(value) {
        return Math.min(100, Math.max(0, value));
    }

    function severityKind(severity) {
        var key = typeof severity === 'string' ? severity.toLowerCase() : '';
        return Object.prototype.hasOwnProperty.call(SEVERITY_KINDS, key) ? SEVERITY_KINDS[key] : 'other';
    }

    function describeSpend(spend, options) {
        var hasPercent = typeof spend.percent === 'number';
        return {
            used: formatMoney(spend.used, spend.currency, options),
            limit: formatMoney(spend.limit, spend.currency, options),
            currency: spend.currency || null,
            percentText: hasPercent ? spend.percent + '%' : '—',
            barPercent: hasPercent ? clampPercent(spend.percent) : null,
            severityText: spend.severity || null,
            severityKind: spend.severity ? severityKind(spend.severity) : null
        };
    }

    function describeWindow(window, nowMs, options) {
        var resets;
        if (window.resets_at === null || window.resets_at === undefined) {
            resets = 'Reset time unknown';
        } else {
            var at = new Date(window.resets_at).getTime();
            var when = formatDateTime(window.resets_at, options);
            if (isNaN(at)) {
                resets = 'Resets at ' + when;
            } else if (at > nowMs) {
                resets = 'Resets in ' + formatSpan(at - nowMs) + ' (' + when + ')';
            } else {
                resets = 'Reset was due ' + when;
            }
        }
        return {
            name: window.window,
            utilizationText: formatPercent(window.utilization),
            barPercent: clampPercent(window.utilization),
            resetsText: resets
        };
    }

    /**
     * @param status the document from GET /api/status
     * @param nowMs the current time in milliseconds
     * @param options {locale, timeZone}, both optional
     */
    function describeStatus(status, nowMs, options) {
        var usage = status.usage;
        var error = status.error;
        var view = {
            badge: { text: 'Loading…', kind: 'loading' },
            fetched: null,
            notice: null,
            spend: null,
            windows: [],
            empty: false,
            refreshing: Boolean(status.refreshing)
        };

        if (usage) {
            view.fetched = 'Fetched ' + formatDateTime(usage.fetched_at, options) +
                ' (' + formatAge(nowMs - new Date(usage.fetched_at).getTime()) + ')';
            if (usage.spend) {
                view.spend = describeSpend(usage.spend, options);
            }
            view.windows = (usage.windows || []).map(function (w) {
                return describeWindow(w, nowMs, options);
            });
            view.empty = !usage.spend && view.windows.length === 0;
        }

        if (usage && error) {
            view.badge = { text: 'Stale', kind: 'stale' };
            view.notice = {
                kind: 'stale',
                title: 'The latest refresh failed, so this is the last successful reading.',
                detail: error.message,
                at: formatDateTime(error.at, options)
            };
        } else if (usage) {
            view.badge = { text: 'Up to date', kind: 'ok' };
        } else if (error) {
            view.badge = { text: 'Error', kind: 'error' };
            view.notice = {
                kind: 'error',
                title: 'No usage could be read yet.',
                detail: error.message,
                at: formatDateTime(error.at, options)
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
        formatSpan: formatSpan,
        formatAge: formatAge,
        formatPercent: formatPercent,
        formatMoney: formatMoney,
        severityKind: severityKind
    };
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = root.UsageView;
    }
})(typeof window !== 'undefined' ? window : globalThis);
