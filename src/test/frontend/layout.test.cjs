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
    // the countdown, the config button and its field.
    const order = ['id="time"', 'id="percent"', 'id="used"', 'id="limit"', 'id="windows"',
        'id="refresh"', 'id="countdown"', 'id="config-toggle"', 'id="config"'];
    const positions = order.map(marker => strip.indexOf(marker));
    positions.forEach((position, i) => assert.ok(position >= 0, 'missing ' + order[i] + ' in the strip'));
    assert.deepEqual([...positions].sort((a, b) => a - b), positions,
        'the order is time, percentage, used, limit, windows, refresh, countdown, config, config field');
});

test('the time is the very first thing in the strip', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    for (const later of ['id="percent"', 'id="used"', 'id="limit"', 'id="windows"', 'id="refresh"', 'id="countdown"', 'id="config-toggle"']) {
        assert.ok(strip.indexOf('id="time"') < strip.indexOf(later), 'the time comes before ' + later);
    }
});

test('the countdown comes right after the refresh button, and the config button after it', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    const refresh = strip.indexOf('id="refresh"');
    const countdown = strip.indexOf('id="countdown"');
    const config = strip.indexOf('id="config-toggle"');
    assert.ok(refresh < countdown && countdown < config);
    assert.equal(strip.slice(refresh, countdown).includes('id="used"'), false, 'nothing else sits between them');
});

test('the amounts are two separate numbers, each able to carry its own tooltip', () => {
    assert.match(html, /<span id="used"><\/span>\s*\/\s*<span id="limit"><\/span>/);
    assert.doesNotMatch(html, /id="amounts"/);
    assert.doesNotMatch(html, /\$/, 'no currency sign in the page');
});

test('the countdown starts hidden and keeps a width that does not move with every digit', () => {
    assert.match(html, /id="countdown"[^>]*\shidden/);
    assert.match(css, /\.countdown\s*{[^}]*min-width:\s*[0-9.]+ch/);
    assert.match(css, /\.countdown\s*{[^}]*tabular-nums/);
});

test('the config field sits next to the config button, in the same row', () => {
    const strip = html.slice(html.indexOf('class="strip"'), html.indexOf('id="note"'));
    assert.ok(strip.includes('id="usage-interval"'), 'the fetch interval field is in the strip');
    assert.ok(strip.indexOf('id="config-toggle"') < strip.indexOf('id="usage-interval"'));
});

test('the update interval is not offered in the window', () => {
    assert.doesNotMatch(html, /poll-interval/);
    assert.equal([...html.matchAll(/<input\b/g)].length, 1, 'the one input is the fetch interval');
});

test('the config fields start hidden', () => {
    assert.match(html, /id="config"[^>]*\shidden/);
});

test('the hidden attribute really hides, even on elements the stylesheet gives a display', () => {
    // An author rule such as `.config { display: inline-flex }` beats the browser's
    // own `[hidden] { display: none }`, so the stylesheet must restore it with force.
    assert.match(css, /\[hidden\]\s*{[^}]*display:\s*none\s*!important/);

    const hiddenIds = [...html.matchAll(/<[^>]*\sid="([^"]+)"[^>]*\shidden[\s>]/g)].map(m => m[1]);
    assert.ok(hiddenIds.includes('config'), 'the config fields use the hidden attribute');
    const classesGivenADisplay = [...css.matchAll(/\.([a-z-]+)\s*{[^}]*\bdisplay:\s*(?!none)/g)].map(m => m[1]);
    const overridden = hiddenIds.filter(id => {
        const tag = html.match(new RegExp('<[^>]*\\sid="' + id + '"[^>]*>'))[0];
        const cls = (tag.match(/class="([^"]+)"/) || [, ''])[1].split(/\s+/);
        return cls.some(c => classesGivenADisplay.includes(c));
    });
    assert.ok(overridden.length > 0, 'at least one hidden element has a display rule, which is why the [hidden] rule is needed: ' + hiddenIds);
});

test('the two buttons are icons with accessible names, not words', () => {
    for (const id of ['refresh', 'config-toggle', 'config-ok']) {
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
