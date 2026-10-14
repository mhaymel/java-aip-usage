'use strict';

// The presentation requirements that can be checked without a window: the text
// is never fine, and the controls come in the required order.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WEB = path.join(__dirname, '../../main/resources/web');
// Read with line feeds whatever the checkout has: on Windows the files are checked out with CR LF, and the
// rules below that span two lines are looked for with a line feed between them.
const read = name => fs.readFileSync(path.join(WEB, name), 'utf8').replace(/\r\n/g, '\n');
const css = read('app.css');
const html = read('index.html');

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
});

test('the percentage is not shrunk or greyed out, since it is the first thing read', () => {
    assert.doesNotMatch(css, /\.percent\s*{[^}]*(font-size|color):/);
});

test('the base text is a sans-serif font', () => {
    assert.match(css, /font-family:[^;]*sans-serif/);
});

test('the controls come in the required order, in one strip', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    // Time first, then the percentage, the two amounts, the refresh button, the countdown, the two
    // changes, and the cost control, history, log and error log buttons (in this order since 0.81), the gear last.
    const order = ['id="time"', 'id="percent"', 'id="used"', 'id="limit"',
        'id="refresh"', 'id="countdown"', 'id="delta-used"', 'id="delta-time"',
        'id="cost-button"', 'id="history-button"', 'id="log-button"', 'id="errors-button"', 'id="settings-button"'];
    const positions = order.map(marker => strip.indexOf(marker));
    positions.forEach((position, i) => assert.ok(position >= 0, 'missing ' + order[i] + ' in the strip'));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions,
        'the order is time, percentage, used, limit, refresh, countdown, changes, cost, history, log, error log, settings');
});

