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

The layout of the window is governed by [Compact window](#compact-window); where
that section and the descriptions below differ on presentation, it wins.

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

## Compact window

The window is a status strip that a software engineer keeps beside their work,
so it must take as little screen space as it can while staying easy to read.

**Size and text**

- The window is as small as its content allows, with no empty space around it.
  In its normal state it is a single row, roughly 400 by 50 pixels.
- The text is easy to read: a sans-serif font of at least 14 pixels, regular
  weight or heavier, with strong contrast. No thin or light weights, no fine print.
- Every time the window shows is the local time of day only, with no date, for
  example `14:24:53`. This applies to the time of the last refresh and to the time
  of an error alike.

**The row**

The controls sit in one horizontal row, one after another, with small gaps, in
this order:

1. the time the usage was last refreshed (`fetched_at`);
2. what has been spent and the budget: `used` and `limit` with the currency, for
   example `$186.02 / $1,000.00`, with the percentage in the most compact form and
   the severity shown by colour rather than by extra words;
3. a small refresh button;
4. a very small config button, an icon rather than a word.

For an account with Pro or Max plan windows instead of spend, the windows take the
place of item 2 in the same row, each as its name exactly as supplied, its
utilization and the time remaining until it resets (for example `five_hour 12%
in 2 h 5 min`). Remaining time is used because a reset can be days away, and a
time of day alone would then mislead. An unknown reset time is shown as unknown.

**Config**

- Pressing the config button shows two input fields in the same row, next to the
  button: the usage fetch interval and the window update interval, in seconds. Each
  has a short label saying which it is, and shows its current value.
- The two values are confirmed together, with Enter or a small confirm button.
  When valid values are confirmed they are saved and take effect, as described under
  Refresh behavior, and the input fields disappear.
- Invalid values keep the fields open and show a brief message; they are not sent.
  Pressing Escape, or the config button again, closes the fields without changing
  anything.

**Messages**

- A failed refresh, stale data, or a missing or logged-out Claude Code must still be
  visible, as required elsewhere. In the compact window this is a short message on
  a second line, shown only while the condition lasts. The window grows to hold it
  and returns to its single-row size afterwards. The last good figures stay in the
  row meanwhile.
- The window also resizes to fit when the config fields appear and disappear.

The refresh button stays enabled at all times, and the behavior of everything
behind these controls is unchanged.

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
