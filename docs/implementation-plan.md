# Implementation plan

How the first version was built, phase by phase: the architecture, the classes,
the reasons for a choice, what went wrong on the way, and what the tests cover.

What the program must do is in [requirements.md](requirements.md), and only there.
This document does not restate it: where a phase changed what the person sees, it
names the section of the requirements instead. The local API is in [api.md](api.md).

**Where it stands: version 0.87 is built: 129 phases are built, the last six (124 to 129) not pushed yet and not looked at by a person; phase 128 was run against a few tests only, and phase 129 against none; the fault of the cost control closed with the settings was not found.** What phases 100 to 113 list "by eye" is still to be looked at, on Windows and on macOS; the folder picker has not been seen in a window at all. Of phases 91 to 99, the notes of `new-requirements.md`, phase 99 is not checked on macOS and leaves one open point, the name macOS shows in bold; the history of phase 94 and what each phase lists "by eye" are still to be looked at. One check is outstanding: the tests of phase 87 on a Mac.
Phases 83 to 88 are the six phases of [java-to-go.md](java-to-go.md), which changed the program so that it leans only
on what Go also has, for the port that [go-port.md](go-port.md) analyses; they changed how the program is built much
more than what it does. The architecture below and the notes at the end describe the program as it is after them.

## Scope

The first version is a Java 25 application, started with `./gradlew run`. It was written for macOS and is also run and
tested on Windows, where phases 83 to 89 were built.
Everything it has to do — the refresh rules, the window, the files, the command
line, the fake backend, and what is left out of the first version — is specified in
[requirements.md](requirements.md). The decisions confirmed in discussion, which
used to be listed here, are stated there, each in the section it belongs to.

## Architecture

A JavaFX WebView is the desktop shell, and a small HTTP server bound only to the
loopback interface serves the web UI to it. The UI talks to the application through
the JSON API of [api.md](api.md) and through nothing else, with one exception: the
page reports its content size, and which panel is open, to the Java host, which
resizes the stage and remembers the heights (phases 7 and 26). The reasons for the
host, for the contract and for keeping the resizing outside it are in the
requirements (Display requirements).

JavaFX is the only library the program is built with (since phase 83). What a library or a part of the Java runtime
used to do that Go has no like of is done by small classes of the program's own, the first six rows of the table.

The backend, by component:

| Component | Responsibility |
| --- | --- |
| `json`: `Json`, `JsonReader`, `JsonWriter` | The program's own JSON (phase 83): a value that keeps a number as it was written, a strict reader whose faults say where and what, and the two written forms, on one line for the API and over several lines for the settings file and a logged response. Each answer record of the API writes itself with a `toJson()`. |
| `money.Amount` | An amount of money as an exact decimal (phase 84): reading, adding and subtracting, the share of a limit, and the written forms. Nothing about money is worked out anywhere else, or with binary fractions. |
| `log.Log` | The program's own log (phase 85): a class says `error`, `warning`, `info` or `debug`, and the entry goes to every sink that listens, one at a time. |
| `Failure`, `Fault` | What went wrong, in a fixed set of texts (phase 86): the one place that looks at the kind of an exception. No message carries a text of the Java runtime or of the operating system. |
| `Cancel` | The way to tell a request that is under way to give up (phase 88), in the place of interrupting a thread. |
| `ProcessTree` | Ends `claude` and what it started with the system's own commands (phase 87). |
| `UsageSnapshot`, `Spend` and related value types | Represent the timestamp and the optional spend details of a reading. |
| `TokenProvider`, `ClaudeTokenProvider`, `CredentialCapture` | Acquire an OAuth token from the Claude Code CLI, and a fresh one after a rejection. Keep tokens in memory; do not log them. Stop waiting when told to give up. |
| `UsageClient`, `UsageFetcher` | Call the Anthropic usage endpoint with the bearer token, decode the response, and distinguish HTTP 401 from other failures; one retry with a fresh token. Stop waiting when told to give up. |
| `UsageService`, `RefreshSchedule`, `IntervalSearch` | Own the current snapshot, the interval and its search, the last error and stale state, and non-overlapping scheduled refreshes. One loop owns all of it and is handed what is asked as messages; a request runs beside it; what the window reads every second never waits for it (phase 88). |
| `Logging` | Open and close the two sinks of the log for a run: the console and the append-mode file `java-aip-usage.log`, written through after every line. In debug mode the console also shows the stack trace of a failure; the file never does. |
| `LocalWebServer` | Serve the bundled frontend, read-only status/log/history/error endpoints, the settings endpoint, and a manual-refresh action on loopback. |
| `Settings`, `SettingsStore`, `IntervalSettings` | All settings as JSON beside the CSV, created with defaults if missing; apply, validate and save them; hold the remembered panel heights. |
| `HistoryReader`, `HistoryDeltas`, `UsageHistory` | The CSV with its status, timing and failed rows; the differences between readings, worked out in the backend. |
| `Formatting`, `StatusDisplay` | Every finished text, tooltip and flag the window shows; the frontend does no arithmetic or formatting of readings. |
| `ErrorLog` | The in-memory errors of the run (every failed refresh, token problems included), newest first, read through a read-only endpoint. |
| `WindowFit`, `RememberedHeights` | Parse the size the page asks for (with its panel); decide the height the history and log open at, and when a dragged height is stored. The history's width comes from the page, which measures its own table (phase 27). |
| `Main` / application lifecycle | Parse CLI options, load settings, start services and the window, and terminate the program and all background resources when the window closes. |

## Phases

### 1. Confirm build and UI integration

- Configure the Java 25 Gradle toolchain and JavaFX dependencies.
- Add the application entry point and `run` task.
- Use JavaFX WebView for the minimal desktop window.
- Verify that the JavaFX WebView can load the frontend from the loopback
  server on macOS.

**Checkpoint:** the Gradle application launches a minimal JavaFX window and
can display a locally served test page.

**Status: done.** `./gradlew run` opens the window and the WebView loads the
placeholder page from `LocalWebServer`, which binds to an OS-assigned loopback
port. Closing the window stops the server and ends the process; server tests
cover loopback binding, content types, 404s, and path traversal.

Logging infrastructure was pulled forward from phase 6: `Logging` sends every
`System.Logger` record to the console and to an append-mode
`java-aip-usage.log` in the project root, falling back to console-only if the
file cannot be opened. Phase 6 still owns the refresh events and sanitized HTTP
diagnostics.

### 2. Model and decode usage data

- Add typed models for the usage snapshot, optional spend object, and window
  entries. The snapshot is the application's normalised view, not the raw
  response: the endpoint sends no `fetched_at`, so the application stamps it.
  Decode the real wire format (minor-unit amounts, windows as top-level keys),
  adapting the `java-aip` parser.
- Add JSON decoding and fixtures for usage-based and plan-based responses,
  including null spend, empty windows, and nullable fields.
- Reject invalid response shapes with an error that can be presented to the
  user.

**Checkpoint:** unit tests decode both example response shapes without
discarding fields needed by the UI.

**Status: done.** `org.example.usage` holds `UsageSnapshot`, `Spend`,
`UsageWindow`, `UsageParser` and `UsageParseException`. Fixtures are the three
`java-aip` responses (usage-based, plan, empty) in
`src/test/resources/fixtures/`. Which bodies are rejected, and which count as an
account that reports no usage, is in the requirements (The usage request).

### 3. Implement OAuth token acquisition

- Adapt the token-fetching implementation from `java-aip` into this
  repository's `TokenProvider`, preserving the no-runtime-dependency boundary.
- Ensure subprocesses, listeners, and other temporary resources are always
  closed, and surface actionable errors when the user is not logged in or a
  token cannot be acquired.

**Checkpoint:** tests cover successful token acquisition and the principal
unavailable/login failure paths using controlled test doubles.

**Status: done.** `org.example.token` holds the `TokenProvider` interface (so
phase 4 can use doubles), `ClaudeTokenProvider`, `CredentialCapture` and
`TokenException`. It runs `claude -p ping` against a loopback server that
answers itself, so no real request is made, and reads the credential from the
request. Every `acquire()` call is a fresh capture; caching and the 401 retry
belong to phase 4.

Failures are typed by `TokenException.Reason` (`NOT_INSTALLED`,
`NOT_LOGGED_IN`, `ENV_CONFLICT`, `TIMEOUT`, `CAPTURE_FAILED`, `INTERRUPTED`),
each with a message written to be shown in the GUI. `ANTHROPIC_API_KEY` or
`ANTHROPIC_AUTH_TOKEN` being set is refused up front, because `claude` would
send that instead of its OAuth token. The CLI, its child processes and the
capture server are always stopped, and the token is never logged. Subprocess
output is logged on failure with credential-shaped text redacted.

Differences from `java-aip`: the capture server takes an OS-assigned loopback
port instead of probing 9000-9100, and `ClaudeLocator` is replaced by a small
PATH check made only when the launch fails, to tell "not on the PATH" (with
advice, including that a changed PATH needs a restart) from "found but cannot be
run" (with the system's reason). Tests run a
stand-in for `claude` (`FakeClaude`) as a real subprocess making real HTTP
requests. The real CLI is not exercised by the automated tests.

### 4. Implement usage requests and refresh state

- Add an HTTP client with connection and request timeouts.
- Send the OAuth token as a bearer token to the usage endpoint.
- Prevent overlapping refreshes and cleanly stop scheduled work on shutdown.

**Checkpoint:** tests verify normal fetches, a 401 followed by one token
refresh and retry, repeated 401 handling, non-401 failures, and preservation of
the last successful snapshot. Scheduler tests verify interval changes while a
fetch is idle and in progress, including due times that have already elapsed.

**Status: done.** In `org.example.usage`:

| Class | Role |
| --- | --- |
| `UsageClient` | One `GET` with a bearer token, 10 s connect and 20 s request timeouts. Redirects are not followed, so a token cannot be forwarded to another host. Failures become `UsageFetchException` with a user-readable message that never contains the token or body. |
| `UsageFetcher` | Fetches a token once and reuses it. A 401 drops it, acquires a fresh one and retries exactly once. A second 401 is reported and the token dropped. No other failure replaces the token or is retried. |
| `RefreshSchedule` | The timing rules as pure arithmetic on monotonic nanoseconds, with no threads. |
| `UsageService` | One refresh thread and the published `UsageState`. `refreshNow()` returns at once and declines while a fetch is running or already requested. `close()` interrupts a fetch in flight and joins the thread. |
| `UsageState` | Latest good snapshot, latest error, `stale()` and `refreshing`. |

Range validation (5-3600 s and 1-60 s) is not in the service, which accepts any
positive interval; it belongs to the settings and API layer in phase 5.

**Rate limiting (added after a live run).** Restarting the application about eight times
in 15 minutes made the endpoint answer HTTP 429 with `retry-after: 0`, and the service
kept retrying every 30 s. It now backs off, by the rule in the requirements (Refresh
behavior). The policy is the pure class `Backoff`, with the 5 minutes as
`DEFAULT_MAX_BACKOFF`. `RefreshSchedule` takes the longer of the interval and the
hold; `UsageService` extends the error with "Next try in N min". `UsageClient`
passes `Retry-After` on, only when it is whole seconds above zero.

*Why easing, not lifting.* The first version dropped the hold at the first success. A
live log then showed the pattern 429, 200, 429, 200: after a success the next request
went out at the interval that had just been refused. The endpoint appears to allow
about one request a minute after a burst of around eight (an inference from that log,
not a published limit). Modelling it, `BackoffPolicyTest` compares the policies over an
hour at a 30 s interval: lifting or halving wastes about 29% of requests on 429s,
a quarter off 23%, an eighth off 14%, a fixed 5 s about 11% but slowly to recover. An
eighth gets the readings the server allows with the fewest refusals and still recovers
from a 5 minute hold in about 18 successes. The test fails for any of the weaker
steps, so the constant cannot drift unnoticed. At a 60 s interval the model never
refuses at all, which is why the default became 60 s in phase 8; the back-off now only
has to make a faster pace, which is still allowed, tolerable.

Not covered by tests: the real endpoint and real `claude`. TLS is left to the JDK
defaults. Proxy support (`HTTPS_PROXY`, as in `java-aip`'s `EnvProxySelector`) is
deliberately not included, by decision.

### 5. Add local API, settings, and UI

*Phase 8 changed two things described here: the usage-fetch default is 60 s, and the
UI polling interval is a command-line option only, with no frontend control and no
saved value. The text below is as built in phase 5.*

The plan for this phase was the local API, the two interval settings and the first
page, as the requirements described them at the time; what was built is below.

**Checkpoint:** tests cover both interval settings and API behavior: UI startup
loads the effective poll interval, frontend interval changes are validated and
persisted, status polling does not fetch Anthropic usage, manual refresh
returns immediately and triggers a backend fetch, and repeated manual
requests do not overlap an active refresh. A macOS smoke test confirms both
response types render, refresh errors are visible without removing the last
successful data, the UI remains open and allows a retry when Claude Code is
missing or unauthenticated, and closing the window terminates the program.

**Status: implemented; the macOS smoke test is still to be done by a person.**
Everything below is covered by automated tests, 200 Java and 35 Node, except
what needs the real window, the real `claude`, or the real endpoint.

- **Settings** (`org.example.settings`): `IntervalSettings` resolves each
  interval as command line, else `settings.json`, else default. A value
  committed in the UI is validated (5-3600 s and 1-60 s), saved, and applied at
  once, replacing the command-line value for the run. A command-line value is
  never saved on its own. If the file cannot be written, nothing changes and the
  UI is told why. `SettingsStore` writes atomically and reads forgivingly: a
  damaged file or value is logged and ignored, never fatal. The file holds only
  the two intervals. `LaunchOptions` parses `--usage-interval` and
  `--poll-interval` (and `--help`) before anything starts, so a bad value fails
  at once with exit code 2.
- **API** (`ApiHandler`, `LocalWebServer`): documented in
  [api.md](api.md). Status polling never fetches; `POST /api/refresh` returns at
  once and declines while a fetch is running. Requests with a foreign `Host`
  header are refused, and POSTs must be JSON, so no other web page can drive
  the API.
- **Wiring** (`AppRuntime`, `Main`): the runtime is assembled without JavaFX so
  tests can drive it end to end over HTTP with a stand-in for the fetch. The
  first fetch starts at once; closing the window stops the refresh service
  (interrupting a fetch or `claude` in flight) and then the server.
- **Frontend** (`web/`): `view.js` holds all the logic as pure functions;
  `app.js` is thin DOM glue that assigns backend text only with `textContent`.
  Spend and plan-window views show only what the response shape carries; stale
  data stays visible under a banner naming the error; the refresh button is never
  disabled. `./gradlew frontendTest` runs the Node tests, including `app.js`
  against a fake DOM and backend.

**Found while testing:** on macOS JavaFX starts a non-daemon keep-alive thread
before `main`, so a `main` that returns without calling `System.exit` leaves the
JVM running. `--help` hit this; a test now launches the real `Main` and checks it
exits.

**Smoke test still to do on macOS** (needs the real window and a logged-in
Claude Code): both response types render, a refresh error leaves the last
reading visible, the window stays open and the refresh button retries when
Claude Code is missing or not logged in, and closing the window ends the process.

### 6. Run, package, and document the first version

- Confirm `./gradlew run` and the IDE run configuration still work once the
  real backend is wired in. (`./gradlew run` and frontend loading are already
  done in phase 1.)
- Extend the existing console and `java-aip-usage.log` logging (done in
  phase 1: appended in the project root beside `gradlew`, not rotated) to
  record every refresh success and failure and detailed but sanitized HTTP
  diagnostics. Verify that tokens, credentials, and sensitive request/response
  content are never logged.
- Add macOS run instructions and document configuration, token
  prerequisites, and known limitations.
- Update the README layout and requirements links to match the completed
  scaffold.
- Defer a distributable macOS app bundle.

**Checkpoint:** `./gradlew build` and the targeted tests pass, and the app can
be launched with `./gradlew run` on macOS.

**Status: done.**

- **Run:** `./gradlew run` launched the real window with the real backend several
  times on macOS, against a usage-based account. The JavaFX native-access warning
  the JDK printed on every start is gone: `applicationDefaultJvmArgs` passes
  `--enable-native-access=javafx.graphics,javafx.web`.
- **Logging:** the console and `java-aip-usage.log` already carried lifecycle and
  refresh events. Added: a sanitized HTTP line per request (status, duration,
  size, Anthropic's `request-id`, and `retry-after` when it is a number; header
  values of any other shape are left out, and the body and other headers never
  appear), and `Redaction`, applied to every log line and stack trace as a safety
  net against anything shaped like a credential. `LoggingEndToEndTest` runs the
  real wiring over real HTTP with secrets planted in the request, response and
  error body, and checks the file and the console for the lifecycle, refresh and
  HTTP events and for the absence of every secret. Deliberately logging a body or
  a token makes it fail, except that a logged token is caught by `Redaction`
  first, which is the point.
- **Docs:** the README now has the token explanation, run instructions for macOS
  and the IDE, a logging section, and known limitations. The app bundle stays
  deferred.

**Left open on purpose** (see the README's known limitations): a live check of the
plan-window view against a Pro or Max account, and Windows and Linux.

**Decided against:** `HTTPS_PROXY` support. The application connects directly.

**Found later: running `Main` from the IDE failed.** IntelliJ's ▶ next to `main` stopped
with "Error: JavaFX runtime components are missing, and are required to run this
application". The README offered that route, and it had never been tried. Cause: `Main`
extended `javafx.application.Application`, and Java refuses to start such a class from
the plain classpath; `./gradlew run` hid it because the JavaFX Gradle plugin puts JavaFX
on the module path for the `run` task alone, which IntelliJ's own task does not get.
Fix: `Main` is now a plain class (command line, logging, then
`Application.launch(UsageApp.class, args)`), and the window moved unchanged into the
new `UsageApp extends Application`. This works from the classpath and the module path
alike. `MainCommandLineTest` now starts `Main` both ways, and was seen to fail with
that exact message before the fix. A second consequence: the window's log lines now
read `[UsageApp]`, not `[Main]`, and `--help` no longer needs an explicit
`System.exit`, since nothing starts JavaFX early. Run from the classpath the JVM prints
two warnings (unsupported configuration, native access) that are harmless; see the
README.

### 7. Compact window

*Phase 9 reorders the row (time first), drops the currency sign, and adds a countdown
and tooltips. The text below is as built in phase 7.*

**Status: implemented; the macOS smoke test is still to be done by a person.**
Requirement: [Compact window](requirements.md#compact-window).

The plan for this phase was the one-row page, the time of day only, a config button
in the row, the message line and a window that fits its content, as the requirements
described them at the time; what was built is below.

**What was built.** `view.js` produces a compact model (time of day only, spent and
budget with percent and severity colour, plan windows with time remaining, one
message line). `index.html` and `app.css` lay it out as one strip, with SVG icon
buttons and a 15 px bold font (700; 800 for the percentage and amounts), nothing
below 14 px. `app.js` runs the
config flow: the toggle opens two fields, Enter or the confirm button validates
both and sends only the values that changed in one request, and the fields close on
success; a bad value, or one the backend refuses, keeps them open with a message;
Escape or the toggle closes them unchanged. `#app` takes its width from its content,
and `window.contentSize()` reports it. `WindowFit` and `Main` poll that every 150 ms
and resize the stage, which is no longer user-resizable. The page-to-host contract
is in [api.md](api.md#window-host-contract). A guard against resize loops is
structural, not a counter: the size reported is the page's own, so applying it
cannot change it; the host also clamps the size and never applies one twice.

**Checkpoint:** Node tests cover the time-only formatting, the compact windows text,
the config open, confirm, invalid and cancel flows, and the message line. A macOS
smoke test confirms the normal state fits one row at the target size, the window
grows and shrinks around the config fields and messages, and the text is readable.

### 8. A 60 s default, and the update interval off the settings

**Status: done.**

*Why.* Runs at 30 s drew HTTP 429 after about ten minutes. Two clean stretches at 60 s
(13 requests, then 18 more with the Mac held awake) drew none, with every request 59
to 61 s apart. That is consistent with the endpoint accepting about one request a
minute over the long run. It is evidence, not proof: 17 continuous minutes cannot rule
out a slower limit that only shows after hours.

*What changed.*

- **Default.** The usage-fetch interval defaults to 60 s (`IntervalRange.USAGE`). The
  range stays 5 to 3600 s, and a faster pace is still allowed; the back-off from
  phase 4 still protects it.
- **The update interval is no longer a setting.** It is 1 s, or whatever
  `--poll-interval` says for one run (1 to 60 s), and nothing changes or saves it.
  `IntervalSettings` keeps it as a fixed value and only the usage interval can be
  updated (`updateUsage`). `SettingsStore` keeps one key, `usageIntervalSeconds`; a
  file from an earlier version that still has `pollIntervalSeconds` is read for the
  usage interval only, and the old key disappears the next time the file is written.
- **API.** `POST /api/config` takes only `usageIntervalSeconds`. A body that gives
  `pollIntervalSeconds` is refused with a 400 that names `--poll-interval`, and
  nothing in it is applied, valid half included, rather than being silently ignored.
  `GET /api/config` still reports `pollIntervalSeconds`, because the page needs it to
  know how often to poll, but its `limits` now cover the usage interval only.
- **Page.** The config area is one field, `fetch`, and the page sends only that.
  Changing it does not touch how often the window polls.
- **Tests and docs.** The settings, API and frontend tests were rewritten for this,
  and the requirements, README and API contract updated.

*Side effect to know about.* An existing `settings.json` that carried a saved update
interval no longer has any effect: the window polls every second unless started with
`--poll-interval`.

**Checkpoint:** the Java and Node suites pass, and each new rule fails a test when
broken: the 60 s default, the API refusing an update-interval change, and the page
never sending one.

### 9. Row order, countdown and tooltips

**Status: done; the macOS check of how it looks is still to be done by a person.**
Requirement: [Compact window](requirements.md#compact-window), the row, Countdown and
Tooltips parts.

*As built.* `RefreshSchedule.nanosUntilNextRefresh` gives the time to the next due
refresh: the last trigger plus the longer of the interval and the back-off, less now. It
is negative once overdue, keeps counting while a request runs, treats a pending manual
request as due now (never later than already overdue), and is empty before the first
trigger. `UsageService.secondsUntilNextRefresh` rounds it to the nearest second, halves
up, and `ApiHandler` puts it in `/api/status` as `nextRefreshInSeconds`. On the page,
`view.js` has `formatAmount` (no currency sign), `formatDateTime` (`8 Oct 2026,
14:24:53`, the same in any language) and `describeCountdown` (`42 s`, `-3 s`, none for
`null`). The row is time, percentage, used, limit, plan windows, refresh button,
countdown, config. The countdown has a minimum width, so the window is not resized as
its digits change. The window is titled `aip usage v0.01`, built by `AppInfo.windowTitle()` from the name and
the version, which is one hand-written constant (`AppInfo.VERSION`); the page inside it is
titled with the name alone, so a new version means changing one line.
One addition beyond the requirement: the percentage's tooltip says
what it is and the severity, since the colour alone carried that.

- **Backend: the countdown.** The status gains `nextRefreshInSeconds`, an integer that
  may be negative, or `null` before any request has been triggered. It is the time the
  next scheduled request is due, minus now: the most recent trigger plus the longer of
  the interval and the back-off, rounded to the nearest second. `RefreshSchedule` gets a
  method for it that, unlike `nanosUntilDue`, does not stop at zero and does not give up
  while a request runs, so an overdue refresh counts below zero. A pending manual
  request is due now. `UsageService` exposes it, computed when the status is read, since
  it changes with time and so cannot sit in the published `UsageState`; `ApiHandler`
  adds it to the response. A manual refresh restarts it, a back-off lengthens it, and an
  interval change moves it, because all of those move the same due time.
- **Frontend: the row.** `view.js` produces the new row: the time first, the percentage,
  the two amounts as separate numbers formatted without a currency symbol, and the
  countdown as `42 s` or `-3 s`, empty when the backend sends `null`. `index.html` and
  `app.js` follow, with each amount in its own element so each can have its own tooltip.
  The window shows the countdown as received and does no countdown of its own.
- **Contract and docs.** `docs/api.md` documents `nextRefreshInSeconds`. The README's
  window section shows the new row.
- **Tests.** `RefreshScheduleTest` and `UsageServiceTest` cover the countdown: the value
  right after a trigger, after a manual refresh, during a back-off, after an interval
  change, negative when overdue, and `null` before the first request. `ApiTest` checks it
  is in the status and never in the config. Node tests cover the row order, no `$` in
  the amounts, the `42 s` and `-3 s` text, the empty countdown, and every tooltip.

**Checkpoint:** the Java and Node suites pass, each new rule is seen to fail a test when
broken, and a macOS check confirms the row, the countdown moving and going negative on a
slow request, and the tooltips.

### 10. Clean shutdown when the process is stopped

**Status: done.**

*Why.* Cleanup ran only when the window was closed. A process told to stop (`SIGTERM`, `SIGINT`)
just ended: no `Shutting down` lines in the log, and the refresh service and web server were not
closed, nor a `claude -p ping` that happened to be running. An IDE's stop button may do the same.

*What was built.* `ShutdownHook` installs a JVM shutdown hook that runs the same cleanup and then
closes the log, in that order and in one hook, since hooks run side by side in no set order.
`RunOnce` makes the cleanup run exactly once however it is reached: `UsageApp.stop()` after a window
close, and the hook after a signal, with a second caller waiting for the first. `Logging.close()` is
safe to call twice, since the hook may close what the normal exit has closed.

*Two things went wrong on the way.* Both were found by running it, not by thinking about it.

- **The JDK closes the log first.** With the hook in place a test that sends the process `SIGTERM`
  still found the shutdown lines missing every time. The JDK's own `LogManager` shutdown hook calls
  `reset()`, which closes every log handler, and it won. `ShutdownSafeLogManager` makes `reset()` do
  nothing, and `Logging` closes its own handlers last. The JDK reads the manager from a system
  property once, on first use, so `Logging.install` sets it before anything logs, and warns, in the
  log file and on the console, if logging was already in use.
- **A real app failed where the test passed.** The warning above showed that the application had
  used logging before `Logging.install`: `Main` called `UsageApp.configure(...)` first, and merely
  loading `UsageApp` creates its logger. `Main` now hands `UsageApp` nothing, and `UsageApp` reads
  its options from the launch arguments when it starts.

*Tests.* `RunOnceTest`; `ShutdownHookTest`, which runs the real hook in a child JVM through
`ShutdownProbe` and stops it with `SIGTERM` (six times, since a race would show only sometimes) and
with `SIGINT`, and checks the lines, their order and that the lock file is gone, plus the normal
exit logging once; and a stronger `LoggingTest` for double close. Each rule was seen to fail a test
when broken. A live check stopped the real application with `SIGTERM` and found the three lines.
The tests cannot see the `Main` ordering above, which is why the warning exists.

**Not done.** `SIGKILL` cannot be caught. Which signal IntelliJ's stop button sends has not been
checked.

### 11. Tighter icon, red errors, a fresh interval on open, and the requirements brought level

**Status: done.**

- **Spacing.** The refresh icon sat about 15 px from the amounts before it: the strip's 10 px gap
  plus about 5 px of empty room inside its 24 px button. `#refresh` is pulled in by 7 px, which
  leaves about 8 px between the number and the icon, close to the 7 px that the countdown already
  sits from the icon on its other side. The pull stays under the 10 px gap, so the button's box
  never covers the number.
- **Error text is red.** The failed-refresh message that comes with figures still on show was amber
  (`--warn`), and only the no-figures one was red. Both are red (`--bad`) now, as are the
  lost-contact banner and an invalid config value, and no message is amber.
- **A fresh interval on open.** The page read the usage interval once, at startup, so a change made
  elsewhere never reached the config field. `openConfig` now asks `GET /api/config` each time and
  falls back to the last value known if the backend cannot be reached. It also refreshes the limits and
  the value that "unchanged" is judged against, so confirming what the backend now has sends
  nothing, and confirming the old value sends it. Nothing is pushed to a field that is already open.
- **Requirements audit.** Reading `requirements.md` against the application found drift, all fixed:
  the old Display requirements still demanded `currency` and `severity` as displayed fields (now a
  tooltip and a colour); the size said about 400 x 50 when it is about 330 x 35; and the application
  did several things no requirement covered: the `Loading… / No data / No usage reported` states, dimmed
  stale figures, the lost-contact banner, the percentage tooltip with its severity, the window being
  fixed to its content and wrapping past about 900 px, the environment-variable refusal and the
  not-on-the-PATH message, the logging detail and masking, the local server's protections, and starting
  from an IDE. The Future extension list still stands.
- **Tests.** Node tests cover the red errors, the icon's pull, and re-reading the interval on open (it
  changed, the fetch fails, confirming what the backend now has, and confirming the old value). Each was seen
  to fail when broken.

**The usage history (CSV).** Requirement: Usage history. As first built the file had
three columns, `datetime,used,limit`, and an ISO 8601 time in UTC.

- *Decided by you:* a row for every successful refresh, and the three columns `datetime, used, limit`.
- *Assumed, because the questions went unanswered, and easy to change:* the date and time are ISO 8601 in
  UTC to the second (**changed in phase 12 to a local time Excel reads**); and a plan account's reading, which has no
  amounts, writes nothing. The file name and place (project root, beside the log), the header written only
  for a new or empty file, and never rotating it, were also my choices.
- *Built.* `UsageHistory` (in `org.example.usage`) appends one row: it truncates the time to the second,
  formats the numbers with `BigDecimal` to two decimals so the machine's language and zone cannot change
  them, leaves a missing amount empty, and writes nothing for a reading without a spend. `AppRuntime` wraps
  the fetcher so that each reading it returns is added; a failure to write is logged and swallowed, because
  the reading is good and the refresh did succeed. `UsageApp` supplies the path, and `.gitignore` lists it.
- *Tests.* `UsageHistoryTest` covers the file format, the header rule, the time and number formats under
  another zone and language, empty amounts, zero, and a reading with nothing to write. `ApiTest` runs the
  whole application against a stand-in fetch and checks the rows, one more for a manual refresh, none for a
  failure or a plan reading, and that an unwritable history leaves the refresh a success. Each rule was seen
  to fail a test when broken.
- *Not done.* Nothing reads the file back; charts remain a future extension.

### 12. An Excel-friendly date, a log window and a usage-history window

**Status: done; how it looks in the window is for a person to judge.**

- **The CSV.** It was already in the project root and already appended to. The date and time are now
  `yyyy-MM-dd HH:mm:ss` in the machine's local zone, which Excel recognises as a date and time, where the
  earlier `2026-10-08T14:24:53Z` it would leave as text. Local, so it is the window's clock; the price is that
  the file names no zone and the hour when the clocks go back appears twice, which the requirement says. `UsageHistory`
  takes the zone as a parameter, so a test is not at the mercy of the machine's. The three rows the file already held,
  all from test runs, were converted once by hand (a backup was kept), since two formats in one column would
  break an import. Not opened in Excel here; the requirement says how to import with a decimal comma.
- **Two buttons at the right-hand end** of the row, after the config button and its field: the log and the
  history, each an icon with a tooltip. Each calls `window.open` on a page of the application's own server
  (`log.html`, `history.html`), so the page stays the same for any host that can open a window.
- **The windows.** (The history window was replaced by a panel in phase 13.) `PopupWindows` installs the web view's popup handler: a new, resizable `Stage` owned by the main
  one, with a web view of its own, titled from the page. Owned, so it closes with the main window, and closing
  the main window ends the program even with one open. `AppFiles` carries the three file paths to where they are needed.
  The log page shows the tail; the history page a table.
- **The endpoints.** `GET /api/log` gives the last 1,000 lines, reading at most 512 KB from the end (`LogTail`: a cut
  line, or a character cut in two, is dropped so none is shown half). `GET /api/history` gives the newest 1,000
  rows (`HistoryReader`), **sorted by `datetime` descending, not just reversed**, skipping any line without three
  columns. Both are read-only, name the file and never its path, and add nothing to the log or the history.
- **View logic.** `view.js` gains `describeLog` and `describeHistory`: the notes ("Showing the newest N of M
  rows"), and which columns line up left or right. The two page scripts only fetch and fill, with `textContent`.
- **Tests.** Java: `UsageHistoryTest` (the format, zones, DST, Excel's shape), `HistoryReaderTest` (the sort, the
  limit, damaged lines), `LogTailTest` (the cuts, a character split by the byte limit), `ApiTest` (both endpoints, the
  cuts, no path). Node: the view logic, both pages against a fake DOM and backend, the buttons and their place, the CSS
  (no text under 14 px, errors red). **`PopupWindowsTest`** opens real windows in a child JVM with a stand-in for the network,
  presses both buttons by running the page's own script, and checks the titles, the owner, the log text and the history
  rows in order, and that closing the main window ends the program. It puts windows on screen for a few seconds, so
  it runs only when asked: `./gradlew test --tests '*PopupWindowsTest' -Daipusage.windows=true`. Each rule was seen to fail
  a test when broken, the popup handler included.
- **Along the way.** The first version of the probe judged "the program ended" by whether a thread called
  `JavaFX Application Thread` was alive, which on macOS it stays after JavaFX exits; it now starts through
  `Application.launch` as the application does, and the test judges by whether the process really ends.

**Not done.** The log window does not update by itself, only on Reload, and does not colour warnings. Another
click opens another window; none is reused. How the two windows look has not been seen.

### 13. The usage history inside the main window

**Status: done; how the panel looks in the window is for a person to judge.** Requirement:
[The usage history panel](requirements.md#compact-window).

The history button no longer opens a window. It makes the main window taller and shows the recorded
data below the first row, small and condensed like a log file; pressed again, it hides it and the window
shrinks back. This **replaces the history window of phase 12**; the log window stays as it is.

*What changes when it is built.*

- **Removed:** `history.html`, `history.js`, the history rows of `popup.css`, `describeHistory`'s
  window-shaped output, their tests, and the history half of `PopupWindowsTest` (the popup count there
  falls from two to one).
- **Kept:** `GET /api/history`, `HistoryReader`, the sort by `datetime` descending, `UsageHistory` and the
  CSV format. Only the page that shows them changes.
- **The panel.** In `index.html`, below the strip and its message lines, a `hidden` element holding a
  scrolling box with one line per reading. `app.js` toggles it with the history button (`aria-expanded`, and
  a tooltip that switches between `Show the usage history` and `Hide the usage history`), fetches
  `/api/history` when it opens, and refreshes it when a new reading arrives (the status carries `fetched_at`,
  so a change of it is the signal; no extra polling), keeping the scroll position.
- **The window follows by itself.** The host already resizes the stage to what `window.contentSize()`
  reports, so the window grows when the panel appears and shrinks when it goes, with no host change. The
  panel has a fixed height, so the size changes once, not with each reading.
- **Style.** About 12 px, monospace, tight line height, regular weight, in a box about twelve lines tall that
  scrolls. The layout test that forbids text under 14 px gets one named exception for this box and no other.
  The `[hidden]` rule already makes the panel really disappear.
- **Tests.** Node: the toggle shows and hides it and switches the tooltip and `aria-expanded`; the lines
  are in the order the backend sent, newest first; each line has the date and time then `used / limit`; a new
  reading appears at the top and the scroll position stays; it can be opened with no history, with a damaged
  file, and when the backend cannot be reached; text goes in with `textContent`. Layout: the panel is below
  the strip, hidden at the start, a fixed height, small and condensed, and the exception is the only one.
  `PopupWindowsTest`: one popup, not two.

*Assumed, easy to change:* newest line first, as the earlier table was; about 12 px and about twelve lines
visible; the line `datetime  used / limit` in the file's own form; the panel updates by itself while open;
and the panel sits below any message line. The button's tooltip switching to `Hide…` is mine.

### 14. The history window twice as tall, and resizable in height

**Status: done; how it feels to drag is for a person to judge.** Requirement:
[The usage history panel](requirements.md#compact-window). Replaces the fixed twelve-line height of phase 13.

- **Page.** `#top` wraps the strip and the message lines; `#history` fills the rest of `#app`, which fills the
  window while the panel is open (a flex column). `window.contentSize()` reports `w,h,r`: with the panel
  open the height is twice the height of `#top` and `r` is `1`; closed, `r` is `0`. It never reads the
  height of `#app`, which follows the window, so dragging the window does not change the report.
- **Host.** `WindowFit.Size` gains `heightResizable`. `UsageApp.resize` makes the stage resizable only when
  it is set, with the width pinned (min = max) and the height free; the host resizes only when the report
  changes, so a dragged height is kept until the panel closes, opens again or a message line comes or goes.
- **Tests.** Node: the report doubles when open and says resizable; layout: no fixed height, the panel
  flexes; `WindowFitTest`: the third field; the real-window probe: open height is twice the closed one.

*Assumed:* resizable only while the panel is shown; the minimum height is the doubled size.

### 15. Five times as tall, a fixed width, and the log as a panel

**Status: done; how it looks and drags is for a person to judge.** Requirements: [Compact window](requirements.md#compact-window),
The log panel and The usage history panel.

- **Height.** Opening a panel makes the window five times the row's height (phase 14 made it twice).
- **Width.** `.note` and the other message lines get `width: 0; min-width: 100%` like the history, so they
  wrap to the row's width and add height only.
- **The log as a panel.** The log button toggles a panel in the same area as the history; one shown at
  a time. `GET /api/log` stays; its lines are shown newest first; it is read again when its file grows
  (the response gains a size or line count to compare, no extra request kinds). Removed: `log.html`,
  `log.js`, `popup.css`, `PopupWindows`, `PopupProbe`/`PopupWindowsTest`'s popup half, `describeLog`'s
  window shape. The closing-main-ends-program rule loses its second window.

*Assumed:* "5 times the size of the width" means five times the row's height (five times the width would be
about 2,000 px); "handled like the csv files" means shown in the window like the history (the file
itself is already handled that way); newest first for the log; one panel at a time.

### 16. Currency column, a spread-out table, ten times as tall, the newest reading at startup, a wide log

**Status: done; how it looks and drags is for a person to judge.**

- **Currency.** `UsageHistory` writes `datetime,used,limit,currency`, and upgrades an old three-column file in
  place on its first append (temp file, then move). `HistoryReader` reads three or four columns and always
  returns four.
- **Table.** The panel gets rows of cells: `describeHistory` returns a header and rows of cells, `describeLog`
  rows of one cell. CSS grid spreads four columns over the width; the header sits above the scrolling box.
- **Size.** Ten times the row height for both panels; the log also three times the row width. The page
  reports `w,h,r` with `r` 0 none, 1 height, 2 height and width; the host pins the width only for 1.
  `WindowFit.MAX` grows to fit.
- **Startup.** `UsageHistory.latest()` builds a snapshot from the newest row; `UsageService.restore` puts it
  in the state before the first refresh. A row with no amounts restores nothing.

*Assumed:* "double the height" means twice what it was, so ten times the row; "the table" is the history
table; "the news entry" is the newest entry; a startup reading is not dimmed unless the refresh then fails.

### 17. Mark where each run starts: log lines, a `startup` CSV column, gray rows

**Status: done (version 0.02); how the gray looks is for a person to judge. Not covered by a test: the two new log
lines (the path line is logged from `AppRuntime.start`) and the `mark` class on the page.** Requirements: Non-functional requirements, Usage history, The log panel, The usage
history panel.

- **Log lines.** Make the first line of a run `Starting java-aip-usage <version>` (today `UsageApp` logs
  `Starting java-aip-usage` without it), and log `Usage history is written to <absolute path>` straight after,
  where `AppFiles` has resolved the CSV. Both go through the redacting formatter as usual. The path is logged
  only; no API response carries it.
- **CSV column.** `UsageHistory` writes `datetime,used,limit,currency,startup`. It keeps an
  in-memory flag, unset at construction; the first `append` that actually writes a row writes `1` and sets the
  flag, every other row writes an empty field. Failed refreshes and amount-less readings write nothing, so
  they leave the flag alone. The in-place upgrade (temp file, then move) now handles two old headers,
  `datetime,used,limit` and `datetime,used,limit,currency`, giving old rows an empty currency and/or `startup`.
- **Reader and API.** `HistoryReader` accepts three, four or five columns and returns each row with a
  `startup` boolean (true only for `1`). The history response carries it per row; rows with fewer columns than
  `datetime,used,limit,currency` still count as damaged and are left out. `UsageHistory.latest()` ignores it.
- **Log marking.** `LogTail` stays dumb about lines. `describeLog` in `view.js` flags a line as a run start
  when its message (after the timestamp) begins `Starting java-aip-usage`, and `describeHistory` flags rows
  from the `startup` field. `app.js` adds a `mark` class to those cells/rows and `app.css` gives it a light gray
  background across the full line (grid row for the history, the line box for the log). The `startup` column
  is not rendered.
- **Tests.** `UsageHistoryTest`: first row marked, second not, a failed/empty reading does not use up the
  mark, a new instance marks again, both old headers upgrade. `HistoryReaderTest`: five columns, `startup`
  flag, old files. `LoggingEndToEndTest`: the two startup lines, in order, with a full path. `view.js` tests
  for the two describe functions flagging the right lines. Bump the hand-written version constant.

*Assumed:* "the first line after startup" in the log is the startup line itself; each run is marked, not just
the latest; the marker for a log line is its text, since the log is plain lines and the requirement keeps
it so; the history API may grow a field though it reveals no path.

### 18. All settings in `settings.json`, with a backend for them

**Status: done (version 0.03).** `/api/config` is kept, reading and writing the interval through the same code, because
the window still uses it; it goes in phase 23 with the config field. The log line for the settings path is
`Settings are stored in <path>`. The upgrade of an old history file used a move that deletes the old file first, so a reader arriving then saw no
file; that made `theHistoryEndpointGivesTheRowsNewestFirst` fail now and then, and it is fixed here with an atomic move. Requirements: Settings, Refresh behavior, Non-functional requirements (the settings file).

- **Model.** A `Settings` record in `settings/` with the eight settings of the requirements table, their
  defaults, and a `Settings.defaults()`. `SettingsStore` reads and writes the whole record as JSON in the file
  beside the CSV (`AppFiles.settings`, already there). A missing key takes its default; an unknown key, such as the
  old `pollIntervalSeconds`, is ignored and not written back; a file that cannot be read or parsed is logged and
  the defaults are used, and the file is left alone until a value is applied. Writing stays temp file then move.
- **Startup.** If there is no file, create one with the defaults and log that, then log its full path in a line of
  its own, next to the CSV path line in `AppRuntime.start`.
- **Interval.** `IntervalRange` keeps 5 to 3600 for the command line and the file; the five dropdown values
  (60 to 300 in steps of 60) live in the settings API response as `intervalChoices`, so the frontend does not
  hard-code them. The command-line value still wins until a value is applied, as now.
- **API.** `GET /api/settings` returns every value, the choices and the defaults; `POST /api/settings` takes the full
  set, validates all of it, applies the interval to the service as `setInterval` does today, saves, and returns
  what it now holds; a bad value is a 400 and nothing is saved. `/api/config` goes (the settings view is its
  only client). A settings change is logged as it is now.
- **Tests.** `SettingsStoreTest`: defaults, a partial file, an unknown key, an unreadable file, creation and its log
  line, round trip. `ApiTest`: get, apply, a rejected value changing nothing, the interval taking effect.

### 19. The history file: `status`, `interval`, `duration_ms`, and rows for failed queries

**Status: done (version 0.04); how the red `failed` and the gray rows look is for a person to judge.** The timing is
taken in `AppRuntime`'s recording wrapper around the whole fetch, so it includes getting a token and the one
retry after a 401; a query cut short by the program stopping writes no row. `UsageHistory` has `append(snapshot,
interval, duration)` and `appendFailure(at, interval, duration)`. `describeHistory` also returns `failed` flags,
and `app.js` gives that cell a red `failed` class. Requirements: Usage history, The usage history panel.

- **Timing.** `UsageFetcher`/`UsageService` measure how long each request took, from sending to the answer or
  failure, and the interval in force when it was made, and hand both to the history with the outcome. A reading
  with no amounts still writes nothing.
- **Writing.** `UsageHistory.append` takes an outcome (a reading, or a failure at a time) and writes
  `datetime,used,limit,currency,status,interval,duration_ms`. `status` is `start`, `failed`, `start-failed` or
  empty: the in-memory first-row flag from phase 17 now decides `start`. The recording wrapper in `AppRuntime`
  also writes on a failed query, still without turning a history write failure into a refresh failure.
- **Upgrade.** `upgradeOldFile` handles all three older headers: `datetime,used,limit` (adds four columns),
  `datetime,used,limit,currency` (adds three), and `...,startup` (replaces `startup` by `status` with `1`
  becoming `start`, and adds `interval` and `duration_ms`). Rows from older files get empty fields.
- **Reading.** `HistoryReader` returns rows as `datetime, used, limit, currency, status, interval, duration_ms`,
  padding the old shorter rows. `UsageHistory.latest()` returns the newest row that has amounts, not just the newest
  row, so a failed row is passed over.
- **Marking.** `describeHistory` marks rows whose status starts with `start` and shows `failed` in red in the
  `used` cell for the two failed statuses; `status`, `interval` and `duration_ms` are not shown. The old
  `startup` marking from phase 17 is replaced.
- **Tests.** The `UsageHistoryTest` and `HistoryReaderTest` cases of phase 17 reworked for the new columns; the three
  upgrades; failed and first-failed rows; `latest()` skipping a failed row; duration and interval in the row.

### 20. Differences between readings, worked out by the backend

**Status: done (version 0.05).** The history response has a parallel `deltas` array (`delta_used`, `delta_time`)
instead of two more fields in each row, so the rows keep their seven strings; `/api/status` has `change`. The
status reads the history only when its file's size or time has changed (`LatestChangeCache`), since it is polled
every second. Rows with the same time are now sorted later-written first. Requirements: Changes between readings, The usage history panel.

- **Calculation.** A small `HistoryDeltas` class in `usage/` takes the rows of the history in file order and returns,
  for each, the change in the amount used and the time since the previous row. A row marked `start` or
  `start-failed` begins a run and has neither; the time is to the directly preceding row, failed rows included;
  the change in the amount is against the directly preceding row and is empty if that row, or this one, has no
  amounts. Empty means null, never zero. Sorting for the panel happens after, so a file out of order is still worked out
  in file order.
- **API.** `/api/history` rows get `delta_used` and `delta_time` fields, computed regardless of the settings,
  as the requirements say the backend sends them in any case. `/api/status` carries the latest reading's two values.
  They are read from the file (the history is already read for the panel), so they are no new source of truth.
- **Tests.** `HistoryDeltasTest`: first row of a run, a second run in the same file, a failed row in the middle
  (time yes, amount no, and no amount for the row after it), a missing amount, rows out of order, a restored
  startup row.

### 21. Logging: the response JSON, the warnings, a multi-line log panel

**Status: done (version 0.06).** A `ResponseLog` switch (set from the setting at startup and when settings change, through a new
`IntervalSettings.onChange`) is what `UsageClient` asks for each response; a body that is not JSON is not logged at all, only
that it was not. The warning is the existing `Usage refresh failed: <message>` line, now written once after the
message is final (the rate-limit wait is in it) instead of before; the loss of contact with the application is only known to the
window, so it is not logged. An entry's first line is the one that starts with its time; `describeLog` keeps the lines after it with it. Requirements: Non-functional requirements (log), The log panel.

- **Response JSON.** `UsageClient` hands the raw body to the log only when the setting is on, as one entry line
  that says it is the response, then the body pretty printed over several lines (Jackson is already used for
  parsing), through the redacting formatter. The setting is read per request, so applying it takes effect at once.
  The existing "status, duration, size, request-id" line and its test of never logging a body are kept for the
  setting-off case and extended for the setting-on one: masked, and still no header.
- **Warnings.** Whenever the status gets a message line (a failed refresh, stale data, a missing login), the
  backend logs it once at the warning level when it appears, not on each poll. A failure that is already logged
  by the fetcher is not logged twice; the line is the message as the window shows it.
- **Log panel.** `LogTail` takes whole lines as before, but `describeLog` keeps the continuation lines of an entry
  (lines with no leading timestamp) together and in order within the newest-first display: it groups a line
  with the lines after it up to the next timestamp, then reverses the groups. The 1,000-line limit still counts
  lines.
- **Tests.** Response logging off and on (pretty printed, masked, multi-line); one log line per message line;
  a front-end test for grouping and order.

### 22. The backend sends the finished figures; the window does no arithmetic

**Status: done (version 0.07).** `Formatting` and `StatusDisplay` (Java) build a `display` block in `/api/status`: time and
tooltip, spend, windows, placeholder, countdown, the two changes, the message, and `show` flags for the optional items. The
page's `describeStatus` only reads it, and the formatters are gone from `view.js`; a test checks the page scripts for date
parsing, rounding and number formatting. The page still shows the countdown always and ignores the change texts and the `show`
flags, as the row is phase 23's; so until then the time shows without seconds (the setting's default) and the window is
otherwise unchanged. The page tests get their `display` from `fake-display.cjs`, a stand-in for the backend that derives it
from the raw fields; the real logic is tested in `FormattingTest` and `StatusDisplayTest`. Requirements: the frontend does no calculation (Display requirements), Compact window.

- **Status payload.** `/api/status` already carries the countdown. Add what `view.js` works out today: the cut
  time (`14:24` or `14:24:53`, by the time-format setting) for the last refresh and for an error, the remaining
  time of a plan window (`in 2 h 5 min`), the percentage, and the change values of phase 20, formatted as in the
  requirements (`+0.05`, `1 m`). A `Formatting` class in Java owns these, so the Go rewrite has one place to
  copy from. The raw values stay in the payload for tooltips and tests.
- **Frontend.** `describeStatus`, `formatTime`, `formatSpan`, `formatPercent` and `describeCountdown` shrink to
  placing the received strings; the only decision left to the page is showing or hiding by the settings the
  backend sent in the same payload (the toggles), so that a setting applied elsewhere shows at once.
- **Tests.** `FormattingTest` for every form, including the sign and a negative countdown, and the existing
  `view.test.cjs` cases rewritten against the new payload. A check in the frontend tests that no arithmetic on
  readings remains (a search of `view.js` for the old helpers).

### 23. The settings view in the window, and a stable row

**Status: done (version 0.08); how it looks, and the window sizes, are for a person to judge.** The view is static HTML in the panel area,
filled by `app.js` from `GET /api/settings` each time it opens. Close asks inline (Discard them / Keep editing), because
a JavaFX WebView shows no `confirm()` dialog unless the host installs a handler. `POST /api/config` and the config field are gone and the
`view.js` interval check with them; `GET /api/config` stays, since the polling interval is the one value that is not a setting
and the page needs it before it polls. The old POST tests in `ApiTest` now go through `/api/settings`. Requirements: Settings, Compact window (row, tooltips, window size).

- **Panel.** A third panel in `PANELS` beside the history and the log: button, tooltips `Show the settings` and
  `Hide the settings`, ten times the row's height, the width of the row, one panel at a time. The config button's
  handler and the inline interval field (HTML, CSS, `app.js`) are removed, and so are the ids the layout test
  checks.
- **Content.** Sections for the usage requests, the main view, the history view and the log. The form is built
  from `GET /api/settings` each time the panel opens, with the dropdown showing the current interval (and offering it
  as an extra entry if it is not one of the five). The frontend keeps nothing between openings.
- **Buttons.** Apply posts all values and shows a rejection in red without closing; Close asks before
  discarding unapplied edits (compare the form with the values it was filled from); Restore defaults fills
  the form from the `defaults` of the API response without applying; Maximum view and Minimum view fill in
  the four main-view fields, leaving the rest.
- **The row.** Optional items (countdown, change in the amount used, time since the previous reading) are
  shown only when their settings, from the status payload, are on. Each has a fixed width for its longest value
  so that a refresh does not resize the window; a setting that adds or removes an item does, through the
  existing fit.
- **Tests.** `app.test.cjs`: opening fetches the settings, Apply, Close with and without unapplied edits,
  Restore, Maximum and Minimum view, the row following the toggles, no stored settings. `layout.test.cjs`: fixed
  widths of the changing fields and that a refresh reports an unchanged size. A manual check of the window by a
  person, as for the other panels.

*Assumed:* the defaults for the new switches are off (only the countdown was stated); the delta time is written
as a short span such as `1 m`; a failed row's time is that of the failure; the history delta columns are the last two,
after the currency; the version constant is raised with each phase.

### 24. Corrections: the delay is the interval, and the history gets its change columns

**Status: done (version 0.09).** Two things of the requirements note were missed by phases 22 and 23. The setting "show the
current delay time in the main view" had been built as a switch on the countdown; the delay is the configured interval, so the
switch is now `showInterval` (a `60 s` item after the countdown, off by default), and the countdown is shown always, as before. And
the history table had no change columns: `/api/history` now tells which are on (`show`) and carries the texts finished
(`delta_used_text`, `delta_time_text`), and `describeHistory` adds the two columns after the currency, with grids for five and six
columns. The old `showCountdown` key in a settings file is ignored.

### 25. A gear at the right, an optional percentage, Apply that closes, and window sizes that fit

**Status: done (version 0.10); the three sizes, the gear and the scrollbar are for a person to judge in the window.** One change from the plan: the
settings report a new flag `3` (`Resize.FIXED`), not `0`, because `0` is also what the host takes as "the window with nothing open"
and records as the size the history may be shrunk back to; the settings are fitted and not resizable, but are not that. The page
measures the scrollbar with a hidden `.scrollbar-probe` box at start, and the page tests give the fake DOM a width to measure. Requirements: Compact window (size, row), Settings, The log panel, The usage history panel.

- **Row order and icon.** In `index.html` the settings button moves behind the history button, so the row ends log, history,
  settings, and its icon becomes a gear (a toothed circle path, in the same 12 px, 1.6 stroke style as its neighbours). The
  tooltips and aria labels stay. The layout tests that fix the order are rewritten: settings is last, and the log and history buttons
  are no longer the right-hand end.
- **Percentage setting.** `Settings` gets `showPercentage` (default `false`) in the position before `showInterval`; `SettingsStore`
  reads and writes it, `/api/settings` carries it, and `StatusDisplay.Show` gets `percentage`. The page hides `#spend`'s
  `#percent` unless `show.percentage` is on; the amounts keep their severity colour and the amounts' tooltips now also say
  the severity when the percentage is hidden (`StatusDisplay` builds that text). The form gets the checkbox in the main-view
  section; Maximum view turns it on and Minimum view off. Plan-window utilizations are not touched.
- **Apply and Cancel.** The `Close` button becomes `Cancel` (`settings-cancel`); `settings-confirm`, `settings-discard`, `settings-keep`,
  `formIsDirty` and the dirty comparison are removed, and Cancel closes at once, sending nothing. Apply closes the view
  after a successful post and polls at once so the row shows the change; a refusal or a failed save keeps the view open
  with the red message, as now.
- **Settings height.** While the settings are shown the page reports the height of its own content: the row and its message
  lines (`#top`) plus the natural height of the settings (`#panel`'s `scrollHeight`), with the view's own `overflow` and
  flex-fill removed so that nothing scrolls, and the flag `0` (not resizable). The host's fit timer already re-asks every
  200 ms, so the window follows when a message line appears, grows or goes. The row is part of the sum, which is what was
  missing when the view was clipped.
- **Height of the log and the history.** The page remembers `10 × (row and message height)` when the panel opens and reports
  that constant while it is open, so a message line coming or going no longer moves the window; the panel, which fills
  the window, absorbs the difference. A drag by the person is respected for the same reason (the report does not change, so
  the host does not apply it again). Opening again starts from the then-current row height.
- **Scrollbar.** The page measures the width of a vertical scrollbar once at start (a throw-away `overflow: scroll` box) and
  sets it as `--scrollbar`; `.panel-lines` always has `overflow-y: scroll`, so the space is reserved whether or not it is
  needed (not `scrollbar-gutter`, which the WebView's engine may lack). While the log or the history is shown, `#app` is
  that much wider than the row, the reported width is the row's plus it (the log: three rows plus it), and the table's columns keep
  their usual width, so the currency is no longer covered. The settings have no scrollbar, so they stay the row's width.
- **Tests.** `layout.test.cjs`: order and gear, the panel scrolls with reserved space, the settings view has no overflow rule.
  `app.test.cjs`: Apply posts and closes and polls; a refusal keeps it open; Cancel sends nothing and never asks; the percentage checkbox and
  Maximum/Minimum; the settings report content height with flag `0` and follow a message line; the history and log report a
  constant height across a message line and add the scrollbar width. Java: `SettingsStoreTest`, `ApiTest`, `StatusDisplayTest` for the
  new setting, the tooltip and `show.percentage`. A manual check of the three sizes and the scrollbar by a person.

*Assumed:* a refused or unsaved Apply keeps the view open, since closing would hide the error; the scrollbar's width is
the platform's, measured, not a fixed 15 px; "the usage view" in the note means both the history and the log.

### 26. A quieter 429, an error log, remembered heights, and a tidier history and settings view

**Status: done (version 0.11); the hover line, the remembered heights, the scrollbar and the dropdown widths are for a person to judge in the window.** Built
as planned in parts A to E. Notes: a rate-limited state is `UsageState.rateLimited` (the 5-argument record, with the old 4-argument constructor kept);
the heights are `SettingsStore.Heights` and live beside the settings in the file, not in the `Settings` record, and `saveHeights` refuses to replace a
damaged file; the page names its panel in the size report (`,history`, `,log`, `,errors`), and `UsageApp` wraps the stage height listener in a 500 ms
`PauseTransition` (the pure rules are `RememberedHeights`, which is tested; the window wiring is not). The interval box is a text field plus a
small dropdown of choices. Requirements: Countdown, Tooltips, Settings, The usage history panel, Remembered heights, The error log panel,
Messages and states, Non-functional requirements (settings file). The work has five parts; each can be built and tested alone, in this order.

**A. The 429 and the error log (backend).**
- `UsageState` gets `rateLimited` (set by `Outcome.rateLimited` in `UsageService`); `stale()` becomes `snapshot != null && error != null && !rateLimited`,
  so a 429 does not dim the figures. `StatusDisplay`: `message` is `null` for a rate-limited state, and the countdown tip carries
  `alert` (the error text) so the page can colour it and show it on hover; `status.stale` follows `stale()`.
- A new `ErrorLog` (in memory, `ArrayDeque` capped at 1,000, thread-safe) owned by `AppRuntime` and handed to `UsageService`, which adds one entry
  for each failed refresh next to where it logs the warning (`run`, after `settleBackoff`), with the final text
  (the 429's `Next try in …` included). Token problems already arrive as `TokenException` failures of the refresh, so they are in it
  without more code; the unexpected-exception path adds its short message too. `GET /api/errors` returns
  `{entries: [{time: "hh:mm:ss", message}]}` newest first, the time cut by `Formatting` (always with seconds), read-only, no credential in it.
- Tests: `ErrorLogTest` (order, cap, concurrency), `UsageServiceTest` (a failed refresh adds an entry, a success none, a 429 sets `rateLimited` and does not dim),
  `StatusDisplayTest` (no message and an alert on a 429, message on others), `ApiTest` (`/api/errors`, a token failure is listed, no poll adds entries).

**B. Remembered heights (host and settings).**
- `Settings` gets `historyDate` (default off) in the main-view-to-history group, and the settings file gets `historyHeight` and `logHeight` (whole pixels, `0` = none).
  The heights are not part of the `/api/settings` form: `IntervalSettings.apply` keeps the current ones, `SettingsStore.save` writes them with the rest, and
  `IntervalSettings.storedHeight(panel)` / `storeHeight(panel, px)` read and write them (saving as `apply` does, logging the change).
- The page report gets a fourth, optional field naming the panel: `width,height,flag,panel` with `history`, `log` or `errors` (`WindowFit`
  parses it; `Size` carries it; older three-field reports still parse). The host applies, for `history` and `log`, `max(reported height, stored height)`
  and stores the reported one when it is larger (the "internal" height), then follows `stage.heightProperty()`: a change that the host
  did not make itself is stored after 500 ms of quiet, as `stage height - decoration height`. The decision (which height to open at, and
  whether to store) is a small pure class, `RememberedHeights`, so it is unit-tested without a window; `UsageApp` only wires it.
- Tests: `WindowFitTest` (the fourth field, bad panel names), `RememberedHeightsTest` (stored larger wins, smaller replaced and stored,
  none stored, the debounce rule), `SettingsStoreTest` and `IntervalSettingsTest` (heights survive an apply and a restart; the API does not change them), `ApiTest`
  (`historyDate` round trip; heights absent from `/api/settings`).

**C. The history table.**
- `/api/history` formats the first column: the time of day `hh:mm:ss`, or `yyyy-MM-dd hh:mm:ss` when `historyDate` is on; it sends the titles
  (`time` or `date time`, `used`, `limit`, `currency`) as `columns` and `startTooltip` (`The program started here`); sorting still uses the raw value.
  `show` gains `date`. `describeHistory` uses these as given, and gives the rows of a start a `title`; `app.js` puts it on the row.
- CSS: the time column is 8ch, 19ch with the date (a `date` class on the rows); a gap between `used` and `limit` (padding on the two cells);
  the rows get `min-width: max-content` so that a table wider than the panel overflows and the existing `overflow-x: auto` shows a horizontal
  scrollbar, only when needed.
- Tests: `ApiTest` (columns, formatted times, both settings), `view.test.cjs` (titles, tooltip on start rows, the wide class), `layout.test.cjs` (gap,
  `min-width: max-content`, `overflow-x: auto`).

**D. The error log panel and the countdown (frontend).**
- `index.html`: the four icon buttons go into one `<span class="tools">` with a 2 px gap; an `errors-button` (a warning-triangle icon) between history and settings;
  `PANELS.errors` (`/api/errors`, read again on every poll, like the log), `describeErrors` (`time`, `message`, one row each, `cols-2` grid), the same size
  rules as the history (report `…,1,errors`, height fixed at open, width plus scrollbar), a one-line note when empty, no stored height.
- The countdown: when `display.countdown.alert` is set it gets the class `alert` (red) and, on `mouseenter`, the message in bold red is shown in the note line
  under the row (`#alert-note`, `note-error` plus bold), removed on `mouseleave`; the window grows for it like for any message and shrinks back. The row's message line
  is empty for a 429 because the backend sends none.
- Tests: `app.test.cjs` (the panel, newest first, polls, the alert class and hover line, no message line for a 429, not dimmed), `layout.test.cjs` (the group, the button order, the red rule).

**E. The settings view.**
- The interval becomes a text box with `inputmode="numeric"` plus a small `<select>` of the five choices that fills it in (a `<datalist>` is not relied on: the WebView's engine may lack it),
  labelled `Interval`; the page checks only that it is a whole number (`^\d+$`) before posting, the backend checks the range 5 to 3600 and its refusal shows in red.
- Texts: the row checkbox `Interval`, `Log the response`, under `History view`: `Date as well as the time`, `Change in the amount used`, `Time since the previous reading`.
- CSS: `.settings` set at 12 px (an exception in the 14 px layout test, like the panels), `user-select: text` (and the `-webkit-` form), `select` and `input`
  given room for their value and the arrow (`min-width` in `ch` plus right padding), checked by the layout test as far as CSS can be, and by eye.
- Tests: `app.test.cjs` (typed value posted, a non-number refused in the page, a choice fills the box, `historyDate` posted, the labels), `layout.test.cjs`.

**Open points to confirm in the window, not in tests:** whether the WebView's text selection works in the settings; the width of the time-format dropdown; the
hover line for the 429 (a native tooltip could not be red and bold, and a page tooltip would not fit in a one-row window).

*Assumed:* the error log has no remembered height; remembered heights are in CSS pixels of the window's content; the history's date setting does not change the file;
the 500 ms quiet period is a guess to tune; the error log keeps non-429 messages also in the row's message line.

### 27. A history window as wide as its table, Δ titles, and no zero change

**Status: done (version 0.12); every column unclipped, with the date and both change columns on, is for a person to judge in the window.** Built as planned: `#table-probe` is
filled with the header and the three rows with the most text, and `measureTable` keeps `tableWidth` across closing. The probe's 12 px text is an addition to the layout test's list of text under 14 px (it is the panel's text, never seen). Requirements: The usage history panel (width, titles, change columns), Changes between readings (a change of zero).

- **No zero change (backend).** `ApiHandler.DeltaBody.of` leaves `deltaUsedText` `null` when the change rounds to zero (`|used| < 0.005`; the raw `delta_used`
  stays `0.0` for other clients). `StatusDisplay.deltaUsed` already returns no item when its text is `null`, so the row's change item and the history cell
  both go empty through the same value; `Formatting.signedAmount` keeps `0.00` for zero, as a formatter should. A time since the previous reading is
  untouched. Tests: `FormattingTest`/`ApiTest` (a zero change has no text in the history and no `deltaUsed` in the status, a non-zero one has), `StatusDisplayTest`.
- **Titles.** `describeHistory` pushes `Δ used` and `Δ time` instead of `delta used` and `delta time`. Tests: `view.test.cjs`.
- **Width.** The page measures the table's own width without the window in the way, since a row that stretches to its container would
  report the container: a hidden `#table-probe` (`position: absolute; visibility: hidden; width: max-content`) is filled by `showPanel` with the
  header and the few data rows with the most text, using the same row classes (`cols-N`, `date`), so each grid takes its content's
  width. `contentSize` for the history reports `max(row width, ceil(probe width) + the panel's 16 px padding) + scrollbar` with flag `1,history`; the last
  measured table width is kept across closing and opening so the window does not start narrow and then widen. The host already fixes the width at what the page
  reports and lets only the height be dragged, so no host change is needed. The log and the error log keep their widths.
- CSS: `.table-probe` rule; the rows keep `min-width: max-content` (the scrollbar then appears only past the 2,400 px limit of `WindowFit`).
- Tests: `app.test.cjs` (the fake `#table-probe` reports 0 by default; with a wider probe the history asks for that plus padding and scrollbar, with a narrower
  one the row's width, and the width follows when the probe changes), `layout.test.cjs` (the probe is hidden and `max-content`). A manual check of
  every column unclipped with the date and both change columns on.

*Assumed:* the amounts and the time are the widest data, so the header and the widest-looking rows are enough to measure; a very wide table
past 2,400 px is cut by the window limit and reached with the horizontal scrollbar.

### 28. The wait after a 429 is the interval, a gutter for the scrollbar, a wider error log, green icons

**Status: done (version 0.14); the gutter, the used/limit room, the error log width and the green are for a person to judge in the window.** Built as planned. The
gutter is `padding-right: 16px` on `.panel-lines` (and so on the probe, whose measured width then includes it), not `scrollbar-gutter`. Requirements: Refresh behavior (the longer wait is the interval in force), Row (items 6 and the green button), Tooltips,
The usage history panel (space between `used` and `limit`, the gutter), The error log panel (its size).

- **The interval in force (backend).** `UsageService.effectiveIntervalSeconds()` is `ceil(max(configured interval, backoff.hold()))` in whole
  seconds, read under the lock. `ApiHandler` builds the settings it sends from `settings.current().withUsageIntervalSeconds(effective)`, so
  `GET /api/settings` (the box), the `display.interval` item of the status and the status' own interval all show the wait in force;
  `IntervalSettings` still holds the configured one, which `settings.json` keeps until something is applied. Apply is left as it is, which is what the
  requirements ask: the box's value goes in, `apply` sees it differs from the saved one, saves it and calls `service.setInterval`. `GET /api/config` is
  untouched (it reports what is configured). The backoff state is not reset by an apply; its hold simply no longer exceeds the new interval once that
  is as long. Tests: `UsageServiceTest` (the effective value during and after a back-off, rounded up), `ApiTest` (after a 429 `/api/settings` and
  the status interval show the longer wait, and applying that value saves it), `StatusDisplayTest`.
- **Tooltips.** `StatusDisplay.spend` loses the `percentShown` parameter and the severity suffix of the amounts' tooltips; the percentage's tooltip keeps
  naming the severity. Tests: `StatusDisplayTest` (the amounts' tooltips are the same with the percentage on or off), `ApiTest` if it looks at them.
- **History table.** The cells next to the `used`/`limit` gap go from 3ch to 1.5ch padding. `.panel-lines` gets `padding-right: 16px`, a gutter that is
  there whether the scrollbar takes room or is drawn over the content (macOS), so the `currency` title is never under it; the probe is a
  `.panel-lines` too, so its measured width includes the gutter and the window's width follows without a constant in the script. Tests:
  `layout.test.cjs` (the padding values, the gutter on `.panel-lines` and the probe).
- **Error log width.** `contentSize` for `errors` reports `3 * row + scrollbar` with flag `2` and the panel name `errors`, like the log; the host needs
  no change (it fixes nothing for `BOTH`). The row layout (`cols-2`) already scrolls sideways for long messages. Tests: `app.test.cjs` (the
  size string), docs.
- **Green icon.** `labelButtons` also sets the button's class: `icon tiny active` for the open panel's, `icon tiny` for the others; `.icon.active` is
  `color: var(--ok)`. Tests: `app.test.cjs` (opening makes it green, closing or opening another moves it, never two, none when closed), `layout.test.cjs`
  (the rule). Docs: `api.md` for the contentSize note, the README.

*Assumed:* the interval shown is rounded up to whole seconds and never below the configured one; 16 px is the gutter because that is what a
classic scrollbar takes; the green is the severity-normal green already in the style sheet.

### 29. A green icon that is easy to see

**Status: done (version 0.15); how it looks is for a person to judge.** Built as planned, with one addition: `.icon.active` has `margin: -2px`, which takes back the 4 px the button grew by, so the row is
neither wider nor taller and the window does not change size (the plan only argued the height). The page test for "contentSize unchanged" is a layout
test of those rules, since the fake DOM has no layout. Requirements: The button of the open panel is green, and bigger (Compact window, The row).

- **Colour.** A new style variable `--active` (`#00b341`, and `#3ddc6b` in the dark theme block that already redefines `--ok`) replaces `var(--ok)` in `.icon.active`;
  `--ok` stays the severity green.
- **Size and line.** `.icon.active` is `width/height: 24px` (the refresh button's size, so the strip's `align-items: center` and its 34 px row do not
  change) and its `svg` is `width/height: 16px`, which beat the `width="12"` attributes; `stroke-width: 2.2` in CSS beats the attribute `1.6`. The gear's `viewBox` is 24 and not
  16, so its line is `3.3` (2.2 x 24/16) to look the same; this is one rule on `#settings-button.active svg`. Nothing else changes: the class is still set by
  `labelButtons`, and removing it brings back the 20 px button, the 12/13 px glyph and the gray, with no extra state.
- **No window change.** Because the strip height is set by the 24 px refresh button, the page's reported size does not change when a button grows; a test keeps
  `contentSize()` unchanged across opening and closing a panel except for the panel's own size.
- **Tests.** `layout.test.cjs`: the `--active` variable in both themes, `.icon.active` 24 px, its `svg` 16 px and `stroke-width: 2.2`, the gear's 3.3, that the active rule
  comes after the `.icon.tiny` rules, and that the button size is not larger than the refresh button's. `app.test.cjs`: the class toggling already tested stays.
  A manual check of the look by a person, which is the point of the change.

*Assumed:* "as before" means the 20 px button, 12 px glyph, 1.6 line and the muted gray; the dark theme green is chosen to be as vivid there.

### 30. Seconds in the history, a right-aligned table, `Cur.`, a narrower error log, and back to the view before the settings

**Status: done (version 0.16); how the packed table, the error log width and the return look and behave in the window is for a person to judge.** Built as planned. `togglePanel`
is now a thin function over `showPanelNamed`, with `returnTo` holding what the settings replaced; the host needed nothing, as planned. The fixed widths are `used` 10ch, `limit` 10ch,
`Cur.` 5ch, `Δ used` 9ch, `Δ time` 8ch. Requirements: The usage history panel (layout, `Cur.`, Δ time in seconds), Settings (the texts, leaving the settings), The error log panel (size), Compact window (size).

- **Δ time in seconds (backend).** `ApiHandler.DeltaBody` gets `deltaSecondsText` (`"63 s"`, `null` when the time cannot be worked out), serialised as `delta_seconds_text`; it is `seconds + " s"` with
  no scaling. `delta_time_text` stays the short `1 m` form, which the row's item and the status use. `describeHistory` uses `delta_seconds_text` for the Δ time cells. Tests: `ApiTest`
  (`63 s` and `3600 s` in the history, `1 m` in the status for the same row), `view.test.cjs`.
- **`Cur.`.** `ApiHandler.history` sets the fourth column title to `Cur.` (the first is `time` or `date time`, as now); the cells are untouched. Tests: `ApiTest`, `app.test.cjs` header.
- **A table packed to the right.** In `app.css` the history grids become `minmax(8ch, 1fr)` (the time, `minmax(19ch, 1fr)` with the date) for the left column and fixed
  widths for the others, as before so that the rows line up: `used` 10ch, `limit` 10ch, `Cur.` 5ch, `Δ used` 9ch, `Δ time` 8ch (`cols-4`, `cols-5`, `cols-6`, and the `date`
  variants); every cell but the first gets `text-align: right`, header included, and the 3ch paddings between `used` and `limit` are replaced by the room the column widths leave (about three characters for a
  typical budget). The `1fr` time column takes the free space, so the other columns sit against the right edge. A longer amount than the column holds would overflow it; the widths are
  generous (up to 9,999,999.99) and are the one fixed-number assumption of the table. The probe measures the same grids, so the window stays as wide as the table needs. Tests: `layout.test.cjs`
  (the track lists, right alignment, `minmax(...,1fr)` first, no padding rule left), `app.test.cjs` (titles `Cur.`, `Δ used`, `Δ time` in order).
- **Settings texts.** In `index.html` the two History view checkboxes read `Δ used` and `Δ time` (the Main view ones keep their long texts). Test: `layout.test.cjs`
  (the settings texts test is changed).
- **Error log width.** `contentSize` for `errors` reports `ceil(1.5 * row) + scrollbar`, flag `2`, panel `errors`; the height stays `panelHeight`. Tests: `app.test.cjs` (the size string), docs.
- **Back to the view before the settings.** `app.js` keeps `returnTo`: when the settings button opens the settings while the log, history or error log is shown, that panel's name is
  remembered; leaving the settings (Apply after a successful post, Cancel, the settings button again) opens `returnTo` through the same path as a click on its button (fresh load, so
  the history shows its new columns, date and times, and the panel height is taken again from the row), then clears it. Opening another panel's button while the settings are shown clears
  `returnTo` first. The host needs nothing: the history and the log come back at their remembered heights because the page reports the panel again, the error log at its opening size.
  Tests: `app.test.cjs` (history open, settings, Cancel returns to history; Apply returns to history and requests it again with the new setting; the same for the log and the error
  log; nothing open before means nothing open after; another panel's button forgets it; the green button follows).
- **Docs.** `api.md` (the new field, `Cur.`, the error log width), README.

*Assumed:* the amounts that fit the fixed columns are up to `9999999.99`; the error log's height is not restored beyond its opening size, since it is not remembered.

### 31. Hiding zero usage and failed lines, changes on the visible lines, and a history the page only draws

**Status: done (version 0.17); the look of the centred checkboxes and the note line is for a person to judge.** Built as planned. `HistoryDeltas.computeVisible` and `HistoryReader.Filter` carry the logic, with the
counts the note needs in `Table`; `ApiHandler.history` makes the finished lines and the note, and `describeHistory` is a plain mapping. The old history tests were rewritten to the new body,
and the page-script test now also forbids status text, `row[4]` and cell cutting in `view.js`. Requirements: Settings (the texts, the vertical centring, the two switches), The usage history panel (Hiding lines, the indicator, the page does no
calculation), Changes between readings (on the lines that are shown). The work has four parts, in this order.

**A. Two settings.** `Settings` gets `historyZeroLines` and `historyFailedLines` (both default `true`) after `historyDate`; `SettingsStore` reads, validates and writes them
(`historyZeroLines`, `historyFailedLines`); `/api/settings` carries them and `POST` requires them. The positional `new Settings(...)` calls in the tests get two more arguments (as in
earlier phases). Tests: `SettingsStoreTest` (defaults on, round trip, file keys), `ApiTest` (the settings body has twelve settings).

**B. The backend makes the lines (the main work).**
- `HistoryDeltas` gets a second entry point, `computeVisible(rows, visible)`, that returns a delta for each row on the rows that are shown: walking the rows in file
  order it keeps `previousShown` and clears it at any row whose status begins a run, shown or not; a shown row that begins a run, or has no `previousShown`, gets none;
  otherwise the change is its amount minus `previousShown`'s (empty if either has no amounts) and the time is the seconds between the two (empty if negative or unreadable). The existing
  `compute` (against the row directly before) stays: it still decides what a zero usage line is and serves the status' `change`.
- `HistoryReader.read(file, limit, Filter)` with `Filter(showZero, showFailed)` (and the old two-argument form as "show all"): parse rows, run `compute` over all of them, mark a
  row **zero usage** when its change is present and `signum() == 0`, **failed** when its status has `failed`; drop what the filter hides; run `computeVisible` on what is left; sort newest
  first (same rule, later-written first on a tie); cut to `limit`. `Table` adds `hiddenZero`, `hiddenFailed` and `shown`-before-cut counts so that the backend can say what is not shown.
- `ApiHandler.history` builds **finished lines**. The body becomes
  `{file, exists, columns, wide, note, total, lines: [{cells, start, failed, title}]}`: `columns` are the titles for what is on (`time` or `date time`, `used`, `limit`, `Cur.`,
  then `Δ used`, `Δ time`); `cells` the final strings (time cut by the date setting, the amounts, `failed` in the place of a failed row's amount, `Δ used` empty for none or zero, `Δ time` in
  seconds, `63 s`); `start` true when the status begins with `start`; `failed` true when it has `failed`; `title` is `The program started here` for a start and empty otherwise; `wide`
  is the date setting; `note` is the one line for every case where the page has something to say: no file (`There is no usage history yet.`), no rows (`The history has no rows yet.`), and
  `Showing 640 of 1,500 lines: 700 zero usage and 60 failed hidden, 100 older not shown.` when anything is left out (parts that are zero are left out of the sentence), else `null`. The
  `rows`, `deltas`, `show` and `startTooltip` fields and the raw status, interval and duration go; `exists`/`total` stay for other clients. Thousands are written with a comma, as the amounts are.
- Tests: `HistoryDeltasTest` (`computeVisible`: the first shown line of a run has none; a hidden line between two shown ones makes the time and change span it; a hidden start row still begins a run; a failed row shown
  has a time and no change; a zero change is empty text), `HistoryReaderTest` (the filter, the counts, a zero usage line is not the first of a run and not a failed one, sorting and the cut after filtering),
  `ApiTest` (the whole new body, the note in each case, the settings switching lines out and the changes following, the first of a run marked `start` even when its neighbours are hidden). The status's `change`
  and `LatestChangeCache` keep the show-all reader and a test says the main row ignores the history switches.

**C. The page only draws.** `describeHistory` becomes a mapping: `header = columns`, `rows = lines.map(l => l.cells)`, `marks`/`failed`/`titles` from the lines, `wide`, `note`. The status-string logic
(`hasStatus`), the `failed` swap, the delta columns and the "newest N of M" text leave `view.js`; `app.js` keeps `cells()`, `showPanel` and the measuring probe, which is layout of finished text, not a calculation on readings.
The layout test that forbids arithmetic in the page scripts gets `split('-')`, `.slice(0, data.columns.length)` and the like in its list. Tests: `view.test.cjs` and the fake history of `app.test.cjs` are rewritten to the new body.

**D. The settings view.** `index.html`: the date checkbox reads `Show Date`, two new checkboxes `Show zero usage lines` and `Show failed lines` (`set-historyZeroLines`, `set-historyFailedLines`) under `History view`;
`app.js`: both ids in `SETTING_FLAGS`. CSS: `.settings label` becomes `display: flex; align-items: center; gap: 4px` (the interval label, whose box and dropdown sit in it, `flex-wrap: wrap`), and the checkbox's margin is reset, so that a
checkbox and its text, and the controls of every line, are centred on each other; the `Maximum view`/`Minimum view`/`Restore defaults` behaviour is unchanged (Restore defaults fills the new defaults, Minimum/Maximum leave the history alone).
Tests: `layout.test.cjs` (the label rule, the texts, the two ids), `app.test.cjs` (they are in the form and posted, Restore defaults gives `true`, true).

**Docs.** `api.md` (the new `/api/history` body, the two settings, `Show Date`), README (the two switches, the indicator), the manual checks (the note line, hidden lines, checkbox alignment).

*Assumed:* a hidden start row is the one case where the first shown line of a run is not itself a start line (it just has no changes); "zero usage" is decided on the whole file before hiding, so switching the failed lines off does not turn a line after a
failed one into a zero usage line; the counts in the note are of lines, not rows of the file with the header; the note is plain text with the thousands separator of the amounts.

### 32. Optional view icons, a currency symbol, and the currency last in the history

**Status: done (version 0.18); the narrower row with icons off, the symbol in the row and the table order are for a person to judge.** Built as planned. `Settings` got four `with...` helpers for the tests; the page keeps the settings that
switch each panel's button in `ICON_SETTINGS` and uses the settings of the Apply response to decide whether a panel is given back. Requirements: The row (items 3, 8 to 11), Settings (the four new switches, Maximum and Minimum view, the texts), The usage history panel (column order, `$`).

**A. Four settings.** `Settings` gets `showCurrency` (default `false`) and `showHistoryIcon`, `showLogIcon`, `showErrorIcon` (default `true`) after `showPercentage`; `SettingsStore` reads, validates
and writes them (`showCurrency`, `showHistoryIcon`, `showLogIcon`, `showErrorIcon`); `/api/settings` carries them and `POST` requires them. The positional `new Settings(...)` calls in the tests get four more
arguments, and `allSettings` in `ApiTest` the four keys (16 settings in all). Tests: `SettingsStoreTest` (defaults, round trip, file keys in order), `ApiTest` (the body has them).

**B. The backend.**
- `Formatting.currencySymbol(String code)`: `"USD"` gives `"$"`, no code or an empty one gives `""`, any other code gives itself followed by a space (`"EUR "`). One place for the rule, used by the row and by the history.
- `StatusDisplay`: when `showCurrency` is on, `spend.used` and `spend.limit` are the symbol plus the amount (`$186.02`, `EUR 1,000.00`); an amount that is missing (the dash) gets no symbol; tooltips are unchanged. `Show` gets
  `currency`, `historyIcon`, `logIcon`, `errorIcon` so that the page knows which buttons there are. Tests: `StatusDisplayTest` (USD, EUR, none, a missing amount, off by default, the flags), `FormattingTest`.
- `ApiHandler.history`: the cells and the titles are built in the new order, `time`, `used`, `limit`, then `Δ used` and `Δ time` when on, and **the currency last** (`Cur.`); the currency cell is `$` for USD and the code
  otherwise (`Formatting.currencySymbol(...).trim()` for the history, which wants `$` or the bare code), empty when none. Tests: `ApiTest` (the order with none, one and both change columns, `$` and `EUR` cells, the title `Cur.`).

**C. The page.**
- `index.html`: nothing new; `app.js` `render` shows or hides `history-button`, `log-button` and `errors-button` from `display.show` (`show(id, flag)`; the gear is never touched); `view.js` passes the flags on (default on, so the buttons are there before the first
  status, as the settings default says). If the open panel's button goes (another client changed it), the panel is closed through `showPanelNamed(null)`.
- Leaving the settings: `applySettings` has the new settings in the response, so before `togglePanel('settings')` it clears `returnTo` when the icon of that panel is off in them; Cancel changes no setting and returns as before. Tests: `app.test.cjs` (each button hidden by its flag,
  the gear always there, the panel closes if its button goes, Apply does not give back a panel whose icon was switched off, Cancel does, `fake-display.cjs` gets the flags).
- CSS grids for the history: `cols-5` becomes `minmax(8ch, 1fr) 10ch 10ch 9ch 5ch` and `cols-6` `minmax(8ch, 1fr) 10ch 10ch 9ch 8ch 5ch` (with the `date` variants), so the currency stays the right-most column and a change column sits between `limit` and it
  (the one change column of `cols-5` is 9ch whichever it is); `cols-4` is as it was. `layout.test.cjs` has the track lists checked again.

**D. The settings view.** `index.html`: under `Main view` the checkboxes `Currency symbol`, `History icon`, `Log icon`, `Error log icon` (`set-showCurrency`, `set-showHistoryIcon`, `set-showLogIcon`, `set-showErrorIcon`); `app.js`: the four ids in
`SETTING_FLAGS`, and `setMainView(all)` sets them too (Maximum on, Minimum off); Restore defaults fills `false` and three `true` from the defaults the backend sends. Tests: `layout.test.cjs` (the ids and texts, in the Main view group), `app.test.cjs` (posted, Maximum and Minimum,
Restore defaults).

**Docs.** `api.md` (the settings, the `show` flags, the history's cell order and `$`), README (the icons, the symbol, the order), the manual checks (icons off and the window narrowing, the symbol in the row, the table order).

*Assumed:* Maximum view also turns the currency symbol on and Minimum off (the requirements say so); the three buttons are visible before the first status arrives and are hidden by the first one that says otherwise, a flicker of one poll
for people who switched them off; the history shows `$` only for USD and the bare code for other currencies, while the row shows the code with a space, as asked.

### 33. One interval field, plainer labels, startup lines as zero usage, and a blue note

**Status: done (version 0.19); the blue and the single field are for a person to judge.** Built as planned, with one fix found by a test: a failed startup line (`start-failed`) must be excluded from the zero usage rule explicitly (`!failed && (startup || ...)`),
or it would have been hidden with the zero usage lines while the failed lines were shown. `noteHighlight` is computed from the same table counts as the note. Requirements: Settings (the `Request` section, the interval field, the labels), The usage history panel (Hiding lines, the note), Refresh behavior (the interval's range).

- **A single interval field.** `index.html`: the section heading `Usage requests` becomes `Request`, and the dropdown `set-intervalChoices` is removed (the text field `set-usageIntervalSeconds` and its `s` stay). `app.js` loses `fillInterval`'s
  choices, `onIntervalChoice` and its listener; `fillInterval(current)` only writes the value. The backend stops offering a list: `Settings.INTERVAL_CHOICES` and the `intervalChoices` member of `SettingsBody` go (the `limits` stay, 5 to 3600, which the page does not
  use for a list any more). Tests: remove the dropdown tests in `app.test.cjs` (`choices`, the pick test, the "offered even when it is not one of the five" test, which becomes "the field shows the value in force"), the select checks in `layout.test.cjs`, `SettingsStoreTest`
  and `ApiTest` (no `intervalChoices`); keep the typed-number, refused-in-red and backend-refusal tests, which are the field's.
- **Labels.** The three History view checkboxes read `Date`, `Zero usage lines` and `Failed lines` (`Δ used` and `Δ time` as before): `index.html` and the settings-texts test in `layout.test.cjs`.
- **Startup lines are zero usage lines (backend).** In `HistoryReader.read` the classification becomes: a row that is a failed query is a failed line (checked first, so a `start-failed` row is a failed line); otherwise a row is a zero usage line when its change is zero **or its
  status begins a run** (`start`). `computeVisible` is unchanged: a hidden start row still begins its run, so the first line shown after a hidden startup line has no change. The note's `N zero usage` counts the startup lines in. Tests to change: the `ApiTest` hiding
  tests whose counts and lists included the startup line (`writeMixedHistory`: 14:00 is now hidden with the zero lines, so 3 zero usage hidden and the lists lose `14:00:00`; the visible-change test loses its `start` line and its first change), the 1,102-row
  note test (1,101 zero usage hidden, one line shown), `HistoryReaderTest` (a start row is a zero usage line, a start-failed row a failed line, the counts); new tests: the startup lines come back when the switch is on, with their gray flag and hover text.
- **A blue note.** `ApiHandler.history` adds `noteHighlight` to the body: `true` only for the `Showing N of M lines: ...` note (something is left out), `false` for the "no history" and "no rows" notes. `describeHistory` passes it on; `app.js` `showPanel` sets
  the note's class to `panel-note highlight` when it is true (and plain `panel-note` for every other panel and note); the style sheet gets `--info` (`#0969da`, `#58a6ff` in the dark block) and `.panel-note.highlight { color: var(--info); }` after the `.panel-note` rule, bold as the rest. The log's note is
  untouched, but the error log's empty note, `There are no errors in this run.`, is blue as well: `describeErrors` gets the same `noteHighlight: true` for it (a page-side constant of the page's own
  note, since the text and the case are the page's: `describeErrors` has no backend line to be told). Tests: `ApiTest` (the flag in each case), `view.test.cjs` (the error log's empty note is highlighted), `app.test.cjs` (the class on the history's hidden-lines note and on the error log's empty note, none on the log's or the empty-history note), `layout.test.cjs` (the variable in both themes, the rule, its place after `.panel-note`).
- **Docs.** `api.md` (no `intervalChoices`, the new labels, startup lines in the zero usage definition, `noteHighlight`), README (the single field, the labels), the manual checks (the note is blue, startup lines hide with the zero usage lines, no dropdown).

*Assumed:* a hidden startup line takes its gray marker and hover text with it; the interval field's own validation (a whole number in the page, the range in the backend) is all that is left of the choices' job; `Request` is one word, as written.

### 34. Green startup lines in the history

**Status: done (version 0.20); the green on both themes is for a person to judge.** Built as planned. Requirements: The usage history panel (the startup line is green text, with no special background; the hover text stays).

- **A class of its own.** Until now the history and the log shared the row class `mark` (the light gray background). The history's start lines get their own class, `start`: `describeHistory` returns `markClass: 'start'` and
  `describeLog` `markClass: 'mark'` next to the existing `marks` flags, and `showPanel`/`cells()` add `' ' + markClass` to a marked row. Nothing decides from the panel's name, and the log keeps the gray.
- **The style.** `app.css`: `.panel-lines .start { color: var(--active); }`, after the rules for the row text and with no `background`, no `font-weight` (the line is not bold). The colour is the vivid green of the open panel's button, which already has
  a value for each theme; a failed startup line's `failed` keeps `color: var(--bad)` through its own, more specific rule. The `.mark` rule is not touched.
- **Tests.** `layout.test.cjs`: the `.start` rule has `color: var(--active)` and no `background`/`font-weight`, comes after `.panel-lines`, and `.mark` still has the gray background (the log); `view.test.cjs`: `markClass` of both describes;
  `app.test.cjs`: a history start line has the class `start` and not `mark`, a log start line `mark` and not `start`, a failed startup line's amount cell is still red. A manual check of the green on a light and a dark theme.
- **Docs.** README (the green startup line).

*Assumed:* the green is the same as the open panel's button (`--active`), as the requirements say, not the severity green; the hover text and the zero-usage handling of startup lines are unchanged.

### 35. Seconds in the row, and every icon while the settings are open

**Status: done (version 0.21); how the buttons come and go with the gear is for a person to judge.** Built as planned; `Formatting.gap` became `Formatting.seconds` (a plain `N s`) rather than just being removed, so the row and the history share one place for the text. Requirements: The row (item 7, the buttons while the settings are shown), Changes between readings. The first item of the note, the row's change in the amount not showing, was answered by the person as now shown; it
needs no work (the item is empty exactly when the change is zero or cannot be worked out, as the requirements say).

- **The time in seconds in the row (backend).** `StatusDisplay.deltaTime` uses `change.deltaSecondsText()` (`105 s`) instead of the short form; `Formatting.gap` and `DeltaBody.deltaTimeText` (`delta_time_text`) have no user left and go, so the status' `change` and the
  history's deltas carry `delta_seconds_text` only. `index.html`/CSS: `.delta-time { min-width: 6ch }` for `3600 s`. Tests: `StatusDisplayTest` (`63 s`, `105 s`, `3600 s`, none for an unknown time), `ApiTest` (the status' `display.deltaTime.text` is seconds, `1 h` is now `3600 s`;
  the history/status comparison test), `FormattingTest` (the `gap` test is removed), `layout.test.cjs` (the width rule).
- **Every button while the settings are open (page).** `renderButtons` shows a button when its flag is on, **or** the settings are open (`openPanel === 'settings'`), **or** its panel is the one shown (`openPanel === name`); the rule that closed a panel whose button
  went is removed, since the button of the shown panel never goes. `showPanelNamed` calls `render()` at its end (when a status has been read) so that the buttons change at the click and not at the next poll. Leaving the settings (the gear, Apply, Cancel) is already a
  `showPanelNamed`, so the row falls back to the configured buttons by itself; an extra button pressed while the settings are open opens its panel through `togglePanel(name)` (which already forgets `returnTo`), and its button stays while the panel is shown and goes
  when it is closed if its flag is off. The row gets wider while the settings are open, which the fit already follows (the settings are the row's width). Tests: `app.test.cjs`: flags all off and the settings closed: only the gear; settings open: all four, in the order log,
  history, error log, gear; Cancel, Apply and the gear again: back to the configured ones; an extra button opens its panel, the settings go, the button stays, and goes when the panel is closed; a configured button stays as it is; a panel whose flag turns off at a poll while it is shown
  is not closed (the old test of that is replaced); `returnTo` is still dropped by Apply for a panel whose button was switched off. `layout.test.cjs` does not change (the order is the HTML's).
- **Docs.** `api.md` (the status' `change`: no `delta_time_text`; the row shows seconds), README (seconds in the row, the buttons while the settings are open), the manual checks (the gear shows all the buttons and Apply/Cancel take the extra ones away).

*Assumed:* "the option icon" is the gear; the extra buttons are in their usual places left of it; the window follows the wider row, as it does for any change of the row.

### 50 to 52. The border round a view, the gray box for what is not known, the level and date of the log

Requirement: Debug mode (the border), and A gray box for a figure that is not known, and the level and date of the log.
Three pieces that do not depend on one another. *Decided by the person:* they are built as phases 45 to 49 were, **one at a
time, each a phase and a version of its own**, both suites green, its passage of the requirements moved out of "specified, not
yet built", a commit. Also decided by the person: the frame that ran down through the settings, as tall as the window but only as wide as the row, is the program's own border, misplaced;
`Debug` is a level to choose and the program gets no debug lines with it; the history's `Δ used` stays as it is.

| Phase | Version | Piece |
| --- | --- | --- |
| 50 | 0.38 | The red border goes round the whole window, a settings view included |
| 51 | 0.39 | A gray box for a figure that is not known; a change of nothing is `0.00` |
| 52 | 0.40 | The log panel: `Level` and `Date`, saved as `logLevel` and `logDate` |

### 50. The red border goes round the whole window

**Status: built (version 0.38).** Requirement: Debug mode.

- **The fault.** `#app` is `width: max-content`, and the panel in it is `width: 0; min-width: 100%`, so that a panel never
  makes the app wider: the app was as wide as the row. The settings and the debug view are made wider than the row by
  `formWidth`, so they stuck out of `#app`, and `#app.fake` drew the border at the row's width through them.
- **The repair.** One rule: `#app.fit { min-width: 100% }`. `fit` is the class the app has had all along while a form is
  shown, and had no rule. The app is as wide as the window then, the form is inside it, and the border is on the box that
  holds everything. Its height stays the content's own, which the window is sized to.
- **Nothing to change in `contentSize`.** `formWidth` measures from the left edge of `#app`, outside its border, and
  doubles the room at the left of the form, so the width it gives has the border of both sides in it already.
- **Tests.** `layout.test.cjs` for the rule. The on-request window test of debug mode now opens the settings with every
  element of the row hidden, the case of the misplaced border, and reports how far the app's right edge is from the window's
  (`gap`) and how far the view sticks out of the border (`outside`): both 0 for the settings, the settings with the row
  emptied (a window of 271 pixels) and the debug settings. Run in a real window; it passes.
- **Also:** `java-aip-usage.fake.csv` is in `.gitignore`, as the real history file is; it was left out in phase 49.

**Not checked by a test.** How it looks: the test measures the edges, not the picture.

### 51. A gray box for a figure that is not known

**Status: built (version 0.39).** Requirement: A gray box for a figure that is not known.

- **The page.** `renderOptional` draws the gray box whenever the element is wanted and has no item, with the tooltip of the
  element; it no longer asks whether the row is still loading. The class `empty` and its rule are gone. A rule after the one
  that dims old figures, `.stale .skeleton`, keeps the box at its own strength.
- **The backend.** `StatusDisplay` writes `0.00` for a change that is there and has no text, which is a change of nothing,
  a change that rounds to nothing included; it gives no item only when the change is not known. `DeltaBody` is as it was,
  with no text for a change of nothing, so the history panel's `Δ used` is empty for it as before.
- **The tooltip** of the checkbox in `index.html`.
- **Tests.** `StatusDisplayTest`: `0.00` for a change of 0, of 0.004 and of -0.004, no item for a change that is not known,
  and no text in `DeltaBody`. `app.test.cjs`: the gray box with its tooltip beside a reading's figures, for the changes and
  for the request duration; the figure back when the value returns; `0.00` shown as a figure. `layout.test.cjs`: no
  `.delta.empty`, the widths, the rule that keeps the box from being dimmed, the tooltip.

**Not checked by a test.** The row in a real window in `No usage reported` with both changes switched on, the case that brought this up: the page test
gives the row a reading with no change, which is the same path.

### 52. The log panel: a level and the date

**Status: built (version 0.40).** Requirement: The log panel (the level and the date of the log panel).

- **The levels.** `LogLevel`, in the settings package: error, warning, info, debug, with the word each has in the file
  (`SEVERE` for an error and `FINE` for a debug line, as `java.util.logging` writes them). The root logger is at
  information, so a debug line was not written then (it is since phase 56).
- **Settings.** `logLevel` and `logDate` in `Settings` (`withLog`) and in `settings.json`, after the debug settings
  and before the two heights. `POST /api/settings` keeps them as they are, as it keeps the debug settings.
- **The backend filters.** `LogTail.read` takes a level: it reads the last 512 kilobytes as before, keeps the entries the
  level lets through, whole, and then takes the newest 1,000 lines. It also says when the file has lines and none of the
  level (`hidden`). `GET /api/log` reads at the saved level, or at the one its query names; `POST /api/log` saves the
  two choices.
- **The page.** A line at the top of the panel, `log-controls`, shown when the first answer after the panel was opened
  has said what is saved. A change is sent at once and the panel is read again, and stands at the top. The date is cut
  in `view.describeLog`, from the lines that begin an entry. The page asks for a level by name only after a save that
  failed, since then the saved one is not the one chosen. A read that was begun for another level than the one chosen
  meanwhile is not shown.
- **Tests.** `LogTailTest`: each level, an entry of several lines, the lines before the first entry, an unknown word,
  the limit counted in lines of the level, `hidden`. `SettingsStoreTest`: the two keys, the defaults, a level that is
  none of the four. `ApiTest`: the saved level, the query, the save, the refusals, the settings leaving the two alone.
  `view.test.cjs`: the date cut, the note. `app.test.cjs`: the controls come up as saved and only with the log; a
  level and the date at once, saved, at the top; again at the next showing; the failed save; the settings left for the
  log. `layout.test.cjs`: the line, its order, its four levels, its type.
- **In a real window** (the on-request test): the line is there with the log and with no other panel; with the date off
  the newest line begins with its time, with it on with its date; `Warning` leaves no line of information. It passes.
- **Also:** `UsageServiceTest.theServiceGoesOnRefreshingAfterARestart`, from phase 49, asked for a refresh while the one
  the restart began could still be under way, and failed now and then; it waits for that one to end.

**Not checked by a test.** How the line looks, and the dropdown on the dark theme.

### 53 to 58. Gray boxes for no usage, the border and the dragging without title bar, debug lines, the format error, select all

Requirement: Gray boxes for no usage, a border and dragging without the title bar, debug lines, the format error, and select
all. *Decided by the person:* every gray box has the green border; `No data` becomes gray boxes too; the black border is left
out only while the red one is shown, and has the colour of the text; debug lines are always written and shown with `Debug`;
the format error is a line of its own with the beginning of the response; the button switches the thirteen elements, all on, or
all off when all are on; and `Title bar` stays as it is, not previewed. Three lines become debug lines: the window's size, a remembered height, and
the title bar coming and going; the lines of a refresh stay information. The position of the window is not remembered. The icon
of the button is a ticked box, always the same. Built **one at a time, in the order of the table**, a phase and a version each.

| Phase | Version | Piece |
| --- | --- | --- |
| 53 | 0.41 | Gray boxes in the place of `No usage reported` and `No data`; a green border round every gray box |
| 54 | 0.42 | A border round the window that has no title bar |
| 55 | 0.43 | Dragging the window that has no title bar |
| 56 | 0.44 | Debug lines in the log: the window's size, a remembered height, the title bar |
| 57 | 0.45 | An error in the log for a response whose format cannot be processed |
| 58 | 0.46 | The button of `Main view` that selects every element, or none |

### 53. Gray boxes for no usage and no data, and the green border

**Status: built (version 0.41).** Requirement: Gray boxes in the place of `No usage reported` and `No data`.

- **The backend is as it was.** `StatusDisplay` still gives the two as `placeholder`, which is now the reason and no longer
  a text of the row.
- **The page.** `render` draws the box for the time and the amounts that have no value in the two states, as it does at the
  start, and puts the reason after the element's tooltip. The element `placeholder` and its rule are gone. The group of
  the amounts has the class `boxes` while it holds boxes, so that it is not dimmed.
- **The border.** `.skeleton` had its gray by `opacity`, which would fade a border with it: the gray is `rgba(138, 143,
  152, 0.33)` now, the border `1px solid var(--ok)`, with `box-sizing: border-box`. `.stale .skeleton` and
  `.stale .spend.boxes` are at full strength.
- **Found on the way:** the interval was drawn through `renderOptional` with the arguments of before phase 51; it has its
  tooltip now.
- **Tests.** `app.test.cjs`: the boxes, their tooltips and the slash with no usage; an amount that is switched off; the
  figures back; no data with the time as a box. `layout.test.cjs`: the gray, the border, no `placeholder`, the rule
  against dimming.
- **In a real window** (the on-request test): with nothing listening at the address the amount used is a box with a
  border of 1 pixel in the green, its tooltip is `Credits used: no data`, and the row has no word; a figure has no
  border. It passes.

**Not checked in a real window.** `No usage reported`: the fake backend has no scenario for a reading without usage, so
that state was seen by the page test only. How the green border looks on the gray.

### 54. A border round the window that has no title bar

**Status: built (version 0.42).** Requirement: A border round the window that has no title bar.

- **The backend** says in the status, as `display.titleBar`, whether the setting gives the window its title bar; the page
  did not know the setting outside the settings view.
- **The page.** `bare()`: the setting off, no panel shown, the fake backend not in use. `#app` then has the class `bare`,
  a border of 1 pixel in `CanvasText`, and `contentSize` adds 2 pixels as it adds 6 for the red border.
- **At the edges.** The app was as wide as the row and the window as wide as the row rounded up, so the right and the bottom
  side of a border could be a fraction of a pixel inside the window. While it has a border and no settings view is shown
  the app fills the window (`min-width` and `min-height` of 100%), for the red border as well.
- **Tests.** `StatusDisplayTest`, `view.test.cjs`: the flag. `app.test.cjs`: the class and the size with the title bar
  off, with each panel, with the title bar on, and with the fake backend on and off. `layout.test.cjs`: the two rules.
- **In a real window** (the on-request test, title bar off): with the fake backend switched off the app has the border of 1
  pixel and the window is larger by 2; with a panel shown it has none; the right and the bottom side of the border are at
  the edges of the window, for the red border too. It passes.

**Not checked by a test.** The colour on the dark theme, and how the border looks.

*Seen and left:* with the debug settings shown and the red border on, the bottom side of the border was 2 pixels above the
bottom edge of the window in the probe; the window of a settings view is rounded up from the page's own height.

### 55. Dragging the window that has no title bar

**Status: built (version 0.43).** Requirement: Dragging the window that has no title bar.

- **The host moves the window.** `UsageApp.letTheWindowBeDragged` puts three event filters on the `WebView`, which stays
  the same node when the window is exchanged for the title bar: pressed, with the left button and no title bar, it asks
  the page whether a move may begin there and notes where the mouse and the window are (`WindowDrag`); dragged, it puts
  the window where `WindowDrag` says and consumes the event; released, the move is over.
- **The page says where.** `window.dragStartsAt(x, y)`: not on a button or a control (`docs/api.md`, Window host
  contract). That was the open choice of the plan; the host asks at each press, so the page tells it nothing in advance.
- **Nothing is consumed but the moves of a drag that moves the window**, so a button gets its press and its release as
  before.
- **Tests.** `WindowDragTest` for the arithmetic; `app.test.cjs` for where a drag may begin.
- **In a real window** (the on-request test, title bar off), with mouse events made by the test: a drag of 40 by 25 pixels
  that begins on the time moves the window by 40 and 25, and so does one that begins at the edge of the window; one that
  begins on the gear or on the refresh button moves nothing; with the history shown, and so the title bar, a drag on the
  time moves nothing. It passes.

**Not checked by a test.** A drag with a real mouse: the events of the test are made by the program and handed to the
`WebView`, which is where the system's events arrive too, but nobody held a mouse. Whether a click on a button still feels
as it did, and a drag over several screens.

### 56. Debug lines

**Status: built (version 0.44).** Requirement: Debug lines in the log.

- **`Logging`** leaves the root logger at information and sets the logger `org.example`, which is above all of the
  program's own, to `FINE`; it holds that logger, since the logging system keeps one only while somebody does, and gives
  it its old level back when it is closed. The two handlers took every level already.
- **The three lines** are written at debug: `Window fitted to` and the two of the title bar in `UsageApp`, and `The
  <panel> window height is now` in `IntervalSettings`.
- **Tests.** `LoggingTest`: a debug line of the program is in the file with `FINE`, a trace line is not, a debug line of
  another package is not, and the level is put back. That the log panel shows `FINE` with `Debug` only is in
  `LogTailTest` since phase 52. No test looked for the three lines among the information lines.

**Not checked by a test.** The three lines in the log of a real run: the on-request probe starts the window class without
the program's logging, so its log is the console's.

### 57. The format error

**Status: built (version 0.45).** Requirement: An error in the log when the response has a format the program cannot
process.

- **Where.** `UsageClient.fetch` is where the body is at hand: it catches the `UsageParseException` of the parser, writes
  the error with the reason and the first 2,000 characters of the body (`UNPROCESSED_CHARACTERS`), and throws it on, so
  everything after is as it was: the failed refresh, its warning, the row, the error log.
- **Masking** is the log's own, as for every line (`Logging`, `Redaction`); nothing is done to the body here.
- **Tests.** `UsageClientTest`, against a server of its own: a body that is not JSON, one that is not an object, one with
  no `spend`, one whose `spend` has neither amount, each with its reason and the body after it; an empty body as
  `(empty)`; a body of 2,500 characters cut at 2,000 with the note; no such error for a good response or an HTTP 500.

**Not checked by a test.** The line in the log file of a real run, with the fake backend's `not-json` scenario.

### 58. Select every element, or none

**Status: built (version 0.46).** Requirement: A button that selects every element of `Main view`, or none.

- **The page.** `set-all`, a button inside the legend of `Main view`, with an icon of its own, a square with a tick.
  `selectAllOrNone` sets the thirteen checkboxes of `ELEMENT_FLAGS`, which is `MAIN_FLAGS` without the currency symbol,
  and calls the preview; nothing else is new, so saving, `Cancel` and the panel buttons are those of any click.
- **Tests.** `app.test.cjs`: all on from some, all off from all, all on from none and from all but one; the two
  properties untouched; the row at once; `Cancel`; the save; nothing before the settings are read. `layout.test.cjs`:
  where the button is, its icon, its tooltip and name, no text.
- **In a real window** (the on-request test): the button is 16 by 16 pixels with its middle a pixel from the middle of
  the heading; a press with eight elements on switches all thirteen on and the window is as wide as the row then needs;
  another switches all off. It passes.

**Not checked by a test.** How the icon looks beside the heading.

The requirements have no section "specified, not yet built" any more.

### 59. An answer that came and has no spend is said explicitly

**Status: built (version 0.47).** Requirement: An answer that came and has no spend is said explicitly. It came from a run
against a seat-based account, whose answer the program read as a success and showed as gray boxes with nothing said.
*Decided by the person:* a message line below the row; the seat-based account is named; the log warns when it begins and
informs after that; it stays a reading and is no failure.

- **The reading knows.** `UsageSnapshot` has `otherLimits`, set by `UsageParser` when the answer has a list `limits`
  with something in it or a window `five_hour` or `seven_day`, and `noSpendMessage()`, which is one of two texts for a
  reading with no spend and nothing for one with spend. A reading taken from the history says nothing of other limits.
- **The log.** `UsageService.refresh` writes that text in the place of `Usage refresh succeeded`, as a warning unless the
  reading before had no spend either.
- **The row.** `StatusDisplay` gives the text as `message` of the kind `info` when there is no failure to say; the page
  draws it as any message line, and `.note-info` is muted.
- **Tests.** `UsageParserTest`: the three signs of other limits, none of them, a reading with spend. `UsageServiceTest`:
  warning, information, success, warning again; no error and no entry in the error log. `StatusDisplayTest`,
  `app.test.cjs`, `layout.test.cjs`: the line, its two texts, its colour, that it goes with spend and gives way to a
  failure.

**Not checked by a test.** The line in a real window against the real account. *Seen:* `UsageServiceTest
.otherFailuresAreNotHeldBack` failed once in nine runs with `took 1464`, a limit of time, while the machine was
compiling; it was not changed.

### 60. The width of the row and of its message lines

**Status: built (version 0.48).** Requirement: The width of the row and of its message lines. *Decided by the person:*
every panel; with no panel a least width while a message is shown; the elements stay at the left.

- **The cause.** `.top` is `width: max-content`, the row's own width, and a `.note` in it is `width: 0; min-width: 100%`,
  so a message is as wide as the row and never makes it wider.
- **With a panel.** `#app.open .top` and `#app.fit .top` are `width: auto` with no largest width, so the row's box is as
  wide as the app, which is the window then. `contentSize` took the row's width from that box: it sets the box back to
  `max-content` and 900 pixels for the moment of the measuring and lets go again, so the size asked for does not follow
  from the size the window has.
- **With no panel.** The page puts the class `noted` on the row's box while one of the three message lines is shown
  (`markNoted`, after each drawing of the row, each change of the line of lost contact, and each time the size is asked
  for), and the style sheet gives that box 300 pixels at least when no panel is shown.
- **Tests.** `layout.test.cjs` for the rules. `app.test.cjs`: the class for each of the three lines and without them;
  the measuring with the stretch let go of, for each panel, and the stretch given back.
- **In a real window** (the on-request test), with the message of a failed refresh and every element of the row hidden,
  the case that brought the fault up: with the settings shown the row is 42 pixels of buttons, its box is as wide as the window
  (265), and the message is 249 pixels wide and two lines tall; with no panel the window is 302 pixels wide, 300 and the
  border, and the message two lines; with every element on the window is as wide as the row, 636. It passes.

**Not checked by a test.** The log, the history and the error log with a message and a narrow row, in a real window: the
rule is the one of the settings. How it looks.

*Found on the way, in the tests only:* `UsageServiceTest.afterARateLimitTheEffectiveIntervalIsTheLongerWaitAndItEasesBack`
failed about one run in six. It waited 5 seconds for something that comes after 4.9: the hold eases by an eighth at each
success, so the fourth call is 2.6 and 2.3 seconds after the second. The program is as it was; that wait is 15 seconds
now. And the on-request window test looked for the end of the probe's line where the probe has since added to it.

### 61 to 65. The word `of`, bigger tooltips, the icon, the zoom of the lists, the automatic interval

Requirement: The word `of`, bigger tooltips, the program's icon, the zoom of the lists, and the automatic interval. *Decided by
the person:* the word `of`; tooltips of 14 pixels; a gauge as the icon; one text size for the three lists, 10 to 20 pixels,
with the buttons at the top of each list; the search closes its bounds in, takes the place of the doubling and still honours
`retry-after`, is saved, and is moved only by an answer of Anthropic; `Automatic` is off by default and its field shows the
interval of the search. Built **one at a time, small ones first**, a phase and a version each.

| Phase | Version | Piece |
| --- | --- | --- |
| 61 | 0.49 | The word `of` in the place of the slash |
| 62 | 0.50 | Tooltips of 14 pixels |
| 63 | 0.51 | The program's icon |
| 64 | 0.52 | The zoom of the lists |
| 65 | 0.53 | The automatic interval |

### 61. The word `of`

**Status: built (version 0.49).** Requirement: The word `of` between the amount used and the budget.

- `#slash` is `#of` in `index.html`, with the text ` of `, and `.of` sets it back: regular weight and the muted gray.
- **14 pixels, not 12.** The requirement was written with 12; the page has a rule, and a test of it, that nothing of the row
  is under 14 pixels, which is there for the same reason the tooltips and the lists are being made larger. The word has
  the least the rule allows, a pixel under the figures.
- **Tests.** `layout.test.cjs`, `app.test.cjs`. In a real window (the on-request test) the row reads `187.39 of
  1,000.00`, the word is 14 pixels, of weight 400, gray, and its foot is level with that of the amount.

**Not checked by a test.** How it looks.

### 62. Tooltips of 14 pixels

**Status: built (version 0.50).** Requirement: Bigger tooltips.

- The tooltips are the `title` attributes of the page, which the `WebView` shows as tooltips of JavaFX; the page's style
  sheet has no say in them. The scene has a style sheet of its own now, `window.css` in the resources, with one rule:
  `.tooltip { -fx-font-size: 14px; }`. The scene is the same one when the window is exchanged for the title bar, so it
  is given once, in `UsageApp.start`.
- **The risk did not come true**: the view's tooltips take the scene's style, so the page draws none of its own.
- **Tests.** `WindowStyleTest` for the file and its rule. In a real window (the on-request test) the mouse is moved onto
  the amount used and left there: the tooltip that comes reads `Credits used, in USD` and its font is 14.0. It passes.

**Not checked by a test.** The tooltips of the settings and of the history's lines, which are the same control. How it
looks.

### 63. The program's icon

**Status: built (version 0.51).** Requirement: An icon of the program's own.

- **The files.** `icons/icon-16.png`, `-32`, `-48` and `-256` in the resources, drawn by `src/main/icons/draw-icon.cjs`,
  which needs only Node: it works out each pixel from the shapes, with a grid of samples for smooth edges, and writes the
  PNG by hand. It is run again only when the icon is to look different; the build does not run it.
- **The window.** `AppIcons.load()` reads the four, and `UsageApp.start` gives them to the first window. `StageSwap`
  already gave a new window the icons of the one it replaces, which had none until now.
- **Tests.** `AppIconsTest`: the four sizes, each file a square of its size with see-through corners, the dark ground,
  the hub and the arc where they belong, and the needle to the upper right and not to the left. In a real window (the
  on-request test) the window has icons of 16, 32, 48 and 256 pixels at every step, with the title bar and without.

**Not checked by a test.** That the title bar and the taskbar show it: the test reads the icons the window was given, not
the screen. How it looks at 16 pixels.

*In the on-request window test, found on the way:* the tooltip of phase 62 did not always come at the first try for the
mouse of the test, which is not the system's, so the probe tries up to five times and puts the tooltip away afterwards.
And a step of the probe looked at the window a fixed second and a half after a button was pressed, which on a busy
machine was sometimes before the title bar had come: it waits now until the window has the title bar when the page shows
a panel, and the test allows the probe three minutes where it allowed one.

### 64. The zoom of the lists

**Status: built (version 0.52).** Requirement: A zoom for the lists.

- **The setting.** `listFontSize` in `Settings` (`withListFontSize`) and `settings.json`, after the log's two choices.
  The page has it from `GET /api/config` at startup and saves it with `POST /api/text-size`; `POST /api/settings` keeps
  it as it is.
- **The size.** The page sets `--list-size` on the document, and `.panel-lines` takes its size and its line height from
  it; the probe that measures the history's table takes them too, after the font it is given, so the window is sized for
  the text that is drawn. The notes and the line of the controls are outside that rule.
- **The line.** `log-controls` is the line at the top of every list now and holds the two buttons; the level and the date
  are in a part of it, `log-choices`, that is shown for the log.
- **Tests.** `SettingsStoreTest`, `ApiTest`: the key, its default, its range, the save, the refusals, the settings leaving
  it alone. `app.test.cjs`: the saved size at the start, the line for each list and for nothing else, a press for all
  three lists and saved, the two ends and the disabled button, the failed save. `layout.test.cjs`: the buttons, their
  icons, the two rules.
- **In a real window** (the on-request test): the history at 12 pixels has lines of 15; eight presses make it 20 pixels
  with lines of 25 and the window 453 pixels wide where it was 371; eight presses back give 12, 15 and 371. The error log
  has the line of the buttons, and with no list there is none. It passes.

**Not checked by a test.** The size after a restart of the real program, which the tests of the store and of the
configuration stand for. How the two icons look.

### 65. The automatic interval

**Status: built (version 0.53).** Requirement: The automatic interval.

- **The search** is `IntervalSearch`, with no clock and no thread: the interval and the two bounds, a success, a 429, and
  whether a 429 set the bounds back. Figures that cannot be a state of it begin it anew.
- **`UsageService`** has the search while it is on (`setAutomatic`), and `settleBackoff` hands an outcome to
  `settleSearch` then: a 429 moves it up, a success down, another failure not at all, and its interval is given to
  `RefreshSchedule`. The back-off that doubles is cleared and not used; a longer `retry-after` is put on the schedule for
  the one wait and taken off at the next answer. The configured interval is kept in the service and is in force again
  when the search is switched off.
- **Saving.** The service tells a listener where the search is, after it has moved, with its lock released, since the
  listener writes a file and the settings call the service under their own lock. `AppRuntime` saves it through
  `IntervalSettings.storeSearch`, and switches the search with the setting.
- **`Settings`** has `autoInterval`; the three figures of the search are kept in `settings.json` beside the settings, like
  the remembered heights (`SettingsStore.Search`), and a save of the one leaves the others as they are.
- **The API.** The settings carry `autoInterval` and, beside them, `configuredIntervalSeconds`; the interval in force is
  that of the search while it is on, also when it is shorter than the configured one.
- **The page.** The checkbox in the line of the interval. The field is disabled and shows the interval in force, from the
  status, at each drawing of the row; what is compared and sent is the configured interval.
- **Tests.** `IntervalSearchTest`: the steps down, a refusal, the bounds set back at the upper bound and a second below
  it, the limits, 5,000 random answers, a saved state and figures that are none. `UsageServiceTest`: each answer moves
  it and is given to be saved, a 500 does not, a longer `retry-after` for the one wait, switching off, a settled search.
  `SettingsStoreTest`: the flag, the search beside the settings and the heights, figures that are not three whole
  numbers. `ApiTest`: the setting, both intervals, the search saved by a run and gone on from. `app.test.cjs`,
  `layout.test.cjs`: the checkbox, the field in each state, what is sent, `Restore defaults`.
- **In a real window** (the on-request test): with the settings shown the field can be typed in; `Automatic` checked, it
  is disabled and shows 60, the interval in force; unchecked, it can be typed in again; the checkbox is in the line of
  the field. It passes.

**Not checked against the real endpoint.** Nothing here was run against Anthropic: where the search settles with a real
account, and how often it is refused once it has, is not known. The fake backend answers without a limit, so against it
the search goes down to 11 seconds and stays.

The requirements have no section "specified, not yet built" any more.

### 66 to 69. The wait after a 429, no selecting, the no-spend text on hover, the About view

Requirement: The wait after a 429 with the automatic interval, no selecting, the no-spend text on hover, and the About view.
*Decided by the person:* the search follows the wait the server names; nothing at all can be selected; the no-spend line is
shown below the row while the mouse is on a gray box; the three fixes first, a version each, then the About view.

| Phase | Version | Piece |
| --- | --- | --- |
| 66 | 0.54 | With the automatic interval, the search follows a longer wait the server names |
| 67 | 0.55 | Nothing in the window can be selected |
| 68 | 0.56 | The no-spend text is shown while the mouse is on a gray box |
| 69 | 0.57 | The About view |

### 66. The search follows the server's wait

**Status: built (version 0.54).** Requirement: With the automatic interval, the search follows a longer wait the server names.

- `IntervalSearch.rateLimited(serverSeconds)` makes its step and then, when the server's wait is longer than the interval
  it has, takes the wait as the interval, at most 300 seconds, with the upper bound at 300 when the wait is above it.
- `UsageService.settleSearch` hands the server's wait over, in whole seconds rounded up. The one longer wait is still put
  on the schedule when it is above the interval, which is now only the case for a wait above 300 seconds.
- **Tests.** `IntervalSearchTest`: the case of the log, refused at 11 with 300; a wait above the upper bound and one within
  the bounds; a wait no longer than the step; one above 300; the way down afterwards. `UsageServiceTest`: the interval in
  force, the countdown, the message and what is saved all say 300; a wait of 500 is honoured beside an interval of 300.

**Not checked against the real endpoint.** The fault came from a run against Anthropic; the repair was tested with the
figures of that run, not by running it again.

### 67. Nothing can be selected

**Status: built (version 0.55).** Requirement: Nothing in the window can be selected.

- **The style.** `user-select: none` on `html` and `body`, which every element takes from there, and `text` for
  `.settings input[type="text"]` alone; the rule that made the settings selectable is gone.
- **Beside it**, in case a style is not honoured: the page refuses a selection the mouse begins (`selectstart`) and passes
  over the key that selects everything, both except in the field to type in.
- **Tests.** `layout.test.cjs`: the one rule that allows selecting. `app.test.cjs`: the key and the beginning of a
  selection, in the row and in the field. In a real window (the on-request test), with the history shown: a drag from the
  time of the row across the list selects 0 characters, selecting everything selects 0, and the style in force is `none`
  for the row and for a line of the list and `text` for the field. It passes.

**Not checked by a test.** A double click with a real mouse, which is how the blue of a selection in the row and the lists most likely came
about; the style that forbids it is the one the test reads.

### 68. The no-spend text on hover

**Status: built (version 0.56).** Requirement: The text of an answer with no spend is shown while the mouse is on a gray box.

- **The backend** gives the text as `display.noSpend`, and no longer as the `message` of the kind `info`, which is always
  shown.
- **The page** has an element for it, `hover-note`, and shows it while the mouse is on `percent`, `used` or `limit` and
  that element is a gray box, as it shows the line of a 429 while the mouse is on the countdown. It counts as a message
  line for the least width of the window.
- **A scenario of the fake backend, `seat-based`**, was added for it: the answer of such an account, cut down. Until now
  the fake backend had no reading with no spend, so this state could be seen in no window but against Anthropic.
- **Tests.** `StatusDisplayTest`, `view.test.cjs`: the field. `app.test.cjs`: no line until the mouse is on a box, for each
  of the three boxes, from one box to the next, not on a figure, not at the start nor with no data, and beside the red
  line of a failure. `FakeBackendTest` for the scenario.
- **In a real window** (the on-request test), with the fake backend answering `seat-based`: gray boxes and a window of 40
  pixels; with the mouse on the amount used the line is there, in the muted gray, and the window is 79 pixels tall; with
  the mouse on the refresh button the line is gone and the window 40 again. It passes.

**Not checked by a test.** A real mouse: the moves are made by the test.

### 69. The About view

**Status: built (version 0.57).** Requirement: The About view. *Decided by the person:* it is opened by a button `About` in
the button row of the settings; it shows where the files are, Java and the system, and debug mode, with a logo of 96 pixels;
the settings are kept and `Back` returns to them.

- **A request of its own**, `GET /api/about`: the name and version, the three full paths, the versions of Java, JavaFX and
  the system, and the two facts of debug mode.
- **The logo** is the icon of 256 pixels, which `LocalWebServer` gives the page as `/icon.png`, the one file it serves that
  is not among the page's own; the page draws it at 96.
- **The page.** `about-view` is a part of the settings view and is shown in the place of the form and its buttons, which
  are hidden and not emptied; `openPanel` stays `settings`, so the title bar, the saving by a button of the row and the
  kind of window are those of the settings. The view has no width of its own: the window keeps the width the settings
  were last measured at.
- **Tests.** `ApiTest`: the request, and that the log and the history still say nothing of where a file is.
  `LocalWebServerTest`: the logo. `app.test.cjs`: opening, the three texts of debug mode, `Back` with unsaved changes and
  the preview kept, a button of the row saving, a request that fails, the width. `layout.test.cjs`: the button and the view.
- **In a real window** (the on-request test, in debug mode with the fake backend): the view is shown in the place of the
  form, the logo is drawn at 96 by 96 from a file of 256, the version is that of the program, the line of debug mode reads
  `on, the fake backend is in use`, the history is the file of the invented readings, the window is as wide as with the
  settings and less tall, nothing reaches past its edge, and `Back` gives the form at the size it had. It passes.

**Not checked by a test.** How the view looks: the test measures it.

The requirements have no section "specified, not yet built" any more.

### 70 to 75. Hover on every box, less room in the row, the history's columns, the paths of About, the fake backend, cost control

Requirement: Hover on every gray box, less room in the row, the history's columns, the paths of About, a fake backend to
configure, and cost control. *Decided by the person:* every gray box shows the no-spend line; room is reserved for the usual
values only; the history's spare width is equal gaps between the columns; a click on a path opens its folder with the file
marked; the fake backend is usage-based or seat-based, shaped in the debug view for the scenario `normal`, refuses a request
that comes too soon after the one before, and delays by a random time in milliseconds; cost control is settings only for now. The fake
backend's budget and starting amount can be set too; the scenario `seat-based` is removed; a daily limit above the monthly
one is refused. Built **one at a time in the order of the table**, a phase and a version each.

| Phase | Version | Piece |
| --- | --- | --- |
| 70 | 0.58 | The no-spend text over every gray box |
| 71 | 0.59 | Less room reserved in the row |
| 72 | 0.60 | The history spreads its spare width over all columns |
| 73 | 0.61 | The paths of About: links, and a button to copy each |
| 74 | 0.62 | A fake backend that can be configured |
| 75 | 0.63 | Cost control: the two limits, as settings |

### 70. The no-spend text over every gray box

**Status: built (version 0.58).** The three elements the page listens on are six: the amounts, the two changes and the
request duration. The condition, that the element under the mouse is a gray box and the reading has no spend, is the one
there was. Tests in `app.test.cjs`: each of the six as a box, and a figure among them that has no line.

### 71. Less room in the row

**Status: built (version 0.59).** The three `min-width`: `.delta-used` 4.5ch for `+9.99` (it was 5.5), `.delta-time` 3ch
for `99s` (5.5), `.delta-duration` 5ch for `999ms` (7.5). The requirement was written from a guess at what the room
had been; the figures built are the ones that take the gaps of the picture away, and the requirement says so. Tests in
`layout.test.cjs`. In a real window (the on-request test) the row with every element switched on is 593 pixels wide,
where it was 636 with the room there was before.

### 72. The history's columns

**Status: built (version 0.60).** A row of the history was a grid whose first track, the time, took what room there was. The
first track is fixed now like the others, 8 characters and 19 with the date, and the grid has `justify-content:
space-between`, which shares the spare width out equally between the tracks. The header and the rows are separate grids of
the same tracks in the same width, so they stay in line. The copy the table is measured on is as wide as its tracks, as
before. Tests in `layout.test.cjs`; in a real window (the on-request test) the first column begins at the left edge, the
last ends at the right, the three gaps are equal and wider than the least, and the header's column ends where the row's does.

### 73. The paths of About

**Status: built (version 0.61).** Requirement: The paths of the About view are links, with a button to copy each.

- **`Desk`** is what the program asks of the desktop: `reveal` a file in its folder and `copy` a text. The system's one
  starts `explorer.exe /select,<path>` on Windows, `open -R` on macOS and `xdg-open` on the folder elsewhere, the folder
  alone for a file that is not there; and puts a text on the clipboard of JavaFX, on its thread.
- **Two requests** take the name of a file and never a path, `POST /api/about/open` and `/api/about/copy`; the name is
  one of three or the request is refused, and the path is the program's own. Nothing of a request reaches a command line.
- **The page.** Each of the three paths is an `a` with a small button beside it; the button's icon is a tick, and its
  tooltip `Copied`, for a second and a half after a copy.
- **Tests.** `DeskTest`: the command for each system, for a file that is not there, for a relative path, and that a path
  is one argument however it is spelled. `ApiTest`, with a desktop that only notes what it is asked: the three names,
  everything else refused and the desktop not asked, and a desktop that refuses. `app.test.cjs`, `layout.test.cjs`: the
  links, the buttons, the tick and its end, the red line.
- **In a real window** (the on-request test): the button beside the path of the settings puts on the clipboard exactly
  the full path the view shows, and says `Copied`; the path is drawn at weight 400, in the blue, underlined, with the
  pointer of a link. What was on the clipboard before is put back by the test. It passes.

**Not checked by a test.** A click on a link: it opens a window of the file manager, which the test does not do to the
desktop it runs on. The command it starts is tested, the starting of it is not.

### 74. The fake backend to configure

**Status: built (version 0.62).** Requirement: A fake backend that can be configured.

- **`FakeOptions`** holds the six: the account, the largest increase, the budget, the starting amount, the time within
  which a request is refused and the longest request, with what each may be and the words for what is wrong.
- **`FakeBackend`** is started with them and takes others while it runs (`configure`). Its ordinary answer waits for a
  time that is drawn, refuses a request that comes too soon after the answered one before it, and is otherwise the document
  of the account. `UsageDocument` keeps the amount used, which begins at the starting amount and grows by what is drawn.
  The numbers are drawn through a function the backend is given: chance in the program, and in a test what the test says.
  `FakeBackend.start(scenario)`, which the tests of before use, is the backend as it was, with the full step each time.
- **Saved** beside the settings like the search and the heights (`SettingsStore.loadFake`, `saveFake`), after the three
  switches. A file that still names the scenario `seat-based` is read as `normal` with the account `seat`.
- **`AppRuntime.applyDebug`** takes the options with the three switches: when only the options changed it saves them and
  tells the backend that runs, so that the amount goes on; when a switch changed it opens the source anew, with them.
- **The API and the page.** `/api/debug` gives and takes the six, and the kinds of account; the debug view has a group for
  them. The page formats no number and has no currency sign, by two rules of its own that tests keep: so the two amounts
  come as finished texts and go back as they were typed, and their unit in the view is `USD`.
- **The scenario `seat-based` is gone**, with its place in the lists.
- **Tests.** `FakeBackendTest`: as it was by default; the starting amount and what is drawn; no increase, and past the
  budget; a seat-based account; refusals by a clock the test moves, counted from the answered request; the wait; other
  options while it runs; options that cannot be. `SettingsStoreTest`: the six keys, each beside the others, values that
  cannot be, the old scenario. `ApiTest`: the defaults, the save, what is left out, the refusals. `app.test.cjs`,
  `layout.test.cjs`: the group, what is sent, the reason in red.
- **In a real window** (the on-request test, started with an increase of 0): the debug view shows `usage`, 0, 1000.00,
  186.02, 0 and 0, and the row 186.02 of 1,000.00; a starting amount of 50.00 makes the row show 50.00; refusing within
  3600 seconds makes the next request a 429, with the countdown in red and the reading kept; the account `seat` gives the
  gray boxes and the line on hover.

**Not checked by a test.** The amounts growing by chance in a real run, and the automatic interval against a fake backend
that refuses: that is what this was built for, and is for a person to try.

### 75. Cost control

**Status: built (version 0.63).** Requirement: Cost control: a monthly and a daily limit, as settings.

- **`CostControl`** holds the two limits, in cents, and whether the daily one is worked out; counts the weekdays of a month
  and divides, rounded down to the cent; and says what is wrong with limits that cannot be.
- **`Settings`** has it as one member, saved under three keys after `autoInterval`, a limit there is none of as `null`.
- **The API.** The settings give the limits as finished texts, and the daily limit in force this month beside them;
  `POST /api/settings` takes them when they are given. `GET /api/cost/daily?monthly=` says what follows from a monthly
  limit, for the settings view: the page formats and works out nothing, by its own rules.
- **The page.** A group of the settings view. The field of the daily limit is disabled and shows what the backend worked
  out while `Automatic` is checked; an answer to an earlier question that arrives late is passed over.
- **Nothing reads the limits.**
- **Tests.** `CostControlTest`: the weekdays of months that begin and end on various days, every month from 2020 to 2032
  between 20 and 23, the division, the limit in force, limits that cannot be, amounts as typed and as written.
  `SettingsStoreTest`, `ApiTest`: the keys, the save, what is left out, the refusals, the question about the daily limit.
  `app.test.cjs`, `layout.test.cjs`: the group, each state of the field, what is sent, a late answer, `Restore defaults`.
- **In a real window** (the on-request test): with no limits the fields are empty and the daily one disabled; a monthly
  limit of 440 gives the daily limit the program works out for the month the test runs in, 20.00 in October 2026 with its
  22 weekdays; unchecked, the field can be typed in; emptied again, there is none. That run is where the late answer was
  seen, and put right.

**Not checked by a test.** How the group looks.

### 76. No back-off, a smoother search, tabs in the settings

**Status: built (version 0.64).** Requirements: Refresh behavior, The automatic interval, Settings (tabs, the history's columns), The About view, Debug mode.

- **No back-off.** `Backoff` and its use in `UsageService` go: the delay is `effectiveIntervalSeconds()`, which is the configured interval with `Automatic` off and the search's with it on. With `Automatic`
  off a `Retry-After` is only logged. The log lines `Easing the back-off` and `The back-off is over` go. `Next try in` of a 429 is the interval in force. The red countdown stays after a 429 until a request is answered
  without one.
- **The search.** `IntervalSearch` is rewritten in place (it keeps its name) and replaces the binary search: the interval, the refused interval `R` (or none), a count of answers without a 429 for the step and one for the decay.
  After a 429: `R := interval`, `interval := min(300, max(ceil(1.1 R), R + 1))`; with a longer `Retry-After` of the server (whole seconds, capped at 300, and so no wait beside the interval) the interval is that. After 8 answers without a 429: `-1`, not below `R + 1`
  and 10; after 60: `R - 1`; with no `R`: `ceil(0.9 × interval)`, floor 10. Only an answer of Anthropic moves it. The log lines are `Automatic interval: <N> s (refused at <R> s)` / `(no refusal yet)`.
- **`Settings`** (four more components at the end of the record, `withHistoryColumns`) loses `autoLowerSeconds` and `autoUpperSeconds` and gets `autoRefusedSeconds` (a number 10 to 300 or none); `SettingsStore` reads an old file without the new key as no refusal, and writes the new one only. Four new history
  keys, `historyTime`, `historyUsed`, `historyLimit` and `historyCurrent`, besides `historyDeltaUsed` and `historyDeltaTime`; the backend leaves an off column out of the finished lines and the header, so the
  page still draws only text. The CSV keeps its `interval` column, recorded from `effectiveIntervalSeconds()` in `AppRuntime.fetchAndRecord`.
- **The page.** The settings view becomes tabs: `Main view`, `History view`, `Cost control`, `Debug` (only in debug mode), `General` (`Request`, `Window`, `Log`), `About`; a clickable strip that scrolls sideways, two arrows disabled at the ends;
  always the first tab on opening; the height follows the tab, the width is the least width of the widest tab (`contentSize()` as now). `Save` and `Cancel` under every tab; `Restore defaults` and the buttons `About` and `Back` go, with
  their tooltips. The yellow debug button and its view go, and with them its own `Save`/`Cancel`; the debug settings are saved with the rest.
- **Also.** The page no longer reports the panel name `debug` (`WindowFit` still accepts it, harmlessly); `POST /api/settings` may leave the four new history keys out (they keep their value); the Debug tab loads `/api/debug` when the settings open and `Save` sends it after the settings, only when it was changed, showing the Debug tab with the reason if it is refused; `AppInfo.VERSION` becomes `0.64`; `docs/api.md` follows (settings keys, `autoRefusedSeconds`, no debug panel).
- **Tests.** `IntervalSearchTest` (a 429 at 20 gives 22, at 100 gives 110; 8 answers give -1, never below `R + 1`; the decay; no `R`; the ceiling of 300; a `Retry-After`; a simulated server that refuses below a limit, with the search staying within a few seconds of it
  and few 429s); `UsageServiceTest` (a 429 with `Automatic` off leaves the delay at the interval, whatever `Retry-After` says); `SettingsStoreTest` (the new keys, an old file); `ApiTest`, `HistoryReaderTest` (each column off and on);
  `app.test.cjs`, `layout.test.cjs` (strip, arrows, first tab, no `Restore defaults`, no debug button, the debug tab only in debug mode, About as a tab with `Save` and `Cancel`). The on-request real-window test: the heights and widths of the tabs.

### 77. Blue tab, no arrows, a whole strip, the change in the amount, restoring the window, one green

**Status: built (version 0.65).** Requirements: Settings (the tabs), Changes between readings, The usage history, The window is restored at start, The look of the page, The button of the open panel.

- **Tabs.** The selected tab is blue (`--note` blue of the history note, `#0969da` / `#58a6ff`), not `--active`; the two arrows and their code and tests go; the window's least width while the settings are shown is
  the width of the whole strip, in one line, besides the widest tab, so there is no horizontal scrollbar (measure the strip's `scrollWidth` as the tabs are measured; a test with every row element hidden).
- **One green.** `--active` goes: the open panel's button and the startup lines of the history use `--ok`. The size of the open button stays.
- **The change in the amount used.** `HistoryDeltas` works out each row's change against the last earlier row of the whole file that has an amount used (failed rows skipped, no run border), and the amount itself when
  there is none; only a row with an amount has one. Computed on the file, not on the shown lines; the time since the previous reading keeps its rule (on the shown lines, the same run). The history shows it only when it is greater than
  0.00, the row shows it whenever the reading has an amount (0.00 included), a gray box when it has none. The zero usage line is defined as before and still hides lines.
- **Restoring the window.** `Settings.restoreWindow` (key `restoreWindow`, off by default; checkbox in `General > Window`); `SettingsStore` reads and writes six keys of the last window (`lastPanel`, `lastTab`, `windowWidth`, `windowHeight`,
  `windowX`, `windowY`) beside the heights, and the settings view neither shows nor changes them. `UsageApp` stores them in the shutdown hook (when the setting is on), restores them at start after the page is loaded
  (size and place, the place checked against the screens, never larger than the screen), and asks the page to open the panel and tab through a call of the page (`window.restore(panel, tab)`). Left-out values give the usual size.
- **Tests.** `HistoryDeltasTest` (failed lines skipped, a first line with no earlier value, hidden lines changing nothing, a fall), `ApiTest` (history cells empty at zero, the row's change), `SettingsStoreTest` (the keys, bad values),
  `app.test.cjs` and `layout.test.cjs` (no arrows, the blue, the strip's width, the green, the checkbox, `restore`), and a window test (a stored size and place restored, a place off every screen left out).
- **As built.** `HistoryDeltas.amountChanges` and `withAmountChanges` (the amount's change, used by `HistoryReader`; the old `compute`/`computeVisible` stay for zero usage lines and the time); `ApiHandler` leaves a cell empty for a change of zero or a fall.
  `SettingsStore.LastWindow` and `loadLast`/`saveLast`; `IntervalSettings.lastWindow`/`storeLastWindow`. `UsageApp` reads `window.currentView()` each tick into fields (so that either way of stopping can store it), places the window before it is shown
  (only on a screen that is there), and each tick asks `window.restoreView(panel, tab)` until it returns true, then applies the stored size once where the panel can be sized (height for the history and the log, width too for the log).
  The page's strip width is `stripWidth()` in `settingsWidth()`; the colour variables are `--info` for the tab and `--ok` for the icon.
- **Not checked by a test:** the restore in a real window (size, place, panel), and the strip's width in a real window; the Node tests mock `scrollWidth`.

### 78. Blue open icon, light blue startup lines, a blue countdown, 5-second steps, a steady width, light green placeholders

**Status: built (version 0.66).** Requirements: The button of the open panel, The usage history panel, The log panel, Countdown, Tooltips, The automatic interval, Gray boxes.

- **Colours (CSS).** The open panel's icon button uses the blue of the notes (`--info`), the other icons are as they were. The start line of the history and of the log has a very light blue background (`#ddf4ff` light, `#0c2d6b` dark; the log's gray
  `#e6e6e6`/`#30363d` goes); the history's text stays green. Every gray box of the row has a light green background (`#dafbe1` light, `#12301d` dark) in place of its gray third; border as before.
- **The countdown.** The backend's display carries a flag for the mode (`automatic`) and the interval text; the page puts the class `auto` on the countdown while it is on and not red (the red after a 429 wins), in `--info`. The tooltip is two lines, the
  second written by the backend (`StatusDisplay`): `Automatic interval is on: the interval is now N s` / `is off: the configured interval is N s`; the interval item's tooltip has the same second line.
- **5-second steps.** `IntervalSearch`: the interval is a multiple of 5 from 10 to 300; a 429 gives `max(roundUp5(1.1 R), R + 5)` capped at 300; 8 answers give `-5` (not below `R + 5`, 10); `R` decays 5 per 60 answers, below 10 none; with no `R` it falls by `roundUp5(0.9 I)`,
  at least 5 less, floor 10; a `Retry-After` is rounded up to 5 (cap 300); `of(...)` rounds a saved interval and `R` up to 5 and raises an interval at or below `R` to `R + 5`. The log line and the keys are unchanged.
- **A steady width.** `UsageApp.resize` (or the page's `contentSize()` and the panel width it reports) keeps the width the person has set while the log, the history or the error log is shown: the usual width is applied when the panel is opened or replaced, and not at each
  change of the page's size (a new line). Find what resets it (the width the page asks for is a fixed multiple of the row; the stage is set to it at every size change) and apply the width only when the panel, not the content, changed.
- **Tests.** `IntervalSearchTest` (the steps, the rounding, a simulated server limit, old saved values); `StatusDisplayTest` (the second tooltip line in both modes, the `automatic` flag); `app.test.cjs` and `layout.test.cjs` (the classes, the red winning, the blue icon, the
  backgrounds); `PanelWindowTest` or a new window test (a narrowed log keeps its width when a line comes).
- **As built.** The width: `UsageApp.resize` applied the page's width at every change of the page's size; it now keeps the stage's width when the same panel stays open and is sized in width (log, error log), and applies the page's only when a panel is
  opened or replaced. The search is `IntervalSearch` with `STEP_SECONDS` and `roundUp`. `StatusDisplay.View.countdownAuto` and `modeLine`. The page: `--info` for `.icon.active` and `.countdown.auto` (before `.countdown.alert`), `--mark` and `--box` variables.
- **Not checked by a test:** the width in a real window (no probe was written for it), and how the colours look.

### 79. The time since the previous reading across runs, and a shorter countdown tooltip

**Status: built (version 0.67).** Requirements: Changes between readings, Hiding lines, Tooltips.

- **The time.** `HistoryDeltas`: the time since the previous reading is against the row directly before in the file, whatever the run (`compute` no longer gives a run border: only the first row of the file has none); `computeVisible` is against the previous shown
  row, with no reset at a run start (only the first shown row has none). The amount's change (`amountChanges`) and the zero usage lines are unchanged: `HistoryReader` still decides a zero usage line from a change against the row directly before **within the run**
  (the startup lines are zero usage lines by their status), so `compute` keeps the run border for the amount and not for the time: split the two (`compute` returns the amount against the row before in the run, as it does, and the seconds against the row before).
  `LatestChangeCache` and the history cells follow from the deltas; a start line shows a long time, `90000 s`, in the history and `90000s` in the row.
- **The tooltip.** `Seconds until the next refresh` (no `(negative when overdue)`) in `StatusDisplay`, the page's own fallback and placeholder tooltip in `app.js`, `fake-display.cjs` and the tests.
- **Tests.** `HistoryDeltasTest` (the first row of a run has a time against the last row of the run before; a failed first row; the first row of the file none; the visible variant without a reset at a hidden start line), `HistoryReaderTest`, `ApiTest` (the cell of a start line),
  `StatusDisplayTest`, `app.test.cjs`, `view.test.cjs`.

### 80. The settings open on the tab they were last on

**Status: built (version 0.68).** Requirements: Settings (the tabs), The About view, The window is restored at start.

- **Stored.** `settingsTab` in `settings.json` (`main`, `history`, `cost`, `debug`, `general`, `about`; anything else is none): `SettingsStore.loadSettingsTab`/`saveSettingsTab`, kept by every other save as `LastWindow` is (add a parameter to `write`, or fold it into `LastWindow`'s neighbours);
  `IntervalSettings.settingsTab()`/`storeSettingsTab(String)`. The settings view neither shows nor changes it: it is not a `Settings` member and `POST /api/settings` leaves it as it is.
- **API.** `GET /api/settings` (the body that the view reads at each opening) gets `settingsTab` (string or null); `POST /api/settings-tab` with `{"tab": "general"}` stores it (400 for an unknown name), answered 200; `docs/api.md`.
- **The page.** The tab shown is remembered in the page while it runs; each change of tab posts it (once per change, not at opening); opening the settings shows the remembered tab, or on the first opening after a start the one in `settingsTab`; `debug` outside debug mode and an unknown name give `Main view`.
  `restoreView('settings', tab)` and `currentView()` use the same tab (when the window is restored with the settings open, the stored `lastTab` is the same value).
- **Tests.** `SettingsStoreTest` (the key kept by the other saves, unknown names), `ApiTest` (the post and the get, a bad name), `app.test.cjs` (the tab survives Save, Cancel, the gear and another panel's button; the first opening after a start uses the stored one; `debug` outside debug mode; About).
- **As built.** `SettingsStore` keeps `settingsTab` through `write`'s extra parameter, so that every save keeps it; `isTab`, `loadSettingsTab`, `saveSettingsTab`; `IntervalSettings.settingsTab`/`storeSettingsTab`; `ApiHandler` `POST /api/settings-tab` and `settingsTab` in `SettingsBody`.
  The page: `currentTab` is no longer reset at opening, `tabChosen` says whether the stored tab has been taken (at the first `loadSettings`) or a tab has been picked, `chooseTab` shows and posts, and `restoreView` with an unknown tab leaves the tab the settings were on.

### 81. The cost control panel with two bars

**Status: built (version 0.69).** Requirements: The cost control panel: two bars, The thirteen elements and their names (the fourteenth), Cost control.

- **Backend.** `CostPanel` (new, in `org.example`): works out the two bars from the history in use and the cost control limits: the monthly used (amount of the newest reading) and limit; the amount spent today (newest reading less the last reading before today in the same
  month, else the first reading of today; a fall is 0.00); the daily limit in force (`CostControl.dailyIn(YearMonth.now())`); the finished texts (`used`, `limit`, `percent`, the fraction filled 0 to 1, `over`, `noLimit`, the tooltip); the currency symbol follows the setting.
  `GET /api/cost` returns `{ "rows": [ {label, text, tooltip, fraction, over, noLimit} x2 ], "note": null|"No usage has been read yet." }`; `docs/api.md`. The history rows come from `HistoryReader` with no filter, in file order (add a small reader of the amounts if
  that is simpler than the table).
- **Settings.** `Settings.showCostIcon` (key `showCostIcon`, default on, optional in a POST like the four history columns), after `showErrorIcon` in the display `show` block (`StatusDisplay.Show.costIcon`).
- **Host.** `WindowFit`: the panel name `cost`, and its size is FIXED (`,3,cost`); `SettingsStore` knows the panel name `cost` for the last window; `restoreView` and `currentView` know it.
- **The page.** The button `cost-button` with its icon (two bars) and tooltips; the panel `cost-view` with two rows (label, bar `div` with a green fill whose width is `fraction`, text, warning sign); the settings checkbox `Cost button` in `Main view` (id `set-showCostIcon`);
  it is read again on each poll while open; the width is that of the history's table; the colours are CSS variables (`--ok`, the gray track, `--err`); `contentSize()` reports `w,h,3,cost`.
- **As built.** `CostPanel` works from `HistoryReader.read` rows (newest first) with no filter; `ApiHandler.cost()`; `Settings.showCostIcon` after `restoreWindow`; `StatusDisplay.Show.costIcon`; `WindowFit` and `SettingsStore` know the panel `cost`. The page: `cost-button`, `cost-view`
  (`cost-rows`, `cost-note`, `cost-error`), `describeCost` in `view.js`, redraw only when the JSON changed; the width is the larger of the row, the history's table (if it was measured) and the bars' own width; the warning sign is the glyph, in `--bad`.
- **Not checked by a test:** the panel in a real window (its size, how the bars look).
- **Tests.** `CostPanelTest` (the day's baseline in all cases: a reading before today in the month, none, a new month, a fall; no limit; over; percent rounding; the currency symbol), `ApiTest` (`/api/cost`, the setting), `StatusDisplayTest` (the `show` flag), `SettingsStoreTest`, `app.test.cjs`
  and `layout.test.cjs` (the button, the panel, the fill widths, the warning sign, no limit, the size report), the window tests are not changed.

### 82. No "Next try in" in the 429 message and the log

**Status: built (version 0.70).** Requirements: Refresh behavior, Usage request messages, The lines of a refresh.

- **Code.** `UsageService.settleInterval`: the 429 message is the fetcher's own (`Anthropic is rate limiting usage requests (HTTP 429).`) with nothing appended (`outcome.withError(...)` goes), and the two `Rate limited; next try in ...` warnings go; `describe(long)` and its test go; the `The server asked for a wait of N s; the interval of M s is kept` line now writes whole seconds from a small private `seconds(long)`. The `Automatic interval: ...` and the `The server asked for a wait ...` lines stay.
- **Tests.** `UsageServiceTest` (`theErrorSaysWhenTheNextTryIs` becomes the message without it; the search tests that end with ` Next try in N s.` lose that), `ApiTest` and the frontend tests that have the text; `docs/api.md` example.

### 83. JSON of the program's own, and no Jackson

**Status: built (version 0.71).** Phase 1 of [java-to-go.md](java-to-go.md). Requirements: Non-functional requirements (JavaFX is the only library), Usage request messages, An error in the log when the response has a format the program cannot process, the settings file as read, The local server.

- **New, in `org.example.json`.** `Json`, one class for every kind of value (object with its keys in order, array, text, number, true and false, null): a number keeps the text it was written with; `isWhole`, `fitsInt` and `fitsLong` are the questions `SettingsStore` and `ApiHandler` asked of Jackson (`isIntegralNumber`, `canConvertToInt`, `canConvertToLong`); `text()`, `bool()`, `intValue()` give what a value holds, and `asText`, `asInt`, `asLong`, `asDouble`, `asBoolean` what it can be taken for, which only `UsageParser` and the tests ask. `JsonReader`: strict (RFC 8259), a fault as `line L, column C: what` in a `JsonException`, which is an `IOException` so that reading a file and its JSON stays one failure; an empty text is no value (`null`) and no fault; nesting to 1,000. `JsonWriter`: `compact` for the API, `pretty` with the line ending given for `settings.json` and the logged response.
- **The answers of the API are written out.** Each of the 24 body records of `ApiHandler`, the six of `StatusDisplay` and the two of `CostPanel` has a `toJson()` that puts its members key by key; nothing is done by reflection. The names the two annotations gave (`delta_used`, `delta_time`, `delta_used_text`, `delta_seconds_text`, `fetched_at`) are written there. `send` takes a `Json`.
- **Gone.** `jackson-databind` and its BOM from `build.gradle.kts`; `ObjectMapper`, `JsonNode`, `ObjectNode`, `@JsonProperty`, `@JsonInclude`. `java.math.BigDecimal` is still used where an amount of the settings is read and written; that is phase 2.
- **What changed for the person.** The words after `The usage response is not valid JSON:` are the program's own. A settings file, and the body of a `POST`, with anything but white space after its JSON value is refused, where Jackson passed over what came after.
- **How it was checked.** For the length of the phase Jackson stayed as a test dependency, and a test (since removed with it) compared the two: which of about a hundred texts is JSON; what both write for what they read, on one line and over several, the fixtures included; the questions about a number and what a value is taken for; every record with 200 random fillings each against Jackson's own writing of it; and the settings file as written. All were the same, byte for byte. A run with `--debug` against the fake backend: every endpoint answered, and `settings.json` was written in the same form.
- **Tests.** `JsonTest` (new: what is and is not JSON, the wording and the place of each fault, the questions, the two written forms as fixed texts), `UsageParserTest` (the message with its place), `SettingsStoreTest` (a file with text after its object), `ApiTest` (a body with text after its value); `ApiTest`, `SettingsStoreTest` and `FakeBackendTest` read JSON with the program's own.
- **Found on the way, and put right.** `./gradlew frontendTest` ran `node --test src/test/frontend`, which Node 24 refuses (a directory is taken for a module). The task now names the `*.test.cjs` files, which every version of Node takes.

### 84. Money as a decimal of the program's own, and no `BigDecimal`

**Status: built (version 0.72).** Phase 2 of [java-to-go.md](java-to-go.md). Requirements: Non-functional requirements (money is reckoned in decimal numbers, exactly), Usage request messages, The reading shown at startup, exactly.

- **New, in `org.example.money`.** `Amount`: a `long` of digits and the count of its decimals (0 to 18), kept without zeros at its end, so two amounts that are the same are equal however they were written. `parse` (a decimal number as written, with sign and exponent, nothing rounded), `ofCents`, `ofMinor` (the unit of the response and its exponent); `plus`, `minus`, `max`, `signum`, `compareTo`; `shareOf(whole, decimals)`, a long division by hand, rounded upwards at half; `cents` (rounded, away from zero at half) and `exactCents` (refuses a third decimal); `plain` (`1000.00`), `grouped` (`1,000.00`), `signed` (`+0.05`), `number` (the exact amount as a number of JSON, `1000.0`). An amount that is more cents than a `long` holds cannot be made, so every amount can be written; a sum or difference that needs more than 18 digits is an `ArithmeticException`.
- **Changed.** `Spend`, `HistoryDeltas.Delta` and the two bodies `SpendBody` and `DeltaBody` hold an `Amount` where they held a `Double` or a `BigDecimal`. `UsageParser` makes the amount from `amount_minor` and `exponent` directly. `Formatting.amount` and `signedAmount` hand on to `Amount`; `DecimalFormat` is gone. `UsageHistory` reads and writes amounts with it, and works out the percentage of the row shown at startup with `shareOf`. `CostPanel` takes the share to six decimals, the percentage from that and the fill of the bar from that, as before. `CostControl`, `SettingsStore` and `ApiHandler` read a typed or saved amount with `parse(...).exactCents()`. `java.math` and `java.text` are imported nowhere, in the tests either.
- **What changed for the person.** Nothing in the row, the panels and the files of an ordinary run. At the edges: the percentage of the reading shown at startup is exact at a half (14.50 of 100.00 is 15%, where the binary fractions gave 14%), until the first refresh brings the server's own; an amount beyond 18 digits in a response is a response that cannot be used, with a message of its own; two history rows whose difference needs more than 18 digits have no change; and a `delta_used`, `used` or `limit` of ten million or more is written in the API without an exponent (`10000000.0`, not `1.0E7`), which is the same number.
- **How it was checked.** For the length of the phase a test (since removed) set `Amount` beside `BigDecimal`: for some 3,000 amounts, the edges of rounding among them, the plain, grouped and signed forms, the cents, the exact cents and the number of JSON; for 20,000 pairs the sum, the difference, the comparison, the share to six and to two decimals and the fill of the bar. All were the same. A run with `--debug` against the fake backend: the status, the cost panel, the history and the CSV row had the figures in the forms of before.
- **Tests.** `AmountTest` (new, 76 cases with the figures fixed), `UsageParserTest` (an exponent of 3 and of -2; an amount too large), `HistoryDeltasTest` (a row with three decimals taken exactly; rows too far apart), `UsageHistoryTest` (the percentage at a half). The tests that made a reading from a `double` make it from the text of the amount.

### 85. Logging of the program's own, and no lock file

**Status: built (version 0.73).** Phase 3 of [java-to-go.md](java-to-go.md). Requirements: Non-functional requirements (the program writes its log itself), Details of the behaviour (there is no lock file beside the log).

- **New, in `org.example.log`.** `Log`: a class has its own (`Log.of(UsageService.class)`, the short name of the class in each line) and says `error`, `warning`, `info` or `debug`, the first two with something thrown if there is. What is logged is an `Entry` (time, level, name, message, thrown) handed to every `Sink` that listens, one entry at a time under one lock, so the lines of two threads are never mixed. `Entry.written(zone)` is the line as it always was: the time, the level's word in seven places (`SEVERE`, `WARNING`, `INFO`, `FINE`), the name in brackets, the message, the stack trace, all through `Redaction`.
- **`Logging`** only opens and closes the two places now: the console (the error output as it is when the log is opened) and the file (appended to, UTF-8, written through after every entry). A file that cannot be opened is said on the console, and the run goes on there.
- **Gone.** `java.util.logging` and `System.Logger` in all twelve classes; `ShutdownSafeLogManager`, the `java.util.logging.manager` property and the warning `Logging was already in use…`; the rule that `Logging` is installed before `UsageApp` is loaded; `java-aip-usage.log.lck` and its line in `.gitignore`. The three messages written with `{0}` are written out.
- **What changed for the person.** No lock file. What JavaFX and the Java runtime log of themselves is on the console only.
- **Not changed here.** A stack trace is still written where something thrown is logged; that is phase 4.
- **How it was checked.** A run with `--debug` against the fake backend: the lines of the start, a refresh and the stopping, the same in the file and on the console and in the form of before; no lock file while it ran; the log panel's endpoint reads them.
- **Tests.** `LoggingTest` rewritten against `Log` (the form of a line at each level, the console the same as the file, a line in the file as soon as it is logged, appending, stack traces, masking, closing twice, no lock file, a file that cannot be opened, a library's line not in the file, the lines of four threads whole). `UsageClientTest` and `UsageServiceTest` listen with a `Sink` where they had a handler; `ShutdownProbe` logs with `Log`. `LoggingEndToEndTest`, `ShutdownHookTest`, `LogTailTest` and `LoggingTimestampTest` pass unchanged.

### 86. Texts of the program's own for every failure, and no stack trace in the log file

**Status: built (version 0.74).** Phase 4 of [java-to-go.md](java-to-go.md). Requirements: The words for what went wrong, Usage request messages, Details of the behaviour (the lines of the log, a start that fails).

- **New, in `org.example`.** `Failure`: the one place that looks at the kind of an exception. `of` gives the words of a failure the program names itself (any exception of `org.example`), and for any other one of fifteen fixed texts, found by the kind of the exception or of what caused it; `ofConnection` is the same for a request, where a failure of no known kind is `the connection failed`; `ofStart` is for a program that did not start, and reads the number the system gives (`error=2`, `error=5`, `error=13`, `error=193`) from the text of the failure, the one thing taken from that text. `Fault`: an `IOException` for what the program sees for itself (`the settings file is not a JSON object`, the clipboard, the file manager), so that it counts as the program's own.
- **Changed.** Every place that built a message from `getMessage()`, or from the name of a class, asks `Failure`: the detail of `Cannot reach <host>`, the reasons of the token flow, the 500s of the API, and the some twenty warnings of the log, which now hand what was thrown to the log (`LOG.warning("Could not read the log", e)`). The message of an unexpected fault is `Unexpected error; start the program with --debug to see on the console where it came from.`
- **The log.** `Log.Entry.written` ends a line about a failure with `: ` and `Failure.of` of what was thrown, and writes no stack trace; `Entry.trace` is the stack trace, masked, which the console sink of `Logging` prints when it was opened for debug mode (`Logging.install(file, options.debug())`).
- **Not done as planned.** `the file is in use` and `the disk is full` have no text of their own: Java reports both only in the system's words, with no kind to tell them by, so they are `the file could not be read or written`. Go can tell them by their error number, and may give them their own text there.
- **What changed for the person.** The words after the colon in a failed refresh, a failed save and a token problem; the message of an unexpected fault; no stack trace in the log file. The messages are the same on Windows and macOS and in every language of the system.
- **How it was checked.** A run with `--debug` pointed at a port nobody listens on: the row's message, the error log and the log line all said `Cannot reach 127.0.0.1: the connection was refused`.
- **Tests.** `FailureTest` (new: a real failure for each kind that can be made, a file that is not there, a refused connection, a host that is not known, a connection that ends, an address that is taken, a program that is not there and one that is none; the fixed set; and a check of the sources that nothing but `Failure` reads the text of an exception and nothing but `Log` a stack trace or the name of a class). `LoggingTest` (a failure is one line; the stack trace on the console in debug mode only; masking in both). `ClaudeTokenProviderTest` (the reason in the program's words and none of the system's), `FakeBackendTest`, `UsageServiceTest`, `ApiTest` (the stand-ins throw a `Fault`).

### 87. Stopping the process tree with the system's commands

**Status: built (version 0.75); the test on macOS is still to be run by hand.** Phase 5 of [java-to-go.md](java-to-go.md). Requirements: Authentication, Clearing up (how the processes are killed).

- **New, in `org.example.token`.** `ProcessTree.kill(pid)`: on Windows it runs `taskkill /PID <pid> /T /F`; elsewhere it finds what the process started with `pgrep -P`, level by level (`descendants`, a pure function over "the children of"), and runs `kill -9` for each, the deepest first, and for the process last. A command has 5 seconds; one that is missing, fails or hangs is noted at debug level and stops nothing.
- **Changed.** `ClaudeTokenProvider.terminate` calls it while `claude` is still alive, then ends `claude` directly once more and waits the two seconds as before. `Process.descendants()` is used nowhere.
- **What changed for the person.** Nothing.
- **How it was checked.** On Windows: the new test passes, and with the call to `ProcessTree.kill` taken out it fails with `what the CLI started is still running`, so it watches the rule.
- **Not checked.** macOS: the commands are built and their order is tested, but no tree was ended there. To be run on the Mac: `./gradlew test --tests '*ClaudeTokenProviderTest' --tests '*ProcessTreeTest'`.
- **Tests.** `ProcessTreeTest` (new: the commands, the order with the deepest first, a number that comes again, the reading of the list of children). `ClaudeTokenProviderTest.whatTheCliStartedIsKilledWithIt` (new: the stand-in `FakeClaude` has a mode `hang-with-child`, which starts a second process that hangs too; after the time-out neither is left). `FakeClaude` records both pids.

### 88. The refresh service as one loop with a queue, and nothing interrupted

**Status: built (version 0.76).** Phase 6 of [java-to-go.md](java-to-go.md), the last. Requirements: Non-functional requirements (one loop does the refreshing), Refresh behavior (unchanged), Details of the behaviour (the order of stopping).

- **`UsageService`, rewritten.** One thread, the loop, owns the schedule, the search, the generation and the request under way. A public method hands the loop a message, a closure, and waits for its answer (`ask`); the loop takes the next message from a `LinkedBlockingQueue`, or waits until the next request is due, whichever comes first (`poll` with a time). A request is begun by the loop on a thread of its own and hands its outcome back as a message (`finished`), so the loop takes messages while a request is under way and requests still never overlap: the schedule says one is running until `finished` has been handled. `ReentrantLock`, `Condition`, `awaitNanos` and `signalAll` are gone.
- **What the window reads** is published by the loop after each step and before each answer: the state as before, and a `Published` record with the interval, whether it is searched for, and a `RefreshSchedule.Countdown`, an unchanging copy of what the countdown is counted from. `state()`, `secondsUntilNextRefresh()`, `effectiveIntervalSeconds()`, `interval()` and `automatic()` read it and never wait for the loop.
- **Giving up is said, not interrupted.** New `Cancel` (`org.example`): `cancel()`, `isCancelled()`, `onCancel(action)`, which runs the action at once if the cancel came first. Each request has its own; `restart` and `close` cancel the one under way. `UsageFetch` (new) is a fetch that takes it; `UsageClient` sends with `sendAsync` and cancels the answer; `ClaudeTokenProvider` ends its waiting for the credential; `AppRuntime.fetchAndRecord` asks it where it asked the interrupt flag. `TokenProvider.acquire(Cancel)` and `UsageSource.fetch(token, Cancel)` are there beside the old forms, which a provider or source that answers at once keeps.
- **Edges.** Asked by the loop itself, as when whoever is told of the search asks something in turn, a question is answered on the spot. After the loop has ended a question is answered in its place, by a service that is closed. A fault in handling a message is logged (`The usage service could not finish a step`) and does not end the loop. `close()` waits up to 5 seconds for the loop and the request, as before for the thread.
- **What changed for the person.** Nothing.
- **How it was checked.** The whole suite passed against the new service before a test was touched, but for the two that asked whether the fetch thread was interrupted. Four rules were broken on purpose, one at a time, and each was caught: a restart that does not tell the request to give up; a close that does not; a manual refresh taken while one runs; an answer given before what follows from it is published. A run with `--debug`: a second manual refresh during a request was declined, a slow request was dropped when the source changed, and closing during a slow request stopped at once.
- **Tests.** `CancelTest` (new, with a check of the sources: nothing interrupts a thread, asks whether one was interrupted, or holds a lock with a condition). `UsageServiceTest`: the stand-in fetch is told to give up where it was interrupted; new, that the window's questions are answered while the loop is busy, that whoever is told of the search may ask in turn, that a fault there does not end the refreshing, and what is asked after closing. `UsageClientTest` (a request told to give up stops waiting at once), `ClaudeTokenProviderTest` (told to give up it stops waiting and kills the CLI), `ApiTest` (its stand-in fetch).

### 89. The gray box is a green frame and nothing else

**Status: built (version 0.77).** Requirements: A placeholder at the start; Gray boxes in the place of `No usage reported` and `No data`, and a green border round every gray box.

- **What was asked** (`new-requirements.md`): the frame of the placeholder in the same green as the text, no rounded corners, no background colour. The first was so already: the frame was `var(--ok)`, which is the colour the figures' text has. The other two are the change.
- **The page.** `app.css`, the rule `.skeleton`: `background` is gone, and with it the variable `--box` and its two values; `border-radius` is `0`, said outright; the border stays `1px solid var(--ok)`. The widths of the boxes and the rule that they are not dimmed are as they were.
- **Nothing else.** No change in the backend, the API or the window host: the box is the page's alone.
- **Tests.** `layout.test.cjs`, the test of the box, now `the gray box of an element with no value is a thin green frame with square corners and nothing in it, and has a width of its own`: no `background` in the rule, no `--box` in the style sheet, `border-radius: 0`, the border in `--ok`, and that `--ok` is the colour of the figures' text. It was changed first and failed against the old style; a fill put back on purpose fails it again.
- **By eye.** Boxes beside figures, drawn by a headless browser from the style sheet, on the light and on the dark theme: a green frame with square corners in the green of the figures, the page's background inside. Not looked at in the JavaFX window itself.

### 90. The API names no language: what the program runs on is said by the backend

**Status: built (version 0.78).** Requirements: The About view; `docs/api.md`, `GET /api/about`.

- **Why.** The requirements and `api.md` were made to hold for every version of the program, whatever language it is written in. One thing in the API and in the page belonged to Java: `/api/about` had the keys `java` and `javafx`, and the page had the labels `Java` and `JavaFX` written in it.
- **The API.** `GET /api/about` has `runtime` and `toolkit` in their place, each an object with a `name` and a `version` (`{"name": "Java", "version": "25.0.1"}`). `ApiHandler.AboutBody` holds two `Named`; the names `Java` and `JavaFX` are said there, in the backend, the one place that knows them.
- **The page.** `index.html`: the two labels are empty (`about-runtime-label`, `about-toolkit-label`), beside `about-runtime` and `about-toolkit`; `app.js` writes the names and the versions it is given. The page shows what it showed, and has no name of a language or a toolkit in it.
- **`api.md`.** Its head no longer speaks of Java and a future Go backend, nor of `ApiHandler`; the window host contract no longer of the Java host and `WindowFit`.
- **Tests.** `ApiTest` (the names and versions under the new keys, and the old keys gone), `app.test.cjs` (the labels are what the backend says), `layout.test.cjs` (the two labels are empty in the page, and no `Java` or `JavaFX` is in the page or its scripts).

The requirements have no section "specified, not yet built" any more.

### 91 to 99. Version 0.79: the notes of `new-requirements.md`

**Status: planned, not built.** The requirements are written (every rule marked "since version 0.79"); these nine phases build them, in this order, and the version constant `AppInfo.VERSION` becomes `0.79` with the last. Each can be built and tested by itself. The first four are the page's alone, or nearly; 95 and 96 change what the host does for the page; 97 to 99 are the backend's.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 91 | The small changes of the page: the order of the tabs, blue start lines, the cost bars, the bold file name, a tooltip for every control, select all in `History view` | `index.html`, `app.css`, `app.js`, `CostPanel` |
| 92 | The panel buttons while the settings are shown | `app.js`, `view.js`, `app.css` |
| 93 | The button in the history for the zero usage lines | `app.js`, `ApiHandler`, `IntervalSettings` |
| 94 | The settings have their own width; the history has no horizontal scrollbar | `app.js` (`contentSize`), found by a failing test first |
| 95 | The title bar only for a panel that can be resized | `UsageApp.followTitleBar`, `app.js` (`bare()`) |
| 96 | The tab `About` can be dragged wider | `WindowFit`, `UsageApp.applySize`, `app.js` (`contentSize`) |
| 97 | The refused interval is forgotten by time | `IntervalSearch`, `UsageService` |
| 98 | The log can be reset, and the reset undone | `Logging`, `ApiHandler`, the log panel |
| 99 | The name of the program on macOS | `Main`, `UsageApp`, `build.gradle.kts`; a spike first |

`docs/api.md` is brought level in the phase that changes an endpoint or the host contract (93, 96, 98). What is left for a person, by eye, is listed at the end of this group.

**Decided before building:**

- **One version, a commit for each phase.** All nine are 0.79: the constant changes with phase 91, and each phase is committed by itself with its tests green, as `Phase 9x built: <what> (0.79)`.
- **Built on Windows, checked on the Mac by a person.** The scrollbar half of phase 94 and all of phase 99 are built here, against tests that stand in for macOS (a scrollbar of no width; the code of the name guarded to macOS), and their status is then `built, not checked on macOS`, with what to look at.
- **The macOS name is done by the program alone, and what is left is reported.** What the program can set for itself before the window opens, AWT's `Taskbar` for the Dock icon included, and `-Xdock:name` for the Gradle `run` task. No app bundle and no second start: whatever still says `java` after that becomes an open point, not more code.
- **A panel is given back whatever its switch says** (phase 92): the requirements now say so, in the place of the rule that a switched-off panel was not given back.

### 91. The small changes of the page

**Status: built (version 0.79); not looked at in the window yet.** Requirements: Settings (the tabs), The log panel and The usage history panel (the start line), The cost control panel: two bars, The paths of the About view are links, A tooltip for every control of the settings, A button that selects every checkbox of `History view`, or none.

- **The order of the tabs.** `app.js`: `TABS` becomes `['general', 'main', 'history', 'cost', 'debug', 'about']`; the strip and the pages in `index.html` are put in that order (`tab-general` first). Wherever the page falls back to the first tab (no stored tab, an unknown one, `debug` outside debug mode, in `loadSettings`, `chooseTab` and `restoreView`) it falls back to `general`; the fallback is to be `TABS[0]`, said once, not the word `main`. `settingsWidth()` filters by name and is not touched by the order. The backend's `settingsTab` and `lastTab` keep their six names.
- **Blue start lines.** `app.css`: the variable `--mark` and its two values go, and with them the background of the log's start line; the rule of the log's start line and `.panel-lines .start` of the history both get the blue of the notes as their colour, no background, regular weight. The red of `failed` in a failed start line keeps its own rule, which must still win. Nothing in the backend: which line is a start line is said as before.
- **The cost bars.** `CostPanel`: the label `This month` becomes `Month` (both places, the empty row and the filled one; the tooltip begins with the label, so it follows). `app.css`: `border-radius` goes from `.cost-bar` and `.cost-fill`. Equal width: `.cost-rows` becomes a grid of three columns, `max-content` for the label, `1fr` for the bar, `max-content` for the figures with the warning sign, each `.cost-row` laid out with `display: contents`, so the two bars begin and end under one another. `costWidth()` in `app.js` measures the grid, not each row.
- **The bold file name.** `app.js`, where the three paths of `/api/about` are written: the text of the link is two spans, the folders and the name, cut at the last `/` or `\` of the path; `app.css` gives the second `font-weight: 700`. The backend sends the path as before; the cut is of a text, no calculation. The link, its tooltip and the copy button are unchanged, and the copied text is the whole path.
- **Tooltips.** `index.html`: the `title` of each control as the table of the requirements says, and the five fields of the fake backend with their ranges; the tooltip is on the `label`, which holds the control, so it shows on both; a field outside its label gets the same `title` itself. The `Cost button` checkbox, the two limits of `Cost control`, `Restore the window at start` and `Title bar` have texts today that differ from the requirements: the requirements win.
- **Select all in `History view`.** `index.html`: a second button of the class `select-all` in the `legend` of `History view`, id `set-all-history`, tooltip `Select every checkbox, or none`. `app.js`: `selectAllOrNone` takes the list of flags it switches, the fourteen elements for the one, the nine `history…` flags for the other; the second has no preview to draw.
- **Tests.** `layout.test.cjs`: the order of the strip and of the pages; no `--mark`, the two start rules in the blue and with no background; no `border-radius` in the two cost rules, the grid; every `input`, `select` and `button` inside the settings form has a `title` or is inside a `label` that has one (the rule of the requirement, as a rule), and the texts of the table. `app.test.cjs`: the fallback tab is `general`; the name of a path is in its own element, for a path with `/` and one with `\`; the history's select button with all on, some on, none on, and that `Cancel` takes it back. `CostPanelTest`: `Month`. Each is changed first and seen failing.
- **As built.** `app.js`: `TABS` with `general` first, and `currentTab = TABS[0]`, which is the one fallback (`showTab` already takes the first visible tab for one that is not there); `HISTORY_FLAGS`, and `selectAllOrNone(flags)` for both buttons; `showPath(id, path)` writes a path as two spans, `about-folders` and `about-file`, cut after the last `/` or `\`. `app.css`: `--mark` is gone, `.panel-lines .mark` and `.panel-lines .start` are `color: var(--info)` and nothing else; `.about-link .about-file` is weight 700; `.cost-rows` is the grid `max-content minmax(120px, 1fr) max-content` and `.cost-row` is `display: contents`.
- **Not as planned: the warning sign is in the cell of the figures.** A row had four parts, and the sign was hidden when it was not wanted, which in a grid would have moved the next row's cells by one. The figures and the sign are now one cell, `cost-figures`, so every row has three; `costWidth()` adds the widest label, the bar's least width, the widest figures and two gaps. Since a row is no box of its own any more, each of its cells carries the row's tooltip as well.
- **Not as planned: the tooltip of `Cur.`** says `the dollar sign`, not `$`: the page has no currency sign in it, which a test of long standing holds; the requirements were changed to the same words.
- **The version** is 0.79 from here (`AppInfo.VERSION`). `docs/api.md`: the label `Month` in the example of `GET /api/cost`.
- **How it was checked.** The frontend suite, 377 tests, and `./gradlew test`. Three rules were broken on purpose and each was caught: a label without its tooltip, a bar with round corners, a start line with a background. The 22 tests that held the old order, colours and labels were changed first and seen failing.
- **By eye, still to do:** the two bars in the window (the grid, and the tooltip of a row over each of its parts), the blue start lines on both themes, and the bold file name in a path that breaks over two lines.

### 92. The panel buttons while the settings are shown

**Status: built (version 0.79); not looked at in the window yet.** Requirements: The panel buttons while the settings are shown; Compact window (the row); A click is shown in the row at once.

- **Today.** While the settings are open the four buttons follow the checkboxes of the view (`ICON_SETTINGS`, and the preview that `checked('showLogIcon')` and its neighbours feed); the button of the open panel is shown whatever its switch says.
- **The change, in the page only.** While `openPanel === 'settings'`, each of the four buttons is shown, and has the class `off` when its checkbox, as it stands in the view, is unchecked. `app.css`: `.icon.tiny.off { opacity: 0.4; }` and nothing else, so the size and the place are those of a button that is on. The decision belongs where it is made today: if `view.js` says which buttons are shown from the `show` block, it is told whether the settings are open and answers `shown` and `off` for each; the page then only sets `hidden` and the class.
- **The tooltip.** A button that is `off` has its usual `Show the …` and, on a second line, `Switched off: this button goes when the settings are closed.`; the `aria-label` stays the first line.
- **A click** needs nothing new: the button's handler already leaves the settings, saving them, and opens its panel; the open panel's button is then shown by the rule there is, and is not `off`.
- **Leaving**: with the settings closed the buttons follow the saved switches, as today. What changes is the giving back: `app.js` drops `returnTo` when the saved settings switch that panel's button off (`if (returnTo && body.settings[ICON_SETTINGS[returnTo]] === false) returnTo = null`); that goes, so the panel is given back whatever its switch says, and its button is shown as the open panel's.
- **The width.** The row is as wide with a dimmed button as with a full one, so a click on one of the four checkboxes no longer changes the width the page asks for while the settings are open; `contentSize` is not touched.
- **Tests.** `view.test.cjs` (shown and `off` for each combination of the switch, the settings open or not, the panel open or not), `app.test.cjs` (with `showLogIcon` off: no button with the settings closed, a dimmed one with them open, its two-line tooltip; a click on the checkbox takes the class away at once; a click on the dimmed button saves and opens the log, the button is then not dimmed, and it is gone when the log is closed; from that log the gear, then `Save`, `Cancel` or the gear again, and the log is back each time, also when its checkbox was switched off in between; the refresh button still goes with its checkbox), `layout.test.cjs` (the rule of the class).
- **As built.** All of it is in `app.js` and one rule of `app.css`; `view.js` is not touched, since the decision which buttons are shown was in `renderButtons` of `app.js` and stays there. `renderButtons` shows every panel button while `openPanel === 'settings'`, notes in `offButtons` which of them are switched off, and calls `labelButtons`, which gives such a button the class `off` and the second line of its tooltip (`OFF_NOTE`), the `aria-label` staying the first line. The switch is the one the row is drawn from: the checkbox of the view once something was clicked, the backend's until then. `.icon.tiny.off` is `opacity: 0.4` and nothing else. `ICON_SETTINGS`, and the lines of `saveSettings` that dropped `returnTo` for a panel whose button was switched off, are gone.
- **How it was checked.** The frontend suite, 382 tests. Four rules were broken on purpose and each was caught: the buttons following their switches again (6 tests fail), no dimming (8), no second line in the tooltip (2), a switched-off panel not given back (1). No Java changed.
- **Found on the way.** Three tests had a backend that kept saying the old settings after a save, so the row went back to them at the next status; they now make the backend say what was saved. That is how the fake differs from the program, not a fault of the page.
- **By eye, still to do:** how well a button at 40 percent is seen on the light and the dark theme, and the two-line tooltip in the JavaFX window, where the tooltip is drawn by the host.

### 93. The button in the history for the zero usage lines

**Status: built (version 0.79); not looked at in the window yet.** Requirements: A button in the history that hides and shows the zero usage lines; Hiding lines.

- **The API.** A request of its own, after the pattern of `POST /api/log` (level and date) and `POST /api/text-size`: `POST /api/history` with `{"zeroLines": true}`, which sets the one setting `historyZeroLines`, saves `settings.json` and answers 200; anything but `true` or `false` is 400 `The setting zeroLines must be true or false.`; a save that fails is answered as the log's two choices answer it, and the value still holds for the run (look there, and do the same). `GET /api/history` already chooses its lines by the setting; it also gets `zeroLines` (boolean), so that the button knows its state without the settings being read. `docs/api.md`.
- **Backend.** `IntervalSettings`: a method that applies the one flag over the current `Settings` and stores it, logging `Settings changed: …` as any change does; `ApiHandler`: the route, and `Allow: GET, POST` in the 405 of `/api/history`, which was `GET` alone.
- **The page.** `index.html`: a third button in the line of the zoom buttons, `zero-lines`, an icon drawn like the others (two short arrows pointing at a line between them), shown with the history only, in the way `log-level` and `log-date` are shown for the log alone. `app.js`: a click posts the other value, puts the list at its top and reads the history again at once; the button is in the blue and has the tooltip `Show the zero usage lines` while the answer says `zeroLines: false`, and is gray with `Hide the zero usage lines` otherwise. The checkbox of `History view` is filled from `GET /api/settings` at each opening, so it follows without more.
- **A red line** `The history settings could not be saved: <reason>`, in the place the log's own line of that kind has.
- **Tests.** `ApiTest` (the post, a bad value, the 405, `zeroLines` in the get, the key in `settings.json` after it, and that `GET /api/settings` then says the same), `IntervalSettingsTest`, `app.test.cjs` (the button with the history only; the two states; a click posts, reads again and stands at the top; a failed save shows the line and keeps the choice), `layout.test.cjs`.
- **As built.** `Settings.withHistoryZeroLines`; `ApiHandler`: `POST /api/history` (`updateHistory`, the body `HistorySettingsBody`), `zeroLines` in `HistoryBody`, and `GET /api/history?zeroLines=true|false` (`zeroLinesAsked`). `IntervalSettings` needed no method of its own: `apply` saves and logs a changed setting as it is. The page: the button `zero-lines` in a span `history-choices` after the zoom buttons, hidden until the first answer of the history; `takeHistoryChoice`, `labelZeroLines`, `toggleZeroLines`; `.icon.tiny.on` is the blue, at the size the button has.
- **Not as planned: a query on the `GET`.** The requirement says that a choice that cannot be saved holds for the run, and the lines are chosen by the backend from the setting, which is then the old one. So the page, after a save that failed, asks for the history by its choice, `?zeroLines=false`, as the log is asked for by its level; the answer still says what is saved. The choice is kept by the page from one opening of the history to the next, until a change is saved.
- **An answer that was overtaken is not shown.** Each press is counted; a read of the history that was under way when the button was pressed is neither drawn nor allowed to put the button back, and the read that follows it is shown.
- **How it was checked.** The frontend suite, 390 tests, and `./gradlew test`. Five rules were broken on purpose and each was caught: the history not put at its top, an overtaken answer shown, an unsaved choice not asked for by name, the choice forgotten when the history is shown again, and the query ignored by the backend. The first form of the test of the overtaken answer did not catch its rule and was sharpened until it did.
- **Seen on the way, not of this phase.** `UsageServiceTest.aRateLimitedRefreshIsMarkedAndDoesNotMakeTheReadingStale` failed once in a run of the whole suite (`condition not reached within 5 s`), and `aWaitOfTheServerAboveThreeHundredSecondsIsNoMoreThanThreeHundred` once in another (`expected: <300> but was: <60>`): two of seven runs of the whole suite, a different test each time, in a class this phase does not touch. The class by itself passed four times of four on the code before this phase. They are tests that wait on the clock and seem to lose under the load of the whole suite; whether that began here or was there before was not established, and it is worth a look before phase 97, which changes that class.
- **By eye, still to do:** the icon of the button, and its blue beside the gray zoom buttons, on both themes.

### 94. Two faults: the width of the settings, and the history's horizontal scrollbar

**Status: built (version 0.79); both seen in a real window on Windows, before and after. The history is still to be looked at on the Mac.** Requirements: The settings have their own width, whatever was shown before; The usage history has no horizontal scrollbar. Both are faults against rules there were, so each begins with a test that shows it, and the cause is to be found, not guessed: what follows is where to look.

- **The settings after the log.** The host is not the first suspect: `UsageApp.resize` keeps the window's width only for a panel that stays the same and may be dragged in both directions (`keepWidth`), and the settings are reported as fixed (`,3,settings`). So the page most likely asks for the wide size: `contentSize` returns `Math.max(width, settingsWidth())`, `settingsWidth()` measures the form with `formWidth(...)` and the strip, and `stripWidth()` adds the room at the left of the strip again at its right. If any of these is measured while the form is still as wide as the window the log left, the answer follows the window, which the comment in `contentSize` forbids for the row and nothing forbids for the form. To be checked in a real window with the debug lines `Window fitted to …` when going from the log to the settings. The remedy is the row's own: let go of the stretch for the moment of the measuring, measure, and put it back.
- **The history on macOS.** There `scrollbarWidth` is 0, since the scrollbar lies over the content, and the table keeps a margin clear at its right for it. The width asked for is `max(row, table + 16) + scrollbarWidth`: if the 16 are the panel's padding and the margin for the scrollbar comes on top, the list is wider than the window on macOS by that margin and exactly right on Windows, which is the picture: a horizontal scrollbar, and the first character of the first column out of sight. To be confirmed by comparing the list's `scrollWidth` with its `clientWidth` in the Mac's window, with both change columns on. The remedy is to ask for the width the list really needs, taken from the probe (`#table-probe`) with its margin, in the place of a sum of fixed numbers; the 16 that is written as a number in `app.js` (see Implementation notes) then goes.
- **Tests.** `app.test.cjs` with the fake display: after the log was shown at three times the row's width, the size asked for with the settings is the least width, not the log's; the width asked for with the history is never less than what the list needs, with a scrollbar width of 0 and of 15, with one, both and no change columns and with the date, at list sizes 10, 12 and 20. `PanelWindowTest` (the real window, asked for by name): log, then settings, and the stage is as wide as the settings ask.
- **By eye, on the Mac**: both, since the fake display has no layout of its own.
- **What the settings' fault was.** As suspected, the page: `stripWidth()` took the strip's `scrollWidth`, and a box that scrolls has a scroll width of at least its own width, so a strip in a view 1,200 pixels wide said 1,200. In the real window, with the page as it was: opened in a window 500 wide the settings asked for 500, and in one 1,200 wide for 1,200. Now 390 both times. `stripWidth()` lets the strip go of the view for the moment of the measuring (`flex: none; width: max-content`), as `formWidth` does with the form.
- **What the history's fault was, and it was not what was suspected.** Not the scrollbar of macOS and not the 16 pixels: the style sheet gave the columns their widths by their number, `cols-4`, `cols-5`, `cols-6`, written for the time, the amounts, the changes and the currency last. Since phase 76 any column can be switched off, and with the currency off and both changes on there are five columns of which the last, `Δ time`, got the 5 characters of the currency. In the real window, with the page as it was, its cell was 6 pixels wider than its column; the screenshot of the fault has exactly these five columns, and `Δ time` cut off at the right.
- **As built.** The backend names what each column is: `kinds` in `GET /api/history` (`time`, `used`, `limit`, `deltaUsed`, `deltaTime`, `currency`), passed on by `describeHistory`. `cells(...)` marks each cell with it (`c-used`) and a row of the history with `columns`; the style sheet has one rule for the row (`grid-auto-flow: column`, each column as wide as its cell) and one width for each kind, in the place of the three rules by count. The rule that put the first title at the left whatever it was is gone with them: the time is at the left by its kind. The red of a failed line is on the cell of `used`, where it was on the second cell, which with the time switched off was the budget.
- **A safeguard for what could not be seen here**, `historyLack` in `app.js`: when the list can be scrolled sideways at the width the page itself asked for, the page asks for that much more, once, and keeps to it while the table is the same; never more than 48 pixels. It is for a scrollbar that takes more room than the one `measureScrollbar` measured at the start, which the pictures of the Mac would also fit. It reads how wide the window is only to know that the width asked for has been given; what it reports is what the list lacks, not the window.
- **Learned on the way.** In the WebView the margin at the right of the list (`padding-right: 16px`) is no part of what can be scrolled: a list made 24 pixels wider in its margin lacked nothing. So the margin never causes a scrollbar, and a column that sticks out by less than it shows no scrollbar either; on Windows the old fault was a cell wider than its column and nothing more.
- **How it was checked.** The frontend suite, 396 tests, and `./gradlew test`. `PanelWindowTest`, the real window, has both cases now and was run against the page as it was (it fails: 1,200 for the settings, a cell 6 pixels over) and as it is (it passes); with the safeguard taken out it fails, and so do two tests of the page. Three more rules were broken on purpose in the page and caught: the strip measured as it lies, the red by its place, the copy for measuring without the kinds.
- **The window tests that are run only when asked for had not been run since phase 91, and that showed.** `PanelWindowTest` measured a line of `Main view`, which is no longer the tab the settings open on, and is put right. `TitleBarWindowTest` fails in both its tests, and did so before phase 91 as well, which was established by running it on the commit before: it expected 8 and 13 checkboxes where there have been 9 and 14 since the cost button of phase 81, and a line `Debug mode (--debug)` in the log that it does not find. The first is put right here, with the tab and with the five buttons of the row that phase 92 brought. Two things are left, and are for phase 95, which changes what that test is about: at three steps the probe sees two windows where the test wants one, and the line of the log.

### 95. The title bar only for a panel that can be resized

**Status: built (version 0.79); seen in a real window on Windows, with the red border of the fake backend. The black border round the cost control is still to be looked at.** Requirements: Settings (The title bar), A border round the window that has no title bar, Dragging the window that has no title bar, The cost control panel: two bars.

- **Host.** `UsageApp.followTitleBar`: `wanted` is today `showTitleBar || panelOpen`. `panelOpen` becomes "a panel is open that keeps the title bar": every panel name but `cost`. It is set where the page's size is parsed (`WindowFit.parse(reported).ifPresent(size -> panelOpen = …)`); the rule gets a name and a home, `WindowFit.Size.keepsTitleBar()`, so that it is tested without a window. The swap of the stage, the measuring of the frame again and the debug lines are as they are.
- **Going from a panel with the title bar to the cost control**, with the setting off, swaps the window once, and once more on the way back: the flicker the requirements accept.
- **The page.** `bare()`, which says whether the window has no title bar (the border of one pixel, and the `edge` of 2 in `contentSize`), must say so for the cost control too. The red border of the fake backend still wins.
- **Dragging.** `UsageApp` begins a drag only when `!StageSwap.hasTitleBar(stage)`, so the cost panel is dragged without more; `window.dragStartsAt` already refuses buttons, and the bars are none.
- **Tests.** `WindowFitTest` (`keepsTitleBar` for each panel name and for none), `app.test.cjs` (`bare` with the cost control, the border, the size larger by 2), `TitleBarWindowTest` (the real window: the setting off, the cost control opened, no title bar; the log opened from it, a title bar; closed, none).
- **As built.** `WindowFit.Size.keepsTitleBar()`: a panel is named and is not `cost`. `UsageApp`: the field `panelOpen` is `panelKeepsTitleBar`, set from it where the size is parsed, and `followTitleBar` asks it. The page: `bare()` holds with no panel and with the cost control. Nothing was needed for the dragging, as planned.
- **Not foreseen: the height of the cost control.** The app of a window with no title bar is made to fill the window (`min-height: 100%`), so that its border is at the edges. With the cost control the window is as tall as the app says, so an app that is as tall as the window would have said the window's own height back, and the window could never have become lower again. The rule is now `#app.bare:not(.fit)`, as it was for the red border already: where the height is the content's own, the app is as tall as its content.
- **`TitleBarWindowTest` passes again**, for the first time since phase 81: both of its tests, run in the real window. What was left of it after phase 94 was the test's own. The probe counted every window of the toolkit, a tooltip among them, where the rule is about the windows of the program; and since phase 85, when the program began to write its log itself, nothing the program logged reached the console the test reads, because the probe starts the window class without `Main`, which opens the log. The probe now counts stages and puts what is logged on the console. It also opens the cost control: no title bar, the size `...,3,cost`, a border at the edges of the window, a drag that moves the window by 40 and 25 pixels, the log opened from it with a title bar, and none again after.
- **How it was checked.** The frontend suite, 398 tests; `./gradlew test`; `TitleBarWindowTest` and `PanelWindowTest` in the real window. Three rules were broken on purpose and each was caught: the cost control keeping the title bar in the page, a bare app filling the window where its height is measured, the host keeping the title bar for every panel.
- **Not seen.** The probe runs with the fake backend in use, so the border it saw round the cost control is the red one. The black border of one pixel is held by the tests of the page only.

### 96. The tab `About` can be dragged wider

**Status: built (version 0.79); seen in a real window on Windows.** Requirements: The tab `About` can be dragged wider; Settings; The window (what may be dragged). This is the one phase that adds to the contract between the page and its host.

- **The contract.** The third field of `contentSize` says what may be dragged: `0` nothing, `1` the height, `2` both, `3` nothing, with a panel. A fifth value is added, `4`, the width only. The page reports `…,4,settings` while the tab `About` is shown and `…,3,settings` on every other tab. `docs/api.md`, Window host contract.
- **Host.** `WindowFit.Resize` gets `WIDTH`; `parse` takes the `4`; `resizable()` is true for it. `UsageApp.applySize`: for `WIDTH` the least width is the width asked for (the least width of the settings), there is no most, and the height is held at what was asked. `UsageApp.resize`: `keepWidth` holds today for `BOTH` with the same panel; it must hold for `WIDTH` as well, so that the 150 ms tick does not put the window back, and the height asked for is applied at the kept width. Another tab reports `3`, which is not `WIDTH`, so the width asked for is applied: back to the least width, with nothing more to do. Opening the settings on `About` starts at the least width because the panel before was another, or none.
- **The height follows the width.** With `About` shown the page reports the height its content has at the width the window has: a wider window breaks the paths over fewer lines, and the page asks for less height at the next tick. This is the one place where what the page asks for follows from the size the window has, which `contentSize` otherwise forbids; it is safe because only the height follows, the height is not the person's to drag here, and the width asked for stays the least width whatever the window has. The comment in `contentSize` is to say so.
- **The page.** `contentSize`, the branch of the settings: the flag by the tab; the width is `Math.max(width, settingsWidth())` as before, and `settingsWidth()` must not grow with the window, which phase 94 has put right. The path may break anywhere, as today, and takes one line when there is room.
- **Not remembered.** Nothing is stored; `rememberedPanel` stays null for the settings. The window restored at start stores and restores a size as for any panel.
- **Tests.** `WindowFitTest` (the `4`, and `5` refused), `app.test.cjs` (the flag by the tab; the width asked for is the least width also when the fake display is wider; the height asked for is less in a wider display), `PanelWindowTest` (the real window: on `About` the stage has a least width and no most, and its height is held; on another tab both are held).
- **As built.** `WindowFit.Resize.WIDTH`, the flag `4`, and `Size.widthIsThePersons()` (true for `BOTH` and `WIDTH`). `UsageApp.resize` keeps the window's width for a size whose width is the person's while the panel and the flag stay the same, where it did so for `BOTH` alone; `applySize` holds the height and gives the width a least, the width asked for, and no most. The page reports `4` while `currentTab` is `about`. Nothing else was needed in the page: the height it reports for the settings was already that of the app as it lies, and the app is as wide as the window.
- **Where it is tested.** In `TitleBarWindowTest`, not `PanelWindowTest` as planned: the dragging is the host's, and that is the test that runs the application itself. Its probe now says at each step what the limits of the window let be dragged (`drag:w.`, `drag:h.`, `drag:wh.`, `drag:.`), and on `About` makes the window 300 pixels wider and then far too narrow.
- **In the real window.** `About` opens at the least width, 454 pixels of content here, and 441 tall; made 300 wider it stays so, the page still asks for 454, and it is 390 tall, since the long paths of the probe need fewer lines; made 200 wide it is back at the least width and as tall as before; with another tab chosen it is back at the least width and cannot be dragged. The other steps are as they were: nothing for the row and the other tabs, the height for the history, both for the log.
- **How it was checked.** The frontend suite, 400 tests; `./gradlew test`; `TitleBarWindowTest` in the real window. Two rules were broken on purpose and each was caught: the page reporting `About` as fixed (three tests of the page), and the host keeping a dragged width for the log alone (the window test: `dragged wider, it stays as wide as it was dragged`). A size of mine was a pixel off in the test at first: the window is rounded up to whole pixels of its frame, so its content can be a pixel more than was asked for.
- **Seen again, not of this phase.** Two more tests of `UsageServiceTest` failed once in a run of the whole suite and passed in the three runs after it. That is now four different tests of that class in some dozen runs since phase 93, never the same twice, never when the class runs alone.

### 97. The refused interval is forgotten by time

**Status: built (version 0.79).** Requirements: The automatic interval (`R` is forgotten by time).

- **Why.** `IntervalSearch.succeeded()` lowers the refused interval by 5 s after `ANSWERS_PER_DECAY`, 60, answers in a row; at 200 s that is 3 hours 20 minutes for one step, and the interval cannot go below `refused + 5`. That is what was seen: 200 s for hours, with no request failing.
- **`IntervalSearch`.** It stays a class with no clock of its own: the time is handed to it, `succeeded(long nowNanos)` and `rateLimited(long retryAfterSeconds, long nowNanos)`, and a field `decayFrom` is the time the 10 minutes are counted from. In `succeeded`: when a refusal is known and `now - decayFrom` is 10 minutes or more, `refused` is 5 lower (none below 10) and `decayFrom = now`: one step for one answer, however long it was. `decayFrom` is set to `now` by a 429, by each step, and when the search is made (`start`, `of`), which is the start of the program and the switching on of `Automatic`. `decayCount` and `ANSWERS_PER_DECAY` go. The step of the interval after 8 answers is unchanged, and comes before the decay in the same answer, as today.
- **`UsageService`** hands it the loop's own clock, the one `RefreshSchedule` is given, so that the clock of the tests moves both. The line `Automatic interval: <N> s (refused at <R> s)` is written when `describe()` differs from before, which a step of `R` alone already makes it do.
- **Saved as before**: `autoIntervalSeconds` and `autoRefusedSeconds` whenever they change, through `searchListener`. The time is not saved: a start begins the 10 minutes anew, as the requirement says.
- **Tests.** `IntervalSearchTest`: no step of `R` before 10 minutes, whatever the number of answers; one step at the first answer after 10 minutes, and only one after an hour of silence; a 429 and a step start the count again; `R` below 10 becomes none; the interval then follows `R` down by the steps of 8 answers; and the case that was seen, 200 s with `R` at 195, comes down. `UsageServiceTest`: the log line for a step of `R`, and the saved figures. The tests that counted 60 answers are rewritten against the time.
- **As built.** `IntervalSearch.succeeded(long nowNanos)` and `rateLimited(long serverSeconds, long nowNanos)`; `DECAY_NANOS`, ten minutes, in the place of `ANSWERS_PER_DECAY`; `decayFrom` and `timed`. `UsageService` tells the search the time by `searchClock`, which is `System::nanoTime` and another clock only in a test.
- **Not as planned: the ten minutes of a search that is made begin with its first answer**, not when it is made. `IntervalSearch.of` and `fresh` have no clock, and the service makes copies of the search with them to hand to whoever saves it; a search that is told the time only with an answer needs no clock to be made. The first answer comes at once at the start and after `Automatic` is switched on, so it is the same moment for the person, and the requirements say it so now.
- **Not as planned: `UsageService` has no clock of its own to hand on.** It reads `System.nanoTime()` where it needs the time; the clock of the search is the one thing that can be given, for the test.
- **What it does to the search, in figures.** The case that was seen, 200 s with 195 refused: 195 after eight answers, where it stood for more than three hours. From there the interval is held by its own pace alone, a step for eight answers: 27 minutes for 5 seconds at 200 s, 40 minutes at 300 s, 2 minutes at 15 s. Against a server with a fixed limit it settles as before and is refused fewer than six times an hour whatever the interval, since a refusal cannot come again before the refused interval has been forgotten a step.
- **The tests that failed now and then, looked into as phase 93 asked.** Three causes, none of them the load alone:
  - **One was a fault of the service, small and real.** The loop made the state of an answer visible before the interval and the countdown that belong to it, which it published only at the end of its step. Whoever read the state at that moment and then the interval got the interval of the answer before: a 429 with the 60 seconds it was refused at, where the search was at 300. The window, which asks once a second, could show that for one poll. `finished` and `begin` now publish before they set the state. The new test `whoeverSeesTheStateOfAnAnswerReadsTheIntervalThatBelongsToIt` spins on the state 200 times and fails at once with the old order.
  - **Two were races of the tests.** `Gate.fetch` said that a call was under way before it had looked whether it was to be held, so a test that saw it under way and then opened the gate for the next call could let the first through with it, and then waited five seconds for it to be given up. And a test waited for a 429 that was over 30 milliseconds later, with the next request, looking every 5 milliseconds: on a busy machine it could miss it for good. The first settles the hold first; the second keeps the 429 coming.
  - **One is left as it is**: `aRateLimitedRefreshIsFollowedAfterExactlyTheIntervalEveryTime` wants gaps of 60 milliseconds to be under 150, which a busy machine can break. It is what the test is about, and it did not fail again.
- **How it was checked.** `IntervalSearchTest` rewritten on a clock of its own, 18 tests; `UsageServiceTest` with the two new ones; the whole Java suite six times in a row, all green, where two of seven and one of four runs had failed before. Three rules were broken on purpose and caught: the state set before the interval is published (the new test), an hour in the place of ten minutes (seven tests), and, in passing, a first expectation of mine that was wrong: I had the search at 250 after four hours from 300, and it is at 270, by the pace of eight answers, which is why that pace is now said in the requirements.

### 98. The log can be reset, and the reset undone

**Status: built (version 0.79); not looked at in the window yet.** Requirements: The log can be reset, and the reset undone; The log panel; Non-functional requirements (the program writes its log itself).

- **`Logging`.** Today it opens the file once and holds its writer until `close()`. It gets three operations. The first two run under the lock that makes entries one at a time, so that an entry never falls between two files, and are refused when the file sink is not open:
  - `reset(now)`: close the writer; rename `java-aip-usage.log` to `java-aip-usage.<yyyy-MM-dd_HH-mm-ss>.log`, never replacing (a file of that second that is there already is a failure); open a new file for appending; then log `The log was reset: the lines before are in <name>`. If the rename fails the old file is opened again and nothing has changed.
  - `merge()`: find the newest old log by its name (the stamp sorts as text), with a strict pattern for the name so that no other file is ever taken for one; close the writer; write `java-aip-usage.log.tmp` as the old log's bytes followed by the current log's; move it over `java-aip-usage.log` in one step, the way `SettingsStore` and the history's upgrade do it; delete the old log; open for appending; log `The log was merged with <name>`. A failure before the move leaves both files as they were and removes the `.tmp`. If the old log cannot be deleted after the move, that is a warning, since the next merge would join its lines a second time.
  - `hasOldLog()`: whether a file of the pattern is there, asked of the directory each time the log panel reads.
- **Windows.** A file that is open cannot be renamed there, which is why the writer is closed first; `LogTail` opens the file for each read and holds nothing. To be checked on Windows that a read of the log panel cannot be in the way at the moment of the rename; if it can, the rename is tried once more after a moment.
- **The API.** `POST /api/log/reset` and `POST /api/log/merge`, whose bodies are not read, answered 200 with `{"oldLog": true|false}`; a failure is 500 `The log could not be reset: <reason>` or `The log could not be merged: <reason>`, in the words of `Failure`; a merge with no old log is 409 `There is no old log.` `GET /api/log` gets `oldLog` (boolean). No request names a file and no answer gives a path away. `docs/api.md`.
- **Who holds what.** `ApiHandler` is given the operations through `AppRuntime`, which is given the `Logging` of `Main`. A run that logs to the console alone answers 500.
- **The page.** `index.html`: two buttons in the line of the zoom buttons, after them, shown with the log alone: `log-reset` and `log-merge`, icons with no text, with the tooltips of the requirements; `log-merge` is disabled, with `There is no old log`, while the last answer said `oldLog: false`. `app.js`: a click posts and reads the log again at once; the red line of the panel shows a failure. The marking of the start line in `view.js` needs nothing: it marks the line where it is.
- **`.gitignore`**: `java-aip-usage.*.log`.
- **Tests.** `LoggingTest`: a reset renames and begins anew, and the first line of the new file; a line logged from another thread during a reset is in one of the two files, whole; a merge joins in order and deletes the old log, and the file is, byte for byte, the one there was before the reset and what came after; with two old logs the newest is taken, then the next; a name of that second that is there already, and a directory that refuses the rename, change nothing; a file that only looks like an old log (`java-aip-usage.notes.log`) is never taken; no `.tmp` is left. `ApiTest`: the two posts, the 409, the 500, `oldLog` in the get, and that no answer holds a path. `LogTailTest`: the panel's read after a reset and after a merge. `app.test.cjs`: the two buttons with the log only, the disabled state, the click, the failure. `LoggingEndToEndTest`: reset, lines, merge, and the file is whole.
- **As built.** `Logging.reset(LocalDateTime)`, `merge()`, `newestOldLog()`, `hasOldLog()`, and `Logging.of(Path)`, which finds the log that is open on a file. `Log.whileNothingIsWritten(...)` runs something under the lock that hands entries to the sinks one at a time; the reset and the merge close the writer, move the files and open a writer again inside it, and log their own line after it. The file sink writes to whatever writer the log has at the moment of the line. `ApiHandler`: `POST /api/log/reset` and `/api/log/merge` (`logAction`), `oldLog` in `GET /api/log`. The page: the buttons `log-reset` and `log-merge` in a span `log-files` after the zoom buttons, `takeOldLog` and `logFileAction`.
- **Not as planned: who holds the log.** The plan had `AppRuntime` given the `Logging` of `Main`. `Main` opens the log before the window class is started by the toolkit and has no way to hand it on but a static one, so the log that is open says so itself: `Logging.of(file)` gives it when it is open, has a file, and the file is the one asked for. The handler asks for the log of the file its panel reads, which is the same path `Main` opens (`LoggingTest` holds that).
- **Windows.** Nothing had to be tried a second time: the writer is closed for the move, the panel's reads hold no file, and the test that resets and merges while another thread writes 3,000 lines passes here.
- **Not written: a test in `LoggingEndToEndTest` and one in `LogTailTest`.** What they were to hold is held nearer by: `ApiTest` resets and merges through the API with a log open on the file and reads the panel's lines after each, and `LoggingTest` has the files byte for byte.
- **How it was checked.** The frontend suite, 407 tests; `./gradlew test`. Three rules were broken on purpose and each was caught: the reset and the merge done without waiting for what is being written (the test with the writing thread), any file with the beginning and the ending of the name taken for an old log, and the old log put behind the current one.
- **The test that phase 97 left as it was did fail again**, once in the two runs of the whole suite made here: `aRateLimitedRefreshIsFollowedAfterExactlyTheIntervalEveryTime`, with gaps of 60 milliseconds held between 55 and 150. Phase 97 said it had not failed again and left it; that was said too early. It now runs with an interval of 200 milliseconds and takes a gap from 140 to under 400: a wait that doubled, which is what the test is there to catch, is 400 and more. The first attempt, 195 at the least, failed more often than the old bounds, and that showed what the lower bound has to allow for: the interval is counted from when a request is triggered, and the test measures when its call began, which on a busy machine is some milliseconds later, so a gap can be that much shorter than the interval as well as longer. The whole suite then passed six times in a row.
- **By eye, still to do:** the two icons in the line of the log, the disabled one beside the others, and a reset and a merge in the running program on each system, with the log panel open.

### 99. The name of the program on macOS

**Status: built (version 0.79) on Windows, and not checked on macOS: nothing of it has been seen on a Mac. One part of the requirement is not met and is an open point.** Requirements: The name of the program, as the system shows it; The Java implementation (the name on macOS).

- **What is seen today.** The name beside the apple and in the Dock is `java`, the menu says `Hide org.example.UsageApp` and `Quit org.example.UsageApp`, and the Dock shows the icon of a program that has none.
- **The constraint.** The requirements ask for it from `./gradlew run` and from an IDE's plain `main` alike, with no VM options and no bundle. So what `build.gradle.kts` can pass to the JVM (`-Xdock:name`, `-Xdock:icon`) is half an answer at most; the other half has to be done by the program itself, in `Main`, before `Application.launch`.
- **The spike, in this order, each tried on the Mac from Gradle and from the IDE:**
  1. What a program can set for itself before the toolkit starts: the system properties for the application's name, and whatever the JavaFX toolkit takes the name of its menu from, which today is the name of the class.
  2. The Dock icon set by the program, with the 256 pixel icon of `AppIcons` (`java.awt.Taskbar` is the public way, and means loading AWT beside JavaFX: to be checked that it shows nothing of its own, and it is not called on Windows).
  3. The menu: whether the toolkit's own application menu can be given the name, or replaced by one of the program's with `Hide aip usage` and `Quit aip usage`.
  4. If the name beside the apple cannot be changed for a process that was started as `java`: the program starting itself once more under the name, or a minimal app bundle made by the build, both of which the requirements have so far ruled out.
- **What comes of it.** If 1 to 3 give the name everywhere, they are built, in a small class of their own that `Main` calls on macOS only, each step guarded so that a failure is a debug line and stops nothing. If something is left that only 4 can do, it is not built: it is written down under Open points with what was tried, and brought back as a decision, since a bundle or a second start is a change to The Java implementation and to Scope and limits, not a detail.
- **`Quit aip usage`** must end the program by the one shutdown path (`UsageApp.shutDown`, `RunOnce`), with the lines of the stopping in the log: to be checked that the quit of the menu arrives there, and not as a kill.
- **Windows.** The taskbar shows the title of the window and the icon already; to be looked at once, nothing is expected to change.
- **Tests.** What a test can hold: that the name given to the system is `AppInfo`'s, not written a second time (`AppInfoTest`), and that nothing is done on another system. The rest is by eye.
- **In the place of the spike on the Mac: the toolkit's own code, read here.** There was no Mac to try things on, so the macOS build of the toolkit (`javafx-graphics-25.0.4-mac-aarch64.jar`, fetched from Maven Central into a scratch directory and only read) was taken apart instead. What it says:
  - **The menu.** `MacApplication.installAppleMenu` writes `Hide ` and `Quit ` each followed by `getName()`, the name of the toolkit's application. `Application.setName` takes a name only while the name is still the default, `java`. And `LauncherImpl` makes the application object and, right after it, calls `PlatformImpl.setApplicationName(appClass)`, which gives the name of the class: that is the `org.example.UsageApp` of the screenshot. So a name given while the object is made, in its constructor, is the first and stays.
  - **The icon of the Dock.** The native library reads `APP_ICON_<pid>` from the environment and sets the application's icon from it; that variable is what the launcher of macOS makes of `-Xdock:icon`. It has no call of its own for the icon that the program could make.
  - **The name in bold beside the apple.** The native library reads `CFBundleName`, the name of an application bundle, and nothing like the `APP_NAME_<pid>` that `-Xdock:name` sets. So that name is the bundle's, and with no bundle it is the process's, `java`.
- **As built.** `MacName`, with three steps, each of them taken on macOS only, guarded, and a debug line when it cannot be taken:
  - `beforeLaunch()`, called by `Main`: the system property `apple.awt.application.name`, for the part of Java the third step loads.
  - `nameTheApplication()`, called in the constructor of `UsageApp`: the toolkit's application is given the name `AppInfo.NAME`, by asking the toolkit's class by name, since there is no public way. On the module path that class is closed to the program, so `build.gradle.kts` opens it for `./gradlew run` (`--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`); started from an IDE with the toolkit on the class path it is open already.
  - `showTheIconInTheDock()`, called when the window is shown: `java.awt.Taskbar.setIconImage` with the icon of 256 pixels, on a daemon thread of its own; not when the start already named an icon (`APP_ICON_<pid>` is set).
  - `build.gradle.kts`, on macOS only, since no other launcher knows the options: `-Xdock:icon=<the icon of 256 pixels>` and `-Xdock:name=aip usage`. The first the toolkit follows; the second it most likely does not, and it is there because it was asked for and does no harm.
  - A switch, `-Daipusage.macname=false`, takes all of it away; `true` takes the steps on any system, which is how the test sees the first of them on Windows.
- **Open point: the name in bold beside the apple, and the name under the icon in the Dock, stay `java`.** By what the toolkit's code says, nothing a program started as `java` can do for itself changes them: they are the bundle's. The two ways that would, a minimal app bundle made by the build or the program starting itself once more under another name, were ruled out before building and are not built. This is the part of the requirement (The name of the program, as the system shows it) that this version does not meet; the Java section of the requirements says so. It needs a decision: an app bundle for macOS after all, or the requirement taken back to the menu entries and the icon.
- **What was seen, and it is little.** On Windows, in the real window (`TitleBarWindowTest`, started as the build starts the program and with the switch on): the toolkit's application is named `aip usage`; with the call taken out of the constructor it is `org.example.UsageApp`, and the test fails. The system's own Java, loaded beside the toolkit for the icon, did no harm there: every step of the probe after it was taken. `./gradlew run --args="--help"` starts with the new options.
- **Not seen at all, and to be looked at on the Mac, from Gradle and from the IDE:** that the menu reads `Hide aip usage` and `Quit aip usage`; that `Quit aip usage` ends the program with the lines of the stopping in the log; that the Dock shows the gauge, in both ways of starting; that loading the system's own Java beside the toolkit does nothing to the menu, the window or the start on macOS, which is the one step here that could do harm and is why it has a thread of its own and a switch; and what the bold name and the Dock's label say.
- **Tests.** `MacNameTest` (new: which systems are macOS; the switch; that nothing is done or said when it is off; that the name is `AppInfo`'s and written nowhere in `MacName`; that a step that cannot be taken is one debug line and no fault). `TitleBarWindowTest` (the name of the toolkit's application in the real window). The whole Java suite twice, and the frontend suite.

**By eye, when 91 to 99 are built**, on macOS and on Windows, on the light and the dark theme: the order of the tabs, and the tab the settings open on with none stored; the blue start lines in the history and the log, and a failed start line with its red word; the two bars, square and equally wide, with a short and a long text and with the warning sign; the bold file name in a path that is broken over two lines; a tooltip on every control of every tab; the dimmed buttons with the settings open, and that the row does not move when one is switched; the zero usage button in both states; log, then settings, and the width; the history with every column on, at the smallest and the largest text, with no horizontal scrollbar; the cost control with no title bar, its border, dragging it, and the flicker on the way to the log; the tab `About` dragged wide and narrow, its height following, another tab and back; the automatic interval over an hour against a fake backend that refuses within a time; reset and merge of the log, twice in a row, with the panel open; and the name in the menu bar, the menu and the Dock.

### 100 to 106. Version 0.80: the second round of notes

**Status: built, all seven, as planned (phase 105 in two commits); what each lists "by eye" is still to be looked at, on Windows and on macOS.** The requirements are written (every rule marked "since version 0.80"); these seven phases build them, in this order, and `AppInfo.VERSION` becomes `0.80` with the first. Each is committed by itself with its tests green, as `Phase 10x built: <what> (0.80)`. The first four are small and independent; 104 adds to the API; 105 and 106 are one feature in two steps, the page first and its host after, and are the bulk of the work.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 100 | The small changes of the page: `icon` in the place of `button`, bold blue notes, list icons that are seen, less room in the cost control, the logo of About at the left, the placeholder as a line, a sturdier typeface | `index.html`, `app.css`, `app.js` |
| 101 | A zero usage line is one whose change is 0.00; a fall is a value; a tooltip for each column title | `HistoryReader`, `ApiHandler`, `app.js` |
| 102 | The settings: `General` at the first opening after a start, and a view whose icon is switched off is not given back | `app.js`, `SettingsStore`, `ApiHandler` |
| 103 | The fault: the lines of the history that are not drawn | `app.js`, found by a failing test first |
| 104 | The paths in the log are links | `Logging`, `LogTail`, `ApiHandler`, `Desk`, the log panel |
| 105 | Several views at once, in the page | `index.html`, `app.js`, `app.css`, `view.js` |
| 106 | Several views at once, in the host and at the start | `WindowFit`, `UsageApp`, `RememberedHeights`, `SettingsStore` |

`docs/api.md` is brought level in the phase that changes an endpoint or the host contract (101, 102, 104, 105, 106). The two window tests that run only when asked for, `PanelWindowTest` and `TitleBarWindowTest`, are run in every phase that changes what the page or the window does, which phases 91 and 92 did not do.

**Decided before building**, in two rounds of questions: views stacked in the order of their icons, each at the height it has alone, the lowest list taking a dragged height; the settings always alone, giving every view back; `General` at the first opening after a start only; sturdier typefaces at regular weight, for all three lists; a zero usage line is one whose `Δ used` would be 0.00, a start line included; a fall written as a value; a view whose icon is switched off is not given back, which takes back phase 92's rule; paths of the program's own files as links, opening the folder; the icons of the lists in the colour of the text and larger, the row's as they are; `icon` for the four that show a view; every blue note bold; the name and the version of About at the right, above the table; every view restored at start; the placeholder a line at the bottom everywhere, with the whole place of the figure for the mouse. The note of `docs/tmp.md`, another directory for the log and the history, is left for a later round.

**Decided about the building itself**, in two more rounds:

- **One version, a commit for each phase, and no push until it is asked for.** All seven are 0.80; the constant changes with phase 100. The order is the one of the table, the small ones first and the several views last.
- **The first commit has the notes as well**: `docs/new-requirements.md`, `docs/tmp.md` and the pictures `docs/img_15.png` to `docs/img_27.png`, with the requirements and the plan, so that the round is whole in the history. (In the round before they were left to their author.)
- **Phase 105 is two commits**: the box and the state for each view first, with the switch always off and every test as it is, then the switch. The first is a program that works as today's if the second goes wrong.
- **With several views each list has its own line of controls**, as when it is shown alone; the zoom is in each and changes all three.
- **The fault of phase 103 has no known steps**: its author does not remember what was done before the picture. Every path the phase lists is tried with a test; if none shows the fault, that is said, and nothing is changed on a guess.
- **If the window does not take the typefaces that are named** (phase 100), a typeface is shipped with the program: a file among its resources, under an open licence, loaded from there and named first. Nothing is fetched. This is decided in advance so that the phase does not stop for it; whether it is needed is seen in the JavaFX window on Windows, and it is not done if the names are taken.
- **The name macOS shows in bold stays the open point of phase 99**: nothing about it in this round. What 0.79 did there is to be looked at on the Mac first.

### 100. The small changes of the page

**Status: built (version 0.80); seen in the real window on Windows by the two window tests, not looked at by a person.** As built: everything as planned below, in `index.html`, `app.css`, one constant of `app.js` (`COST_GAP`), one flag of `view.js` (the log's note that lines are left out is now a highlighted one) and the usage text. **No typeface had to be shipped**: asked in the real window, the list is drawn in Cascadia Mono, and the window has Consolas as well (`PROBE list-typefaces=has:[Cascadia Mono|Consolas] used:Cascadia Mono`); `PanelWindowTest` holds that the window takes one of the named typefaces. `TitleBarWindowTest` was brought level with three looks it measures: the placeholder's line of 2 pixels at the bottom where it had a frame of 1, the zoom button of 22 pixels where it was 20, and the logo of About at the left of its text. Checked by the frontend suite (413 tests), `./gradlew test` and both window tests. Requirements: The thirteen elements and their names; The usage history panel (the note); A zoom for the lists; The cost control panel: two bars; The About view; A placeholder at the start; Gray boxes in the place of `No usage reported` and `No data`; A sturdier typeface for the lists.

- **`icon` in the place of `button`.** `index.html`: the texts of the four checkboxes of `Main view`, `Log icon`, `History icon`, `Error log icon`, `Cost icon`, and their tooltips, `The icon that shows …`. The ids, the keys of the settings and `Refresh button` stay. The usage text of the command line is not touched by this; it still says `a debug button in the window`, which has been wrong since the Debug tab of phase 76 and is put right here, in `LaunchOptions.USAGE`, to the words the requirements give.
- **Bold blue notes.** `app.css`: `.panel-note.highlight` gets `font-weight: 700`. Which notes are blue is said by the backend or by `view.js` already (`noteHighlight`); the log's two, `Showing the newest N lines of the log.` and `The log has no lines at this level.`, and the error log's `There are no errors in this run.` are to be checked for being highlighted, and made so where they are not.
- **The icons of the lists.** `app.css`: a rule for the buttons in the line at the top of a list (`.log-controls .icon.tiny`): the colour of the text, 22 pixels, and its `svg` 16 pixels with a line of 2. `.icon.tiny.on` stays blue and comes after it, to win; `.icon[disabled]` stays at a third. The icons of the row have their own rules and are not matched.
- **Less room in the cost control.** `app.css`: `.cost-label` loses its `min-width`; the grid of `.cost-rows` gets `gap: 4px 6px`. `app.js`: `COST_GAP` is 6, as `costWidth()` adds it.
- **The logo of About at the left.** `index.html`: the logo in a cell of its own, and the name, the version, the red line and the table in a second element beside it. `app.css`: `.about` becomes a grid of two columns, `max-content` and `minmax(0, 1fr)`, aligned at the top and at the left; the centring goes. The paths still break when the view is narrow, and the least width of the settings is unchanged, since About is not measured for it.
- **The placeholder as a line.** `app.css`, `.skeleton`: the border on all four sides becomes `border-bottom: 2px solid var(--ok)` alone; the box keeps its width, its height and `display: inline-block`, so that the whole of it takes the mouse. The thickness is the one the selected tab has (`.settings .tab` has `border-bottom: 2px`); the test holds the two together.
- **A sturdier typeface.** `app.css`: the font of `.panel`, of `.table-probe` and of `.cost` is named in three places today; it becomes one variable, `--list-font`, with the stack of the requirements, so that the copy the table is measured on cannot have another typeface than the table.
- **Seen in the window before anything else of it is done.** The names are a request, and the WebKit of the JavaFX window decides what it takes. `PanelWindowTest`'s probe is given one more line: the typeface the list is really drawn in, asked of the page by measuring a line of the list against the same text set in each named typeface and in `monospace`, since a page cannot ask for the name outright. If on Windows it is one of the named ones, the phase is done with the stack.
- **If it is not: a typeface of the program's own.** A fixed-width typeface under the SIL Open Font License, Cascadia Mono as the first choice since it is the one named first, as a file in `src/main/resources/web/fonts/`, with its licence text beside it; `app.css` declares it with `@font-face` from that file and names it first in `--list-font`; `LocalWebServer` serves the ending `woff2` with its type. The page still loads nothing from the network. The width of the history's table is measured after the typeface has loaded, which `currentTableWidth()` allows for already, since it measures again at every asking. The requirements (A sturdier typeface for the lists; the files of the page under The local server) are then changed to say that the program brings the typeface, and the test that the page has four files becomes one that it has those and the typeface.
- **Tests.** `layout.test.cjs`: the four texts and that no checkbox of `Main view` but the refresh one says `button`; the weight of the highlighted note; the rule of the list icons and that it does not match the row's; no `min-width` in `.cost-label` and the two gaps; the grid of About and the order of its parts; the placeholder: a bottom border of 2 pixels in `--ok`, no other border, and the same thickness as the tab's; `--list-font` used by the three rules, and the stack. `app.test.cjs`: `costWidth` with the gap of 6; the notes of the log and the error log highlighted. `LaunchOptionsTest`: the usage text.
- **By eye.** All of it, on Windows: this phase is about how things look, and the typeface in particular has to be seen in the JavaFX window, whose WebKit may pass over a name another browser takes.

### 101. A zero usage line, a fall as a value, and tooltips for the titles

**Status: built (version 0.80).** As built: `HistoryReader` asks `HistoryDeltas.amountChanges`, the figures of the `Δ used` column, whether a row's change is 0.00 to the cent, in the place of its own comparison inside a run; `ApiHandler.history` writes every change but 0.00; `HistoryBody.columnTitles`, which the page puts on the header cells (the key is `columnTitles`, since `titles` was taken by the tooltips of the lines). The two checkbox tooltips follow. Nine tests held the old definition and were rewritten: with the history the notes showed, a start line that found a change is shown with its mark and its value, one that found none is hidden, a line that repeats the last amount across a failed line is hidden, and a fall has its minus. Checked by `./gradlew test` and the frontend suite (415 tests). **Found on the way:** the test phase 97 added for the refused interval read what was saved as soon as the state said the request had ended, and the saving follows the state since that phase; it failed once here, and now waits for what is saved. Requirements: Changes between readings; The usage history panel (Hiding lines); A tooltip for each column title of the usage history.

- **Today.** `HistoryReader` decides what a zero usage line is on the whole file, against the row directly before each row and inside a run: `zero = !failed && (startup || changeAgainstTheRowBefore == 0)`. So a start line is always one, whatever `Δ used` shows for it, and a line after a failed one never is. `ApiHandler.history` then writes `Δ used` only when it is above zero.
- **The rule.** One figure decides both: the change against the last earlier line that has an amount, which `HistoryDeltas` already works out for the column. A line is a zero usage line when that change is exactly 0.00; a failed line never is. `HistoryReader` takes it from there, and the second comparison, with its own notion of a run, goes. The first line of the file has the amount itself as its change and is no zero usage line.
- **A fall is a value.** `ApiHandler.history`: the cell is `delta.deltaUsedText()` for a rise and for a fall (`Formatting.signedAmount` writes the minus already, as the row has it), and empty for 0.00. The cost control is not touched: `CostPanel` counts a fall as no spending by its own rule.
- **Tooltips of the titles.** `HistoryBody` gets `titles`, a list beside `columns` and `kinds`, written by the backend; the page puts each on its header cell (`cells(...)` takes them for the header row). `docs/api.md`.
- **The tooltips of the two checkboxes** of `History view`, `Zero usage lines` and `Δ used`, as the requirements now have them.
- **Tests.** `HistoryReaderTest`: a start line with a change is shown and one without is hidden; a line after a failed one with the amount of the last good reading is hidden; a fall is no zero usage line; the first line of the file is none; the count of the note. `ApiTest`: a fall in the cell, with its minus; 0.00 empty; `titles` for each set of columns, with and without the date. `HistoryDeltasTest` if the figure it gives has to carry more. `app.test.cjs`: the tooltips on the header cells. The tests that held the old definition (`theStartupLinesCountAsZeroUsageLines…`, `aRowThatFollowsAFailedRow…`) are rewritten, each first, and seen failing.
- **To be looked at with the pictures of the notes**: the history of the fake backend that the screenshots show, with the toggle on, has no line with an empty `Δ used` but a failed one.

### 102. The settings: `General` after a start, and what they give back

**Status: built (version 0.80); both window tests run.** As built: the page keeps the tab in `currentTab` alone, which begins at the first tab and is changed by `chooseTab` and `restoreView`; `tabChosen`, the taking of `settingsTab` from the settings and the post are gone, and so are `POST /api/settings-tab`, the key in the answer of `GET /api/settings`, and the key, its two methods and the parameter of `write` in `SettingsStore`. **The giving back is decided in one place, and not only after a save**: `dropWhatIsSwitchedOff()` asks the settings as they are saved, which the form was last told, when the settings are left by `Save`, by the gear or by another icon, whether or not that leaving had anything to save. So a view that was reached through a dimmed icon is not given back by the gear either, as the requirements say, where the lines phase 92 took out looked only after a save. `Cancel` does not ask. Checked by the frontend suite (416 tests), `./gradlew test`, `TitleBarWindowTest` and `PanelWindowTest`. Requirements: Settings (the tabs; Leaving the settings brings back the view); The panel buttons while the settings are shown; The window is restored at start.

- **`General` at the first opening after a start.** `app.js`: `tabChosen` and the taking of `body.settingsTab` at the first `loadSettings` go; `currentTab` begins as `TABS[0]` and is changed by `chooseTab` and by `restoreView` alone. `chooseTab` no longer posts. Backend: `POST /api/settings-tab`, `settingsTab` in `GET /api/settings`, `IntervalSettings.settingsTab`/`storeSettingsTab`, and in `SettingsStore` the key, `loadSettingsTab`, `saveSettingsTab` and the parameter of `write` that carried it through every save. A `settingsTab` left in a file is ignored like any key that is none, and is gone with the next save. `lastTab`, of the window restored at start, stays as it is. `docs/api.md`: the endpoint and the key are taken out.
- **A view whose icon is switched off is not given back.** Phase 92 took out of `saveSettings` the lines that dropped `returnTo` when the saved settings switch that view's icon off; they come back, with the name of the setting for each view (`showLogIcon`, `showHistoryIcon`, `showErrorIcon`, `showCostIcon`). `Cancel` saves nothing and so gives back as before. The button of a panel that is opened through a dimmed button is not concerned: that is not a giving back, and it stays until the panel is closed.
- **Tests.** `app.test.cjs`: the first opening is on `General` whatever the backend holds; within a run the last tab, About included; no request for the tab is made. The tests of phase 92 that hold the giving back are turned round: `Save`, the gear and another panel's button do not bring back a view whose icon was unchecked, `Cancel` does; a view whose icon is on comes back as ever. `ApiTest`: `/api/settings-tab` is no endpoint (404), `GET /api/settings` has no `settingsTab`. `SettingsStoreTest`: the key is neither read nor written, and a file that has it loses it with a save.
- **`TitleBarWindowTest`**: its probe goes to `Main view` by `tabbtn-main` already; to be run, since it opens the settings several times in one run and now finds them on the tab they were left on from the first opening.

### 103. The fault: the lines of the history that are not drawn

**Status: built (version 0.80). The cause was found, and it was none of the paths listed below.** The note of the picture reads `12 zero usage and 1 failed hidden`: both kinds of lines hidden at once, which is what the button in the heading of `History view` does, the one phase 91 added, when all nine checkboxes were on: it switches them all off, the six columns too. With no column on the backend still sent a line for every row, each with no cell in it, and a note that counted them; the page drew lines with nothing in them. A test with every checkbox of `History view` off shows it: `Showing 3 of 7 lines: 3 zero usage and 1 failed hidden.` over nothing. **As built:** `ApiHandler.history` answers a history that has rows and no column with no lines and the note `No column of the history is switched on. Switch one on in the settings, under History view.`, highlighted; the requirements say so, in the place of 0.79's "has no table to show". **One of the listed paths was a fault too, a smaller one:** in `loadPanel` the branch of the cost control ended with `return`, inside the `try`, which left out what follows a read: starting the read of a view that was opened meanwhile. A history opened while the cost control was being read waited for the next poll. It is an `else` now. The other paths, tried with tests, draw the lines. Checked by the frontend suite (419 tests) and `./gradlew test`; the two tests of the fault were seen failing first. Requirements: The lines of the usage history are always shown. A fault, so it begins with a test that shows it; what follows is where to look, not what was found.

- **What was seen.** The history open, the note saying `Showing 35 of 48 lines: 12 zero usage and 1 failed hidden.`, and no lines under it: no header either.
- **The steps that led to it are not known**, and its author does not remember them. So no one path is tried first: all of those below are, each with a test, and if none of them shows the fault that is reported and nothing is changed on a guess.
- **Where to look.** The list, `#panel-lines`, is hidden in two places of `showPanelNamed`, for the cost control and for the settings, and shown again in one, at the end of `showPanel`, which runs only when what is to be drawn differs from what was drawn last (`panelKey`). The note is set by the same `showPanel`, so a note with no lines means that `showPanel` ran and the list was hidden after it, or that it was hidden and `showPanel` then returned early for an answer it took for the one already shown. Candidates: an answer of the history that arrives after the cost control or the settings were opened and left again; the read that follows a press of the zero usage button (phase 93) while another is under way; the settings giving the history back. The steps that led to the picture are not known, so each is tried with the fake display.
- **The remedy** is one rule in one place: whether the list is shown follows from which view is open and whether it has lines, worked out wherever either changes, and not set by hand in three places.
- **Tests.** `app.test.cjs`: each of the candidates above, ending with the lines drawn whenever the note says that lines are shown; and the rule itself, that the list is never hidden while the history, the log or the error log is the view shown and has lines.
- **Phase 105 rebuilds this part of the page**; the test stays and must still hold there.

### 104. The paths in the log are links

**Status: built (version 0.80); not looked at in the window yet.** As built: `Logging.reset` and `merge` log the full path of the old log; `Logging.oldLogName(logFile)` is the one pattern for the name of an old log, used by the merge and by the links. `ApiHandler`: `links` in `GET /api/log` (`logLinks`), each a line, a place and a name; `POST /api/log/open` (`openLogLink`), which takes the names `settings`, `log`, `history`, `fake-history` and `old:<name>`, and nothing else. A path is found as the whole text of a path the program knows, as it logs it and as an absolute one, and not when it is the beginning of a longer name (`…java-aip-usage.log.tmp` is not the log); an old log by the form of its name beside the log, also when the file is gone. The page: `describeLog` keeps the links with their lines through the turning of the order and moves them by the date that is cut; `linkPaths` draws such a line as text, links and text; `openLogFile` posts the name. Checked by the frontend suite (425 tests) and `./gradlew test`. Requirements: The paths in the log are links; The log can be reset, and the reset undone.

- **The two lines.** `Logging.reset` and `merge` log the full path of the old log, where they log its name. `ApiTest`, `LoggingTest` and the page tests that hold the wording follow.
- **Which paths are links is the backend's to say.** `GET /api/log` gets, beside `lines`, a list `links`: for each line that holds the path of a file of the program's own, where in the line it is and what the backend calls it, `{"line": 3, "from": 61, "to": 118, "file": "old:java-aip-usage.2026-10-13_21-06-29.log"}`. The files are the three of `AppFiles`, the fake history, and every old log beside the log that has the form of the name (`Logging.newestOldLog`'s pattern, as a list). A path is looked for as the whole text of the file's absolute path, and nothing is guessed from what a line looks like.
- **A click.** `POST /api/log/open` with `{"file": "<the name the backend gave>"}`, after the pattern of `POST /api/about/open`: the names `settings`, `log`, `history` and `fake-history`, and `old:<name>` for an old log, whose name must have the form and be beside the log; anything else is `400`. It reveals the file in its folder with `Desk`, the folder alone when the file is gone. A failure is `500` with `The folder could not be opened: <reason>`. No path comes from the page.
- **The page.** `view.js` passes the links on with the lines, and keeps them with their line when it puts the newest first and when it cuts the date, which moves a place in the line by the 11 characters it takes. `app.js`: a line with a link is drawn as text, a link, and text; the link is an `a` of the class the paths of About have, with their tooltip, and a click posts the name. The log is drawn again every second only when it changed, so the links are not rebuilt under the mouse.
- **`dragStartsAt`** already refuses an `a`, so a click on a link in a window with no title bar moves nothing.
- **Tests.** `ApiTest`: `links` for a reset line, for the line `Logging to …` and `Settings are stored in …`, none for a path that is no file of the program's; the post for each name, for an old log that is there, for one that is gone, for a name of another form and for a path, which are refused; no path in an answer. `view.test.cjs`: the place of a link with and without the date, and in the reversed order. `app.test.cjs`: the link is drawn, the rest of the line is text, a click posts the name and nothing else, a failure shows the red line. `layout.test.cjs`: the link has the look of About's.

### 105. Several views at once, in the page

**Status: built (version 0.80), in two commits as decided; the switch came with phase 106.** As built: the panel has a box for each view, `view-log`, `view-history`, `view-errors` and `cost-view`, each list with its own `log-controls-<name>`, `zoom-in-<name>`, `zoom-out-<name>`, `log-error-<name>`, `panel-note-<name>`, `panel-error-<name>` and `panel-lines-<name>`; the cost control has its own red line for a read that failed. `app.js`: `openViews` and `settingsOpen` in the place of `openPanel`, `views[name]` in the place of the one state of the one list, `loadPanel(name)`, `showPanel(name, shown)`, `showViews(names, settings)` in the place of `showPanelNamed`, `layoutViews()`, `severalSize(width)`; `returnTo` is a list. `several()` reads `show.severalViews` of the status, and the saved settings while the settings are shown. **Each view is read by itself**: a view opened while another is being read no longer waits for it, which the requirements now ask and one test had held the other way. **The report of the size with several views** is `width,height,flag,log+history,alone`: the names joined, and after them the height the lowest list has alone, for the host of phase 106. **The first commit** left the switch off and every test of the page passing; the tests that show one view at a time still ask for `panel-lines`, `panel-note` and the like, and the harness gives them the part of the view that is shown, or was shown last, hidden when its box is. Checked: the frontend suite, 427 tests after the first part and 442 after the second, `./gradlew test`, and after the first part both window tests in the real window. Four rules of the second part were broken on purpose and caught: a view taking the place of the others, a view that stays being reset, every list stretching, the settings giving back one view. Requirements: Several views at once; Compact window (the row); Settings. The larger of the two steps: the page today has one view open, and everything about it is one of a kind.

- **Today.** `openPanel` is one name or none, read in some fifty places of `app.js`. The four views share one box, `#panel`, with one line of controls (`#log-controls`), one note, one red line and one list (`#panel-lines`), and beside them `#cost-view` and `#settings-view`; and one each of what is known of the list that is drawn (`panelKey`, `panelRows`, `panelMark`, `panelHasHeader`, `panelLoading`), of the choices of the log and of the history, and of the height the window was opened at (`panelHeight`).
- **The page gets a box for each view.** `index.html`: four sections under the row, in the order of the icons, `view-log`, `view-history`, `view-errors`, `view-cost`, each with its own line of controls, note, red line and list, or its bars; the settings stay one view of their own. The controls that are one for all three lists, the zoom, are repeated in each line and act on the one size.
- **Each list keeps its own line of controls**, as decided: the zoom is in the line of every list, and the log's level, date and two file buttons, and the history's zero usage button, are in the line of their own list. The cost control has no such line, as today.
- **`app.js`.** The state of a list becomes an object for each view, `{ key, rows, mark, hasHeader, loading, lines, note, error }`, and the functions that read and draw a list take the view they are for: `loadPanel(name)`, `showPanel(name, shown)`, `refreshPanel()` for every view that is open. `openPanel` becomes the list of the open views, in the order of the icons, and the settings a state beside it: `openViews` and `settingsOpen`, with `returnTo` the list of views the settings replaced. Every reading of `openPanel` is gone through: most ask "is this view open", some "is any list open", some "are the settings open".
- **The switch.** `severalViews` comes with the status, in the `show` block, like the other things the row is drawn by. Off, `togglePanel` closes whatever is open before it opens a view, and the page behaves as today, which the whole of today's tests must still say. On, it opens or closes the one view.
- **`contentSize`.** With one view open it reports what it reports today. With several: the width the widest needs; the heights of the views, each as it has alone; and what may be dragged, the height when a list is among them and the width when the log or the error log is. The panel field of the report names them all, `log+history`, in the order of the icons. `docs/api.md`, Window host contract.
- **The heights.** Each list has the height it is opened at, ten rows, as a figure of its own; the host gives the remembered one to the history and the log and tells the page nothing, as today. So with several lists the page lays them out by the heights it asked for and gives what the window has more or less to the lowest list. This is the one part that needs the host as well, and it is finished in phase 106; until then the page is right for one view and for several at the heights it asked for.
- **The settings.** They replace all the views and give back the list, less the views whose icons the saved settings switch off (phase 102's rule, for each view).
- **Switched off while several are shown**: the first in the order of the icons stays.
- **Tests.** Every test of `app.test.cjs` that opens a view runs with the switch off and must pass unchanged, but for the ids that moved. New, with the switch on: an icon opens its view and leaves the others; a blue icon closes its own; the order is the icons' whatever the order of the clicks; each view keeps up by itself and has its own note and red line; the zoom in any line changes all; the settings replace all and give back all, less those switched off; the report of the size for each combination; switched off with several shown. `layout.test.cjs`: the four sections and their order. Phase 103's test holds for each list.
- **Two commits, as decided.** This phase touches most of `app.js`. First, `Phase 105 built, first part: a box and a state for each view (0.80)`: the four sections, the state for each view and the functions that take a view, with the switch always off, so that the page does what it does today and every test passes as it is, but for the ids that moved; the window tests are run on it. Then, `Phase 105 built: several views at once, in the page (0.80)`: the switch, the stacking, the report of the size and the giving back of several.

### 106. Several views at once, in the host and at the start

**Status: built (version 0.80); seen in the real window on Windows by a window test of its own, not looked at by a person.** As built: `Settings.severalViews` (key `severalViews`, off, optional in a `POST`), in `show` of the status, and the checkbox `Several views at once` last in the group Window. **The heights went another way than planned below:** the page lays the lists out, and a list that has another view below it must have its height before the window has one, so the status now carries `heights` (`history`, `log`, as the host remembers them), and the page gives such a list the larger of that and its ten rows. The report with several views ends with the height the lowest list has alone, row included; `UsageApp.resize` keeps what is above it as the page asked (`RememberedHeights.above`), gives the lowest list its remembered height when it is the history or the log, stores what the person drags it to less what is above, and lets the window be dragged no lower than that and a row. `WindowFit` takes the names joined by `+`, each once, in the order of the icons, and the fifth field; `Size.panels()`, `several()`, `lowestList()`; the title bar when any view but the cost control is named. `SettingsStore.loadLast` reads `lastPanel` as names with commas, leaves out what it does not know, puts them in the order of the icons, and `settings` alone; `LastWindow.shows` tells the host that the restore is done also when the setting is off and only the first came back; `window.restoreView` waits for the status before it opens more than one. **The real-window test is `SeveralViewsWindowTest`** (with `SeveralViewsProbe`, two starts in one directory, about a minute) and not `TitleBarWindowTest`, so that the long probe was not made longer: the cost control and the history together, the history above; the title bar and the height to drag; dragged 150 taller, the history takes it, the cost control keeps its 46, and 150 more is remembered for the history; the log added, the window 1113 wide with the width free; the settings and back to all three; closed one by one, the title bar gone with the cost control alone; the history again at its dragged height; `history,cost` stored, and both back at the second start at that height; switched off there, the history stays; an icon then closes it. Checked: the frontend suite (446 tests), `./gradlew test` (986), and the three window tests; of the two older ones, run once more after the machine had slept through the first run, the short probe of the title bar ended once with a crash of the toolkit as it stopped, after every check had passed, and passed three times after. Requirements: Several views at once; Remembered heights; The window is restored at start; Settings (The title bar).

- **`WindowFit`.** The panel field takes several names joined by `+`, each one of the four views, in the order of the icons and none twice; `Size.panels()` gives the list. `keepsTitleBar()` is true when any of them is a list or the settings; `widthIsThePersons()` follows the flag as before.
- **`UsageApp`.** `resize` and `applySize` go by the flag, which the page works out, so they change little. What changes is the height: with several views the content is the row and the heights of the views, and the remembered height of a list is given to it only when it is the lowest. `RememberedHeights` is asked for the lowest list, and a dragged height is stored for it when it is the history or the log, as today for the one panel. The height the window is given is the page's own with the lowest list's remembered height in the place of its ten rows, and never more than `WindowFit.MAX`, where the lowest list takes what is left.
- **The page has to know the height the lowest list was given**, since it lays the lists out: the window is taller than it asked for, and the list that is last takes the difference, as the one list does today (`flex: 1`). So the page needs to be told nothing; the lists above have fixed heights and the last one is elastic.
- **The window restored at start.** `SettingsStore.LastWindow.panel` holds the names with commas (`history,cost`); a name that is not known is left out and the others are used; `UsageApp` hands the list to `window.restoreView`, which opens them all when `severalViews` is on and the first when it is off. `currentView()` says the list.
- **The settings.** `Settings.severalViews` (key `severalViews`, default off, optional in a `POST` like the newer flags), in the `show` block of the status. `index.html`: the checkbox in `Window`, with its tooltip.
- **Tests.** `WindowFitTest`: the names joined, an unknown one, one twice, the wrong order. `RememberedHeightsTest`: which list the remembered height is for. `SettingsStoreTest`: the list in `lastPanel`, and a file of 0.79 with one name. `ApiTest` and `StatusDisplayTest`: the setting. `TitleBarWindowTest`, the real window, with the switch on: the cost control and the history together, the size, the title bar, that dragging the height changes the history's, the log added and the width free; closed one by one, the window follows; and restored at a second start.
- **By eye.** The stacking with three and four views on a small screen, where the lowest list is squeezed, and the look of two lines of controls one under the other.

**By eye, when 100 to 106 are built**, on Windows and on macOS, on both themes: what each phase lists; and the pictures of the notes once more, each against the window: the typeface of the lists, the history with the zero usage lines hidden, the room in the cost control, the placeholder, the four words, the bold note, the icons of the lists, the tooltips of the titles, a link in the log, About with its logo at the left, and a view whose icon was unchecked.

### 107 to 113. Version 0.81: the third round of notes

**Status: built, all seven, as planned; what each lists "by eye" is still to be looked at, on Windows and on macOS.** The requirements are written (every rule marked "since version 0.81"); these seven phases build
them, in this order, and `AppInfo.VERSION` becomes `0.81` with the first. Each is committed by itself with its tests green, as
`Phase 1xx built: <what> (0.81)`. The small ones come first; 111 changes how the settings stand among the views, page and host;
112 and 113 are the folders, the backend first and the page after, and are the bulk of the work.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 107 | The small changes of the page: the line between the views, the funnel, the cost control tighter and in blocks of 10 %, the logo of About in the middle, room before the scrollbar | `index.html`, `app.css`, `app.js` |
| 108 | Cascadia Mono comes with the program | `web/fonts`, `app.css`, `LocalWebServer` |
| 109 | The funnel hides the failed lines too | `HistoryReader`, `ApiHandler`, `view.js`, `index.html` |
| 110 | The new order of the icons: cost control, history, log, error log | `index.html`, `app.js`, `WindowFit`, `SettingsStore` |
| 111 | The settings beside the other views | `app.js`, `WindowFit`, `UsageApp`, `SettingsStore` |
| 112 | The folders of the log and of the history, in the backend: the settings, the switch and the move | `Settings`, `SettingsStore`, `AppFiles`, `Logging`, `ApiHandler`, a new `FileFolders` |
| 113 | The folders in the window: the group `Files` and the folder picker | `index.html`, `app.js`, `UsageApp`, `Desk` |

`docs/api.md` is brought level in the phase that changes an endpoint or the host contract (109, 110, 111, 112, 113). The three
window tests that run only when asked for, `PanelWindowTest`, `TitleBarWindowTest` and `SeveralViewsWindowTest`, are run in
every phase that changes what the page or the window does; a run during which the machine sleeps does not count and is run again.

**Decided before building**, in three rounds of questions: the line gray on the light theme and dark gray on the dark one, under
the row and between the views; the views in the new order of the icons, the checkboxes of `Main view` too; the funnel, which hides
the failed lines with the zero usage lines (either it or the checkbox `Failed lines` hides one); bars in blocks of 10 % with the
fill exact, a bar over its limit full as before; the cost control at 11 pixels, rows 1 pixel apart; the logo in the middle of
everything at its right; 6 pixels before the scrollbar; Cascadia Mono shipped; the settings beside the views at the top, `Save`
and `Cancel` closing them alone, restored with the views, and alone as before with the switch off; a folder for the log and one
for the history, chosen in `General` with the system's picker, moved on `Save` and used at once, a folder that cannot be written
refused, the history of invented readings with the history. The note "when deselecting" is not built: its author found it works.

**Decided about the building itself**: one version, 0.81, a commit for each phase, no push until it is asked for; the notes and the
pictures of this round are committed already, by their author, and the first commit has the requirements and this plan.
Confirmed in a last round: the font from the official release, not copied from Windows; the checkbox `Zero usage lines` keeps its
text, its tooltip saying that the failed lines go with them; the line `#8c959f` / `#484f58`; the folders as two commits, 112 and 113.

### 107. The small changes of the page

**Status: built (version 0.81); seen in the real window on Windows by the three window tests, not looked at by a person.** As
built, two things otherwise than below: **the line is the top border of each view** (`.view`, `.panel > .cost`, `.panel > .settings`),
inside its box, so that no size the page reports and no remembered height changes, and the requirements say so; and **the 6 pixels
are a margin of the last cell** of a row of the history and of the error log, not more padding of the list, since the padding of a
box that scrolls is not kept at the end of its lines in every WebKit, which is why the `s` of `Δ time` stood half under the scrollbar on macOS; the copy the table is measured
on has the margin too, so the window is wider by it without the page adding anything. The probe of `TitleBarWindowTest` now asks for
the logo in the middle of the text and the last column 6 pixels from the edge. Requirements: A line between the row and the views; A button in the history that hides and shows the zero
usage lines (the icon); The cost control panel: two bars; The About view; Details of the behaviour (The panels, exactly).

- **The line.** `app.css`: a token `--view-line` on `:root`, a middle gray on the light theme (`#8c959f`) and a dark gray on the
  dark one (`#484f58`), and `#panel > :not([hidden]) ~ :not([hidden])` with `border-top: 1px solid var(--view-line)`; the line
  under the row is the top border of the panel while it is shown. The heights the page reports grow by a pixel for each line:
  `severalSize` and the one-view sizes add them, so that no list loses a pixel to them, and the remembered heights stay those of
  the lists.
- **The funnel.** `index.html`: the `svg` of `zero-lines` becomes a funnel outline (`M2 3h12l-4.5 5.5v4.5l-3 1.5v-6z`), the
  tooltips stay as they are until phase 109.
- **The cost control.** `app.css`: `.cost` 11 pixels on 1.2, `padding: 2px 8px`; `.cost-rows` `gap: 1px 6px`; `.cost-bar` 12
  pixels. The blocks: a `repeating-linear-gradient` over the bar, drawn above the fill (`::after`), transparent for 10 % less a
  pixel and the window's background for one pixel, nine gaps and none at the ends; the fill is as it is, so it stays exact. `COST_GAP`
  of `app.js` and the measured height follow by themselves; the probe's `cost-view:…:46` becomes the new height.
- **About.** `app.css`: the two columns `align-items: center` where they had `start`.
- **Room before the scrollbar.** `app.css`: the lists' right padding of their lines grows by 6 pixels, and `app.js` adds 6 to the
  width it asks for each list (`scrollbarWidth` stays what is measured).
- `AppInfo.VERSION = "0.81"`.
- **Tests.** `layout.test.cjs`: the rule of the line and its two colours; the cost sizes and the gradient of ten blocks; About's
  `align-items`; the 6 pixels. `app.test.cjs`: the size with one and with several views is taller by the lines, and wider by 6.
  The three window tests; their numbers that follow from these sizes are brought level.
- **By eye.** The line on both themes, the blocks at 11 %, 55 % and over 100 %, and the funnel against the zoom icons.

### 108. Cascadia Mono comes with the program

**Status: built (version 0.81); seen in the real window on Windows, not on macOS.** As built, otherwise than below: **the file is
the static `CascadiaMono-Regular.ttf`** (576 KB) of release `v2407.24`, not the `.woff2`, since whether the WebKit of JavaFX reads
WOFF2 is not known and TrueType it does; the release has no licence file, so `CascadiaMono-LICENSE.txt` is the `LICENSE` of the
repository at that tag. `LocalWebServer` serves `ttf` as `font/ttf` and `txt` as text. No wait for `document.fonts.ready` was
needed: the page measures the table again when it is seen to lack room, as for any typeface that comes late. In the real window:
`PROBE list-typefaces=has:[Cascadia Mono Bundled|Cascadia Mono|Consolas] used:Cascadia Mono Bundled`. One run of
`TitleBarWindowTest` failed on the clipboard of the About view, which another program held; it passed run again. Requirements: A sturdier typeface for the lists.

- **The file.** `CascadiaMono-Regular.woff2` from the official release of Microsoft's `cascadia-code` on GitHub (the static
  regular weight, about 300 KB; the variable `.ttf` of Windows is larger and not needed), and its `LICENSE` (SIL Open Font
  License 1.1) beside it, in `src/main/resources/web/fonts/`. The version of the release is said in the commit.
- **The page.** `app.css`: an `@font-face` `"Cascadia Mono Bundled"` from `fonts/CascadiaMono-Regular.woff2`, weight 400, and
  `--list-font` begins with it. The bold of the table's header and of the notes is the browser's own from the regular.
- **The server.** `LocalWebServer`: `woff2` is `font/woff2` in the types it serves; nothing else is new, the file is a resource
  like the others.
- **The measuring.** The copy the history's table is measured on uses `--list-font`; the page waits for `document.fonts.ready`
  before the first measure, so that a table measured before the face is loaded is not too narrow.
- **Tests.** `layout.test.cjs`: the `@font-face` and the first name. A Java test: `/fonts/CascadiaMono-Regular.woff2` is served
  with its type, and the licence is among the resources. `PanelWindowTest`: the list is drawn in the bundled face
  (`used:Cascadia Mono Bundled`).
- **By eye.** On macOS above all: the lists, the cost control and the history, against the same views on Windows, in Cascadia Mono.

### 109. The funnel hides the failed lines too

**Status: built (version 0.81).** As built: the note already counted both kinds; what changed is one line of `ApiHandler.history`, the failed lines shown only when the zero usage lines are too (by the setting or the query), the two tooltips of the button, and the tooltip of the checkbox. Three tests of `ApiTest` that held the failed lines shown with the zero usage lines hidden now hold the other way. Requirements: A button in the history that hides and shows the zero usage lines; Hiding lines.

- **The backend.** `HistoryReader`: a failed line is left out when `historyFailedLines` is off **or** `historyZeroLines` is off;
  the count of what is left out is kept by kind, zero usage and failed. `ApiHandler`: the note of `GET /api/history` says both
  kinds, `Showing 320 of 835 lines: 499 zero usage and 16 failed hidden.`, and a kind with none is not named.
- **The page.** `app.js` (`labelZeroLines`): the tooltips `Show the zero usage lines and the failed lines` and `Hide …`.
  `index.html`: the checkbox `Zero usage lines` keeps its text, and its tooltip says that the failed lines go with them.
- **Tests.** `HistoryReaderTest`: the four ways of the two switches; the counts. `ApiTest`: the note. `app.test.cjs`: the tooltips.

### 110. The new order of the icons

**Status: built (version 0.81); seen in the real window on Windows by the three window tests.** As built: as below, and one fault of 0.80 found by the new order: a list with another view below it was given its height alone less the row's height only, while as the lowest it loses the message lines, the borders and the room below the views too, so the history above the log was ten pixels taller than as the lowest. `app.js` has `chromeHeight()` for what of the window is no view's, measured from the layout, and both the fixed lists and the total use it; in the real window the history is 446 both above the log and as the lowest. `SeveralViewsWindowTest` now opens the cost control above the history, adds the log as the lowest list, and holds that the history keeps its dragged height above it; with the switch off at the second start the cost control stays, and the history icon takes its place. Requirements: The thirteen elements and their names; A checkbox for each element; Several views at once; The
window is restored at start.

- **The page.** `index.html`: the four buttons of the row in the order cost, history, log, error log, and the four checkboxes of
  `Main view` in the same order; the boxes of the views in the panel in that order. `app.js`: `VIEWS` and `LISTS` in the new
  order, from which the stacking, `currentView()` and the report follow.
- **The host.** `WindowFit`: its `VIEWS` in the new order, which is what `inIconOrder` holds the report to. `SettingsStore`: its
  `VIEWS` in the new order, so that a `lastPanel` of 0.80, `history,cost`, is read as `cost,history`. `RememberedHeights` and
  the lowest list need nothing: the lowest is the last list named.
- **Tests.** The page's tests that name the order (the boxes, the icons, the reports `history+cost` that become `cost+history`);
  `WindowFitTest` and `SettingsStoreTest` with the new order and a file of 0.80; `SeveralViewsWindowTest`, whose views are now
  the cost control above the history: the drag still goes to the history, the lowest list.
- **By eye.** The row, and the `Main view` tab.

### 111. The settings beside the other views

**Status: built (version 0.81); seen in the real window on Windows by `SeveralViewsWindowTest`.** As built: `settingsBeside` in `app.js`, set when the settings are opened with several views at once on and kept until they are left; `leaveForm(name, saved, drop)` closes them alone then, and after a save reads the views that stayed afresh, as views given back are; `keepSwitchedOn` in the place of `dropWhatIsSwitchedOff`; `showViews` loads the form only when the settings are opened, not when a view beside them comes or goes, and keeps the preview; `fillsWindow()` decides `open` and `fit`; `.panel > .settings` has `order: -1` and `flex: none`. `WindowFit` takes `settings` first in a joined list and nowhere else; `SettingsStore` reads `settings,cost,history`. In the real window: the settings at the top (y 37) above the cost control and the history, the history 447 as the lowest list, `settings,cost,history` stored, all three back at the second start at the same height, and with the switch unchecked and saved there, the cost control alone. Requirements: Several views at once; Settings; The panel buttons while the settings are shown; The window is
restored at start.

- **The page.** `app.js`: with `several()` on, the settings are a view among `openViews` (`settings` first, the others in the
  order of the icons), and `settingsOpen` follows from that; the gear opens and closes them like an icon; `Save` and `Cancel` take
  `settings` out of the list and leave the others; an icon pressed while they are shown opens or closes its view and leaves the
  settings as they are, unsaved; `returnTo` is used only with the switch off. `layoutViews` gives the settings their content's
  height, never the lowest list's part. Off, everything stays as built in 0.80.
- **The report.** `severalSize`: the settings count with their least width and their height; the name list begins with
  `settings`; the flag is that of the views, and `4` when the settings on `About` are with no list (the width free); with a list,
  `2` covers About's wider width. `currentView()` names them first with the tab after the comma: `settings+cost+history,general`.
- **The host.** `WindowFit`: `settings` may stand first in a joined list; `lowestList` passes it over. `UsageApp` needs nothing new
  for the height, since the settings are never the lowest list. `SettingsStore`: `lastPanel` `settings,cost,history` is kept
  with its `lastTab` (today `settings` with a view is read as `settings` alone); `LastWindow.shows` takes it. `window.restoreView`
  opens them together, on the tab, with the switch on, and the settings alone with it off.
- **The settings' own state.** What the form holds while it is open with other views is kept when a view beside it opens or
  closes: `showViews` must not reload the form for a change of the other views.
- **Tests.** `app.test.cjs`: the gear with views open, `Save` and `Cancel` leaving them, an icon while the settings are shown, the
  report and `currentView`, the switch off as before, the switch switched off by saving with views open. `WindowFitTest`,
  `SettingsStoreTest`. `SeveralViewsWindowTest`: the settings with the cost control and the history, their order, `Save`
  closing them alone, and the restore of all three at the second start.
- **By eye.** The settings above a list, with a dragged height; `About` beside the log.

### 112. The folders of the log and of the history, in the backend

**Status: built (version 0.81).** As built: `Settings.logFolder` and `historyFolder`, texts, empty for the program's folder, saved after `severalViews`; `FileFolders` holds the folders in use, made by `AppRuntime.start` from the settings (`atStart`, which passes over a folder that cannot be used, with a warning) and handed to `ApiHandler` in the place of `AppFiles`, with the same names (`log()`, `history()`, `fakeHistory()`, `settings()`); `Main` begins the log in the folder the settings name. `UsageHistory.moveTo`, `Logging.moveTo` (inside `Log.whileNothingIsWritten`, like a reset) and `Logging.moveFiles`, which puts back what it moved when a move fails; `AppRuntime.moveFolders` checks both folders first, moves the log and then the history, and moves the log back when the history cannot be moved. `ApiHandler` takes the two optional in a `POST`, refuses a folder that is no full path, and moves before it saves. Tests: `FileFoldersTest`, three of `ApiTest`, one of `SettingsStoreTest`. Requirements: The folders of the log and of the usage history; the settings file.

- **The settings.** `Settings`: `logFolder` and `historyFolder`, texts, empty for the program's folder; `SettingsStore`: the keys
  after `severalViews`, a value that is not text read as empty. `ApiHandler`: both in `GET /api/settings` and optional in
  `POST`.
- **The files in use.** A new `FileFolders` holds where the log, the history and the fake history are now, and is what every
  reader and writer asks: `AppFiles` gives the program's folder and the names, `FileFolders` the folder in force for each.
  `AppRuntime` makes it at the start from the settings, falling back to the program's folder, with a warning, for a folder that
  is not there or cannot be written. Every place that holds a path of its own today (`Logging`, `LogTail`, the writer and the
  readers of the history, `LatestChangeCache`, `CostPanel`, the About paths, the log's links) takes it from there.
- **The switch.** On a `POST` that changes a folder: the folder is checked (there, a folder, a file can be written in it and
  deleted again), then the files are moved: the log through `Logging.moveTo(folder)`, which closes the writer, moves the log and
  its old logs and opens the moved one again; the history and the fake history with `Files.move`. A file of the same name in the
  new folder is not overwritten: it is used, the old one stays, and the log says so. A move that fails puts back what was moved and
  the `POST` is refused with `The folder cannot be written: <reason>`, the words of The words for what went wrong. The history's
  stamp changes with the file, so the page reads it again by itself.
- **The log.** At the info level: the folder in use for each, and the full path of every file moved.
- **Tests.** `SettingsStoreTest`: the keys, their order, empty, not text. A `FileFoldersTest`: a start with a folder that is
  gone; a move of each kind; a file of the same name already there; a folder that cannot be written; a move that fails half way and
  is put back. `ApiTest`: the `POST`, the refusal, the history and the log read from the new folder after it. `LoggingTest`:
  `moveTo` keeps logging to the moved file, and the old logs go with it.
- **`docs/api.md`**: the two settings and the refusal.

### 113. The folders in the window

**Status: built (version 0.81); the group seen in the real window by the three window tests (the settings neither wider nor wrapped by it); the folder picker itself not seen, since no test can drive a window of the system.** As built: `Desk.chooseFolder`, which `Desk.SYSTEM` does with JavaFX's `DirectoryChooser` over the program's window, on the toolkit's thread, waiting up to an hour for the person; `POST /api/folder`; `programFolder` in `GET /api/settings`, which the view shows in gray for a folder that is the default. `app.js`: `folderChoice`, `showFolder`, `chooseFolder`, `defaultFolder`; both folders are sent with every save, and a refusal `The folder cannot be written` is said under the group (`folder-error`), not under one line: the backend does not say which of the two it was, and the requirements now say so. Requirements: The folders of the log and of the usage history.

- **The folder picker.** The backend cannot show a window; the host can. `Desk` gets `chooseFolder(Path from)`, which `UsageApp`
  implements with JavaFX's `DirectoryChooser` on its own stage, on the FX thread, and the tests with a fake. `POST /api/folder`
  `{start}` answers `{folder}` or `{folder: null}` when the person cancelled; nothing is saved by it.
- **The page.** `index.html`: the group `Files` after `Window` in `General`, a line for each folder: its label, the path as
  text (not an input), `Choose…` and `Default`, with the tooltips of the requirements. `app.js`: `Choose…` asks the backend and
  puts the folder in the form; `Default` empties it; `Save` sends both with the rest; a refusal is the red line under the line of
  that folder. The form's least width takes a long path by wrapping it, never by widening the window.
- **About** shows the paths in force after a `Save`, as it reads them from the backend.
- **Tests.** `app.test.cjs`: the group, `Choose…` with a folder and cancelled, `Default`, the save and the refusal. `ApiTest`: `POST
  /api/folder` with the fake `Desk`. In the real window, a probe step that opens the picker cannot be driven: it is checked by hand.
- **By eye.** The picker on Windows; on macOS not checked here, as the requirements say.

**By eye, when 107 to 113 are built**, on Windows and on macOS, on both themes: what each phase lists, and the pictures of the
notes once more (the line under the row, the typeface, the scrollbar), each against the window.

### 114 to 120. Version 0.82: the fourth round of notes

**Status: built, all seven, as planned; what each lists "by eye" is still to be looked at, on Windows and on macOS; the weekend rule of phase 117 is to be confirmed.** The requirements are written (every rule marked "since version 0.82"); these seven phases build
them, in this order, and `AppInfo.VERSION` becomes `0.82` with the first. Each is committed by itself with its tests green, as
`Phase 1xx built: <what> (0.82)`. The first two are small and stand alone; 116 to 119 are one feature, the limits and what is
compared with them, in four steps from the settings to the page; 120 adds to the history file.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 114 | The small changes: the icons of a list's line at the right, bars of twenty blocks in green and light green, red over the limit with no warning sign, the cost control's own currency symbol | `app.css`, `index.html`, `CostPanel`, `Settings` |
| 115 | Views that do not fit on the screen: the lists share the height | `UsageApp`, `WindowFit`, `app.js` |
| 116 | The limit counted and the working days, as settings | `Settings`, `CostControl`, `SettingsStore`, `ApiHandler`, the Cost control tab |
| 117 | What is compared: the day's projection, the month's pace, the states | a new `Spending`, `CostPanel`, `StatusDisplay` |
| 118 | The row: the status circle, the figures of the day, red over the limit | `index.html`, `app.js`, `StatusDisplay` |
| 119 | The cost control's colours: amber and red | `CostPanel`, `app.js`, `app.css` |
| 120 | The limits in the history: three columns in the file and in the table | `UsageHistory`, `HistoryReader`, `ApiHandler`, `app.js` |

`docs/api.md` is brought level in the phase that changes an endpoint or the host contract (114 to 120 all do). The three window
tests are run in every phase that changes what the page or the window does.

**Decided before building**, in one round of questions: the choice `Server limit` / `Monthly limit` in the Cost control tab,
the monthly field disabled with the server's, which then counts everywhere; the row red over the limit it shows; three columns
per reading in the history, off by default; the lists sharing the height of the screen, five lines each at least; the working
day from the first rise of the day, eight hours by default; the month's pace by working days begun; a typed number of working
days for its month only; the day's figures after the month's, off by default; a filled status dot, shown by default, gray with
no limit; twenty blocks; the icons of a list's line at the right; the currency symbol of the cost control in its tab, off by
default; every block red over the limit. **Confirmed in a last round**: the default `Monthly limit`, with the
server's counted while none is typed; the amber, the titles `month`, `day`, `by`, the texts of the tooltips and the keys as the
requirements have them; the header of a history file of seven columns rewritten once; one version, a commit per phase, no push,
and the notes and pictures (`new-requirements.md`, `tmp.md` and the pictures) in the first commit.

### 114. The small changes

**Status: built (version 0.82); seen in the real window on Windows by the three window tests.** As built: the cost control's symbol is a member of `CostControl` (`currency`, with a constructor of three for the rest), not of `Settings`, so that the settings of cost control are together and the record of the settings does not grow; key `costCurrency` after `costDailyAutomatic`. The icons go right by one rule, `.log-controls > .zoom:not([id]) { margin-left: auto }`: the zoom has no id, and what follows it in each line (the funnel, the log's two buttons) goes with it; no markup moved. The probe's check of the copied path of About now asks the clipboard again for up to four seconds: it failed twice on a clipboard another program held. Requirements: A zoom for the lists (the icons at the right); The cost control panel (twenty blocks, the
colours, over the limit, the currency symbol); Cost control (`Currency symbol`).

- **The icons at the right.** `app.css`: in `.log-controls` the span of the zoom gets `margin-left: auto`, and the funnel and the
  log's two buttons are put in the same right-hand group in `index.html` (one `span.icons` per list, the zoom first); the log's
  `Level` and `Date` stay at the left. The ids stay.
- **Twenty blocks.** `app.css`: the gradient of `.cost-bar::after` at `5% 100%`; the track `color-mix(in srgb, var(--ok) 25%,
  transparent)` where it was the muted gray; a bar with no limit keeps the gray (`.cost-bar.none`).
- **Red over the limit, no sign.** `CostPanel.Row.over` already says it: `.cost-bar.over .cost-fill` is `var(--bad)` and its track
  the light red; the warning sign, its cell and its tooltip go from `app.js` and `index.html`, and the column of the figures loses
  its room.
- **The currency symbol of the cost control.** `Settings.costCurrency` (key `costCurrency`, off), optional in a `POST`; `CostPanel.build`
  is given it in the place of the row's `showCurrency`; the checkbox `Currency symbol` in the Cost control tab, with its tooltip.
- `AppInfo.VERSION = "0.82"`.
- **Tests.** `layout.test.cjs`: the right-hand group, the twenty blocks, the light green, no sign. `app.test.cjs`: an over bar has the
  class, no sign is drawn. `CostPanelTest`/`ApiTest`: the symbol by the new setting. `SettingsStoreTest`: the key. The window tests,
  whose cost control and list lines change.

### 115. Views that do not fit on the screen

**Status: built (version 0.82); seen in the real window on Windows by `SeveralViewsWindowTest`.** As built: `UsageApp.screenRoom()` (the visual height of the screen the window is on, less the frame), told to the page at each tick it changes and kept to by `resize`; `window.screenRoom`, `listLeast()` (seven lines and 24 pixels: five lines, the header and the note, and the controls), `sharedHeight()`; `layoutViews` and `severalSize` use it, and while the lists share the report has no fifth field, which the host takes for "remember nothing". `WindowFit` did not need the room: `UsageApp` clamps. In the real window, on a screen of 1,392: the four views fit (1,114), the error log 296 tall; told a room of 600, the three lists are 174 each and the window 598; the history is 446 again once two are closed, its remembered height untouched. Requirements: Views that do not fit on the screen; Several views at once.

- **The host knows the screen**, the page does not. `UsageApp` tells the page the height its window may have, the visual bounds of
  the screen it is on less the frame, through a function of the page, `window.screenRoom(pixels)`, when it changes (at the start,
  when the window moves to another screen). The host contract gets it.
- **The page shares the height.** `layoutViews` and `severalSize`: when the row, the views that are no list and the lists at their
  heights alone need more than the room, each list is given an equal part of what the others leave, at least five of its lines;
  the report asks for no more than the room. The lowest list still takes a dragged height. `aloneHeight` is unchanged, so the
  remembered heights are not touched.
- **The host** keeps a size within the room as well (`WindowFit` takes a room besides `MAX`), so a window is never taller than
  its screen.
- **Tests.** `app.test.cjs`: four views in a small room, each list its part, none under five lines; one view as before.
  `WindowFitTest`: the room. `SeveralViewsWindowTest`: all four views open, the error log seen, the window within the screen.

### 116. The limit counted and the working days, as settings

**Status: built (version 0.82).** As built: the four are members of `CostControl` (`source`, `workdayHours`, `workdays`, `workdaysMonth`; its compact constructor makes what cannot be the default), with `withCounting`, `serverCounted`, `workdaysTyped`, `workdays(month)`, `monthlyCounted(server)` and `dailyIn(month, server)`; `dailyIn(month)` is that with no server limit. `CostPanel` takes the server's limit from the newest reading (`serverLimit(row)`); `StatusDisplay.counted` gives the row the limit counted and works the percentage out when it is not the server's, and the budget's tooltip says `Monthly limit of cost control` then. `GET /api/settings` adds `serverLimit` and `weekdays`, and `GET /api/cost/daily` takes `workdays`. The page: `syncCounting`, `countingChanged`; a test found that the field of the working days lost what was typed when it was synced, and the page now keeps what the field held while it was not showing the weekdays. Two tests that held "no limit" with a server limit there now hold the server's limit counted. Requirements: Cost control; Limits, projections and the status of spending (the limit counted).

- **The settings.** `CostControl` gets `source` (`server` / `monthly`, default `monthly`), `workdayHours` (8), `workdays` and
  `workdaysMonth`; `CostControl.workdays(YearMonth)` gives the typed number for its month and Monday to Friday otherwise, and
  `dailyFor` divides by it. `SettingsStore` keys `costLimitSource`, `costWorkdayHours`, `costWorkdays`, `costWorkdaysMonth`;
  `ApiHandler` takes them, each optional in a `POST`, with their ranges.
- **The limit counted** is a function of the settings and of the newest reading: `CostControl.monthlyCounted(serverLimit)` – the
  server's with `server`, the typed one with `monthly`, and the server's when none is typed. `CostPanel`, the daily limit worked
  out and `GET /api/cost/daily` use it. The row's `Budget` and percentage: `StatusDisplay` takes the limit counted.
- **The Cost control tab.** `index.html`: `Limit counted` as two radio buttons, `Hours of a working day` (a field, `h`), `Working days`
  with `Automatic`, `Currency symbol` (from 114); `app.js`: the monthly field disabled with `Server limit`, and `Working days`
  like the daily limit, with the number worked out shown while `Automatic` is on.
- **Tests.** `CostControlTest`: the days typed for a month and not the next, the limit counted each way. `SettingsStoreTest`,
  `ApiTest`: the keys, the ranges, the daily limit by the days. `app.test.cjs`: the tab, the field disabled, `Automatic`.

### 117. What is compared: the projection, the pace and the states

**Status: built (version 0.82).** As built: `Spending.compute(rows, cost, now)` with `dayStart`, `projection`, `pace`, `circle`, and `Result`/`Part` that write themselves as JSON; it counts today's spend with `CostPanel`'s own `baseline` and `spentSince`, opened to the package. The status has it at the top as `spending` (not under `display`), worked out in `ApiHandler.spending()` when the history, the settings of cost control or the minute is another, and `null` when the history cannot be read. **On a Saturday or a Sunday** there is no projection, but the day is still red over the daily limit and green under it: the daily limit the cost control shows is not taken away on a weekend, which the requirements' "no daily limit" would have done to the bar as well; to be confirmed. `SpendingTest`, seven, by the clock given; one more of `ApiTest`. Requirements: Limits, projections and the status of spending.

- **A new `Spending`** (in `org.example`, no window, no clock of its own: it is given the readings of the history in use, the
  settings and the time) works out, for the day: the spend, the limit, the start of the working day (the first rise of today),
  the projection, and the state; for the month: the amount used, the limit counted, the pace, and the state; and the circle's
  state and its tooltip, in the words of the requirements. `CostPanel` uses it for its rows, so that both say the same.
- **The status** carries it: `display.spending` with `state` (`green`, `amber`, `red`, `gray`), `tooltip`, and `day` and `month` each
  with `state`, `used`, `limit`, `percent` as finished texts. Read with every poll from the history, as `GET /api/cost` is.
- **Tests.** `SpendingTest`, by the clock given: no rise yet, the first 15 minutes, a projection over and under, the day over, a
  weekend, the working day over; the month on and ahead of pace, the days typed, over; the circle of each pair, gray with no
  limits, one limit missing; the tooltips word for word. `ApiTest`: `display.spending`.

### 118. The row: the status circle, the figures of the day, red

**Status: built (version 0.82); seen in the real window on Windows by the three window tests.** As built: the four settings in `Settings` (`withDay`), `SettingsStore`, `ApiHandler` and `StatusDisplay.Show`; `renderSpending` in `app.js` draws the dot, the class `over` on the amount used and the percentage, and the three of the day, gray boxes with `No daily limit is set` when there is none; `--warn` (the amber) on `:root`; the four checkboxes join `MAIN_FLAGS`, so that the button of Main view switches eighteen. Two window tests changed with the row: the title bar test counts ten checkboxes on by default and eighteen in all, and the panel test switches the dot off for its step that pushes the history's lines, since the row with the dot is wider than the table and the list would not lack room. Requirements: The elements of the row; One green, and red over the limit; Limits, projections and the
status of spending (the circle).

- **The settings.** `showStatus` (on), `showDayPercentage`, `showDayUsed`, `showDayLimit` (off), in `Settings`, `SettingsStore`,
  `ApiHandler` (optional in a `POST`), `StatusDisplay.Show`.
- **The page.** `index.html`: the dot before the time, and after the budget a thin line and the three of the day, with `of`;
  their checkboxes in `Main view` in the order of the row, with the tooltips. `app.js`: drawn from `display.spending`; the class
  `over` (red) on the amount used and the percentage, and on today's spend and percentage; gray boxes with `No daily limit is
  set` where there is no daily limit; the dot's colour by its state, its tooltip the text. `app.css`: the dot, 10 pixels, the four
  colours; `.over` red, winning over the green.
- **The width of the row** is still reserved for what is switched on, so the row does not change width when a figure turns red.
- **Tests.** `app.test.cjs`: each element switched, the order, red and green, the dot's states and tooltip, the gray boxes.
  `layout.test.cjs`: the markup, the order, the colours. `StatusDisplayTest`. The window tests: the row's width with the new
  elements on and off.

### 119. The cost control's colours

**Status: built (version 0.82).** As built: `CostPanel.Row.state`, with the old constructor of six making it from the limit alone (red, gray, green); `Row.with(part)` and `View.with(spending)` make a row amber, and add the line of the part to its tooltip, only when the part is amber and the row neither over nor without a limit; `ApiHandler.cost()` applies `Spending.compute` to what `CostPanel.build` gives. `view.js` passes `state` on, one of the four or green: a page test found it left out, as `view.js` copies the fields it knows. `.cost-bar.amber` and its fill in `--warn`. Requirements: The cost control panel (the colour of the used part).

- `CostPanel.Row` gets `state` from `Spending`; `app.js` puts `green`, `amber` or `red` as a class on each bar; `app.css`: the
  amber `#bf8700` / `#d29922` and its light form, the red and its light form, the light green of 114 for green.
- The tooltip of a bar adds, when amber, what the projection or the pace is: the second line of the circle's tooltip for it.
- **Tests.** `CostPanelTest` and `ApiTest`: the states; `app.test.cjs`: the classes; `layout.test.cjs`: the colours.

### 120. The limits in the history

**Status: built (version 0.82); seen in the real window on Windows by the three window tests.** As built: `UsageHistory.limitsFrom`, a function of the server's limit of the reading, which `AppRuntime` gives both histories; the header of ten, and `upgradeOldFile` takes a file of seven as it took the older ones, its rows getting three empty fields; a header that cannot be rewritten is logged and the row added all the same. `HistoryReader.FIELDS` is ten. The three settings in `Settings` (`withHistoryLimits`), `SettingsStore`, `ApiHandler`; the columns after `limit`; the three checkboxes after `limit` in History view, and in its select-all. Some fifty tests held the rows of seven fields, and were brought to ten by script where they were expectations and left where they wrote an old file. **One fault of phase 115 came up in this phase's window run**: once, at a start whose window was restored, the frame of the window was measured before the stage and its scene agreed (879 pixels), the room of the screen was told the page as 513, and the views were shared into it; `UsageApp` now passes over a frame measured larger than 150 pixels and measures again at the next tick. It passed three times after. Requirements: Usage history (the three columns); The lines of the usage history (`month`, `day`, `by`).

- **The file.** `UsageHistory` writes `monthly_limit`, `daily_limit`, `daily_auto` after `duration_ms`, from the settings at the time
  of the reading (it is given them by `AppRuntime.fetchAndRecord`); the header of a new file has ten columns; a file with the seven
  of before has its header rewritten once, through a temporary file moved into its place, before the first ten-column row (a
  rewrite that fails leaves the file and the row is added all the same; readers take every row by place). `HistoryReader` reads the three when
  they are there, empty when not.
- **The table.** `historyMonthly`, `historyDaily`, `historyDailyBy` (off) in `Settings`, `SettingsStore`, `ApiHandler`; the columns
  `month`, `day`, `by` after `limit`, with their widths and the tooltips of their titles; their checkboxes in `History view`
  after `limit`, and in the select-all of the tab.
- **Tests.** `UsageHistoryTest`: the ten columns, a file of seven whose header is rewritten once and whose rows stay, a rewrite that fails. `HistoryReaderTest`: both kinds of rows. `ApiTest`: the
  columns on and off. `app.test.cjs`/`layout.test.cjs`: the checkboxes and the widths. The window tests, whose history table is
  measured.

**By eye, when 114 to 120 are built**, on Windows and on macOS, on both themes: the bars against the sketch of twenty blocks, the row with
everything on, the dot in each colour with its tooltip, the icons of the lists at the right, and four views on a small screen.

### 121 to 123. Version 0.83: the fifth round of notes

**Status: built, all three, as planned (the pulse of the circle left out, as decided); what each lists "by eye" is still to be looked at.** The requirements are written (every rule marked "since version 0.83"); these three phases build
them, in this order, and `AppInfo.VERSION` becomes `0.83` with the first. Each is committed by itself with its tests green, as
`Phase 12x built: <what> (0.83)`; the first commit has the notes and the pictures, the requirements and this
plan. No push until it is asked for.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 121 | The row: the day first, the icons and the gear at the right edge, `Monthly limit` in the place of `Budget`, no severity | `index.html`, `app.css`, `app.js`, `StatusDisplay` |
| 122 | The amber is a golden yellow | `app.css` |
| 123 | The figures of the cost control in four columns | `CostPanel`, `app.js`, `app.css` |

**Decided before building**, in one round of questions: the circle does not pulse after all (asked once more: a dot that fades in and out has the window repaint all the time); the yellow
`#e3b341` on both themes; the day first in the row only, the cost control keeping Today above Month; the icons and the gear
together at the right edge; `Monthly limit` for the row's limit everywhere but the history's column `limit`; the severity shown
nowhere; four aligned columns for the figures of the cost control; the room between the figures and the icons moves a window
that has no title bar, as the rest of the row does.

### 121. The row: the day first, the icons at the right, `Monthly limit`, no severity

**Status: built (version 0.83); seen in the real window on Windows by the three window tests.** As built: the icons go right by `.tools { margin-left: auto }` and `.top { min-width: 100% }`, the row reaching the window's edge; `contentSize` lets go of that while it measures, but keeps the 300 pixels a row with a message line and nothing open has (a window test found the page asking for 38 pixels there when the first fix let go of everything). The thin line is the month's, `#day:not([hidden]) + #spend:not([hidden])`. The tooltip `Today's spend` became `What was spent today, counted from the last reading before today.`: a layout test wants every tooltip of Main view a sentence of forty characters. `TitleBarWindowTest` now holds the icons at the right edge of the row with the settings open, where it held the row as wide as its five buttons; `PanelProbe` lets go of the least width when it measures the row. One run of the seat-based step lost the hover of a synthetic mouse event, and passed again. Requirements: The elements of the row (the day first, `Monthly limit`); Compact window (the icons at the
right edge, the severity); Limits, projections and the status of spending (the tooltips).

- **The day first.** `index.html`: the `span#day` before `span#spend`, and the thin line on the left of the month's group, not the
  day's (`.day + .spend` gets the line, so that it is there only when both are shown); the four checkboxes of the day before
  `Percentage spent` in `Main view`, and `MAIN_FLAGS` in that order.
- **The icons at the right edge.** `app.css`: `.tools { margin-left: auto }` in the strip, which is a flex box: the icons and the gear
  go to its right end when the window is wider than the row; the strip is as wide as the window while a view is open or a
  message line is wider (it is stretched then already). The width the page asks for is measured with the row at its own width,
  as now, so it does not change.
- **`Monthly limit`.** `index.html`: the text and the tooltip of the checkbox `set-showLimit`. `StatusDisplay`: the percentage's
  tooltip `<p>% of the monthly limit spent` and no severity; the limit's `Monthly limit, in USD`, or `Monthly limit sent by the
  server, in USD` when the server's is counted; `app.js`: the tooltips of the gray boxes, `Share of the monthly limit spent` and
  `Monthly limit`.
- `AppInfo.VERSION = "0.83"`.
- **Tests.** `layout.test.cjs`: the order of the row and of `Main view`, the line, `.tools`. `app.test.cjs`: the gray boxes'
  tooltips. `StatusDisplayTest`, `ApiTest`: the tooltips. The window tests, whose row changes; a probe step that measures the
  icons' place at the right edge with a list open.

### 122. The yellow

**Status: built (version 0.83).** As built: one `--warn: #e3b341` on `:root`, none in the dark theme's block; the tests hold one yellow, and no animation on the dot in any of its colours. Requirements: Limits, projections and the status of spending (amber).

- `app.css`: `--warn: #e3b341` on both themes. Nothing else draws with `--warn` but the dot and the amber bars. No animation: the
  circle stands still.
- **Tests.** `layout.test.cjs`: the yellow on both themes, and no `@keyframes` for the dot.

### 123. The figures of the cost control in four columns

**Status: built (version 0.83); seen in the real window on Windows by `SeveralViewsWindowTest`.** As built: `CostPanel.Row` has `used`, `limit` and `percent` (`withParts`); `view.js` passes them on; `showCost` draws `cost-used`, `cost-of`, `cost-limit` and `cost-percent`, `cost-used` and `cost-rest` over three columns with no limit, and the text in one `cost-text` over four when a backend sends no parts. `costWidth` counts a row of four cells by the columns and their gaps, and a row of fewer by its own cells. In the real window the amounts of the two rows end at one pixel (257) and the limits at another (334). `SeveralViewsWindowTest` compares the history's height with the height the drag gave, not with the height before and 150, since a frame rounds a pixel or two each time. Requirements: The cost control panel (the figures in four columns).

- **The backend.** `CostPanel.Row` gets the parts of its figures, `used`, `limit` and `percent` as finished texts (`limit` and
  `percent` null with no limit), besides `text`, which stays for the tooltip and for an older page; `GET /api/cost` has them.
- **The page.** `.cost-rows` gets three more columns: the grid is the label, the bar, then the amount (right), `of` (centred),
  the limit (right) and `· <p>%` (right); a row with no limit spans its amount and `· no limit set` over the four. `costWidth()`
  measures the four columns.
- **Tests.** `CostPanelTest`: the parts. `app.test.cjs`: the cells, the row with no limit. `layout.test.cjs`: the grid. The window
  tests: `TitleBarWindowTest`'s cost control, the decimal points of the two rows at one place (a probe step).

**By eye, when 121 to 123 are built**: the row with a list open and with the row alone (the icons at the right), the dot in
each colour, the yellow against the light brown of 0.82, the figures of the cost control lined up in their four columns.

### 124 to 126. Version 0.84: the sixth round of notes

**Status: built, all three; the fault of the cost control closed with the settings could not be made to happen, and its steps are to be asked.** The requirements are written (every rule marked as of 0.84); these three phases build them, in
this order, and `AppInfo.VERSION` becomes `0.84` with the first. A commit for each, with its tests green, as `Phase 12x built:
<what> (0.84)`; the first has the notes and the pictures, the requirements and this plan. No push until it is
asked for.

| Phase | What | Where most of it is |
| --- | --- | --- |
| 124 | The fault seen in 0.83: leaving the settings closed the cost control beside them | `app.js`, found by a failing test first |
| 125 | The cost control: its own width, never cut; blocks of 14 pixels; the bright yellow | `app.js`, `app.css` |
| 126 | The row: the colours of the bars on its figures, `Monthly credit used`; the folders say `Program folder` | `app.js`, `app.css`, `StatusDisplay`, `index.html` |

**Decided before building**, in two rounds of questions: the cost control's width its own and the row's; a path only for a folder
that is chosen; blocks of a fixed 14 pixels, more of them in a wider bar; the history not moved (the note was a mistake, the
cost control stays first); the view stays when the settings are left, its icon being checked (a fault, not a rule); `#ffff33` for
the bars and the dot, `#b8a000` for a yellow figure; the amount used and the percentage take the colour, the limits do not; the
window always wide enough for the figures of the cost control.

### 124. The fault: leaving the settings closed the cost control beside them

**Status: built (version 0.84), as far as it could be: the fault was not found.** Tried, each as a test: the cost control open and the settings beside it, every element of Main view unchecked but the cost icon, the settings left by `Save`, `Cancel` and the gear (three tests); the settings opened first and the cost control beside them, left the same three ways (three tests); and in the real window, with the real backend, the settings beside the cost control and the history, every element unchecked but the icons, and `Save` (`SeveralViewsWindowTest`, step `beside-saved`). In every one the cost control stayed open. Nothing was changed on a guess; the tests stay, and the steps that led to the fault are to be asked of the person who saw it. The version is 0.84 with this phase. Requirements: Several views at once (leaving the settings).

- **First a test that fails**, in `app.test.cjs`, doing what the person saw: several views at once on, the cost control open, the
  gear, every element of `Main view` unchecked but the cost icon, and the settings left by each way (`Save`, `Cancel`, the gear).
  The cost control must stay. If the test does not fail, the other ways are tried (the settings opened first and the cost control
  beside them, the switch off when they were opened, the poll that comes between), each in a test, and nothing is changed on a
  guess; what was found is said here.
- **The fix** is where the test points, in `leaveForm`, `keepSwitchedOn` or the poll; the test stays.
- `AppInfo.VERSION = "0.84"`.

### 125. The cost control: its own width, blocks of 14 pixels, the yellow

**Status: built (version 0.84); seen in the real window on Windows by the three window tests.** As built: the cost control asks for `Math.max(row, costWidth() + edge)`, alone and among views, the history's table no longer in it. **The cut of the figures at the window's edge was found in `costWidth()`**: it counted the panel's padding and not the cost control's own (8 pixels each side, `COST_PADDING`), nor the border of a window with no title bar; in the real window the row of the cost icon alone and the cost control now leave 19 pixels at the right of the figures (`SeveralViewsWindowTest`, `narrow-room`), and the cost control is 391 wide before and after the history. Blocks of 14 pixels by the gradient's size; `--warn: #ffff33`, `--warn-text: #b8a000`, the amber dot ringed by a `box-shadow`. Requirements: The cost control panel (the width, the blocks, the yellow); Several views at once (the width).

- **The width.** `app.js`: the cost control's width, alone and among several views, is `Math.max(rowWidth, costWidth())`, no longer
  `currentTableWidth() + 16`. `costWidth()` is checked against the real window: the label, `COST_BAR_MIN`, the four columns and
  their gaps, the panel's padding, and 8 pixels of room at the right; the room missing at the right of the figures is found by a probe step that
  says how far the figures stick out, and the formula is put right by what it says.
- **The blocks.** `app.css`: the gradient of `.cost-bar::after` sized `14px 100%` in the place of `5% 100%`, the layer as wide as the
  bar and a pixel; the fill is unchanged, so it still ends at the share.
- **The yellow.** `--warn: #ffff33`; a new `--warn-text: #b8a000` for 126; the amber dot a ring of 1 pixel in `--warn-text`
  (`box-shadow: 0 0 0 1px`, which takes no room), decided in a last question: the plain yellow dot was hardly seen.
- **Tests.** `app.test.cjs`: the cost control's size the same before and after the history has been shown; `layout.test.cjs`: the
  gradient of 14 pixels, the yellow. The window tests: a probe step with the row of few elements and the cost control, that
  nothing sticks out at the right, and that the window is as wide after the history as before.

### 126. The row: the colours of the bars, `Monthly credit used`, the folders

**Status: built (version 0.84); seen in the real window on Windows by the three window tests.** As built: `toneOf(part)` gives ` over` for red and ` warn` for yellow, put on the amount used and the percentage of the month and on today's spend and percentage; `.spend .warn` in `--warn-text`; the limits untouched. `Monthly credit used` in `StatusDisplay` and in the gray box. `showFolder` writes `Program folder` with the path as its tooltip. Two tests of `ApiTest` that read the server's limit at once now wait for the first reading: they failed once in this phase's run, the fetch not having answered yet. Requirements: One green, and the colours of the bars; the tooltips of the row; The folders of the log and of
the usage history.

- **The colours.** `renderSpending`: the class of the amount used and the percentage of the month from `spending.month.state`
  (`over` for red, `warn` for amber), and of today's spend and percentage from `spending.day.state`; `app.css`: `.spend .warn {
  color: var(--warn-text) }`. A gray state leaves the green.
- **The tooltip.** `StatusDisplay`: `Monthly credit used` with its unit in the place of `Credits used`; `app.js`: the gray box's.
- **The folders.** `showFolder`: with no folder chosen the text is `Program folder` in gray, and the path is the tooltip's.
- **Tests.** `app.test.cjs`: the classes by state, the folder's text; `StatusDisplayTest`, `ApiTest`: the tooltip; the window tests,
  whose seat-based step reads the tooltip of the amount used.

**By eye, when 124 to 126 are built**: the cost control in a narrow and in a wide window (the blocks, nothing cut), the yellow, the
row's colours in each state, the group Files.

### 127. Version 0.85: the orange, and the row that goes while the pointer is outside

**Status: built (version 0.85); not looked at by a person.** The notes of `new-requirements.md` (the seventh round), decided in one
round of questions: `#ff9900` for the bars and the dot, `#c26a00` for an orange figure; "only icons" is every figure of the row off,
no new setting; the window less tall by the row, the views moved up; half a second before the row goes. `AppInfo.VERSION = "0.85"`.
Requirements: The cost control panel (the colour of the used part); One green, and the colours of the bars; The row goes while the
pointer is outside the window. The exchange is in `docs/api.md` (The pointer outside the window).

- **The orange.** `app.css`: `--warn: #ff9900`, `--warn-text: #c26a00`; nothing else changes, the dot's ring, the bars and the figures
  taking them by name.
- **The host.** `PointerAway` (new): away once the pointer has been outside for `DELAY_MILLIS` (500) without a break, back at once.
  `UsageApp.followPointer`, on every fitting tick: the pointer's place from a JavaFX `Robot`, inside the stage's bounds with its
  title bar and frame, and `window.pointerAway(true|false)` only on a change. `WindowFit` reads a last field `,-<pixels>`, after
  the panel and its height alone, as `Size.away`, never more than the least height leaves. `resize` takes it from the content
  height after the screen's room; `rememberDraggedHeight` and the stored window add it back, and a height dragged and not yet
  remembered is remembered before the row goes.
- **The page.** `rowMayHide(show)`: `display.titleBar === false`, a view or the settings open, and every figure off in the saved
  `show` (the circle, the refresh and the icons may be on). `render` sets `rowAway`, `markNoted` the class `away` on `#top`, which
  `app.css` gives no height and no vertical padding, its width kept. `rowHeight()` measures the row with the class let go of for
  the moment; `chromeHeight`, `panelHeight` and the page's own height (`appHeight`) count it while it is away, so every height is
  the one with the row, and `contentSize` appends `,-` and the row's height rounded down.
- **Tests.** `app.test.cjs`: the row going and coming with the cost control, a list, the settings; not with nothing open, the title
  bar on, or any of the twelve figures on; back when the saved settings change or the last view closes. `layout.test.cjs`: the
  orange, the rule of `.top.away`. `WindowFitTest`: the new field; `PointerAwayTest`: the half second and the bounds.

**By eye**: the orange of the dot, the bars and a figure; the row going and coming in the real window with the cost control, a
list and several views, on Windows and on macOS, and a list dragged taller and the row going after it.

### 128. Version 0.86: the circle keeps the row, one orange, one view without a line, the cost control first

**Status: built (version 0.86); only the tests of what changed were run, not the whole of them.** The notes of `new-requirements.md`
(the eighth round: a row of only the status circle, the cost icon and the gear above the cost control alone, a line
between them, and the cost control with the row away, its first bar at the window's top edge), decided in one round of questions. `AppInfo.VERSION = "0.86"`. Requirements: The row goes
while the pointer is outside the window; One green, and the colours of the bars; A line between the row and the views; Several views
at once (the order).

- **The circle.** `rowMayHide`: `s.status !== false` among the figures, so the row stays while the circle is shown; the refresh
  button is still an icon.
- **The orange.** `.spend .warn` in `--warn`; `--warn-text` is the ring's alone.
- **The line.** `showViews` gives `#panel` the class `single` with one view or the settings alone; `.panel.single > …` has no
  `border-top`.
- **The order.** `.panel > .cost { order: -2 }`, before the settings' `-1`; the names the page says to the host are unchanged
  (`settings+cost+history`), as nothing there depends on the order on the screen.
- **The room.** `.top.away ~ .panel > .cost { padding-top: 8px }`, 6 more than its own 2; the page measures it, so the size asked
  for has it.
- **Tests.** `app.test.cjs`: the circle keeps the row, the refresh does not, the class `single`; `layout.test.cjs`: the rules.
  Run: those and the tests of 0.85's row, eleven in all.

**By eye**: both again, the cost control above the settings, and one view without its line.

### 129. Version 0.87: one folder, and no title bar but the settings'

**Status: built (version 0.87); its tests brought up to date with phase 130, and they pass (the window tests not run).** Built
with no test run, by request; with phase 130 the tests listed under *Not done* were rewritten for one folder and for a list
with no title bar: `SettingsStoreTest` (the key `dataFolder`, a file of 0.86 and `oldLogFolder`), `ApiTest` (the four tests of
the folders), `WindowFitTest` (`keepsTitleBar` for the settings and the debug settings alone), `app.test.cjs` (the fixture,
the group `Files`, the border with a list open, the height a list alone asks for) and `layout.test.cjs` (the tooltip of
`Title bar`, the one line of `Files`). No test was added for the buttons `Taller` and `Shorter`, `POST /api/height`, the
scrollbars that do not move the window, or the move of the old log at the start. The notes of `new-requirements.md` (the ninth round, with the two folder lines of the group `Files` to be made one), decided in three rounds of
questions. `AppInfo.VERSION = "0.87"`. Requirements: The folders of the log and of the usage history; Settings, The title bar;
A border round the window that has no title bar; Dragging the window that has no title bar; Taller and shorter by buttons.

- **One folder.** `SettingsStore`: `dataFolder` read (else `historyFolder`, which wins), written alone; `Settings` keeps its two
  fields, always the same, so nothing that reads them changed. `oldLogFolder()` says the log's folder of a 0.86 file when it is
  another; `Main.moveOldLog` moves the log and its old logs there by `Logging.moveFiles` before the log is opened, saves the
  file, and logs what it did once the log is open. `ApiHandler`: `dataFolder` in and out of `/api/settings`. The page: `FOLDERS
  = ['dataFolder']`, one line in `index.html`.
- **The title bar.** `WindowFit.Size.keepsTitleBar()`: the settings and the debug settings only. The page: `bare()` without
  `!listOpen()`, so a list has the border; `dragStartsAt` refuses the scrollbars of `.panel-lines`, by `clientWidth` and
  `clientHeight` against the box; the tooltip of `Title bar`.
- **The buttons.** `size-<list>` with `taller-<list>` and `shorter-<list>` in each line of controls, shown while
  `display.titleBar === false`. `resizeList` changes the height alone by `5 × listSize × 1.25`, between `panelHeight` and 1,600;
  for the history and the log `POST /api/height` (new) stores it first, then the page takes it into `lastStatus.heights`, and
  for the error log `errorsHeight` holds it. A list alone now asks for `aloneHeight(list)` in the place of `panelHeight`, so that
  the host sees a new height; where nothing is remembered that is the same number.
- **Not done**: the tests. `app.test.cjs` and `layout.test.cjs` that name `logFolder`, `historyFolder`, the two lines of `Files`,
  a list with its title bar, or a list alone at `panelHeight` with a larger remembered height, are expected to fail;
  `SettingsStoreTest` and `ApiTest` where they read or write the two keys; `WindowFitTest` on `keepsTitleBar`.

**By eye**: the move of a 0.86 settings file with two folders; a list with no title bar, its border, moving it by its lines and
not by its scrollbar, the two buttons, and the height kept for the next start.

### 130. Version 0.88: the circle blinks

**Status: built (version 0.88); `./gradlew build frontendTest` passes, the tests that open a real window (`TitleBarWindowTest`,
opt-in) not run, by request.** The note of `new-requirements.md` (the tenth round), decided in one round of questions; it takes
back "the circle does not pulse" of phase 121. `AppInfo.VERSION = "0.88"`. Requirements: Limits, projections and the status of
spending (The status circle blinks); Settings (the table); A checkbox for each element (the property beside `Status`).

- **The setting.** `Settings`: `statusBlink`, the last component, on by default, `withStatusBlink`; `SettingsStore`: the key
  `statusBlink` after `historyDailyBy`, a file without it reads as on. `ApiHandler`: in and out of `/api/settings`, optional in
  a `POST`. `StatusDisplay.Show`: `statusBlink`, so that the row knows it without the settings.
- **The page.** `index.html`: `set-statusBlink`, `Blinks`, in a `span.line` with `set-showStatus`, and its tooltip. `app.js`:
  `statusBlink` among `MAIN_FLAGS` (so read, sent and previewed as the others) but not among `ELEMENT_FLAGS`, so the button that
  selects every element leaves it, as it leaves the currency symbol. `renderSpending` gives the dot the class `blink` when
  `statusBlink` is not off and the state is not `gray`.
- **The fade.** `app.css`: `.status-dot.blink` runs `status-breath`, opacity 1 → 0.3 → 1 in 2 s, `ease-in-out`, infinite. Only
  the class changes with the state, so the fade goes on; the ring of the orange fades with the dot.
- **Minimized.** `UsageApp.followMinimized`, on the tick that follows the pointer: `stage.isIconified()` told to the page by
  `window.windowHidden(…)` when it changes, and again until the page takes it, so a start minimized is told once the page is
  loaded. The page adds `paused` to a blinking dot; `.status-dot.blink.paused { animation-play-state: paused }`. Read on the
  tick rather than listened for, since the stage may be swapped.
- **Tests.** `SettingsStoreTest`: on for a file that says nothing, saved and read back, the key in the file. `ApiTest`: on by
  default in `/api/settings` and in `display.show`, switched off, kept by a `POST` without it. `StatusDisplayTest`: the new
  member of `Show`. `app.test.cjs`: `blink` for green, amber and red, not gray, not with `Blinks` off; `windowHidden` pauses
  and lets go; the checkbox beside `Status`. `layout.test.cjs`: three lines with a property, the animation and the paused rule;
  the test that said the circle has no animation now says it has none without `blink`.

**By eye**: the breath on both themes, the orange with its ring; that it does not distract; that it stands still while the
window is minimized and blinks again when it is brought back; how much of the processor the window uses while it blinks.

### 131. Version 0.89: the bottom edge, the figures of the cost control, the day's line, the zoom, the reasons in the log

**Status: built (version 0.89); `./gradlew build frontendTest -Daipusage.windows=true` passes, the window tests
included.** The notes of `new-requirements.md` (the eleventh round, the two buttons of five lines to go, the column `by`, its `auto` touching the
figures of `day`, the line of the lines shown wanting a tooltip, the wrong field of a failed save not shown, the history's days not told apart, and each column of the cost control's figures to be switched off), decided in two rounds of questions, and a third before building: the grip of the bottom
edge on hover, the zoom at the start too, the whole suite run with the window tests (`-Daipusage.windows=true`), and one commit
once it passes.

**As built, where it differs from the plan below:**
- **The bottom edge** (part 4) needs no `edgeDragged` and no `POST /api/height` from the page: `UsageApp` sets the window's height
  while the edge is dragged (`edgeDrag`, `edgeHeight`, clamped by the limits of the last fit, `WindowFit.MAX` and the screen),
  and the height is remembered by the path a frame drag already takes (`watchHeight` → `heightSettled` →
  `rememberDraggedHeight`). `resizeList`, `errorsHeight` and `LIST_HEIGHT_MAX` are gone from the page; `/api/height` stays, unused
  by the page. The cursor is the strip's CSS `ns-resize`, which the WebView shows; no scene cursor was needed. A height that
  cannot be saved is logged, as with the frame.
- **The step of the search** (part 1): no `Step` record; `IntervalSearch.lastStep()` gives the line, null when nothing moved.
  `held at 10 s` can never be said of a step down with no refusal (it then moves nothing), only `held at 300 s` after a 429.
- **The columns of figures** (part 8) are three components of `CostControl`, beside its `currency`, not of `Settings`; `/api/cost`
  sends them as `show`.
- **The zoom** (part 10): `Locator` makes a new transparent stage for each zoom, owned by the window, marked by `setUserData`
  so that `Desk` and the probes never take it for the program's window; focus changes are ignored while it is drawn and for
  400 ms after, and while the folder picker is open and 400 ms after (`Desk.pickerBusy`).
- **The dark gray of the day's line** is `#3d444d`: `#30363d` is a colour an old test keeps out of the style sheet (the start
  line's background of before 0.80).
- **Tests added**: `IntervalSearchTest` (each step and its line), `ApiTest` (the day's first line with lines hidden, the note's
  tooltip, the cost columns, `field` in a 400), `SettingsStoreTest` (the cost columns), `app.test.cjs` (eight), `layout.test.cjs`
  (four), and in the window, `UsageAppProbe` `drag-edge`: the bottom edge makes the history 60 pixels taller and moves nothing.
  Changed: the log lines in `UsageServiceTest`, the `by` column, the settings count, unchecking `Automatic` (page and window). `AppInfo.VERSION = "0.89"`. Requirements: Taller and
shorter by the bottom edge; Dragging the window that has no title bar; The automatic interval (the log); Cost control (unchecking
`Automatic`, `Figures`); The cost control panel (each column); Settings (a wrong field gets the focus); Usage history (the column
`daily limit is`, the tooltip of the note); The first reading of a day is underlined in the history; The window shows where it is
when it comes to the front. Eleven parts, in the order they are best built:

1. **The reasons in the log.** `IntervalSearch`: each step returns what it did, a small `Step` (the kind: `REFUSED`,
   `SERVER_WAIT`, `FASTER`, `FASTER_UNREFUSED`, `R_LOWERED`, `R_FORGOTTEN`; the old and new interval, `R`, the wait, whether
   10 or 300 held it) in the place of the boolean it gives now; `Step.describe()` writes the line of the requirements.
   `UsageService` logs `"Automatic interval: " + step.describe()` at 511 and 520, and the lines of switching on and off at 263.
   `describe()` of the search stays for `Automatic interval on: …`.
2. **Unchecking `Automatic`.** `app.js`: the handlers of `set-costDailyAutomatic` and `set-costWorkdaysAuto` leave the field's
   value when it is unchecked, where they now put back the typed value (or empty it). Checking again shows the worked-out one,
   as now.
3. **The scrollbars on macOS.** `dragStartsAt`: besides `clientWidth`/`clientHeight`, a press within 16 CSS pixels of the
   right edge of a `.panel-lines` whose `scrollHeight > clientHeight`, or of its bottom edge when `scrollWidth > clientWidth`,
   is no drag. Overlay scrollbars have `clientWidth === offsetWidth`, which is why the check of 0.87 let them through.
4. **The bottom edge.** `index.html`: the buttons `taller-<list>` and `shorter-<list>` gone, and their handlers and
   `resizeList` in `app.js`. `dragStartsAt` returns `false` in the bottom 6 pixels while `bare()` and a list is shown; a new
   `window.edgeAt(x, y)` says `'s'` there, else `''`. `WindowDrag` (Java): on a press where `edgeAt` says `'s'`, a resize in
   place of a move: the stage's height follows the pointer, and the page is asked for nothing until the release, when it is
   told `window.edgeDragged(height)`; the page then works the lowest list's height alone out of it, clamped to `panelHeight`
   and `LIST_HEIGHT_MAX`, posts `/api/height` for the history and the log (as `resizeList` did) or keeps `errorsHeight`, and
   lays the views out; a refused post is the red line, the height kept for the run. The cursor: `#app.bare.open` gets a
   `::after` strip of 6 pixels at the bottom with `cursor: ns-resize`, and the host sets `Cursor.V_RESIZE` on the scene while
   the pointer is over it (WebView does not pass CSS cursors to an undecorated stage on every system; check on Windows and
   macOS). **The grip** (decided before building: on hover, not always and not the cursor alone): `#edge-grip`, absolutely
   placed, 24×2, `border-radius: 1px`, `var(--muted)`, `hidden` unless the pointer's `clientY` is within 16 pixels of the
   window's bottom (`mousemove` on the document, `mouseleave` hides it), and `pointer-events: none`. `UsageAppProbe` keeps its
   steps; the window tests check the strip and a drag of it.
5. **The column `daily limit is`.** `ApiHandler` (1068): `auto` written `worked out`, `set` `typed`, other text as it is; the
   title `daily limit is` and its tooltip where the column titles are made. `app.js`/`app.css`: the width of `dailyBy` 14
   characters, and `padding-left: 1ch` on its cells and title. `index.html`: the checkbox's text and tooltip.
6. **The tooltip of the note.** `ApiHandler` (1120, where the parts of the note are made): `noteTooltip` from the same counts,
   in `HistoryBody`; `app.js` puts it on the note's `title`, and none with another note.
7. **The day's line.** `ApiHandler`, in the loop over the shown rows (1047), newest first: a row is `firstOfDay` when the next
   row shown has another local date or there is none; `HistoryLine` gets the field. `app.js`: the class `day-first` on such a
   row; `app.css`: `tr.day-first td { border-bottom: 1px solid var(--grid) }`, `--grid` `#d0d7de` / `#30363d` if there is none.
8. **The figures of the cost control.** `Settings`: `costShowUsed`, `costShowLimit`, `costShowPercent`, on by default, with
   `withCostFigures`; `SettingsStore`, `ApiHandler` (in and out, optional in a `POST`). `CostPanel`/`/api/cost`: the three
   flags with the rows, so the page knows them without the settings. `index.html`: the line `Figures` after `Currency
   symbol` in the tab `Cost control`, three checkboxes. `app.js` (1578): the cells of `cost-used`, `cost-of`, `cost-limit`,
   `cost-percent` only as the flags say, `of` only with both amounts, `·` only after something; the grid's columns by what is
   shown; the preview from the form while the settings are open, as `previewNow` does for the row. The panel's width follows,
   as it is measured.
9. **A wrong field gets the focus.** `InvalidSettingException` gets an optional `field` (the key of the setting) and every
   `throw` that knows its setting gives it (the interval, the limits, the hours, the working days, the folder); `ApiException`
   and `send` put `field` beside `error` in the `400`. `app.js`: the page's own checks (831 and the cost fields) name their
   field too; one `focusField(key)` shows the tab whose section holds `set-<key>` (the folder: `choose-dataFolder`), focuses
   it, selects its text, and adds the class `invalid`, taken away on its next `input`/`change`. `app.css`: `.invalid {
   border-color: var(--bad); outline: none }`.
10. **The zoom.** A new class `Locator`: one `Stage` of `StageStyle.TRANSPARENT`, `UTILITY` owner so it has no taskbar entry,
    always on top, `setFocusable`-free (it is never `requestFocus`ed, and shown with `show()` after the main window has its
    focus, then the main window's focus is kept). A `Rectangle` with no fill, stroke 3 px `#0969da`, arcs 12; a `Timeline` of
    0.6 s with `Interpolator.EASE_OUT` from the centred 60 % of the screen (`Screen.getScreensForRectangle` of the window) to
    the window's bounds, then a fade of 0.15 s, and `hide()`. The stage covers the screen's visual bounds and its scene is
    `mouseTransparent`; a transparent pixel takes no click on Windows and macOS, and the line is only there for 0.75 s.
    `UsageApp`: on the tick that follows the pointer, `stage.isFocused()` compared with the last tick, as `followMinimized`
    does with being minimized (the stage may be another one from tick to tick, so it is read, not listened for); a rise plays
    the zoom, unless the folder picker was open since the last tick (`Desk` sets a flag while its dialog is shown). At the
    start it plays once the window is shown. A failure to make a transparent stage is logged once at the debug level, and
    nothing is drawn after.
11. **The version and the docs.** `AppInfo.VERSION = "0.89"`; the README if it names the buttons of the lists.

- **Tests.** `IntervalSearchTest`: each kind of step and its line, the holds at 10 and 300. `UsageServiceTest`: the lines in the
  log. `ApiTest`: `worked out`/`typed` and the title; `noteTooltip` with each part; `firstOfDay` with lines hidden and across a
  day; the three cost flags in and out and in `/api/cost`; `field` in a `400` for each refused setting. `SettingsStoreTest`:
  the three keys and their defaults. `app.test.cjs`: unchecking keeps the figure, both checkboxes; no buttons of the lists;
  `dragStartsAt` near the edge of a list that scrolls with overlay scrollbars and at the bottom edge; `edgeAt`;
  `edgeDragged` posts the height, keeps it on a refusal; the cost columns and `of`/`·`, with the preview; `focusField` for a
  page check and for a backend `field`, the tab, the class and its removal; the note's tooltip; `day-first`.
  `layout.test.cjs`: the `by` width and its room, the strip and its cursor, `.invalid`, `tr.day-first`, the line `Figures`.
  `WindowFitTest`/window tests: the bottom edge resizes the lowest list and the height is stored; the zoom is not tested in a
  window beyond that `Locator` is made and hidden again (its look is by eye).

**By eye**: the zoom on Windows and macOS, from the taskbar, from minimized, with Alt-Tab, and that it takes no click and no
focus; the bottom edge's cursor on both; the scrollbar of a list on macOS not moving the window; the day's line on both themes;
the cost control with columns off; the red frame of a wrong field; the lines of the log after a 429 and after a long quiet.

### 132. Version 0.90: the settings rearranged, two folders again, the zoom a setting, the version of Claude Code

**Status: built (version 0.90).** Decided
before building: a select-all button for each of `Main` and `Icons`; the whole suite run with the window tests; one commit once
it passes, with parts 1 and 2 and without the notes and images of `docs/`. The notes of
`new-requirements.md` (the twelfth round, the four icons' checkboxes to be a group of their own, the tabs `Main view` and `History view` marked, the groups `Request`, `Files` and `Log` of `General` with
arrows to the tabs they go to, and the column `daily limit is` standing at the right; the swap of About and Debug was asked before the round, its two tabs marked),
decided in two rounds of questions, and the fault seen with the automatic interval. `AppInfo.VERSION = "0.90"`. Requirements:
The settings rearranged; The folders of the log and of the usage history; The window shows where it is when it comes to the
front; Authentication (the version of Claude Code); The automatic interval (switched on, it begins anew); Settings (the tabs);
Usage history (the column `daily limit is` centred). Ten parts:

1. **About before Debug** (built). `index.html`: `tabbtn-about` before `tabbtn-debug`; `app.js`: `TABS` with `about` before
   `debug`; `settingsWidth` takes the pages by name, where it took them by their place in `TABS` and so measured the Debug page
   outside debug mode once About was no longer last. Tests: the strip's order, the Debug tab's titles.
2. **Switched on, the search begins anew** (built). `AppRuntime`: the listener of the settings gives `setAutomatic` a fresh
   `IntervalSearch` (60 s, no refusal) and saves it, where it gave the saved search; the start still goes on from the saved one.
   `ApiTest.switchingTheAutomaticIntervalOnBeginsTheSearchAnew` (written, not run).
3. **The tabs.** `index.html`: titles `Main` and `History`, and the group headings; a page `tab-log` with `tabbtn-log` after
   `tabbtn-history`; `TABS = ['general', 'main', 'history', 'log', 'cost', 'about', 'debug']`. The group `Request` moved from
   `tab-general` to the top of `tab-cost`, unchanged (its ids stay, so `syncIntervalField` and the rest are untouched). The group
   `Log` (`set-logResponse`) to `tab-log`. `FIELD_TABS`: `usageIntervalSeconds` → `cost`. The settings open on the tab they were
   left on; a stored `main` or `history` stays valid by its id.
4. **The group `Icons`.** `index.html`: the four checkboxes out of the group `Main` into a fieldset `Icons` after it, labels
   `Cost`, `History`, `Log`, `Error log`, each followed by a `button.icon.tiny.glyph` (`glyph-showCostIcon` …) with the row's SVG of
   that view, `aria-label="Check <name>"`, tooltip of the checkbox. `app.js`: a click on a glyph sets its checkbox to checked and
   calls `previewNow`. **The select-all button** (decided before building: one for each group): `set-all` in the heading of
   `Main` takes `ELEMENT_FLAGS` less the four icon keys; a new `set-all-icons` in the heading of `Icons` takes the four, by
   `selectAllOrNone`. The row's icon handlers do not touch the form (they never did; a test says so). `app.css`: the glyphs muted, no border until hover.
5. **The paths.** `index.html`: the three lines of the About view (`about-settings`, `about-log`, `about-history` with their copy
   buttons) moved: the settings' below the group `Window` of `tab-general`, the log's into the group `Files` of `tab-log`, the
   history's into the group `Files` of `tab-history`; the About table keeps the rest. `app.js`: `loadAbout` still fills them (it
   reads `/api/about`), and is now also called when the settings are opened, since the paths are in other tabs; the ids stay, so
   the link and copy handlers are untouched.
6. **Two folders again.** `SettingsStore`: read `logFolder` and `historyFolder` (a file with `dataFolder` gives it to both),
   write the two and not `dataFolder`; `oldLogFolder` and `Main.moveOldLog` stay for a file of 0.86 (a file of 0.87–0.89 needs
   no move: both get the same folder). `ApiHandler.updateSettings`: `folderText(body, "logFolder", …)` and `"historyFolder"`,
   `dataFolder` taken for both when sent alone; a refusal names `logFolder` or `historyFolder` in `field` (the move says which
   folder failed: `moveFolders` tries the log first). `SettingValues`: the two in the place of `dataFolder`. The page: `FOLDERS
   = ['logFolder', 'historyFolder']`, a line each (`set-`, `choose-`, `default-`), and `folder-error-log` / `folder-error-history`
   under each group; `focusField('logFolder')` → `choose-logFolder`, tab `log`.
7. **The zoom a setting.** `Settings`: `locateWindow`, off by default, `withLocateWindow`; `SettingsStore` key `locateWindow`;
   `ApiHandler` in and out. `UsageApp.followFocus`: nothing unless `runtime.settings().current().locateWindow()`; a mouse press on
   the scene (an event filter on `MOUSE_PRESSED`, which `letTheWindowBeDragged` already has) records its time, and a rise of the
   focus within 500 ms of it plays nothing. The page: `set-locateWindow` in the group `Window`, after `Several views at once`, with
   its tooltip.
8. **The version of Claude Code.** A small `ClaudeVersion` in `token`: runs `claude --version` with the same command resolution as
   `ClaudeTokenProvider` (`windowsCommand` on Windows), stdin closed, output read, a limit of 10 s (`ProcessTree` ends it after),
   and returns the first line or the reason. `AppRuntime.start`, after `logSource`: unless the fake backend or the placeholder
   token is in use, on a daemon thread, `LOG.info("Claude Code version: " + line)` or `"Claude Code version could not be read: " +
   reason`. The command is the one the token provider is given, so a test passes a fake.
9. **The column `daily limit is` centred.** `app.css`: `text-align: center` on `.c-dailyBy` (title and cells), keeping the room
   at its left.
10. **The docs and the version.** `api.md` has `locateWindow` and the two folders; the README if it names the tabs.

- **Tests.** `SettingsStoreTest`: the two folder keys again, a file with `dataFolder`, `locateWindow` and its default; the key list.
  `ApiTest`: the two folders in and out, a move into each, a refused one named in `field`, `dataFolder` sent alone; `locateWindow`;
  the settings count. `ClaudeVersionTest`: a fake command that prints a version, one that fails, one that hangs past the limit.
  `app.test.cjs`: the tab titles and order, `Log` with its two controls, `Request` in Cost control (the interval's focus goes to
  that tab), the group `Icons`, a glyph click checks and previews, a second click leaves it checked, closing a view in the row
  leaves the form; the paths in their tabs filled on opening the settings; the two folder lines and their errors; `locateWindow`.
  `layout.test.cjs`: the groups in their tabs, the strip, the centred column. Window tests: the probe's tab names (`tabbtn-main`
  stays, its title changes), the settings' width with the paths in `General`, and the zoom not playing when off (the default).

**By eye**: the tabs and the widest of them with the paths; the glyphs in the group `Icons`; the zoom when switched on, and not on a
click into the window; the line of the Claude Code version in the log on Windows and macOS.

**As built, where it differs from the plan above:**
- **The move of the log of 0.86 at the start is gone** (`Main.moveOldLog`, `SettingsStore.oldLogFolder`): with a folder each again a
  file of 0.86 means what it says, and a file of 0.87 to 0.89 gives its one folder to both, so there is nothing to move.
- **The version of Claude Code** is asked for by `UsageApp.start`, right after its first line, once in a run on a daemon thread,
  whatever the source (asked for after building: at every start, the fake backend and the placeholder token included; it was
  first left out with them); not in `AppRuntime`, whose many test starts would each run the real `claude --version`.
  `ClaudeTokenProvider.isWindows` is package-visible for it.
- **`Show where the window is`** is the one checkbox that starts with `Show`: the old rule of the texts makes it its exception.
- **A refused folder** names `logFolder` or `historyFolder` by which of the two changed; `dataFolder` sent alone (a page of 0.89)
  still sets both, and is named `dataFolder` when it is refused.
- **Tests added**: `ClaudeVersionTest` (prints, fails, hangs past the limit, the command), `FakeClaude` mode `version`;
  `SettingsStoreTest` (two folders, a file with `dataFolder`, `locateWindow`); `ApiTest` (`locateWindow`, the two folders,
  `dataFolder` alone, a refused `historyFolder`); `app.test.cjs` (five: the glyph, a view closed in the row, the log's folder and
  its red line, `locateWindow`, the paths filled on opening). Rewritten: the layout tests of the tabs, the groups, the About view,
  the paths, the icons and the folders; the select-all tests for the two buttons; the folder tests for the history's folder.

### 133. Version 0.91: the error log into the log, the right edge, the tooltips' duration, and smaller things

**Status: built (version 0.91); the Java and frontend tests pass, the window tests not run, by request.** The notes of `new-requirements.md` (the thirteenth round, `Show where the window is` marked to be on by default, the icons of the group `Icons` showing as empty boxes, the folder's and the file's lines of the tab `Log`, and the history's columns at the left and the right), decided in two rounds of
questions, and a third before building: the page's own tooltips should the toolkit give no handle on its own; the Java and
frontend tests without the window tests; one commit once they pass, with the Claude version at every start, without the notes and
images of `docs/`; the gap after the level of a log line stays as it is, by request. Also in it, built already and not committed: the
version of Claude Code logged at every start, the fake backend and the placeholder token included (`UsageApp.start` calls
`logClaudeVersionOnce`, the factory of sources no longer does). `AppInfo.VERSION = "0.91"`. Requirements: Version 0.91: the error log
goes into the log, and smaller things; The window shows where it is (on by default, not after a click on the title bar); The first
reading of a day (3 px, blue); Bigger tooltips (their duration). Ten parts:

1. **The zoom on by default.** `Settings`: `locateWindow` `true` in the defaults; `SettingsStore` reads a missing key as on. The
   page's fixture and the tests that assume it off.
2. **Not after a click on the title bar.** `UsageApp.followFocus`: besides the press of the mouse, a rise of the focus counts only
   when the pointer is outside the window's bounds, title bar and frame included (`robot.getMousePosition()` as `followPointer`
   reads it, against `stage.getX/Y/Width/Height`), or on the first rise of the run (the start). `PointerAway.inside` does the test.
3. **The error log goes.** The page: `errors-button`, `view-errors` and their lines, `set-showErrorIcon` (and its line in `Icons`),
   `'errors'` out of `VIEWS` and `LISTS`, `ICON_FLAGS`, `MAIN_FLAGS`, `contentSize` (the `only === 'errors'` branch), the
   restore of views (a stored `errors` name is skipped by `restoreView`, which already skips names that are no views). Java:
   `/api/errors`, `ErrorsBody`, `ErrorEntryBody`, `StatusDisplay.Show.errorIcon`, `Settings.showErrorIcon` (kept in the record and
   read, no longer written, so that the record's many `with…` stay as they are), `WindowFit`'s `errors` in the panels it knows
   and `lowestList`. `UsageService.errors()` and `ErrorLog` stay only if something else reads them; else they go.
4. **Every failed refresh at `ERROR`.** `UsageService`: `LOG.warning("Usage refresh failed: …")` and the unexpected one at
   `LOG.error`; the HTTP 429 of the automatic search stays at `INFO` (it is logged by the search). `Log` has `error`, used already.
5. **Red and orange in the log viewer.** `view.js` `describeLog`: a `levels` array beside the lines, the level read from the
   line (`ERROR`, `WARNING` after the time); `app.js` `showPanel`: the class `level-error` or `level-warning` on the row; `app.css`:
   `.panel-lines .level-error { color: var(--bad) }`, `.level-warning { color: var(--warn-text) }`.
6. **The log larger.** `app.js` `contentSize`: the log alone `Math.ceil(4.5 * width)` wide where it was `3 * width`, and its height
   alone `3 * panelHeight` where nothing is remembered (`aloneHeight('log')`), within the screen (`screenRoom`, as now); a
   remembered height still wins. With other views the log's share is as before.
7. **No icons in `Icons`.** `index.html`: the `glyph-…` buttons out; `app.js`: their handlers; `app.css`: `.icon.glyph`.
8. **The right edge.** As the bottom edge of 0.89: `#edge-right` (6 px, `cursor: ew-resize`) and `#edge-right-grip` (2×24, on
   hover within 16 px of the right), shown while `noTitleBar() && !settingsOpen` and the log is open (its width is the person's);
   `edgeAt` says `'e'` there; `UsageApp`: a press where it says `'e'` starts a width drag (`stage.setWidth`, clamped by the stage's
   min and max width and the screen), which `fit` keeps as a width the person gave (`keepWidth`, as the frame does).
9. **The paths in `Log` and `History`.** `showFolder`: the full path always, `programFolder` while it is the default, with the class
   `default` (gray) then; `showPath`: a mode that shows the file's name alone as the link's text (`about-log`, `about-history`;
   the settings' line in General keeps the full path), the full path still in `title` and on the copy button.
10. **The day's line, the columns, the tooltips.** `app.css`: `.panel-lines .day-first { border-bottom: 3px solid var(--info) }`; every
    `.panel-lines .columns > .c-*` and the title row `text-align: center`. The tooltip's duration: `Settings.tooltipMillis` (0 by
    default; 0 or 500–3,600,000, else refused with `field`), `SettingsStore` key `tooltipMillis`, `ApiHandler` in and out, the page's
    field `set-tooltipMillis` in `Window` after `Show where the window is`, with its tooltip, in `FIELD_TABS` as `general`. In
    `UsageApp`, on the tick, the `Tooltip` the WebView installs on itself (found in `webView.getProperties()` under the key the
    toolkit uses, or by `Tooltip`'s `install` being watched through a listener on the node's properties) gets `setShowDuration`:
    `Duration.INDEFINITE` for 0, else the millis; `setHideDelay` short. **Where the toolkit gives no handle** (decided before building), the page draws its own
    tooltips: a `div.tip` of the window's tooltip look (14 px, the system's colours as near as CSS has them), shown after the
    toolkit's delay at the pointer for the element's `title` (moved to `data-tip` while the page's tooltips are in use, so that
    the toolkit shows none), and hidden when the pointer leaves or after the setting's time. The window grows for no tooltip: one
    that would reach beyond the window is placed inside it.

- **Tests.** `SettingsStoreTest`/`ApiTest`: `locateWindow` on by default, `tooltipMillis` and its range, `showErrorIcon` no longer
  written, `/api/errors` gone (404), the settings count. `UsageServiceTest`: a failed refresh logged at `ERROR`. `app.test.cjs`:
  no error log anywhere (icon, view, checkbox), a stored `errors` view skipped, the log's size alone, the levels' classes, the
  glyphs gone, the right edge and its grip, `edgeAt` `'e'`, the folder's full path and the file's name, `tooltipMillis` sent and
  refused with focus. `layout.test.cjs`: the day's line, the centred columns, the edge, no `errors` ids. Window tests: the probe's
  steps of the error log out, a drag of the right edge, the log's opening size; `TitleBarWindowTest`'s expectations of the error
  log rewritten. The whole suite with the window tests.

**By eye**: the zoom not after a click on the title bar, and still from the taskbar; how long a tooltip stays (and whether the toolkit
honours it); the log's red and orange on both themes; the right edge's cursor on Windows and macOS.

**As built, where it differs from the plan above:**
- **The tooltips are the toolkit's still**: it gives a handle after all. The WebView installs one `Tooltip` on itself for the page's
  titles, under the property key `javafx.scene.control.Tooltip`; a listener on `webView.getProperties()` sets its `showDuration`
  each time it is installed (`Duration.INDEFINITE` for 0). The page's own tooltips were not needed. Whether it holds is by eye.
- **`ErrorLog` and `UsageService.errors()` stay**, unread by the page: the record of a run's failures costs nothing and its tests
  stay; only `/api/errors`, its bodies, the page's view and the icon go. `Settings.showErrorIcon` stays in the record, is read and
  no longer written or sent; a `POST` that sends it keeps it. `StatusDisplay.Show.errorIcon` and `WindowFit`'s name `errors` stay,
  unused.
- **The 429 of the automatic search** is logged `Usage refresh refused: …` at `INFO`; any other failure `Usage refresh failed: …` at
  `ERROR` (`SEVERE` in the file, which is what the viewer colours red).
- **The right edge** shares `edgeDrag` with the bottom one (a third field says which); `edgeAt` says `'e'` for it.
- **The log's levels** are read from the line in `view.js` (`SEVERE` or `WARNING` after the date and time), and an entry's following
  lines take its level.
- **Tests**: `UsageServiceTest` (a failed refresh at `SEVERE`), `ApiTest` (the error log's endpoint gone, a token problem and a 429 kept
  as failures, `locateWindow` on by default, `tooltipMillis` and its refusals), `SettingsStoreTest` (both, and the file's keys).

### 134. Version 0.92: a quieter log, the icons in one line, the screen as the largest size

**Status: built (version 0.92).** The notes of `new-requirements.md` (the fourteenth round, the log at `Info` full of the two lines of each request, and the checkboxes of `Icons` one below the other), decided in one round of
questions: only a request that succeeds goes to `DEBUG`; the screen is the largest size everywhere. Before building: the whole suite run, the
window tests included (`-Daipusage.windows=true`), and one commit once it passes, without the notes and images of `docs/`. `AppInfo.VERSION = "0.92"`.
Requirements: Version 0.92: quieter log, the icons in one line, the screen as the largest size; Details of the behaviour (the
largest size). Three parts:

1. **A quieter log.** `UsageClient.logOutcome`: `LOG.debug` when the outcome is `HTTP 200` (the request succeeded), `LOG.info`
   otherwise, as now; `UsageService` (line 429): `LOG.debug("Usage refresh succeeded")`. Nothing else changes level. Tests:
   `UsageClientTest` and `UsageServiceTest` where they read those lines (by level: `FINE`), and the seat test of
   `UsageServiceTest`, which lists `INFO Usage refresh succeeded` among its lines, now `FINE`.
2. **The icons in one line.** `index.html`: the three labels of the fieldset `Icons` in one `span.line`, as `Status` and `Blinks` are.
   Tests: `layout.test.cjs` (the group `Icons`: one line, three checkboxes in it, in that order).
3. **The screen as the largest size.** `UsageApp`: a `screenArea()` beside `screenRoom()`, the visual bounds of the screen the window
   is on, less the frame (`decorationWidth`/`Height` once measured). Where `WindowFit.MAX` limits what is applied (in `resize`: the
   opening height, `contentHeight`; `edgeHeight`) the screen's figures take its place: `contentHeight` is already capped by
   `screenRoom()`, and the width is capped now as well (`Math.min(width, screenArea().width)`). `applySize`: the max width and height of
   the stage are the screen's, where they were `Double.MAX_VALUE` (and the fixed figures where the size is not the person's), so that a
   drag by the frame stops at the screen too; `edgeWidth` and `edgeHeight` keep their screen limits. `WindowFit.MAX` stays as a sanity
   limit of what the page may report (a figure that large is no size at all), but is no longer what the window is held to: raised to
   10,000 by 10,000 so that a screen larger than 2,400 by 1,600 is not cut. The page: `screenRoom` stays the height; nothing else.
   Tests: `WindowFitTest` where it names 2,400 or 1,600; a window test is not run (by eye: a list dragged larger than the screen stops at
   its edge; a log opened on a small screen is no larger than it).

- **Tests.** As above, and the whole suite with the window tests, which have not run since 0.90: the error log's removal of 0.91
  touches their probes (`UsageAppProbe`'s steps `errors`, `errors-closed`; `TitleBarWindowTest`'s expectations of them;
  `SeveralViewsWindowTest`'s `four`), which are to be brought up to date as part of this phase.

**By eye**: the log at `Info` with only the lines that matter; the three icons in one line; dragging the window larger than the
screen, by the frame and by the edges, stops at the screen's edge; on a screen wider than 2,400 pixels the log may be as wide as it.

**As built**: as planned. `UsageApp.screenArea()` is the screen's visual bounds; `resize` caps the width by it and no longer the
opening height by `WindowFit.MAX`; `applySize` sets the stage's max to the screen (or the size, where that is larger) after opening
the limits; `edgeHeight` has no fixed most. `WindowFit.MAX` is 10,000 by 10,000. Tests: `UsageClientTest` (a request that succeeds
at `FINE`, one that fails at `INFO`), `UsageServiceTest` (the seat test's `FINE Usage refresh succeeded`), `WindowFitTest` (the
bounds), `layout.test.cjs` (the icons in one line). The window probes without the error log: `UsageAppProbe`'s `errors` and
`errors-closed` give way to `log-closed`; `SeveralViewsProbe`'s `four` is the cost control, the history and the log.

### 135. Version 0.93: a view grows towards the room there is

**Status: built (version 0.93).** The note of `tmp.md` (the log opened low on the screen ran over its bottom edge and had to be moved), decided
in one round of suggestions: the corner nearest the screen's stays, what still does not fit is moved onto the screen, closing goes
back to the place of the row, and the log's width is remembered. Before building: phase 134 committed. `AppInfo.VERSION = "0.93"`.
Requirements: Version 0.93: a view grows towards the room there is; Remembered heights (the log's width). Four parts:

1. **Where the window goes, worked out apart from JavaFX.** A new `WindowPlace`, final, package private, beside `WindowDrag`, with
   records `Rect(x, y, width, height)` and one rule each, so that `WindowPlaceTest` covers them without a window:
   - `anchored(Rect before, double width, double height, Rect screen)`: the place of a window of the new size whose corner nearest
     the screen's corner is where it was. The halves are decided by the middle of `before` against the middle of `screen` (the
     visual bounds): lower half → `y = before.maxY - height`, else `y = before.y`; right half → `x = before.maxX - width`, else
     `x = before.x`. Then `onScreen`.
   - `onScreen(Rect window, Rect screen)`: moved the least that puts it wholly on the screen, in each direction on its own; a
     window as large as the screen lies at its edge (the window is never larger, phase 134).
2. **Applied where the program sets a size.** `UsageApp`:
   - `applySize` takes the place too: the size and the place are set together, inside `applying`, so that the frame and the
     listeners take neither for the person's. `resize` decides which: `before` absent, or `restoring` not null (the start puts the
     window where it was, `restorePlace` and `applyRestoredSize`, and that place is not moved) → the size alone, as now. Otherwise:
     - **from the row alone to a view** (`before.resize() == NONE`, `size.resize() != NONE`): `rowPlace = (x, y)` of the stage, then
       `anchored`;
     - **from a view to the row alone**: back to `rowPlace` when the person has not moved the window since (`movedByPerson` false),
       else `anchored` from the window as it is; then `onScreen`, and `rowPlace` cleared;
     - **a view to a view** (another panel, another set of several, a list's own buttons: anything `WindowFit.next` gives while a
       view stays shown) → `anchored`; `rowPlace` stays, so that replacing a view keeps the place of the row;
     - **not for** `size.away()` changing alone (the row away and back, phase 129), nor `keepWidth` with the same height (a new line):
       those keep the place as now. The comparison is of the size with `before` but `away`.
   - `movedByPerson`: listeners on `stage.xProperty()` and `yProperty()` set it when the change is not `applying`, and not
     `StageSwap`'s own (the title bar coming or going moves the window by its height, keeping the row in place, phase 48: that swap
     runs inside `applying` as well, or passes the place it sets on, whichever its code allows). A drag by the row (`WindowDrag`) or
     by the frame sets it the same way, there being one listener. Cleared when `rowPlace` is set. The listeners go with the stage to
     the swapped one, as `watchHeight` does.
   - `edgeHeight`'s room below the top (`UsageApp.java:303`) stays: a window anchored at the bottom has none, and its bottom edge drags
     no lower, as the frame drags no lower than the screen.
3. **The log's width remembered.** `SettingsStore.Heights` gets a third field, `logWidth` (key `logWidth`, `LOG_WIDTH_KEY`, read as
   the heights are, `0` for none), and `withWidth`; `IntervalSettings.storedWidth()` / `storeWidth(int)` as `storedHeight` /
   `storeHeight`, logging a file that cannot be written and nothing more. `UsageApp`: `watchHeight` watches the width too, with a
   `widthSettled` `PauseTransition` of 500 ms like `heightSettled`, while the log is shown **alone** (with several views the width is
   that of all of them, as the height of a list among them is not its alone); `rememberDraggedWidth` stores `stage.getWidth() -
   decorationWidth` when it differs. `resize`, for the log alone and not `keepWidth`: `width = max(page's, stored + decorationWidth)`,
   then capped by `screenArea()` as now. The page knows nothing of it; `/api/heights` (`ApiHandler.java:834`) stays the heights'.
4. **Version and texts.** `AppInfo.VERSION = "0.93"`; no text of the page changes.

- **Tests.** `WindowPlaceTest` (new): each quarter of the screen, the middle exactly on a half (upper left wins), a window that
  grows past the screen's edge after anchoring and is moved on, one as wide as the screen, a screen not at `0,0` (a second screen
  left of the first, negative `x`). `SettingsStoreTest`: `logWidth` read, written, kept by `save` and by the other writes, `0` and a
  missing key as none, a file not an object left alone. `IntervalSettingsTest`: a width
  equal to the stored one writes nothing. `UsageAppProbe`: a step `log-low`, the window put at the bottom right of its screen, the log
  opened: the window lies within the screen's visual bounds and its bottom right corner is where it was; `log-low-closed`: back at the
  place it had; asked for with `-Daipusage.windows=true`, as the window tests are.

**By eye**: the row at the bottom left by the taskbar, the log opened: it grows upwards and to the right, closing puts the row back;
at the bottom right, upwards and to the left; in the middle, it opens downwards and is moved up as far as needed; moved while the
log was open, closing leaves the row where it was moved to, its corner kept; the log dragged wider, closed and opened again: the
width it was left at; with `Several views at once`, the history added to the log at the bottom of the screen: the bottom edge stays.

**As built**: as planned, built on the uncommitted 0.92. `WindowPlace` with `Rect`, `anchored` and `onScreen`. `UsageApp.place`,
called by `resize` inside `applying` right after `applySize`, with the window and the screen as they were before the size changed.
The program's own sizes are told apart by `placedSize`, which the title bar's exchange does not reset (`appliedSize` it does), so
the views are still known after a swap; `rowPlace` is the place of the content, not of the stage, which the swap keeps. `StageSwap`
needed no change: it runs inside `applying`, and `watchPlace` is put on the new stage after it, as `watchHeight` is. `movedByPerson`
is set by any move outside `applying`, a resize by the top or left frame included. The log's width: `Heights.logWidth` (a
two-argument constructor kept for the callers that have no width), `IntervalSettings.storedLogWidth` / `storeLogWidth`,
`widthSettled`, and a width dragged to that has not settled yet is remembered before the next size is applied.
Tests: `WindowPlaceTest` (new, ten cases), `SettingsStoreTest` (the key `logWidth`, kept by `save`, unusable values, a file of
0.92), `IntervalSettingsTest` (across a restart, a file that cannot be written). `UsageAppProbe`: `log-low-before`, `log-low`,
`log-low-closed` with a `-place` line each, checked by `TitleBarWindowTest`: on a 1707 x 1019 screen, the row at 1338,979 opened
the log at 46,0 to 1707,1019, and closed it went back to 1338,979.

**Brought up to date with it**: two window tests the 0.91 changes had left behind. `PanelWindowTest`: the log three times as tall
as the history and four and a half times as wide as the row, with its scrollbar. `TitleBarWindowTest`: nine elements checked by
default, seventeen with all on, three with all off but the icons, the error log's icon being gone. Both pass.

### 136. Version 0.94: the whole address in the log, a button that ends the program, the history grouped

**Status: built (version 0.94).** The notes of `new-requirements.md` (the fifteenth round), decided in two rounds of questions: the whole
address at the levels of 0.92; every level written whatever the dropdown shows (already so, nothing to build); the cross closes at
once, its checkbox in `Icons`, on by default; the history grouped by the last reading with the changes summed, a dropdown in the
zoom line, good readings only, ISO weeks; closing the views goes back to the row's place always. `AppInfo.VERSION = "0.94"`.
Requirements: Version 0.94: the whole address in the log, a button that ends the program, the history grouped. Five parts, each
testable by itself, in this order:

1. **The whole address.** `UsageClient` (lines 209 and 218): a `shown(URI)` that gives `scheme://authority/path`
   (`uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath()`), used by both lines in place of `getHost() +
   getPath()`. No query, there being none; nothing in it is a credential, so `Redaction` is not concerned, which a test says.
   Tests: `UsageClientTest` where it reads those lines (`GET http://127.0.0.1:<port>/api/oauth/usage -> HTTP 200`, the response
   line with `Log the response`), and one with `https://api.anthropic.com` as the base, by a fake `HttpClient` if the test has one,
   else by the formatting alone.
2. **Back to the row's place, always.** `UsageApp.place`: closing goes to `rowPlace` whenever there is one, `onScreen` as now;
   `movedByPerson`, `watchPlace` and their calls go, being needed for nothing else. The `anchored` branch of closing stays for a
   window that has no `rowPlace` (the start restored it with a view open). Tests: `TitleBarWindowTest`'s `log-low` steps still hold;
   a step `log-low-moved`, the window moved by 100 pixels while the log is open, and `log-low-moved-closed` back at `log-low-before`.
3. **The cross.** `index.html`: `<button id="exit-button" class="icon tiny">` after `settings-button` in `.tools`, an SVG cross
   drawn as the others (16 viewBox, stroke `currentColor`, 1.6), `aria-label` and `title` `Close aip usage`; a checkbox
   `set-showExitIcon` labelled `Exit`, after `Log`, in the line of `Icons`. `Settings`: `showExitIcon`, default true, with
   `withShowExitIcon`; `SettingsStore`: `SHOW_EXIT_ICON_KEY = "showExitIcon"`, read and written beside `showCostIcon`;
   `ApiHandler`'s `SettingValues` and the flags of `POST /api/settings`; `StatusDisplay` passes it with the other icons. `app.js`:
   `ICON_SETTINGS` and `ICON_FLAGS` take `showExitIcon` (`exit: 'showExitIcon'`), so the preview and the heading's button include
   it; a click sets `exitAsked = true` and `window.exitAsked = function () { return exitAsked; }`. `UsageApp`: the tick (in
   `keepFitting`, before `fit`) asks `typeof window.exitAsked === 'function' && window.exitAsked()` and, when true, `stage.close()`:
   the last window closing ends the toolkit, and `stop()` runs `cleanup`, which stores the window as closing it by hand does. The
   page does not ask the backend: the window is the host's, and the host ends the program the one way it always has.
   Tests: `app.test.cjs` (the button is last, after the gear; hidden with `showExitIcon` off; a click makes `exitAsked()` true);
   `layout.test.cjs` (the icon's size and colour as the gear's; the checkbox in `Icons` after `Log`); `SettingsStoreTest` (the key,
   on by default, the file's list of keys); `ApiTest` (the flag in and out); the window probe: a last step that presses
   `exit-button` instead of closing the window, and `PROBE the program ended after the window was closed` still comes.
4. **The history grouped, in the backend.** A new `usage/HistoryGroups`, pure, beside `HistoryDeltas`:
   - `enum Group { NONE, MIN5, MIN15, MIN30, HOUR, DAY, WEEK, MONTH }` with `key()` (`none`, `5m`, …, `month`) and `of(String)`
     (anything else `NONE`); `LocalDateTime start(LocalDateTime reading)`: truncated to the interval, a week to its Monday
     (`with(TemporalAdjusters.previousOrSame(MONDAY))`), a month to its first day.
   - `List<Row> group(List<List<String>> rows, Group group)`: the rows oldest first, good readings only (no `failed`, not a start
     line without an amount); for each interval the last reading's cells, `Δ used` the sum of the amount changes of its readings
     (`HistoryDeltas.amountChanges`, the first one's from the reading before), `Δ time` the seconds from the reading before the
     first to the last; the time cell the interval's start written as Version 0.94 says (`HH:mm`, with the date when `Date` is on;
     `yyyy-MM-dd`; `yyyy-MM`).
   - `HistoryReader.read`: grouped, the whole file is read (`limit` = `Integer.MAX_VALUE`), the rows grouped, and the newest 1,000
     groups kept; ungrouped as now. The filter of zero usage applies to the groups (a `Δ used` of 0), that of failed lines to none.
   - `ApiHandler.history`: the group from `Settings.historyGroup()`, `firstOfDay` worked out on the groups for 5 minutes to an hour
     and false for a day or longer, `start` false for every group; the note counts groups. `GET /api/history?group=15m` shows that
     group, as `zeroLines` may be asked; `POST /api/history` takes `group` beside `zeroLines` and saves it (`withHistoryGroup`).
   - `Settings.historyGroup`, a text, default `none`; `SettingsStore`: `HISTORY_GROUP_KEY = "historyGroup"`, a value that is none
     of the keys read as `none` with a warning, as an unknown scenario is.
   - **The cost of reading the whole file**: at a reading a minute a year is about 525,000 lines and some 40 MB; read at each
     opening and each refresh of the grouped history. Measured once while building (`HistoryGroupsTest` with a generated year);
     should it be more than about 300 ms, the groups of the closed intervals are kept between requests, keyed by the file's size
     and time of change, and only the newest interval worked out again.
   Tests: `HistoryGroupsTest` (each group's start, a week across a month and a year, a month's reset within a day and a week
   summed right, failed and start rows left out, an interval with one reading, the order newest first, the 1,000 cap), `ApiTest`
   (`group` asked and saved, an unknown value refused with 400 as other bad fields are, the note), `SettingsStoreTest` (the key).
5. **The dropdown, in the page.** `index.html`: in `#log-controls-history`, before `.zoom`, a `<label>` `Group` with
   `<select id="history-group">` and the eight options, values the keys; the tooltip of Version 0.94. `app.js`: set from the
   history's answer (`group`, a new field of `HistoryBody`), and on `change` `POST /api/history {group}` then the history read again;
   the zoom line keeps its height. `view.js`: nothing; the backend's lines are drawn as they are. `docs/api.md`: `group` in GET and
   POST of `/api/history`, and in its body.
   Tests: `app.test.cjs` (the dropdown follows the answer; a change posts the key and reads the history again); `layout.test.cjs`
   (in the zoom line, before the buttons, the line no taller than before).

- **Tests.** As above; the whole suite, and the window tests (`-Daipusage.windows=true`).

**By eye**: the log at `Debug` with whole addresses, the fake backend's and Anthropic's; the cross after the gear, closing at once,
the window where it was at the next start; `Exit` off and the cross gone; the history grouped by 15 minutes, a day, a week and a
month, with the date on and off, the underline between days, the choice kept after a restart; the log moved while open, closed:
the row back where it was.

**As built**: as planned, with these differences.
- **The grouping lives in two places**: `settings/HistoryGroup` (the enum, its keys and where an interval begins, used by
  `Settings` to keep an unknown key out) and `HistoryReader.readGrouped` (the rows grouped, in the same `Table` shape as the readings,
  so `ApiHandler.history` draws them with the same code: the time cell by `groupTime`, `firstOfDay` off for a day or longer). No
  `usage/HistoryGroups`.
- **The cost of the whole file, measured**: a year at a reading a minute (525,600 rows, 34 MB) took 773 to 1,231 ms, over the
  300 ms of the plan, so the cache was built: `HistoryReader.Grouping`, one per file and interval, holds the intervals, how far the
  file was read and its last 256 bytes; a request reads only what was added since (16 ms after a reading), and groups anew when the
  file is shorter, its bytes before that point differ, or an added line is earlier than one before. The first opening of a grouped
  history of a year still takes about a second.
- **`Settings` has two more fields**, `showExitIcon` and `historyGroup`, at the end; a constructor with the 51 of 0.93 is kept for
  the callers that have no use for them. `POST /api/settings` keeps `historyGroup` as it is.
- **The response of the history** says the saved grouping, as `zeroLines` says the saved switch, also when the query asked for another.
- **The cross while the settings are open** is shown or hidden as its checkbox stands, not dimmed as the icons of the views are:
  it opens no view that would have to be reached. The requirement says so now.
- **Ending by the cross**: `UsageApp.exitAsked` on the tick, before anything else, `fitTimer.stop()`, `stage.close()` and
  `Platform.exit()`, so that the outline's window, if it is showing, does not keep the toolkit running. Logged as `The program is
  ended by its cross`.
- **Back to the row's place always**: `movedByPerson` and `watchPlace` are gone. The probe's `log-low-moved` steps move the window
  by 100 and 50 pixels while the log is open; closed, the row is at `log-low-before`.
- Tests: `HistoryGroupTest` (new), `HistoryGroupedTest` (new: the cells, the sums across a month, failed rows, the zero filter, the
  limit, lines added, a file replaced, and a year's timing), `UsageClientTest` (the whole address, Redaction leaving it), `ApiTest`
  (the grouping saved and asked for, refused keys, `showExitIcon` among the settings), `SettingsStoreTest` (the two keys),
  `LoggingEndToEndTest` (the addresses), `StatusDisplayTest` (`exitIcon`); `app.test.cjs` (the cross asks the host, hidden with its
  switch off; the dropdown as saved, a choice posted and the history read again, one that cannot be saved asked for by name),
  `layout.test.cjs` (the cross after the gear, its checkbox after `Log`, the dropdown before the zoom); the window probe ends the
  program by the cross. `TitleBarWindowTest`'s probe takes longer with the steps of 0.93 and 0.94: it is given 260 seconds, the test 300.

### 137. Version 0.95: a level Fine, and a shorter line for a change of the settings

**Status: built (version 0.95).** The notes of `new-requirements.md` (the sixteenth round, `img_77`, `img_78`), decided in two rounds
of questions: five levels, in the order Error, Warning, Info, Fine, Debug; what happened is Fine, how it was done Debug; the file
writes `FINE` and `DEBUG`; the settings line is Fine and names only what changed. Requirements: Version 0.95.

- **The levels.** `Log.Level` gets `FINE("FINE")` and `DEBUG` writes `DEBUG`; `Log.fine(String)`. `LogLevel` (the view's choice) gets
  `FINE("fine")` between `INFO` and `DEBUG`, and `ofLogWord` reads `FINE` as Fine (an older file's debug lines among them) and any
  other unknown word, `DEBUG` too, as Debug. `index.html`: the option `Fine` between `Info` and `Debug`; the page needs nothing else.
- **The lines that are Fine**: `UsageClient` (a request that succeeds), `UsageService` (`Usage refresh succeeded`),
  `IntervalSettings` (a height and the width remembered, `Settings changed`), `UsageApp` (`The window is restored`). The other
  `LOG.debug` calls stay.
- **The settings line.** `SettingsStore.root(...)`, split out of `write`, builds the file's object; `SettingsStore.changes(before,
  after)` builds it for both with the rest at its defaults and names each key whose value differs, `<key> <before> -> <after>`, a text
  as it is and an empty one as `""`. `IntervalSettings.apply` logs `Settings changed: <changes>` with `LOG.fine`, and nothing when the
  text is empty.
- **Tests**: `LoggingTest` (a Fine line `FINE`, a debug line `DEBUG`, the words read back), `MacNameTest` (`DEBUG`), `LogTailTest`
  (Fine shows Fine and not Debug), `SettingsStoreTest` (`changes`: the order, the empty text, a level, nothing), `IntervalSettingsTest`
  (one Fine line naming the change, none for a save that changes nothing), `layout.test.cjs` (five options). `docs/api.md`: `fine`
  among the levels of `/api/log`, and the words.

**By eye**: the log at `Info` without `Settings changed` while the zoom is pressed; at `Fine` the requests and `Settings changed:
listFontSize 12 -> 13`; at `Debug` the window's lines as well, written `DEBUG`.

## Implementation notes, as built

How the requirements are met today (version 0.77; written at version 0.37, and the part about the backend brought up
to date after phases 83 to 88). What these mechanisms make the
program do is in the requirements; what is here is only the means.

**Page and host**
- `window.contentSize()` is the whole of the page-to-host exchange; its wire format is in `docs/api.md`. `WindowFit` parses and clamps it, the stage stays resizable and its min and max size do the
  restricting, and `RememberedHeights` with a 500 ms `PauseTransition` stores a dragged height.
- The 16 in the history's width is the panel's own padding written as a number in `app.js`, the one fixed number left in the measure; the probe is `#table-probe`. `panelHeight` is taken when a panel is
  opened and not read again while it stays open.
- Polling is chained `setTimeout` after each answer; `setInterval(render, 1000)` draws the last status again each second without asking, which mostly keeps the hover line and the open panel's buttons in
  step. The click handlers are attached only after `/api/config` has answered.
- A panel's lines are replaced only when the JSON differs (`panelKey`).
- The page sets `.fit` on `#app` for the settings; there is no CSS rule for it (the settings are laid out by the absence of `.open`), so it is only a marker. `[hidden]` is forced with
  `display: none !important`; `box-sizing` is `border-box` throughout.
- The log's run grouping and its run-start marker are in `view.js`: a hidden contract with the form of the log line, which the requirements now state.
- A figure of the row that has no value is the class `skeleton`: a frame of one pixel in `--ok`, the colour of the figures' text, with square corners and no fill (phase 89).

**Backend**
- Startup order: `Main` reads the command line and opens the log (`Logging.install`, with the stack traces on the console in debug mode), then the window; in `AppRuntime`: settings load, history `latest()` restore, listeners, `ResponseLog`, web server, then `service.start()`. `Main` is not an `Application` so that an IDE `main` works from the class path.
  One JVM shutdown hook and `RunOnce` make the window close and the process stop share one cleanup, and the same hook closes the log last. The log is the program's own (`Log`), so nothing else can close it first: `ShutdownSafeLogManager` and the rule that `Logging` is installed before `UsageApp` is loaded are gone (phase 85).
- The history is appended with `CREATE`/`APPEND`. `/api/history` reads the whole file on every call, `/api/status` only when `LatestChangeCache` sees a changed size or time.
- The back-off of versions up to 0.63 is replaced by the exact interval and the search of phase 76 (version 0.64); `retryAfter` is read in `UsageService` and used by the search only.
- `UsageService` is one loop, a daemon thread, that takes messages from a queue or waits until the next request is due; each public method hands it a closure and waits for the answer. A request runs on a thread of its own and hands its outcome back as a message. The state, the interval and what the countdown is counted from are published by the loop and read without asking it. A request is given up through its `Cancel`: `UsageClient` sends with `sendAsync` and cancels the answer, `ClaudeTokenProvider` ends its waiting for the credential. No lock with a condition, no interrupt (phase 88).
- The server answers on virtual threads and is stopped with `stop(0)`. Static files come from the class path under `/web`.
- JSON is read and written by `org.example.json`; an answer of the API is built by the `toJson()` of its record, key by key. The settings are one record (`Settings`), read strictly, key by key, by `SettingsStore`; a value that is not valid has its default and the other keys are kept.
- An amount is an `Amount` from the response to the row, the history and the API; `java.math` is not used. The fill of a cost bar is the one `double`, made from the exact share.
- A failure is named by `Failure.of`, and logged as one line by handing what was thrown to the log (`LOG.warning("Could not read the log", e)`). The masking is `Redaction`, applied where an entry is written.
- `claude` and what it started are ended by `ProcessTree`: `taskkill /T /F` on Windows, `pgrep -P` and `kill -9` elsewhere.
- Tests that read the sources hold some of this in place: `FailureTest` (nothing but `Failure` reads the text of an exception), `CancelTest` (nothing interrupts a thread or holds a lock with a condition), and the frontend's check that the page calculates nothing.

## Open points found by the audit

Differences between the code and the requirements found by reading the one against the other (version 0.21). The first three were fixed in version 0.22; the rest need a decision, and none of them has been changed.

1. **Fixed (0.22).** The history panel was read again only when `fetched_at` changed, so a failed refresh did not appear until the next success. The status now carries `historyStamp`, the history file's size and time, and the page reads the history again when it changes.
2. **Fixed (0.22).** An optional item of the row left out when it had no value made the row change width. With its switch on it now stays in the row, empty and `visibility: hidden` (`.delta.empty`), with the min-width it already had; with the switch off it is not there.
3. **Fixed (0.22).** The log's start line was `#e6e6e6` in the dark theme too. It is `--mark`: `#e6e6e6`, and `#30363d` in the dark block.
4. `/api/config` reports the configured interval, while `/api/settings` and the row report the one in force; `docs/api.md` and the requirements now say so, but the page needs both for different things.
5. The window's lost-contact line is not logged, which the requirements now say; a program that is reachable only by its window has nothing to log it with, so this may be right.
6. `duration_ms` includes getting the token and the retry after a 401, so the first row of a run is long; the requirements now say so, but a measure of the request alone may be what is wanted.
7. The window checks the interval field itself (a whole number) before it asks the backend, though the requirements first said the backend refuses a bad value; both messages exist.
8. The first lines of the log are `Logging to ...`, the start line and the history path, in that order; the requirements now say so, replacing the earlier "the first thing logged is the start line".

### 36. The three defects of the audit

**Status: done (version 0.22).** `ApiHandler.historyStamp()` (file size and last-modified time, empty with no file) is in the status; `app.js` `historyStamp()` replaces `fetchedAt()` as the mark of what the open history shows. `renderOptional(id, wanted, item)` keeps the element
(`hidden` only when its switch is off) and sets `empty` when there is no value; the page's `OPTIONAL_CLASSES` keep the classes of the three optional items. The log's start-line gray is a style variable. Tests in `ApiTest`, `app.test.cjs` and `layout.test.cjs`; the
tests that triggered a history read by changing `fetched_at` now change the stamp, and the test that expected an item with no value to be hidden now expects it empty.

### 37. A configurable Anthropic URL, a run with no token, and a fake backend

**Status: done (version 0.23).** Requirements: Source data (the base URL), Authentication (`--fake-token`, `--fake-backend`), Command line (the four options and their messages), The usage request (the host in
the messages), Fake backend, Non-functional requirements (the startup lines, no task of its own). The work divided cleanly: the application learns a base URL and a way to skip the token, and it gains a server of
its own that answers on one. Nothing was added outside the application — no second program, no second command — so the whole change is reachable from the command line of the one that already exists.

Built as planned, with two departures. `AppRuntime.start` took an `Optional<URI>` of where the fetcher fetches rather than a record beside `options`: everything else the lines need is already in `options`, so a
second type earned nothing. And the fake backend is closed by `UsageApp.release()`, the `RunOnce` the window close and the process stop already share, rather than by `AppRuntime.close()`: `UsageApp` is what starts
it, because the fetcher it builds needs the address, so `UsageApp` is what owns it. Checked by running it: the four log lines appear in the order the requirements give, three readings climbed 187.39 → 188.76 →
190.13 in the history, a `POST /scenario` to `http-429-retry-after` drew `HTTP 429 ... retry-after 42`, the back-off answered `Next try in 42 s.`, a `failed` row was written, and closing the window released the
port. 62 tests were added; 622 pass.

- **The four options (`LaunchOptions`).** The record grows `Optional<URI> baseUrl`, `boolean fakeToken`, `boolean fakeBackend` and `Optional<Scenario> fakeScenario`, and `none()` gains the empties and falses.
  `--fake-token` and `--fake-backend` are handled with `-h` and `--help`, before the value handling, so they need no value; given one they are `<option> takes no value.` `--anthropic-url` and `--fake-scenario`
  join the interval names in the known-option check and take their values the same two ways. A private `baseUrl(String)` validates: `URI.create` in a try for `IllegalArgumentException`, then `isAbsolute()`, a
  scheme of `http` or `https` compared lower-cased, a host that is not null, and a null query and fragment; anything else is `--anthropic-url must be an absolute http or https URL, not "x".` and an empty value is
  `--anthropic-url needs a URL.` A single trailing slash is stripped on the way in, so appending the path cannot double it. Two checks run after the loop, where both sides are known: `--fake-backend` with
  `--anthropic-url` is `--fake-backend and --anthropic-url cannot both be given: each says where usage comes from.`, and `--fake-scenario` without `--fake-backend` is `--fake-scenario needs --fake-backend.` —
  refused rather than ignored, so a forgotten `--fake-backend` cannot leave a run quietly fetching real usage. `USAGE` gains a line for each option and the note that none is saved.
- **Appending the path (`UsageClient`).** `USAGE_PATH = "/api/oauth/usage"` and `DEFAULT_BASE_URL = URI.create("https://api.anthropic.com")` join `DEFAULT_URI`, and a static `usageUri(URI base)` returns
  `URI.create(base + USAGE_PATH)`. String concatenation, not `URI.resolve`, which would throw away a path prefix and so break `http://127.0.0.1:8080/proxy`. `DEFAULT_URI` becomes `usageUri(DEFAULT_BASE_URL)`,
  so the path is written once and the default keeps its name for the callers and tests that use it.
- **The messages and log lines need no change.** `UsageClient` already builds `Cannot reach ` from `uri.getHost()`, and the request and response log lines from `uri.getHost() + uri.getPath()`, so all three follow a
  configured URL on their own; the requirements now say so rather than naming `api.anthropic.com` as though it were fixed. This is the one part of the change that is only a test and a sentence.
- **The placeholder token (`org.example.token`).** `PlaceholderTokenProvider implements TokenProvider` returns a fixed string that is deliberately not shaped like a credential, so `Redaction` has nothing to mask
  and the log of a fake run stays readable; `acquire()` cannot fail. `UsageApp` picks the provider: `options.fakeToken() ? new PlaceholderTokenProvider() : ClaudeTokenProvider.create()`. Because
  `ClaudeTokenProvider.create()` is then never called, a fake run also skips the `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` refusal, which is right: no `claude` is started that could send the wrong credential.
  `options.fakeToken() || options.fakeBackend()` is the condition, in one method so the two ways of asking for it cannot drift apart.
- **Wiring (`UsageApp`).** The one construction site decides the base URL from the options: the fake backend's own address when `--fake-backend` started one, else `options.baseUrl()`, else
  `UsageClient.DEFAULT_BASE_URL`. It becomes `new UsageFetcher(tokenProvider, UsageClient.create(UsageClient.usageUri(base), responseLog))`. Nothing below it knows a URL, and nothing below it knows the
  backend it talks to is the same process.
- **The startup lines (`AppRuntime`).** After `Settings are stored in`: for a fake run, `The fake backend is in use: these readings are invented (--fake-backend, scenario <name>)`; then
  `Usage is fetched from <base URL>` on every run, configured or not, naming the fake backend's own address when that is where it fetches; then, when no token is obtained,
  `No token is obtained: a placeholder is sent instead (--fake-token)`, with `(--fake-backend)` in its place when that is what left it out. All are after the line the log panel marks as the start of a run, so its
  marking is untouched. `options` is already a parameter of `AppRuntime.start`; the base URL and whether a fake backend is in use have to reach it too, which is one more parameter or a small record beside `options`.
- **The fake backend (`org.example.fake`).** `FakeBackend` has no `main`: it is a class the application starts, with `start()` returning the instance and `baseUrl()` its address, and `close()` stopping it, so that
  the existing shutdown path can take it down with everything else (`RunOnce`, the shutdown hook). It uses `com.sun.net.httpserver.HttpServer` on the loopback address with port `0`, as `CredentialCapture` and
  `LocalWebServer` already do, so nothing has to be said about a port and a taken one cannot spoil a run. `GET /api/oauth/usage` is answered by the current scenario; every other path and method is 404. It is a
  server of its own on its own port, not a handler added to `LocalWebServer`, which keeps it out of the application's local API and out of reach of the page.
- **The scenarios (`Scenario`).** An enum with the names the command line uses, lower-case with hyphens: `normal`, `http-401`, `http-403`, `http-429`, `http-429-retry-after`, `http-500`, `not-json`, `empty`,
  `no-spend-no-windows`, `trailing-text`, `slow` (answers after 25 s, past the 20 s request limit) and `hang` (never answers). The enum is the one list: `LaunchOptions` validates against it and names its members in
  `--fake-scenario must be one of <the names>, not "x".`, so the message and the behaviour cannot disagree. The scenario is held in a volatile field, read on each request, and `POST /scenario` with the name as the
  body switches it while the application runs and logs `The fake backend scenario is now <name>`. A small `UsageDocument` builds the normal body in the shape Source data describes, with a counter that nudges the
  utilizations and the spend a little on each request so that the row, the history and the differences all have something to show.
- **Starting and stopping it (`UsageApp`, `AppRuntime`).** `--fake-backend` starts it before the fetcher is built, because the fetcher needs its address; it is closed on the one cleanup path the window close and the
  process stop already share, so a run that ends either way leaves no listening socket behind. It is never started for any other reason, which is the whole of the requirement that it is reachable only through its
  option: one `if` on `options.fakeBackend()`.
- **Tests.** `LaunchOptionsTest`: the URL plain and as `--anthropic-url=`, a trailing slash stripped, a path prefix kept, `http` and `https`, upper-case scheme; rejected when relative, without a host, with another
  scheme, with a query, with a fragment, empty, or given twice; `--fake-token` and `--fake-backend` on, off by default, each refused with a value; `--fake-backend` with `--anthropic-url` refused either way round;
  `--fake-scenario` without `--fake-backend` refused; an unknown scenario name refused with the names in the message; a known one accepted in any case; the help text names all four. `UsageClientTest`: `usageUri`
  for the default and for a base with a path; `Cannot reach <host>` and the request log line name a configured host, not `api.anthropic.com`. `PlaceholderTokenProviderTest`: a non-credential-shaped token, and
  `Redaction` leaves it alone. `FakeBackendTest`: the normal body parses with the real `UsageParser` and differs between two requests; each error scenario answers its status, with `retry-after` where the name says
  so; the malformed ones produce the parse failures the requirements already name; another path and another method are 404; `POST /scenario` switches it; `close()` leaves nothing listening. One test end to end: a
  `UsageClient` pointed at a started `FakeBackend` with a `PlaceholderTokenProvider` yields a snapshot, which is the whole point of the facility. `slow` and `hang` are exercised against a client with shortened
  timeouts rather than by waiting 20 s.
- **Docs.** `README`: the four options, and the one command that runs the application against its own fake backend (`./gradlew run --args="--fake-backend"`). `docs/api.md` does not change (see the assumption).

*Assumed:* the base URL is not reported by `/api/config` and nothing in the window mentions it or the fake backend, since no part of the page needs it and the log already records where a reading came from; a fake run
skips the environment-variable refusal, as above; the fake backend's port is always the operating system's choice, there being no reason to name one now that nothing outside the process has to find it; the scenario is
switched by a `POST` to the fake server rather than by a header or query on the usage request, so that what the application sends stays exactly what it sends against the real endpoint; and the fake backend is a real
loopback server rather than a `UsageSource` fitted in place of the client, which costs a socket and buys the real HTTP path — the statuses, the timeouts and the redirect refusal — being what a fake run exercises.

### 40. Start Claude Code on Windows, where it is a batch file

**Status: done (version 0.28).** Requirement: Getting the token (the command on Windows).

*Why.* The first run on Windows failed at the token: `Claude Code was found but could not be started: Cannot run program "claude":
CreateProcess error=2`. npm installs Claude Code as `claude.cmd`, with a script called just `claude` beside it for other shells.
`ProcessBuilder` hands the name to `CreateProcess`, which tries no ending but `.exe` and does not run batch files; and the check
that tells "not found" from "could not be started" looked for a file of exactly the name `claude`, found the shell script, and so
said the wrong one of the two.

*Built.* `ClaudeTokenProvider.windowsCommand` resolves a bare command name through the `PATH` and the endings of `PATHEXT`, as a
shell does, and returns the full name of the file, behind `cmd.exe /c` when it is a `.cmd` or a `.bat`; `launch` uses it when the
operating system is Windows, and `notStarted` asks it whether the command was found. The file is named by the case it really has. It
is a pure function of the command, the `PATH` and `PATHEXT`, so its tests run on any system: the npm layout, a program before a
batch file on the `PATH`, a name found nowhere, and a command that has a directory. Checked by hand on Windows: it resolved to
`cmd.exe /c ...\npm\claude.cmd` and `claude --version` answered through it. The capture of the token itself has not been run on
Windows: `claude` and what it starts are killed as before, by way of the descendants of the process, which now begins at `cmd.exe`.

### 42. Only the spend is read

**Status: done (version 0.30).** Requirement: Source data. Phases 38, 39 and 41 are undone as far as they told two formats of the
response apart, and their entries are removed from this plan. `UsageParser` reads `spend` and nothing else: a response with a
`spend` object is a reading, with spend or with none, whatever else it carries, and one without is refused with
`The usage response has no "spend" object; its format may have changed.` Nothing shows a plan window; the status keeps
`usage.windows`, always empty, so that a reading has the shape `java-aip` prints. The fake backend's document stays the real
response key for key. The fixture `usage-windows.json` and the tests of the refusal are deleted.

### 43. One green in the row, and seconds without a space

**Status: done (version 0.31); how it looks is for a person to judge.** Requirement: Compact window (One green; the order of the
row) and How the figures are written.

*Decided by the person:* one fixed green for the percentage, the amounts and the two change items, with no colour by severity; no
currency symbol on the change in the amount used, ever (first asked for, then withdrawn); and no space before the `s` in the
three seconds figures of the row, with the history and the messages left as they are.

- **The texts.** `Formatting.rowSeconds` writes seconds without the space, and `StatusDisplay` uses it for the countdown, the
  interval and the time since the previous reading, the last made from `change.deltaTime()` and never below `0s`.
  `Formatting.seconds` keeps the space and is now the history's alone; `DeltaBody`, and with it the `change` of the status and
  the cells of the history, is not touched. The change in the amount used keeps its text.
- **The colour.** In `app.css` one rule gives `.delta-used`, `.delta-time` and `.spend` `var(--ok)`. The four `.sev-` rules and
  the `--warn` colour, which nothing else used, are deleted, and `app.js` no longer puts a class on `spend`. `severityKind` had
  no other use and is gone from `SpendView`, from `Formatting` with its table of severity words, from `view.js` and from
  `api.md`; `severityText` stays, and the tooltip of the percentage still names the severity.
- **Dimming.** `.stale .delta-used` and `.stale .delta-time` are dimmed with the time and the amounts. This is new: the change
  items were not dimmed before, and now that they are the green of the amounts they go with them.
- **The least widths.** `.countdown` 3.5ch, `.delta-interval` 4ch, `.delta-time` 5.5ch, each half a character less than
  before; `.delta-used` as it was.
- **Tests.** `FormattingTest`: `rowSeconds`, and the test of the severity kinds deleted. `StatusDisplayTest` and `ApiTest`:
  the three texts of the row without the space, the cells of the history still with it. `app.test.cjs`: no class on the amounts
  for a warning, a critical or an unknown severity, and the tooltip still naming it. `layout.test.cjs`: the one green rule and its
  two colours, no `.sev-` rule and no amber, the time, countdown and interval without a colour, the red of a back-off kept, the
  dimming, the widths.
- **Docs.** The requirements (the section that specified this is worked into Compact window, How the figures are written and
  Details of the behaviour), `api.md`.

*Assumed, easy to change:* the severity still named in the tooltip of the percentage; the two change items dimmed with old
figures; the dash of a missing amount green.

**Not checked by a test.** How the green of five figures reads beside the black of the time and the countdown, in both themes,
and whether the new least widths still keep the row from moving: for a person to look at in the window.

### 44. Twelve named elements, a checkbox for each with a preview, and gray boxes at the start

**Status: done (version 0.32); how it looks is for a person to judge.** Requirement: The elements of the row (and Compact window,
Settings, Countdown).

*Decided by the person:* all twelve elements of the row (the time, the percentage, the amount used, the budget, the refresh
button, the countdown, the interval, the change in the amount used, the time since the previous reading, and the log, history
and error log buttons) can be hidden, the gear alone staying; a click on a
checkbox is a preview in the row, kept by Apply and taken back by Cancel; the gray box is for the start only; the amount used and
the budget have a checkbox each.

- **Settings.** Five switches, `showTime`, `showUsed`, `showLimit`, `showRefresh` and `showCountdown`, at the end of the
  `Settings` record and of the file, on by default. The sixteen-argument constructor stays, with the five on, so the positional
  calls of the tests stand; every `with...` method carries the five, and `withRowElements` sets them. `POST /api/settings`
  requires them like every other key, checked last.
- **The status.** `StatusDisplay.Show` has the five flags. The view has `loading`, true with no reading and no failure, when the
  placeholder is now `null` and no longer `Loading…`; and `variants`, the time in both formats and the two amounts with and
  without the symbol, so that the page can preview the time format and the symbol and still format nothing itself. An HTTP 429
  with the countdown hidden is no `countdownAlert` but a message line.
- **The row (`app.js`).** `render` takes its switches from the preview when there is one, else from the status; a switch the
  backend does not name counts as on. `figure` shows a figure or its gray box, `amount` picks the previewed form of an amount,
  and the slash between the amounts is an element of its own, `slash`, there only with both. `renderButtons` no longer shows
  every panel button while the settings are open.
- **The preview.** `previewNow` reads the main-view part of the form into `preview` at a `change` of any of its controls and after
  `Maximum view`, `Minimum view` and `Restore defaults`. `showPanelNamed` drops it, so Cancel, the gear and another panel give
  the row back; Apply keeps it until the next poll has the status that says the same.
- **The settings view (`index.html`).** The twelve checkboxes in the order of the row, each label with its tooltip as `title`;
  the time format and `Currency symbol` in a `line` with `Time` and `Amount used`. `Minimum view` keeps the five that used to
  be fixed.
- **The gray box (`app.css`).** The class `skeleton`: the muted gray at 0.33, 3 pixels of rounding, transparent text of one
  no-break space for its height, and a least width for the time, the percentage and the two amounts; the countdown and the two
  changes keep the widths they have.
- **Tests.** `StatusDisplayTest`: `loading`, `variants`, the five flags each by itself, the 429 with the countdown hidden.
  `SettingsStoreTest`: the five saved and read back, and an older file having them on. `ApiTest`: the five in the settings and in
  the status, and a missing one refused. `app.test.cjs`: every element taken away and brought back by its checkbox with nothing
  sent, the slash, the preview of the time format and of the symbol, Cancel and Apply, the three buttons filling the row at once,
  every element off by the backend, the gray boxes with their tooltips and their going. `layout.test.cjs`: the twelve checkboxes
  and their texts, their order against the order of the row, the tooltips, the two lines with a property, the gray box.
- **Docs.** The requirements (the section, and the passages on the row, the countdown, the settings and the start that it
  changes), `api.md`.

*Assumed, easy to change:* the names, in particular `Log button`, `History button` and `Error log button` for what the checkboxes
called icons; the texts of the tooltips; one `Currency symbol` for both amounts; a 429 as a message line when the countdown is
hidden; what `Minimum view` keeps; that the panel buttons follow their switches while the settings are open; the gray and the
widths of the boxes.

**Not checked by a test.** How the settings view looks with its two lines that hold a property, how the gray boxes look, and
whether the row is steady while checkboxes are clicked: for a person to look at in the window. A start against the fake backend
loaded the page and fitted the window, which is all that was seen.

### 45 to 49. Debug mode, the title bar, the window on top, the request duration: how they are built

Requirement: Debug mode, a section that was named for all five when it was written and loses a piece with each
phase. It is five pieces that do not
depend on one another. *Decided by the person:* they are built **one at a time, each a phase and a version of its own**, tested
and committed by itself, so that each can be looked at in the window before the next is begun. Smallest first, the two with risk
last:

| Phase | Version | Piece |
| --- | --- | --- |
| 45 | 0.33 | The settings view: no `Maximum view` and `Minimum view`, a least width, leaving saves (done) |
| 46 | 0.34 | The request duration, a thirteenth element (done) |
| 47 | 0.35 | The window to the front at startup, and `Stay on top` (done) |
| 48 | 0.36 | The title bar, on and off while the program runs (done) |
| 49 | 0.37 | Debug mode: `--debug`, the debug view, the fake backend switched at runtime, the red border (done) |

Each phase ended the same way: both suites green, the passages of the requirements it made true moved out of the section
"specified, not yet built" into the description of the program, `api.md` where the API changed, the version, a commit. With
phase 49 done nothing in the requirements is specified and not built.

### 45. The settings view: two buttons less, a least width, and leaving saves

**Status: done (version 0.33); how the view looks is for a person to judge.** Requirement: Settings (Leaving the settings by a
button of the row saves them; The settings have a least width).

- **The two buttons.** `settings-maximum` and `settings-minimum` are gone from `index.html`, with `setMainView`, `MINIMUM` and
  their listeners in `app.js` and the rule for the buttons inside a group.
- **The least width.** While the settings are shown `contentSize` reports the larger of the row's width and `settingsWidth()`.
  Nothing in the view wraps (`white-space: nowrap` on `.settings`, no `flex-wrap` on its labels, lines and buttons), so an
  element's own width is the width it needs. The buttons, `settings-buttons`, are `width: max-content`. The form is
  `width: max-content` with `min-width: 100%`, so that its boxes still reach across a wider window; for the moment of the
  measuring `settingsWidth` takes the `min-width` off by an inline style and puts it back, which makes the answer independent
  of how wide the window happens to be. Without that the window, once wide, would never become narrower again. Each of the two is
  given the room at its left again at its right. Nothing changed in the host: `WindowFit` takes the width as any other.
- **Leaving saves.** `togglePanel` is asynchronous now. With the settings open it asks `settingsChanged`, which compares the
  form with what the backend gave, and when something differs `saveSettings`, the half of the old `applySettings` that checks
  the interval field and posts; only when that returned true does `leaveSettings` show the other panel or what the settings
  replaced. `applySettings` is `saveSettings` and `leaveSettings`; `cancelSettings` calls `leaveSettings` alone and is the
  one path that sends nothing. After a save the preview is kept until the next poll, as after Apply.
- **Tests.** `app.test.cjs`: the gear saves and closes; each of the three panel buttons saves and shows its panel; nothing
  changed sends nothing; a refused value and an interval that is no number keep the view open and the other panel shut; Cancel
  sends nothing and gives the row back; the row keeps the preview after a save on leaving; the width reported for the settings,
  from the form or from the buttons, never less than the row, and the row's again afterwards. The four tests of the two buttons
  are deleted. `layout.test.cjs`: no such buttons, nothing that wraps, the three buttons in one element.
- **Seen in a real window.** `PanelProbe` opens the settings in a real web view, switches every element of the row off so that
  the row is the gear alone, makes the window as wide as the page asks, and reports: `PanelWindowTest` checks that the settings
  ask for more than that row, the same whatever the window's width, that no text wraps, that the three buttons have the same
  top, that nothing sticks out at the right, and that the width is the row's again afterwards. It passed. The test runs only
  when asked for (`-Daipusage.windows=true`).
- **Repaired on the way.** That test had gone stale without anyone noticing, since it is not in the ordinary run: it expected the
  history with dates, which are off by default since the date setting, and the log newest first from lines that had no time, which
  the page takes for one entry. Its expectations and the probe's lines are put right, and the probe prints only the beginning of
  the log, since its longer lines filled the pipe the test reads at the end and the probe hung.

*Assumed, easy to change:* `Apply` always sends, also when nothing was changed, as before.

### 46. The request duration, a thirteenth element

**Status: done (version 0.34); how it looks in the row is for a person to judge.** Requirement: The elements of the row (The
request duration).

- **Where the figure comes from.** `AppRuntime.recording` measured the request already, for the `duration_ms` column of the
  history. It now measures once and gives the one number to both: the history row, and an `AtomicLong` the runtime holds, which
  is negative until a request has ended. A request cut short because the program is stopping sets neither. The plan had the
  figure go into `UsageState`; it is kept beside the service instead, because the measuring is done in the wrapper round the
  fetcher, where the history is written, and so the row and the file cannot differ.
- **To the row.** `ApiHandler` is given a `LongSupplier` for it and hands `StatusDisplay.build` an `OptionalLong`;
  `Formatting.duration` writes `87ms` and `1s034ms`; the view has a `duration` tip and `show.duration`.
- **The setting.** `showDuration`, off by default, at the end of the `Settings` record, the file and the settings API, required
  in a `POST` like every key and checked last. The sixteen-argument constructor leaves it off; `withShowDuration` sets it.
- **The page.** An element `duration` after `delta-time`, drawn by `renderOptional` like the two changes, so it has their
  behaviour: its room kept when it has no value, a gray box at the start. The class `delta-duration` is in the rule of the one
  green and in the rule that dims old figures, with a least width of 7.5 characters. A checkbox `Request duration` after
  `Time since the previous reading`, in `MAIN_FLAGS`, so that it is previewed and saved with the others.
- **Tests.** `FormattingTest` (0, 87, 999, 1,000, 1,034, 12,005, more than a minute, a negative number); `StatusDisplayTest`
  (none before a request has ended, the tip, the flag, a failed request); `SettingsStoreTest` (off by default, saved and read
  back, the key in the file); `ApiTest` (the tip in a status after a request, the switch through the settings, and that the
  figure of the row is the one in the last line of the history file); `app.test.cjs` (off unless the backend says so, shown as
  written, its room kept when empty, its checkbox previewed, its gray box and tooltip at the start); `layout.test.cjs` (the
  last of the figures, its checkbox in the order of the row, the green, the dimming, the width).

*Assumed, easy to change:* the least width, which is that of `1s034ms`; a request of ten seconds or more makes the row wider
for as long as it shows.

### 47. The window to the front at startup, and `Stay on top`

**Status: done (version 0.35).** Requirement: Settings (The window on top).

- **To the front at startup.** `UsageApp.bringToFront`, after `stage.show()`: the window is put on top, asked to the front and
  given the focus, and 400 milliseconds later left to the setting again. A window that is on top is in front, which asking alone
  does not always achieve on Windows.
- **The setting.** `stayOnTop`, off by default, at the end of `Settings`, the file and the settings API, required in a `POST`
  and checked last. A checkbox `Stay on top` in a new group `Window` of the settings view, after `Main view`; it is in
  `SETTING_FLAGS` and not in `MAIN_FLAGS`, so it is saved with the others and not previewed.
- **The host follows the setting.** `followStayOnTop` runs on the tick that fits the window, every 150 milliseconds: it reads
  `runtime.settings().current().stayOnTop()` and sets the stage when the two differ, with a line in the log. The plan had it
  listen through `IntervalSettings.onChange`; that holds one listener, which the logging of the response has, and a setting
  that changes a few times in a run is as well read as listened for.
- **Tests.** `SettingsStoreTest` (off by default, saved and read back, the key in the file); `ApiTest` (applied, given back,
  and in `settings().current()`, where the window reads it); `app.test.cjs` (sent with the others, no `change` listener);
  `layout.test.cjs` (the group `Window` between `Main view` and `History view`, the checkbox with its tooltip).
- **Seen in a real window.** The installed program was started in a folder of its own and asked through the system: at startup
  its window was the foreground window and not on top; with the setting applied it was on top within a second; with the setting
  off again it was not. That check was done by hand with a script and is not in the suite.

### 48. The title bar, on and off while the program runs

**Status: done (version 0.36); the flicker of the exchange is for a person to judge.** Requirement: Settings (The title bar).

- **Tried first, and it held.** `TitleBarProbe` opens a real window with the application's page, sets a mark in the page, and
  exchanges the window for one without a title bar and back. The first run answered every question of the plan: the scene
  with its `WebView` moves to the new stage as it is (`Stage.setScene` takes it from the old one), the mark is still there
  and the page was loaded once, the content is at the same place on the screen and of the same size, there is one window
  throughout, and the program ends only when the last window is closed. No `Platform.setImplicitExit(false)` was needed: the
  new stage is shown before the old one is closed.
- **`StageSwap.replace`.** Makes a stage in the other style with the title, the icons, the always-on-top state and the
  resizable flag of the old one, invisible until it is in place; sets the scene on it and shows it; then, now that the frame
  of the new window is known, moves it so that the content is where it was and sizes it to the content it had; makes it
  visible and closes the old one.
- **The host.** `UsageApp` keeps the stage in a field, since it is another after an exchange, and every method reads the field.
  `followTitleBar`, on the tick that fits the window: the title bar is wanted when the setting is on or the page named an open
  panel, and when the stage is not so, it is exchanged. Afterwards the frame's size is forgotten and measured again by `resize`
  on a later tick, and the size the page asked for is applied again, with its limits. A program started with the setting off
  gives its first stage the style without a title bar before showing it, so there is no exchange at startup.
- **A mistake found by the real window.** The first version measured the new frame at once, before the new window had been laid
  out, and got it wrong: the content shrank by the same amount at every exchange, 335 by 34, then 251 by 18, then 166 by 2,
  and then JavaFX refused a negative size. None of the tests showed it; asking the system for the real window's size did. The
  measuring is now left to `resize`, as for the first window. With it, the size the window may be shrunk to with a panel open
  is kept as the content's and the frame added when it is used, since the frame is another from one exchange to the next.
- **The page names every panel.** The size answer of the settings ends `,settings` now, as those of the other panels end with
  their name; `WindowFit` takes `settings` and `debug` (for phase 49) and still passes over a name it does not know.
- **The setting.** `showTitleBar`, on by default, at the end of `Settings`, the file and the settings API. The checkbox
  `Title bar` is the first of the group `Window`, in `SETTING_FLAGS` and not previewed.
- **Tests.** `SettingsStoreTest`, `ApiTest` (on by default, also for an older file; applied, given back, read by the window);
  `WindowFitTest` (the two new names; an unknown one is no size); `app.test.cjs` (sent with the others, not previewed; every
  panel names itself and none is named with none open); `layout.test.cjs` (the checkbox, before `Stay on top`).
- **Seen in real windows.** `TitleBarWindowTest`, on request, runs two probes. `TitleBarProbe`, above. `UsageAppProbe` runs
  the application itself against its fake backend with the title bar switched off in the settings file and presses the buttons
  of the page: no title bar at the start; a title bar with the settings, the history, the log and the error log open; none
  again after each is closed, with the row at the size it had; one window at every step; and the program ends when the window
  is closed. Both pass. By hand, with a script that asks the system: the setting switched through the API while the installed
  program ran took the title bar away and gave it back with the content at the same place, the window still on top when it was
  set to be, one window, the page loaded once; and a second start with the setting off opened without a title bar.

*Assumed, easy to change:* a window that is being held on top for the moment of startup is not exchanged until that moment is
over.

**Not checked by a test.** How the exchange looks: the new window is shown before the old one is closed, and is invisible until
it is in place, so there should be little to see, but that is for a person to judge. Whether the window keeps the keyboard focus
through an exchange was not looked at.

### 49. Debug mode

**Status: done (version 0.37); the border and the debug view are for a person to judge.** Requirement: Debug mode, Command line,
Fake backend, Authentication.

- **The command line.** `LaunchOptions` is `--debug`, `--anthropic-url` and help. `--fake-backend`, `--fake-scenario`,
  `--fake-token`, `--usage-interval` and `--poll-interval` are gone with their messages, and are unknown options now.
- **The debug settings.** `fakeBackend`, `fakeScenario` and `fakeToken` at the end of `Settings` and of the file, the scenario
  by its name; a name the program does not know is logged and passed over. They have `GET` and `POST /api/debug`, a `404`
  without `--debug`; `/api/settings` neither gives nor takes them, and what it applies keeps the three as they are.
  `/api/config` says `debug`, the status says `fake`.
- **Where usage comes from.** `UsageSources` is what the API asks: debug or not, fake or not, the history file in use, the
  latest duration, and `applyDebug`. `AppRuntime` implements it. It is given a `Factory` that opens a `Source` (a fetcher,
  whether it is the fake backend, the base URL for the log, and what to close with it); `UsageApp` supplies the real one, which
  starts a `FakeBackend` or builds the client for the base URL with a real or a placeholder token. Without `--debug` the factory
  is asked once, for the real source with a real token. The fetcher-taking `start` methods stay for the tests, as a factory that
  hands the same fetcher to every source.
- **Switching.** `applyDebug` opens the new source first, so that one that cannot be opened changes nothing; saves the settings;
  then makes the new source the one in use, closes the old, forgets the duration, logs where usage now comes from, and calls
  `UsageService.restart` with the newest reading of the new source's own history.
- **`UsageService.restart`.** Counts a generation, replaces the reading, and asks for a request now. A request under way is
  interrupted, as at shutdown; when it returns, `run` sees that the generation is not the one it began with, throws the answer
  away without logging or recording it as a failure, clears the interruption and asks for the request to the new source.
  `AppRuntime.fetchAndRecord` writes a row only when the source it asked is still the one in use.
- **Two history files.** `AppFiles.fakeHistory()` is `java-aip-usage.fake.csv` beside the other. The runtime holds a
  `UsageHistory` for each; `ApiHandler` reads the history, the change and the stamp from `sources.history()`, so the panel
  and the change items follow the source. `LatestChangeCache` already keys on the file.
- **The page.** `debug-button` after the gear, shown when `/api/config` says debug, yellow by the class `debug`, which
  `labelButtons` keeps. `debug-view` in the panel area, filled by `loadDebug`, the scenarios made into options from what the
  backend names. The settings and the debug settings are both `FORMS`: `togglePanel` saves whichever is open when it is left
  by a button of the row, `leaveForm` is the old `leaveSettings`, and `formWidth` measures either. The size answer of the debug
  view ends `,debug`, so the host gives it the title bar.
- **The red border.** `#app.fake`: three pixels of `var(--bad)`, `box-sizing: border-box`, and an animation of the border
  colour to `Canvas` and back in four seconds. The class follows `fake` of the status. `contentSize` adds six pixels to the
  width and the height of the row while it is there.
- **A pixel found by the real window.** With a title bar the window's content came out a pixel narrower than the page asked
  for, because a frame is not a whole number of pixels wide at every display scale; nobody had seen it, but it cut a pixel off
  the red border. `resize` rounds the window's size up now.
- **Tests.** `LaunchOptionsTest` rewritten. `SettingsStoreTest`: the three settings, the scenario by name, an unknown one.
  `UsageServiceTest`: a restart shows the reading it is given and fetches at once, begins with none, gives up a request under
  way without a failure, and the service goes on. `ApiTest`: no debug settings without `--debug` and the file's ignored; the
  settings and the scenarios in debug mode; the fake backend on and off, saved; the two history files kept apart, and the panel
  showing the one in use; the reading of the other source not kept; a start with the fake backend saved; the checks of the three
  keys; the ordinary settings leaving them alone; a source that cannot be opened; the source that is left closed. The tests of
  the interval options are deleted or rewritten for the settings file. `app.test.cjs` and `layout.test.cjs`: the button, the
  view, Apply and Cancel, saving on leaving both ways between the two views, a failed save, the border class and the larger size.
- **Seen in a real window.** `UsageAppProbe` now starts the application with `--debug`, the fake backend saved as on, and an
  address nothing listens at. `TitleBarWindowTest` checks, from what the real page reports: the border three pixels wide and the
  figures of the fake backend at the start; the debug button; the debug view named and with the title bar; with the fake
  backend switched off in the view, no border and no figures, the debug button still there; switched on again by leaving the
  view through its own button, the border and the figures back; the invented readings in their own file and nothing but failed
  rows in the real one; the page loaded once through all of it. It passes.
- **Docs.** The requirements (Debug mode is the description now; Command line rewritten; Fake backend, Authentication, Refresh
  behavior and the log's lines where they named the options), `api.md` (`/api/debug`, `debug`, `fake`), the README.

*Left as it was:* `IntervalSettings` can still be given an interval that overrides the file's for a run. Nothing gives it one
any more; the tests of that mechanism still pass, and it was not taken out.

**Not checked by a test.** How the border looks as it fades, and whether it is hard to overlook; the yellow of the button on
both themes; the debug view. What happens with a real account when the fake backend is switched off was not tried: the probe
used an address nothing listens at.

## Validation

What is tested, what stays outside the automated suite and what a person checks by
eye on macOS is in the requirements (Testing and verification). The tests of each
phase are named in that phase.

At version 0.77 `./gradlew build` and `./gradlew frontendTest` pass, on Windows. Two things have not been checked:

- **Phase 87 on macOS.** That `pgrep` and `kill` end `claude` and what it started there:
  `./gradlew test --tests '*ClaudeTokenProviderTest' --tests '*ProcessTreeTest'`, on a Mac.
- **Phase 89 in the window.** The placeholder as a green frame was looked at as a headless browser draws it, on both
  themes, not in the JavaFX window; it shows there for a moment at startup.

## Related repositories

Use sibling repositories as references, not runtime dependencies:

- [`java-aip`](../../java-aip) for endpoint behavior, response mapping, and
  token-related implementation patterns.
- [`java-claude-login`](../../java-claude-login) and
  [`java-claude-code-fetch-oauth-token`](../../java-claude-code-fetch-oauth-token)
  for authentication and token acquisition.
- [`java-interceptor`](../../java-interceptor) for request/response
  interception patterns if needed by the chosen token flow.
- [`doc-and-ref-repos`](../../doc-and-ref-repos) for relevant specifications
  and reference material.
