# Implementation plan

Derived from [requirements.md](requirements.md) and the implementation choices
clarified during discussion. This document describes how the first version can
be built; observable behavior belongs in `requirements.md`.

## Scope and confirmed decisions

The first version is a Java 25 application that runs on macOS. It displays both
usage-based spend data and Pro/Max plan-window data in a JavaFX WebView desktop
window containing a browser-based UI. Keep the frontend independent of the
Java backend where practical so it can be reused in a future Go rewrite.
Windows support and cross-compiling a native Windows binary from macOS apply
to that possible future Go version, not to the Java first version. The v1
delivery target is `./gradlew run`; a distributable macOS app bundle is deferred.

Other decisions confirmed during discussion:

- Adapt the token-fetching implementation from `java-aip` into this
  repository; do not add a runtime dependency on the sibling repository. The
  Claude Code CLI must be installed and logged in.
- Reuse a token for usage requests. On HTTP 401, acquire a fresh token and
  retry the request once. Do not refresh the token for other HTTP or network
  failures.
- Fetch usage immediately at startup, then use a 60-second default backend
  usage-fetch interval. (It was 30 s until phase 8: a live run showed the endpoint
  accepts about one request a minute over the long run and answers 429 to a faster
  pace.) Configure it in seconds through both the CLI and a frontend control,
  allowing 5 through 3600 seconds. Persist a frontend change in the project-root
  settings file, which holds this one value. A committed valid frontend value is
  sent to the backend and replaces any CLI override for the rest of the run.
- Changing the backend usage-fetch interval does not cancel an in-flight
  request. Apply the new interval to the next scheduled request, measured from
  when the current or most recent request was triggered. If the new interval
  has already elapsed, start the next request as soon as no request is running;
  otherwise wait until the interval elapses. Do not trigger an extra immediate
  request merely because the interval changed.
- The UI-to-backend polling interval is not a setting. It defaults to 1 second
  and can be overridden for one run with a CLI option (`--poll-interval`),
  accepting 1 through 60 seconds. It has no frontend control, never changes while
  the application runs, and is never saved. (Before phase 8 it was a saved,
  UI-editable setting; a value left in an old settings file is ignored.)
- On startup, the frontend requests the effective intervals from the backend:
  it shows the usage-fetch interval when the config field is opened, and uses the
  polling interval to poll for the latest available state.
- Keep scheduled Anthropic fetching on the backend only; UI status polling
  must not trigger usage requests. Provide a separate UI action that calls the
  backend to start an immediate Anthropic usage fetch. The backend action
  returns immediately without waiting for the fetch result. Do not run
  overlapping usage requests; if a refresh is already running, return
  immediately without starting another request. Keep the UI action enabled;
  extra clicks during a refresh do not start additional requests.
- If refreshing usage fails, retain the last successful data, indicate that it
  is stale, and show the error.
- If Claude Code is missing or not logged in, keep the GUI open, show setup
  guidance, and let the user retry token acquisition after resolving the issue.
- Closing the application window terminates the program and stops its backend
  server, scheduled tasks, and other background resources.
- Write lifecycle events, each refresh success and failure, and detailed HTTP
  diagnostics to both the console and `java-aip-usage.log`, an append-mode log
  file in the project root alongside `gradlew`. Do not rotate the log in v1.
- Sanitize log output: never log access tokens, credentials, or sensitive
  request/response content.
