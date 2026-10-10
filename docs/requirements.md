# Requirements

## Goal

This project provides a small Java-based client for monitoring Anthropic OAuth usage data. It should fetch the current usage snapshot and present it in a readable, continuously refreshed form.

The implementation is intended to exercise the neighboring `java-aip` tooling and to reuse patterns from the sibling repos for authentication and OAuth token handling.

## Source data

The application fetches usage data from the Anthropic OAuth usage endpoint:

`https://api.anthropic.com/api/oauth/usage`

The part before the path, `https://api.anthropic.com`, is the base URL, and it is
configurable: a command-line option (`--anthropic-url <url>`, see Command line)
points the application at another server. The default is Anthropic's own, so a run
that says nothing about it behaves as it always has. A run can instead fetch from
the application's own fake backend (`--fake-backend`, see Fake backend), which needs
no URL because the application knows where it put it.
The path is always `/api/oauth/usage` and is appended to whatever
base URL is in force; it is never configurable, because what the application
parses is the document that path serves.

The JSON examples below show the application's normalised view of a usage
reading, the same shape `java-aip usage --format json` prints. They are not the
raw HTTP response. The endpoint sends no `source` or `fetched_at`: the
application stamps `fetched_at` with the time it received the response. It
sends spend as minor units under a `spend.enabled` flag (`{"amount_minor":
18602, "exponent": 2}` is 186.02) and plan windows as top-level keys holding a
`utilization`, among unrelated and placeholder keys. The application maps the
raw response to the shape below; real responses are in
`src/test/resources/fixtures/`.

**The endpoint answers in one of two formats, and this version supports one of them.**
Which one an account gets depends on its subscription and on nothing else: not on the
operating system and not on the machine. The same program therefore gets one format on
one machine and the other on another only because the two are logged in with different
subscriptions.

| Name | Whose it is | What it carries | In this version |
| --- | --- | --- | --- |
| **usage-based format** | an account on a usage-based plan, where usage is billed as it is used, up to a spend limit | `spend` populated, `windows` empty | supported |
| **seat-based format** | an account on a plan with a usage allowance, such as Pro, Max and the seat-based Team and Enterprise plans, which have a five-hour session limit and a weekly limit | `spend` is `null`, `windows` populated | **not supported** |

The names are this project's. Anthropic has no name for the two shapes of the response, so
they are named after its own words for the two kinds of plan, "usage-based" and
"seat-based"; its words for what the formats carry are a "spend limit" for the first and a
"session limit" and "weekly limit" for the second. These two names are the ones this
document, the code, the log and the tests use.

**A response in the seat-based format is refused.** A response that carries any plan window,
that is, any top-level object with a numeric `utilization` other than `extra_usage`, is a
failed refresh, whether it carries `spend` as well or not: the two together are refused like
the windows alone, and the spend of such a response is not shown. The message is
`This account answers in the seat-based format (plan limits), which this version does not
support yet; only the usage-based format (spend) is.` It is a failed refresh like any other
(see Refresh behavior and Messages and states): the message line, the error log, the log and
a `failed` row in the usage history, at every refresh for as long as the account answers so.
It is not an HTTP 429, so nothing is slowed down, and it does not touch the token. A window
key that is `null`, as the usage-based format has them, is no plan window and is passed
over. Nothing of a refused response is shown, kept or written: no window, no utilization,
no reset time.

Nothing in the program shows a plan window, and nothing in this document says how one would be
shown: no place in the row, no form for its figures, no test. How the seat-based format is to
be displayed is to be decided, and written here, when it is supported, against a real account
that answers in it.

For the usage-based format, the reading contains spend details and an empty `windows` array:

```json
{
  "source": "anthropic-oauth-usage",
  "fetched_at": "2026-10-07T15:23:12.302447Z",
  "spend": {
    "used": 218.07,
    "limit": 1000.0,
    "currency": "USD",
    "percent": 22,
    "severity": "normal"
  },
  "windows": []
}
```

In the seat-based format, which is not supported, the `spend` value is `null` and `windows`
contains the plan usage windows instead. For example, as documented in the
[`java-aip` JSON specification](../../java-aip/docs/json-spec.md):

```json
{
  "source": "anthropic-oauth-usage",
  "fetched_at": "2026-10-03T08:15:00Z",
  "spend": null,
  "windows": [
    {
      "window": "five_hour",
      "utilization": 12.34,
      "resets_at": "2026-10-06T18:00:00Z"
    },
    {
      "window": "seven_day",
      "utilization": 80.0,
      "resets_at": "2026-10-10T00:00:00Z"
    }
  ]
}
```


## Authentication

The client must obtain an OAuth access token before it can fetch usage data.

- Reuse and adapt the token-fetching implementation from the sibling `java-aip` project; do not add a runtime dependency on that repository.
- The token flow requires the Claude Code CLI to be installed and logged in.
- The token should be fetched once and then reused for usage requests.
- The token is kept in the memory of the running program and nowhere else. It is never written to the settings file, the usage history, the log or any other file, and no request to the local server returns it; a new run obtains it again.
- If the usage endpoint responds with HTTP 401, fetch a fresh token and retry the request once. Do not refresh the token for other HTTP or network failures.
- **The token is obtained at the first refresh, not before it**, so the window is on the screen while `claude` runs.
- **What is kept after each outcome.** A failure that is not a 401, whatever it is (another status, the network, a response that cannot be read), keeps the token for the next refresh and is not tried again within the refresh. On a 401 the rejected token is dropped before a fresh one is asked for, so that it is never sent again: if getting the fresh one fails, the refresh fails with the message of the token flow and the next refresh starts the flow anew. If the retry is rejected with a 401 as well, no token is kept, and every later refresh runs the token flow until one is accepted. If the retry fails in any other way, that failure is reported as it is and the fresh token is kept; a 429 answered to the retry starts the back-off as usual.
- If the user is not logged in or the token is unavailable, the application should show a clear user-facing message instead of failing silently. The same goes when `claude` cannot be found on the `PATH` (the message says what to do, including that a changed `PATH` needs a restart) and when it is found but cannot be run.
- If `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set, `claude` would send that credential instead of its OAuth login token, so the application does not capture one: it shows a message naming the variable, never its value, and asks for it to be unset and the application restarted.
- The whole token flow can be left out of a run with `--fake-token` (see Command line). The application then obtains no token and sends a fixed placeholder bearer instead, `placeholder-no-real-token-was-obtained`, so it starts with no Claude Code installed, nobody logged in, and no paid account. Obtaining it cannot fail. The placeholder is deliberately not shaped like a credential, so the masking of the log (see The log) has nothing to hide in a fake run and its lines stay readable, and so that nobody can mistake it for a key that leaked. It exists for running against a server that does not look at the token, and for not spending real requests on the usage endpoint while developing. A run that uses it is said so in the log, so that a reading taken this way cannot be mistaken for a real one.
- `--fake-backend` leaves the token flow out by itself, because the fake backend ignores the token: the two options need not both be given, and giving both is no mistake either, since they then ask for the same thing.
- `--fake-token` and `--anthropic-url` are independent. A placeholder bearer sent to the real endpoint is rejected, which is the ordinary HTTP 401 path and not a special case; a real token is used against a custom URL, which is what pointing the application at a proxy in front of the real endpoint needs. Neither combination is refused.

Example token output:

```json
{
  "access_token": "sk-ant-..."
}
```

## Refresh behavior

- Fetch usage immediately when the application starts, then repeat at the configured interval.
- The default backend usage-fetch interval is 60 seconds. Configure it in seconds through both a command-line option (`--usage-interval <seconds>`) and the settings view (see Settings); the command line, the settings file and the settings view accept values from 5 through 3600 seconds, the settings view in a single entry field. The default is a minute because the usage endpoint appears to accept about one request a minute over the long run: a faster pace, such as 30 seconds, is allowed but draws HTTP 429 after roughly ten minutes.
- Save a usage-fetch interval changed in the frontend to the settings file (see Settings). A committed valid frontend value is sent to the backend and replaces any CLI override for the remainder of the run.
- Changing the backend usage-fetch interval does not cancel a request already in progress. Apply the new interval to the next scheduled request, measuring the interval from when the current/most recent request was triggered. If the new interval has already elapsed, start the next request as soon as no request is running; otherwise wait until the interval elapses. Changing the interval does not otherwise trigger an extra immediate request.
- If a refresh fails, keep the last successful data visible, mark it as stale, and show the error. Resume normal display after the next successful refresh.
- If Anthropic answers HTTP 429 (rate limited), the application slows down instead of
  carrying on at the same pace. The next scheduled request waits twice the usage
  interval, and each further 429 in a row doubles the wait, up to 5 minutes, or the
  `Retry-After` the server gave if that is longer (only a whole number of seconds is believed, and no more than an hour; with an interval of
  150 seconds or more twice the interval is over the 5 minutes already, so the wait is the 5 minutes at most, and at 3600 seconds a 429 alone
  changes nothing). A success eases the wait and does
  not drop it: it takes an eighth off, and the eased value is the new wait, until it
  is no longer than the usual interval. Dropping it at once would return to the very
  pace the server has just refused. A failure that is not a 429 leaves the wait as it
  is. The refresh button is never held back, since a person asked. The error shown
  says when the next try is.
- **The back-off, exactly.** On an HTTP 429 the new wait is twice the larger of the wait in force and the
  usage interval, at most 5 minutes; then, if the server gave a `Retry-After` longer than that, the
  `Retry-After`, itself at most an hour. A `Retry-After` shorter than the doubled wait changes nothing. On a
  success the wait becomes seven eighths of itself, and it is over when that is no longer than the interval,
  equal counting as over: at 30 seconds after one 429 the waits are 60, 52.5, 45.9, 40.2, 35.2 and 30.8
  seconds, and the sixth success ends it. After it is over the next 429 starts from twice the interval again.
- **A manual refresh counts in the back-off like a scheduled one**, although it is never held back by it: a
  429 answered to a press of the button doubles the wait again, and a success of one eases it.
- **Only an HTTP 429 starts or lengthens a wait.** A `Retry-After` on any other status, a 503 for example, is
  written in the log line of the request and changes nothing.
- **Changing the interval does not end a back-off.** The wait in force is at every moment the longer of the
  interval and the back-off's wait. An interval applied while the application is backing off that is shorter
  than the wait leaves the wait as it is, to ease as before; one that is longer simply is the wait, and the
  back-off it has overtaken is dropped at the next success, not at the change.
- **The wait, and the `Next try in` figure, are counted from when the refused request was triggered**, not
  from when the 429 came back, so what is really left is shorter by the time the request took.
- **A request that takes longer than the wait in force is followed by the next one as soon as it ends**, with
  no pause, whether or not the interval was changed meanwhile.
- **The longer wait is the interval in force.** While the application waits longer than the configured usage interval
  because of 429s, that longer wait, in whole seconds (rounded up), is the interval the application is using, and it is the
  one that is shown: in the row's interval item, if its setting is on, and in the interval box when the settings view is opened, and
  where the backend reports the interval to a client (`GET /api/config` reports what is configured, not the longer wait). It falls back, as the wait eases after successes, to the configured
  interval, which is what the settings file keeps until a value is applied. Applying settings saves what the interval box
  shows, so applying while the application is backing off saves the longer wait as the configured interval, unless the person typed or picked another; it then is the
  interval, and no longer eases back.
- The backend reports the time until the next scheduled refresh together with the
  status, in whole seconds and possibly negative, as described under Compact window.
  The frontend does not work it out.
- The backend alone fetches usage from Anthropic on this interval. UI status polling must not trigger an Anthropic request.
- The UI-to-backend status polling interval, how often the window asks the backend for the latest state, is not a setting. It is 1 second by default and can be overridden for one run with a command-line option (`--poll-interval <seconds>`), which accepts values from 1 through 60 seconds. It has no frontend control, is never changed while the application runs, and is never saved: it is forgotten when the application stops, and a value left in an older settings file is ignored.
- When the UI starts, it must request the effective intervals from the backend, and it uses the polling interval to poll the backend for the latest available state. It shows the usage-fetch interval, and every other setting, when the settings view is opened, and it asks the backend for the values each time the view is opened, not only at startup. If the backend's values have changed in the meantime, for instance because another client changed them, the view shows the new values. If the backend cannot be reached at that moment, the view says so and shows no values, since the frontend keeps none of its own.
- Provide a UI action to fetch usage immediately. It must call the backend, which starts an Anthropic usage request without waiting for the next scheduled refresh and returns immediately. Do not run overlapping usage requests; if a refresh is already in progress, return immediately without starting another one. Keep the manual-refresh action enabled; extra clicks while a request is in progress do not start additional requests.
- Closing the application window must terminate the program and stop its backend server, scheduled tasks, and other background resources.
- The program shuts down the same way, with the same lines in the log, when the process is asked to stop without the window being closed, for example by `SIGTERM` or `SIGINT`. A forced kill (`SIGKILL`) cannot be caught by any program, so it leaves nothing in the log.

## Display requirements

**The frontend does no calculation.** Every figure the window shows that is worked out from
others is worked out by the backend and sent ready: the differences between readings, the time
until a reset or the next refresh, the percentage, the ordering of the history, and the cut of a time
to hours and minutes or to seconds. The frontend only places what it is given, and chooses between
what the backend sent and what to show where a setting says so. It stores no settings of its own.

The layout of the window is governed by [Compact window](#compact-window); where
that section and the descriptions below differ on presentation, it wins.

The usage data must be displayed in a simple graphical user interface (GUI)
that is easy to read at a glance. `fetched_at` must always be shown. The
remaining fields are those of the usage-based format, the one format this version
supports (see Source data).

For the usage-based format (`spend` populated, `windows` empty), present:

- `used` and `limit`, as two plain numbers, with no currency sign unless the setting for it is on (see Settings);
- `currency`, in the tooltips of those two numbers;
- `percent`, as a percentage;
- `severity`, by the colour of the percentage and the numbers, and named in the
  percentage's tooltip.

Section Compact window says where each goes and what the tooltips say.

The seat-based format (`spend` is `null`, `windows` populated) is not shown in this
version: a response in it is refused, as Source data says.

The initial application must target Java 25 and run on macOS. Use JavaFX
WebView to show the browser-based interface in a minimal desktop window without
the usual browser controls. A future Go rewrite should be designed to support
Windows and allow a native Windows binary to be cross-compiled on macOS. Keep
the frontend as independent from the backend as practical so it can be reused
if the implementation language changes.

The application should serve its HTML, CSS, and JavaScript or TypeScript
interface locally and open it automatically in the JavaFX WebView window.
Prefer keeping the frontend dependency-light and independent of Java-specific
implementation details.

As built, the frontend is plain HTML, CSS and JavaScript, four files served as they
are written: no framework, no library of anyone else's, no TypeScript and no build
step, and nothing fetched from the network. A build toolchain is to be added only if
the interface grows enough to justify one.

JavaFX WebView is the window host because it is a direct fit for a Java desktop
application and brings no browser runtime of its own to ship. That choice is to be
revisited only if a concrete limitation of the frontend appears, not for preference.

**The local API is a contract, and it is written down.** The window and the application
talk over the small local JSON API, and its endpoints, the shape of every response and
the rules the server applies are kept in a document of their own,
[docs/api.md](api.md), and kept level with the program. The API is to stay small, and
its shapes are defined in terms of JSON rather than of the Java types behind them, so
that a backend written in another language can serve the same contract and keep the
window it is read by. A change to what an endpoint returns is a change to that document.

**Resizing the window is the host's business, and outside that contract.** A window that
is as small as its content has to change size when a message appears or a panel opens, and
only the program that owns the window can resize it, so the page tells its host the size it
needs and which panel is open, and the host sizes the window and remembers the heights (see
Remembered heights). This is window management, not part of what a future backend in another
language would have to provide: such a rewrite needs a window host of its own in any case,
and JavaFX is not reusable as one. The page-to-host exchange is documented with the API.

## Compact window

The window is a status strip that a software engineer keeps beside their work,
so it must take as little screen space as it can while staying easy to read.

**Size and text**

- The window's title is `aip usage` followed by the program's version, for example
  `aip usage v0.01`. The version is written in the source code as one constant, is
  increased by hand whenever the program changes, and starts at 0.01. The page inside
  the window is titled `aip usage` without the version, so the version is in one place.
  It is the program's own version and has nothing to do with the version of the build:
  changing the Gradle project version changes nothing the window shows, and raising this
  constant is the whole of releasing a new version.
- The window is as small as its content allows, with no empty space around it.
  In its normal state it is a single row, roughly 330 by 35 pixels of content, which is
  about 62 pixels tall with the title bar. The window cannot be resized by hand: it always
  fits its content and follows it as it changes. The exceptions are while the usage history or the
  error log is shown, when it is ten times as tall as the single row (the history, and the log, may open
  taller, as the person last left them) and can be resized in height, and, for the
  log, three times as wide, and the error log, one and a half times as wide, both resizable in width too (see The log panel, The usage history panel and The error log panel),
  and while the settings are shown, when the window is as tall as the row, its message lines and the settings
  need, exactly (see Settings). A row that would be wider than about 900
  pixels wraps onto a second line.
- **A refresh does not change the window's size.** New figures, the countdown ticking and a changed
  time never make the window bigger or smaller: the fields that change have room for their usual
  values (which, and what happens to a longer one, is under The look of the page). The window still changes size when something other than a refresh asks it to: a message line
  appearing or going, a panel opening or closing, and a setting that adds or removes an item of the row.
- **The window's width is set by the row alone.** A message line, such as an error, never makes the
  window wider: it is wrapped to the width the row has and takes as many extra lines as it needs,
  and the window grows in height only. The same holds for the log. The one thing that sets the width of the
  history is its own table (see The usage history panel).
- The text is easy to read: a sans-serif font of at least 14 pixels, with strong
  contrast. It is set bold throughout (weight 700), with the percentage and the
  spent and budget heavier still (800). No thin or light weights, no fine print. The exceptions
  are the usage history, the log and the error log shown below the row, which are deliberately small and
  condensed, like a log file (see The usage history panel), and the settings view, which is set in 12 pixel
  text so that it needs less room; none of them is ever thin.
- Every time the row and its message lines show is the local time of day only, with no date, as
  hours and minutes, for example `14:24`, or with seconds, for example `14:24:53`, as the time format
  setting says; the default is hours and minutes. This applies to the time of the last refresh and to the
  time of an error alike. The backend cuts the time; the window shows it as received. The history panel
  and the log keep their own forms, with seconds. The one exception is the tooltip on the time (see Tooltips),
  which gives the full date and time.

**The row**

The controls sit in one horizontal row, one after another, with small gaps, in
this order. The refresh button sits snug against the amounts before it, and the countdown
snug against the button, so they read as one group:

1. the time the usage was last refreshed (`fetched_at`), first of all;
2. the percentage spent, when its setting is on (it is off by default);
3. what has been spent and the budget: `used` and `limit`, as two plain numbers
   with no currency symbol, for example `186.02 / 1,000.00`, unless the setting for the symbol is on, when each number
   has it before it, for example `$186.02 / $1,000.00`: a `$` for US dollars and, for any other currency, its code
   and a space, `EUR 186.02 / EUR 1,000.00`; no symbol when the response named no currency. The unit is also given by
   the tooltips;
4. a small refresh button;
5. a countdown to the next refresh, in seconds and with its unit, for example
   `42 s`, and `-3 s` when the refresh is overdue; it is always shown;
6. the time between usage requests that is in force, the delay, for example `60 s`, when its setting is on
   (it is off by default); it is not the countdown, which counts down to the next request, but the interval
   the countdown starts from, as set in the settings, or the longer wait the application is using after
   an HTTP 429 (see Refresh behavior);
7. the change since the previous reading, when its settings are on (both are off by default): first
   the change in the amount used, with its sign, for example `+0.05`, then the time since the previous
   reading, **in whole seconds, never in minutes or hours**, for example `63 s` or `105 s`; see Changes between readings;
8. a small button that shows the log below the row, and hides it again, an icon rather than a word, when its
   setting is on (it is on by default);
9. next to it, a small button that shows the usage history below the row, and hides it again,
   likewise an icon, when its setting is on (on by default);
10. a small button that shows the error log below the row, and hides it again, an icon rather than a word
    (see The error log panel), when its setting is on (on by default);
11. at the right-hand end, after the other buttons, the settings button, a very small icon of
    a gear, not a word and not sliders. It is always there, so that the other buttons can be brought back.

**An item of the row whose switch is on keeps its room.** The interval and the two changes (the percentage is part of the figures) are in the row when their
switches are on, and when the program has no value for one at that moment, for instance a change of zero or a time that cannot be worked out, the item is empty and not seen but keeps the width it would have, so the row does not change width from
one refresh to the next. An item whose switch is off is not there and takes no room.

A button that is switched off is not shown and takes no room, so the row is narrower by it (the window follows when the
settings are applied, as for any other item). Without its button a panel cannot be opened: if it was the panel that the settings would
give back when they are left (see Settings), nothing is given back.

**While the settings are shown, all the buttons are shown.** Pressing the gear shows the log, history and error log buttons beside it, to
its left in their usual places, whether or not their switches are on, so that every panel can be reached from there. When the settings are
left, by pressing the gear again or Apply or Cancel, the row has only the buttons that are switched on, as configured (the window follows the
row). The button of a panel that is shown is always shown, whatever its switch says: if an extra button is pressed while the settings are
shown, its panel replaces the settings and its button stays, so that the panel can be closed again; when the panel is closed the button goes
if it is switched off. A button that is switched off is therefore never taken away from a panel that is open.

The four icon buttons sit close together: the space between them is small, no
more than about 2 pixels, not the strip's usual gap.

**The button of the open panel is green, and bigger.** Of the log, history, error log and settings buttons, the one whose panel is
shown stands out so that it is seen at a glance: it is green, a vivid green and not the dark green of the severity (about
`#00b341` on the light theme and `#3ddc6b` on the dark one), its button is 24 pixels instead of 20 and its icon 16 pixels
instead of 12, and its lines are heavier (about 2.2 instead of 1.6; the gear, which is 13 pixels and drawn on a finer grid, is made to look the same). Twenty-four pixels is
the height of the refresh button, so the row keeps its height and the window does not change size when a panel is opened or
closed. When that panel is closed, or another is opened, the new one turns green and bigger and the one that was goes back to
what it was, as before: gray, 20 and 12 pixels, light lines. No more than one is green, and none is when no panel is shown.

