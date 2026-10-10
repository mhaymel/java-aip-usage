# Implementation plan

How the first version was built, phase by phase: the architecture, the classes,
the reasons for a choice, what went wrong on the way, and what the tests cover.

What the program must do is in [requirements.md](requirements.md), and only there.
This document does not restate it: where a phase changed what the person sees, it
names the section of the requirements instead. The local API is in [api.md](api.md).

## Scope

The first version is a Java 25 application for macOS, started with `./gradlew run`.
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

Suggested backend boundaries:

| Component | Responsibility |
| --- | --- |
| `UsageSnapshot` and related value types | Represent the timestamp and the optional spend details of a reading in the usage-based format. |
| `TokenProvider` | Acquire and refresh an OAuth token using the selected sibling-repo flow. Keep tokens in memory; do not log them. |
| `UsageClient` | Call the Anthropic usage endpoint with the bearer token, decode the response, and distinguish HTTP 401 from other failures. |
| `UsageService` | Own the current snapshot, refresh interval, last error/stale state, and non-overlapping scheduled refreshes. |
| `Logging` | Configure console and append-mode output to `java-aip-usage.log` in the project root; record lifecycle, refresh, and sanitized HTTP diagnostic events without credentials. |
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

## Implementation notes, as built

How the requirements are met today (version 0.28). What these mechanisms make the
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

**Backend**
- Startup order: settings load, history `latest()` restore, listeners, `ResponseLog`, web server, then `service.start()`. `Main` is not an `Application` so that an IDE `main` works from the class path;
  `Logging` must be installed before `UsageApp` is loaded. One JVM shutdown hook and `RunOnce` make the window close and the process stop share one cleanup; `ShutdownSafeLogManager` keeps the stop lines
  from being lost.
- The history is appended with `CREATE`/`APPEND`. `/api/history` reads the whole file on every call, `/api/status` only when `LatestChangeCache` sees a changed size or time.
- The back-off is kept in nanoseconds: `hold = max(min(max(hold, interval) * 2, 5 min), retryAfter)`, eased by `hold / 8 * 7`; `retryAfter` is capped at an hour in `UsageService`.
- The server answers on virtual threads and is stopped with `stop(0)`; the refresh thread is a daemon. Static files come from the class path under `/web`.
- The masking is `Redaction`; the settings are one record (`Settings`), read leniently key by key by `SettingsStore`.

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

### 38. Two named formats, the seat-based one refused, and a fake backend that sends only the usage-based one

**Status: done (version 0.26); not seen in the window, and no real seat-based account has been tried.** Requirements: Source data,
Display requirements, The row, Usage history, The usage request, Fake backend, Testing and verification.

*Why.* A run against the fake backend showed spend and four plan windows in one row. No real account answers like that, the window
display had only ever met fixtures, and the format a machine gets depends on the subscription it is logged in with, not on its
operating system. Showing a format that has never been verified against a real account is worse than saying it is not supported.

*Decided by the person:* the names, **usage-based format** and **seat-based format**, after Anthropic's words for the two kinds of
plan (Anthropic has no name for the two shapes of the response; its help pages speak of usage-based plans with a spend limit, and of
Pro, Max and seat-based plans with a five-hour session limit and a weekly limit); that a response which carries plan windows is
refused as a failed refresh, with spend beside the windows or without; that the refusal is recorded like any failed refresh, a
`failed` history row at every refresh included; that the code which displayed plan windows is deleted, not kept for later; that the
fake backend's document is the real usage-based response, key for key; and that the fake backend gets no scenario for the refusal.

- **The refusal (`UsageParser`).** After the checks that were there (not JSON, not an object, enabled spend without amounts, neither
  spend nor windows), a document in which a plan window is found throws `UsageParseException` with `SEAT_BASED_REFUSED`, whether
  `spend` was read or not. The window scan became `hasPlanWindows`: the same rule of shape, `extra_usage` and `null` keys passed
  over, now only to recognise the format. A `UsageParseException` was already a failed refresh that keeps the token, starts no
  back-off and reaches the message line, the error log, the log and a `failed` history row, so nothing above the parser changed.
- **What was deleted.** `UsageWindow`; the `windows` of `UsageSnapshot`, which is now a time and a spend; `StatusDisplay.WindowView`
  and the `windows` member of the display; `Formatting.span` and `Formatting.percent`; the `windows` element of the page, its
  styles and `renderWindows`; and the tests of all of them. The status' `usage.windows` stays, always an empty list, so that the
  reading keeps the shape `java-aip` prints. The requirements lost the passages on where plan windows go in the row and how their
  figures are written; how the seat-based format is to be shown is to be decided when it is supported.
- **The fake document (`UsageDocument`).** The body is the usage-based response of `usage-credits.json`: the five window keys and
  two invented ones `null`, an `extra_usage` that repeats the spend (with a `utilization`), `limits`, `spend` as before (18602 +
  137 n of 100000 minor units, the percent, the severity), `member_dashboard_available` and `seven_day_breakdown`. The climbing
  utilizations and the reset times are gone. `trailing-text` and `slow` keep using it.
- **Tests.** `UsageParserTest`: `usage-windows.json` is refused with the message; spend beside one window is refused; a window at
  exactly 0, one with a `null` reset time and one with no reset time are each enough; what is no plan window (null keys, a list, an
  object without a numeric utilization, `extra_usage`) does not make it the seat-based format; windows with no `spend` object draw
  the refusal and not the "neither" message. `FakeBackendTest`: the document has the awkward parts of the real one, and a thousand
  readings in a row are never refused. `ApiTest`: a refused response is a failed refresh with the message, leaves the reading that
  was there on show and old, sends no window, is no 429, adds an error log entry and a `failed` history row. The tests that used a
  plan reading as "a reading with no amounts" use an account that reports no usage instead. Page tests: no element and no style for
  a plan window, and a status that carried one is ignored.