- The window is a compact status strip: as small as its content allows, one row in
  its normal state, readable text of at least 14 px, local time of day only. The
  row holds the refresh time, the percentage, spent and budget (or the plan windows),
  a small refresh button, a countdown to the next refresh and a very small config
  button, which reveals the fetch-interval field until it is confirmed. (The order
  and the countdown are from phase 9; phase 7 built percentage, amounts, time.) See [Compact window](requirements.md#compact-window);
  planned as phase 7.

Charts and historical usage are not part of the first version.

## Recommended architecture

Use JavaFX WebView for the desktop shell and host a small HTTP server bound
only to the loopback interface. The JavaFX window loads the web UI from that
server; the UI communicates with the application through a small JSON API.
This provides the requested minimal window while keeping the HTML, CSS, and
JavaScript frontend separate from Java-specific code.

The frontend provides a control for the backend usage-fetch interval. It sends a
committed valid value to the backend, which validates and persists it in the
project-root settings file. A frontend edit replaces a CLI override for the
remainder of the run. The usage-fetch interval defaults to 60 seconds and
accepts values from 5 through 3600 seconds. Changing it does not cancel an
in-flight fetch or cause an extra immediate fetch. The next scheduled fetch is due one new
interval after the current/most recent fetch was triggered; if that due time
has passed while a request is running, run the next fetch as soon as the
current one completes. Otherwise wait for the remaining interval. The UI
polling interval is not a setting: it is one second unless the command line says
otherwise (1 through 60 seconds), is reported to the frontend read-only, and the
backend refuses to change it.
At startup, the frontend requests the effective intervals from the backend and
uses the UI polling interval to poll a read-only JSON status endpoint for the latest snapshot, refresh status, and
errors. Status polls only read current backend state; they never trigger
Anthropic requests.

Expose a separate backend action for the UI's manual refresh control. It starts
an immediate usage fetch without waiting for the next scheduled refresh and
returns immediately without waiting for the result. Ensure scheduled and
manual refreshes cannot overlap: if one is already in progress, return
immediately without starting another. Keep the manual-refresh control enabled;
extra clicks during an in-progress fetch do not start additional requests.
Keep the API small and document its response shapes so a future Go backend can
provide the same contract.

The compact window (phase 7) adds one thing outside that API. The window must fit
its content, so it has to change size when a message or the config fields appear,
and only the host can resize a window. The page therefore reports its content size
to the Java host, which resizes the stage. This is a window-management concern, not
part of the contract a Go backend provides: a Go host would need its own way to
resize its window, as it needs its own window host anyway.

JavaFX WebView is the selected host because it is a direct fit for a Java
desktop app and avoids bundling a full Chromium runtime. Revisit this choice
only if a concrete frontend limitation appears.

Keep the first frontend dependency-light: use HTML, CSS, and JavaScript unless
the interface grows enough to justify a TypeScript build toolchain. Define the
JSON API independently of Java classes so a future Go service can implement
the same contract. The Go rewrite would need a Go-compatible desktop window
host; JavaFX is not reusable as the Go host.

Suggested backend boundaries:

| Component | Responsibility |
| --- | --- |
| `UsageSnapshot` and related value types | Represent the timestamp, optional spend details, and zero or more usage windows. |
| `TokenProvider` | Acquire and refresh an OAuth token using the selected sibling-repo flow. Keep tokens in memory; do not log them. |
| `UsageClient` | Call the Anthropic usage endpoint with the bearer token, decode the response, and distinguish HTTP 401 from other failures. |
| `UsageService` | Own the current snapshot, refresh interval, last error/stale state, and non-overlapping scheduled refreshes. |
| `Logging` | Configure console and append-mode output to `java-aip-usage.log` in the project root; record lifecycle, refresh, and sanitized HTTP diagnostic events without credentials. |
| `LocalWebServer` | Serve the bundled frontend, read-only status/configuration endpoints, and a manual-refresh action on loopback. |
| `Main` / application lifecycle | Parse CLI options, load settings, start services and the window, and terminate the program and all background resources when the window closes. |

The response model must support both documented shapes: `spend` may be
populated or `null`, and `windows` may be empty or contain plan windows.
Preserve window names and nullable reset timestamps as supplied by the
endpoint. Treat unexpected or malformed responses as visible errors rather
than substituting empty usage data.

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
`src/test/resources/fixtures/`. A body is rejected, with a message safe to
show the user, when it is not valid JSON or not an object, has neither a
`spend` object nor any window, or enables spend without any amount. An account
that legitimately reports nothing decodes as an empty snapshot rather than an
error.

### 3. Implement OAuth token acquisition

- Adapt the token-fetching implementation from `java-aip` into this
  repository's `TokenProvider`, preserving the no-runtime-dependency boundary.
- Require the Claude Code CLI to be installed and logged in; show clear setup
  guidance in the GUI if it is missing or unauthenticated. Keep the UI open
  and allow the user to retry token acquisition after fixing the issue.
- Ensure subprocesses, listeners, and other temporary resources are always
  closed, and surface actionable errors when the user is not logged in or a
  token cannot be acquired.
- Keep credentials out of logs and persistent application settings.

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
- On HTTP 401, acquire a new token and retry once. Do not retry with a new
  token for other HTTP statuses or network failures.
- Keep the most recent successful snapshot when a refresh fails, and expose
  its stale status and the latest error.
- Prevent overlapping refreshes and cleanly stop scheduled work on shutdown.
- When the backend usage-fetch interval changes, preserve any in-flight
  request and reschedule the next fetch using the new interval from the
  current/most recent request's trigger time. If the due time passes during an
  in-flight request, start the next fetch as soon as it completes; otherwise
  wait until the new interval elapses. Do not add a separate immediate fetch
  solely because the interval changed.

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

The interval is measured from when the most recent request was triggered,
manual refreshes included. Changing it only moves the due time: a fetch in
flight is neither cancelled nor duplicated, and a due time that has already
passed starts the next fetch as soon as none is running. Range validation
(5-3600 s and 1-60 s) is not in the service, which accepts any positive
interval; it belongs to the settings and API layer in phase 5.

**Rate limiting (added after a live run).** Restarting the application about eight times
in 15 minutes made the endpoint answer HTTP 429 with `retry-after: 0`, and the service
kept retrying every 30 s. It now backs off: the first 429 doubles the wait, each further
one in a row doubles it again up to 5 minutes (`DEFAULT_MAX_BACKOFF`), or the server's
`Retry-After` if longer (believed up to an hour). A success eases the hold by an eighth
and the eased value is the new wait, until it is no longer than the interval; other
failures leave it alone, and a manual refresh is never held back. The policy is the
pure class `Backoff`. `RefreshSchedule` takes the longer of the interval and the
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

- Serve the static frontend and a narrowly scoped JSON API from a loopback-only
  HTTP server on an available local port.
- Expose the current snapshot, last successful fetch time, stale status,
  user-visible error, and both effective intervals through local JSON
  endpoints. On startup, have the frontend request both effective intervals,
  display them, then poll the read-only status endpoint at the UI interval. Do
  not perform Anthropic fetches in response to status polls.
- Add frontend controls for both intervals. Send each committed valid value
  to the backend for validation, persistence, and immediate application. A
  frontend change replaces the corresponding CLI override for the remainder
  of the current run.
- Add a separate endpoint for the UI's manual refresh action. It starts an
  immediate backend usage fetch and returns without waiting for the result.
  Prevent manual and scheduled refreshes from overlapping; extra clicks while
  a fetch is active return immediately without starting another request.
- Implement the spend view and plan-window view, showing only relevant fields
  for the shape received.
- Trigger the first usage fetch immediately at startup.
- Add a CLI option (`--usage-interval <seconds>`) and frontend control for the
  backend usage-fetch interval; accept values from 5 through 3600, defaulting
  to 30. Store the setting in `settings.json` in the project root beside
  `gradlew`. A committed valid frontend edit is persisted and immediately
  replaces any CLI override for the remainder of the run.
- Add a CLI option (`--poll-interval <seconds>`) and frontend control for the
  UI polling interval, defaulting to 1 second and accepting values from 1
  through 60. Send frontend changes to the backend for validation,
  persistence, and immediate application in the same settings file. The
  backend returns both effective intervals on UI startup; display both values
  and use the UI interval for status polling. A frontend edit
  immediately replaces any CLI override for that interval for the remainder
  of the run.
- Open the UI automatically in a minimal JavaFX WebView window. Avoid exposing
  credentials through the API or browser logs.
- Ensure closing the window terminates the program and stops the server,
  scheduled tasks, and other background resources.

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

- Rework the page into one row: percentage, spent and budget (or the plan windows),
  time, a small refresh button, a very small config button, with small gaps and a
  bold font of at least 14 px (700, and 800 for the percentage and amounts).
  Severity is the colour of the percentage and amounts.
- Show local time of day only. Add a time-only formatter to `view.js` and use it
  for the refresh time and for error times. Show a window's reset as the time
  remaining instead of a date and time.
- Replace the settings panel with a config button that reveals the interval
  fields in the row. They are confirmed together, with Enter or a small confirm
  button, in one `POST /api/config` (the API already accepts both keys at once), and
  hide again on success. (Phase 8 reduced these to the one fetch-interval field.) Validation errors keep them open with a brief message;
  Escape or the config button closes them unchanged.
- Move errors and stale notices to a short second line shown only while they apply.
- Make the window fit its content. The page reports its content size to the Java
  host, which resizes the stage when the size changes (message or config fields
  shown or hidden), with a minimum size and a guard against resize loops. This is
  the one part that needs host code; everything else is in `web/`.
- Update the Node tests for the new view logic and page script, and the README's
  description of the window.

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

The row becomes: time, percentage, amounts as plain numbers (no `$`), refresh button,
countdown, config button. Plan windows take the place of the percentage and amounts.
The order of percentage and amounts was assumed (percentage first) and is the thing to
confirm.

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
- **Tooltips.** The time: `Last update: 8 Oct 2026, 14:24:53`, the one place a date
  appears, so `view.js` needs a date-and-time formatter again. The used amount:
  `Credits used, in USD`; the budget: `Credit budget, in USD`; the code comes from the
  response's currency, and without one the tooltip says just `Credits used` and
  `Credit budget`. The countdown: seconds until the next refresh, negative when overdue.
- **Plan accounts.** No amounts, so no amount tooltips; the windows keep their reset
  text as it is.
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

**The usage history (CSV).** Every successful refresh that has amounts adds a row to
`java-aip-usage.csv` in the project root: `datetime,used,limit`, for example
`2026-10-08T14:24:53Z,186.02,1000.00`.

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

**Status: planned.** Requirements: Changes between readings, The usage history panel.

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

**Status: planned.** Requirements: Non-functional requirements (log), The log panel.

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

**Status: planned.** Requirements: the frontend does no calculation (Display requirements), Compact window.

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

**Status: planned.** Requirements: Settings, Compact window (row, tooltips, window size).

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

## Validation strategy

- Unit-test response parsing, settings precedence, refresh scheduling behavior,
  and stale/error state transitions.
- Test that the UI obtains its effective polling interval at startup, frontend
  usage-interval changes are validated and persisted, the polling interval cannot
  be changed or saved, status polls do not trigger
  provider requests, and manual refresh returns immediately and starts at most
  one provider request when another refresh is not already in flight.
- Test that lifecycle and refresh events and sanitized HTTP diagnostics are
  emitted to the console and appended to `java-aip-usage.log`, and that
  credentials and sensitive request/response content are redacted.
- Test HTTP and token behavior against local test doubles; do not depend on
  live Anthropic credentials in the automated test suite.
- Include fixtures for both response variants and malformed input.
- Perform a manual macOS UI smoke test for startup, initial load, both usage
  views, the fetch-interval setting, manual refresh, refresh failure
  display, and verify closing the window terminates the app cleanly. Once phase 7
  is done, also check the compact layout: one row at the target size, the window
  growing and shrinking around the config fields and messages, readable text, and
  time of day only.

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