The severity is shown by colour on the percentage, when it is shown, and on the amounts, rather than by
extra words. With the percentage switched off the amounts still carry the colour. The severity is named in
the tooltip of the percentage only: the tooltips of the amounts say what the number is and its unit, and nothing
about the severity.

**Countdown**

- The countdown is the number of whole seconds until the next scheduled refresh, to
  the nearest second.
- It is calculated by the backend, which reports it with the status. The window
  shows it as received and does not work it out itself.
- It is counted from when the most recent request was triggered, whether by the
  schedule or by the refresh button, plus the wait that applies: the usage interval,
  or the longer wait while the application is backing off after an HTTP 429. It
  therefore starts again after a manual refresh, and shows the longer wait during
  a back-off.
- It may be negative. A negative value means the next refresh is overdue by that
  many seconds, for example because a request is taking longer than the interval.
  The window shows it as it is, with the minus sign.
- Until the first request has been triggered there is nothing to count to, and the
  countdown is left empty.
- The countdown is not optional: it is always shown, as before. The time between usage requests is a
  separate item, with its own setting (see Settings).
- **While the application is backing off after an HTTP 429 the countdown is red**, and hovering over it
  shows the error message, in bold red, in a line under the row that is there while the pointer is over the
  countdown (a plain `title` tooltip cannot be coloured, and a tooltip of the page's own would not fit in a window
  as small as the row, which grows for the line as for any message and goes back when the pointer leaves). This is
  the only sign of a 429 in the row: it has no message line, and the figures are not dimmed, since the
  data is not out of date because the server asked us to wait. The red goes with the back-off, at the
  next success. The 429 is written to the log and to the error log like every failure.

**Tooltips**

Hovering over these shows what they mean. The currency is the one the response
names, shown as its code, here `USD`.

- The time: `Last update: ` followed by the full local date and time of the last
  refresh, for example `Last update: 8 Oct 2026, 14:24:53`. This is the one place a
  date appears.
- The used amount (the first number): `Credits used, in USD`.
- The budget (the second number): `Credit budget, in USD`.
- The percentage: how much of the budget is spent, and the severity, for example
  `19% of the budget spent. Severity: normal`, since the colour alone would be the only
  place the severity showed.
- The countdown: that it is the seconds until the next refresh, and negative when
  overdue.
- The refresh button says `Refresh now`, and the settings button says `Show the settings`, and
  `Hide the settings` while the view is shown.
- The change in the amount used says `Change in the amount used since the previous reading, in USD`,
  and the time since the previous reading says what it is.
- The log button says `Show the log`, and `Hide the log` while the log is shown. The history button says `Show the usage history`, and
  `Hide the usage history` while the history is shown. The error log button says `Show the error log`, and
  `Hide the error log` while it is shown.
- The countdown, during a back-off after an HTTP 429: the error message, in bold red, in a line under the row (see Countdown).
- The exact texts: the countdown, `Seconds until the next refresh (negative when overdue)`; the time since the previous
  reading, `Time since the previous reading`; the interval item, `Time between usage requests`. When the response named
  no currency the amounts say `Credits used` and `Credit budget`, and the change `Change in the amount used since the
  previous reading`, with no `, in ...` after them. A percentage that the response did not send has no text, and its
  tooltip is `Share of the budget spent`; `. Severity: <word>` follows only when a severity was sent.

**How the figures are written**

The backend writes every figure, and it writes them the same on every machine, whatever its language: a dot for the
decimals, a comma for the thousands, English for the month, and the 24-hour clock in the machine's own time zone.

- **Amounts in the row** have two decimals and a comma for the thousands, `1,234,567.89` and `0.00`. A third decimal
  is rounded to the nearer second one and upwards when it is exactly half, on the decimal form of the number, exactly
  as the history file rounds it (see Usage history), so the row and the file always show the same amount. An amount that is missing is shown as a dash, `—`, not as zero and not as nothing, and
  has no currency symbol even when the symbol is on.
- **The currency symbol.** Only the code `USD`, written so, is `$`. A code of blanks alone is no currency. In the history
  the cell is the same without the space after a code.
- **The percentage spent** is the whole number the response sent, with `%`; the program does not round it or work it out
  (but see the reading shown at startup, under Details of the behaviour).
- **The change in the amount used** has two decimals, rounded to the nearer and upwards at half, the comma for
  thousands, and always its sign: `+0.05`, `-1.20`, `+1,234.50`.
