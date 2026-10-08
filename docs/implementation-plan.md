# Implementation plan

Derived from [requirements.md](requirements.md) and the implementation choices
clarified during discussion. This document describes how the first version can
be built; observable behavior belongs in `requirements.md`.

## Scope and confirmed decisions

The first version is a Java application that runs on macOS. It displays both
usage-based spend data and Pro/Max plan-window data in a minimal desktop window
containing a browser-based UI. Keep the frontend independent of the Java
backend where practical so it can be reused in a future Go rewrite. Windows
support and cross-compiling a native Windows binary from macOS apply to that
possible future Go version, not to the Java first version.

Other decisions confirmed during discussion:

- Compare JavaFX WebView and JCEF, then use the simpler option that meets the
  first-version needs.
- Retrieve OAuth tokens directly in this application, using sibling
  repositories as references rather than invoking `java-aip token`.
- Reuse a token for usage requests. On HTTP 401, acquire a fresh token and
  retry the request once. Do not refresh the token for other HTTP or network
  failures.
- Start with a 30-second refresh interval. Save changes made in the GUI
  locally; a command-line interval overrides the saved setting for that run.
- If refreshing usage fails, retain the last successful data, indicate that it
  is stale, and show the error.

Charts, historical usage, and advanced logging are not part of the first
version.

## Recommended architecture

Use JavaFX WebView for the desktop shell and host a small HTTP server bound
only to the loopback interface. The JavaFX window loads the web UI from that
server; the UI communicates with the application through a small JSON API.
This provides the requested minimal window while keeping the HTML, CSS, and
JavaScript frontend separate from Java-specific code.

JavaFX WebView is the recommended starting point because it is a direct fit for
a Java desktop app and avoids bundling a full Chromium runtime. JCEF is an
alternative if later UI requirements need Chromium compatibility that WebView
cannot provide, but it brings larger binaries and more involved native
packaging. Revisit this choice only if a concrete frontend limitation appears.

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
| `LocalWebServer` | Serve the bundled frontend and the small JSON API on loopback. |
| `Main` / application lifecycle | Parse CLI options, load settings, start services and the window, and shut everything down cleanly. |

The response model must support both documented shapes: `spend` may be
populated or `null`, and `windows` may be empty or contain plan windows.
Preserve window names and nullable reset timestamps as supplied by the
endpoint. Treat unexpected or malformed responses as visible errors rather
than substituting empty usage data.

## Phases

### 1. Confirm build and UI integration

- Inspect the existing scaffold and choose a Java toolchain compatible with
  the installed Gradle wrapper.
- Add the application entry point and `run` task.
- Compare JavaFX WebView with JCEF against the requirements and record the
  choice; proceed with JavaFX WebView unless a concrete limitation rules it
  out.
- Verify that the JavaFX WebView can load the frontend from the loopback
  server on macOS.

**Checkpoint:** the Gradle application launches a minimal JavaFX window and
can display a locally served test page.

### 2. Model and decode usage data

- Add typed models for the response envelope, optional spend object, and
  window entries.
- Add JSON decoding and fixtures for usage-based and plan-based responses,
  including null spend, empty windows, and nullable fields.
- Reject invalid response shapes with an error that can be presented to the
  user.

**Checkpoint:** unit tests decode both example response shapes without
discarding fields needed by the UI.

### 3. Implement OAuth token acquisition

- Read the token acquisition and login flows in `java-claude-login`,
  `java-claude-code-fetch-oauth-token`, and `java-aip`.
- Implement the required flow behind `TokenProvider`; avoid coupling the
  frontend or usage model to the particular token-capture mechanism.
- Ensure subprocesses, listeners, and other temporary resources are always
  closed, and surface actionable errors when the user is not logged in or a
  token cannot be acquired.
- Keep credentials out of logs and persistent application settings.

**Checkpoint:** tests cover successful token acquisition and the principal
unavailable/login failure paths using controlled test doubles.

### 4. Implement usage requests and refresh state

- Add an HTTP client with connection and request timeouts.
- Send the OAuth token as a bearer token to the usage endpoint.
- On HTTP 401, acquire a new token and retry once. Do not retry with a new
  token for other HTTP statuses or network failures.
- Keep the most recent successful snapshot when a refresh fails, and expose
  its stale status and the latest error.
- Prevent overlapping refreshes and cleanly stop scheduled work on shutdown.

**Checkpoint:** tests verify normal fetches, a 401 followed by one token
refresh and retry, repeated 401 handling, non-401 failures, and preservation of
the last successful snapshot.

### 5. Add local API, settings, and UI

- Serve the static frontend and a narrowly scoped JSON API from a loopback-only
  HTTP server on an available local port.
- Expose the current snapshot, last successful fetch time, stale status, and
  user-visible error to the UI.
- Implement the spend view and plan-window view, showing only relevant fields
  for the shape received.
- Add a GUI control for the refresh interval and persist that preference
  locally. Use the command-line interval when provided; otherwise use the
  saved GUI setting, falling back to 30 seconds.
- Open the UI automatically in a minimal JavaFX WebView window. Avoid exposing
  credentials through the API or browser logs.
- Ensure closing the window stops the server and background tasks.

**Checkpoint:** tests cover settings precedence and API responses; a macOS
smoke test confirms both response types render and refresh errors are visible
without removing the last successful data.

### 6. Run, package, and document the first version

- Make the application runnable from Gradle and the IDE.
- Confirm the frontend is included in the application distribution.
- Add macOS run/package instructions and document configuration, token
  prerequisites, and known limitations.
- Update the README layout and requirements links to match the completed
  scaffold.

**Checkpoint:** `./gradlew build` and the targeted tests pass, and the app can
be launched from the documented macOS workflow.

## Validation strategy

- Unit-test response parsing, settings precedence, refresh scheduling behavior,
  and stale/error state transitions.
- Test HTTP and token behavior against local test doubles; do not depend on
  live Anthropic credentials in the automated test suite.
- Include fixtures for both response variants and malformed input.
- Perform a manual macOS UI smoke test for startup, initial load, both usage
  views, interval changes, refresh failure display, and clean shutdown.

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
