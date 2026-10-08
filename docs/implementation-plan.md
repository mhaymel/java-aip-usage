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
- Fetch usage immediately at startup, then use a 30-second default backend
  usage-fetch interval. Configure it in seconds through both the CLI and a
  frontend control, allowing 5 through 3600 seconds. Persist frontend changes
  in the project-root settings file. A committed valid frontend value is sent
  to the backend and replaces any CLI override for the rest of the run.
- Changing the backend usage-fetch interval does not cancel an in-flight
  request. Apply the new interval to the next scheduled request, measured from
  when the current or most recent request was triggered. If the new interval
  has already elapsed, start the next request as soon as no request is running;
  otherwise wait until the interval elapses. Do not trigger an extra immediate
  request merely because the interval changed.
- Configure the UI-to-backend polling interval through both a CLI option and
  a frontend control, sending frontend changes to the backend to store in the
  project-root settings file. Default to 1 second and accept values from 1
  through 60 seconds. A committed valid
  frontend value becomes effective immediately and replaces any CLI override
  for the rest of the run.
- On startup, the frontend requests both effective intervals from the
  backend, displays their active values, and uses the UI polling interval to
  poll for the latest available state.
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

Charts and historical usage are not part of the first version.

## Recommended architecture

Use JavaFX WebView for the desktop shell and host a small HTTP server bound
only to the loopback interface. The JavaFX window loads the web UI from that
server; the UI communicates with the application through a small JSON API.
This provides the requested minimal window while keeping the HTML, CSS, and
JavaScript frontend separate from Java-specific code.

The frontend provides controls for both the backend usage-fetch interval and
the UI-to-backend polling interval. It sends each committed valid value to the
backend, which validates and persists it in the project-root settings file. A
frontend edit replaces a CLI override for that interval for the remainder of
the run. The usage-fetch interval defaults to 30 seconds and accepts values
from 5 through 3600 seconds. Changing it does not cancel an in-flight fetch or
cause an extra immediate fetch. The next scheduled fetch is due one new
interval after the current/most recent fetch was triggered; if that due time
has passed while a request is running, run the next fetch as soon as the
current one completes. Otherwise wait for the remaining interval. The UI
polling interval defaults to one second and accepts values from 1 through 60
seconds.
At startup, the frontend requests both effective intervals from the backend,
displays the active values, and uses the UI polling interval to poll a
read-only JSON status endpoint for the latest snapshot, refresh status, and
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
port instead of probing 9000-9100, and the `ClaudeLocator` PATH search is
dropped, since a failed launch already reports a missing CLI. Tests run a
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

### 5. Add local API, settings, and UI

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

## Validation strategy

- Unit-test response parsing, settings precedence, refresh scheduling behavior,
  and stale/error state transitions.
- Test that the UI obtains its effective polling interval at startup, frontend
  interval changes are validated and persisted, status polls do not trigger
  provider requests, and manual refresh returns immediately and starts at most
  one provider request when another refresh is not already in flight.
- Test that lifecycle and refresh events and sanitized HTTP diagnostics are
  emitted to the console and appended to `java-aip-usage.log`, and that
  credentials and sensitive request/response content are redacted.
- Test HTTP and token behavior against local test doubles; do not depend on
  live Anthropic credentials in the automated test suite.
- Include fixtures for both response variants and malformed input.
- Perform a manual macOS UI smoke test for startup, initial load, both usage
  views, both polling interval settings, manual refresh, refresh failure
  display, and verify closing the window terminates the app cleanly.

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
