'use strict';

// The presentation requirements that can be checked without a window: the text
// is never fine, and the controls come in the required order.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WEB = path.join(__dirname, '../../main/resources/web');
const css = fs.readFileSync(path.join(WEB, 'app.css'), 'utf8');
const html = fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');

test('no text is smaller than 14 px', () => {
    const sizes = [...css.matchAll(/font-size:\s*([0-9.]+)px/g)].map(m => Number(m[1]));
    assert.ok(sizes.length > 0, 'the page sets a font size');
    for (const size of sizes) {
        assert.ok(size >= 14, 'font-size ' + size + 'px is below 14 px');
    }
});

test('no text is set in a thin or light weight', () => {
    const weights = [...css.matchAll(/font-weight:\s*([a-z0-9]+)/g)].map(m => m[1]);
    assert.ok(weights.length > 0, 'the page sets a font weight');
    for (const weight of weights) {
        assert.ok(['normal', 'bold', 'bolder'].includes(weight) || Number(weight) >= 400, 'font-weight ' + weight + ' is lighter than regular');
    }
    assert.doesNotMatch(css, /font-weight:\s*(lighter|[1-3]00)\b/);
});

/** The font-weight set for a selector, looking across every rule it appears in, including selector lists. */
function weightOf(selector) {
    const escaped = selector.replace(/[.#]/g, '\\$&');
    const rules = [...css.matchAll(new RegExp('(?:^|[\\n,])\\s*' + escaped + '\\s*(?:,[^{]*)?{([^}]*)}', 'g'))];
    assert.ok(rules.length > 0, 'no rule for ' + selector);
    const weights = rules.map(rule => rule[1].match(/font-weight:\s*(\d+)/)).filter(Boolean).map(m => Number(m[1]));
    assert.ok(weights.length > 0, selector + ' sets no numeric font-weight');
    return Math.max(...weights);
}

test('the text is bold, and the percentage and amounts are heavier still', () => {
    assert.ok(weightOf('body') >= 700, 'the text is set bold');
    assert.ok(weightOf('.spend') >= 800, 'the percentage and amounts are heavier than the rest');
    assert.ok(weightOf('.spend') > weightOf('body'));
    assert.ok(weightOf('.win-value') >= 800, 'so is a window\'s utilization');
});

test('the percentage is not shrunk or greyed out, since it is the first thing read', () => {
    assert.doesNotMatch(css, /\.percent\s*{[^}]*(font-size|color):/);
});

test('the base text is a sans-serif font', () => {
    assert.match(css, /font-family:[^;]*sans-serif/);
});

test('the controls come in the required order, in one strip', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    // Time first, then the percentage, the two amounts (or the plan windows), the refresh button,
    // the countdown, the two changes, the settings button, then the log and history buttons.
    const order = ['id="time"', 'id="percent"', 'id="used"', 'id="limit"', 'id="windows"',
        'id="refresh"', 'id="countdown"', 'id="delta-used"', 'id="delta-time"',
        'id="log-button"', 'id="history-button"', 'id="settings-button"'];
    const positions = order.map(marker => strip.indexOf(marker));
    positions.forEach((position, i) => assert.ok(position >= 0, 'missing ' + order[i] + ' in the strip'));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions,
        'the order is time, percentage, used, limit, windows, refresh, countdown, changes, settings, log, history');
});

test('the time is the very first thing in the strip', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    for (const later of ['id="percent"', 'id="used"', 'id="limit"', 'id="windows"', 'id="refresh"', 'id="countdown"', 'id="settings-button"']) {
        assert.ok(strip.indexOf('id="time"') < strip.indexOf(later), 'the time comes before ' + later);
    }
});

test('the countdown comes right after the refresh button, and the settings button after the changes', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const refresh = strip.indexOf('id="refresh"');
    const countdown = strip.indexOf('id="countdown"');
    const settings = strip.indexOf('id="settings-button"');
    assert.ok(refresh < countdown && countdown < settings);
    assert.equal(strip.slice(refresh, countdown).includes('id="used"'), false, 'nothing else sits between them');
});

test('the amounts are two separate numbers, each able to carry its own tooltip', () => {
    assert.match(html, /<span id="used"><\/span>\s*\/\s*<span id="limit"><\/span>/);
    assert.doesNotMatch(html, /id="amounts"/);
    assert.doesNotMatch(html, /\$/, 'no currency sign in the page');
});