- **The time since the previous reading** is never less than `0 s`.
- **The times** are `HH:mm` or `HH:mm:ss`, with leading zeros. The date of the time's tooltip is `d MMM yyyy,
  HH:mm:ss`, the day without a leading zero and the month in English, `8 Oct 2026, 14:24:53`. The times of the error
  log always have seconds, whatever the time format setting says, and are in the zone the machine has when the panel
  asks.
- **The severity** is read without regard to case, so `WARNING` is a warning. A response with no severity has none: no
  word in the tooltip and no colour of its own.
- **The message line** is `Refresh failed at <time>: <message>`, the time as the time format setting says, with or
  without seconds.
- **`No usage reported`** comes with the time of that reading and its tooltip, since there is a reading; `Loading…` and
  `No data` come with no time.
- **The change shown in the row** is that of the newest row of the history that has a `used` or a `limit`. There is
  none while the program has no reading, even when the history has rows.

**Changes between readings**

- The change in the amount used is the amount used minus that of the row just before it in the
  history; the time since the previous reading is the time between the two rows. The backend works
  both out from the history file and sends them with the status. The row shows the values of the
  newest reading, and the history panel those of each row (see Settings for the switches).
- The two are worked out only between rows of the same run, which is from a row whose status marks
  a start (see Usage history) to the row before the next such row. The first row of a run has neither.
- The time since the previous reading is counted to the row directly before it, a failed one
  included, so a failed row has a time and the row after it counts from the failed row. The change in
  the amount used is likewise against the row directly before: if that row is a failed one, with no
  amounts, there is no change, and a failed row itself has none.
- A value that cannot be worked out is left empty, not shown as zero.
- **In the history the two are worked out on the lines that are shown.** When the history leaves lines out (see Hiding lines),
  the change in the amount used and the time since the previous reading of a line are those against the previous
  line that is shown in the same run, not against the row directly before it in the file: with the unchanged readings hidden, the time is the
  time since the previous reading that is shown, and so is the change. A run is still what the whole file says: a row whose status marks a start begins
  a run even if it is hidden, and the first line shown of a run has neither. The main row's items are not affected by what the history hides: they
  are worked out against the row directly before, as above.
- **A change in the amount used of exactly zero is left empty too**, as if it could not be worked out: no `0.00` in
  the history column and no change item in the row. It is a figure that says nothing. The time since the previous
  reading is shown whatever it is.
- **The arithmetic is exact, on the text of the file**: `10.25` less `10.00` is `0.25`, and an amount that went
  down gives a negative change. A time of no seconds at all is a value, `0 s`; only a negative one is empty. Each
  of the two is given when it can be worked out, whether or not the other can.
- **The time is the difference of the two local times as they are written.** Across a change of the clocks it is
  therefore an hour too long when they go forward, and too short, or empty, when they go back.
- **A row that follows a failed row has no change in the amount**, so it is never a zero usage line and is always
  shown, even when its amount is the one of the last good reading.

**Settings**

- Pressing the settings button shows the settings view in the same place as the log and the history,
  below the row and its message lines, sharing the one panel area with them: showing one replaces the
  other. Its tooltip and behaviour are those of the other two buttons. It replaces the config button
  and the interval field in the row, which are gone.
- **Leaving the settings brings back the view that was shown before.** If the log, the history or the error log was shown when
  the settings button was pressed, it is shown again when the settings are left, whichever way they are left (Apply, Cancel, or the
  settings button once more), at the size it had before, so the person finds the window as they left it: the history and the log at
  their remembered height, the error log at its opening size. It shows the changes the settings made: the history with its new
  columns, times and date, the row with its new items. If nothing was shown before, nothing is shown afterwards, as before.
  Pressing the button of another panel while the settings are shown shows that panel, and the settings are left without coming back
  to anything: what was shown before the settings is then forgotten.
- **The window is exactly as tall as the row, its message lines and the settings need**, so the
  settings are shown whole, with no vertical scrollbar, and with no empty space below them. The height of the
  row counts: the settings are never clipped by it. The height follows the content: when the row changes,
  in particular when a message line such as an error appears, grows or goes, the window follows at once, so the
  settings stay whole and the window is no taller than needed. The window cannot be resized by hand while the
  settings are shown. The width is the row's.
- The view shows every setting with its current value, which the frontend has just asked the backend for,
  each time the view is opened. The frontend keeps no setting of its own: what is shown is what the
  backend has.
- Changes made in the view take effect only when the **Apply** button is pressed. Apply sends all the
  values to the backend, which keeps them and saves them in the settings file, and then **closes the
  view**; the main view shows the changes at once. A **Cancel** button closes the view, and no value
  is changed; it asks nothing. A **Restore defaults** button sets the fields to the defaults below; it
  does not apply them, so Apply is still pressed to make them take effect. If the backend refuses a
  value, or the settings cannot be saved, the view stays open and shows a brief message in red; nothing
  is changed.
- The settings, with their defaults:

  | Setting | Values | Default |
  | --- | --- | --- |
  | Interval (the time between usage requests) | a single entry field for a whole number of seconds from 5 to 3600, showing the backend's current value; there is no dropdown list | 60 s |
  | Log the response | on, off | off |
  | Show the percentage spent in the row | on, off | off |
  | Show the currency symbol in the row, before the amounts | on, off | off |
  | Show the history icon in the row | on, off | on |
  | Show the log icon in the row | on, off | on |
  | Show the error log icon in the row | on, off | on |
  | Show the time between usage requests in the row | on, off | off |
  | Show the change in the amount used in the row | on, off | off |
  | Show the time since the previous reading in the row | on, off | off |
  | Time format in the row | hours and minutes, or hours, minutes and seconds | hours and minutes |
  | Show the date in the history, as well as the time | on, off | off |
  | Show the zero usage lines in the history | on, off | on |
  | Show the failed lines in the history | on, off | on |
  | Show the change in the amount used in the history | on, off | off |
  | Show the time since the previous reading in the history | on, off | off |

- A value typed in the interval box that is not a whole number from 5 to 3600 keeps the view open
  and shows a brief message in red when Apply is pressed; the backend refuses it, as before. The current value,
  whatever it is (set on the command line, or in an old settings file), is what the field shows. The field has
  its unit, `s`, beside it, and there is no list of values to pick from.
- **The texts of the view.** The section that holds the interval is headed `Request` (not `Usage requests`), and the interval's
  label reads `Interval`, and so does the checkbox for the row's item;
  the row item's tooltip keeps `Time between usage requests`. The checkbox for the log reads `Log the response`.
  Under the heading `History view` the checkboxes read `Date`, `Zero usage lines`, `Failed lines`, `Δ used` and `Δ time`
  (the last two like the titles of the columns they switch on): no `Show` in front, no `Column`, and no colon in them. Under `Main view` the two checkboxes for the row keep their longer
  texts, `Change in the amount used` and `Time since the previous reading`. The other new ones in `Main view` read `Currency symbol`, `History icon`, `Log icon` and
  `Error log icon`.
- **A checkbox and its text are centred on each other vertically**, and so is every other control and its label
  (the interval's field, the time format), in every line of the view, so that nothing sits higher or lower than its
  label.
- **Every control is wide enough for its own value**, the arrow of a dropdown included: the value is never
  covered by the arrow, nor cut off.
- **The text of the view can be selected and copied.**
- The view is arranged in sections: `Request`, `Main view`, `History view` and `Log`.
- Two buttons set the switches of the main view together: **Maximum view** turns on the percentage, the currency symbol, the time between
  usage requests, the change in the amount used, the time since the previous reading, the three icons (history, log, error log) and the time
  with seconds; **Minimum view** turns them all off, the percentage with them, and sets the time to hours and minutes: a bare strip, with the gear
  as its only button besides the refresh button. The settings of the history, the
  interval and the logging of the response are left as they are. They fill in the fields like Restore
  defaults does, and Apply is still pressed.
- Turning on logging of the response makes the application write, for each response, the JSON it was
  sent, pretty printed over several lines, in the log (see Non-functional requirements).
- The command line still overrides the interval for the run, as before, until a value is applied here.

**The log panel**

The log is shown the way the usage history is: inside the main window, not in a window of its own.

- Pressing the log button makes the main window ten times as tall, as for the history, **and three times
  as wide as the single row**, and shows the
  end of the log file, `java-aip-usage.log`, in a panel below the first row and its message lines.
  Pressing it again hides the panel and the window goes back to the size it had. While it is shown the
  window can be resized in height **and in width**, and the panel takes the room there is. Unlike the
  history, the log has long lines, which is why it gets the extra width. Opening it again starts again
  at three times the row's width, and at the height it was last left at (see Remembered heights), never
  less than ten times the row's.
- The log panel has the width of three rows, plus the width of the vertical scrollbar, which is always reserved
  and never covers text. As for the history, the window's height does not follow the main view while the
  panel is shown: when a message line appears or goes, the window keeps the height it has and the panel takes
  up the difference.
- The log, the history, the error log and the settings share the one panel area: showing one while another is shown replaces
  it, and the window takes the size the shown one has (so it is wider for the log than for the history, and
  as tall as its content for the settings),
  and each button's tooltip says `Hide…` only for the one
  that is shown.
- One line of the file is one line of the panel, as written, newest first like the history, in the
  same small, condensed, regular-weight, fixed-width text, and it is never wrapped (a long line
  scrolls sideways). The response JSON that the log setting writes (see Non-functional requirements) is
  several lines, each of them a line of the panel, in the order the entry reads from the top
  down even though the panel is newest first: the lines of one entry stay together and in order.
- It shows the end of the log: the newest 1,000 lines, reading at most the last 512 kilobytes of the
  file, since the log is never rotated and can be large. If lines were left out, a line above them
  says how many are shown.
- While the panel is shown it keeps up with the file: new lines appear at the top soon after they
  are written, without pressing anything, and what the person has scrolled to does not move. There
  is no reload button.
- The line that records the start of a run (see Non-functional requirements) has a gray
  background, `#e6e6e6` on the light theme and a dark gray, `#30363d`, on the dark one so that the light text on it can be read, so where each run begins can be seen at a glance. The log is appended to across runs, so
  there can be several such lines; each is marked. Only the background differs: the text is the same.
- If there is no log yet, or it is empty, the panel says so in one line. If it cannot be read, the
  panel says so in red, and shows what it had.
- The file itself is handled like the usage history file: in the project root, beside `gradlew`,
  added to and never rotated or overwritten, kept out of version control, and its text comes from
  the application through a read-only request that reveals nothing about where the file is.

**The usage history panel**

The history is shown inside the main window, not in a window of its own.

- Pressing the history button makes the main window **ten times as tall** as it was, and shows the
  recorded usage in a panel in the added space below the first row, below any message line, as lines
  of text like a log file. Pressing the button again hides the panel, and the window goes back to the
  size it had, which is the single row again.
- **While the panel is shown the window can be resized in height**, by dragging its bottom edge, down
  to the size the window has with the panel hidden (it can be made as small as it originally was), and the
  panel takes all the height there is: a taller window shows more lines. Its width stays fixed. When the
  panel is hidden the window is back to fitting its row and cannot be resized. Opening the panel again
  starts at the height the person last left it at, never less than ten times the row's height (see
  Remembered heights).
- **The window is as wide as the table needs, so that every column is whole**, never clipped: the time (with or
  without the date), `used`, `limit`, when their settings are on `Δ used` and `Δ time`, and the currency, plus the
  width of the vertical scrollbar, which is always reserved so that nothing shifts when it appears or goes. It is
  never narrower than the row and the scrollbar. The page measures the table, so the width follows it when a
  setting adds or removes a column or the date, and nothing is computed from fixed numbers. The width cannot be
  dragged: the person changes the height only. The scrollbar never covers a column: the last one is whole next to it. The table keeps a margin at its right
  edge as wide as a scrollbar, always, so that a scrollbar that is drawn over the content (as on macOS, where it takes no room of its
  own) does not hide the title of the last column, `Cur.` or a change column, either.
  Nothing else in the row moves.
- **While the panel is shown, the window's height does not follow the main view.** When a message line
  appears or goes, or the row changes in any other way, the window keeps the height it has, as opened
  or as the person dragged it, and the panel takes up the difference.
- The history is a table with a header row (`time`, `used`, `limit`, and, when
  their settings are on, `Δ used` and `Δ time`, and last `Cur.`; it does not scroll away) and one line for each reading, with space between the
  columns so that nothing is cut off (in particular the last title). **The time is at the left, and every other column is at
  the right side**: `used`, `limit`, when switched on `Δ used` and `Δ time`, and **the currency last, as the right-most column**, are packed against the right edge of the
  panel, each right-aligned in its own column, so that the numbers are lined up by their last digit, and the free space of the
  panel is between the time and `used`. **The currency's title is `Cur.`**, and its cells show `$` for US dollars; for any other currency they keep the code the
  response named, for example `EUR`, and are empty when it named none. **There is a little more space between `used` and `limit`** than between the others, so
  the two numbers do not run together: about three characters of room between them, besides the usual gap, and no more. For example, as wide as the row:

  ```
  time        used     limit  Δ used  Δ time  Cur.
  21:01:22   263.89   1000.00   +0.26    72 s     $
  20:46:11   260.66   1000.00           63 s     $
  ```

- **The first column is the time of day only**, `21:01:22`, and its title is `time`. A setting (see
  Settings) shows the date as well, `2026-10-08 21:01:22`, and then the title is `date time`. The file keeps
  the full date and time either way. The backend cuts the time; the window shows it as received.
- **Hovering over a line of the first row of a run**, the green one below, shows the
  text `The program started here`.
- **A horizontal scrollbar** appears at the bottom of the panel when the columns are wider than the panel, which
  happens only if the table is wider than the largest window there is (2,400 pixels), so that nothing is out of
  reach. It takes no room when it is not needed.

