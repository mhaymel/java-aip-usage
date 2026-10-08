# Requirements

## Goal

This project provides a small Java-based client for monitoring Anthropic OAuth usage data. It should fetch the current usage snapshot and present it in a readable, continuously refreshed form.

The implementation is intended to exercise the neighboring `java-aip` tooling and to reuse patterns from the sibling repos for authentication and OAuth token handling.

## Source data

The application fetches usage data from the Anthropic OAuth usage endpoint:

`https://api.anthropic.com/api/oauth/usage`

The JSON examples below show the application's normalised view of a usage
reading, the same shape `java-aip usage --format json` prints. They are not the
raw HTTP response. The endpoint sends no `source` or `fetched_at`: the
application stamps `fetched_at` with the time it received the response. It
sends spend as minor units under a `spend.enabled` flag (`{"amount_minor":
18602, "exponent": 2}` is 186.02) and plan windows as top-level keys holding a
`utilization`, among unrelated and placeholder keys. The application maps the
raw response to the shape below; real responses are in
`src/test/resources/fixtures/`.

The shape depends on the account's subscription. For usage-based accounts,
the reading contains spend details and an empty `windows` array:

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

For Pro or Max plan subscriptions, the `spend` value is `null` and `windows`
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
- If the usage endpoint responds with HTTP 401, fetch a fresh token and retry the request once. Do not refresh the token for other HTTP or network failures.
- If the user is not logged in or the token is unavailable, the application should show a clear user-facing message instead of failing silently.

Example token output:

```json
{
  "access_token": "sk-ant-..."
}
```

## Refresh behavior

- Fetch usage immediately when the application starts, then repeat at the configured interval.
- The default backend usage-fetch interval is 60 seconds. Configure it in seconds through both a command-line option (`--usage-interval <seconds>`) and a frontend control; accept values from 5 through 3600 seconds. The default is a minute because the usage endpoint appears to accept about one request a minute over the long run: a faster pace, such as 30 seconds, is allowed but draws HTTP 429 after roughly ten minutes.
- Save a usage-fetch interval changed in the frontend to a settings file named `settings.json` in the project root, beside `gradlew`. A committed valid frontend value is sent to the backend and replaces any CLI override for the remainder of the run. The settings file holds this one value and nothing else.
- Changing the backend usage-fetch interval does not cancel a request already in progress. Apply the new interval to the next scheduled request, measuring the interval from when the current/most recent request was triggered. If the new interval has already elapsed, start the next request as soon as no request is running; otherwise wait until the interval elapses. Changing the interval does not otherwise trigger an extra immediate request.
- If a refresh fails, keep the last successful data visible, mark it as stale, and show the error. Resume normal display after the next successful refresh.
- If Anthropic answers HTTP 429 (rate limited), the application slows down instead of
  carrying on at the same pace. The next scheduled request waits twice the usage
  interval, and each further 429 in a row doubles the wait, up to 5 minutes, or the
  `Retry-After` the server gave if that is longer. A success eases the wait and does
  not drop it: it takes an eighth off, and the eased value is the new wait, until it
  is no longer than the usual interval. Dropping it at once would return to the very
  pace the server has just refused. A failure that is not a 429 leaves the wait as it
  is. The refresh button is never held back, since a person asked. The error shown
  says when the next try is.
- The backend reports the time until the next scheduled refresh together with the
  status, in whole seconds and possibly negative, as described under Compact window.
  The frontend does not work it out.
- The backend alone fetches usage from Anthropic on this interval. UI status polling must not trigger an Anthropic request.
- The UI-to-backend status polling interval, how often the window asks the backend for the latest state, is not a setting. It is 1 second by default and can be overridden for one run with a command-line option (`--poll-interval <seconds>`), which accepts values from 1 through 60 seconds. It has no frontend control, is never changed while the application runs, and is never saved: it is forgotten when the application stops, and a value left in an older settings file is ignored.
- When the UI starts, it must request the effective intervals from the backend: it shows the usage-fetch interval when the config field is opened, and uses the polling interval to poll the backend for the latest available state.
- Provide a UI action to fetch usage immediately. It must call the backend, which starts an Anthropic usage request without waiting for the next scheduled refresh and returns immediately. Do not run overlapping usage requests; if a refresh is already in progress, return immediately without starting another one. Keep the manual-refresh action enabled; extra clicks while a request is in progress do not start additional requests.
- Closing the application window must terminate the program and stop its backend server, scheduled tasks, and other background resources.
- The program shuts down the same way, with the same lines in the log, when the process is asked to stop without the window being closed, for example by `SIGTERM` or `SIGINT`. A forced kill (`SIGKILL`) cannot be caught by any program, so it leaves nothing in the log.