test('the countdown sits close to the refresh button: left-aligned and pulled in, not right-aligned', () => {
    const rule = css.match(/\.countdown\s*{([^}]*)}/)[1];
    // Right-aligned in a wider box, spare room would sit between the icon and the number.
    assert.match(rule, /text-align:\s*left/);
    assert.doesNotMatch(rule, /text-align:\s*right/);
    const pull = rule.match(/margin-left:\s*(-?\d+)px/);
    assert.ok(pull && Number(pull[1]) < 0, 'it is pulled towards the button by a negative margin');
    assert.ok(Number(pull[1]) > -10, 'but not so far that it would touch the icon: the strip gap is 10 px');
});

test('the refresh icon sits snug against the amounts before it', () => {
    const rule = css.match(/#refresh\s*{([^}]*)}/)[1];
    const pull = rule.match(/margin-left:\s*(-?\d+)px/);
    assert.ok(pull && Number(pull[1]) < 0, 'it is pulled towards what comes before it');
    assert.ok(Number(pull[1]) > -10, 'but not so far that its box covers the number: the strip gap is 10 px');
});

test('every error is red: the failed refresh with figures still shown, the one without, and the rest', () => {
    for (const kind of ['note-stale', 'note-error']) {
        const rule = css.match(new RegExp('\\.' + kind + '[^{]*{([^}]*)}'));
        assert.ok(rule, 'no rule for ' + kind);
        assert.match(rule[1], /color:\s*var\(--bad\)/, kind + ' is red');
    }
    assert.doesNotMatch(css, /\.note[a-z-]*[^{]*{[^}]*var\(--warn\)/, 'no message is amber');
    assert.match(css, /--bad:\s*#[0-9a-f]{6}/i, 'the red is a real red');
});

test('the countdown starts hidden and keeps a width that does not move with every digit', () => {
    assert.match(html, /id="countdown"[^>]*\shidden/);
    assert.match(css, /\.countdown\s*{[^}]*min-width:\s*[0-9.]+ch/);
    assert.match(css, /\.countdown\s*{[^}]*tabular-nums/);
});

test('the optional items of the row start hidden and keep a width that does not move with every digit', () => {
    for (const id of ['delta-used', 'delta-time']) {
        assert.match(html, new RegExp('id="' + id + '"[^>]*\\shidden'));
    }
    assert.match(css, /\.delta-used\s*{[^}]*min-width:\s*[0-9.]+ch/);
    assert.match(css, /\.delta-time\s*{[^}]*min-width:\s*[0-9.]+ch/);
    assert.match(css, /\.delta\s*{[^}]*tabular-nums/);
});

test('the update interval is not a setting of the window', () => {
    assert.doesNotMatch(html, /poll-interval/);
    assert.doesNotMatch(html, /usage-interval/, 'the interval field is not in the strip any more; it is a dropdown in the settings');
});

test('the settings view is in the panel area, below the strip, and starts hidden', () => {
    const panel = html.slice(html.indexOf('id="panel"'));
    assert.ok(panel.includes('id="settings-view"'), 'the settings are in the panel area');
    assert.match(html, /id="settings-view"[^>]*\shidden/);
    assert.ok(html.indexOf('id="settings-button"') < html.indexOf('id="settings-view"'));
});

test('the settings view has the three buttons, the two view buttons and a dropdown for the interval', () => {
    for (const id of ['settings-apply', 'settings-restore', 'settings-cancel', 'settings-maximum', 'settings-minimum']) {
        assert.match(html, new RegExp('<button[^>]*id="' + id + '"'), id);
    }
    assert.match(html, /<input id="set-usageIntervalSeconds" type="text"/, 'a box to type any number in');
    assert.doesNotMatch(html, /set-intervalChoices/, 'and no dropdown of values');
    for (const key of ['showPercentage', 'showInterval', 'showDeltaUsed', 'showDeltaTime', 'historyDate', 'historyDeltaUsed', 'historyDeltaTime', 'logResponse']) {
        assert.match(html, new RegExp('<input id="set-' + key + '" type="checkbox"'), key);
    }
    assert.match(html, /<select id="set-timeFormat"/);
});

test('the hidden attribute really hides, even on elements the stylesheet gives a display', () => {
    // An author rule such as `.config { display: inline-flex }` beats the browser's
    // own `[hidden] { display: none }`, so the stylesheet must restore it with force.
    assert.match(css, /\[hidden\]\s*{[^}]*display:\s*none\s*!important/);

    const hiddenIds = [...html.matchAll(/<[^>]*\sid="([^"]+)"[^>]*\shidden[\s>]/g)].map(m => m[1]);
    assert.ok(hiddenIds.includes('settings-view'), 'the settings view uses the hidden attribute');
    const classesGivenADisplay = [...css.matchAll(/\.([a-z-]+)\s*{[^}]*\bdisplay:\s*(?!none)/g)].map(m => m[1]);
    const overridden = hiddenIds.filter(id => {
        const tag = html.match(new RegExp('<[^>]*\\sid="' + id + '"[^>]*>'))[0];
        const cls = (tag.match(/class="([^"]+)"/) || [, ''])[1].split(/\s+/);
        return cls.some(c => classesGivenADisplay.includes(c));
    });
    assert.ok(overridden.length > 0, 'at least one hidden element has a display rule, which is why the [hidden] rule is needed: ' + hiddenIds);
});

test('the settings button is at the right-hand end of the row, after the log and history buttons, which come after everything else', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const log = strip.indexOf('id="log-button"');
    const history = strip.indexOf('id="history-button"');
    const settings = strip.indexOf('id="settings-button"');
    assert.ok(log >= 0 && history >= 0 && settings >= 0, 'all three are in the row');
    for (const earlier of ['id="time"', 'id="percent"', 'id="used"', 'id="refresh"', 'id="countdown"', 'id="delta-used"', 'id="delta-time"']) {
        assert.ok(strip.indexOf(earlier) < log, earlier + ' comes before the log button');
    }
    assert.ok(log < history && history < settings, 'the log button, the history button, then the settings button');
});

test('the settings button is a gear: a circle and the toothed outline round it', () => {
    const button = html.match(/<button[^>]*id="settings-button"[\s\S]*?<\/button>/)[0];
    assert.match(button, /<circle cx="12" cy="12" r="3"\/>/, 'the hub');
    assert.match(button, /<path d="M19\.4 15a1\.65/, 'the teeth');
    assert.doesNotMatch(button, /cx="5\.5"/, 'not the two sliders it used to be');
});

test('neither the log nor the history is a window: there are no pages for them, and nothing opens a window', () => {
    for (const gone of ['history.html', 'history.js', 'log.html', 'log.js', 'popup.css']) {
        assert.ok(!fs.existsSync(path.join(WEB, gone)), gone + ' is gone');
    }
    assert.doesNotMatch(fs.readFileSync(path.join(WEB, 'app.js'), 'utf8'), /window\.open/);
});

test('the panel is below the strip and below every message line, and closed at the start', () => {
    const panel = html.indexOf('id="panel"');
    assert.ok(panel > html.indexOf('class="strip"'), 'after the strip');
    for (const note of ['id="note"', 'id="config-note"', 'id="connection"']) {
        assert.ok(panel > html.indexOf(note), 'after ' + note);
    }
    assert.match(html, /<div id="panel"[^>]*\shidden/);
    assert.match(html, /id="history-button"[^>]*aria-expanded="false"/);
    assert.match(html, /id="log-button"[^>]*aria-expanded="false"/);
    assert.match(html, /id="panel-error"[^>]*role="alert"/);
});

/** The declarations of the rule for one selector in app.css. */
function ruleOf(selector) {
    const escaped = selector.replace(/[.#()>:*+?^$|]/g, '\\$&');
    const match = css.match(new RegExp('(?:^|\\n)' + escaped + '\\s*{([^}]*)}'));
    assert.ok(match, 'no rule for ' + selector);
    return match[1];
}

test('the panel is small, condensed, regular-weight monospace with a tight line height', () => {
    const font = ruleOf('.panel').match(/font:\s*(\d+)\s+(\d+)px\/(\d+)px\s+([^;]+);/);
    assert.ok(font, 'the panel sets its font in one declaration');
    assert.ok(Number(font[1]) >= 400, 'never thin');
    assert.ok(Number(font[2]) <= 13 && Number(font[2]) >= 11, 'about 12 px');
    assert.ok(Number(font[3]) <= Number(font[2]) * 1.35, 'tight line height');
    assert.match(font[4], /monospace/);
    assert.match(ruleOf('.panel'), /letter-spacing:\s*-/, 'condensed');
});

test('the panel keeps the width of the row and takes the height the window leaves it, scrolling', () => {
    assert.match(ruleOf('.panel'), /width:\s*0;[^}]*min-width:\s*100%/);
    assert.match(ruleOf('.panel'), /flex:\s*1\b/);
    const box = ruleOf('.panel-lines');
    assert.match(box, /flex:\s*1\b/);
    assert.match(box, /overflow-y:\s*scroll/, 'the vertical scrollbar is always there, so its room is always reserved');
    assert.match(box, /min-height:\s*0/, 'a flex child may shrink below its content, or it would not scroll');
    assert.doesNotMatch(box, /(^|[^-])height:\s*\d/, 'no fixed height');
});

test('with the history open the page fills the window, in a column; the row and its messages are what is sized to', () => {
    const open = ruleOf('#app.open');
    assert.match(open, /display:\s*flex/);
    assert.match(open, /flex-direction:\s*column/);
    assert.match(open, /height:\s*100%/);
    assert.match(css, /html,\s*body\s*{[^}]*height:\s*100%/);
    assert.match(html, /<div id="top"[\s\S]*id="note"[\s\S]*id="connection"[^>]*>[^<]*<\/p>\s*<\/div>\s*<div id="panel"/);
});

test('the panels and the settings view are the only text under 14 px', () => {
    const small = [...css.matchAll(/([^{}]+){([^}]*)}/g)]
        .filter(rule => [...rule[2].matchAll(/font(?:-size)?:\s*(?:[a-z0-9 ]*\s)?([0-9.]+)px/g)].some(m => Number(m[1]) < 14))
        .map(rule => rule[1].trim().split('\n').pop().trim());
    assert.deepEqual([...small].sort(), ['.panel', '.settings', '.table-probe']); // the probe is the panel's text, never seen
});

test('an error in the panel is red', () => {
    assert.match(ruleOf('.panel-error'), /color:\s*var\(--bad\)/);
});

test('the log and history buttons say what they open', () => {
    assert.match(html, /id="log-button"[^>]*title="Show the log"/);
    assert.match(html, /id="history-button"[^>]*title="Show the usage history"/);
    assert.match(html, /id="settings-button"[^>]*title="Show the settings"/);
});

test('the two buttons are icons with accessible names, not words', () => {
    for (const id of ['refresh', 'settings-button', 'log-button', 'history-button']) {
        const element = html.match(new RegExp('<button[^>]*id="' + id + '"[\\s\\S]*?</button>'));
        assert.ok(element, id + ' is a button');
        const button = element[0];
        assert.match(button, /aria-label="[^"]+"/, id + ' has an accessible name');
        assert.match(button, /<svg/, id + ' shows an icon');
        const visible = button.replace(/<svg[\s\S]*?<\/svg>/g, '').replace(/<[^>]+>/g, '').trim();
        assert.equal(visible, '', id + ' has no visible words');
    }
});

test('the page is sized by its own content, not by the window', () => {
    assert.match(css, /#app\s*{[^}]*width:\s*max-content/);
    assert.match(css, /html,\s*body\s*{[^}]*overflow:\s*hidden/);
});

test('a message line never makes the window wider: it adds no width of its own and wraps', () => {
    const note = ruleOf('.note');
    assert.match(note, /width:\s*0;[^}]*min-width:\s*100%/);
    assert.doesNotMatch(note, /white-space:\s*(nowrap|pre)/);
    assert.match(note, /white-space:\s*normal/);
    assert.match(note, /overflow-wrap:\s*anywhere/);
    assert.doesNotMatch(note, /max-width/);
});

test('the row has its own width, which the window follows: it wraps at 900 px, and the open page fills the window', () => {
    assert.match(ruleOf('.top'), /width:\s*max-content/);
    assert.match(ruleOf('.top'), /max-width:\s*900px/);
    assert.match(ruleOf('#app.open'), /width:\s*100%/);
    assert.doesNotMatch(ruleOf('#app'), /max-width/, 'a wide log is not held to the width of the row');
});

test('the history is a table: the time at the left takes what room there is, the other columns are fixed and right-aligned', () => {
    const table = ruleOf('.panel-lines .cols-4');
    assert.match(table, /display:\s*grid/);
    const columns = table.match(/grid-template-columns:\s*(.+);/)[1].trim().split(/\s+(?![^(]*\))/);
    assert.equal(columns.length, 4);
    assert.match(columns[0], /^minmax\(\d+ch,\s*1fr\)$/, 'the time is the flexible one, so the others sit against the right edge');
    assert.ok(Number(columns[0].match(/\d+/)[0]) >= '21:01:22'.length, 'it has room for the time of day');
    for (const fixed of columns.slice(1)) {
        assert.match(fixed, /^\d+ch$/, 'a fixed width, so that the rows line up with each other: ' + fixed);
    }
    assert.ok(Number(columns[3].replace('ch', '')) >= 'USD'.length && Number(columns[3].replace('ch', '')) >= 'Cur.'.length, 'wide enough for the code and its short title');
    assert.ok(Number(columns[1].replace('ch', '')) >= '9999999.99'.length, 'used holds the amounts the requirements name');
    assert.ok(Number(columns[2].replace('ch', '')) >= '9999999.99'.length, 'so does limit');
    const withDate = ruleOf('.panel-lines .cols-4.date').match(/grid-template-columns:\s*minmax\((\d+)ch/);
    assert.ok(Number(withDate[1]) >= '2026-10-08 21:01:22'.length, 'with the date the time is wider');
    assert.ok(Number(table.match(/column-gap:\s*(\d+)px/)[1]) <= 10, 'little space between the columns');
    assert.match(ruleOf('.panel-lines .cols-4 > :not(:first-child)'), /text-align:\s*right/);
    assert.doesNotMatch(table, /text-align/, 'the time keeps the default, at the left');
});

test('the change columns are fixed and right-aligned too, after the currency, in both widths of the time', () => {
    for (const [cols, count] of [['cols-5', 5], ['cols-6', 6]]) {
        for (const suffix of ['', '.date']) {
            const columns = ruleOf('.panel-lines .' + cols + suffix).match(/grid-template-columns:\s*(.+);/)[1].trim().split(/\s+(?![^(]*\))/);
            assert.equal(columns.length, count, cols + suffix);
            assert.match(columns[0], /^minmax\(\d+ch,\s*1fr\)$/);
            columns.slice(1).forEach(c => assert.match(c, /^\d+ch$/, cols + suffix + ' ' + c));
        }
    }
    assert.match(ruleOf('.panel-lines .cols-5 > :not(:first-child),\n.panel-lines .cols-6 > :not(:first-child)'), /text-align:\s*right/);
});

test('the titles are aligned like their columns: the time at the left, the others at the right', () => {
    assert.match(ruleOf('.panel-lines .head > :first-child'), /text-align:\s*left/);
    assert.doesNotMatch(css, /\.head\.cols-\d > \*[^{]*{[^}]*text-align:\s*center/, 'nothing is centred any more');
});

test('the table header stays at the top of the panel as the rows scroll, and is bold', () => {
    const head = ruleOf('.panel-lines .head');
    assert.match(head, /position:\s*sticky/);
    assert.match(head, /top:\s*0/);
    assert.match(head, /font-weight:\s*700/);
    assert.match(head, /background:\s*Canvas/, 'rows do not show through it');
});

test('a log line is never wrapped: a long one scrolls sideways', () => {
    assert.match(ruleOf('.panel-lines'), /white-space:\s*pre/);
    assert.match(ruleOf('.panel-lines'), /overflow-x:\s*auto/);
});

test('the page measures a scrollbar with a box that always has one, which is never seen', () => {
    const probe = ruleOf('.scrollbar-probe');
    assert.match(probe, /overflow:\s*scroll/);
    assert.match(probe, /visibility:\s*hidden/);
    assert.match(probe, /position:\s*absolute/);
});

test('the settings never scroll: the window is as tall as they need', () => {
    assert.match(ruleOf('.settings'), /overflow:\s*visible/);
    assert.doesNotMatch(ruleOf('.settings'), /flex:\s*1/);
});

test('the four icon buttons sit close together in one group, much nearer than the strip\'s gap', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const group = strip.slice(strip.indexOf('class="tools"'));
    for (const id of ['log-button', 'history-button', 'errors-button', 'settings-button']) {
        assert.ok(group.includes('id="' + id + '"'), id + ' is in the group');
    }
    assert.ok(group.indexOf('id="log-button"') < group.indexOf('id="history-button"'));
    assert.ok(group.indexOf('id="history-button"') < group.indexOf('id="errors-button"'));
    assert.ok(group.indexOf('id="errors-button"') < group.indexOf('id="settings-button"'), 'the gear is last');
    const gap = Number(ruleOf('.tools').match(/gap:\s*(\d+)px/)[1]);
    const stripGap = Number(ruleOf('.strip').match(/gap:\s*[0-9]+px\s+(\d+)px/)[1]);
    assert.ok(gap <= 2 && gap < stripGap, 'the group gap ' + gap + ' px is less than the strip\'s ' + stripGap + ' px');
});

test('the error log button is an icon with an accessible name', () => {
    const button = html.match(/<button[^>]*id="errors-button"[\s\S]*?<\/button>/)[0];
    assert.match(button, /aria-label="Show the error log"/);
    assert.match(button, /title="Show the error log"/);
    assert.match(button, /<svg/);
});

test('the countdown is red during a back-off, and its message is bold red under the row', () => {
    assert.match(ruleOf('.countdown.alert'), /color:\s*var\(--bad\)/);
    assert.match(ruleOf('.note-alert'), /color:\s*var\(--bad\)/);
    assert.match(ruleOf('.note-alert'), /font-weight:\s*[89]00/);
});

test('a history table wider than the panel scrolls sideways: rows are never narrower than their content, and the box scrolls on x only when needed', () => {
    for (const cols of ['cols-4', 'cols-5', 'cols-6']) {
        assert.match(ruleOf('.panel-lines .' + cols), /min-width:\s*max-content/, cols);
    }
    assert.match(ruleOf('.panel-lines'), /overflow-x:\s*auto/, 'only when needed: no room is reserved for it');
});

test('the error log rows have the time and the message, which is not wrapped', () => {
    const rule = ruleOf('.panel-lines .cols-2');
    assert.match(rule, /display:\s*grid/);
    assert.match(rule, /grid-template-columns:\s*\d+ch max-content/);
});

test('the settings can be selected and copied, and their dropdowns have room for the value and the arrow', () => {
    assert.match(ruleOf('.settings'), /user-select:\s*text/);
    const select = ruleOf('.settings select');
    assert.match(select, /min-width:\s*\d+ch/);
    assert.match(select, /padding:\s*\d+px\s+[2-9](\.\d+)?em\s+\d+px\s+\d+px/, 'room on the right, for the arrow');
});

test('the settings texts are the ones the requirements give', () => {
    assert.match(html, /<label>Interval\s*<input id="set-usageIntervalSeconds"/);
    assert.match(html, /<input id="set-showInterval" type="checkbox"> Interval<\/label>/);
    assert.match(html, /<input id="set-logResponse" type="checkbox"> Log the response<\/label>/);
    assert.match(html, /<legend>History view<\/legend>/);
    assert.match(html, /<input id="set-historyDate" type="checkbox"> Date<\/label>/);
    assert.match(html, /<input id="set-historyZeroLines" type="checkbox"> Zero usage lines<\/label>/);
    assert.match(html, /<input id="set-historyFailedLines" type="checkbox"> Failed lines<\/label>/);
    assert.match(html, /<legend>Request<\/legend>/);
    assert.doesNotMatch(html, /Usage requests/, 'the section is `Request`');
    assert.doesNotMatch(html, /> Show /, 'no checkbox starts with Show');
    assert.match(html, /<input id="set-historyDeltaUsed" type="checkbox"> \u0394 used<\/label>/, 'the History view: like the title of the column');
    assert.match(html, /<input id="set-historyDeltaTime" type="checkbox"> \u0394 time<\/label>/);
    assert.match(html, /<input id="set-showDeltaUsed" type="checkbox"> Change in the amount used<\/label>/, 'the Main view keeps the long texts');
    assert.match(html, /<input id="set-showDeltaTime" type="checkbox"> Time since the previous reading<\/label>/);
    assert.doesNotMatch(html, /Column/, 'no \'Column\' anywhere in the page');
    assert.doesNotMatch(html, /Time between requests/);
});

test('the table probe is never seen: off the page, hidden, and as wide as its content', () => {
    const rule = ruleOf('.table-probe');
    assert.match(rule, /position:\s*absolute/);
    assert.match(rule, /visibility:\s*hidden/);
    assert.match(rule, /width:\s*max-content/);
    assert.match(rule, /left:\s*-\d+px/);
    assert.match(html, /id="table-probe"[^>]*aria-hidden="true"/);
    assert.match(rule, /font:\s*400 12px/, 'the same text as the panel, or it would measure the wrong width');
    assert.ok(css.indexOf('.table-probe {') > css.indexOf('.panel-lines {'), 'it comes after the panel rule it overrides');
});

test('the button of the open panel is a vivid green, easier to see than the severity green', () => {
    assert.match(ruleOf('.icon.active'), /color:\s*var\(--active\)/);
    assert.notEqual(css.match(/--active:\s*(#[0-9a-f]{6})/g).length, 0);
    const colours = [...css.matchAll(/--active:\s*(#[0-9a-f]{6})/g)].map(m => m[1]);
    assert.deepEqual(colours, ['#00b341', '#3ddc6b'], 'the light theme, then the dark one');
    assert.ok(css.indexOf('--active: #3ddc6b') > css.indexOf('prefers-color-scheme: dark'), 'the second is in the dark block');
    assert.ok(css.indexOf('.icon.active {') > css.indexOf('.icon.tiny {'), 'it comes after the rule giving the buttons their gray, to win');
});

test('a margin as wide as a scrollbar is always kept at the right of the panel text, and the probe has it too', () => {
    assert.match(ruleOf('.panel-lines'), /padding-right:\s*16px/);
    assert.match(html, /id="table-probe" class="panel-lines table-probe"/, 'the probe is a panel-lines too, so it is measured with the margin');
    assert.doesNotMatch(ruleOf('.table-probe'), /padding/, 'and does not take it away');
});

test('there is no padding left between used and limit: their room is what the fixed columns leave', () => {
    assert.doesNotMatch(css, /:nth-child\(2\)/);
    assert.doesNotMatch(css, /:nth-child\(3\)/);
});

test('the active button is 24 px with a 16 px icon and heavier lines, and takes back what it grew by so nothing moves', () => {
    const button = ruleOf('.icon.active');
    assert.match(button, /width:\s*24px/);
    assert.match(button, /height:\s*24px/);
    assert.match(button, /margin:\s*-2px/, 'the 4 px it grew by, so the row is neither taller nor wider');
    const icon = ruleOf('.icon.active svg');
    assert.match(icon, /width:\s*16px/);
    assert.match(icon, /height:\s*16px/);
    assert.match(icon, /stroke-width:\s*2\.2/);
    assert.match(ruleOf('#settings-button.active svg'), /stroke-width:\s*3\.3/, 'the gear is on a 24 unit grid: 2.2 x 24 / 16');
});

test('the active button is no bigger than the refresh button, which sets the height of the row', () => {
    const refresh = Number(ruleOf('.icon').match(/height:\s*(\d+)px/)[1]);
    const active = Number(ruleOf('.icon.active').match(/height:\s*(\d+)px/)[1]);
    assert.ok(active <= refresh, 'the active button ' + active + ' px, the refresh button ' + refresh + ' px');
    assert.ok(css.indexOf('.icon.active {') > css.indexOf('.icon.tiny {'), 'it comes after the rule that makes the buttons 20 px, to win');
});

test('the gear is drawn on a grid of 24 units and the other icons of 16, which is why its line is heavier', () => {
    for (const id of ['log-button', 'history-button', 'errors-button']) {
        assert.match(html.match(new RegExp('<button[^>]*id="' + id + '"[\\s\\S]*?</button>'))[0], /viewBox="0 0 16 16"/, id);
    }
    assert.match(html.match(/<button[^>]*id="settings-button"[\s\S]*?<\/button>/)[0], /viewBox="0 0 24 24"/);
});

test('a checkbox and its text, and a label and its controls, are centred on each other vertically', () => {
    const rule = ruleOf('.settings label');
    assert.match(rule, /display:\s*flex/);
    assert.match(rule, /align-items:\s*center/);
    assert.match(rule, /flex-wrap:\s*wrap/, 'a line that does not fit wraps, the controls staying centred');
    assert.match(css, /\.settings label input\[type="checkbox"\]\s*{[^}]*margin:\s*0/, 'no margin of the browser to push it off centre');
});

test('the history has two new switches, the date and the change columns after them, under the History view heading', () => {
    const view = html.slice(html.indexOf('<legend>History view</legend>'), html.indexOf('<legend>Log</legend>'));
    const order = ['set-historyDate', 'set-historyZeroLines', 'set-historyFailedLines', 'set-historyDeltaUsed', 'set-historyDeltaTime'];
    const positions = order.map(id => view.indexOf('id="' + id + '"'));
    positions.forEach((p, i) => assert.ok(p >= 0, order[i] + ' is in the History view group'));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions);
});

test('the currency is the right-most column of the history, with the change columns between the budget and it', () => {
    for (const [cols, count] of [['cols-4', 4], ['cols-5', 5], ['cols-6', 6]]) {
        for (const suffix of ['', '.date']) {
            const columns = ruleOf('.panel-lines .' + cols + suffix).match(/grid-template-columns:\s*(.+);/)[1].trim().split(/\s+(?![^(]*\))/);
            assert.equal(columns.length, count, cols + suffix);
            assert.equal(columns[columns.length - 1], '5ch', 'the currency, 5 characters wide, is last in ' + cols + suffix);
            if (count >= 5) {
                assert.equal(columns[3], '9ch', 'the first change column, after the budget, in ' + cols + suffix);
            }
            if (count === 6) {
                assert.equal(columns[4], '8ch', 'the time change column, then the currency, in ' + cols + suffix);
            }
        }
    }
});

test('the four new settings are in the Main view group, with the texts of the requirements', () => {
    const main = html.slice(html.indexOf('<legend>Main view</legend>'), html.indexOf('<legend>History view</legend>'));
    for (const [id, text] of [['set-showCurrency', 'Currency symbol'], ['set-showHistoryIcon', 'History icon'], ['set-showLogIcon', 'Log icon'], ['set-showErrorIcon', 'Error log icon']]) {
        assert.ok(main.includes('<input id="' + id + '" type="checkbox"> ' + text + '</label>'), id);
    }
});

test('the highlighted note is blue, in a colour of its own for each theme, and comes after the plain note rule', () => {
    const colours = [...css.matchAll(/--info:\s*(#[0-9a-f]{6})/g)].map(m => m[1]);
    assert.deepEqual(colours, ['#0969da', '#58a6ff'], 'the light theme, then the dark one');
    assert.ok(css.indexOf('--info: #58a6ff') > css.indexOf('prefers-color-scheme: dark'), 'the second is in the dark block');
    assert.match(ruleOf('.panel-note.highlight'), /color:\s*var\(--info\)/);
    assert.ok(css.indexOf('.panel-note.highlight {') > css.indexOf('.panel-note {'), 'it wins over the gray');
    assert.match(ruleOf('.panel-note'), /color:\s*var\(--muted\)/, 'the others stay gray');
});

test('the interval is one entry field with its unit, in the Request section', () => {
    const request = html.slice(html.indexOf('<legend>Request</legend>'), html.indexOf('<legend>Main view</legend>'));
    assert.match(request, /<input id="set-usageIntervalSeconds" type="text"/);
    assert.match(request, /> s<\/label>/, 'the unit s beside it');
    assert.equal((request.match(/<select/g) || []).length, 0, 'no dropdown');
});

test('the history\'s start line is green text with no background and no bold; the log\'s keeps the gray background', () => {
    const start = ruleOf('.panel-lines .start');
    assert.match(start, /color:\s*var\(--active\)/, 'the vivid green of the open panel\'s button');
    assert.doesNotMatch(start, /background/, 'no special background');
    assert.doesNotMatch(start, /font-weight/, 'not bold');
    assert.match(ruleOf('.panel-lines .mark'), /background:\s*var\(--mark\)/, 'the log keeps its gray, from a variable');
    assert.doesNotMatch(ruleOf('.panel-lines .mark'), /color/);
});

test('the time since the previous reading in the row has room for six characters: 3600 s', () => {
    assert.match(ruleOf('.delta-time'), /min-width:\s*6ch/);
});

test('the log\'s start line has a gray for each theme, a light one and a dark one that the light text can be read on', () => {
    const colours = [...css.matchAll(/--mark:\s*(#[0-9a-f]{6})/g)].map(m => m[1]);
    assert.deepEqual(colours, ['#e6e6e6', '#30363d'], 'the light theme, then the dark one');
    assert.ok(css.indexOf('--mark: #30363d') > css.indexOf('prefers-color-scheme: dark'), 'the second is in the dark block');
});

test('an optional item of the row that is empty keeps its room and is not seen', () => {
    assert.match(ruleOf('.delta.empty'), /visibility:\s*hidden/);
    assert.doesNotMatch(ruleOf('.delta.empty'), /display\s*:\s*none/, 'it must not leave the layout');
    for (const rule of ['.delta-used', '.delta-time', '.delta-interval']) {
        assert.match(ruleOf(rule), /min-width:\s*[0-9.]+ch/, rule + ' has a width of its own, which it keeps empty');
    }
});
