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
its digits change. One addition beyond the requirement: the percentage's tooltip says
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
