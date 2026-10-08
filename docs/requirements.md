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
- If the user is not logged in or the token is unavailable, the application should show a clear user-facing message instead of failing silently. The same goes when `claude` cannot be found on the `PATH` (the message says what to do, including that a changed `PATH` needs a restart) and when it is found but cannot be run.
- If `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set, `claude` would send that credential instead of its OAuth login token, so the application does not capture one: it shows a message naming the variable, never its value, and asks for it to be unset and the application restarted.

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
- When the UI starts, it must request the effective intervals from the backend, and it uses the polling interval to poll the backend for the latest available state. It shows the usage-fetch interval when the config field is opened, and it asks the backend for that value each time the field is opened, not only at startup. If the backend's value has changed in the meantime, for instance because another client changed it, the field shows the new value. If the backend cannot be reached at that moment, the last value known is shown.
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

- `used` and `limit`, as two plain numbers with no currency sign;
- `currency`, in the tooltips of those two numbers, not as a sign;
- `percent`, as a percentage;
- `severity`, by the colour of the percentage and the numbers, and named in the
  percentage's tooltip.

Section Compact window says where each goes and what the tooltips say.

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
  In its normal state it is a single row, roughly 330 by 35 pixels of content, which is
  about 62 pixels tall with the title bar. The window cannot be resized by hand: it always
  fits its content and follows it as it changes. The one exception is while the usage history is
  shown, when it is ten times as tall as the single row and can be resized in height, and, for the
  log, three times as wide and resizable in width too (see The log panel and The usage history panel). A row that would be wider than about 900
  pixels, for an account with many plan windows, wraps onto a second line.
- **The window's width is set by the row alone.** A message line, such as an error, never makes the
  window wider: it is wrapped to the width the row has and takes as many extra lines as it needs,
  and the window grows in height only. The same holds for the log and the history.
- The text is easy to read: a sans-serif font of at least 14 pixels, with strong
  contrast. It is set bold throughout (weight 700), with the percentage and the
  spent and budget heavier still (800). No thin or light weights, no fine print. The one
  exception is the usage history shown below the row, which is deliberately small and
  condensed, like a log file (see The usage history panel); it is still never thin.
- Every time the window shows is the local time of day only, with no date, for
  example `14:24:53`. This applies to the time of the last refresh and to the time
  of an error alike. The one exception is the tooltip on the time (see Tooltips),
  which gives the full date and time.

**The row**

The controls sit in one horizontal row, one after another, with small gaps, in
this order. The refresh button sits snug against the amounts before it, and the countdown
snug against the button, so they read as one group:

1. the time the usage was last refreshed (`fetched_at`), first of all;
2. the percentage spent;
3. what has been spent and the budget: `used` and `limit`, as two plain numbers
   with no currency symbol, for example `186.02 / 1,000.00`. The unit is given by
   the tooltips, not by a sign;
4. a small refresh button;
5. a countdown to the next refresh, in seconds and with its unit, for example
   `42 s`, and `-3 s` when the refresh is overdue;
6. a very small config button, an icon rather than a word;
7. at the right-hand end, after the config button and its field when that is open, a small
   button that shows the log below the row, and hides it again, an icon rather than a word;
8. next to it, a small button that shows the usage history below the row, and hides it again,
   likewise an icon.

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
- The percentage: how much of the budget is spent, and the severity, for example
  `19% of the budget spent. Severity: normal`, since the colour alone would be the only
  place the severity showed.
- The countdown: that it is the seconds until the next refresh, and negative when
  overdue.
- The refresh button says `Refresh now`, and the config button says it configures
  the fetch interval, as before.
- The log button says `Show the log`, and `Hide the log` while the log is shown. The history button says `Show the usage history`, and
  `Hide the usage history` while the history is shown.

**Config**

- Pressing the config button shows one input field in the same row, next to the
  button: the usage fetch interval, in seconds. It has a short label saying what it
  is, and shows the backend's current value, read afresh each time the field is
  opened, as described under Refresh behavior. The window update interval is not
  offered: it is set on the command line only.
- The value is confirmed with Enter or a small confirm button. When a valid value is
  confirmed it is saved and takes effect, as described under Refresh behavior, and the
  input field disappears.
- An invalid value keeps the field open and shows a brief message; it is not sent.
  Pressing Escape, or the config button again, closes the field without changing
  anything.

**The log panel**

The log is shown the way the usage history is: inside the main window, not in a window of its own.

- Pressing the log button makes the main window ten times as tall, as for the history, **and three times
  as wide as the single row**, and shows the
  end of the log file, `java-aip-usage.log`, in a panel below the first row and its message lines.
  Pressing it again hides the panel and the window goes back to the size it had. While it is shown the
  window can be resized in height **and in width**, and the panel takes the room there is. Unlike the
  history, the log has long lines, which is why it gets the extra width. Opening it again starts again
  at three times the row's width and ten times its height.
- The log and the history share the one panel area: showing one while the other is shown replaces
  it, and the window takes the size the shown one has (so it is wider for the log than for the history),
  and each button's tooltip says `Hide…` only for the one
  that is shown.
- One line of the file is one line of the panel, as written, newest first like the history, in the
  same small, condensed, regular-weight, fixed-width text, and it is never wrapped (a long line
  scrolls sideways).
- It shows the end of the log: the newest 1,000 lines, reading at most the last 512 kilobytes of the
  file, since the log is never rotated and can be large. If lines were left out, a line above them
  says how many are shown.
- While the panel is shown it keeps up with the file: new lines appear at the top soon after they
  are written, without pressing anything, and what the person has scrolled to does not move. There
  is no reload button.
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
  starts again at ten times the row's height, not at the height the person last dragged it to.
- The history panel keeps the width of the row. Only the height changes, and nothing else in the row
  moves.
- The history is a table with a header row (`datetime`, `used`, `limit`, `currency`, which does not
  scroll away) and one line for each reading, the columns **spread across the width of the panel**, with
  a little space between them, no more than needed, so that nothing is cut off (in particular
  the currency and its title): the date and time at the left, and `used`, `limit` and
  `currency` each centred horizontally in their own column. For example, as wide as the row:

  ```
  datetime               used      limit     currency
  2026-10-08 21:01:22    263.89    1000.00     USD
  2026-10-08 20:46:11    260.66    1000.00     USD
  ```

- The column titles are centred over their columns.
- **The lines are sorted by `datetime`, latest first.** They are sorted by that column, not merely
  taken in reverse file order, so a file that is out of order is still shown right.
- The text is **small and condensed**, in the manner of a log file: a fixed-width font of about
  12 pixels with tight line spacing and no padding between lines, so that many readings fit in
  little space. This is the one place the 14-pixel minimum does not apply. It is regular weight,
  not bold, and not thin.
- The panel scrolls: older readings are reached by scrolling it, or by making the window taller. It
  holds the newest 1,000 readings; a line above them says how many readings there are in all and how
  many are shown, when there are more than are shown.
- While the panel is shown it keeps up with the history: a new reading appears at the top soon
  after it is recorded, without pressing anything. It does not move what the person has scrolled to.
- A line of the file that does not have the columns is left out, so one damaged line cannot
  hide the others. If there is no history yet, or it has no readings, the panel says so in one line.
  If it cannot be read, the panel says so in red, and the window shows what it had.
- The panel's data comes from the application, through a read-only request that reveals nothing
  about where the file is and adds nothing to the log or the history.

**Messages and states**

- A failed refresh, stale data, or a missing or logged-out Claude Code must still be
  visible, as required elsewhere. In the compact window this is a short message on
  a second line, shown only while the condition lasts, in the form
  `Refresh failed at 14:25:01: <what went wrong>`. The window grows to hold it and
  returns to its single-row size afterwards.
- **Error text is red.** Every error message is shown in red: the failed refresh, whether
  or not older figures are still on show, the loss of contact with the application, and an
  invalid value in the config field. No error is shown in another colour.
- The last good figures stay in the row after a failed refresh, dimmed to show that
  they may be out of date, and the dimming goes when a refresh succeeds again.
- **On startup the newest reading in the history file is loaded and shown at once**, as the row's
  figures with the time of that reading, until the first refresh replaces it, so the row is not empty
  while the first request is on its way. If that refresh fails, the figures stay, dimmed, with the usual
  failure message. It is not written to the history again. If there is no history, or its newest row has
  no amounts, nothing is shown in advance.
- Before the first reading the row says `Loading…`. If the first refresh failed it says
  `No data`, and if the account reports no usage at all it says `No usage reported`.
- If the window cannot reach the application, it says so in red (`Lost contact with the
  application. Still trying.`), keeps trying, and shows what it last had.
- The window also resizes to fit when the config field appears and disappears.

The refresh button stays enabled at all times, and the behavior of everything
behind these controls is unchanged.

## Usage history

Every reading the application gets is kept, so the usage can be looked at afterwards.

- Each successful refresh that has amounts adds one row to a CSV file named
  `java-aip-usage.csv` in the project root, beside `gradlew` and the log. The file is not
  committed to version control. It is added to, never overwritten, so successive runs build
  one history, and nothing in it is ever rotated or deleted by the application.
- The columns are `datetime`, `used`, `limit` and `currency`, with a header row written when the file
  is new or empty and never again, for example:

  ```
  datetime,used,limit,currency
  2026-10-08 16:24:53,186.02,1000.00,USD
  2026-10-08 16:25:53,186.07,1000.00,USD
  ```

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
  language, with no currency sign, no digit grouping and no quoting. A missing amount is an
  empty field. `currency` is the code the response names, for example `USD`, or empty when it named none.
- A file written before the currency was a column, with the header `datetime,used,limit`, is upgraded
  in place the first time a row is added: the header gets the new column and the rows already in it get
  an empty currency, since the file never said. Nothing else in it changes.
- A failed refresh writes nothing, and neither does a reading with no amounts, such as the
  plan windows of a Pro or Max account.
- A history that cannot be written is logged and nothing more. The reading is good, so the
  refresh still counts as a success, and the window shows it as usual.
- The file holds spending figures, so it stays on this machine, and nothing in it is a
  credential. At the default interval of a minute it grows by about 60 kilobytes a day.

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
- Provide a `./gradlew run` task and keep the solution easy to run from the IDE. A distributable macOS app bundle is out of scope for the first version.
- Prefer simple, testable interaction boundaries between token acquisition, usage fetching, and rendering.
- Write application logs to both the console and a log file named `java-aip-usage.log` in the project root, beside `gradlew`, appending to the file on each run rather than overwriting it. Never log access tokens or other credentials.
- Each line of the log begins with its time in the same form as the usage history file: the local date and time to the second, `yyyy-MM-dd HH:mm:ss`, for example `2026-10-08 16:24:53`, so the log, the history and the window agree on the clock.
- The log records startup and shutdown, each refresh and how it ended, token acquisition and rejection, settings changes, and for each request to Anthropic its status, duration, size and `request-id`. It never records a token, a header or any part of a response body, and anything shaped like a credential is masked before it is written, as a last line of defence.
- The local web server listens on the loopback interface only, on a port chosen by the operating system. It refuses a request whose `Host` header is not its own address, which stops another web page from reaching it by DNS rebinding, and it accepts a `POST` only as `application/json`, which another origin cannot send without a preflight the server never grants. No endpoint accepts, returns or logs a credential.
- The program starts from an IDE as a plain `main` method, with no module path or VM options, as well as with `./gradlew run`.
