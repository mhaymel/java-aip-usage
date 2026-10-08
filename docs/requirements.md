
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

- The token is retrieved via the same mechanisms used by the sibling auth-related repos.
- The token should be fetched once and then reused while it remains valid.
- If the Anthropic backend request fails because the token is invalid, expired, or rejected, the client should fetch a fresh token and retry.
- If the user is not logged in or the token is unavailable, the application should show a clear user-facing message instead of failing silently.

Example token output:

```json
{
  "access_token": "sk-ant-..."
}
```

## Refresh behavior

- The usage endpoint should be queried repeatedly in a loop.
- A reasonable default polling interval is 30 seconds.
- The interval should be easy to configure or tune later.
- Only the usage fetch should be repeated; token acquisition should not be repeated unless it is required after a failed request.

## Display requirements

The usage data must be displayed in a simple graphical user interface (GUI)
that is easy to read at a glance. At minimum, the following values should be
presented clearly:

- `fetched_at`
- `used`
- `limit`
- `currency`
- `percent`
- `severity`

The initial Java application must run on macOS. A future Go rewrite should be
designed to support Windows and allow a native Windows binary to be
cross-compiled on macOS. Keep the browser-based frontend as independent from the
backend as practical so it can be reused if the implementation language
changes.

A browser-based interface using HTML, CSS, and JavaScript or TypeScript is one
possible approach. In this design, the application would start an embedded
web server and automatically open the interface in a minimal browser window
without the usual browser controls. The displayed usage data must refresh
periodically. Use a reasonable default interval, such as 30 seconds, and make
the interval configurable.

## Future extension

The project should be designed to evolve toward a richer dashboard, potentially
including:

- charts showing usage trends over time
- status and error logging
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

- Use a JDK with Gradle toolchain support.
- Use the bundled Gradle wrapper rather than a manual local install.
- Keep the solution easy to run from the command line and from the IDE.
- Prefer simple, testable interaction boundaries between token acquisition, usage fetching, and rendering.