## Display requirements

The layout of the window is governed by [Compact window](#compact-window); where
that section and the descriptions below differ on presentation, it wins.

The usage data must be displayed in a simple graphical user interface (GUI)
that is easy to read at a glance. `fetched_at` must always be shown. The
remaining fields depend on which response shape the account returns; show only
the fields relevant to the shape received.

For a usage-based account (`spend` populated, `windows` empty), present:

- `used`
- `limit`
- `currency`
- `percent`
- `severity`

For a Pro or Max account (`spend` is `null`, `windows` populated), present each
window with:

- `window` — the window name, as supplied by the endpoint
- `utilization`
- `resets_at` — may be absent; show it as unknown rather than omitting the window

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

## Compact window

The window is a status strip that a software engineer keeps beside their work,
so it must take as little screen space as it can while staying easy to read.

**Size and text**

- The window's title is `aip usage` followed by the program's version, for example
  `aip usage v0.01`. The version is written in the source code as one constant, is
  increased by hand whenever the program changes, and starts at 0.01. The page inside
  the window is titled `aip usage` without the version, so the version is in one place.
- The window is as small as its content allows, with no empty space around it.
  In its normal state it is a single row, roughly 400 by 50 pixels.
- The text is easy to read: a sans-serif font of at least 14 pixels, with strong
  contrast. It is set bold throughout (weight 700), with the percentage and the
  spent and budget heavier still (800). No thin or light weights, no fine print.
- Every time the window shows is the local time of day only, with no date, for
  example `14:24:53`. This applies to the time of the last refresh and to the time
  of an error alike. The one exception is the tooltip on the time (see Tooltips),
  which gives the full date and time.

**The row**

The controls sit in one horizontal row, one after another, with small gaps, in
this order:

1. the time the usage was last refreshed (`fetched_at`), first of all;
2. the percentage spent;
3. what has been spent and the budget: `used` and `limit`, as two plain numbers
   with no currency symbol, for example `186.02 / 1,000.00`. The unit is given by
   the tooltips, not by a sign;
4. a small refresh button;
5. a countdown to the next refresh, in seconds and with its unit, for example
   `42 s`, and `-3 s` when the refresh is overdue;
6. a very small config button, an icon rather than a word.

The severity is shown by colour on the percentage and the amounts, rather than by
extra words.

For an account with Pro or Max plan windows instead of spend, the windows take the
place of items 2 and 3 in the same row, each as its utilization first, then its
name exactly as supplied, then the time remaining until it resets (for example
`12% five_hour in 2 h 5 min`). Remaining time is used because a reset can be days
away, and a time of day alone would then mislead. An unknown reset time is shown
as unknown.

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

**Tooltips**

Hovering over these shows what they mean. The currency is the one the response
names, shown as its code, here `USD`.

- The time: `Last update: ` followed by the full local date and time of the last
  refresh, for example `Last update: 8 Oct 2026, 14:24:53`. This is the one place a
  date appears.
- The used amount (the first number): `Credits used, in USD`.
- The budget (the second number): `Credit budget, in USD`.
- The countdown: that it is the seconds until the next refresh, and negative when
  overdue.
- The refresh button says `Refresh now`, and the config button says it configures
  the fetch interval, as before.

**Config**

- Pressing the config button shows one input field in the same row, next to the
  button: the usage fetch interval, in seconds. It has a short label saying what it
  is, and shows its current value. The window update interval is not offered: it is
  set on the command line only.
- The value is confirmed with Enter or a small confirm button. When a valid value is
  confirmed it is saved and takes effect, as described under Refresh behavior, and the
  input field disappears.
- An invalid value keeps the field open and shows a brief message; it is not sent.
  Pressing Escape, or the config button again, closes the field without changing
  anything.

**Messages**

- A failed refresh, stale data, or a missing or logged-out Claude Code must still be
  visible, as required elsewhere. In the compact window this is a short message on
  a second line, shown only while the condition lasts. The window grows to hold it
  and returns to its single-row size afterwards. The last good figures stay in the
  row meanwhile.
- The window also resizes to fit when the config fields appear and disappear.

The refresh button stays enabled at all times, and the behavior of everything
behind these controls is unchanged.

## Future extension

The project should be designed to evolve toward a richer dashboard, potentially
including:

- charts showing usage trends over time
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
- Provide a `./gradlew run` task and keep the solution easy to run from the IDE. A distributable macOS app bundle is out of scope for the first version.
- Prefer simple, testable interaction boundaries between token acquisition, usage fetching, and rendering.
- Write application logs to both the console and a log file named `java-aip-usage.log` in the project root, beside `gradlew`, appending to the file on each run rather than overwriting it. Never log access tokens or other credentials.
