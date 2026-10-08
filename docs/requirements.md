# Requirements

## Goal

This project provides a small Java-based client for monitoring Anthropic OAuth usage data. It should fetch the current usage snapshot and present it in a readable, continuously refreshed form.

The implementation is intended to exercise the neighboring `java-aip` tooling and to reuse patterns from the sibling repos for authentication and OAuth token handling.

## Source data

The application fetches usage data from the Anthropic OAuth usage endpoint:

`https://api.anthropic.com/api/oauth/usage`

The response shape depends on the account's subscription. For usage-based
accounts, the response contains spend details and an empty `windows` array:

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
- The default backend usage-fetch interval is 30 seconds. Configure it in seconds through both a command-line option (`--usage-interval <seconds>`) and a frontend control; accept values from 5 through 3600 seconds.
- Save interval changes made in the frontend to a settings file named `settings.json` in the project root, beside `gradlew`. A committed valid frontend value is sent to the backend and replaces any CLI override for the remainder of the run.
- Changing the backend usage-fetch interval does not cancel a request already in progress. Apply the new interval to the next scheduled request, measuring the interval from when the current/most recent request was triggered. If the new interval has already elapsed, start the next request as soon as no request is running; otherwise wait until the interval elapses. Changing the interval does not otherwise trigger an extra immediate request.
- If a refresh fails, keep the last successful data visible, mark it as stale, and show the error. Resume normal display after the next successful refresh.
- The backend alone fetches usage from Anthropic on this interval. UI status polling must not trigger an Anthropic request.
- Configure the UI-to-backend status polling interval in seconds through both a command-line option (`--poll-interval <seconds>`) and a frontend control, and send frontend changes to the backend to save in the settings file. Its default is 1 second; accept values from 1 through 60 seconds. A committed valid frontend value is sent to the backend, becomes effective immediately, and replaces any CLI override for that interval for the remainder of the run.
- When the UI starts, it must request both effective intervals from the backend. Display the returned active values, then use the UI polling interval to poll the backend for the latest available state.
- Provide a UI action to fetch usage immediately. It must call the backend, which starts an Anthropic usage request without waiting for the next scheduled refresh and returns immediately. Do not run overlapping usage requests; if a refresh is already in progress, return immediately without starting another one. Keep the manual-refresh action enabled; extra clicks while a request is in progress do not start additional requests.
- Closing the application window must terminate the program and stop its backend server, scheduled tasks, and other background resources.

## Display requirements

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
