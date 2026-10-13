// The strip: asks the backend for its settings once, then polls its status at
// the interval it reported. What to show is decided in view.js; this file talks
// to the backend and puts text on the page. Text from the backend is only ever
// assigned with textContent, never parsed as HTML.
(function () {
    'use strict';

    var view = window.UsageView;
    var REQUEST_TIMEOUT_MS = 10000;
    var RETRY_START_MS = 2000;

    var config = null;
    var lastStatus = null;
    var pollTimer = null;
    var polling = false;
    var lastStale = false;
    // Which panel is shown below the strip: 'history', 'log', or none.
    var openPanel = null;
    var panelLoading = false;
    // The two choices of the log panel: the least severe level shown and whether the lines have their date. They are the
    // backend's, saved in the settings file; the page has them from the first answer after the panel was opened.
    var logLevel = null;
    var logDate = false;
    var logKnown = false;
    // A choice the backend could not save still holds for the panel: the level is then asked for by name.
    var logUnsaved = false;
    // After a change of either the log stands at its newest line again.
    var logToTop = false;
    // The size of the text of the three lists, in pixels: the backend's, saved in the settings file, and the page's to
    // change with the two buttons at the top of a list.
    var LIST_SIZE_MIN = 10;
    var LIST_SIZE_MAX = 20;
    var listSize = 12;
    // Which reading the history panel shows up to: a new one means it is read again.
    var panelMark = null;
    // What the panel shows, to leave it alone when a read finds nothing new.
    var panelKey = null;
    // The rows on show, to tell how many came in above what the person was reading.
    var panelRows = [];
    var panelHasHeader = false;
    // Whether the pointer is over the countdown, which then shows the message of an HTTP 429 under the row.
    var hoveringCountdown = false;
    // Which of the gray boxes of the row the mouse is on, by its id, or null: the line of a reading with no spend is
    // shown for as long as it is on one.
    var hoveringBox = null;
    // The panel that was shown when the settings were opened, which they give back when they are left; null if none.
    var returnTo = null;
    // The height the window has while a log or history is shown, and the width the vertical scrollbar of their panel takes.
    var panelHeight = 0;
    var scrollbarWidth = 0;
    // How wide the history table needs to be, as last measured; kept while the panel is closed so that it opens at once as wide as it was.
    var tableWidth = 0;
    // What the settings view shows of the row before it is applied: {show, timeFormat}, or null when the row is the backend's.
    var preview = null;

    function $(id) {
        return document.getElementById(id);
    }

    function show(id, visible) {
        $(id).hidden = !visible;
    }

    function setNote(id, text, className) {
        var note = $(id);
        note.hidden = !text;
        note.textContent = text || '';
        if (className) {
            note.className = className;
        }
    }

    // ---- talking to the backend

    async function request(path, options) {
        var controller = new AbortController();
        var timer = setTimeout(function () { controller.abort(); }, REQUEST_TIMEOUT_MS);
        try {
            var response = await fetch(path, Object.assign({ cache: 'no-store', signal: controller.signal }, options));
            var body = null;
            try {
                body = await response.json();
            } catch (ignored) {
                // Not JSON; the status code below is all there is to report.
            }
            if (!response.ok) {
                throw new Error(body && body.error ? body.error : 'The application answered HTTP ' + response.status + '.');
            }
            return body;
        } finally {
            clearTimeout(timer);
        }
    }

    function postJson(path, payload) {
        return request(path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
    }

    // ---- polling

    async function poll() {
        if (polling) {
            return;
        }
        polling = true;
        try {
            lastStatus = await request('/api/status');
            // What was applied is in this status; a preview is for the open settings only.
            if (openPanel !== 'settings') {
                preview = null;
            }
            showConnection(false);
            render();
            refreshPanel();
        } catch (e) {
            showConnection(true);
        } finally {
            polling = false;
            scheduleNextPoll();
        }
    }

    function scheduleNextPoll() {
        clearTimeout(pollTimer);
        pollTimer = setTimeout(poll, config.pollIntervalSeconds * 1000);
    }

    function pollNow() {
        clearTimeout(pollTimer);
        poll();
    }

    // ---- showing the status

    function render() {
        if (!lastStatus) {
            return;
        }
        var v = view.describeStatus(lastStatus);
        // The settings view shows a change in the row before it is applied; then its switches count, not the backend's.
        var s = preview ? preview.show : v.show;
        var variants = preview && v.variants ? v.variants : null;
        var loading = v.loading;

        lastStale = v.stale;
        applyAppClass();
        show('refresh', on(s.refresh));
        $('refresh').className = 'icon' + (v.refreshing ? ' busy' : '');

        // With no usage reported, and with no reading at all, the row has no word for it: the figures that are switched on and
        // have no value are gray boxes, as at the start, and their tooltips say which of the two it is.
        var why = v.placeholder ? ': ' + v.placeholder.charAt(0).toLowerCase() + v.placeholder.slice(1) : '';
        var boxes = loading || Boolean(v.placeholder);

        var timeText = variants ? (preview.timeFormat === 'hh:mm:ss' ? variants.timeLong : variants.timeShort) : v.time;
        figure('time', 'time', on(s.time) && (boxes || Boolean(timeText)), timeText, timeText ? v.timeTooltip || 'Last update' : 'Last update' + why,
            boxes && !timeText);

        var spend = v.spend;
        var spendBoxes = boxes && !spend;
        var percentShown = Boolean(s.percentage) && (spendBoxes || Boolean(spend && spend.percentText));
        var usedShown = on(s.used) && (spendBoxes || Boolean(spend));
        var limitShown = on(s.limit) && (spendBoxes || Boolean(spend));
        show('spend', percentShown || usedShown || limitShown);
        // Boxes are not dimmed with the old figures, and the dimming is the whole group's.
        $('spend').className = 'spend' + (spendBoxes ? ' boxes' : '');
        figure('percent', 'percent', percentShown, spend && spend.percentText, spend ? spend.percentTooltip : 'Share of the budget spent' + why, spendBoxes);
        figure('used', 'used', usedShown, spend && amount(spend.used, variants, 'used', s.currency), spend ? spend.usedTooltip : 'Credits used' + why, spendBoxes);
        figure('limit', 'limit', limitShown, spend && amount(spend.limit, variants, 'limit', s.currency), spend ? spend.limitTooltip : 'Credit budget' + why, spendBoxes);
        show('of', usedShown && limitShown);

        // While the server asks us to slow down (HTTP 429) the countdown is red and, hovered, says why in bold red.
        figure('countdown', 'countdown' + (v.countdownAlert ? ' alert' : v.countdownAuto ? ' auto' : ''), on(s.countdown) && (loading || Boolean(v.countdown)),
            v.countdown && v.countdown.text, v.countdown ? v.countdown.tooltip : 'Seconds until the next refresh',
            loading && !v.countdown);
        setNote('alert-note', hoveringCountdown && v.countdownAlert ? v.countdownAlert : '');
        // A reading that came and has no spend says so while the mouse is on one of its gray boxes, and not otherwise.
        var onBox = hoveringBox !== null && !$(hoveringBox).hidden && /\bskeleton\b/.test($(hoveringBox).className);
        setNote('hover-note', onBox && v.noSpend ? v.noSpend : '', 'note note-info');
        renderOptional('interval', s.interval, v.interval, 'Time between usage requests');
        renderButtons(s);
        renderOptional('delta-used', s.deltaUsed, v.deltaUsed, 'Change in the amount used since the previous reading');
        renderOptional('delta-time', s.deltaTime, v.deltaTime, 'Time since the previous reading');
        renderOptional('duration', s.duration, v.duration, 'How long the latest usage request took');

        setNote('note', v.message && v.message.text, v.message ? 'note note-' + v.message.kind : null);
        markNoted();
        // With the settings shown and Automatic checked, the field of the interval follows the search.
        if (openPanel === 'settings' && settingsShown !== null && $('set-autoInterval').checked) {
            syncIntervalField();
        }
    }

    /**
     * Says on the row's box whether a message line is shown below the row, any of the three: the style sheet gives the
     * window a least width then, when no panel is shown, so that a message is not broken into a column as narrow as a row
     * of few elements.
     */
    function markNoted() {
        var noted = ['note', 'alert-note', 'hover-note', 'connection'].some(function (id) { return !$(id).hidden; });
        $('top').className = 'top' + (noted ? ' noted' : '');
    }

    /** Shows or hides the line that says contact is lost, and what follows from a message line being there. */
    function showConnection(lost) {
        show('connection', Boolean(lost));
        markNoted();
    }

    /** A switch of the row is on unless it says otherwise: the five elements that were always there are shown when nothing is said. */
    function on(flag) {
        return flag !== false;
    }

    /**
     * A figure of the row: there or not, with its text and tooltip. Before the first reading, with no usage reported and with no
     * reading at all it is a gray box instead, with the tooltip that needs no value, so that the row always has its shape.
     */
    function figure(id, className, visible, text, tooltip, skeleton) {
        show(id, visible);
        $(id).className = className + (skeleton ? ' skeleton' : '');
        $(id).textContent = skeleton ? '\u00a0' : (text || '');
        $(id).title = tooltip || '';
    }

    /** An amount as the backend wrote it, or, while the settings preview the symbol, the one of its two forms the checkbox asks for. */
    function amount(text, variants, name, withSymbol) {
        var form = variants && variants[name + (withSymbol ? 'Symbol' : 'Plain')];
        return form === null || form === undefined ? text : form;
    }

    // The panel buttons that are shown although they are switched off, which they are while the settings are open: dimmed, by name.
    var offButtons = {};

    /**
     * The buttons of the history, the log, the error log and the cost control are there when their settings say so; the gear never
     * goes. While the settings are shown all four are there, whatever their switches say, so that a panel whose button is switched
     * off can be reached through the settings: one that is switched off, as its checkbox stands in the view, is dimmed and says in
     * its tooltip that it will go. The button of the panel that is shown is always there, so that the panel can be closed: a
     * button that is switched off is never taken away from a panel that is open.
     */
    function renderButtons(show) {
        var flags = { history: show.historyIcon, log: show.logIcon, errors: show.errorIcon, cost: show.costIcon };
        var settingsOpen = openPanel === 'settings';
        Object.keys(flags).forEach(function (name) {
            var on = flags[name] !== false;
            $(PANELS[name].button).hidden = !(on || openPanel === name || settingsOpen);
            offButtons[name] = settingsOpen && !on;
        });
        labelButtons();
    }

    // The classes of the optional items of the row, which are a gray box of their own width when they have no value.
    var OPTIONAL_CLASSES = { 'interval': 'delta delta-interval', 'delta-used': 'delta delta-used', 'delta-time': 'delta delta-time', 'duration': 'delta delta-duration' };

    /**
     * An optional item of the row. Its setting off, it is not there and takes no room. Its setting on, it is its value, or, when the
     * backend has none for it (no reading before this one, no request that has ended), a gray box with its tooltip: at the start
     * and at any time after. The box is as wide as the item's room, so the row does not change width from one refresh to the next.
     */
    function renderOptional(id, wanted, item, tooltip) {
        show(id, Boolean(wanted));
        $(id).className = OPTIONAL_CLASSES[id] + (item ? '' : ' skeleton');
        $(id).textContent = item ? item.text : '\u00a0';
        $(id).title = item ? item.tooltip : tooltip;
    }

    /** Whether the backend says that the fake backend is in use, so that the figures are invented. */
    function fakeInUse() {
        return Boolean(lastStatus && lastStatus.fake);
    }

    function applyAppClass() {
        // The log and the history fill the window; the settings do not, so that the page's own height is the content's.
        $('app').className = (lastStale ? 'stale' : '')
            + (openPanel === 'log' || openPanel === 'history' || openPanel === 'errors' ? ' open' : '')
            // The settings and the cost control are as tall as their content, which is the page's own height.
            + (FORMS[openPanel] || openPanel === 'cost' ? ' fit' : '')
            // The readings are invented while the fake backend is in use: the red border says so.
            + (fakeInUse() ? ' fake' : '')
            // A window with no title bar has no frame of the system to show where it ends: the page draws one.
            + (bare() ? ' bare' : '');
    }

    /**
     * Whether the window has no title bar and the page draws its border: the setting is off and no panel is shown, since a
     * panel brings the title bar back. Not while the red border of the fake backend is round the window.
     */
    function bare() {
        return Boolean(lastStatus && lastStatus.display && lastStatus.display.titleBar === false) && !openPanel && !fakeInUse();
    }

    // ---- the settings view: a form the backend fills in each time it is opened

    /** The settings as the backend gave them when the view was opened, to tell whether the form has unapplied changes. */
    var settingsShown = null;
    var settingsDefaults = null;
    // The interval that is saved, which the field does not show while the program finds the interval itself: it is what
    // is sent with Automatic checked, and what the field has again when Automatic is unchecked.
    var configuredInterval = null;
    // Whether the field of the interval is disabled, for Automatic: the page's own note of what it did to the field.
    var intervalLocked = false;
    // Cost control: the daily limit that was typed in, which waits while the limit is worked out; and the limit the backend
    // worked out, as a finished text. The page works no limit out itself.
    var costDailyTyped = '';
    var costDailyWorkedOut = '';
    // Counts the questions about the daily limit: an answer to an earlier one, arriving late, is not the one to show.
    var costAsked = 0;
    // The tabs of the settings, in order: the page of each is `tab-<id>`, its title in the strip `tabbtn-<id>`. The Debug tab is there
    // in debug mode only. The settings open on the tab they were last on: the one the page has shown, and, at the first opening after a
    // start, the one the backend stored (`settingsTab`), unless the person has already chosen a tab or the host has restored one.
    // General is the first tab, and the one the settings fall back to; About is the last.
    var TABS = ['general', 'main', 'history', 'cost', 'debug', 'about'];
    var currentTab = TABS[0];
    var tabChosen = false;

    // The switches of the main view, in the order of the row; the currency symbol is a property of the amounts, beside the first of them.
    var MAIN_FLAGS = ['showTime', 'showPercentage', 'showUsed', 'showCurrency', 'showLimit', 'showRefresh', 'showCountdown', 'showInterval', 'showDeltaUsed', 'showDeltaTime', 'showDuration', 'showLogIcon', 'showHistoryIcon', 'showErrorIcon', 'showCostIcon'];
    // The fourteen elements of the row that have a checkbox: the flags of Main view but the currency symbol, which is a property of the amounts.
    var ELEMENT_FLAGS = MAIN_FLAGS.filter(function (key) { return key !== 'showCurrency'; });
    // The nine checkboxes of History view: the date, the two kinds of lines, and the six columns.
    var HISTORY_FLAGS = ['historyDate', 'historyZeroLines', 'historyFailedLines', 'historyTime', 'historyUsed', 'historyLimit', 'historyDeltaUsed', 'historyDeltaTime', 'historyCurrent'];
    var SETTING_FLAGS = MAIN_FLAGS.concat(['showTitleBar', 'stayOnTop', 'restoreWindow', 'historyDate', 'historyZeroLines', 'historyFailedLines', 'historyTime', 'historyUsed', 'historyLimit', 'historyDeltaUsed', 'historyDeltaTime', 'historyCurrent', 'logResponse', 'autoInterval', 'costDailyAutomatic']);

    /**
     * Shows in the row what the main-view part of the form says, at once and before Save, so that it can be seen which element a
     * checkbox belongs to. Nothing is sent: leaving the settings without Save gives the row back as the backend has it.
     */
    function previewNow() {
        if (openPanel !== 'settings' || settingsShown === null) {
            return;
        }
        var checked = function (key) { return Boolean($('set-' + key).checked); };
        preview = {
            timeFormat: $('set-timeFormat').value,
            show: {
                time: checked('showTime'), percentage: checked('showPercentage'), used: checked('showUsed'), currency: checked('showCurrency'),
                limit: checked('showLimit'), refresh: checked('showRefresh'), countdown: checked('showCountdown'),
                interval: checked('showInterval'), deltaUsed: checked('showDeltaUsed'), deltaTime: checked('showDeltaTime'),
                duration: checked('showDuration'),
                logIcon: checked('showLogIcon'), historyIcon: checked('showHistoryIcon'), errorIcon: checked('showErrorIcon'),
                costIcon: checked('showCostIcon')
            }
        };
        render();
    }

    /**
     * The button in the heading of Main view, and the one in the heading of History view: switches every checkbox of its list
     * on, or, when every one is on already, all of them off. For Main view the list is the elements of the row: the two
     * properties, the currency symbol and the time format, are left as they are, and the row shows it at once, like a click on
     * a checkbox. History view has no preview. Either is saved when the view is.
     */
    function selectAllOrNone(flags) {
        if (openPanel !== 'settings' || settingsShown === null) {
            return;
        }
        var allOn = flags.every(function (key) { return Boolean($('set-' + key).checked); });
        flags.forEach(function (key) { $('set-' + key).checked = !allOn; });
        previewNow();
    }

    /**
     * What the backend gave of the settings, as the form is compared with it and as it is sent back: with Automatic on the
     * interval is the configured one, not the one in force, which the field only shows.
     */
    function takeSettings(body) {
        settingsShown = Object.assign({}, body.settings);
        configuredInterval = typeof body.configuredIntervalSeconds === 'number' ? body.configuredIntervalSeconds : body.settings.usageIntervalSeconds;
        if (settingsShown.autoInterval) {
            settingsShown.usageIntervalSeconds = configuredInterval;
        }
        settingsDefaults = body.defaults;
    }

    /** The interval in force as the row has it, in seconds; none while the backend has said none. */
    function intervalInForce() {
        var shown = lastStatus && lastStatus.display && lastStatus.display.interval;
        var digits = shown && /^(\d+)s$/.exec(shown.text);
        return digits ? Number(digits[1]) : null;
    }

    /**
     * The field of the interval follows the checkbox Automatic. Checked, the field is disabled and shows the interval the
     * program is using now; unchecked, it can be typed in and has the configured interval again.
     */
    function syncIntervalField() {
        var field = $('set-usageIntervalSeconds');
        var automatic = Boolean($('set-autoInterval').checked);
        var wasDisabled = intervalLocked;
        intervalLocked = automatic;
        if (automatic) {
            if (!wasDisabled && /^\d+$/.test(String(field.value).trim())) {
                // What was typed is the configured interval from now on, and is sent when the view is saved.
                configuredInterval = Number(field.value);
            }
            field.setAttribute('disabled', '');
            var inForce = intervalInForce();
            if (inForce !== null) {
                field.value = String(inForce);
            }
        } else {
            field.removeAttribute('disabled');
            if (wasDisabled && configuredInterval !== null) {
                field.value = String(configuredInterval);
            }
        }
    }

    /** The interval is one entry field for a whole number of seconds; it shows the value in force, which is what the backend has. */
    function fillInterval(current) {
        $('set-usageIntervalSeconds').value = String(current);
    }

    function fillForm(values) {
        SETTING_FLAGS.forEach(function (key) {
            $('set-' + key).checked = Boolean(values[key]);
        });
        $('set-timeFormat').value = values.timeFormat;
        $('set-usageIntervalSeconds').value = String(values.usageIntervalSeconds);
        $('set-costMonthlyLimit').value = values.costMonthlyLimit || '';
        costDailyTyped = values.costDailyLimit || '';
    }

    /**
     * The field of the daily limit follows its checkbox Automatic. Checked, it is disabled and shows the limit the backend
     * worked out; unchecked, it can be typed in and has what was typed.
     */
    function syncDailyLimit() {
        var field = $('set-costDailyLimit');
        if ($('set-costDailyAutomatic').checked) {
            field.setAttribute('disabled', '');
            field.value = costDailyWorkedOut;
        } else {
            field.removeAttribute('disabled');
            field.value = costDailyTyped;
        }
    }

    /** Asks the backend what daily limit follows from the monthly limit in the field, and shows it while the limit is worked out. */
    async function workOutDailyLimit() {
        var monthly = String($('set-costMonthlyLimit').value).trim();
        var asked = ++costAsked;
        var worked;
        try {
            worked = (await request('/api/cost/daily?monthly=' + encodeURIComponent(monthly))).daily || '';
        } catch (e) {
            // What is typed is no amount yet: there is nothing to work out, and saving says what is wrong with it.
            worked = '';
        }
        if (asked !== costAsked) {
            // The field was changed again meanwhile: the answer to that is the one that counts.
            return;
        }
        costDailyWorkedOut = worked;
        if ($('set-costDailyAutomatic').checked) {
            syncDailyLimit();
        }
    }

    /** The checkbox Automatic of the daily limit was changed: what was typed is kept, and the field shows the one or the other. */
    function dailyAutomaticChanged() {
        if ($('set-costDailyAutomatic').checked) {
            costDailyTyped = String($('set-costDailyLimit').value).trim();
            syncDailyLimit();
            return workOutDailyLimit();
        }
        syncDailyLimit();
        return undefined;
    }

    function readForm() {
        var values = {
            // With Automatic checked the field shows the interval the program found; the configured one is what is kept.
            usageIntervalSeconds: $('set-autoInterval').checked && configuredInterval !== null ? configuredInterval : Number($('set-usageIntervalSeconds').value),
            timeFormat: $('set-timeFormat').value
        };
        SETTING_FLAGS.forEach(function (key) {
            values[key] = $('set-' + key).checked;
        });
        // The limits of cost control go as they were typed, and an empty field is no limit. While the daily limit is worked
        // out, the one that was typed before is what is kept.
        var monthly = String($('set-costMonthlyLimit').value).trim();
        var daily = values.costDailyAutomatic ? costDailyTyped : String($('set-costDailyLimit').value).trim();
        values.costMonthlyLimit = monthly === '' ? null : monthly;
        values.costDailyLimit = daily === '' ? null : daily;
        return values;
    }

    /** Reads the settings from the backend and fills the form; the form is hidden, with the reason, if they cannot be read. */
    async function loadSettings() {
        setNote('settings-error', '');
        try {
            var body = await request('/api/settings');
            takeSettings(body);
            if (!tabChosen) {
                // The first opening after a start: the tab the settings were last on, stored by the backend.
                tabChosen = true;
                if (TABS.indexOf(body.settingsTab) >= 0) {
                    currentTab = body.settingsTab;
                }
            }
            fillInterval(body.settings.usageIntervalSeconds);
            fillForm(body.settings);
            // The field is enabled while the form is filled, so that the checkbox alone says how it is afterwards.
            $('set-usageIntervalSeconds').removeAttribute('disabled');
            intervalLocked = Boolean(body.settings.autoInterval);
            syncIntervalField();
            costDailyWorkedOut = body.costDailyWorkedOut || '';
            syncDailyLimit();
            show('settings-form', true);
        } catch (e) {
            // The frontend keeps no settings of its own, so with none to show there is no form.
            settingsShown = null;
            settingsDefaults = null;
            show('settings-form', false);
            setNote('settings-error', 'The settings could not be read: ' + e.message);
        }
        if (config && config.debug) {
            await loadDebug();
        }
        setApplyEnabled(settingsShown !== null);
        showTab(currentTab);
    }

    function setApplyEnabled(enabled) {
        if (enabled) {
            $('settings-save').removeAttribute('disabled');
        } else {
            $('settings-save').setAttribute('disabled', '');
        }
    }

    /** Whether the form holds anything other than what the backend gave when the view was opened or last saved. */
    function settingsChanged() {
        if (settingsShown === null) {
            return false;
        }
        var form = readForm();
        return Object.keys(form).some(function (key) { return form[key] !== settingsShown[key]; });
    }

    /**
     * Sends what the form holds to the backend, which keeps and saves it. True when that was done; when a value is refused or the
     * settings cannot be saved it says why in red and is false, and the caller leaves the view open.
     */
    async function saveSettings() {
        // Only that it is a number is checked here; whether it is in range is the backend's to say.
        if (!$('set-autoInterval').checked && !/^\d+$/.test($('set-usageIntervalSeconds').value.trim())) {
            setNote('settings-error', 'The interval must be a whole number of seconds.');
            return false;
        }
        try {
            var body = await postJson('/api/settings', readForm());
            takeSettings(body);
            // The panel the settings replaced is given back whatever its switch now says: its button stays until it is closed.
            return true;
        } catch (e) {
            setNote('settings-error', e.message);
            return false;
        }
    }

    /** Whether anything in the settings, or in the debug settings of the Debug tab, was changed since they were opened or last saved. */
    function formChanged() {
        return settingsChanged() || debugChanged();
    }

    /**
     * Saves the settings and, in debug mode, the debug settings of the Debug tab. False, with the reason in red, when something was
     * refused; a failure of the debug settings shows the Debug tab, where it is said.
     */
    async function saveAll(onlyWhatChanged) {
        if (!onlyWhatChanged || settingsChanged()) {
            if (!(await saveSettings())) {
                return false;
            }
        }
        if (debugChanged() && !(await saveDebug())) {
            showTab('debug');
            return false;
        }
        return true;
    }

    /** Save: saves, and then the view closes and the row shows the change at once. */
    async function applySettings() {
        if (settingsShown === null || !(await saveAll(false))) {
            return;
        }
        leaveForm(null, true);
    }

    /**
     * Leaves the settings, or the debug settings, for the named panel, or for what they replaced when none is named. After a save the
     * row keeps what was previewed until the poll brings it back as applied, so that it does not flick to the old state; without one
     * the row is the backend's again.
     */
    function leaveForm(name, saved) {
        var applied = saved ? preview : null;
        var back = name || returnTo;
        returnTo = null;
        showPanelNamed(back);
        if (saved) {
            preview = applied;
            render();
            pollNow();
        }
    }

    /** Shows the tab, and no other: its page, its title marked. */
    function showTab(id) {
        var visible = visibleTabs();
        if (visible.indexOf(id) < 0) {
            id = visible[0];
        }
        currentTab = id;
        TABS.forEach(function (tab) {
            var page = tab === 'about' ? $('about-view') : $('tab-' + tab);
            page.hidden = tab !== id;
            $('tabbtn-' + tab).setAttribute('aria-selected', String(tab === id));
        });
        var shown = $('tabbtn-' + id);
        if (shown.scrollIntoView) {
            shown.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        }
        if (id === 'about') {
            loadAbout();
        }
    }

    /** The person has picked a tab: it is shown and told to the backend, which stores it for the next opening, also after a start. */
    function chooseTab(id) {
        tabChosen = true;
        showTab(id);
        postJson('/api/settings-tab', { tab: currentTab }).catch(function () {
            // Only a convenience: the tab is still the one shown, and the next change tells the backend again.
        });
    }

    /** The tabs there are: all of them, and the Debug tab in debug mode only. */
    function visibleTabs() {
        return TABS.filter(function (tab) { return tab !== 'debug' || Boolean(config && config.debug); });
    }

    /** Reads what the About view says; the logo, the name and the version are there even when the rest cannot be read. */
    async function loadAbout() {
        setNote('about-error', '');
        $('about-version').textContent = '';
        try {
            var about = await request('/api/about');
            $('about-name').textContent = about.name;
            $('about-version').textContent = 'v' + about.version;
            showPath('about-settings', about.files.settings);
            showPath('about-log', about.files.log);
            showPath('about-history', about.files.history);
            // What the program runs on is the backend's to name: the page writes the names it is given.
            $('about-runtime-label').textContent = about.runtime.name;
            $('about-runtime').textContent = about.runtime.version;
            $('about-toolkit-label').textContent = about.toolkit.name;
            $('about-toolkit').textContent = about.toolkit.version;
            $('about-system').textContent = about.system;
            $('about-debug').textContent = !about.debug ? 'off' : about.fake ? 'on, the fake backend is in use' : 'on';
            show('about-details', true);
        } catch (e) {
            show('about-details', false);
            setNote('about-error', 'The details could not be read: ' + e.message);
        }
    }

    /**
     * Writes a path into its link in two parts, the folders and the name of the file, so that the name alone can be bold: the
     * name is what follows the last separator of the path, whichever of the two the system has.
     */
    function showPath(id, path) {
        var text = String(path);
        var cut = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1;
        var folders = document.createElement('span');
        folders.className = 'about-folders';
        folders.textContent = text.slice(0, cut);
        var name = document.createElement('span');
        name.className = 'about-file';
        name.textContent = text.slice(cut);
        $(id).replaceChildren(folders, name);
    }

    // How long the button that copied a path says so, in milliseconds.
    var COPIED_MS = 1500;

    /** Asks the program to show one of its files in its folder: `settings`, `log` or `history`. The page names the file, never a path. */
    async function openFolderOf(name) {
        setNote('about-error', '');
        try {
            await postJson('/api/about/open', { file: name });
        } catch (e) {
            setNote('about-error', /^The folder could not be opened: /.test(e.message) ? e.message : 'The folder could not be opened: ' + e.message);
        }
    }

    /** Asks the program to put the path of one of its files on the clipboard, and says in the button that it did. */
    async function copyPathOf(name) {
        var button = $('about-copy-' + name);
        setNote('about-error', '');
        try {
            await postJson('/api/about/copy', { file: name });
        } catch (e) {
            setNote('about-error', /^The path could not be copied: /.test(e.message) ? e.message : 'The path could not be copied: ' + e.message);
            return;
        }
        button.className = 'icon tiny copy done';
        button.title = 'Copied';
        setTimeout(function () {
            button.className = 'icon tiny copy';
            button.title = 'Copy the path';
        }, COPIED_MS);
    }

    /** Cancel: closes the view and changes nothing; it asks nothing, since nothing was sent. It is the one way to leave without saving. */
    function cancelSettings() {
        if (openPanel === 'settings') {
            leaveForm(null, false);
        }
    }

    // ---- the debug settings view, in debug mode only: a form like the settings, with its own endpoint

    /** The debug settings as the backend gave them when the settings were opened or last saved; null when they could not be read. */
    var debugShown = null;

    // How the fake backend's ordinary answer is shaped: the fields of the Debug tab that hold a number, by their setting.
    var FAKE_NUMBERS = ['fakeIncreaseCents', 'fakeBudget', 'fakeStartAmount', 'fakeRefuseSeconds', 'fakeDelayMillis'];
    // The kinds of account by the name the view shows for each.
    var ACCOUNT_NAMES = { usage: 'Usage-based', seat: 'Seat-based' };

    // Of those, the amounts of dollars: the backend gives them as finished texts, `1000.00`, and takes them as they are typed.
    var FAKE_AMOUNTS = { fakeBudget: true, fakeStartAmount: true };

    /**
     * What is typed in a field of the Debug tab, as it is sent: a whole number as a number, an amount as its text, and
     * anything else as it is, for the backend to say what is wrong with it. Nothing is worked out or formatted here.
     */
    function typedNumber(key) {
        var text = String($('debug-' + key).value).trim();
        return !FAKE_AMOUNTS[key] && /^\d+$/.test(text) ? Number(text) : text;
    }

    function readDebugForm() {
        var values = {
            fakeBackend: Boolean($('debug-fakeBackend').checked),
            fakeScenario: $('debug-fakeScenario').value,
            fakeToken: Boolean($('debug-fakeToken').checked)
        };
        // What the backend gave of the fake backend's answer is sent back; a backend that said nothing of it is sent nothing.
        if (debugShown && 'fakeAccount' in debugShown) {
            values.fakeAccount = $('debug-fakeAccount').value;
        }
        FAKE_NUMBERS.forEach(function (key) {
            if (debugShown && key in debugShown) {
                values[key] = typedNumber(key);
            }
        });
        return values;
    }

    async function loadDebug() {
        setNote('debug-error', '');
        try {
            var body = await request('/api/debug');
            debugShown = body.settings;
            // The scenarios are the backend's to name; the list is made from what it sends.
            $('debug-fakeScenario').replaceChildren.apply($('debug-fakeScenario'), body.scenarios.map(function (name) {
                var option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                return option;
            }));
            $('debug-fakeBackend').checked = Boolean(body.settings.fakeBackend);
            $('debug-fakeScenario').value = body.settings.fakeScenario;
            $('debug-fakeToken').checked = Boolean(body.settings.fakeToken);
            // How the fake backend answers: the kinds of account are the backend's to name, like the scenarios.
            show('debug-fake', 'fakeAccount' in body.settings);
            $('debug-fakeAccount').replaceChildren.apply($('debug-fakeAccount'), (body.accounts || []).map(function (name) {
                var option = document.createElement('option');
                option.value = name;
                option.textContent = ACCOUNT_NAMES[name] || name;
                return option;
            }));
            if ('fakeAccount' in body.settings) {
                $('debug-fakeAccount').value = body.settings.fakeAccount;
            }
            FAKE_NUMBERS.forEach(function (key) {
                $('debug-' + key).value = key in body.settings ? String(body.settings[key]) : '';
            });
            show('debug-form', true);
        } catch (e) {
            debugShown = null;
            show('debug-form', false);
            setNote('debug-error', 'The debug settings could not be read: ' + e.message);
        }
    }

    function debugChanged() {
        if (debugShown === null) {
            return false;
        }
        var form = readDebugForm();
        return Object.keys(form).some(function (key) { return form[key] !== debugShown[key]; });
    }

    /** Sends the debug settings; the backend saves them and they take effect at once. False, with the reason in red, when it could not. */
    async function saveDebug() {
        try {
            var body = await postJson('/api/debug', readDebugForm());
            debugShown = body.settings;
            setNote('debug-error', '');
            return true;
        } catch (e) {
            setNote('debug-error', e.message);
            return false;
        }
    }

    // The views that are forms: leaving one by a button of the row saves it first, when anything in it was changed.
    var FORMS = {
        settings: { changed: formChanged, save: function () { return saveAll(true); } }
    };

    // ---- the panel below the strip: the usage history or the log, one at a time

    var PANELS = {
        history: {
            button: 'history-button',
            path: '/api/history',
            describe: view.describeHistory,
            show: 'Show the usage history',
            hide: 'Hide the usage history',
            failure: 'The usage history could not be read: '
        },
        settings: {
            button: 'settings-button',
            show: 'Show the settings',
            hide: 'Hide the settings'
        },
        errors: {
            button: 'errors-button',
            path: '/api/errors',
            describe: view.describeErrors,
            show: 'Show the error log',
            hide: 'Hide the error log',
            failure: 'The error log could not be read: '
        },
        cost: {
            button: 'cost-button',
            path: '/api/cost',
            describe: view.describeCost,
            show: 'Show the cost control',
            hide: 'Hide the cost control',
            failure: 'The cost control could not be read: '
        },
        log: {
            button: 'log-button',
            path: '/api/log',
            describe: view.describeLog,
            show: 'Show the log',
            hide: 'Hide the log',
            failure: 'The log could not be read: '
        }
    };

    /** What the backend says the history file is now: it changes when a row is added, a failed one included. */
    function historyStamp() {
        return lastStatus && lastStatus.historyStamp ? lastStatus.historyStamp : '';
    }

    function panelIsOpen() {
        return openPanel !== null;
    }

    /**
     * Reads what the open panel shows and puts it on the page. The lines are replaced only when they
     * changed, and what the person has scrolled to stays where it is, though lines come in above it.
     */
    async function loadPanel() {
        if (panelLoading || !openPanel || FORMS[openPanel]) {
            return;
        }
        var name = openPanel;
        var spec = PANELS[name];
        var stamp = historyStamp();
        var level = logLevel;
        panelLoading = true;
        try {
            var body = await request(spec.path + (name === 'log' && logUnsaved ? '?level=' + level : ''));
            if (name === 'log') {
                takeLogChoices(body);
                // The date is the page's to cut: the lines come as the file has them.
                body = Object.assign({}, body, { date: logDate });
            }
            var shown = spec.describe(body);
            if (name === 'cost') {
                if (name === openPanel) {
                    showCost(shown);
                }
                return;
            }
            // A read that was begun for another level than the one chosen meanwhile is not shown: the one that follows is.
            if (name === openPanel && !(name === 'log' && level !== null && level !== logLevel)) {
                showPanel(shown);
                if (name === 'log' && logToTop) {
                    $('panel-lines').scrollTop = 0;
                    logToTop = false;
                }
                // Noted only now that the read has been shown: a read that failed, or was for a panel that
                // has gone, leaves the mark as it was, so the history is read again at the next poll.
                panelMark = stamp;
            }
        } catch (e) {
            // What was shown stays; only the error is added.
            if (name === openPanel) {
                setNote('panel-error', spec.failure + e.message);
            }
        } finally {
            panelLoading = false;
        }
        // Another panel was opened while this read was under way and could not start its own: it starts now. So does the
        // read for a level that was chosen while this one was under way.
        if (name !== openPanel || (name === 'log' && level !== null && level !== logLevel)) {
            loadPanel();
        }
    }

    /** Takes the two choices of the log panel from the first answer after it was opened, and shows them in its controls. */
    function takeLogChoices(body) {
        if (logKnown || openPanel !== 'log') {
            return;
        }
        logKnown = true;
        logLevel = body.level || 'info';
        logDate = Boolean(body.date);
        $('log-level').value = logLevel;
        $('log-date').checked = logDate;
        show('log-choices', true);
    }

    /** Gives the lists the size of text chosen, and takes from the two buttons the step that would lead out of the range. */
    function applyListSize() {
        var root = document.documentElement;
        if (root && root.style && root.style.setProperty) {
            root.style.setProperty('--list-size', listSize + 'px');
        }
        setDisabled('zoom-in', listSize >= LIST_SIZE_MAX);
        setDisabled('zoom-out', listSize <= LIST_SIZE_MIN);
    }

    function setDisabled(id, disabled) {
        if (disabled) {
            $(id).setAttribute('disabled', '');
        } else {
            $(id).removeAttribute('disabled');
        }
    }

    /**
     * One of the two buttons was pressed: the text of the lists is a pixel larger or smaller, at once and for all three,
     * and the size is saved. If it cannot be saved it holds all the same, for the run.
     */
    async function zoomLists(step) {
        var wanted = Math.min(LIST_SIZE_MAX, Math.max(LIST_SIZE_MIN, listSize + step));
        if (wanted === listSize) {
            return;
        }
        listSize = wanted;
        applyListSize();
        try {
            await postJson('/api/text-size', { size: listSize });
            setNote('log-error', '');
        } catch (e) {
            setNote('log-error', /^The text size could not be saved: /.test(e.message) ? e.message : 'The text size could not be saved: ' + e.message);
        }
    }

    /**
     * A choice of the log panel was changed: it takes effect at once, on the log that is shown, which stands at its newest
     * line again, and it is saved. If it cannot be saved it holds all the same, for as long as the panel is shown.
     */
    async function changeLogChoices() {
        logLevel = $('log-level').value;
        logDate = Boolean($('log-date').checked);
        logToTop = true;
        try {
            await postJson('/api/log', { level: logLevel, date: logDate });
            logUnsaved = false;
            setNote('log-error', '');
        } catch (e) {
            logUnsaved = true;
            setNote('log-error', /^The log settings could not be saved: /.test(e.message) ? e.message : 'The log settings could not be saved: ' + e.message);
        }
        if (openPanel === 'log') {
            // Read again whatever was read last: the lines are other ones, or are drawn another way.
            panelKey = null;
            await loadPanel();
        }
    }

    function cells(className, values, marked, failed, title, wide, markClass) {
        var row = document.createElement('div');
        row.className = className + ' cols-' + values.length + (marked ? ' ' + (markClass || 'mark') : '') + (wide ? ' date' : '');
        if (title) {
            row.title = title;
        }
        values.forEach(function (value, i) {
            var cell = document.createElement('span');
            cell.textContent = value;
            if (failed && i === 1) {
                cell.className = 'failed';
            }
            row.append(cell);
        });
        return row;
    }

    function showPanel(shown) {
        var key = JSON.stringify(shown);
        setNote('panel-error', '');
        if (key === panelKey) {
            return;
        }
        panelKey = key;
        var box = $('panel-lines');
        var scrolled = box.scrollTop;
        var perRow = box.scrollHeight && panelRows.length ? box.scrollHeight / (panelRows.length + (panelHasHeader ? 1 : 0)) : 0;
        var rowKeys = shown.rows.map(function (row) { return row.join('\u0000'); });
        box.replaceChildren();
        if (shown.header) {
            // The first child of the box, kept at its top as the rows scroll under it.
            box.append(cells('row head', shown.header, false, false, '', shown.wide));
        }
        shown.rows.forEach(function (row, i) {
            box.append(cells('row', row, shown.marks && shown.marks[i], shown.failed && shown.failed[i], shown.titles && shown.titles[i], shown.wide, shown.markClass));
        });
        // Rows that came in above what the person was reading push it down; follow it.
        var added = panelRows.length ? rowKeys.indexOf(panelRows[0]) : 0;
        box.scrollTop = scrolled > 0 && added > 0 ? scrolled + added * perRow : scrolled;
        panelRows = rowKeys;
        panelHasHeader = Boolean(shown.header);
        if (openPanel === 'history') {
            measureTable(shown);
        }
        show('panel-lines', shown.rows.length > 0);
        // A note that says something is left out, or that there is nothing to see, is in the info colour.
        setNote('panel-note', shown.note, 'panel-note' + (shown.noteHighlight ? ' highlight' : ''));
    }

    // What the cost control shows now, as JSON, so that it is drawn again only when it changed.
    var costKey = null;

    // The least width of a bar, and the gap between the parts of a row, as the style sheet has them (`.cost-bar`, `.cost-rows`).
    var COST_BAR_MIN = 120;
    var COST_GAP = 8;

    /** Draws the two bars: a label, a bar with its green share, the figures and, over the limit, the warning sign; and the note under them. */
    function showCost(shown) {
        setNote('cost-error', '');
        var key = JSON.stringify(shown);
        if (key === costKey) {
            return;
        }
        costKey = key;
        var box = $('cost-rows');
        box.replaceChildren();
        shown.rows.forEach(function (row) {
            var line = document.createElement('div');
            line.className = 'cost-row';
            line.title = row.tooltip;
            var label = document.createElement('span');
            label.className = 'cost-label';
            label.textContent = row.label;
            var bar = document.createElement('span');
            bar.className = 'cost-bar' + (row.noLimit ? ' none' : '');
            var fill = document.createElement('span');
            fill.className = 'cost-fill';
            fill.style.width = (row.noLimit ? 0 : Math.round(row.fraction * 1000) / 10) + '%';
            bar.append(fill);
            var text = document.createElement('span');
            text.className = 'cost-text';
            text.textContent = row.text;
            var sign = document.createElement('span');
            sign.className = 'cost-over';
            sign.title = 'Over the limit';
            sign.textContent = row.over ? '\u26a0' : '';
            sign.hidden = !row.over;
            // The figures and the sign are one cell, so that the two rows have the same three columns whether or not a sign is there.
            var figures = document.createElement('span');
            figures.className = 'cost-figures';
            figures.append(text, sign);
            // A row is no box of its own in the grid, so each of its cells says the tooltip of the row.
            label.title = row.tooltip;
            bar.title = row.tooltip;
            text.title = row.tooltip;
            line.append(label, bar, figures);
            box.append(line);
        });
        setNote('cost-note', shown.note, 'panel-note');
    }

    /**
     * How wide the bars need the window to be. The rows share their columns, so that the two bars are equally wide: the widest
     * label, a bar's least width, the widest figures with their sign, and the gaps between the three.
     */
    function costWidth() {
        var lines = Array.prototype.slice.call($('cost-rows').children);
        if (lines.length === 0) {
            return 0;
        }
        var widest = function (i) {
            return lines.reduce(function (most, line) {
                var part = line.children[i];
                return Math.max(most, part ? Math.ceil(part.getBoundingClientRect().width) : 0);
            }, 0);
        };
        // 16 px is the panel's own padding, as for the history.
        return widest(0) + COST_BAR_MIN + widest(2) + 2 * COST_GAP + 16;
    }

    /**
     * Measures how wide the history table needs to be, in a copy of it that nothing constrains: a row in the panel
     * stretches to the window, so it could only report the window's own width. The copy has the header and the
     * few rows with the most text, in the same classes, so each grid takes its content's width.
     */
    function measureTable(shown) {
        var probe = $('table-probe');
        probe.replaceChildren();
        if (!shown.header || shown.rows.length === 0) {
            return;
        }
        probe.append(cells('row head', shown.header, false, false, '', shown.wide));
        shown.rows
            .map(function (row) { return { row: row, size: row.join('').length }; })
            .sort(function (a, b) { return b.size - a.size; })
            .slice(0, 3)
            .forEach(function (entry) { probe.append(cells('row', entry.row, false, false, '', shown.wide)); });
        tableWidth = Math.ceil(probe.getBoundingClientRect().width);
    }

    /**
     * The table's width as it is now. It is read again every time the host asks for the size, not only when the rows were
     * put on the page: a font that is loaded after the first layout changes the width, and the window must follow.
     */
    function currentTableWidth() {
        var probe = $('table-probe');
        if (probe.children.length > 0) {
            tableWidth = Math.ceil(probe.getBoundingClientRect().width);
        }
        return tableWidth;
    }

    /** Reads the open panel again when there may be more to show: a new reading, or any time for the log. */
    function refreshPanel() {
        if (openPanel === 'log' || openPanel === 'errors' || openPanel === 'cost' || (openPanel === 'history' && historyStamp() !== panelMark)) {
            loadPanel();
        }
    }

    // The second line of the tooltip of a panel button that is switched off and shown all the same, while the settings are open.
    var OFF_NOTE = 'Switched off: this button goes when the settings are closed.';

    function labelButtons() {
        Object.keys(PANELS).forEach(function (name) {
            var spec = PANELS[name];
            var label = openPanel === name ? spec.hide : spec.show;
            var off = Boolean(offButtons[name]) && openPanel === 'settings';
            var button = $(spec.button);
            button.title = off ? label + '\n' + OFF_NOTE : label;
            button.setAttribute('aria-label', label);
            button.setAttribute('aria-expanded', String(openPanel === name));
            // The button of the panel that is shown is blue; closing it, or showing another, gives it back its colour. One that is
            // switched off is dimmed.
            button.className = 'icon tiny' + (openPanel === name ? ' active' : '') + (off ? ' off' : '');
        });
    }

    /**
     * Opens the panel, or closes it if it is the one shown; opening one replaces the other. The settings are a panel that remembers
     * what it replaced: leaving them by Save, Cancel or the gear shows that again, freshly loaded so that it has the new settings.
     * Another panel's button while the settings are shown shows that one, and what the settings replaced is forgotten.
     *
     * Leaving the settings, or the debug settings, by a button of the row, their own or another panel's, saves them first, as
     * Save does, when anything in them was changed. If that fails they stay, with the reason, and the other panel is not shown.
     */
    async function togglePanel(name) {
        var form = FORMS[openPanel];
        if (form) {
            var changed = form.changed();
            if (changed && !(await form.save())) {
                return;
            }
            leaveForm(name === openPanel ? null : name, changed);
            return;
        }
        if (FORMS[name]) {
            returnTo = openPanel;
            showPanelNamed(name);
            return;
        }
        returnTo = null;
        showPanelNamed(openPanel === name ? null : name);
    }

    /** Shows the named panel, or none for null, in the one panel area, loading what it shows afresh. */
    function showPanelNamed(name) {
        openPanel = name;
        // A preview belongs to the settings as they were opened: it ends when they are left, and a new one begins with the first click.
        preview = null;
        panelKey = null;
        // Nothing of this panel has been read yet, whatever stamp the one before was read at.
        panelMark = null;
        panelRows = [];
        panelHasHeader = false;
        $('panel-lines').replaceChildren();
        setNote('panel-note', '');
        setNote('panel-error', '');
        // The log panel comes up with what is saved: its controls are shown when the first answer has said what that is.
        logKnown = false;
        logUnsaved = false;
        logToTop = false;
        logLevel = null;
        // The line at the top of a list has the two buttons of the zoom, for each of the three lists; the two choices of
        // the log are in it for the log alone.
        show('log-controls', Boolean(openPanel) && !FORMS[openPanel] && openPanel !== 'cost');
        show('log-choices', false);
        setNote('log-error', '');
        labelButtons();
        show('panel', panelIsOpen());
        // The settings are a form in the same place, not lines; the others are lines.
        show('settings-view', openPanel === 'settings');
        show('cost-view', openPanel === 'cost');
        costKey = null;
        $('cost-rows').replaceChildren();
        setNote('cost-note', '');
        setNote('cost-error', '');
        // The settings open on the tab they were left on (see currentTab).
        if (openPanel === 'cost') {
            show('panel-lines', false);
        }
        if (openPanel === 'settings') {
            show('panel-lines', false);
            showTab(currentTab);
            loadSettings();
        } else {
            settingsShown = null;
            settingsDefaults = null;
            debugShown = null;
        }
        // The window's height while a log or history is shown is fixed at what it was when it opened (ten rows),
        // so that a message line coming or going does not move it; the panel takes up the difference.
        panelHeight = 10 * Math.ceil($('top').getBoundingClientRect().height);
        applyAppClass();
        // The row changes with the panel (the buttons that are switched off come and go), at once and not at the next poll.
        render();
        loadPanel();
    }

    // ---- the window host

    /**
     * What the window shows, for the host to store when the program stops: `panel,tab`. The panel is `log`, `history`, `errors`, `cost` or
     * `settings`; the tab is the one shown of the settings, `main`, `history`, `cost`, `debug`, `general` or `about`, and empty for any
     * other panel. It is the empty text when nothing is open, and when the page is not ready yet: it is safe to ask at any time.
     */
    window.currentView = function () {
        if (!config || !openPanel) {
            return '';
        }
        return openPanel + ',' + (openPanel === 'settings' ? currentTab : '');
    };

    /**
     * Opens the panel, and for the settings the tab, as if the person had pressed the button, for the host to restore what was
     * stored. A panel that is not known is ignored, and so is a tab that is not known, or the Debug tab when the program is not in
     * debug mode: the settings then open on their first tab.
     *
     * @return false while the page is not ready (its configuration has not come yet), so that the host asks again; true once it has
     *     opened the view, or has nothing to open because it was given none it knows
     */
    window.restoreView = function (panel, tab) {
        if (!config) {
            return false;
        }
        if (['log', 'history', 'errors', 'cost', 'settings'].indexOf(panel) < 0) {
            return true;
        }
        returnTo = null;
        if (panel === 'settings') {
            returnTo = openPanel;
            showPanelNamed('settings');
            if (visibleTabs().indexOf(tab) >= 0) {
                tabChosen = true;
                showTab(tab);
            }
            return true;
        }
        showPanelNamed(panel);
        return true;
    };

    /**
     * The size, in CSS pixels, the window needs to show all of this page, and what of it the person
     * may drag: `width,height,resizable`. The host of the page asks for it and resizes the window to
     * match; see docs/api.md. It is the page's own size, not the window's, so asking does not
     * change it. With a panel shown the height is ten times the row's, and the height is the
     * person's to change from there; with the log the width is three times the row's, and that is
     * theirs too. The page never reports the size the window was dragged to.
     */
    /**
     * Whether a press of the mouse at this place of the page, in CSS pixels, may begin a move of the window. The host asks
     * when the window has no title bar, and moves it by a drag that begins where this says yes: anywhere but on something
     * that is pressed for its own sake, a button of the row or a control. A press there does what it always did.
     */
    window.dragStartsAt = function (x, y) {
        var at = document.elementFromPoint(x, y);
        return !(at && at.closest && at.closest('button, select, input, textarea, label, a'));
    };

    window.contentSize = function () {
        markNoted();
        // While a panel is shown the row is stretched to the window, so its own width is measured with the stretch let go
        // of for the moment: the size asked for must never follow from the size the window has.
        var top = $('top');
        var stretched = openPanel !== null;
        if (stretched) {
            top.style.width = 'max-content';
            top.style.maxWidth = '900px';
        }
        var box = top.getBoundingClientRect();
        box = { width: box.width, height: box.height };
        if (stretched) {
            top.style.width = '';
            top.style.maxWidth = '';
        }
        // The red border of the fake backend is round everything: the window is larger by it, so that nothing is covered.
        // So is the border of a window with no title bar, which is one pixel.
        var edge = fakeInUse() ? 6 : bare() ? 2 : 0;
        var width = Math.ceil(box.width) + edge;
        var height = Math.ceil(box.height) + edge;
        if (openPanel === 'settings') {
            // As tall as the row, its message lines and the settings need, whole, and never narrower than the settings need
            // for every text to be on one line and the buttons in one row; the flag says it is not resizable, and the name that a panel is open.
            // The window does not change its width from tab to tab: the width is that of the widest of them.
            return Math.max(width, settingsWidth()) + ',' + Math.ceil($('app').getBoundingClientRect().height) + ',3,settings';
        }
        if (openPanel === 'cost') {
            // As tall as the two bars and the note need, and as wide as the history's table, or as the bars need: not resizable.
            return Math.max(width, currentTableWidth() + 16, costWidth()) + ',' + Math.ceil($('app').getBoundingClientRect().height) + ',3,cost';
        }
        // The last field names the panel, so that the host remembers the height of the history and of the log.
        if (openPanel === 'log') {
            return (3 * width + scrollbarWidth) + ',' + panelHeight + ',2,log';
        }
        if (openPanel === 'history') {
            // As wide as the table needs (16 px is the panel's padding), never narrower than the row.
            return (Math.max(width, currentTableWidth() + 16) + scrollbarWidth) + ',' + panelHeight + ',1,history';
        }
        if (openPanel === 'errors') {
            // Half as wide as the log; a long message scrolls sideways.
            return (Math.ceil(1.5 * width) + scrollbarWidth) + ',' + panelHeight + ',2,errors';
        }
        return width + ',' + height + ',0';
    };

    /**
     * The least width of the settings: that of the widest tab. Every page of the form is let into the layout for the moment of the
     * measuring, so that the form is as wide as the widest, and the shown one is given back as it was; nothing is drawn between.
     */
    function settingsWidth() {
        var pages = TABS.filter(function (tab) { return tab !== 'about'; }).map(function (tab) { return $('tab-' + tab); });
        var was = pages.map(function (page) { return page.hidden; });
        var visible = visibleTabs();
        pages.forEach(function (page, i) { page.hidden = visible.indexOf(TABS[i]) < 0; });
        var width = formWidth('settings-form', 'settings-buttons');
        pages.forEach(function (page, i) { page.hidden = was[i]; });
        return Math.max(width, stripWidth());
    }

    /**
     * How wide the window has to be for the whole strip of the titles to be seen, in one line: the strip's scroll width, which is
     * that of its titles whatever the width of the view, and the room there is at the left of it again at its right.
     */
    function stripWidth() {
        var strip = $('tab-strip');
        var left = $('app').getBoundingClientRect().left || 0;
        var edge = Math.max(0, (strip.getBoundingClientRect().left || 0) - left);
        return Math.ceil((strip.scrollWidth || 0) + 2 * edge);
    }

    /**
     * How wide the window has to be for the settings: the form at the width of its own content, and the buttons of the view, each
     * with the room there is at its left again at its right. The form is as wide as the window when the window is the wider, so it
     * is let go of that for the moment of the measuring, and the answer does not depend on how wide the window happens to be.
     */
    function formWidth(formId, buttonsId) {
        var left = $('app').getBoundingClientRect().left || 0;
        var form = $(formId);
        form.style.minWidth = '0';
        var widths = [form, $(buttonsId)].map(function (element) {
            var box = element.getBoundingClientRect();
            return box.width + 2 * Math.max(0, (box.left || 0) - left);
        });
        form.style.minWidth = '';
        return Math.ceil(Math.max(widths[0], widths[1]));
    }

    /**
     * How wide the vertical scrollbar of the log and the history is, measured with a box that always has one. The
     * panel reserves that room, so the window is that much wider than the row and no column is covered.
     */
    function measureScrollbar() {
        if (!document.body) {
            return 0;
        }
        var probe = document.createElement('div');
        probe.className = 'scrollbar-probe';
        document.body.append(probe);
        var width = probe.offsetWidth - probe.clientWidth;
        document.body.removeChild(probe);
        return width > 0 && width < 100 ? width : 0;
    }

    // ---- start

    async function start() {
        try {
            config = await request('/api/config');
        } catch (e) {
            showConnection(true);
            setTimeout(start, RETRY_START_MS);
            return;
        }
        showConnection(false);
        scrollbarWidth = measureScrollbar();
        // The size of the lists' text as it was saved; a backend that says none, or one out of the range, means the usual.
        if (typeof config.listFontSize === 'number' && config.listFontSize >= LIST_SIZE_MIN && config.listFontSize <= LIST_SIZE_MAX) {
            listSize = config.listFontSize;
        }
        applyListSize();
        $('zoom-in').addEventListener('click', function () { zoomLists(1); });
        $('zoom-out').addEventListener('click', function () { zoomLists(-1); });
        // The Debug tab is there in debug mode, and never otherwise.
        show('tabbtn-debug', Boolean(config.debug));
        TABS.forEach(function (tab) {
            $('tabbtn-' + tab).addEventListener('click', function () { chooseTab(tab); });
        });

        $('log-button').addEventListener('click', function () { togglePanel('log'); });
        $('log-level').addEventListener('change', changeLogChoices);
        $('log-date').addEventListener('change', changeLogChoices);
        $('history-button').addEventListener('click', function () { togglePanel('history'); });
        $('settings-button').addEventListener('click', function () { togglePanel('settings'); });
        $('settings-save').addEventListener('click', applySettings);
        $('errors-button').addEventListener('click', function () { togglePanel('errors'); });
        $('cost-button').addEventListener('click', function () { togglePanel('cost'); });
        $('countdown').addEventListener('mouseenter', function () { hoveringCountdown = true; render(); });
        $('countdown').addEventListener('mouseleave', function () { hoveringCountdown = false; render(); });
        // Every element of the row that can be a gray box: the amounts, the two changes and the request duration.
        ['percent', 'used', 'limit', 'delta-used', 'delta-time', 'duration'].forEach(function (id) {
            $(id).addEventListener('mouseenter', function () { hoveringBox = id; render(); });
            $(id).addEventListener('mouseleave', function () {
                // The mouse may be on the next box already: only the box it leaves is given up.
                if (hoveringBox === id) {
                    hoveringBox = null;
                }
                render();
            });
        });
        $('set-all').addEventListener('click', function () { selectAllOrNone(ELEMENT_FLAGS); });
        $('set-all-history').addEventListener('click', function () { selectAllOrNone(HISTORY_FLAGS); });
        ['settings', 'log', 'history'].forEach(function (name) {
            $('about-' + name).addEventListener('click', function (event) {
                // A link that goes nowhere: the click asks the program, and the page stays where it is.
                if (event && event.preventDefault) {
                    event.preventDefault();
                }
                return openFolderOf(name);
            });
            $('about-copy-' + name).addEventListener('click', function () { return copyPathOf(name); });
        });
        // The key that selects everything is passed over, except in the field to type in: nothing of the window is to be selected.
        if (document.addEventListener) {
            document.addEventListener('keydown', function (event) {
                var field = event.target && event.target.tagName === 'INPUT' && event.target.type === 'text';
                if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 'a' && !field) {
                    event.preventDefault();
                }
            });
            // A selection is not begun by the mouse either, where a style that forbids it should not be honoured.
            document.addEventListener('selectstart', function (event) {
                var field = event.target && event.target.tagName === 'INPUT' && event.target.type === 'text';
                if (!field) {
                    event.preventDefault();
                }
            });
        }
        $('set-autoInterval').addEventListener('change', syncIntervalField);
        $('set-costDailyAutomatic').addEventListener('change', dailyAutomaticChanged);
        $('set-costMonthlyLimit').addEventListener('change', workOutDailyLimit);
        $('settings-cancel').addEventListener('click', cancelSettings);
        MAIN_FLAGS.forEach(function (key) { $('set-' + key).addEventListener('change', previewNow); });
        $('set-timeFormat').addEventListener('change', previewNow);
        $('refresh').addEventListener('click', async function () {
            try {
                await postJson('/api/refresh', {});
            } catch (e) {
                showConnection(true);
            }
            pollNow();
        });
        // Keeps the reset countdowns moving between polls, without asking the backend.
        setInterval(render, 1000);
        poll();
    }

    start();
})();
