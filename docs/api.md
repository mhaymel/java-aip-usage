# Local API

The contract between the frontend (`src/main/resources/web/`) and the backend.
It is deliberately small and independent of Java, so a future Go backend can
serve the same frontend by providing the same endpoints. The Java implementation
is `ApiHandler`.

The backend listens on `127.0.0.1` only, on a port chosen by the operating
system, and serves the frontend at `/`. Every response from `/api/` is JSON with
`Cache-Control: no-store`, except the plain-text `403` described under
Protections. Errors are `{"error": "<message fit to show a user>"}`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/config` | The effective intervals, and the limits they must stay within |
| `POST` | `/api/config` | Change one or both intervals |
| `GET` | `/api/status` | The latest usage reading and refresh outcome (read-only) |
| `POST` | `/api/refresh` | Start a usage fetch now |

Any other path under `/api/` is `404`; any other method on these paths is `405`
with an `Allow` header.

## `GET /api/config`

The frontend asks for this once at startup, shows both values, and then polls
`/api/status` every `pollIntervalSeconds`.

```json
{
  "usageIntervalSeconds": 30,
  "pollIntervalSeconds": 1,
  "limits": {
    "usageIntervalSeconds": { "min": 5, "max": 3600 },
    "pollIntervalSeconds": { "min": 1, "max": 60 }
  }
}
```

- `usageIntervalSeconds`: how often the backend fetches usage from Anthropic.
  Default 30.
- `pollIntervalSeconds`: how often the frontend asks the backend for the latest
  state. Default 1.

The values are the ones in force: a command-line option, else the saved
setting, else the default (see the README), until the frontend changes them.

## `POST /api/config`

```json
{ "usageIntervalSeconds": 45 }
```

Either key, or both. A key that is absent or `null` is left alone; unknown keys
are ignored. Values must be whole JSON numbers within `limits`.

- `200`: applied and saved; the body is the same as `GET /api/config`. The
  change is effective at once and replaces any command-line override for the
  rest of the run.
- `400`: a value is out of range or not a whole number, the body is not a JSON
  object, or no setting was given. Nothing is changed, even if the other value
  was valid.
- `413`: the body is larger than 4096 bytes.
- `415`: the `Content-Type` is not `application/json`.
- `500`: the values are valid but could not be saved. Nothing is changed.

Changing `usageIntervalSeconds` does not cancel a fetch in progress and does not
by itself start one. The next fetch is due one new interval after the most recent
fetch was *started*; if that time has already passed it starts as soon as no fetch
is running.

## `GET /api/status`

Read-only. It never causes a request to Anthropic, however often it is polled.

```json
{
  "refreshing": false,
  "stale": false,
  "error": null,
  "usage": {
    "source": "anthropic-oauth-usage",
    "fetched_at": "2026-10-08T12:00:00Z",
    "spend": {
      "used": 186.02,
      "limit": 1000.0,
      "currency": "USD",
      "percent": 19,
      "severity": "normal"
    },
    "windows": []
  }
}
```

- `refreshing`: a fetch is running now.
- `usage`: the most recent *successful* reading, or `null` before the first one.
  - `fetched_at`: when the backend received it (RFC 3339, UTC).
  - `spend`: for a usage-based account; `null` for a Pro or Max account. Its
    members may each be `null`.
  - `windows`: for a Pro or Max account; empty for a usage-based account. Each is
    `{"window": "five_hour", "utilization": 12.34, "resets_at": "..."}`. `window`
    is the name exactly as Anthropic sends it, `utilization` is a percentage
    (it can exceed 100), and `resets_at` is `null` when no reset time is known.
  - Both empty means the account reports no usage; that is a valid answer.
- `error`: `null` after a successful refresh. After a failed one it is
  `{"message": "...", "at": "2026-10-08T12:01:00Z"}`, written to be shown to the
  user. It never contains a credential or any part of the response body.
- `stale`: `true` when `usage` is present *and* the latest refresh failed, so the
  reading may be out of date. The next success clears `error` and `stale`.

Before the first reading, `usage` is `null`: with `error` also `null` the first
fetch is still running; with `error` set it failed, and the message says what to
do (for example log in with Claude Code).

## `POST /api/refresh`

Body: `{}`. Starts a usage fetch now instead of waiting for the next scheduled
one, and returns at once without waiting for the result. Poll `/api/status` to
see it.

- `202` with `{"started": true}`: a fetch was started.
- `200` with `{"started": false}`: none was started, because one is already
  running or already requested. Extra requests are therefore harmless, and the
  frontend keeps its button enabled.

The request counts as the most recent fetch, so the scheduled interval restarts
from it.

## Protections

The server holds usage data, so it refuses requests that another web page on the
same machine could make:

- A request whose `Host` header is not `127.0.0.1:<port>` or `localhost:<port>`
  gets `403`. This stops a web page from reaching the API through a hostname it
  controls that resolves to `127.0.0.1` (DNS rebinding).
- Every `POST` must send `Content-Type: application/json`, otherwise `415`. A page
  on another origin cannot do this without a CORS preflight, which the server
  never answers with permission.

No endpoint accepts, returns or logs the OAuth token. Status polls are not
logged, since they arrive every second.
