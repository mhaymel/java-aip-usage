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
    assert.match(html, /<select id="set-intervalChoices"/, 'and a dropdown of the usual values');
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

test('the history is a table: four columns spread over the width, close together, used, limit and currency centred', () => {
    const table = ruleOf('.panel-lines .cols-4');
    assert.match(table, /display:\s*grid/);
    const columns = table.match(/grid-template-columns:\s*(.+);/)[1].trim().split(/\s+(?![^(]*\))/);
    assert.equal(columns.length, 4);
    assert.match(columns[0], /^\d+ch$/, 'the time has room for what it holds, so is not clipped');
    assert.match(columns[3], /^\d+ch$/, 'and so has the currency, with its title');
    assert.ok(Number(columns[3].replace('ch', '')) >= 'currency'.length, 'wide enough for its own title');
    assert.ok(Number(columns[0].replace('ch', '')) >= '21:01:22'.length, 'the time of day');
    const withDate = ruleOf('.panel-lines .cols-4.date').match(/grid-template-columns:\s*(\d+)ch/);
    assert.ok(Number(withDate[1]) >= '2026-10-08 21:01:22'.length, 'and with the date it is wider');
    assert.match(columns[1], /fr/, 'the amounts share what is left, which is how they spread over the width');
    assert.match(columns[2], /fr/);
    assert.ok(Number(table.match(/column-gap:\s*(\d+)px/)[1]) <= 10, 'little space between the columns');
    assert.match(ruleOf('.panel-lines .cols-4 > :not(:first-child)'), /text-align:\s*center/);
    assert.doesNotMatch(table, /text-align/, 'the date keeps the default, at the left');
});

test('the column titles are centred, and that rule comes after the one aligning the cells', () => {
    assert.match(ruleOf('.panel-lines .head.cols-4 > *'), /text-align:\s*center/);
    assert.ok(css.indexOf('.panel-lines .head.cols-4 > *') > css.indexOf('.panel-lines .cols-4 > :not(:first-child)'), 'so it wins');
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

test('between used and limit there is more room than between the other columns', () => {
    assert.match(ruleOf('.panel-lines .cols-4 > :nth-child(2),\n.panel-lines .cols-5 > :nth-child(2),\n.panel-lines .cols-6 > :nth-child(2)'), /padding-right:\s*[1-9]/);
    assert.match(ruleOf('.panel-lines .cols-4 > :nth-child(3),\n.panel-lines .cols-5 > :nth-child(3),\n.panel-lines .cols-6 > :nth-child(3)'), /padding-left:\s*[1-9]/);
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
    assert.match(html, /<input id="set-historyDate" type="checkbox"> Date as well as the time<\/label>/);
    assert.match(html, /<input id="set-historyDeltaUsed" type="checkbox"> Change in the amount used<\/label>/);
    assert.match(html, /<input id="set-historyDeltaTime" type="checkbox"> Time since the previous reading<\/label>/);
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

test('the button of the open panel is green, with the colour the severity uses', () => {
    assert.match(ruleOf('.icon.active'), /color:\s*var\(--ok\)/);
    assert.ok(css.indexOf('.icon.active {') > css.indexOf('.icon.tiny {'), 'it comes after the rule giving the buttons their gray, to win');
});

test('a margin as wide as a scrollbar is always kept at the right of the panel text, and the probe has it too', () => {
    assert.match(ruleOf('.panel-lines'), /padding-right:\s*16px/);
    assert.match(html, /id="table-probe" class="panel-lines table-probe"/, 'the probe is a panel-lines too, so it is measured with the margin');
    assert.doesNotMatch(ruleOf('.table-probe'), /padding/, 'and does not take it away');
});

test('the room between used and limit is one and a half characters on each side', () => {
    assert.match(ruleOf('.panel-lines .cols-4 > :nth-child(2),\n.panel-lines .cols-5 > :nth-child(2),\n.panel-lines .cols-6 > :nth-child(2)'), /padding-right:\s*1\.5ch/);
    assert.match(ruleOf('.panel-lines .cols-4 > :nth-child(3),\n.panel-lines .cols-5 > :nth-child(3),\n.panel-lines .cols-6 > :nth-child(3)'), /padding-left:\s*1\.5ch/);
});