- The column titles are aligned like their columns: `time` at the left, the others at the right.
- `Δ used` and `Δ time` are the change in the amount used and the time since the previous
  reading, as described under Changes between readings, worked out by the backend for each row from the
  file and sent with the rows. The titles are a delta symbol, a space, and `used` or `time`: they sit
  beside the `used` column and are told apart from it by the symbol. Each column is shown only when its setting
  is on, and both are off by default; the columns are between `limit` and the currency, which stays the last. An empty value is an
  empty cell, and a change in the amount used of zero is an empty cell too, not `0.00`. **The time is in whole seconds, with the unit,
  never in minutes or hours**: `63 s`, `126 s`, `3600 s`. (The row's item shows it the same way, in seconds.)
- **Hiding lines.** Two settings (see Settings) say whether the history shows its zero usage lines and its failed lines; both are
  shown unless switched off. A **zero usage line** is a line whose change in the amount used, as under Changes between readings (against the
  row directly before it, in the same run), is exactly zero: the reading is the same as the one before. **The startup lines count as zero usage
  lines**: the first line of each run, the green one with the `The program started here` hover text, has no change and
  is handled like the others that show no change, so it is hidden and shown with them. A startup line of a failed query (it has no amounts) is a failed line
  and goes with those. A **failed line** is a row of a failed query. When the startup lines are hidden, the first line shown of a run has no change
  either, since there is no line before it to compare with. What is hidden is
  left out of the table, and so of its sorting, its count of 1,000 lines and the changes worked out on it (see Changes between readings).
  The file itself is not touched.
- **When not all the data is shown, the panel says so.** One line above the table, written by the backend, says how many lines there are,
  how many are shown, and why the others are not: hidden zero usage lines (the startup lines among them), hidden failed lines, and older lines beyond the newest 1,000. For example
  `Showing 640 of 1,500 lines: 700 zero usage and 60 failed hidden, 100 older not shown.` It is there whenever anything is left out, and not
  otherwise; it replaces the line that said how many of the lines were shown. The window shows it as received. **It is blue** (about `#0969da` on the
  light theme and `#58a6ff` on the dark one), in the panel's own small regular text, not bold, so that it stands out by its colour as the sign that not everything is shown. **The error log's `There are no errors in this run.` is blue as well**,
  the same blue, since it too is a line that tells the person something about what they are looking at; the other notes (no history yet, no rows yet, and the log's) keep their gray.
- **The page does no calculation and makes no decision about the lines.** The backend sends each line finished: its cells, as the table
  shows them (the time as the setting says, the amounts, `failed` in the place of the amount of a failed line, the changes), whether it is the first
  line of a run, whether it is a failed line, and its hover text. The page only puts them on the screen. The status, the interval and the duration of
  a row never reach it.
- A row of a failed query (status `failed` or `start-failed`, see Usage history) is shown with its
  time, empty amounts and empty currency, and the word `failed` in red in the `used` column.
- The line of the first row recorded after the program started (a row whose `status` is `start` or
  `start-failed` in the file, see Usage history) is shown **in green text**, the whole line, so
  where each run begins can be seen at a glance: the vivid green of the button of the open panel (`#00b341` on the light theme,
  `#3ddc6b` on the dark one). **It has no special background**: the background is that of every other line, not gray and not
  coloured, and the line is not bold. (A failed startup line keeps its red `failed` in the place of the amount; the rest of its
  text is green.) The file can hold several such rows, one for each run; each is marked. The `status`, `interval` and `duration_ms` columns themselves are not shown in
  the panel.
- **The lines are sorted by the date and time, latest first.** They are sorted by that, not merely
  taken in reverse file order, so a file that is out of order is still shown right. Two lines of the
  same second keep the file's own order, the later written one first, so that a reading and the failure
  or startup line beside it do not change places from one reading of the file to the next.
- **The columns have room for the amounts that occur, not for every amount.** The widths are fixed so
  that the numbers of every line are under one another, which they could not be if each line were as wide
  as its own content; they hold amounts up to `9999999.99`, ten characters, written as the history writes them, without digit grouping. A larger amount than that overruns its
  column rather than widening the table. This is the one place in the window where a figure is given a
  fixed width in characters.
- The text is **small and condensed**, in the manner of a log file: a fixed-width font of about
  12 pixels with tight line spacing and no padding between lines, so that many readings fit in
  little space. This is the one place the 14-pixel minimum does not apply. It is regular weight,
  not bold, and not thin; only the header row of the history, and that of the error log, is bold.
- The panel scrolls: older readings are reached by scrolling it, or by making the window taller. It
  holds the newest 1,000 of the lines that are not hidden; the line above the table says so (see above).
- While the panel is shown it keeps up with the history: a new reading appears at the top soon
  after it is recorded, without pressing anything. It does not move what the person has scrolled to.
- A line of the file that does not have at least the columns `datetime`, `used` and `limit` is left out, so one damaged line cannot
  hide the others (what exactly a row is, is under Details of the behaviour). If there is no history yet, or it has no readings, the panel says so in one line.
  If it cannot be read, the panel says so in red, and the window shows what it had.
- The panel's data comes from the application, through a read-only request that reveals nothing
  about where the file is and adds nothing to the log or the history.

**Remembered heights**

- The height of the window while the history is shown, and while the log is shown, is remembered for each
  of the two, as `historyHeight` and `logHeight` in the settings file, in pixels of the window's content. The
  width is not. They are not settings of the settings view: they are neither shown nor applied there, and
  Restore defaults leaves them alone.
- When the person changes the window's height while the history or the log is shown, the new height is stored,
  once the change has settled, not at every pixel of a drag. The program, not the page, notices: the page never
  reports a height the window was dragged to.
- When the history or the log is opened, the window gets the stored height. If there is none, or it is smaller than
  the height the program works out itself, ten times the row's height as it is at that moment, the worked-out
  height is used instead, and it is stored as the new value.
- The error log and the settings do not remember a height: the error log opens at ten times the row's height, and the
  settings are as tall as their content.
- A height that cannot be stored is logged and nothing more: the window keeps the size it has, the height is not
  taken as remembered, and the next change tries again. A height is not worth a damaged settings file: if
  `settings.json` is there and is not a JSON object, the height is not stored and the file is left as it is, as it is
  until a setting is applied. A height equal to the one remembered writes and logs nothing. A stored `0`, like no key
  at all, is no remembered height.

**The error log panel**

The error log is a third panel in the same area as the log and the history, for the errors of the program as
they happen, so that a person can see what went wrong and when without opening the log file.

- A small icon button, between the history button and the settings button, shows the panel and hides it again, as
  for the others. It shares the panel area with the log, the history and the settings: showing one replaces the other.
- It lists the errors of **this run only**. It is kept in the application's memory and is not written
  to a file: when the program stops, it is gone. The next run starts with an empty error log. (The same errors are in the log file, as
  always.)
- One line for each error, newest first, in two columns, `time` as `hh:mm:ss` (the local time of day, with
  seconds) and `message`, in the small, condensed text of the log. A message is never wrapped (a long
  one scrolls sideways).
- **Every failed refresh is an error of the list**, with the message the row would show for it:
  an HTTP 429 (with how long the application now waits), any other HTTP status, a network failure or timeout,
  a response that cannot be read, and every problem with the token: Claude Code not logged in, `claude` not
  found on the `PATH` or not able to run, and a credential in the environment that has to be unset. A failure that
  has no message line, an HTTP 429, is in the list all the same. One line is added for each failed refresh, not for each
  poll of the window.
- It holds the newest 1,000 errors. If there are none yet, the panel says so in one line, `There are no errors in this run.`, in blue.
- While the panel is shown it keeps up: a new error appears at the top soon after it happens, without pressing
  anything, and what the person has scrolled to does not move.
- **Its size is half that of the log in width**: ten times the row's height, and one and a half times the row's width plus the
  vertical scrollbar, resizable in height and in width, the height not following the main view, and a horizontal scrollbar when a
  line is wider than the panel. It opens at that width, not narrower; a long message scrolls sideways.
- The data comes from the application through a read-only request that reveals no credential and no file name.

**Messages and states**

- A failed refresh, stale data, or a missing or logged-out Claude Code must still be
  visible, as required elsewhere, except for an HTTP 429, which has its own, quieter sign (see Countdown).
  In the compact window this is a short message on
  a second line, shown only while the condition lasts, in the form
  `Refresh failed at 14:25:01: <what went wrong>`. The window grows to hold it and
  returns to its single-row size afterwards.
- **Error text is red.** Every error message is shown in red: the failed refresh, whether
  or not older figures are still on show, the loss of contact with the application, and an
  invalid value in the settings view. No error is shown in another colour.
- The last good figures stay in the row after a failed refresh, dimmed to show that
  they may be out of date, and the dimming goes when a refresh succeeds again. After an HTTP 429 they
  are not dimmed.
- **On startup the newest reading in the history file is loaded and shown at once**, as the row's
  figures with the time of that reading, until the first refresh replaces it, so the row is not empty
  while the first request is on its way. If that refresh fails, the figures stay, dimmed, with the usual
  failure message. It is not written to the history again. The reading shown is the newest row that has
  amounts, so a failed row is passed over. If there is no history, or no row has amounts, nothing is shown in
  advance.
- Before the first reading the row says `Loading…`. If the first refresh failed it says
  `No data`, and if the account reports no usage at all it says `No usage reported`.
- If the window cannot reach the application, it says so in red (`Lost contact with the
  application. Still trying.`), keeps trying, and shows what it last had.
- The window also resizes to fit when a setting adds or removes an item of the row, as the settings view
  is applied.
- A message line, the warnings among them, shown in the row is also written to the log (see Non-functional
  requirements).

The refresh button stays enabled at all times, and the behavior of everything
behind these controls is unchanged.

## Usage history

Every reading the application gets is kept, so the usage can be looked at afterwards.

- Each refresh that has amounts, and each failed one, adds one row to a CSV file named
  `java-aip-usage.csv` in the project root, beside `gradlew` and the log. The file is not
  committed to version control. It is added to, never overwritten, so successive runs build
  one history, and nothing in it is ever rotated or deleted by the application.
- The columns are `datetime`, `used`, `limit`, `currency`, `status`, `interval` and `duration_ms`, with a
  header row written when the file is new or empty and never again, for example:

  ```
  datetime,used,limit,currency,status,interval,duration_ms
  2026-10-08 16:24:53,186.02,1000.00,USD,start,60,412
  2026-10-08 16:25:53,186.07,1000.00,USD,,60,388
  2026-10-08 16:26:53,,,,failed,60,5003
  ```

- `status` says what the row is. It is `start` on the first row written after the program started,
  `failed` on the row of a query that did not succeed, `start-failed` when the first row of a run is
  a failed one, and empty on every other. Each run has one row marked `start` or `start-failed`, so a file
  built over several runs has one per run, each where that run's first row was recorded. A run that
  records no row marks nothing. The mark is the first row actually written, so a reading with no amounts,
  which writes nothing, leaves it for the next row that is written.
- `interval` is the time between usage requests that was set when the request was made, in whole seconds.
  `duration_ms` is how long the query took, from the start of the refresh to the answer or the failure, which **includes getting the token** (a
  run of `claude`, up to 30 seconds) and the one retry after an HTTP 401, in whole milliseconds, so the first row of a run is usually the longest.
  The `interval` is the interval the application was using, the configured one or the one from the command line, not the longer wait of a back-off. Both are on every row, failed ones included, and plain numbers.
- A failed query's row has `datetime` of the time of the failure, since there is no `fetched_at`, and
  empty `used`, `limit` and `currency`. What went wrong is in the log, not in the file.

- `datetime` is the time of the reading, which is `fetched_at`, as the local date and time
  to the second in the form `yyyy-MM-dd HH:mm:ss`, with a space between the date and the
  time and no `T`, no `Z` and no zone offset. That is the form Excel recognises as a date
  and time when it imports the file, where an ISO 8601 `2026-10-08T14:24:53Z` it leaves as
  text. It is the machine's local time, the same clock the window shows, so the two agree.
  The file does not say which zone it is in, because Excel has no use for one, and the hour
  in which the clocks go back appears twice. It still sorts correctly as text.
- The columns are separated by commas and the numbers use a dot. A copy of Excel set to use a
  decimal comma may open the file as one column or read `260.36` as text; import it with
  Data, From Text/CSV, and choose comma as the delimiter and the dot as the decimal.
- `used` and `limit` are plain numbers with two decimals and a dot, whatever the machine's
  language, with no currency sign, no digit grouping and no quoting. A third decimal, which the
  endpoint can send, is rounded to the nearer second decimal and upwards when it is exactly half,
  so the same reading always writes the same text. A missing amount is an
  empty field. `currency` is the code the response names, for example `USD`, or empty when it named none.
- A file written before these columns is upgraded in place the first time a row is added: the header
  gets the new columns and the rows already in it get empty fields where the file never said. The header
  `datetime,used,limit` gets `currency`, `status`, `interval` and `duration_ms`; `datetime,used,limit,currency` gets `status`, `interval` and
  `duration_ms`; and the header `datetime,used,limit,currency,startup` is replaced by the new one, and a `1` in `startup` becomes
  `start` in `status`. Only an exact match of one of the three old headers is upgraded; any other header leaves the file as it is. Nothing else in it changes.
- A reading with no amounts, that of an account that reports no usage, writes nothing. A
  failed query does write a row, as above, and a response in the seat-based format is a failed
  query (see Source data), so an account that answers in it gets a `failed` row at every refresh.
- A history that cannot be written is logged and nothing more. The reading is good, so the
  refresh still counts as a success, and the window shows it as usual.
- **A file is never seen half written.** Adding a row is an append, so a reader sees whole rows. The two
  writes that replace a file instead — the upgrade of an old header above, and the settings file — write a
  new file beside the old one and then put it in the old one's place in one step, so that the window, which
  reads the history while the application writes it, never finds a file that is missing, empty or half
  converted. Where the file system cannot do it in one step, the plain replacement is used, which is as
  good as that file system allows.
- The file holds spending figures, so it stays on this machine, and nothing in it is a
  credential. At the default interval of a minute it grows by about 60 kilobytes a day.
- **The file as bytes.** It is UTF-8. Every row the program adds, and the header, ends with a single line feed
  on every operating system, Windows included; the header of a new file and its first row are written together.
  A file that is upgraded from an older header is written the same way, every line of it, so a file never has
  two kinds of line ending because of the program.
  A file whose lines end with a carriage return and a line feed is read all the same.
- **What is written, more exactly.** A reading with one of the two amounts missing still writes a row, with
  that field empty. An amount used of zero is written `0.00`, not left empty. The `datetime` is cut to the
  second, not rounded: 14:24:53.987 is `14:24:53`. A comma in the currency is written as a space, so that the
  field can never break the columns; nothing else is escaped or quoted. A negative amount is rounded away from
  zero when it is exactly half.
- **The upgrade, more exactly.** The old header is compared with blanks round it taken off. A blank line stays
  as it is; every other line gets the fields it lacks, empty, whatever is in it; under the `startup` header a
  line that ends `,1` gets `start` and every other an empty status. The new file is written beside the old one
  as `java-aip-usage.csv.tmp`, and none is left behind. If the upgrade fails, the row of that refresh is not
  written. A row is added to a file whose header is none of the known ones without a header being added.

## Details of the behaviour

These are things the program does that the sections above do not say, written down so that the requirements are the whole of it.

**Command line**

- `-h` and `--help` print the usage text and the program ends without opening a window. The options that take a value are `--usage-interval <seconds>`, `--poll-interval <seconds>`, `--anthropic-url <url>` and
  `--fake-scenario <name>`, each also as `--name=<value>`; `--fake-token` and `--fake-backend` take none. Values are trimmed. A mistake prints one of these messages and then the usage text on the error output,
  and the program ends with exit code 2 before anything is logged or shown:
  `Unknown option: X`, `<option> needs a value in seconds.`, `<option> was given more than once.`, `<option> must be a whole number of seconds, not "x".`, `<option> must be from 5 to 3600 seconds.`
  (1 to 60 for the poll interval), `--anthropic-url needs a URL.`, `--anthropic-url must be an absolute http or https URL, not "x".`, `<option> takes no value.` for a value given to one of the two that take none,
  `--fake-backend and --anthropic-url cannot both be given: each says where usage comes from.`, `--fake-scenario needs a name.`, `--fake-scenario needs --fake-backend.`, and
  `--fake-scenario must be one of <the names>, not "x".`
- A command-line interval is never saved on its own: `settings.json` keeps what the person last applied.
- `--anthropic-url <url>` replaces the base URL of the usage endpoint for the run. It is accepted when it is an absolute URL whose scheme is `http` or `https` and which has a host; any host is allowed, this machine
  or any other, and no warning is given, because the person naming the URL is the one deciding where the token may go. A trailing slash is ignored, so `http://127.0.0.1:8080` and `http://127.0.0.1:8080/` are the same.
  A path is kept as a prefix, so `http://127.0.0.1:8080/proxy` is fetched as `http://127.0.0.1:8080/proxy/api/oauth/usage`, which is what a proxy under a path needs. A URL with a query or a fragment is a mistake and
  draws the message above, since neither has a meaning here. The default is `https://api.anthropic.com`.
- `--fake-token` leaves out the token flow for the run, as Authentication says. A value given to it (`--fake-token=1`) is the mistake named above.
- `--fake-backend` fetches from the application's own fake backend for the run, as Fake backend says, and leaves out the token flow with it. `--fake-scenario <name>` chooses what that backend answers; it is
  meaningless without `--fake-backend` and is refused rather than ignored, so a run never quietly fetches real usage because the option that was meant to make it fake was forgotten.
- None of the four is ever saved, as `--poll-interval` is not, and none has a setting or a window control: they belong to one run and are forgotten when the program stops.
- **Help.** The usage text goes to the standard output, the program ends with exit code 0, and it ends before the log is set up: no log file is created or added to and nothing is logged. A mistake wins over help: the whole
  command line is read before help is acted on, so `--help --bogus` prints the mistake and the usage text on the error output and ends with exit code 2. `-h` and `--help` may be given more than once, and anywhere among the
  other options, without that being a mistake. `--help=x` is not help: it is `Unknown option: --help=x`.
- **What is an option.** The names are matched exactly and with their case; there are no abbreviations. Anything that is not one of them is an unknown option, a bare argument included (`10` draws `Unknown option: 10`), and
  the message gives the whole argument as typed, with its `=value` if it had one. The options may come in any order: `--fake-scenario slow --fake-backend` is as good as the other way round.
- **Where the value comes from.** In the two-argument form the value is the next argument whatever it is, even one that looks like an option: `--usage-interval --help` draws `--usage-interval must be a whole number of
  seconds, not "--help".` and shows no help. `needs a value` is said only when the option is the last argument. An empty value after `=` is, for an interval, `<option> must be a whole number of seconds, not "".`; for the URL,
  `--anthropic-url needs a URL.`; for the scenario, `--fake-scenario needs a name.`; a value of blanks alone is the same.
- **What an interval value may be.** After trimming it is read as a whole decimal number, so a leading `+` and leading zeros are accepted (`+10`, `010`). `1.5`, `1e3`, a word, and a number too large to hold draw the
  `whole number` message; a whole number outside the range, zero and the negative ones included, draws the range message. The `whole number` message gives the value as typed; the URL and scenario messages give it trimmed.
- **One mistake is reported, the first.** The arguments are checked from left to right and the first mistake ends it. For an option with a value the order is: no value, given more than once, a bad value. For `--fake-token` and
  `--fake-backend`, `takes no value` comes before `given more than once`. The two rules about options together are checked last, when the whole line has been read: `--fake-backend` with `--anthropic-url` first, then
  `--fake-scenario` without `--fake-backend`.
- **The URL, more exactly.** The scheme is read without regard to case, so `HTTP://127.0.0.1:8080` is accepted, and kept as typed. One trailing slash is dropped, not several.
- **The scenario names** are `normal`, `http-401`, `http-403`, `http-429`, `http-429-retry-after`, `http-500`, `not-json`, `empty`, `no-spend-no-windows`, `trailing-text`, `slow` and `hang` (see Fake backend for what each
  answers). A name is read without regard to case and trimmed, so `NOT-JSON` is `not-json`. `<the names>` in the message above is this list in this order, separated by a comma and a space.
- **The usage text** is this, word for word. The ranges, the defaults, the default URL, the default scenario and the list of scenarios in it are the ones the program checks against, so the text cannot say one thing and the
  program do another:

  ```
  Usage: java-aip-usage [--usage-interval <seconds>] [--poll-interval <seconds>]
                        [--anthropic-url <url>] [--fake-token]
                        [--fake-backend [--fake-scenario <name>]]

    --usage-interval <seconds>  how often usage is fetched from Anthropic (5-3600, default 60)
    --poll-interval <seconds>   how often the window asks for the latest state (1-60, default 1)
    --anthropic-url <url>       where to fetch usage from, without the path (default https://api.anthropic.com)
    --fake-token                send a placeholder token instead of obtaining a real one
    --fake-backend              fetch from a fake backend inside this program, not from Anthropic
    --fake-scenario <name>      what the fake backend answers (default normal):
                                normal, http-401, http-403, http-429, http-429-retry-after, http-500, not-json, empty, no-spend-no-windows, trailing-text, slow, hang
    -h, --help                  show this help

  A usage interval chosen in the window is saved to settings.json and replaces --usage-interval
  for the rest of the run. --poll-interval applies to this run only and is never saved, and so
  do --anthropic-url, --fake-token, --fake-backend and --fake-scenario.

  --fake-backend needs nothing of Anthropic: no account, no login, no network. It implies
  --fake-token, because it ignores the token, and cannot be combined with --anthropic-url.
  ```

**The usage request**

- The connection is made within 10 seconds and the answer comes within 20 seconds, else the refresh fails with `Anthropic did not answer within 20 seconds.` A redirect is never followed, so the token cannot
  be sent to another host; it fails like any other status, with the status it had, for example `Anthropic returned HTTP 302.` A connection that is not made within the 10 seconds draws the same
  message as an answer that does not come, the one that names 20 seconds, and both are `timed out` in the log.
- **The connection is direct, and that is a decision, not an omission.** No proxy is used and no proxy
  setting of the environment is read, `HTTPS_PROXY` and `HTTP_PROXY` among them. On a network that lets
  nothing out but a proxy every refresh therefore fails with `Cannot reach <host>: <detail>`, like any
  other host that cannot be reached, and the window says so rather than appearing to work. There is no
  option, no setting and no window control for a proxy: a proxy in front of the endpoint is reached by
  naming it as the base URL (`--anthropic-url`, see Command line), which is the one way to put another
  server in the path.
- The messages of a failed refresh (after `Refresh failed at HH:mm: `): `Anthropic rejected the OAuth token (HTTP 401).`, `Anthropic refused the usage request (HTTP 403).`,
  `Anthropic is rate limiting usage requests (HTTP 429).` followed by ` Next try in N s.` or ` N min.` (rounded up), `Anthropic returned HTTP n.` for any other status, `Cannot reach <host>: <detail>` (the one message with no full stop of its own, since it ends with the system's text) where the host is the one
  of the base URL in force, without its port, so the default run reads `Cannot reach api.anthropic.com: <detail>` and a run against the fake backend reads `Cannot reach 127.0.0.1: <detail>`,
  `Interrupted while fetching usage.`, and `Unexpected error (<kind>); see the log.` When the retry with a fresh token is rejected too: `Anthropic rejected a freshly obtained OAuth token (HTTP 401). Log in again with Claude Code, then refresh.`
- The response is read as follows. `spend` counts only when its `enabled` flag is true; amounts are minor units divided by ten to the power of the exponent (0 if none); the currency is that of `used`, else of `limit`; the percentage is used as sent
  if it is a number; the severity is the text sent. The plan windows are the top-level objects that have a numeric `utilization`, in the order they come, except `extra_usage`. A response that is not JSON, not an object, empty, has trailing text,
  enables spend but reports neither `used` nor `limit`, or has neither a `spend` object nor any window is a failed refresh whose message says what is wrong (for example
  `The usage response has neither a "spend" object nor any usage windows; its format may have changed.`). A `spend` that is not enabled, with no windows, is an account that reports no usage.
- The wait and the amount of a `Retry-After` header are only believed as a whole number of at most six digits; a date is taken as no header.
- A manual refresh that has been asked for and has not started yet shows a countdown of 0 or less. `POST /api/refresh` answers 202 with `{"started":true}` when it started a request and 200 with `{"started":false}` when one is already
  running or asked for, and also when refreshing has not started yet or has been stopped.
- **What is sent.** One `GET` with two headers of the program's own, `Authorization: Bearer <token>` and `Accept: application/json`, and no body. It sends no `anthropic-version`, no `anthropic-beta` and no user agent of its
  own, keeps no cookies and answers no challenge. The security of the connection is the platform's: its certificates and its defaults, with nothing of the program's own added or taken away.
- **Which statuses are a success.** Any status from 200 to 299 is one, and its body is read as a usage document; every other status is a failed refresh whose body is never read. The messages of a status and of a time-out
  always say `Anthropic`, whatever the base URL; only `Cannot reach` names the host. `<detail>` in that message is the system's own text for what went wrong, or the kind of the fault when it has none.
- **`Next try in`.** The figure is the wait in force, in whole seconds rounded up and never less than `1 s`. Under a minute it is `N s`; from 60 seconds on it is whole minutes, rounded up: 60 seconds is `1 min` and
  61 seconds `2 min`.
- **`Retry-After`, exactly.** It is believed only as a whole number of seconds above zero, of at most six digits: `0`, a negative number, a decimal, a date and any other text are taken as no header. Only the first value of the
  header is looked at.
- **The messages of a response that cannot be used**, each of which begins `The usage response`: `The usage response is not valid JSON: <what the JSON reader says is wrong>`; `The usage response is not a JSON object.`, which is
  also what a body with nothing in it, or with blanks alone, draws, and `null`, a number, a text and a list; `The usage response enables spend but reports neither "used" nor "limit"; its format may have changed.`; and the one
  about neither a `spend` object nor any window, above. (`The usage response was empty.` is the message for a response that has no body at all, which an HTTP answer never is.) What the JSON reader says can name the first
  thing it could not read, a word of the body, so that message is the one place where a few characters of a response can reach the window and the log; it is masked like everything else.
- **A response is never guessed at.** One that cannot be understood is a failed refresh that says so; it is never shown as an empty reading, as zero, or as the figures of the reading before. The endpoint is not a published
  one, and if its format changes the window is to say what is wrong, not to show wrong numbers.
- **The spend, more exactly.** A `spend` with no `enabled` at all is not enabled. An amount is an object with a numeric `amount_minor`; anything else is an amount that is missing. One of the two is enough: a `used` with no
  `limit`, or the other way round, is a reading with one amount. An `amount_minor` with a fraction is cut to a whole number before it is scaled, and the scaling is exact (1 with an exponent of 2 is 0.01); an exponent that is
  missing or not a number is 0. A `percent` with a fraction is cut to a whole number (19.9 is 19), and one that is not a number is missing, not a mistake. The severity and the currency are taken as text whatever was sent, a
  number as its digits; `null` and a missing key are none. The currency of `limit` is used also when that of `used` is `null`.
- **What a plan window is, more exactly.** It is looked for only to tell the two formats apart. A window whose `utilization` is exactly 0 is a window. A top-level member that is `null`, a list, a plain value, or an object without a numeric `utilization` is passed over without a word,
  and so is every key the program does not know, anywhere. Whether it has a `resets_at` makes no difference. `extra_usage` is left out because it says again what `spend` says. What is found
  this way decides whether the response is refused: one plan window is enough to make it the seat-based format, a window at exactly 0 and a window with no reset time included, with an enabled `spend` beside it or with no `spend` key at all
  (see Source data). The check comes after the checks of a response that cannot be used, so a body that is not a usage document at all draws its own message and not this one.
- **While a refresh runs, what the last one left stays**: the message, its time and the sign of a 429 are there until the running request has ended, and only a success takes them away; a new failure replaces the message and
  its time. The time of a failure, in the message line and in the error log, is when the failed request ended, not when it was started.
- **Old figures, exactly.** The figures are old, and dimmed, when there is a reading, the latest refresh failed, and the failure was not a 429. A failure with no reading yet is not that, and a reading taken from the history
  at startup is not old until a refresh fails.
- **An unexpected fault does not stop the refreshing.** It is the failed refresh `Unexpected error (<kind>); see the log.`, `<kind>` being the name of the kind of fault; what the fault itself says goes to the log, with its
  stack trace, and never to the window. The next refresh comes at its usual time.

**Getting the token**

- Each time a token is needed the program runs `claude -p ping` with its base URL pointed at a small server of its own on the loopback interface, on a port the operating system chooses so that no port has to be free for the program to work, takes the credential `claude` sends, and stops `claude` and what it started; nothing is sent to
  Anthropic by it. It waits 30 seconds at most. After a failure no token is kept, so while the person is logged out a new run of `claude` starts with every scheduled refresh.
- The messages, as the row shows them: `Claude Code did not send a credential within 30 seconds. Run `claude` in a terminal to check that it works and is logged in, then retry.`, `Claude Code exited without sending a credential, so it is probably
  not logged in. Run `claude` in a terminal and log in with /login, then retry.`, `Claude Code could not be found on the PATH. Install it, then retry. If it is installed somewhere the PATH does not cover, add that directory and restart this
  application, which keeps the PATH it was started with.`, and `Claude Code was found but could not be started: <reason>. Check that it is installed correctly and can be run, then retry.`
- The credential variables count only if they are not empty; the message names the one or both that are set, and the check is made on every acquisition, from the environment the program started with.
- After a failed attempt the first 2,000 characters of what `claude` printed are written to the log at the warning level as `claude output: ...`, masked like everything the log gets.
- **How `claude` is run.** It is started as `claude -p ping` with the environment the program has, plus `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>/`, in the program's own working directory. What it prints on its error
  output is taken together with its ordinary output, and its input is closed at once, so that it cannot sit waiting to be typed to.
- **What the capture server answers.** It answers every request itself, whatever the path and the method, with HTTP 200, and passes nothing on; it does not look at the `Host` header. A `HEAD` gets no body. A request whose
  body asks for a stream (`"stream":true`, spaces aside) gets `Content-Type: text/event-stream` and the shortest event stream that is a complete answer, the one word `pong`: `message_start` (message `msg_mock`, model
  `claude-opus-4-5`, one input token), `content_block_start`, a `content_block_delta` with `pong`, `content_block_stop`, `message_delta` (`end_turn`, one output token) and `message_stop`. Any other request gets
  `Content-Type: application/json` and `{"type":"message","role":"assistant","content":[{"type":"text","text":"pong"}]}`. `claude` is therefore given a real answer and ends by itself if it is left to.
- **Which header is the credential.** `x-api-key` first, if it is there and not blank, taken as it stands; otherwise `Authorization`, if it begins with `Bearer ` (in any case), the rest of it trimmed and not empty. Header
  names are read without regard to case. An empty bearer, or any other kind of authorization, is no credential. The first request that carries one settles it and later ones are not looked at; a request without one is
  answered like any other and the wait goes on, so a request `claude` makes before the real one does not end it.
- **`claude` ending before a credential has come is "not logged in"**, whatever its exit code, zero included, and whether or not it made a request.
- **Clearing up, in every outcome**, success, time-out, exit and interruption alike: every process `claude` started is killed, then `claude` itself, and the program waits up to 2 seconds for it to be gone; then the capture
  server is stopped at once, so nothing listens on that port afterwards. An acquisition can therefore take a little over the 30 seconds: the 30 for the credential, up to 2 for `claude` to be gone and, after an exit, up to 2
  for its output to end.
- Two more messages, besides those above: `Cannot start the local server used to read the Claude Code token: <detail>` when the loopback server cannot be started, and `Interrupted while reading the Claude Code token.`
- **The message for a credential in the environment**, word for word: `ANTHROPIC_API_KEY is set, so Claude Code would send that credential instead of its OAuth login token. Unset it and restart this application.`, and with
  both, `ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN are set, so Claude Code would send that credential instead of its OAuth login token. Unset them and restart this application.`, the API key always named first. A variable
  that holds blanks alone counts as not set. The check comes before anything is started: no server is opened and `claude` is not run.
- **"Not found" and "could not be started" are told apart only after the system has refused to start it.** It is not found when the command is a bare name and no directory of the `PATH` holds an ordinary file of exactly that
  name that may be run; a `PATH` that is not set at all counts as not found. In every other case it was found and could not be started, and `<reason>` is the operating system's own message.
- **The output of `claude` is logged only after a time-out or an exit without a credential**, not when it could not be started and never after a success. The line is `claude output:` with the output beginning on the next
  line. After a time-out it is what has arrived by then; nothing is logged if it is blank. The output is read as UTF-8, and all of it is read however much there is, so that `claude` is never held up by nobody reading.
- Each acquisition writes three lines to the log, none of which carries the token: `Listening on 127.0.0.1:<port> to capture the token`, `Started claude -p ping`, and, when it succeeded, `Token captured`.

**The log**

- **What counts as credential-shaped**, and is therefore masked in every line and every stack trace before it is written: a key that begins `sk-ant-`, a `Bearer` followed by a value, and the words `x-api-key`, `authorization`, `api_key`, `access_token`, `refresh_token`, `password` and `secret` followed by a colon or an equals sign and a value. The masking is the last defence behind not logging these things at all, so a line that was never meant to carry one is masked just the same.
- A line is `yyyy-MM-dd HH:mm:ss LEVEL [Name] message`, with the level padded to seven characters and any stack trace after it; the console gets the same lines. If the log file cannot be opened the program goes on with the
  console only and says `Cannot write log file <file>; logging to console only`.
- The line for each request to Anthropic is `GET api.anthropic.com/api/oauth/usage -> HTTP 200 in 412 ms (1234 bytes, request-id <id>)`, with `retry-after <n>` when the server sent one; the request id is written only if it is made of letters, digits,
  `_` and `-`. A timeout or a failure is `timed out` or `failed` with the time and no size. The host and path are those of the URL actually fetched, so a run against another base URL names that one; the line is the place
  the log shows where a reading came from.
- With the setting to log the response on, the line is `Response of GET <host><path> (HTTP <status>):` and the pretty printed JSON on the lines after it, for every status; a body that is not JSON is `(not JSON, N bytes, not logged)`, N being its size in bytes as UTF-8, and an empty one is written as `null`, which is how the JSON reader takes a body with nothing in it. A body that has text after its first JSON value is logged as that value alone, although the refresh then fails for the text. The entry is at the information level, comes
  after the request's own line and before the status is judged, so it is written for a failed status as for a good one; when there was no answer at all there is nothing to write. The setting is asked for each response, so switching it takes effect with the next one, without a restart.
- **The window's status polls are not logged.** They arrive every second, so a line for each would bury everything the log is kept for. The same holds for the window's reads of the log, the history and the error log panels, and for the page and its files: nothing the window asks for is logged, unless it goes wrong. What is logged is what the application does of its own accord, and what a person asked for: a manual refresh is logged, since there is one of those and not sixty a minute, as `Manual refresh requested`, and only when it started a request; a press that starts nothing writes nothing.
- **What a request of the window writes when it goes wrong**, each at the warning level: `Could not read the log: <reason>` and `Could not read the usage history: <reason>` when a panel's file cannot be read, once for each request that failed, the reason going to the log only and never into the answer; `Could not read the usage history for the change: <reason>` when the file cannot be read for the status, at every poll for as long as it lasts, since a read that failed is tried again; `No such frontend resource: /web/<path>` for a file of the page that does not exist; and `API request failed: <path>` with the stack trace for a fault of the program inside a request.
- A change of settings is logged with all the values, a change of the interval also as `Usage fetch interval is now N s`, and a new remembered height as `The history window height is now N px`. Applying settings that change nothing writes and logs nothing.
- Two programs started at the same time in one directory write to the same history and settings files; running several at once is not something the program is built for (what happens to the log file then is up to the logging system, and has not been checked).
- **What is written, and where.** Lines of the information level and above are written, and nothing finer. The console is the standard error output, not the standard output. The file is UTF-8. The levels are written as
  `INFO`, `WARNING` and `SEVERE`; what this document calls logged "as an error" is a `SEVERE` line. `[Name]` is the last part of the name of the part of the program that wrote the line, for example `[UsageApp]` or
  `[ApiHandler]`. The time is cut to the second, not rounded. A stack trace begins on the line after its message and is masked with it.
- **`Logging to <file>`** gives the full path. When the file cannot be opened that line is not written, and `Cannot write log file <file>; logging to console only` is a warning with the stack trace of what went wrong. If
  something had started the logging before the program set it up, a warning says so: `Logging was already in use, so lines logged while the process is being stopped may be lost`.
- **The masking, exactly.** What is masked is replaced by `[redacted]`. A key beginning `sk-ant-` is masked to the end of its run of letters, digits, `_` and `-`. `Bearer <value>` becomes `Bearer [redacted]`, in any case, up
  to the next blank. The named words are found in any case, also written `api-key`, `apikey`, `access-token`, `accesstoken`, `refresh-token` and `refreshtoken`, and with quotation marks round the name and the value; the value
  ends at a blank, a quotation mark, a comma or a `}`, and the name and its separator stay, so `{"access_token":"abc"}` becomes `{"access_token":"[redacted]"}` and `Authorization: Bearer abc` becomes
  `Authorization: [redacted]`. Every occurrence is masked, on every line of an entry.
- **The request line, exactly.** It is at the information level. The size is that of the body in bytes as UTF-8. The request id is the `request-id` header, written only if it is 1 to 100 characters of letters, digits, `_`
  and `-`. Inside the brackets the order is the size, the request id, the `retry-after`. A request cut short by the program stopping writes no request line at all.
- **The lines of a refresh.** Every refresh that succeeded writes `Usage refresh succeeded`. A rejected token writes `The token was rejected (HTTP 401); acquiring a fresh one`. An HTTP 429 writes two warnings,
  `Rate limited; next try in <N s or N min>` and then the `Usage refresh failed: ...` line. A success while a back-off is still on writes `Easing the back-off; next try in <N s or N min>`, and the success that ends it
  `The back-off is over; back to the usual interval`. An unexpected fault writes the warning `Usage refresh failed unexpectedly` with its stack trace, and then the `Usage refresh failed: Unexpected error ...` line.
- **The lines of the settings.** At startup, when the settings have been read: `Usage interval N s, poll interval M s`, with the values in force for the run. `There was no settings file; created <path> with the defaults`
  when one was made, and the warning `Could not create the settings file <path>: <reason>` when it could not be, which stops nothing: the defaults are used and the run goes on. The warnings for a file or a value that is
  not right: `Ignoring unreadable settings file <path>: <reason>`; `Ignoring settings file <path>: not a JSON object`, which is also what an empty file and a list draw; `Ignoring usageIntervalSeconds in <path>: The usage
  interval must be a whole number of seconds from 5 to 3600.`; `Ignoring <key> in <path>: it must be true or false`; `Ignoring timeFormat in <path>: it must be "hh:mm" or "hh:mm:ss"`; and `Ignoring <key> in <path>: it must be
  a whole number of pixels from 0 to 10000`. A change of the settings is one line, `Settings changed: ` and every setting with its value, by its key; in that line the time format is written `HOURS_MINUTES` or
  `HOURS_MINUTES_SECONDS`. Settings that cannot be saved write the warning `Could not save settings to <path>` with the stack trace. The height of the log has its own line, `The log window height is now N px`, and a height
  that cannot be stored the warning `Could not save the height of the <history or log> to <path>: <reason>`.
- **The lines of the files and the window.** `Showing the newest reading in the usage history until the first refresh is done`, when a reading was taken from the history at startup. `Could not add the reading to <file>:
  <reason>`, a warning, for a history row that cannot be written. `Settings are stored in <path>`, the line that gives the path of the settings file. `Frontend served at http://127.0.0.1:<port>/`, and `Frontend loaded` when
  the page has loaded. Each time the program gives the window a new size: `Window fitted to <w>x<h> (window <W>x<H>)`, the size of the content and then of the window with its title bar, with ` (resizable: height)` or
  ` (resizable: both)` between them when it may be dragged. If the page cannot be asked for its size, the warning `Could not ask the page for its size: <reason>`, once in a run and not at every try.
- **The order of the lines at startup**: `Logging to`, the start line, the history path, the settings path, the lines about the fake backend, the base URL and the token (see Non-functional requirements), the lines of the
  settings, the line about the reading taken from the history, `Frontend served at`, and then the first refresh. One line is out of that order in a run with `--fake-backend`: the fake backend is started first, so its own
  `Fake backend listening on http://127.0.0.1:<port>, answering <scenario>` comes straight after the start line, before the history path.

**The local server**

- The `Host` header must be `127.0.0.1:<port>` or `localhost:<port>` (case does not matter), else the answer is 403 `Forbidden`; this holds for the page as well as the API.
- A `POST` must have a `Content-Type` that starts with `application/json`, else 415 `Send Content-Type: application/json.`; a body over 4,096 bytes is 413 `The request body is too large.`; a body that is not JSON is 400 `The request body is not valid JSON.`; one that is not an object is
  400 `The request body must be a JSON object.` A wrong method is 405 with an `Allow` header, an unknown path 404 `{"error":"No such endpoint."}`, a fault of the program 500 `Internal error; see the log.`
- Every API answer is JSON, and the answers carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. No `Access-Control` header is ever sent, and under `/api/` an `OPTIONS` is refused like any other wrong method, so another page cannot post to it. (The page's own files are answered whatever the method, `OPTIONS` included, with the file and still no `Access-Control` header.)
- The page and its files are served from inside the program only, never from the file system, and a path with `..` is 404. `/api/log` and `/api/history` name their file by its bare file name, never its path.
- The settings are strict: every one of them must be in a `POST /api/settings`, else 400 `The setting X is missing.`; a flag must be true or false (`The setting X must be true or false.`); the time format `hh:mm` or `hh:mm:ss`; and the interval a whole number
  within 5 to 3600 (`The usage interval must be a whole number of seconds from 5 to 3600.`). A file that cannot be written is 500 `The settings could not be saved: <reason>`, and nothing changes.
- **The settings are checked in a fixed order and the first fault is the one reported**: the interval first, then the switches in the order of the settings table, with the time format between `showDeltaTime` and
  `historyDeltaUsed`. An interval that is not a whole JSON number (`30.5`, `"30"`, `true`, a number too large) draws the same message as one out of range. A `null` counts as missing, for every key. The message for the time
  format is `The setting timeFormat must be "hh:mm" or "hh:mm:ss".`, also when it is not a text. A key that is not a setting is ignored. An interval out of range refuses the whole set before anything is written, so no other
  value of that request is kept. A set that was applied is answered with the settings as they now are, in the shape `GET /api/settings` has, which is what the view shows next.
- **The order of the checks on a request**: the path (404), then the method (405), then the `Content-Type` (415), then the size (413), then whether it is JSON (400), then the settings themselves. So a `POST` of the wrong type
  to a path that does not exist is 404. The 405 says which method is right: `{"error":"Use GET."}` with `Allow: GET` for the config, the status, the log, the errors and the history; `{"error":"Use POST."}` with
  `Allow: POST` for the refresh; `{"error":"Use GET or POST."}` with `Allow: GET, POST` for the settings.
- **The `Content-Type` and the body, exactly.** The type is read without regard to case and by its beginning, so `application/json; charset=utf-8` passes; a request with no `Content-Type` is 415. The limit is 4,096 bytes
  exactly: 4,096 is taken and 4,097 is 413. A body with nothing in it is 400. `POST /api/refresh` does not read its body: with the right type, any body or none will do.
- **The `Host` check, exactly.** The 403 is plain text, `Forbidden`, not JSON. It is also the answer when there is no `Host` header, when the port is missing or another, and for every other way of naming this machine,
  `[::1]:<port>` and its host name among them.
- **Which requests are the API's.** Only a path that begins `/api/` is; `/api` without the slash is taken as a file of the page and is the plain 404 below. A query on the path is not looked at.
- **The files of the page.** They are `index.html`, `app.css`, `app.js` and `view.js`, and `/` is `index.html`. The type follows the ending of the name, in any case: `text/html; charset=utf-8`, `text/css; charset=utf-8`,
  `text/javascript; charset=utf-8`, `application/json; charset=utf-8` for `json`, `image/svg+xml` for `svg`, and `application/octet-stream` for anything else. They carry `Cache-Control: no-store` and
  `X-Content-Type-Options: nosniff` like the API's answers, which are `application/json; charset=utf-8`. A file that is not there is 404 with the plain text `Not found`, and so is a path with `..`, written plainly or as
  `%2e%2e`. The method is not looked at for these: a file is given whatever the method.
- **A file that is not a file is no file.** A log or a history that is there but is not an ordinary file, a directory for example, is answered as if there were none: no lines, no stamp, no change, and no error.
- **The local server is not locked.** It guards against other web pages, by the checks above, and not against other programs on the same machine: any of them can read the status and the history, change the settings and ask
  for a refresh, as on any loopback port, and there is no password and no token for it. It is therefore not for a machine shared with people who are not trusted. What it can never give away is a credential, since no answer
  holds one.

**Starting, stopping and the files**

- If the program cannot start (for example the port cannot be bound) it logs `Could not start` as an error and ends without a message in a window; if the page cannot be loaded the window is empty and `Frontend failed to load` is logged.
- When the window is closed, or the process is told to stop, the program logs `Shutting down`, `Usage refresh stopped` and `Frontend server stopped`, interrupts a request that is running, waits up to 5 seconds for it, and then ends; the interrupted request
  adds no error, no log line of failure and no row to the history. A request cut short is not a failure.
- **The shutdown happens once, whichever way it is reached.** Closing the window and stopping the process are two ways to the same cleanup, and they can happen together — closing a window is one way of ending a process — so the lines above are written once and each resource is released once, with the second caller waiting for the first rather than doing it again. A fake backend of that run (see Fake backend) is stopped on the same path, so a run that ends either way leaves no listening socket behind.
- The history on the first run is shown from the newest row that has amounts: the percentage is worked out from `used` and `limit` when the budget is above zero, and there is no severity, so the figures have no severity colour until the first refresh.
- A line of the history file is a row if it has 3 to 7 fields (older files had fewer) and a date and time; the missing fields are empty. The change in time of a row is empty if the row is earlier than the one before it (the clock was set back) or a
  time cannot be read; a change in the amount of less than half a cent counts as zero in the row and the history cell.
- The newest lines read of the log are those of its last 512 kilobytes (524,288 bytes), at most 1,000. Whenever the file is larger than that, the first line of what was read is dropped, whether or not the cut fell inside it, so that none is ever shown half, and the note says how many lines are shown. A line ends at a line feed, a carriage return, or both; an empty file is a log with no lines.
- **What a row of the history file is, exactly.** A line is cut at every comma, there being no quoting. It is a row if it has 3 to 7 fields and its first field is not blank and is not the word `datetime`; a line that begins `datetime` is a header wherever it stands. The first field is not checked to be a date and time: a row whose first field is something else is shown with that text as it is, and takes its place in the order as text. Blank lines, and lines with fewer than 3 or more than 7 fields, are left out. The count of lines in the note above the table is the count of rows, not of the lines of the file.
- **How a row's status is read.** A row is that of a failed query if its status has `failed` in it, and begins a run if its status begins `start`; any other text there is an ordinary row. The first row of a file begins a run even when nothing marks it, so the rows of an old file with no status form one run from the first; such a first row is not a startup line, is not green and is not hidden with the zero usage lines.
- **The order of the history is that of the text.** The lines are put in order by the `datetime` as it is written, compared as text, which for the form the program writes is the order of time.
- **The first cell of a history line.** The time of day is what follows the date in the `datetime` as written; a `datetime` that has not the usual form is shown as it stands, with or without the date setting. The amounts are the file's text as written, `1000.00`, without the digit grouping the row has.
- **The line above the table, exactly.** `Showing <shown> of <all> lines: `, then `<n> zero usage` and `<n> failed`, joined by ` and ` when both are there, followed by ` hidden`; then, after a comma when something came before, `<n> older not shown`; then a full stop. A part whose count is 0 is left out, so with only older lines left out it reads `Showing 1,000 of 1,500 lines: 500 older not shown.` The counts have a comma for thousands whatever the machine's language.
- **The reading shown at startup, exactly.** It is the newest by its `datetime`, not the last line of the file, among the rows that have a `used` or a `limit`; either is enough. If that one row cannot be understood, an amount that is not a number or a time that is not `yyyy-MM-dd HH:mm:ss`, nothing is shown in advance: older rows are not tried. A file that cannot be read shows nothing and is no error. The time is the file's local time taken in the zone the machine has now; the percentage is the amount used as a share of the budget, to the nearest whole number, and there is none when an amount is missing or the budget is not above zero.
- **`interval` and `duration_ms` of a row.** The interval is the one in force at the moment the query starts. The duration is cut to whole milliseconds, not rounded.
- **`--help`, and a mistake on the command line, leave no trace**: no log file, no settings file, no window.
- **A start that fails.** `Could not start` carries the stack trace of what went wrong, and `Frontend failed to load` the error of the load. `Shutting down` is not written for a start that failed.
- **The order of stopping**: the refreshing, then the local server, then a fake backend, and the log last, so that the lines of the stopping are in it. The local server stops at once, without waiting for a request that is being answered. If the refreshing has not ended after the 5 seconds, the warning `The usage refresh thread did not stop in time` is written and `Usage refresh stopped` after it all the same. A cleanup that fails is not tried a second time.
- **While the program runs there is a lock file beside the log**, `java-aip-usage.log.lck`, which the logging system keeps; it is gone after a stop that the program saw, and is kept out of version control like the log.
- **How the process ends.** With exit code 0 when the window is closed, 143 after `SIGTERM` and 130 after `SIGINT`. Where the operating system ends a process without letting it clean up, as Windows does when a process is terminated from outside, there are no lines of the stopping, as after a forced kill.

**The window**

- The program never gives the window a size smaller than 160 by 32 or larger than 2,400 by 1,600 pixels of content, and until the page has told its size it is 420 by 50. Those limits are on what the program sets, not on what a person drags: while a panel that can be
  resized is open, the window may be dragged larger than that, as far as the desktop lets it. A remembered height is stored once the person has not changed it for half a second, as it is, with no upper limit of its own, and is
  kept only if it is more than 0; when a panel is opened at a remembered height, 1,600 is the most that is used for that opening, and the stored value stays what it was.
- The window title is `aip usage v0.26`; the program is called `java-aip-usage v0.26` in the log.
- **The program asks the page for its size about every 150 milliseconds** and gives the window a new size when the answer is a new one, so the window follows its content within a moment. The page measures its own content, never the window, so that giving the window that size does not change the answer; the same size is not set twice. The page says nothing but its size, what may be dragged and which panel is open, and the program tells the page nothing.
- **What may be dragged.** With no panel, and with the settings, nothing. With the history, the height only, the width staying what the page asked for. With the log and the error log, the height and the width. Neither can be dragged below the size the window has with no panel open, which is the size the page last asked for with none; until there has been one, the size the panel opened at is the least.
- **An answer of the page that is not a size is passed over**: one that is not text, one with a width or a height of 0 or of more than five digits, a panel name other than `history`, `log` and `errors`. A change of what may be dragged, or of the panel, with the same numbers, counts as a new size.
- **The room the title bar takes is measured once**, when the window is first given a size, and used from then on. Until the window has been laid out no size is set, and the next time of asking tries again.
- **A dragged height, exactly.** Only the height is watched, and only while the history or the log is shown. The half second starts again with every change by the person and is dropped whenever the program itself sets a size. A height that has settled is stored only if it is not the one stored already. A stored height equal to the one the program works out is kept and not written again.

**The look of the page**

- **Colours** follow the operating system's light or dark setting, and there is no setting for it. `muted` is `#8a8f98` in both; severity normal is `#1a7f37` and `#3fb950`, warning `#a86a00` and `#d29922`, critical and the red of errors `#cf222e` and `#f85149`, the open
  panel's button `#00b341` and `#3ddc6b`, the blue of notes `#0969da` and `#58a6ff`. Background and text are the system's. A severity the program does not know is shown in the muted gray.
- **Severity words:** `normal`; `warning` or `warn`; `critical`, `exceeded` or `error`; any other word is unknown.
- The refresh icon turns round and round while a refresh is running. When a refresh has failed and the figures are old they are dimmed to 60 percent: the time, the amounts and the percentage, not the countdown, the other items or the buttons.
- The icons are: refresh, an arrow round a circle; log, a sheet with lines; history, a table; error log, a triangle with an exclamation mark; settings, a gear. An icon button has a border that is invisible and turns muted gray when the pointer is over it or it has the keyboard focus.
- A time that the backend gave no tooltip for has the tooltip `Last update`.
- Under the row, in this order, there can be the message line, the line with the message of an HTTP 429 that shows while the pointer is over the countdown, and the red line `Lost contact with the application. Still trying.`. The last one is the window's own: it shows when a request to the
  program fails, including the one for the refresh button, keeps showing the last figures, and goes with the next answer. Before the program has answered at all the window asks again every 2 seconds, the placeholder says `Loading…`, and the buttons do nothing until the first answer; every request to the program
  is given up after 10 seconds.
- **The settings view:** the buttons are Apply, Restore defaults (tooltip `Fill in the defaults; Apply makes them take effect`) and Cancel (`Close without changing anything`), in that order; Apply and Restore defaults are disabled when the settings could not be read; Maximum view (`Show everything in the main view`) and Minimum view (`Show as little
  as possible in the main view`) are inside the Main view group. The time format's two choices read `hh:mm` and `hh:mm:ss`. The interval field is 5 characters wide, right-aligned, with `s` after it. When Apply is pressed the window checks that the field is a whole number
  (`The interval must be a whole number of seconds.`) and sends nothing otherwise; whether it is within 5 to 3600 is for the backend. Nothing else is bound to the keyboard: there is no Enter to apply and no Escape to cancel; the panels' lines can be reached with Tab, and the buttons say
  whether their panel is open to a screen reader.
- **The panels:** while a panel is on show it is read again when its content changes. The log and the error log are read at every poll of the status that was answered, so every second unless `--poll-interval` says otherwise, and not at all while contact is lost; the history whenever a row has been added to its file, a row of a failed refresh as well as a reading (the status carries a stamp of the file, `historyStamp`, which changes when a row is added; it is empty when there is no file). If nothing has changed the lines are left alone; when new lines come in above, what the person
  is reading does not move, and a panel scrolled to the top stays at the top. If a read fails the old lines stay and a red line says `The usage history could not be read: <reason>` (`The log could not be read: ...`, `The error log could not be read: ...`). A panel with nothing to list shows only its note. The notes of the log are `There is no log file yet.`, `The log is empty.`
  and `Showing the newest N lines of the log.`; those of the history are written by the program, as above.
- Text the program sends is put on the page as text, never as HTML, the tooltips included.
- **Type and page.** The row is 15 pixels, weight 700, with a line height of 1.3, in the font `-apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, Arial, sans-serif`; the message lines are 14. Every figure that changes has digits of equal width, so that a changing
  digit does not move what is beside it. The page itself never scrolls; only the lines of a panel do. Checkboxes, the dropdown and the scrollbars follow the system's light or dark setting like the rest. The page is in English.
- **The spacing of the row.** The row and its message lines have 5 pixels above and below and 8 at the sides, so a 24 pixel button gives a row of 34. The items are 10 pixels apart, centred on each other, and none is broken in two; a row that wraps has 2 pixels between its lines. The 900 pixels of the wrap
  include the 8 at each side. The percentage and the amounts are 8 pixels apart; the ` / ` between the amounts is the page's. The percentage is never smaller or grayer than the amounts. The refresh button is drawn 7 pixels nearer the amounts than the gap, about 8 pixels from the number, and the countdown 8
  pixels nearer the button, 2 pixels from it.
- **The buttons.** The refresh button is 24 by 24 pixels with an icon of 14, a line of 1.7, in the colour of the text, and it is never green. The log, history and error log icons are 12 pixels with a line of 1.6; the gear is 13, drawn on a finer grid with a line of 2, which becomes 3.3 when its panel is open
  (the 2.2 of the others at its scale). The button of the open panel takes back the 4 pixels it grew by on every side, so that the row is neither wider nor taller and nothing beside it moves. An icon button has corners rounded by 4 pixels and no focus ring of the browser's; the border that shows is its sign of
  focus. The refresh icon turns once a second, at an even speed.
- **The room of the items that change.** The countdown has room for four characters, `-3 s` to `99 s`; the interval for `120 s`; the change in the amount for `+12.34`; the time since the previous reading for `3600 s`. A longer value, such as a countdown of `120 s` during a back-off, makes the row wider for
  as long as it shows. The time, the amounts and the percentage have no room kept for them: their digits are of equal width, so a change of a digit moves nothing, and a figure that gains a digit, `99.99` becoming `100.00`, makes the row that much wider. An item whose switch is on and which has no
  value keeps that least width, not the width of the value it lacks. The percentage is the exception among the optional items: with its switch on and no percentage sent, it is not there and takes no room. A countdown the backend did not send is likewise not there.
- **Colours not said elsewhere.** The placeholder (`Loading…`, `No data`, `No usage reported`) is muted gray. The line of a 429 that shows over the countdown is weight 800, heavier than the 700 of the other message lines. A message line is 3
  pixels below the row and breaks anywhere, inside a long word if it must. With no severity sent, the figures are in the colour of the text.
- **Before the first answer** the row shows `Loading…`, the refresh button and all four panel buttons, and no time, no figures, no countdown and no optional item. A status that says nothing of a button leaves it shown, so the three panel buttons are there until the first status that switches one off says so.
- **What the page asks the application.** At startup `GET /api/config`, once, then the status at once, and never a refresh of its own. The polls never overlap: the next one is asked for the poll interval after the one before was answered, failed or given up, so the time between two is the interval and the time
  the answer took. Pressing the refresh button sends `POST /api/refresh` with `Content-Type: application/json` and the body `{}`, does not look at what comes back, and asks for the status at once, whether or not the press got through; so does a successful Apply. A status asked for at once while a poll is under
  way is not sent, and the change shows with the next poll. Every request says not to use a stored answer. Of the status the page uses what is finished for it, whether the figures are old, whether a refresh is running, and the stamp of the history; it reads none of the raw figures.
- **Lost contact, exactly.** The line shows for three failures only: the first request at startup, a poll of the status, and the press of the refresh button. A panel, the settings or an Apply that fails says so in its own red line and not here. A failure is no answer within the 10 seconds, no connection, or an
  answer whose status is not a success, so an application that answers a poll with an error is "lost contact" too. The line goes with the next status that is answered. While it shows, an open panel is not read again.
- **What the application's refusal reads as.** The reason a red line gives is the `error` text of the answer when it has one, else `The application answered HTTP <status>.` With the answers the panels' files give, the line reads `The usage history could not be read: The usage history could not be read.`, and the
  same for the log.
- **The settings view, exactly.** The groups and their order: `Request`, with `Interval`, the field and `s`; `Main view`, with `Percentage spent`, `Currency symbol`, `History icon`, `Log icon`, `Error log icon`, `Interval`, `Change in the amount used`, `Time since the previous reading`, then `Time format` with its
  dropdown, then `Maximum view` and `Minimum view`; `History view`, with `Date`, `Zero usage lines`, `Failed lines`, `Δ used`, `Δ time`; and `Log`, with `Log the response`. The text is bold, 12 pixels on a line of 1.45, in the system's own interface font, not condensed. Each group is a box with a thin gray border
  and its heading set in the border; each setting has a line of its own, the control 4 pixels from its text. The buttons are 6 pixels apart, with a gray border that takes the colour of the text under the pointer or with the focus; a disabled one is at half strength. `Apply` has no tooltip. The dropdown is at
  least 12 characters wide. The red line of the view is at its top, above the groups; it stays until the view is opened again, whatever is typed or pressed meanwhile. When the settings cannot be read it says `The settings could not be read: <reason>` and the groups are not shown at all, not shown empty; Cancel
  still works. A refusal of the backend is shown in its own words. The check of the interval field is: blanks at its ends aside, digits and nothing else, so ` 60 ` and `0060` are taken, the latter as 60, and `-5`, `5.0`, `1e3`, `60s` and an empty field are not. Apply sends all sixteen settings in one request.
  Restore defaults fills in the defaults the backend sent with the settings; the page has none of its own. A setting the backend's answer lacks is shown switched off.
- **The panels, exactly.** The text is 12 pixels on a line of 15, in a fixed-width font (`ui-monospace, SFMono-Regular, Menlo, monospace`), regular weight, the letters drawn very slightly closer. From the top: the note, the red line of a read that failed, the lines. Opening a panel, or changing to another,
  clears the lines, the note and the red line of the one before at once, so nothing of another panel is ever under the new button, and an answer that comes for a panel that is no longer the one shown is dropped. A panel with no lines shows no box of lines, only its note. The red line goes with the next read that
  succeeds, even one that brings nothing new. The log and the error log are read again at every poll, and the history when its stamp is not the one it was last read and shown at; a read that failed is tried again at the next poll, for the history as for the others, whether or not a row was added meanwhile. Only one panel is read at a time: a panel opened while another is still being read is read as soon as that read has ended, without waiting for a poll or a new row. The header of a table stays at the
  top with the page's background behind it; the log has none. The 16 pixels kept clear at the right are kept in the log and the error log as in the history. How far the lines are moved to keep a person's place is worked out from the average height of a line.
- **The history, exactly.** The columns are, in characters: the time at least 8, or 19 with the date, and taking the room that is left; `used` 10; `limit` 10; `Δ used` 9; `Δ time` 8; `Cur.` 5; with 8 pixels between them. With one of the two change columns on it is 9 wide, whichever it is. The room between
  `used` and `limit` is what an amount shorter than its column leaves, not a gap of its own. The width of the first column follows what the backend says of the date, and the red of `failed` is put on the second cell of a line the backend marks as failed: the page looks for no word. The header is shown only when
  there is a line. The table is measured on a copy that is not seen, holding the header and the three lines with the most characters, each time the program asks for the size; the width last measured is kept while the panel is closed. The width asked for is the larger of the row's and the table's with 16 pixels
  for the panel's sides, and the scrollbar's width.
- **The log, exactly.** A line begins an entry when it begins with a date, a time and a blank, `yyyy-MM-dd HH:mm:ss `; every other line belongs to the entry before it. Lines at the beginning of what was read that come before the first such line, the end of an entry the 512 kilobytes cut, are kept together and
  shown at the very bottom. The start of a run is a line that has `] Starting java-aip-usage` followed by a blank or by the end of the line. The number in `Showing the newest N lines of the log.` is the number of lines received.
- **The error log, exactly.** The time is 8 characters wide and the message as wide as its text, 8 pixels apart, both at the left; its header, `time` and `message`, stays at the top and is bold. `There are no errors in this run.` is the page's own text.
- **The sizes the page asks for, exactly.** The width and the height of the row are those of the row with its message lines and the room round them, each rounded up to a whole pixel. The height of a panel is ten times that height as it is at the moment the panel is shown, a message line that shows then
  included, and it is taken again when one panel replaces another and when the settings give one back. The log's width is three times the row's rounded width, and the scrollbar's; the error log's is one and a half times it, rounded up, and the scrollbar's. The settings are as tall as the whole page and as wide
  as the row. The scrollbar is measured once, at startup; where scrollbars lie over the content it is 0. The line of a 429 over the countdown is part of the row's height, so the window grows for it with no panel and with the settings, and not with the log, the history or the error log.
- **For a screen reader.** The message line and the line of a 429 are announced as a status, the lost-contact line and the red lines of a panel and of the settings as an alert. Each icon button is named by its tooltip, `Show…` or `Hide…`, and says whether its panel is open; the icons themselves are passed over.
  The box of a panel's lines can be reached with the keyboard and is named `Newest first`. The interval field is named `Interval in seconds, from 5 to 3600`, and the dropdown `Time format`.
- **What the page does not have.** No currency sign of its own, no window of its own that it opens, no setting it keeps, and nothing that it loads from anywhere but the application.

## Fake backend

The application carries a fake Anthropic backend of its own, so that it can be run and
watched without a paid Anthropic account, without Claude Code installed or logged in, and
without spending real requests on the usage endpoint. One option turns it on; nothing else
is needed and nothing else has to be started.

- `--fake-backend` (see Command line) makes the application start a fake usage server inside
  its own process and fetch from it for the rest of the run. There is no second program and no
  second command: the fake backend is part of the application and is reached only through this
  option.
- It is a real HTTP server on the loopback interface, on a port the operating system chooses,
  and the application fetches from it over HTTP exactly as it fetches from Anthropic. It is not a
  substitute for the usage client fitted in place of it: the connection, the status, the headers,
  the timeouts and the refusal to follow a redirect are all the real ones, which is what makes a
  run against it worth watching. The application cannot tell that the server is fake.
- It serves `GET /api/oauth/usage`, and the one request that changes its scenario (below), and nothing
  else. Any other path is HTTP 404 with no body, a path beneath the usage path such as
  `/api/oauth/usage/extra` included, and any other method on the usage path is HTTP 405 with no body. It
  never looks at the bearer token and never reaches the network.
- Because it ignores the token, `--fake-backend` also leaves out the token flow, exactly as
  `--fake-token` does, and does not have to be given with it (see Authentication).
- `--fake-backend` and `--anthropic-url` contradict each other, since each says where usage comes
  from. Giving both is a mistake and is refused on the command line (see Command line).
- Its normal answer is a plausible usage document in the **usage-based format** (see Source data),
  and in that format only: a `spend` object that is enabled, and no plan window. The fake backend
  never answers in the seat-based format, in any scenario, because the application does not support
  that format and a fake run is for watching what the application does support; it is to be given
  such an answer only when the format is supported. The figures move a little on each request, so
  that the row, the history and the differences between readings all have something to show rather
  than the same numbers forever.
- It can be made to answer otherwise, so that the behaviour the requirements describe for a bad
  answer can be seen without waiting for the real endpoint to misbehave. The scenarios are at least:
  the normal answer; HTTP 401, 403, 429 (with and without `retry-after`), and a 500; a body that is
  not JSON, an empty body, a body that is JSON but neither a `spend` object nor any window, and a
  body with trailing text; and an answer that is slow or never comes, past the 20 second request limit
  of The usage request. (Both take the connection at once, so neither goes past the 10 second connect
  limit; no scenario does.)
- The scenario is chosen with `--fake-scenario <name>`, the normal answer by default, and can be
  changed while the application runs so that one run can be taken through several of them. How it is
  changed is an implementation decision, provided it is on the loopback interface, is not part of the
  application's own local API, and cannot be reached from the page the application serves. **The log
  says where it listens**, so that a person can find it and change the scenario of a run that is
  already going; it is the only place that address appears, as nothing in the window mentions the
  fake backend at all.
- **How the scenario is changed, as built.** `POST /scenario` on the fake backend's own address,
  `http://127.0.0.1:<port>`, with the name of the scenario as the body, as plain text; the name is trimmed
  and read without regard to case, and no `Content-Type` is asked for. The answer is 200 with the name; 400
  `Unknown scenario "<name>"; one of <the names>` for a name it does not know, a body with nothing in it
  included, and then nothing changes; and 405 `POST a scenario name` for any other method. The new scenario
  holds from the next request: the one that applies to a request is the one in force when the request
  arrives, and a request already being held by `slow` or `hang` is not touched. A change is logged even when
  the name is the one already in force.
- **What each scenario answers** to `GET /api/oauth/usage`:

  | Scenario | Status | Answer |
  | --- | --- | --- |
  | `normal` | 200 | the usage document below |
  | `http-401` | 401 | `{"type": "error", "error": {"type": "authentication_error", "message": "fake backend"}}` |
  | `http-403` | 403 | the same with `permission_error` |
  | `http-429` | 429 | the same with `rate_limit_error`, and no `Retry-After` |
  | `http-429-retry-after` | 429 | the same, with `Retry-After: 42` |
  | `http-500` | 500 | the same with `api_error` |
  | `not-json` | 200 | the text `this is not JSON, it is a sentence` |
  | `empty` | 200 | no body |
  | `no-spend-no-windows` | 200 | `{"detail": "this is not a usage document", "limits": [], "member_dashboard_available": false}` |
  | `trailing-text` | 200 | the usage document, and on a line after it `and then some text that has no business here` |
  | `slow` | 200 | the usage document, after 25 seconds |
  | `hang` | none | nothing: the request is held for up to 5 minutes and then closed without an answer |

  Every answer has the header `request-id: fake-backend-request`, so that the log line of the request has an
  id to show. Every answer with a body is sent as `Content-Type: application/json`, the sentence that is not
  JSON included. Requests that are being held do not keep the server from answering others, and when the fake
  backend is stopped they are let go at once and closed without an answer.
- **The usage document**, whose members are, in this order, with `n` the number of documents made so far in
  the run, this one counted, so 1 for the first:

  | Member | Value |
  | --- | --- |
  | `five_hour`, `seven_day`, `seven_day_oauth_apps`, `seven_day_opus`, `seven_day_sonnet` | each `null`: the keys of the plan windows are there, as in a real response of the usage-based format, and none is a window |
  | `juniper_tide`, `cedar_ember` | each `null`: stand-ins for the keys with invented names that a real response carries |
  | `extra_usage` | `is_enabled` `true`, `monthly_limit` 100000, `used_credits` and `utilization` that follow the spend (the amount used in minor units, and that as a percentage of the limit with three decimals), `currency` `USD`, `decimal_places` 2: the credit figures said a second time, as a real response says them, which must stay out of the windows although it has a `utilization` |
  | `limits` | an empty list |
  | `spend` | `used` of 18602 + 137 × n minor units and `limit` of 100000, both `USD` with an exponent of 2; `percent`, the share used as a whole number, rounded down; `severity` `warning` from 80 percent and `normal` below; `enabled` `true`; `disabled_reason` `null` |
  | `member_dashboard_available` | `true` |
  | `seven_day_breakdown` | `null` |

  The first reading is therefore 187.39 of 1,000.00 US dollars, 18 percent, and each reading adds 1.37 to
  the amount used. The document is, key for key, what a real response of the usage-based format is,
  awkward parts and all, because those are what a parser must get right: the keys of the plan windows
  present and `null`, keys with invented names, an `extra_usage` with a `utilization` that is no window, and
  keys that are no window at all. Read by the application it is a reading with spend and no windows, so it
  is never refused. The amount used is not held at the budget, so on a long run it passes it and the percent
  passes 100; and the severity is `normal` or `warning` only, never critical. `normal`, `trailing-text` and a
  `slow` answer that was sent share the one count, which starts again with every run.
- **A fake reading is kept like any other.** The application does not know that its backend is fake, so a
  reading from it is shown in the row, written to the usage history and counted in the differences between
  readings exactly as a real one is. The rows land in the history file of the directory the run was started
  from, mixed in with the real ones, and the only thing that tells them apart afterwards is the log of that
  run, which says the fake backend was in use (see Non-functional requirements). Someone who would rather
  not mix them starts the run in another directory, which the application neither does nor advises on its
  own: nothing is invented about the history file because the backend is.
- It ships inside the application rather than beside it, and that is the whole of its presence: it is
  started only by its option, never otherwise; nothing in the window mentions it or shows that it is in
  use; it adds no endpoint to the application's own local web server; and the log says plainly that it
  is on (see Non-functional requirements), so that a reading invented by it cannot be mistaken for a
  real one.

## Testing and verification

What the sections above require is to be held up by tests, and the few things no test can judge are to be
looked at by a person. This section says which is which, so that "it works" means something.

- **The behaviour that can be tested is tested.** The parsing of the usage-based format and of every
  malformed body, and the refusal of the seat-based format, alone and together with spend, from fixtures of
  real responses; the precedence of the command line over the settings
  file over the default; the refresh schedule, including an interval changed while a request is in flight
  and a due time that has already passed; the back-off after an HTTP 429 and its easing; and the move
  between fresh, stale and failed state.
- **No automated test needs an account.** Everything that talks HTTP talks to a server on loopback: the
  token flow to a stand-in for `claude`, the usage requests to doubles, and the application as a whole to
  the fake backend of Fake backend, which is the end-to-end one — a real usage client against it with a
  placeholder token, and its error, malformed, slow and hanging answers for the failure paths that would
  otherwise need the real endpoint to misbehave. The real endpoint, the real `claude` and the real window
  stay outside the suite, and are named where they are relied on instead.
- **The rules about requests are tested as rules**: that polling the status triggers no Anthropic request,
  that a manual refresh returns at once and starts at most one request and never a second while one runs,
  that the update interval can be neither changed nor saved from the window, and that an applied usage
  interval replaces a command-line one for the rest of the run.
- **The log is tested for what it must say and for what it must never say**: the lifecycle lines in their
  order, each refresh and its outcome, and the HTTP diagnostics, in the console and in the file; and the
  absence of every credential, header and response body, checked by planting credential-shaped text in a
  request, a response and an error message and finding it masked.
- **That the frontend does no calculation is itself tested**, by a check of the page scripts for date
  parsing, rounding and number formatting, since the rule is about what is absent and cannot be seen in
  any one output.
- **The frontend is tested outside the JVM**, with Node, through a Gradle task of its own
  (`./gradlew frontendTest`, which needs `node` on the `PATH`): the view logic against the shapes the
  backend sends, the page against a fake document and a fake backend, and the style sheet for the rules
  this document fixes, such as no text under 14 pixels outside the panels. The frontend keeps its own
  tests so that it stays testable if the backend is rewritten.
- **The rules of the window are tested as rules as well**: that the page gets its polling interval from the
  backend at startup; that the settings are read from the backend each time the view opens, and that Apply,
  Cancel and Restore defaults do what Settings says; that an HTTP 429 gives no message line and no dimming
  but a red countdown; that every failed refresh reaches the error log; that a remembered height is stored
  and is never smaller than the one worked out; and that a change in the amount used of zero is shown
  nowhere, in no history cell and no item of the row, while a time of zero is.
- **Where usage comes from is tested where it is decided**: the base URL and the placeholder token on the
  command line, at the one place the usage client is made, and in the lines of the log at startup; and the
  host in the message of a failure and in the log line of a request is the configured one. The fake backend
  does not take the place of the smaller doubles, which stay because they are faster.
- **A test that opens a real window is asked for, not run by default.** It puts a window on the screen for
  a few seconds, so it runs only when a property says so (`-Daipusage.windows=true`); everything else runs
  with no display, on a machine with no window server.
- **A rule that cannot fail a test is not counted.** Each new rule is to be seen failing a test when it is
  deliberately broken, which is the only evidence that the test watches it.
- **What is left is for a person, on macOS, by eye**: startup and the first reading; each panel, its size
  and its scrollbars; the history as wide as its table with every column whole, with the date and both
  change columns on and off again; the window growing and shrinking around a message; the settings whole,
  without a scrollbar, giving back the panel that was open before them; the interval applied; a manual
  refresh; a failed refresh and the quiet sign of an HTTP 429; the green of the open panel's button and the
  green startup line on a light and a dark theme; the gear and the spacing of the icons; the width of the
  dropdown; text that can be selected in the settings; readable text
  and the time of day only; and that closing the window ends the program. These are listed because they are
  the parts no assertion covers, not because they matter less.

## Scope and limits

What the first version deliberately is not, so that none of it is taken for an oversight.

- **macOS, started from Gradle.** The first version is for macOS and is started with `./gradlew run` or from
  an IDE. There is no packaged application and no installer; a macOS app bundle is left out on purpose.
  Windows and Linux are not a target of this version: Windows is what the Go rewrite is for.
- **Claude Code is the only way to sign in.** The program has no login of its own. When Claude Code is
  missing or logged out the window says what to do, and the refresh button tries again once it has been
  done; the program cannot do it for the person.
- **One program at a time.** Nothing stops a second copy from being started in the same directory; the two
  would share the settings, the history and the log, and the last to write would win.
- **A direct connection.** No proxy, as The usage request says.
- **The local server is for this machine's one user**, as The local server says.
- **The endpoint is not a published one.** `/api/oauth/usage` is the one Claude Code itself asks, and the
  default interval and the back-off rest on how it was seen to behave, not on a documented limit.
- **No charts.** The history is kept, and shown as lines; drawing it is a future extension.

## Future extension

The project should be designed to evolve toward a richer dashboard, potentially
including:

- charts showing usage trends over time, drawn from the usage history above
- optional historical windows and summaries

## Implementation context

This repo is deliberately positioned as a playground around the sibling projects in the same research folder:

- `java-aip` — main CLI and usage patterns to exercise
- `java-claude-login` — login/auth flow references
- `java-claude-code-fetch-oauth-token` — OAuth token retrieval patterns
- `java-claude-code-model-list` — data-fetching and output formatting examples
- `java-interceptor` — request/response interception and debugging patterns
- `doc-and-ref-repos` — specification and reference material

These repos are intended as a source of knowledge and reusable implementation ideas rather than a hard dependency.

## Non-functional requirements

- Use Java 25 with Gradle toolchain support.
- Use the bundled Gradle wrapper rather than a manual local install.
- **Nothing has to be installed by hand to build it.** The toolchain is declared, so Gradle fetches the JDK 25 it needs when the machine has none, and the wrapper fetches the one Gradle version the project declares, so every machine builds it with the same one. A clone and `./gradlew` are the whole of the setup; the only thing the build expects of the machine is a network and, for the frontend tests, `node` on the `PATH`.
- `./gradlew build` compiles the sources and runs the tests, and `./gradlew run` opens the window. The options of Command line are given to the `run` task after `--args`, for example `./gradlew run --args="--usage-interval 10"`, so every option of the program can be used without a packaged launcher.
- Provide a `./gradlew run` task and keep the solution easy to run from the IDE. A distributable macOS app bundle is out of scope for the first version.
- The fake backend (see Fake backend) is part of the application and needs no task, no second entry point and no second command of its own: `./gradlew run --args="--fake-backend"`, or the same option in an IDE run configuration, is the whole of it. It is in the shipped artefact, reachable only through its option.
- Prefer simple, testable interaction boundaries between token acquisition, usage fetching, and rendering.
- **A poll is cheap.** The window asks for the status every second for as long as the application runs, so that answer costs no more work than it has to: the figures it carries are the ones the refresh already worked out, and the history file is read again only when it has changed (its size or its time), not once a second. A panel that is open is read again on the same terms: the history when its file has changed, the log and the error log on the poll, and the lines on the page are replaced only when they differ from what is already there, so that what a person is reading does not move for an answer that says nothing new.
- Write application logs to both the console and a log file named `java-aip-usage.log` in the project root, beside `gradlew`, appending to the file on each run rather than overwriting it. Never log access tokens or other credentials.
- Each run of the log begins with the line `Logging to <the log file>`, and then a line saying the program was started, with its version
  (`Starting java-aip-usage v0.26`), and then a line that says which file the usage history CSV is written to, as a full path, for example
  `Usage history is written to /path/to/java-aip-usage.csv`. The path is logged only here, never
  revealed to the window or any request (see The log panel). The line that says the program was started
  is the one the log panel marks as the start of a run (the log panel finds it by its wording, `Starting java-aip-usage`, so that wording and the form of the log line are part of this requirement).
  After the settings path comes a line naming the base URL usage is fetched from, `Usage is fetched from https://api.anthropic.com`, on every run and not only a configured one, so that the log of any run says where
  its readings came from; for a run with `--fake-backend` it names the address the fake backend was given, preceded by a line of its own, `The fake backend is in use: these readings are invented (--fake-backend,
  scenario <name>)`. When no token is obtained, a line follows: `No token is obtained: a placeholder is sent instead (--fake-token)`, and `(--fake-backend)` in its place when that is what left the token out.
  A change of scenario while the application runs is logged too, as `The fake backend scenario is now <name>`. These lines come after the one the log panel marks, so the marking is unaffected.
- The settings file, `settings.json` in the same directory as the usage history file, holds all the
  settings, and the two remembered heights (see Remembered heights), stored as JSON, and nothing else. If there is none at startup a new one is created with the
  defaults, and the event is logged. The full path of the settings file is logged at startup, in a line of its
  own, like that of the history file. A settings file that cannot be read, or whose content is not a JSON object, is logged and
  the defaults are used for the run, without overwriting it until a setting is applied. A single value that is not valid (the wrong type, an
  interval outside 5 to 3600, a time format other than `hh:mm` or `hh:mm:ss`, a height outside 0 to 10,000) is logged and has its default, and the other keys are kept. A key the file
  lacks has its default, and a key it has that is not a setting, such as the old `pollIntervalSeconds`,
  is ignored. The keys are `usageIntervalSeconds`, `logResponse`, `showPercentage`, `showCurrency`, `showHistoryIcon`, `showLogIcon`, `showErrorIcon`, `showInterval`, `showDeltaUsed`,
  `showDeltaTime`, `timeFormat` (the text `hh:mm` or `hh:mm:ss`), `historyDeltaUsed`, `historyDeltaTime`, `historyDate`, `historyZeroLines`, `historyFailedLines`, and the two heights
  `historyHeight` and `logHeight`. Where this document says "in the project root, beside `gradlew`", the files are in the working directory the program was
  started from, which is the project root for `./gradlew run` and for an IDE run configuration set as the notes say.
- **The settings file as written.** One JSON object, printed over several lines, with the keys always in the order above, the two heights last and always there, as `0` when none is remembered. Every save writes the whole file
  anew from what the program holds, so a key that is not a setting, the old `pollIntervalSeconds` or one added by hand, is gone after the first save, and a value that was not valid is replaced by its default there;
  storing a remembered height is such a save. The new file is written beside the old one as `settings.json.tmp`, which is not left behind whether or not the save succeeded; a save that fails leaves the file as it was.
- **How the settings file is read.** No value is turned into another: the interval and the heights must be whole JSON numbers (`"45"`, `45.5` and `true` are refused), a switch must be `true` or `false` (`"yes"` is
  refused), and the time format exactly the text `hh:mm` or `hh:mm:ss`. A key whose value is `null` is a key the file lacks: it has its default and nothing is logged. An empty file, and one that holds a list, are files that
  are not a JSON object. A file added to by hand keeps its extra keys until the next save.
- **Applying settings that are the file's own.** Whether anything is written and logged is judged against what the file holds, not against what the run is using: applying the file's values while an interval from the
  command line is in force writes and logs nothing, and still puts the applied interval in the place of the command line's for the rest of the run.
- Each line of the log begins with its time in the same form as the usage history file: the local date and time to the second, `yyyy-MM-dd HH:mm:ss`, for example `2026-10-08 16:24:53`, so the log, the history and the window agree on the clock.
- The log records startup and shutdown, each refresh and how it ended, token acquisition and rejection, settings changes, and for each request to Anthropic its status, duration, size and `request-id`. It never records a token or a header. It records no part of a response body, except that, while the setting to log the response is on, it writes the JSON of each response, pretty printed over several lines, after one entry line that says whose it is. That text is masked like everything else, so anything shaped like a credential in it is hidden before it is written, as a last line of defence. The setting is off by default.
- Every failed refresh is also written to the log, once when it happens, at the warning level, as `Usage refresh failed: <the message>` (the message without its `Refresh failed at HH:mm` wrapper), an HTTP 429 included although it has no message line. The window's own `Lost contact with the application` message is the window's only and is not logged.
- The local web server listens on the loopback interface only, on a port chosen by the operating system. It refuses a request whose `Host` header is not its own address, which stops another web page from reaching it by DNS rebinding, and it accepts a `POST` only as `application/json`, which another origin cannot send without a preflight the server never grants. No endpoint accepts, returns or logs a credential.
- The program starts from an IDE as a plain `main` method, with no module path or VM options, as well as with `./gradlew run`.