test('the time is the very first thing in the strip', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    for (const later of ['id="percent"', 'id="used"', 'id="limit"', 'id="refresh"', 'id="countdown"', 'id="settings-button"']) {
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
    assert.match(html, /<span id="used"><\/span><span id="of" class="of"> of <\/span><span id="limit"><\/span>/, 'the word of is an element of its own, with a blank either side, and there is no slash');
    assert.doesNotMatch(html, /id="slash"/);
    const of = ruleOf('.of');
    assert.match(of, /font-size:\s*14px/, 'smaller than the figures, and no smaller than any text of the row may be');
    assert.match(of, /font-weight:\s*400/, 'and lighter');
    assert.match(of, /color:\s*var\(--muted\)/, 'and gray, so that the two figures stand out');
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

test('the settings view has the two buttons and a box for the interval, and no buttons for the whole main view', () => {
    assert.doesNotMatch(html, /settings-maximum|settings-minimum|Maximum view|Minimum view/);
    assert.doesNotMatch(html, /settings-restore|Restore defaults/, 'there is no Restore defaults');
    for (const id of ['settings-save', 'settings-cancel']) {
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

test('the settings button is at the right-hand end of the row, after the icons of the views, which come after everything else', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const cost = strip.indexOf('id="cost-button"');
    const history = strip.indexOf('id="history-button"');
    const log = strip.indexOf('id="log-button"');
    const settings = strip.indexOf('id="settings-button"');
    assert.ok(cost >= 0 && log >= 0 && history >= 0 && settings >= 0, 'all four are in the row');
    for (const earlier of ['id="time"', 'id="percent"', 'id="used"', 'id="refresh"', 'id="countdown"', 'id="delta-used"', 'id="delta-time"']) {
        assert.ok(strip.indexOf(earlier) < cost, earlier + ' comes before the first icon, the cost icon');
    }
    assert.ok(cost < history && history < log && log < settings, 'the cost icon, the history icon, the log icon, then the settings button');
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
    for (const name of ['log', 'history', 'errors', 'cost']) {
        assert.match(html, new RegExp('id="panel-error-' + name + '"[^>]*role="alert"'), 'each view has its own red line: ' + name);
    }
});

/** The declarations of the rule for one selector in app.css. */
function ruleOf(selector) {
    const escaped = selector.replace(/[.#()>:*+?^$|\[\]]/g, '\\$&');
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
    assert.equal(font[4], 'var(--list-font)', 'the typeface of the lists, named in one place');
    assert.match(css.match(/--list-font:\s*([^;]+);/)[1], /monospace$/, 'a fixed-width one, the system\'s own last');
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

test('the panels, the line of the log\'s two choices and the settings view are the only text under 14 px', () => {
    const small = [...css.matchAll(/([^{}]+){([^}]*)}/g)]
        .filter(rule => [...rule[2].matchAll(/font(?:-size)?:\s*(?:[a-z0-9 ]*\s)?([0-9.]+)px/g)].some(m => Number(m[1]) < 14))
        .map(rule => rule[1].trim().split('\n').pop().trim());
    assert.deepEqual([...small].sort(), ['.cost', '.log-controls', '.panel', '.settings', '.table-probe']); // the probe is the panel's text, never seen
});

test('the log panel has a line at its top with the level and the date, at the left, in that order', () => {
    const line = html.slice(html.indexOf('<div id="log-controls-log"'), html.indexOf('<p id="panel-note-log"'));
    assert.match(line, /^<div id="log-controls-log" class="log-controls">\s*<span id="log-choices" class="log-choices" hidden>/, 'shown with its view, and the two choices in it when the log has said what is saved');
    assert.ok(html.indexOf('id="log-controls-log"') > html.indexOf('<div id="panel"'), 'in the panel');
    assert.ok(html.indexOf('id="log-controls-log"') < html.indexOf('id="panel-lines-log"'), 'above the lines');
    assert.match(line, /<label[^>]*>Level\s*<select id="log-level"/, 'the label, then the dropdown');
    assert.deepEqual([...line.matchAll(/<option value="([a-z]+)">([A-Za-z]+)<\/option>/g)].map(m => m[1] + ':' + m[2]),
        ['error:Error', 'warning:Warning', 'info:Info', 'debug:Debug'], 'four levels, the most severe first');
    assert.match(line, /<input id="log-date" type="checkbox"> Date<\/label>/);
    assert.ok(line.indexOf('log-level') < line.indexOf('log-date'), 'the level first');
    assert.match(line, /<p id="log-error-log" class="panel-error" role="alert" hidden><\/p>/, 'and a red line for a save that failed');
    assert.doesNotMatch(html.slice(html.indexOf('id="settings-view"')), /log-level|log-date/, 'the settings view does not have the two');

    const rule = ruleOf('.log-controls');
    assert.match(rule, /display:\s*flex/);
    assert.match(rule, /flex:\s*none/, 'it does not scroll or shrink with the lines');
    assert.doesNotMatch(rule, /justify-content/, 'at the left');
    assert.match(rule, /font:\s*700 12px\/1\.45 system-ui/, 'written like the settings view');
    assert.match(css, /\.log-controls\[hidden\]\s*{\s*display:\s*none/, 'hidden wins over the flex');
    assert.match(css, /\.log-controls select,\s*\.settings select\s*{/, 'and its dropdown is the settings\' dropdown');
});

test('the heading of Main view has a small button with an icon and no text that selects every element, or none', () => {
    const legend = html.slice(html.indexOf('<legend>Main view'), html.indexOf('</legend>', html.indexOf('<legend>Main view')));
    assert.match(legend, /^<legend>Main view<button id="set-all" class="select-all" type="button"/, 'in the line of the heading, after it');
    assert.match(legend, /title="Select every element, or none"/);
    assert.match(legend, /aria-label="Select every element, or none"/, 'it has no text, so it has a name');
    assert.match(legend, /<rect x="2" y="2" width="12" height="12" rx="2"\/>\s*<path d="M5 8\.2l2\.2 2\.3L11 5\.6"\/>/, 'a square with a tick in it');
    assert.equal(legend.replace(/<svg[\s\S]*<\/svg>/, '').replace(/<[^>]*>/g, '').trim(), 'Main view', 'no text but the heading');
    assert.equal((html.match(/class="select-all"/g) || []).length, 2, 'Main view has one, and History view');

    const rule = ruleOf('.settings .select-all');
    assert.match(rule, /border-color:\s*transparent/, 'an icon, not a boxed button like Apply');
    assert.match(rule, /color:\s*CanvasText/);
    assert.match(rule, /margin-left:\s*\d+px/, 'at the right of the heading');
});

test('the line of an answer with no spend is an element of its own below the row, after the line of a 429', () => {
    assert.match(html, /<p id="alert-note" class="note note-alert" role="status" hidden><\/p>\s*<p id="hover-note" class="note note-info" role="status" hidden><\/p>/);
    assert.ok(html.indexOf('id="hover-note"') < html.indexOf('<div id="panel"'), 'in the row\'s box, so the window is sized by it');
});

test('the line that says an answer had no spend is muted, and the lines of a failure are red', () => {
    assert.match(ruleOf('.note-info'), /color:\s*var\(--muted\)/);
    assert.match(css, /\.note-stale,\s*\.note-error\s*{\s*color:\s*var\(--bad\)/);
});

test('while a panel is shown the row is as wide as the window, and with none a message line gives it a least width', () => {
    const stretched = ruleOf('#app.open .top,\n#app.fit .top');
    assert.match(stretched, /width:\s*auto/, 'the width of the app, which is the window\'s then');
    assert.match(stretched, /max-width:\s*none/, 'not held at the 900 pixels of a row');
    assert.match(css, /#app:not\(\.open\):not\(\.fit\) \.top\.noted\s*{\s*min-width:\s*300px/, 'with no panel, and only while a message is shown');
    assert.match(ruleOf('.top'), /width:\s*max-content/, 'with no panel and no message the row is as wide as its elements');
    assert.match(ruleOf('.top'), /max-width:\s*900px/);
    assert.match(ruleOf('.note'), /min-width:\s*100%/, 'a message is as wide as the row\'s box, whatever that is');
    assert.doesNotMatch(ruleOf('.strip'), /justify-content/, 'the elements stay at the left');
});

test('the line at the top of a list has two buttons that make its text larger and smaller, after the log\'s two choices', () => {
    const line = html.slice(html.indexOf('<div id="log-controls-log"'), html.indexOf('<p id="log-error-log"'));
    assert.ok(line.indexOf('id="log-choices"') < line.indexOf('id="zoom-in-log"'), 'after the level and the date');
    assert.ok(line.indexOf('</span>') < line.indexOf('id="zoom-in-log"'), 'and outside them');
    assert.match(line, /<button id="zoom-in-log" class="icon tiny" type="button" aria-label="Larger text" title="Larger text">/);
    assert.match(line, /<button id="zoom-out-log" class="icon tiny" type="button" aria-label="Smaller text" title="Smaller text">/);
    assert.ok(line.indexOf('id="zoom-in-log"') < line.indexOf('id="zoom-out-log"'));
    // The history and the error log have the two buttons too, each in its own line, drawn the same.
    for (const name of ['history', 'errors']) {
        const own = html.slice(html.indexOf('<div id="log-controls-' + name + '"'), html.indexOf('<p id="log-error-' + name + '"'));
        assert.equal(own.slice(own.indexOf('<span class="zoom">'), own.indexOf('</span>')).split('-' + name).join('-log'),
            line.slice(line.indexOf('<span class="zoom">'), line.indexOf('</span>', line.indexOf('<span class="zoom">'))), 'the zoom of ' + name);
    }
    const icons = [...line.matchAll(/<button id="zoom-(in|out)-log"[\s\S]*?<\/button>/g)].map(m => m[0]);
    assert.equal(icons.length, 2);
    for (const icon of icons) {
        assert.match(icon, /<circle cx="6\.8" cy="6\.8" r="4\.6"\/>/, 'a magnifying glass');
        assert.equal(icon.replace(/<svg[\s\S]*<\/svg>/, '').replace(/<[^>]*>/g, '').trim(), '', 'an icon and no text');
    }
    assert.match(icons[0], /M6\.8 4\.6v4\.4/, 'the plus has its upright');
    assert.doesNotMatch(icons[1], /M6\.8 4\.6v4\.4/, 'the minus has none');
    assert.match(css, /\.icon\[disabled\]\s*{\s*opacity:\s*0\.35/,'the button that would lead out of the range is faint');
});

test('the text of the lists is as large as the page says, twelve pixels when it says nothing, and a line a quarter taller', () => {
    for (const rule of [ruleOf('.panel-lines'), ruleOf('.table-probe')]) {
        assert.match(rule, /font-size:\s*var\(--list-size, 12px\)/);
        assert.match(rule, /line-height:\s*calc\(var\(--list-size, 12px\) \* 1\.25\)/);
    }
    assert.ok(css.indexOf('font-size: var(--list-size', css.indexOf('.table-probe {')) > css.indexOf('font: 400 12px/15px', css.indexOf('.table-probe {')),
        'in the probe after the font it is given, so that the table is measured at the size it is drawn at');
    assert.doesNotMatch(ruleOf('.panel-note'), /list-size/, 'the notes of a list keep their size');
    assert.doesNotMatch(ruleOf('.log-controls'), /list-size/, 'and so does the line of the controls');
});

test('the group Request has the checkbox Automatic in the line of the interval, after the field and its unit', () => {
    const request = html.slice(html.indexOf('<legend>Request</legend>'), html.indexOf('<legend>Window</legend>'));
    assert.match(request, /<span class="line">\s*<label title="[^"]+">Interval\s*<input id="set-usageIntervalSeconds"[^>]*> s<\/label>\s*<label title="[^"]+"><input id="set-autoInterval" type="checkbox"> Automatic<\/label>\s*<\/span>/);
    assert.match(request, /title="Lets the program find the shortest time between usage requests that Anthropic accepts, between 10 and 300 seconds\."/);
    assert.match(css, /\.settings input\[type="text"\]\[disabled\]\s*{\s*opacity:\s*0\.5/, 'the field is grayed while it is not to be typed in');
});

test('the settings have no button About and no Back: About is a tab', () => {
    const buttons = html.slice(html.indexOf('<p id="settings-buttons" class="buttons">'), html.indexOf('</p>', html.indexOf('id="settings-buttons"')));
    assert.doesNotMatch(buttons, /about/i, 'not in the row of Save and Cancel');
    assert.doesNotMatch(html, /id="about-back"|settings-about|about-button/);
    assert.doesNotMatch(css, /about-button/);
    assert.doesNotMatch(html.slice(html.indexOf('<div id="top"'), html.indexOf('<div id="panel"')), /about/i, 'the row of the window has no button for it');
    assert.match(html, /<button id="tabbtn-about" class="tab" type="button" role="tab" data-tab="about">About<\/button>/);
});
test('the About view has the logo at 96 pixels, the name and the version, a table of two columns and no button', () => {
    const view = html.slice(html.indexOf('<div id="about-view"'), html.indexOf('<p id="settings-buttons"'));
    assert.match(view, /^<div id="about-view" class="about" hidden>/, 'hidden until it is asked for');
    assert.ok(html.indexOf('id="about-view"') > html.indexOf('id="settings-view"') && html.indexOf('id="about-view"') < html.indexOf('id="settings-buttons"'), 'a part of the settings view, above Save and Cancel');
    assert.match(view, /<img id="about-logo" class="about-logo" src="icon\.png" width="96" height="96" alt="[^"]+">/);
    assert.match(view, /<p id="about-name" class="about-name">aip usage<\/p>\s*<p id="about-version" class="about-version"><\/p>/);
    assert.deepEqual([...view.matchAll(/<span class="about-label">([^<]+)<\/span><span(?: class="about-path"><a)? id="(about-[a-z]+)"/g)].map(m => m[1] + ':' + m[2]),
        ['Settings:about-settings', 'Log:about-log', 'Usage history:about-history', 'System:about-system', 'Debug mode:about-debug']);
    // What the program runs on has no name in the page: the backend says it, so that the page is the same for a backend in any language.
    assert.match(view, /<span id="about-runtime-label" class="about-label"><\/span><span id="about-runtime"><\/span>\s*<span id="about-toolkit-label" class="about-label"><\/span><span id="about-toolkit"><\/span>\s*<span class="about-label">System<\/span>/);
    assert.doesNotMatch(html + read('app.js') + read('view.js'), /JavaFX|>Java<|['"]Java['"]/, 'no name of a language or a toolkit in the page');
    assert.deepEqual([...view.matchAll(/<button id="([a-z-]+)"[^>]*>([^<]+)<\/button>/g)].map(m => m[1] + ':' + m[2]), [], 'no button with a text');

    assert.match(ruleOf('.about-logo'), /width:\s*96px/);
    assert.match(ruleOf('.about-logo'), /height:\s*96px/);
    assert.doesNotMatch(ruleOf('.about'), /text-align:\s*center/, 'nothing is in the middle any more');
    assert.match(ruleOf('.about'), /width:\s*0/, 'it never makes the window wider');
    assert.match(ruleOf('.about'), /min-width:\s*100%/);
    assert.match(ruleOf('.about-details'), /grid-template-columns:\s*max-content minmax\(0, 1fr\)/);
    assert.match(ruleOf('.about-label'), /color:\s*var\(--muted\)/);
    assert.match(css, /\.about-details span:not\(\.about-label\)\s*{\s*overflow-wrap:\s*anywhere/, 'a long path breaks');
    assert.doesNotMatch(css.slice(css.indexOf('.about {'), css.indexOf('.settings button {')), /user-select/, 'nothing of it can be selected, like the rest of the window');
});

test('the row keeps room for the usual values only, so that its elements do not stand far apart', () => {
    assert.match(ruleOf('.delta-used'), /min-width:\s*4\.5ch/, 'the change in the amount used: +9.99');
    assert.match(ruleOf('.delta-time'), /min-width:\s*3ch/, 'the time since the previous reading: 99s');
    assert.match(ruleOf('.delta-duration'), /min-width:\s*5ch/, 'the request duration: 999ms');
    assert.match(ruleOf('.delta-interval'), /min-width:\s*4ch/, 'the interval is as it was: 300s');
    assert.match(ruleOf('.countdown'), /min-width:\s*3\.5ch/, 'and so is the countdown');
    assert.doesNotMatch(ruleOf('.delta'), /max-width|overflow/, 'a longer value is not cut off: the row is wider for it');
});

test('the three paths of About are links that are not bold, each with a button of an icon that copies it', () => {
    const view = html.slice(html.indexOf('<div id="about-view"'), html.indexOf('<p id="settings-buttons"'));
    for (const name of ['settings', 'log', 'history']) {
        assert.match(view, new RegExp('<span class="about-path"><a id="about-' + name + '" class="about-link" href="#" title="Open the folder of this file"></a>\\s*<button id="about-copy-' + name
            + '" class="icon tiny copy" type="button" aria-label="Copy the path" title="Copy the path">'), name);
    }
    for (const plain of ['runtime', 'toolkit', 'system', 'debug']) {
        assert.match(view, new RegExp('<span id="about-' + plain + '"></span>'), plain + ' is text, no link');
    }
    assert.equal((view.match(/class="about-link"/g) || []).length, 3);
    assert.equal((view.match(/class="copy-icon"/g) || []).length, 3, 'the icon of a copy');
    assert.equal((view.match(/class="copied-icon"/g) || []).length, 3, 'and the tick that takes its place for a moment');

    const link = ruleOf('.about-link');
    assert.match(link, /font-weight:\s*400/, 'not bold');
    assert.match(ruleOf('.about-link .about-file'), /font-weight:\s*700/, 'but for the name of the file, the last part of the path');
    assert.match(link, /color:\s*var\(--info\)/, 'the blue of the notes');
    assert.match(link, /text-decoration:\s*underline/);
    assert.match(link, /cursor:\s*pointer/);
    assert.match(css, /\.copy \.copied-icon,\s*\.copy\.done \.copy-icon\s*{\s*display:\s*none/, 'one of the two icons at a time');
    assert.match(ruleOf('.copy.done .copied-icon'), /color:\s*var\(--ok\)/);
    assert.doesNotMatch(link, /user-select/, 'a link cannot be selected either: the button is how a path is copied');
});

test('the Debug tab has a group for how the fake backend answers: the account, the increase, the two amounts, the two times', () => {
    const view = html.slice(html.indexOf('<div id="tab-debug"'), html.indexOf('<div id="about-view"'));
    assert.match(view, /<fieldset id="debug-fake">\s*<legend>Fake backend, scenario normal<\/legend>/);
    assert.match(view, /<label title="[^"]+">Account\s*<select id="debug-fakeAccount"[^>]*><\/select><\/label>/, 'the kinds of account are the backend\'s to name');
    const fields = [...view.matchAll(/<label title="[^"]{30,}">([A-Za-z ]+)\s*<input id="(debug-fake[A-Za-z]+)" class="(number|amount)" type="text"[^>]*> ([A-Za-z]+)<\/label>/g)]
        .map(m => m[1].trim() + '|' + m[2] + '|' + m[3] + '|' + m[4]);
    assert.deepEqual(fields, [
        'Largest increase|debug-fakeIncreaseCents|number|cents',
        'Budget|debug-fakeBudget|amount|USD',
        'Starting amount|debug-fakeStartAmount|amount|USD',
        'Refuse within|debug-fakeRefuseSeconds|number|s',
        'Longest request|debug-fakeDelayMillis|number|ms',
    ], 'each with its unit after the field, and a tooltip that says what it does');
    assert.ok(html.indexOf('id="debug-fake"') > html.indexOf('id="debug-fakeToken"'), 'after the three switches');
    assert.match(css, /\.settings input\[type="text"\]\.number\s*{\s*width:\s*7ch/);
    assert.match(css, /\.settings input\[type="text"\]\.amount\s*{\s*width:\s*10ch/);
});

test('the settings have a tab Cost control, with the monthly limit, the daily limit and its checkbox Automatic', () => {
    const order = ['id="tab-general"', 'id="tab-main"', 'id="tab-history"', 'id="tab-cost"', 'id="tab-debug"', 'id="about-view"'].map(id => html.indexOf(id));
    assert.ok(order.every(p => p >= 0) && order.every((p, i) => i === 0 || order[i - 1] < p), 'the pages are in the order of the tabs');
    const group = html.slice(order[3], order[4]);
    assert.match(group, /<legend>Cost control<\/legend>/);
    assert.match(group, /<label title="[^"]{40,}">Monthly limit\s*<input id="set-costMonthlyLimit" class="amount" type="text"[^>]*> USD<\/label>/);
    assert.match(group, /<span class="line">\s*<label title="[^"]+">Daily limit\s*<input id="set-costDailyLimit" class="amount" type="text"[^>]*> USD<\/label>\s*<label title="[^"]{40,}"><input id="set-costDailyAutomatic" type="checkbox"> Automatic<\/label>\s*<\/span>/,
        'the checkbox in the line of the field it belongs to');
    assert.match(group, /Monday to Friday/, 'the tooltip says how it is worked out');
    assert.doesNotMatch(group, /\$/, 'the unit is the code: the page has no currency sign');
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

// The width a kind of column has, in characters, with or without the date.
const widthOf = (kind, date) => Number(ruleOf('.panel-lines .columns' + (date ? '.date' : '') + ' > .c-' + kind).match(/width:\s*(\d+)ch/)[1]);

test('the history is a table of fixed columns, the time at the left and the others right-aligned, with the spare width shared out between them', () => {
    const table = ruleOf('.panel-lines .columns');
    assert.match(table, /display:\s*grid/);
    assert.match(table, /grid-auto-flow:\s*column/, 'the columns that are shown, one after another');
    assert.match(table, /grid-auto-columns:\s*max-content/, 'each as wide as its cell says');
    assert.match(table, /justify-content:\s*space-between/, 'equal room between the columns, the first and the last at the edges');
    assert.doesNotMatch(table, /1fr|grid-template-columns/, 'no column takes what room there is, and none has its width by its place');
    assert.ok(Number(table.match(/column-gap:\s*(\d+)px/)[1]) <= 10, 'little space between the columns');
    assert.ok(widthOf('time') >= '21:01:22'.length, 'the time is a fixed column too, with room for the time of day');
    assert.ok(widthOf('time', true) >= '2026-10-08 21:01:22'.length, 'with the date the time is wider');
    const amounts = ruleOf('.panel-lines .columns > .c-used,\n.panel-lines .columns > .c-limit').match(/width:\s*(\d+)ch/);
    assert.ok(Number(amounts[1]) >= '9999999.99'.length, 'used and limit hold the amounts the requirements name');
    assert.ok(widthOf('currency') >= 'USD'.length && widthOf('currency') >= 'Cur.'.length, 'wide enough for the code and its short title');
    assert.match(ruleOf('.panel-lines .columns > *'), /text-align:\s*right/, 'the others are at the right of their columns');
    assert.match(ruleOf('.panel-lines .columns > .c-time'), /text-align:\s*left/, 'the time at the left');
    assert.ok(css.indexOf('.panel-lines .columns > .c-time {') > css.indexOf('.panel-lines .columns > * {'), 'which comes after, to win');
});

test('a column of the history has its width by what it is, not by its place: no rule by the number of columns is left', () => {
    assert.doesNotMatch(css, /\.cols-[3-9]/, 'the rules by count, which gave the last column the width of the currency whatever it was, are gone');
    assert.deepEqual(['time', 'deltaUsed', 'deltaTime', 'currency'].map(kind => widthOf(kind)), [8, 9, 8, 5], 'the time, the two changes and the currency, in characters');
    assert.ok(widthOf('deltaTime') >= '\u0394 time'.length && widthOf('deltaTime') >= '3600 s'.length, 'the time change has room for its title and its values, also as the last column');
    assert.ok(widthOf('deltaUsed') >= '+1,234.50'.length);
    assert.equal(widthOf('time', true), 19, 'with the date');
});

test('the titles are aligned like their columns: the time at the left, the others at the right, wherever each is', () => {
    assert.doesNotMatch(css, /\.head > :first-child/, 'not the first title at the left whatever it is: with the time switched off it is a figure');
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
    for (const id of ['cost-button', 'history-button', 'log-button', 'errors-button', 'settings-button']) {
        assert.ok(group.includes('id="' + id + '"'), id + ' is in the group');
    }
    assert.ok(group.indexOf('id="cost-button"') < group.indexOf('id="history-button"'));
    assert.ok(group.indexOf('id="history-button"') < group.indexOf('id="log-button"'));
    assert.ok(group.indexOf('id="log-button"') < group.indexOf('id="errors-button"'));
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
    assert.match(ruleOf('.panel-lines .columns'), /min-width:\s*max-content/);
    assert.match(ruleOf('.panel-lines'), /overflow-x:\s*auto/, 'only when needed: no room is reserved for it');
});

test('the error log rows have the time and the message, which is not wrapped', () => {
    const rule = ruleOf('.panel-lines .cols-2');
    assert.match(rule, /display:\s*grid/);
    assert.match(rule, /grid-template-columns:\s*\d+ch max-content/);
});

test('nothing in the window can be selected but what is typed in the field of the interval', () => {
    const page = css.slice(css.indexOf('html,\nbody {'), css.indexOf('}', css.indexOf('html,\nbody {')));
    assert.match(page, /-webkit-user-select:\s*none/);
    assert.match(page, /[^-]user-select:\s*none/);
    const allowed = [...css.matchAll(/([^{}]+){([^}]*user-select:\s*text[^}]*)}/g)].map(rule => rule[1].trim().split('\n').pop().trim());
    assert.deepEqual(allowed, ['.settings input[type="text"]'], 'the one exception: a field to type in');
    assert.doesNotMatch(ruleOf('.settings'), /user-select/, 'the settings are no exception any more');
    assert.doesNotMatch(ruleOf('.panel-lines'), /user-select/, 'nor are the lists');
});

test('the dropdowns of the settings have room for the value and the arrow', () => {
    const select = ruleOf('.settings select');
    assert.match(select, /min-width:\s*\d+ch/);
    assert.match(select, /padding:\s*\d+px\s+[2-9](\.\d+)?em\s+\d+px\s+\d+px/, 'room on the right, for the arrow');
});

test('the settings texts are the ones the requirements give', () => {
    assert.match(html, /<label title="[^"]+">Interval\s*<input id="set-usageIntervalSeconds"/);
    assert.match(html, /<input id="set-showInterval" type="checkbox"> Interval<\/label>/);
    assert.match(html, /<input id="set-logResponse" type="checkbox"> Log the response<\/label>/);
    assert.match(html, /<legend>History view<button id="set-all-history"/);
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

test('the button of the open panel is the blue of the notes; the other icons are as they were', () => {
    assert.match(ruleOf('.icon.active'), /color:\s*var\(--info\)/);
    assert.doesNotMatch(ruleOf('.icon.active'), /var\(--ok\)/, 'no longer green');
    assert.doesNotMatch(ruleOf('.icon'), /--info|--ok/, 'the other icons keep the colour of the text');
    assert.doesNotMatch(css, /--active/, 'the vivid green is gone');
    assert.doesNotMatch(css, /#00b341|#3ddc6b/);
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
    assert.match(css, /\.settings label input\[type="checkbox"\]\s*{[^}]*margin:\s*0/, 'no margin of the browser to push it off centre');
});

test('nothing in the settings wraps: every text on one line and the two buttons in one row', () => {
    assert.match(ruleOf('.settings'), /white-space:\s*nowrap/);
    for (const rule of ['.settings label', '.settings .line', '.settings .buttons']) {
        assert.doesNotMatch(ruleOf(rule), /flex-wrap:\s*wrap/, rule);
    }
    assert.match(ruleOf('.settings .buttons'), /width:\s*max-content/, 'measured at its own width');
    assert.match(ruleOf('#settings-form,\n#debug-form'), /width:\s*max-content/);
    assert.match(ruleOf('#settings-form,\n#debug-form'), /min-width:\s*100%/, 'and as wide as the window when that is wider');
    assert.match(ruleOf('.tabs'), /width:\s*0/, 'the strip of the titles has no width the window is measured to');
    assert.match(ruleOf('.tabs'), /min-width:\s*100%/);
    assert.match(ruleOf('.tab-strip'), /overflow-x:\s*auto/, 'it scrolls sideways when it is wider than the view');
    const buttons = html.slice(html.indexOf('<p id="settings-buttons" class="buttons">'), html.indexOf('</p>', html.indexOf('id="settings-buttons"')));
    assert.deepEqual([...buttons.matchAll(/id="(settings-[a-z]+)"/g)].map(m => m[1]), ['settings-buttons', 'settings-save', 'settings-cancel']);
    assert.match(ruleOf('.settings .panel-error'), /white-space:\s*normal/, 'a message may break');
});

test('the History view tab has the date, the two lines, and a checkbox for every column in the order of the view', () => {
    const view = html.slice(html.indexOf('<legend>History view<'), html.indexOf('id="tab-cost"'));
    const order = ['set-historyDate', 'set-historyZeroLines', 'set-historyFailedLines', 'set-historyTime', 'set-historyUsed', 'set-historyLimit', 'set-historyDeltaUsed', 'set-historyDeltaTime', 'set-historyCurrent'];
    const positions = order.map(id => view.indexOf('id="' + id + '"'));
    positions.forEach((p, i) => assert.ok(p >= 0, order[i] + ' is in the History view group'));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions);
    assert.match(view, /<input id="set-historyCurrent" type="checkbox"> Cur\./);
});
const MAIN = html.slice(html.indexOf('<legend>Main view'), html.indexOf('<legend>History view<'));
const MAIN_CHECKBOXES = [
    ['set-showTime', 'Time'], ['set-showPercentage', 'Percentage spent'], ['set-showUsed', 'Amount used'], ['set-showLimit', 'Budget'],
    ['set-showRefresh', 'Refresh button'], ['set-showCountdown', 'Countdown'], ['set-showInterval', 'Interval'],
    ['set-showDeltaUsed', 'Change in the amount used'], ['set-showDeltaTime', 'Time since the previous reading'], ['set-showDuration', 'Request duration'],
    ['set-showCostIcon', 'Cost icon'], ['set-showHistoryIcon', 'History icon'], ['set-showLogIcon', 'Log icon'],
    ['set-showErrorIcon', 'Error log icon'],
];

test('the Main view group has a checkbox for each of the fourteen elements, named as the requirements name them', () => {
    for (const [id, text] of MAIN_CHECKBOXES) {
        assert.ok(MAIN.includes('<input id="' + id + '" type="checkbox"> ' + text + '</label>'), id);
    }
});

test('the checkboxes are in the order of the row, from left to right', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const inRow = ['id="time"', 'id="percent"', 'id="used"', 'id="limit"', 'id="refresh"', 'id="countdown"', 'id="interval"', 'id="delta-used"',
        'id="delta-time"', 'id="duration"', 'id="cost-button"', 'id="history-button"', 'id="log-button"', 'id="errors-button"'].map(id => strip.indexOf(id));
    const inSettings = MAIN_CHECKBOXES.map(([id]) => MAIN.indexOf('id="' + id + '"'));
    for (const positions of [inRow, inSettings]) {
        assert.ok(positions.every(p => p >= 0));
        assert.deepEqual([...positions].sort((a, b) => a - b), positions);
    }
});

test('each checkbox of the Main view has a tooltip on its text that says what the element shows', () => {
    for (const [id, text] of [...MAIN_CHECKBOXES, ['set-showCurrency', 'Currency symbol']]) {
        const label = MAIN.match(new RegExp('<label title="([^"]+)"><input id="' + id + '" type="checkbox"> ' + text + '</label>'));
        assert.ok(label, id + ' has a title');
        assert.ok(label[1].length >= 40 && label[1].endsWith('.'), id + ': a sentence, not a word: ' + label[1]);
    }
    assert.match(MAIN, /<select id="set-timeFormat"[^>]*title="[^"]{20,}"/);
});

test('the time format is beside the Time checkbox and the currency symbol beside Amount used, each in one line', () => {
    const lines = [...MAIN.matchAll(/<span class="line">([\s\S]*?)<\/span>/g)].map(m => m[1]);
    assert.equal(lines.length, 2);
    assert.ok(lines[0].indexOf('id="set-showTime"') >= 0 && lines[0].indexOf('id="set-showTime"') < lines[0].indexOf('id="set-timeFormat"'));
    assert.ok(lines[1].indexOf('id="set-showUsed"') >= 0 && lines[1].indexOf('id="set-showUsed"') < lines[1].indexOf('id="set-showCurrency"'));
    assert.match(ruleOf('.settings .line'), /display:\s*flex/);
    assert.match(ruleOf('.settings .line'), /align-items:\s*center/);
});

test('the General tab has the groups Request, Window and Log, with Title bar before Stay on top', () => {
    const order = ['id="tab-general"', '<legend>Request</legend>', '<legend>Window</legend>', '<legend>Log</legend>'].map(legend => html.indexOf(legend));
    assert.ok(order.every(p => p >= 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    const group = html.slice(html.indexOf('<legend>Window</legend>'), html.indexOf('<legend>Log</legend>'));
    assert.match(group, /<label title="[^"]{40,}"><input id="set-showTitleBar" type="checkbox"> Title bar<\/label>/);
    assert.match(group, /<label title="[^"]{40,}"><input id="set-stayOnTop" type="checkbox"> Stay on top<\/label>/);
    assert.ok(group.indexOf('set-showTitleBar') < group.indexOf('set-stayOnTop'), 'the title bar first');
});
test('the gray box of an element with no value is a green line at its bottom and nothing else, as thick as the line under the selected tab, and has a width of its own', () => {
    const rule = ruleOf('.skeleton');
    assert.doesNotMatch(rule, /background(-color)?\s*:/, 'nothing fills it: the background of the window is seen in the frame');
    assert.doesNotMatch(css, /rgba\(138, 143, 152/, 'the gray third is gone');
    assert.doesNotMatch(css, /--box\b/, 'and so is the light green it was filled with after that, on both themes');
    assert.doesNotMatch(rule, /opacity/, 'the strength is the colour\'s own, or the frame would be as faint');
    assert.match(rule, /border-bottom:\s*2px solid var\(--ok\)/, 'a line at the bottom, in the green of the figures');
    assert.match(rule, /border:\s*0;/, 'and no frame at the top or at the sides');
    assert.ok(rule.indexOf('border-bottom:') > rule.indexOf('border: 0'), 'the line comes after, so that it is there');
    assert.doesNotMatch(rule, /border:\s*\d+px solid/, 'the frame all the way round is gone');
    assert.equal(rule.match(/border-bottom:\s*(\d+px)/)[1], ruleOf('.settings .tab').match(/border-bottom:\s*(\d+px)/)[1], 'as thick as the line under the selected tab');
    assert.match(rule, /display:\s*inline-block/, 'the place keeps its width and its height: the tooltip comes anywhere in it');
    assert.match(ruleOf('.delta-used,\n.delta-time,\n.delta-duration,\n.spend'), /color:\s*var\(--ok\)/,
        'which is the very colour the text of the figures has, on both themes');
    assert.match(rule, /box-sizing:\s*border-box/, 'inside the box, which is as wide and as tall as it was');
    assert.doesNotMatch(css, /\.placeholder\s*{/, 'the words No data and No usage reported have no style left');
    assert.match(rule, /border-radius:\s*0\b/, 'square corners, said outright so that nothing rounds them');
    assert.match(rule, /color:\s*transparent/);
    for (const id of ['time', 'percent', 'used', 'limit']) {
        assert.match(css, new RegExp('#' + id + '\\.skeleton\\s*{\\s*min-width:\\s*[0-9.]+ch'), id);
    }
    assert.doesNotMatch(html, /id="placeholder"/, 'and the row has no place for a word, at the start or after');
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
    const request = html.slice(html.indexOf('<legend>Request</legend>'), html.indexOf('<legend>Window</legend>'));
    assert.match(request, /<input id="set-usageIntervalSeconds" type="text"/);
    assert.match(request, /> s<\/label>/, 'the unit s beside it');
    assert.equal((request.match(/<select/g) || []).length, 0, 'no dropdown');
});

test('the start line of the history and of the log is blue text, the blue of the notes, with no background of its own, and not bold', () => {
    for (const selector of ['.panel-lines .start', '.panel-lines .mark']) {
        const rule = ruleOf(selector);
        assert.match(rule, /color:\s*var\(--info\)/, selector + ': the blue of the notes and of the open panel\'s icon');
        assert.doesNotMatch(rule, /background/, selector + ': no background of its own');
        assert.doesNotMatch(rule, /font-weight/, selector + ': not bold');
        assert.doesNotMatch(rule, /--ok/, selector + ': the green is gone');
    }
    assert.ok(css.indexOf('.panel-lines .failed {') > css.indexOf('.panel-lines .start {'), 'the red of failed comes after the blue, to win in a failed start line');
});

test('the seconds of the row have room for their usual values without the space: 99s, 120s, 3600s', () => {
    assert.match(ruleOf('.countdown'), /min-width:\s*3\.5ch/);
    assert.match(ruleOf('.delta-interval'), /min-width:\s*4ch/);
    assert.match(ruleOf('.delta-time'), /min-width:\s*3ch/);
});

test('the request duration is the last of the figures, before the buttons, with room for 1s034ms', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    assert.ok(strip.indexOf('id="delta-time"') < strip.indexOf('id="duration"'));
    assert.ok(strip.indexOf('id="duration"') < strip.indexOf('id="log-button"'));
    assert.match(html, /<span id="duration" class="delta delta-duration" hidden><\/span>/, 'hidden until the backend says it is on');
    assert.match(ruleOf('.delta-duration'), /min-width:\s*5ch/);
});

test('the percentage, the amounts, the two changes and the request duration are one fixed green, and no severity colours anything', () => {
    assert.match(ruleOf('.delta-used,\n.delta-time,\n.delta-duration,\n.spend'), /color:\s*var\(--ok\)/);
    assert.deepEqual([...css.matchAll(/--ok:\s*(#[0-9a-f]{6})/g)].map(m => m[1]), ['#1a7f37', '#3fb950'], 'the light theme, then the dark one');
    assert.doesNotMatch(css, /\.sev-/, 'no rule by severity');
    assert.match(css, /--warn:\s*#bf8700/,'amber since 0.82, for a limit about to be passed, never for the severity');
    for (const rule of ['.time', '.countdown', '.delta-interval']) {
        assert.doesNotMatch(ruleOf(rule), /color/, rule + ' stays in the colour of the text');
    }
    assert.match(ruleOf('.countdown.alert'), /color:\s*var\(--bad\)/, 'the red of a back-off stays');
    assert.match(ruleOf('.countdown.auto'), /color:\s*var\(--info\)/, 'the blue of the open panel\'s button with the automatic interval on');
    assert.ok(css.indexOf('.countdown.alert {') > css.indexOf('.countdown.auto {'), 'the red comes after the blue, to win');
});

test('old figures are dimmed, the two changes with the amounts', () => {
    assert.match(ruleOf('.stale .time,\n.stale .spend,\n.stale .delta-used,\n.stale .delta-time,\n.stale .delta-duration'), /opacity:\s*0\.6/);
});

test('the start line has no background colour of its own on either theme: the very light blue is gone, like the gray before it', () => {
    assert.doesNotMatch(css, /--mark/, 'no variable for it');
    assert.doesNotMatch(css, /#ddf4ff|#0c2d6b/i, 'neither the light one nor the dark one');
    assert.doesNotMatch(css, /#e6e6e6|#30363d/, 'the gray is gone');
    assert.deepEqual([...css.matchAll(/--info:\s*(#[0-9a-f]{6})/g)].map(m => m[1]), ['#0969da', '#58a6ff'], 'the blue of the text: the light theme, then the dark one');
});

test('an optional item of the row with no value is a gray box of the item\'s own width, never an unseen gap, and is not dimmed', () => {
    assert.doesNotMatch(css, /\.delta\.empty/, 'nothing is kept unseen any more');
    for (const rule of ['.delta-used', '.delta-time', '.delta-duration', '.delta-interval']) {
        assert.match(ruleOf(rule), /min-width:\s*[0-9.]+ch/, rule + ' has a width of its own, which its gray box has too');
    }
    assert.match(ruleOf('.stale .skeleton,\n.stale .spend.boxes'), /opacity:\s*1\b/, 'the box and its border at their own strength, not that of the old figures');
    assert.ok(css.indexOf('.stale .skeleton') > css.indexOf('.stale .delta-duration'), 'after the rule that dims, so that it wins');
    assert.match(html, /0\.00 when it has not changed\./, 'the tooltip of the checkbox says what an unchanged amount shows');
});

// ---- debug mode

test('there is no debug button in the row, and no yellow of its own', () => {
    assert.doesNotMatch(html, /debug-button/);
    assert.doesNotMatch(css, /--debug:|\.icon\.debug/);
    assert.match(html, /<button id="tabbtn-debug" class="tab" type="button" role="tab" data-tab="debug" hidden>Debug<\/button>/, 'a tab, hidden until the page knows it is debug mode');
});
test('the debug settings are a tab of the settings, with the scenario beside the switch of the fake backend, and no buttons of their own', () => {
    const view = html.slice(html.indexOf('<div id="tab-debug"'), html.indexOf('<div id="about-view"'));
    assert.match(view, /^<div id="tab-debug" class="tab-page" hidden>/);
    assert.doesNotMatch(html, /id="debug-view"|debug-apply|debug-cancel|debug-buttons/);
    for (const id of ['debug-fakeBackend', 'debug-fakeToken']) {
        assert.match(view, new RegExp('<label title="[^"]{40,}"><input id="' + id + '" type="checkbox">'), id);
    }
    assert.match(view, /<select id="debug-fakeScenario"[^>]*><\/select>/, 'the scenarios are the backend\'s to name');
    const line = view.match(/<span class="line">([\s\S]*?)<\/span>/)[1];
    assert.ok(line.indexOf('debug-fakeBackend') < line.indexOf('debug-fakeScenario'), 'the scenario is beside the switch of the fake backend');
});

test('the tabs are in the order General, Main view, History view, Cost control, Debug, About, with no arrows', () => {
    const strip = html.slice(html.indexOf('id="settings-tabs"'), html.indexOf('id="settings-form"'));
    assert.deepEqual([...strip.matchAll(/data-tab="([a-z]+)"[^>]*>([^<]+)</g)].map(m => m[1] + ':' + m[2]),
        ['general:General', 'main:Main view', 'history:History view', 'cost:Cost control', 'debug:Debug', 'about:About'], 'General first, About last');
    const pages = ['id="tab-general"', 'id="tab-main"', 'id="tab-history"', 'id="tab-cost"', 'id="tab-debug"', 'id="about-view"'].map(id => html.indexOf(id));
    assert.deepEqual([...pages].sort((a, b) => a - b), pages, 'and the pages are in the order of the strip');
    assert.doesNotMatch(html, /tab-prev|tab-next|tab-arrow/, 'no arrows');
    assert.match(ruleOf('.settings .tab[aria-selected="true"]'), /color:\s*var\(--info\)/, 'the tab shown is blue');
    assert.match(ruleOf('.settings .tab[aria-selected="true"]'), /border-bottom-color:\s*var\(--info\)/);
    assert.match(css, /\.tab-page\[hidden\]\s*{\s*display:\s*none/);
});
test('with a settings view shown the app is as wide as the window, so that the red border goes round the view and not round the row alone', () => {
    const rule = ruleOf('#app.fit');
    assert.match(rule, /min-width:\s*100%/);
    assert.doesNotMatch(rule, /height/, 'the height stays the content\'s own: the window is sized to it');
    assert.match(ruleOf('#app.fake'), /box-sizing:\s*border-box/, 'and the border is inside that width');
});

test('an app with a border fills the window, so that the border is at its edges, but not while a settings view is measured', () => {
    const rule = ruleOf('#app.bare:not(.fit),\n#app.fake:not(.fit)');
    assert.match(rule, /min-width:\s*100%/);
    assert.match(rule, /min-height:\s*100%/);
    assert.doesNotMatch(ruleOf('#app.fit'), /height/, 'the height of a settings view is the content\'s own');
});

test('a window with no title bar has a border of one pixel in the colour of the text, inside its size', () => {
    const rule = ruleOf('#app.bare');
    assert.match(rule, /border:\s*1px solid CanvasText/, 'black on the light theme, light on the dark one');
    assert.match(rule, /box-sizing:\s*border-box/);
    assert.doesNotMatch(rule, /animation/, 'it does not fade');
});

test('the mark of the fake backend is a solid red border, three pixels, that fades to the background and back in four seconds', () => {
    const rule = ruleOf('#app.fake');
    assert.match(rule, /border:\s*3px solid var\(--bad\)/);
    assert.match(rule, /animation:\s*fake-border 4s linear infinite/);
    assert.match(rule, /box-sizing:\s*border-box/, 'so that a panel that fills the window keeps inside it');
    const frames = css.slice(css.indexOf('@keyframes fake-border'), css.indexOf('#app.open'));
    assert.match(frames, /0%, 100% \{ border-color: var\(--bad\); \}/);
    assert.match(frames, /50% \{ border-color: Canvas; \}/, 'the background of the window, where the border is not seen');
});

test('the cost control: a bar in the green of the figures over a light green, square corners, red over the limit, gray with no limit', () => {
    const bar = ruleOf('.cost-bar');
    assert.match(bar, /height:\s*12px/);
    for (const selector of ['.cost-bar', '.cost-fill']) {
        assert.doesNotMatch(ruleOf(selector), /border-radius:\s*[1-9]/, selector + ' has square corners');
    }
    assert.match(bar, /min-width:\s*120px/, 'COST_BAR_MIN of app.js');
    assert.match(bar, /color-mix\(in srgb, var\(--ok\) 25%, transparent\)/, 'the rest a light green (since 0.82)');
    assert.match(ruleOf('.cost-fill'), /background:\s*var\(--ok\)/, 'the green of the figures');
    assert.match(ruleOf('.cost-bar.over .cost-fill'), /background:\s*var\(--bad\)/, 'over the limit, the red of errors');
    assert.match(ruleOf('.cost-bar.over'), /color-mix\(in srgb, var\(--bad\) 25%, transparent\)/, 'every block');
    assert.match(ruleOf('.cost-bar.none'), /var\(--muted\) 33%/, 'with no limit, gray');
    assert.doesNotMatch(css, /\.cost-over/, 'no warning sign');
    assert.match(ruleOf('.cost'), /font:\s*400 11px\/1\.2/);
    assert.match(ruleOf('.cost-bar.none .cost-fill'), /width:\s*0/);
});

test('the open panel button is blue and bigger, whichever panel it is, the cost control included', () => {
    assert.match(ruleOf('.icon.active'), /color:\s*var\(--info\)/);
    assert.ok(html.includes('id="cost-button"') && html.includes('id="cost-view"'));
    assert.ok(html.indexOf('id="delta-time"') < html.indexOf('id="cost-button"') && html.indexOf('id="cost-button"') < html.indexOf('id="history-button"'),
        'the first of the icons, before the history icon (since 0.81; after the error log icon before)');
});

// ---- version 0.79: the small changes of the page

test('the two bars of the cost control are equally wide: the rows share the columns of one grid', () => {
    const rows = ruleOf('.cost-rows');
    assert.match(rows, /display:\s*grid/);
    assert.match(rows, /grid-template-columns:\s*max-content minmax\(120px, 1fr\) max-content/, 'the label, the bar with its least width (COST_BAR_MIN of app.js), the figures');
    assert.match(rows, /gap:\s*1px 6px/, '1 pixel between the two rows, and 6 between the parts of a row: COST_GAP of app.js');
    assert.match(read('app.js'), /var COST_GAP = 6;/);
    assert.doesNotMatch(ruleOf('.cost-label'), /min-width/, 'the labels are as wide as the wider of them, and no wider');
    assert.match(ruleOf('.cost-row'), /display:\s*contents/, 'a row is no box of its own: its parts are the cells');
    assert.doesNotMatch(ruleOf('.cost-bar'), /flex/, 'the bar takes the column, not what its own row leaves');
    assert.match(ruleOf('.cost-figures'), /justify-content:\s*flex-end/, 'the figures and the sign are one cell');
});

test('the heading of History view has the button of Main view, which selects every checkbox, or none', () => {
    const legend = html.slice(html.indexOf('<legend>History view'), html.indexOf('</legend>', html.indexOf('<legend>History view')));
    assert.match(legend, /^<legend>History view<button id="set-all-history" class="select-all" type="button"/, 'in the line of the heading, after it');
    assert.match(legend, /title="Select every checkbox, or none"/);
    assert.match(legend, /aria-label="Select every checkbox, or none"/);
    assert.match(legend, /<rect x="2" y="2" width="12" height="12" rx="2"\/>\s*<path d="M5 8\.2l2\.2 2\.3L11 5\.6"\/>/, 'the same icon');
    assert.equal(legend.replace(/<svg[\s\S]*<\/svg>/, '').replace(/<[^>]*>/g, '').trim(), 'History view', 'no text but the heading');
});

test('every control of the settings has a tooltip, on itself or on the label it is in', () => {
    const view = html.slice(html.indexOf('<div id="settings-form">'), html.indexOf('</div>\n    </div>\n</div>'));
    const controls = [...view.matchAll(/<(input|select|button)\b[^>]*>/g)].filter(m => !/class="tab"/.test(m[0]));
    assert.ok(controls.length >= 45 && controls.some(m => /id="settings-save"/.test(m[0])) && controls.some(m => /id="about-copy-log"/.test(m[0])), 'the controls were found, to the last: ' + controls.length);
    for (const control of controls) {
        if (/\btitle="[^"]{10,}"/.test(control[0])) {
            continue;
        }
        const label = view.lastIndexOf('<label', control.index);
        const closed = view.lastIndexOf('</label>', control.index);
        const tag = label > closed ? view.slice(label, view.indexOf('>', label) + 1) : '';
        assert.match(tag, /^<label title="[^"]{10,}">$/, 'no tooltip for ' + control[0]);
    }
    assert.doesNotMatch(html.slice(html.indexOf('id="tab-strip"'), html.indexOf('id="settings-form"')), /title=/, 'the titles of the tabs have none');
});

test('the tooltips of the settings are the texts the requirements give, with the range of a value where it has one', () => {
    const texts = {
        'set-usageIntervalSeconds': 'The time between two usage requests: a whole number of seconds from 5 to 3600. The default is 60.',
        'set-restoreWindow': 'Opens the window at the next start as it was when the program was stopped: the same panel, the same size and the same place.',
        'set-severalViews': 'Lets more than one view be shown at the same time: an icon opens its view and leaves the others open. Off, opening a view closes the one that is shown.',
        'set-logResponse': 'Writes the JSON of every usage response to the log. Anything shaped like a credential is masked.',
        'set-showTitleBar': 'Shows the title bar of the window. Off, the window is the row alone; the title bar comes back for as long as a panel that can be resized is open, so open one to close the window.',
        'set-showCostIcon': 'The icon that shows the cost control, a bar for the day and one for the month, below the row, and hides it again.',
        'set-historyDate': 'Shows the date before the time of each line.',
        'set-historyZeroLines': 'Shows the lines whose amount used is that of the reading before. Off, they are left out, and the lines of failed requests with them.',
        'set-historyFailedLines': 'Shows the lines of the usage requests that failed. Off, they are left out.',
        'set-historyTime': 'The column with the time of the reading.',
        'set-historyUsed': 'The column with the amount used.',
        'set-historyLimit': 'The column with the budget.',
        'set-historyDeltaUsed': 'The column with the change in the amount used since the last earlier reading: a rise with a plus, a fall with a minus. It is empty when nothing changed.',
        'set-historyDeltaTime': 'The column with the seconds since the previous line that is shown.',
        'set-historyCurrent': 'The column with the currency: the dollar sign for US dollars, the code for any other.',
        'set-costMonthlyLimit': 'What may be spent in a month, in US dollars: from 0.01 to 1,000,000, with up to two decimals. Empty for no limit.',
        'set-costDailyLimit': 'What may be spent in a day, in US dollars: from 0.01 to 1,000,000, with up to two decimals, and not more than the monthly limit.',
        'set-costDailyAutomatic': 'Works the daily limit out: the monthly limit divided by the weekdays, Monday to Friday, of the current month.',
    };
    for (const [id, text] of Object.entries(texts)) {
        const at = html.indexOf('id="' + id + '"');
        const label = html.lastIndexOf('<label', at);
        assert.equal(html.slice(label, html.indexOf('>', label) + 1), '<label title="' + text + '">', id);
    }
    const ranges = { 'debug-fakeIncreaseCents': 'From 0 to 100,000 cents.', 'debug-fakeBudget': 'from 0.01 to 1,000,000.', 'debug-fakeStartAmount': 'From 0 to the budget.',
        'debug-fakeRefuseSeconds': 'From 0 to 3,600 s.', 'debug-fakeDelayMillis': 'From 0 to 60,000 ms.' };
    for (const [id, range] of Object.entries(ranges)) {
        const at = html.indexOf('id="' + id + '"');
        const label = html.lastIndexOf('<label', at);
        assert.ok(html.slice(label, html.indexOf('>', label) + 1).endsWith(range + '">'), id + ' says its range');
    }
});

test('a panel button that is switched off and shown while the settings are open is dimmed, and nothing else of it changes', () => {
    const rule = ruleOf('.icon.tiny.off');
    assert.match(rule, /opacity:\s*0\.4/, 'at 40 percent of its strength');
    assert.doesNotMatch(rule, /width|height|margin|padding|display/, 'in its place and at its size, so the row is as wide with it as without');
});

test('the history has a button in the line of the zoom buttons, after them, for its zero usage lines: an icon with no text, blue while they are hidden', () => {
    const line = html.slice(html.indexOf('id="log-controls-history"'), html.indexOf('id="log-error-history"'));
    assert.ok(line.indexOf('id="zoom-out-history"') >= 0 && line.indexOf('id="zoom-out-history"') < line.indexOf('id="history-choices"'), 'after the two zoom buttons, in the line of the history');
    assert.match(line, /<span id="history-choices" class="zoom" hidden>\s*<button id="zero-lines" class="icon tiny" type="button" aria-label="Hide the zero usage lines and the failed lines" title="Hide the zero usage lines and the failed lines" aria-pressed="false">/,
        'hidden until the history says what is saved');
    const button = line.slice(line.indexOf('id="zero-lines"'));
    assert.match(button, /<path d="M2 3h12l-4\.5 5\.5v4\.5l-3 1\.5v-6z"\/>/, 'a funnel (since 0.81; two short arrows before)');
    assert.equal(button.slice(0, button.indexOf('</button>')).replace(/<svg[\s\S]*<\/svg>/, '').replace(/<[^>]*>/g, '').replace(/^[^>]*>/, '').trim(), '', 'no text');
    const rule = ruleOf('.icon.tiny.on');
    assert.match(rule, /color:\s*var\(--info\)/, 'the blue of the notes');
    assert.doesNotMatch(rule, /width|height|margin/, 'at the size of the zoom buttons, not the bigger one of an open panel');
});

test('the log has two buttons in the line of the zoom buttons, after them: icons with no text, the second not to be pressed until there is an old log', () => {
    const line = html.slice(html.indexOf('id="log-controls-log"'), html.indexOf('id="log-error-log"'));
    assert.ok(line.indexOf('id="zoom-out-log"') >= 0 && line.indexOf('id="zoom-out-log"') < line.indexOf('id="log-files"'), 'after the two zoom buttons, in the line of the log');
    assert.match(line, /<span id="log-files" class="zoom" hidden>/, 'hidden until the log is shown');
    assert.ok(line.indexOf('id="log-reset"') < line.indexOf('id="log-merge"'), 'the reset, then its undo');
    assert.match(line, /<button id="log-reset" class="icon tiny" type="button" aria-label="Reset the log: [^"]+"\s+title="Reset the log: the file is kept under a name with the date and time, and a new one is started">/);
    assert.match(line, /<button id="log-merge" class="icon tiny" type="button" aria-label="There is no old log" title="There is no old log" disabled>/);
    const files = line.slice(line.indexOf('<span id="log-files"'), line.lastIndexOf('</span>') + '</span>'.length);
    assert.doesNotMatch(line, /history-choices|zero-lines/, 'the button of the history is in the history\'s line, not here');
    assert.equal(files.replace(/<svg[\s\S]*?<\/svg>/g, '').replace(/<[^>]*>/g, '').trim(), '', 'no text');
    assert.equal((files.match(/<svg /g) || []).length, 2, 'an icon each');
});

// ---- version 0.80: the small changes of the page

test('the four checkboxes of the things that show a view say icon, and no other but the refresh one says button', () => {
    const texts = [...MAIN.matchAll(/<input id="set-show[A-Za-z]+" type="checkbox"> ([^<]+)<\/label>/g)].map(m => m[1]);
    assert.deepEqual(texts.filter(text => /icon$/.test(text)), ['Cost icon', 'History icon', 'Log icon', 'Error log icon']);
    assert.deepEqual(texts.filter(text => /button/i.test(text)), ['Refresh button'], 'which does something, and shows no view');
    for (const id of ['set-showLogIcon', 'set-showHistoryIcon', 'set-showErrorIcon', 'set-showCostIcon']) {
        const label = html.lastIndexOf('<label', html.indexOf('id="' + id + '"'));
        assert.match(html.slice(label, html.indexOf('>', label)), /title="The icon that shows /, id);
    }
});

test('a blue note is bold: the one that says not everything is shown, and its like', () => {
    const rule = ruleOf('.panel-note.highlight');
    assert.match(rule, /color:\s*var\(--info\)/);
    assert.match(rule, /font-weight:\s*700/);
    assert.doesNotMatch(ruleOf('.panel-note'), /font-weight/, 'a gray note stays regular');
});

test('the icons in the line at the top of a list are in the colour of the text and larger than those of the row, and the one that is on stays blue', () => {
    const button = ruleOf('.log-controls .icon.tiny');
    assert.match(button, /color:\s*CanvasText/, 'not the muted gray');
    assert.match(button, /width:\s*22px/);
    assert.match(button, /height:\s*22px/);
    const icon = ruleOf('.log-controls .icon.tiny svg');
    assert.match(icon, /width:\s*16px/);
    assert.match(icon, /height:\s*16px/);
    assert.match(icon, /stroke-width:\s*2\b/);
    assert.match(ruleOf('.log-controls .icon.tiny.on'), /color:\s*var\(--info\)/);
    assert.ok(css.indexOf('.log-controls .icon.tiny.on {') > css.indexOf('.log-controls .icon.tiny {'), 'the blue comes after, to win');
    assert.match(ruleOf('.icon.tiny'), /color:\s*var\(--muted\)/, 'the icons of the row are as they were');
    assert.match(ruleOf('.icon.tiny'), /width:\s*20px/);
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    assert.doesNotMatch(strip, /log-controls/, 'and none of them is in that line');
    assert.match(ruleOf('.icon[disabled]'), /opacity:\s*0\.35/, 'one that cannot be pressed is at a third');
});

test('the About view has its logo at the left and the name, the version and the table at the right of it', () => {
    const view = html.slice(html.indexOf('<div id="about-view"'), html.indexOf('<p id="settings-buttons"'));
    const order = ['id="about-logo"', 'id="about-text"', 'id="about-name"', 'id="about-version"', 'id="about-error"', 'id="about-details"'].map(id => view.indexOf(id));
    assert.ok(order.every(p => p >= 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order, 'the logo, and then, in one element, the name, the version and the table');
    assert.match(view, /<img id="about-logo"[^>]*>\s*<div id="about-text" class="about-text">/, 'the logo is alone in its column');
    const rule = ruleOf('.about');
    assert.match(rule, /display:\s*grid/);
    assert.match(rule, /grid-template-columns:\s*max-content minmax\(0, 1fr\)/, 'the logo as wide as it is, the rest taking what is left and breaking its paths');
    assert.match(rule, /align-items:\s*center/, 'the logo in the middle of the height (since 0.81; at the top in 0.80)');
    assert.match(rule, /text-align:\s*left/);
    assert.doesNotMatch(ruleOf('.about-logo'), /auto/, 'not centred');
    assert.match(ruleOf('.about-text'), /min-width:\s*0/, 'so that a long path breaks and does not widen the view');
});

test('the three lists, the copy the table is measured on and the cost control have one typeface, sturdy ones named before the system\'s own', () => {
    const stack = css.match(/--list-font:\s*([^;]+);/)[1];
    assert.equal(stack, '"Cascadia Mono Bundled", ui-monospace, "Cascadia Mono", "SF Mono", SFMono-Regular, Menlo, Consolas, monospace');
    for (const selector of ['.panel', '.table-probe', '.cost']) {
        assert.match(ruleOf(selector), /font:\s*400 1[12]px\/[0-9.px]+ var\(--list-font\)/, selector);
    }
    assert.equal((css.match(/Menlo/g) || []).length, 1, 'named in one place, so that the copy cannot have another typeface than the table');
    assert.deepEqual(css.match(/url\([^)]*\)/g), ['url("fonts/CascadiaMono-Regular.ttf")'], 'nothing is fetched: the one file named is the program\'s own (since 0.81)');
});

// ---- version 0.80: a box for each view

test('the panel has a box for each view, in the order of the icons, and each list has its own controls, notes and lines', () => {
    const boxes = ['<div id="cost-view"', '<section id="view-history"', '<section id="view-log"', '<section id="view-errors"', '<div id="settings-view"'].map(tag => html.indexOf(tag));
    assert.ok(boxes.every(at => at > html.indexOf('<div id="panel"')), 'all in the panel');
    assert.deepEqual([...boxes].sort((a, b) => a - b), boxes, 'the cost control, the history, the log, the error log (since 0.81), and the settings after them');
    for (const name of ['log', 'history', 'errors']) {
        const box = html.slice(html.indexOf('<section id="view-' + name + '"'), html.indexOf('</section>', html.indexOf('<section id="view-' + name + '"')));
        assert.match(box, new RegExp('^<section id="view-' + name + '" class="view" aria-label="[^"]+" hidden>'), name + ' is hidden until it is shown');
        const parts = ['log-controls-', 'log-error-', 'panel-note-', 'panel-error-', 'panel-lines-'].map(part => box.indexOf('id="' + part + name + '"'));
        assert.ok(parts.every(at => at >= 0), name + ' has every part');
        assert.deepEqual([...parts].sort((a, b) => a - b), parts, name + ': the controls, the red line, the note, the second red line, the lines');
        assert.equal((box.match(/class="panel-lines"/g) || []).length, 1);
    }
    for (const once of ['log-level', 'log-date', 'log-reset', 'log-merge', 'zero-lines', 'cost-rows']) {
        assert.equal((html.match(new RegExp('id="' + once + '"', 'g')) || []).length, 1, once + ' belongs to one view and is there once');
    }
    assert.doesNotMatch(html, /id="(panel-lines|panel-note|panel-error|log-error|log-controls|zoom-in|zoom-out)"/, 'nothing of a list is there once for all');
});

test('a view fills what the panel leaves it, and one that has another below it keeps its height', () => {
    const view = ruleOf('.view');
    assert.match(view, /display:\s*flex/);
    assert.match(view, /flex-direction:\s*column/);
    assert.match(view, /flex:\s*1 1 auto/, 'alone, it takes the height the one list took');
    assert.match(view, /min-height:\s*0/, 'so that its lines scroll and the window is not pushed taller');
    assert.match(css, /\.view\[hidden\]\s*{\s*display:\s*none/, 'hidden wins over the flex');
    assert.match(ruleOf('.view.fixed'), /flex:\s*none/, 'the page says how tall it is');
});

test('the checkbox Several views at once is the last of the group Window, after the one that restores the window', () => {
    const group = html.slice(html.indexOf('<legend>Window</legend>'), html.indexOf('</fieldset>', html.indexOf('<legend>Window</legend>')));
    const ids = [...group.matchAll(/<input id="(set-[A-Za-z]+)"/g)].map(m => m[1]);
    assert.deepEqual(ids, ['set-showTitleBar', 'set-stayOnTop', 'set-restoreWindow', 'set-severalViews']);
    assert.match(group, /<input id="set-severalViews" type="checkbox"> Several views at once<\/label>/);
});

// ---- version 0.81: the small changes of the page

test('a line of a middle gray separates the row from a view and each view from the next, inside the view', () => {
    assert.match(css, /:root\s*{[^}]*--view-line:\s*#8c959f/, 'the light theme');
    assert.match(css, /@media \(prefers-color-scheme: dark\)\s*{\s*:root\s*{[^}]*--view-line:\s*#484f58/, 'the dark theme');
    assert.match(css, /\.view,\s*\.panel > \.cost,\s*\.panel > \.settings\s*{\s*border-top:\s*1px solid var\(--view-line\)/,
        'the top edge of every view, the cost control and the settings among them');
    assert.doesNotMatch(css, /border-bottom:\s*1px solid var\(--view-line\)/, 'none below the lowest');
});

test('the button of the zero usage lines is a funnel', () => {
    const button = html.slice(html.indexOf('<button id="zero-lines"'), html.indexOf('</button>', html.indexOf('<button id="zero-lines"')));
    assert.match(button, /<path d="M2 3h12l-4\.5 5\.5v4\.5l-3 1\.5v-6z"\/>/);
    assert.match(button, /width="13" height="13"/, 'the size of the zoom');
});

test('the cost control is tight: 11 pixels on 1.2, rows a pixel apart, bars 12 tall in blocks of 5 %', () => {
    assert.match(ruleOf('.cost'), /padding:\s*2px 8px/);
    assert.match(ruleOf('.cost-rows'), /gap:\s*1px 6px/);
    const blocks = ruleOf('.cost-bar::after');
    assert.match(blocks, /position:\s*absolute/);
    assert.match(blocks, /width:\s*calc\(100% \+ 1px\)/, 'the tenth gap falls outside the bar');
    assert.match(blocks, /linear-gradient\(to right, transparent calc\(100% - 1px\), Canvas calc\(100% - 1px\)\) 0 0 \/ 5% 100% repeat-x/,
        'a gap of a pixel in the background after every twentieth (since 0.82)');
    assert.match(ruleOf('.cost-bar'), /overflow:\s*hidden/, 'so that what is beyond the bar is not drawn');
});

test('the logo of About is in the middle of the height of what is at its right', () => {
    assert.match(ruleOf('.about'), /align-items:\s*center/);
});

test('the last cell of a line of the history and of the error log keeps 6 pixels from the scrollbar', () => {
    assert.match(css, /\.panel-lines \.columns > :last-child,\s*\.panel-lines \.cols-2 > :last-child\s*{\s*margin-right:\s*6px/);
});

test('the program brings Cascadia Mono, with its licence, and names it first', () => {
    const face = css.slice(css.indexOf('@font-face'), css.indexOf('}', css.indexOf('@font-face')));
    assert.match(face, /font-family:\s*"Cascadia Mono Bundled"/);
    assert.match(face, /src:\s*url\("fonts\/CascadiaMono-Regular\.ttf"\) format\("truetype"\)/, 'from the program, fetched from nowhere');
    assert.match(face, /font-weight:\s*400/, 'the regular weight alone');
    const dir = require('node:path').join(__dirname, '..', '..', 'main', 'resources', 'web', 'fonts');
    assert.ok(fs.statSync(require('node:path').join(dir, 'CascadiaMono-Regular.ttf')).size > 100000);
    assert.match(fs.readFileSync(require('node:path').join(dir, 'CascadiaMono-LICENSE.txt'), 'utf8'), /SIL Open Font License, Version 1\.1/);
});

test('the settings beside other views are above them and keep the height of their tab', () => {
    assert.match(css, /\.panel > \.settings\s*{\s*order:\s*-1;\s*flex:\s*none/);
});

test('the group Files is in General after Window, a line for each folder with its path as text, Choose… and Default, and their tooltips', () => {
    const general = html.slice(html.indexOf('<legend>Window</legend>'), html.indexOf('<legend>Log</legend>'));
    const group = general.slice(general.indexOf('<legend>Files</legend>'));
    assert.ok(general.indexOf('<legend>Files</legend>') > 0, 'after Window, before Log');
    for (const [key, label, tip] of [
        ['logFolder', 'Log folder', 'The folder the log and its old logs are kept in. The default is the program\'s folder.'],
        ['historyFolder', 'History folder', 'The folder the usage history is kept in. The default is the program\'s folder.']]) {
        const line = group.slice(group.lastIndexOf('<div class="folder-line"', group.indexOf('id="set-' + key + '"')), group.indexOf('</div>', group.indexOf('id="set-' + key + '"')));
        assert.match(line, new RegExp('title="' + tip.replace(/[.()]/g, '\\$&') + '"'), key);
        assert.match(line, new RegExp('<span class="folder-label">' + label + '</span>'));
        assert.match(line, new RegExp('<span id="set-' + key + '" class="folder-path"></span>'), 'text, not a field: nothing is typed');
        assert.match(line, new RegExp('<button id="choose-' + key + '" type="button" title="Opens a window to choose the folder\\.">Choose…</button>'));
        assert.match(line, new RegExp('<button id="default-' + key + '" type="button" title="Puts back the program\'s folder\\.">Default</button>'));
    }
    assert.match(group, /<p id="folder-error" class="panel-error" role="alert" hidden><\/p>/, 'a red line of its own');
    assert.match(ruleOf('.folder-path'), /overflow-wrap:\s*anywhere/, 'a long path breaks, and does not widen the view');
    assert.match(ruleOf('.folder-path.default'), /color:\s*var\(--muted\)/);
});

// ---- version 0.82

test('the icons of the line at the top of a list stand at its right end, the log\'s choices at the left', () => {
    assert.match(css, /\.log-controls > \.zoom:not\(\[id\]\)\s*{\s*margin-left:\s*auto/);
    for (const name of ['history', 'log', 'errors']) {
        const line = html.slice(html.indexOf('<div id="log-controls-' + name + '"'), html.indexOf('</div>', html.indexOf('<div id="log-controls-' + name + '"')));
        const zoom = line.indexOf('<span class="zoom">');
        assert.ok(zoom > 0, name + ' has the zoom without an id, which the rule finds');
        for (const after of ['id="history-choices"', 'id="log-files"']) {
            if (line.includes(after)) {
                assert.ok(line.indexOf(after) > zoom, after + ' follows the zoom, at the right');
            }
        }
        if (name === 'log') {
            assert.ok(line.indexOf('id="log-choices"') < zoom, 'the level and the date stay at the left');
        }
    }
});

test('the checkbox Currency symbol of the cost control is in its tab, with its tooltip', () => {
    const tab = html.slice(html.indexOf('<div id="tab-cost"'), html.indexOf('<div id="tab-debug"'));
    assert.match(tab, /<label title="Puts the currency symbol before the amounts of the cost control\."><input id="set-costCurrency" type="checkbox"> Currency symbol<\/label>/);
});

test('the Cost control tab has Limit counted, the hours of a working day and the working days, in that order before the currency symbol', () => {
    const tab = html.slice(html.indexOf('<div id="tab-cost"'), html.indexOf('<div id="tab-debug"'));
    const order = ['id="set-costMonthlyLimit"', 'id="set-costDailyLimit"', 'id="set-costLimitSource-server"', 'id="set-costLimitSource-monthly"',
        'id="set-costWorkdayHours"', 'id="set-costWorkdays"', 'id="set-costWorkdaysAutomatic"', 'id="set-costCurrency"'].map(id => tab.indexOf(id));
    assert.ok(order.every(at => at > 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    assert.match(tab, /name="costLimitSource" type="radio" value="server"> Server limit/);
    assert.match(tab, /name="costLimitSource" type="radio" value="monthly"> Monthly limit/);
    assert.match(tab, /title="How long a working day is: today's spend is projected over it\. The default is 8\.">Hours of a working day/);
    assert.match(tab, /title="The working days of this month, Monday to Friday unless a number is typed; a typed number holds for this\s+month only\.">Working days|title="The working days of this month, Monday to Friday unless a number is typed; a typed number holds for this month only\.">Working days/);
});

test('the row has the status circle first and the figures of the day after the budget, and Main view their checkboxes in that order', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const order = ['id="status-dot"', 'id="time"', 'id="limit"', 'id="day"', 'id="day-percent"', 'id="day-used"', 'id="day-of"', 'id="day-limit"', 'id="refresh"'].map(id => strip.indexOf(id));
    assert.ok(order.every(at => at >= 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    const main = html.slice(html.indexOf('id="tab-main"'), html.indexOf('id="tab-history"'));
    const boxes = ['set-showStatus', 'set-showTime', 'set-showLimit', 'set-showDayPercentage', 'set-showDayUsed', 'set-showDayLimit', 'set-showRefresh'].map(id => main.indexOf('id="' + id + '"'));
    assert.ok(boxes.every(at => at >= 0));
    assert.deepEqual([...boxes].sort((a, b) => a - b), boxes);
    assert.match(ruleOf('.status-dot'), /width:\s*10px/);
    assert.match(ruleOf('.status-dot'), /border-radius:\s*50%/);
    assert.match(css, /\.status-dot\.amber\s*{\s*background:\s*var\(--warn\)/);
    assert.match(css, /\.status-dot\.red\s*{\s*background:\s*var\(--bad\)/);
    assert.match(ruleOf('.spend .over'), /color:\s*var\(--bad\)/);
});

test('an amber bar has its used part in the amber and the rest in a light amber', () => {
    assert.match(ruleOf('.cost-bar.amber .cost-fill'), /background:\s*var\(--warn\)/);
    assert.match(ruleOf('.cost-bar.amber'), /color-mix\(in srgb, var\(--warn\) 25%, transparent\)/);
    assert.match(css, /--warn:\s*#d29922/, 'the dark theme');
});
