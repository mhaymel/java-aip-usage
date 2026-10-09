# java-aip-usage

A small Java client that monitors Anthropic OAuth usage. It fetches the
current usage snapshot from `https://api.anthropic.com/api/oauth/usage` and
shows it in a compact JavaFX WebView window, refreshed on a timer.

Depending on the account, the snapshot carries either usage-based spend
(`used`, `limit`, `currency`, `percent`, `severity`) or the Pro/Max plan
windows (`window`, `utilization`, `resets_at`) — the window shows whichever shape
the endpoint returns. When a refresh fails the last good figures stay on screen,
dimmed, with the error underneath.

## The window

A status strip to keep beside your work: as small as its content, bold readable
text, local time of day only.

```
16:47  186.02 / 1,000.00  ⟳ 42 s  ⚙
```

From the left: when the usage was last refreshed, the percentage spent, what has been
spent and the budget as plain numbers (severity is the colour of the percentage and
the amounts), a refresh button, the countdown, optionally the time between requests and the change since the previous reading, and small log, history, error log and settings buttons (the gear last). A Pro or Max
account shows its plan windows in place of the percentage and amounts, each as its
utilization, its name and the time until it resets.

Hover over things to see what they are:

| Hover over | It says |
| --- | --- |
| the time | `Last update: 8 Oct 2026, 14:24:53`, the one place a date appears |
| the first number | `Credits used, in USD` |
| the second number | `Credit budget, in USD` (the currency code is the response's own) |
| the percentage | how much of the budget is spent, and the severity |
| the countdown | seconds until the next refresh, negative when overdue |

- **Log** and **History** buttons, at the right-hand end of the row, show the end of the log file or the usage
  history inside the main window: the window becomes ten times as tall and shows the lines below the first row,
  newest first, in a small condensed font like a log file. The history is a table with a header
  (`datetime`, `used`, `limit`, `currency`) whose columns are spread over the full width; the log is plain lines,
  and its window is also three times as wide as the row. Only one is shown at a time; pressing the other
  button switches, pressing the same one again hides the panel and the window shrinks back. While a panel is
  open the window can be dragged taller (the log's also wider, the history's not) and the panel fills it,
  scrolling. New readings and new log lines appear at the top by themselves, without moving what you are
  reading. The log shows the newest 1,000 lines (reading at most the last 512 KB); the history the newest 1,000
  readings.
- **At startup** the newest reading in the history file is shown straight away, until the first refresh
  replaces it (and kept, dimmed, with the error if that refresh fails). Nothing is added to the history for it.
- A message line, such as an error, never makes the window wider: it wraps and takes more lines.
- **Refresh** (⟳) fetches now. It stays clickable; clicks during a fetch do nothing extra.
- **Countdown** (`42 s`) is the seconds until the next scheduled refresh, worked out by
  the application and shown as it reports it. It starts again after a manual refresh,
  is longer while the application is backing off after a rate limit, and goes negative
  (`-3 s`) if the refresh is overdue, for example because a request is slow. It is
  empty until the first request has been made.
- The button of the panel that is shown (log, history, error log, settings) is a vivid green, a little bigger and bolder than the others; it goes back to gray when that panel is closed or another opens.
- After HTTP 429s the application waits longer than the interval you set. That longer wait is what the interval item in the row and the interval box of the
  settings show (and Apply then saves what the box shows); it eases back by itself as requests succeed.
- **Error log** (the triangle) lists the errors of this run, newest first, with the time and the message: every failed
  refresh, an HTTP 429 and a problem with the token (not logged in, `claude` not found) included. It is kept in memory only and is empty at
  every start. An HTTP 429 is shown only as a red countdown, which tells you why when you hover over it, and is not a message under the row.
- The history shows the time of day (a setting adds the date) at the left and the amounts, the currency (`Cur.`) and, if switched on, the changes (`Δ used`, the time in seconds as `Δ time`) packed to the right, and hovering over the first line of a run says `The program started here`. The window
  remembers the height you leave the history and the log at.
- **Settings** (⚙, at the right-hand end) shows the settings in the panel area below the row, like the log and the history: the time
  between usage requests (a dropdown: 60 to 300 s, default 60), whether the row shows the percentage spent (off by default), the interval (a box you can type any number of seconds in, or pick from), whether the history shows the date, the time between usage requests and the change
  since the previous reading, the time format (`hh:mm` or `hh:mm:ss`), the two matching columns of the history, and
  whether each response's JSON is written to the log. The values are read from the application each time you
  open it. Nothing takes effect until **Apply**, which saves them and closes the view; **Cancel** closes it and changes
  nothing. **Restore defaults** fills in the defaults, and **Maximum view** and **Minimum view** switch all the row's
  optional items on or off together.
- The title bar reads `aip usage v0.01`. The version is one constant, `AppInfo.VERSION`,
  written by hand and increased by hand when the program changes; it has nothing to do
  with the Gradle project version.
- A failed refresh, or a missing or logged-out Claude Code, adds a short message
  line under the strip while it lasts, **in red**; the window grows to hold it and shrinks
  back. The last figures stay on show, dimmed, until a refresh succeeds again.

It doubles as a playground for exercising the neighboring
[`java-aip`](../java-aip) tooling and reusing auth patterns from the sibling
repos.

- [Requirements](docs/requirements.md) — what the tool must do
- [Implementation plan](docs/implementation-plan.md) — how it is to be built

> **Status:** first version complete. It has run end to end on macOS against a
> usage-based account: startup, scheduled and manual refresh, saving a setting,
> a missing Claude Code CLI, and clean shutdown. The Pro/Max plan-window view has
> only been exercised with test data. See [Known limitations](#known-limitations).

## Prerequisites

- **A JDK 25 toolchain** — the same one [`java-aip`](../java-aip) uses. Gradle
  downloads it if it is missing, so no manual JDK or Gradle install is needed;
  use the bundled `./gradlew` (Gradle 9.6).
- **The Claude Code CLI, installed and logged in**, with `claude` on the `PATH`
  of the shell you start this from. The OAuth token is taken from it (see
  [Where the token comes from](#where-the-token-comes-from)). Without it the
  window still opens, but it shows what to do instead of usage, and the Refresh
  button tries again.
- **Direct network access to `api.anthropic.com`.** Proxies are not used.

The first version targets macOS.

## Build and run

```sh
./gradlew build          # compile and run the tests
```

```sh
./gradlew run            # open the desktop window
```

Run it from the project directory, in a terminal where `claude` works. The log
and `settings.json` are written to the directory you start it from, which is the
project root for `./gradlew run`.

Options go after `--args`:

```sh
./gradlew run --args="--usage-interval 10 --poll-interval 2"
./gradlew run --args="--help"
```

Or start `org.example.Main` from IntelliJ (▶ in the gutter next to `main`). It needs no
VM options or module path. Set the run configuration's working directory to the
project root so the log and settings land there, and check that IntelliJ's
environment finds `claude`: a program started from an IDE or the Finder can have a
shorter `PATH` than your terminal, which shows up as "Claude Code could not be found
on the PATH".

Started that way JavaFX sits on the plain classpath, so two warnings appear that
`./gradlew run` avoids: JavaFX's "Unsupported JavaFX configuration: classes were
loaded from 'unnamed module'", and the JDK's note that native access will need to be
enabled in a future release. Both are harmless. To silence the second, add
`--enable-native-access=ALL-UNNAMED` to the run configuration's VM options.

The test that opens the real window (the panels, and the size the page asks for) runs only when asked for, since it puts a window on the screen for a few seconds:

```sh
./gradlew test --tests '*PanelWindowTest' -Daipusage.windows=true
```

The frontend's logic is tested with Node, outside the JVM build:

```sh
./gradlew frontendTest     # needs node on the PATH; same as: node --test src/test/frontend
```

If Gradle reports `problem occurred starting process 'command 'node''`, its
daemon was started without `node` on its `PATH`; run `./gradlew --stop` from a
shell that has it and try again.

Closing the window terminates the program, including the backend server and
its scheduled work.

## Configuration

Two intervals, which are not alike:

| Setting | Default | Range | Command line | In the window | Saved |
| --- | --- | --- | --- | --- | --- |
| **Usage fetch**: how often the application asks Anthropic | 60 s | 5–3600 | `--usage-interval <seconds>` | yes, a dropdown in the settings (60–300 s) | yes, in `settings.json` |
| **Update**: how often the window asks the application for the latest state | 1 s | 1–60 | `--poll-interval <seconds>` | no | no |

For the usage fetch, the command line wins over the saved setting, which wins over the
default. A value confirmed in the window is saved, takes effect at once, and replaces
the command-line one for the rest of the run. A command-line value is not saved on its
own, so the file keeps what you last chose in the window.

The update interval is a setting for one run only: give `--poll-interval` or get 1 s.
It cannot be changed from the window, and nothing remembers it. A `settings.json`
written by an earlier version may still hold one; it is ignored and disappears the
next time the file is saved. `settings.json` holds all the settings of the settings view
(and is created with the defaults if it is missing), certainly never a credential.

Only the backend talks to Anthropic; the window's updates read local state and never
trigger a request. A manual refresh button fetches immediately, and refreshes never
overlap.

The window talks to the application through a small local API, described in
[docs/api.md](docs/api.md).

## Where the token comes from

The OAuth token is the one Claude Code itself uses, and the application gets it
the way [`java-aip`](../java-aip) does:

1. It starts a small server on `127.0.0.1` and runs `claude -p ping` with
   `ANTHROPIC_BASE_URL` pointing at it.
2. The server answers `claude` itself, so nothing is sent to Anthropic and
   nothing is billed, and reads the credential out of the request.
3. The token is kept in memory only and reused for every usage request. If
   Anthropic answers HTTP 401, a fresh one is obtained and the request retried
   once. Nothing else triggers a new token.

This takes about a second, at startup and after a 401. If `ANTHROPIC_API_KEY` or
`ANTHROPIC_AUTH_TOKEN` is set, the application refuses to start the capture,
because `claude` would send that instead of its login token: unset it and restart.

## Usage history

Every successful refresh that has amounts adds a row to `java-aip-usage.csv` in the project root:

```
datetime,used,limit,currency
2026-10-08 16:24:53,186.02,1000.00,USD
2026-10-08 16:25:53,186.07,1000.00,USD
```

The time is your local date and time to the second, in the form Excel recognises as a date and
time, and it is the clock the window shows. The file does not name a zone, and the hour in which the clocks
go back appears twice. To open it in Excel use Data, From Text/CSV: the columns are separated by commas and the
numbers use a dot, so if your Excel uses a decimal comma, choose comma as the delimiter and the dot as the
decimal in that dialog. (I have not opened it in Excel myself.) The amounts are plain numbers with a dot and two decimals, no currency sign; the currency is its own column. A file from before that column (header `datetime,used,limit`) is upgraded in place when the next row is added: its rows get an empty currency, since the file never said. The file
is added to, never overwritten, and never rotated, so delete or move it when you like; at the default
interval of a minute it grows by about 60 KB a day. A failed refresh adds nothing, nor does a Pro or Max
account, which has no amounts. If the file cannot be written the log says so and the refresh still
counts as a success. It holds spending figures, so it stays on this machine and out of git.

## Logging

On the way out the program logs `Shutting down`, `Usage refresh stopped` and
`Frontend server stopped`, whether the window was closed or the process was told to stop
(`SIGTERM`, `SIGINT`).

Everything is written to the console and appended to `java-aip-usage.log` in the
working directory. The file is never rotated; delete it when it gets large.

```
2026-10-08T14:15:15.202Z INFO    [ApiHandler] Manual refresh requested
2026-10-08T14:15:15.804Z INFO    [UsageClient] GET api.anthropic.com/api/oauth/usage -> HTTP 200 in 602 ms (992 bytes, request-id req_011C…)
2026-10-08T14:15:15.805Z INFO    [UsageService] Usage refresh succeeded
```

It records startup and shutdown, each refresh and its outcome, token acquisition
and rejection, settings changes, and for each HTTP request the status, duration,
size, and Anthropic's `request-id` (useful if you ask support about a request).
Status polls from the window are not logged: they arrive every second.

It never records the token, any header, or any part of a response body. Beyond
not writing them in the first place, every log line passes through a filter that
masks anything shaped like a credential, so one that slipped into an error message
would still not reach the file.

## Known limitations

- **macOS only.** Windows and Linux are untested. A Go rewrite is the plan for
  Windows, which is why the frontend is kept independent of the Java backend.
- **Needs Claude Code.** There is no other way to sign in. The window helps when
  it is missing, but cannot fix it for you. Changing your `PATH` needs an
  application restart, because a running program keeps the `PATH` it started with.
- **Direct connection only, by design.** `HTTPS_PROXY` is deliberately not read,
  so a network that requires a proxy will show "Cannot reach api.anthropic.com".
- **The usage endpoint rate limits.** It seems to accept about one request a minute
  over the long run, after a burst of about ten. That is why the default is 60 s. A
  faster setting is allowed but can draw HTTP 429 after roughly ten minutes, and
  restarting the application repeatedly can trigger it sooner (each start fetches at
  once). The application then backs off: it shows "Next try in …" and waits longer,
  easing back to the normal pace as requests succeed. The refresh button is never
  held back. This is an inference from observed behaviour, not a published limit.
- **An undocumented endpoint.** `/api/oauth/usage` is the one Claude Code polls,
  not a published API. If its format changes you get an error naming the problem
  rather than wrong numbers, but you may need a parser update.
- **Plan windows are tested with fixtures only.** Verified live against a
  usage-based account; the Pro/Max view has not met a real Pro or Max account.
- **Not a locked-down server.** The local API is open to any program on the same
  machine, as it is on any loopback port; other web pages are refused (see
  [docs/api.md](docs/api.md)). Do not run it on a machine shared with people you
  do not trust.
- **A forced kill leaves no shutdown lines.** Closing the window, `SIGTERM` and `SIGINT` all
  shut the program down in the same way, and log it. `SIGKILL` cannot be caught by any program,
  so a process stopped that way just ends. I have not checked which signal an IDE's stop
  button sends.
- **One instance at a time.** Nothing stops two copies from starting; they would
  share `settings.json` and the log, and the last write wins.
- **A long list of plan windows wraps.** The strip is at most 900 px wide, so an
  account with many plan windows shows them on more than one line. The window is
  sized to its content and cannot be dragged to another size.
- **No packaged app.** `./gradlew run` is the way to start it. A macOS app bundle
  is deliberately left out of the first version.
- **No charts or history.** Only the latest reading is kept, in memory.

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/org/example/` | Application sources (`Main` window, `WindowFit`, `AppRuntime`, `LocalWebServer`, `ApiHandler`, `Logging`, `Redaction`) |
| `src/main/java/org/example/token/` | OAuth token acquisition via the Claude Code CLI |
| `src/main/java/org/example/usage/` | Usage model, parser, HTTP client, refresh service |
| `src/main/java/org/example/settings/` | Interval settings, `settings.json`, command-line options |
| `src/main/resources/web/` | Frontend: the strip with its log and history panel (`index.html`), with its CSS and JavaScript |
| `src/test/java/`, `src/test/frontend/` | JUnit tests, and Node tests for the frontend |
| `docs/` | Requirements, implementation plan, and the local API contract |
| `build.gradle.kts` | Build config — JDK 25 toolchain, JavaFX, JUnit 6 |
| `settings.json` | UI-edited settings, written at run time (git-ignored) |
| `java-aip-usage.log` | Append-mode run log, written at run time (git-ignored) |
| `java-aip-usage.csv` | The usage history, one row per refresh, written at run time (git-ignored) |

## Related repos

The sibling repositories next to this one are a source of knowledge and
reusable ideas — not a hard dependency. In particular, the token-fetching code
is adapted from `java-aip` into this repo rather than called across the
directory boundary:

| Repo | Why it is useful |
| --- | --- |
| [`java-aip`](../java-aip) | Endpoint behavior, response mapping, token patterns |
| [`java-claude-login`](../java-claude-login) | Login/auth flow references |
| [`java-claude-code-fetch-oauth-token`](../java-claude-code-fetch-oauth-token) | OAuth token retrieval patterns |
| [`java-claude-code-model-list`](../java-claude-code-model-list) | Data-fetching and output formatting examples |
| [`java-interceptor`](../java-interceptor) | Request/response interception and debugging |
| [`doc-and-ref-repos`](../doc-and-ref-repos) | Specs and reference material |