- **Docs.** `api.md` (`usage.windows` always empty, no `display.windows`, the refusal), README (the two formats, what is not
  supported), the fixtures README (`usage-windows.json` is the fixture of the refusal).

*Assumed, easy to change:* the wording of the message; that "carries plan windows" means the scan finds at least one, so the `null`
keys of a usage-based response do not count; and that an account that reports nothing at all (`No usage reported`) stays a valid
reading and is not refused.

### 39. Both formats: the seat-based one supported, one format to a history file, a tab for each in the settings

**Status: done (version 0.27); not seen in the window, and built on a made-up response.** Requirement: The seat-based format (and
Source data, Display requirements, Command line, Fake backend). It undoes the refusal of phase 38 and brings back, in another shape,
some of what that phase deleted: a reading can hold limits again, though two named ones and not a list of windows.

- **The model.** `UsageFormat` (`usage-based`, `seat-based`, with the text the log, the command line and the API write);
  `PlanLimits` with its `Limit` (a utilization and the reset time as sent); and `UsageSnapshot`, which now holds a spend or the
  limits, never both, and says its `format()`, `null` for a reading that reports nothing. The two-argument constructor stays for the
  usage-based reading, and a seat-based one is made with `UsageSnapshot.seatBased`: two two-argument constructors would be ambiguous
  for a `null`.
- **The parser.** `hasPlanWindows` decides the format, as it decided the refusal. A seat-based response is read from `five_hour` and
  `seven_day` alone and refused with `SEAT_BASED_INCOMPLETE` if either is missing; its `spend` is not read at all, so a spend
  that could not be read cannot fail a reading in which it is ignored.
- **The history file (`UsageHistory`).** `content()` reads what the file holds from its first line: empty, a format, or a header
  that is neither's. `makeRoomFor` then leaves it (upgrading an old usage-based header), empties it (a header and no rows), or sets it
  aside with `setAside`, a move to `<name>.<yyyy.MM.dd-HH.mm.ss>.csv` that never replaces a file, with the two log lines. The time
  of the name comes from a `Clock`, a constructor parameter so that a test knows the name. A failed row is written in the shape of
  the file, or of `lastFormat` when there is none. `latest()` restores a reading of either format.
- **Reading it back.** `HistoryReader` tells the format from the header and gives `Table.format()`; a seat-based row has eight
  fields and its status in the sixth (`statusIndex`). `HistoryDeltas.Delta` has a second figure, `other`, for the weekly limit, and
  `isZero(format)` says what a zero usage line is in each format. The existing one-format entry points stay as overloads.
- **The display.** `Formatting` has `percent`, `span`, `signedPoints` and `resetCell` (the first two came back from phase 38).
  `StatusDisplay` sends `format` and a `seat` block with two `LimitView`s, and puts the change of both percentages into the one
  `deltaUsed` item, whose `show` flag follows the switch of the format in force, so the page needed no new flag. `ApiHandler`
  builds the seat-based history in `seatHistory`, with `kinds` naming what each column is, sends the two limits as
  `usage.windows`, and says the format in force with the settings (`formatInForce`).
- **Settings.** Four switches, `seatShowResets`, `seatShowDelta`, `seatHistoryResets` and `seatHistoryDelta`, at the end of the
  record, with the sixteen-argument constructor kept so that the many positional calls of the tests stand; `withSeat` sets them.
- **The fake backend.** `--fake-format` in `LaunchOptions` (an old seven-argument constructor kept), `FakeBackend.start(scenario,
  format)`, `POST /format`, and `UsageDocument.nextSeatBased`, whose limits climb and are set back when their reset time is reached.
- **The page.** A `seat` group in the strip beside `spend`, filled by `renderLimit`. The seat-based table has columns that come and
  go with three switches, so its lines get their column widths inline, from `view.js`'s map of the backend's `kinds`, and the class
  `seat-table`; the usage-based table keeps the `cols-N` rules of the style sheet. The settings have a `Format` group with two tab
  buttons and two panels; the four switches that exist in the usage-based format only moved from `Main view` and `History view` onto
  its tab. `showTab` shows one and marks the one in force.
- **Tests.** Parser: both formats told apart, spend ignored beside windows, the two limits only, the refusal of an incomplete
  response. History: the seat-based row and its local reset times, a failed row in each shape, the file set aside both ways round and
  for an unknown header, a header-only file replaced, never onto a file that is there, nothing moved by a failure or an empty reading,
  the reading restored at startup. Reader and deltas: the format from the header, eight fields, both changes, the zero usage line.
  Display, formatting, options, fake backend (the limits climb, are set back, the format switched while running), settings store.
  `ApiTest`: a seat-based reading end to end, the seat-based history with its switches, and a change of format through the running
  application. Page: the row, the change of format at a poll, the table with its widths, the tabs, Apply with both tabs, Maximum and
  Minimum view. The tests of the refusal of phase 38 became tests of the reading.
- **Docs.** The requirements (the section is no longer a proposal), `api.md`, the README, the fixtures README.

*Assumed, easy to change:* a percentage that did not change is `0.0` in the row's pair and an empty cell in the history; a reset
time that cannot be read is none; the widths of the seat-based columns; Maximum and Minimum view act on both tabs; a failed row for
a file with an unknown header keeps the usage-based shape.

**Not done.** No response of a real seat-based account has been seen. The window has not been looked at with either the seat-based
row or the tabs, and the widths of the seat-based table are untried by eye.

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

## Validation

What is tested, what stays outside the automated suite and what a person checks by
eye on macOS is in the requirements (Testing and verification). The tests of each
phase are named in that phase.

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
