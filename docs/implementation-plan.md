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
| `UsageSnapshot` and related value types | Represent the timestamp and the optional spend details of a reading. |
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

### 50 to 52. The border round a view, the gray box for what is not known, the level and date of the log

Requirement: Debug mode (the border), and A gray box for a figure that is not known, and the level and date of the log.
Three pieces that do not depend on one another. *Decided by the person:* they are built as phases 45 to 49 were, **one at a
time, each a phase and a version of its own**, both suites green, its passage of the requirements moved out of "specified, not
yet built", a commit. Also decided by the person: the frame in `docs/img_3.png` is the program's own border, misplaced;
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
  element of the row hidden, the case of `docs/img_3.png`, and reports how far the app's right edge is from the window's
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

**Not checked by a test.** The row in a real window in `No usage reported`, the case of `docs/img_4.png`: the page test
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
  the case of `docs/img.png`: with the settings shown the row is 42 pixels of buttons, its box is as wide as the window
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

**Status: specified, not built (version 0.53).**

- **The search** is a class of its own with no clock and no thread: the interval and the two bounds, a success, a 429.
  It is tested by its arithmetic alone.
- **`UsageService`** asks it in the place of `Backoff` while it is on; `RefreshSchedule` gets the interval as it gets one
  from the settings. `retry-after` lengthens the one wait as it does now.
- **`Settings`** gets `autoInterval`; the three figures of the search are kept beside the settings like the remembered
  heights, since they change by themselves and are no choice of the settings view.
- **The page.** The checkbox; the field disabled and filled from the interval in force, which the status already carries.
- **Tests.** The search step by step, the reset, the bounds; the service with a scripted fetcher; the store; the page.

*Risk:* the largest piece. A search that is wrong asks Anthropic too often, so the least interval and the steps are
tested before anything is run against the real endpoint.

## Implementation notes, as built

How the requirements are met today (version 0.37). What these mechanisms make the
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

*Decided by the person:* all twelve elements marked in `docs/img_2.png` can be hidden, the gear alone staying; a click on a
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
