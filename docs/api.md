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
| `GET` | `/api/config` | The effective intervals (read-only: the settings view changes the usage interval, through `/api/settings`) |
| `GET` | `/api/settings` | Every setting, the defaults, and the interval choices |
| `POST` | `/api/settings` | Apply a full set of settings |
| `GET` | `/api/errors` | The errors of this run (every failed refresh), newest first; in memory only |
| `GET` | `/api/status` | The latest usage reading and refresh outcome (read-only) |
| `POST` | `/api/refresh` | Start a usage fetch now |

Any other path under `/api/` is `404`; any other method on these paths is `405`
with an `Allow` header.

## `GET /api/settings`

Every setting as the backend has it, read afresh each time the settings view is opened; the frontend keeps none of its own.

```json
{
  "settings": {
    "usageIntervalSeconds": 60,
    "logResponse": false,
    "showPercentage": false,
    "showInterval": false,
    "showDeltaUsed": false,
    "showDeltaTime": false,
    "timeFormat": "hh:mm",
    "historyDeltaUsed": false,
    "historyDeltaTime": false,
    "historyDate": false
  },
  "defaults": { "...": "the same keys, with the defaults" },
  "intervalChoices": [60, 120, 180, 240, 300],
  "limits": { "usageIntervalSeconds": { "min": 5, "max": 3600 } }
}
```

`timeFormat` is `"hh:mm"` or `"hh:mm:ss"`. `intervalChoices` is what the settings view offers; the
backend accepts anything within `limits`, so an interval set on the command line or in the file is
shown as the current value even when it is not a choice. `settings.usageIntervalSeconds` (and the status' `display.interval`) is the interval in force: the configured one, or, while the application
waits longer after HTTP 429s, that longer wait in whole seconds rounded up. Applying values saves what is sent, so applying while backing off saves the longer
wait as the configured interval. `defaults` and `GET /api/config` are not changed by a back-off.
The same keys are in `settings.json`, which
is created with the defaults if it is missing.

## `POST /api/settings`

The body is an object with all eight keys of `settings`. A missing key, a value of the wrong type, an
unknown `timeFormat` or an interval outside `limits` is a `400` and nothing is changed or saved; keys that
are not settings are ignored.

- `200`: applied and saved, effective at once; the body is the same as `GET /api/settings`. The interval
  replaces any command-line override for the rest of the run.
- `400`: a key is missing or has the wrong type, `timeFormat` is unknown, the body is not a JSON object, or the interval is outside `limits`.
- `413`: the body is larger than 4096 bytes.
- `415`: the `Content-Type` is not `application/json`.
- `500`: the values are valid but could not be saved. Nothing is changed.


## `GET /api/config`

The frontend asks for this once at startup, and then polls `/api/status` every
`pollIntervalSeconds`.

```json
{
  "usageIntervalSeconds": 60,
  "pollIntervalSeconds": 1,
  "limits": {
    "usageIntervalSeconds": { "min": 5, "max": 3600 }
  }
}
```

- `usageIntervalSeconds`: how often the backend fetches usage from Anthropic.
  Default 60. This is the one setting a frontend can change.
- `pollIntervalSeconds`: how often the frontend asks the backend for the latest
  state. Default 1. It is read-only: it is set on the command line, for one run, and
  is never saved or changed through this API. It has no entry under `limits` for that
  reason.

The values are the ones in force. For the usage interval that is a command-line
option, else the saved setting, else the default (see the README), until the
frontend changes it.

## `GET /api/status`

Read-only. It never causes a request to Anthropic, however often it is polled.

```json
{
  "refreshing": false,
  "stale": false,
  "nextRefreshInSeconds": 42,
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
  },
  "change": { "delta_used": 0.05, "delta_time": 60 }
}
```

- `display`: what the window shows, finished: the frontend does no calculation or formatting of readings.
  `time` (the local time of the last reading, `14:24` or `14:24:53` by the `timeFormat` setting) and `timeTooltip`
  (`Last update: 8 Oct 2026, 14:24:53`); `spend` (`percentText`, `percentTooltip`, `used`, `limit`, `usedTooltip`,
  `limitTooltip`, `severityText`, `severityKind`) for a usage-based account; `windows` (`name`, `utilizationText`,
  `resetsText` such as `in 2 h 5 min`) for a plan account; `placeholder` (`Loading…`, `No data`, `No usage reported`);
  `countdown` (with `countdownAlert`, the message of an HTTP 429, which makes the countdown red and is shown when it is hovered; the 429
  has no `message`), `interval` (the time between usage requests in force, `60 s`), `deltaUsed` and `deltaTime` as
  `{text, tooltip}` or `null`; `message` as `{kind, text}` or `null`; and `show`, which of `percentage`, `interval`, `deltaUsed` and
  `deltaTime` the settings switch on (the countdown is always shown). The raw values above stay for
  other clients. Numbers use a dot and `,` for thousands whatever the machine's language.
- `change`: what changed between the newest reading (the newest history row that has amounts) and the row
  directly before it in the history file, worked out by the backend; `null` when there is no usage or no
  history. `delta_used` is the change in the amount used, `delta_time` the whole seconds between the
  two rows. Either is `null` when it cannot be worked out: the first row of a run has neither, a failed row
  before it leaves no `delta_used`. See the requirements, Changes between readings.
- `refreshing`: a fetch is running now.
- `nextRefreshInSeconds`: whole seconds until the next *scheduled* refresh, to the
  nearest second, worked out by the backend; a frontend shows it and does no counting
  of its own. It is counted from when the most recent request was triggered, by the
  schedule or by `POST /api/refresh`, plus the wait that applies: the usage interval,
  or the longer wait while backing off after an HTTP 429. So it starts again after a
  manual refresh, is longer during a back-off, and moves when the interval changes.
  It **can be negative**: that means the refresh is overdue by that many seconds, for
  example because a request is taking longer than the interval. It keeps counting
  while a request runs. It is `null` until the first request has been triggered.
  It changes with time, so it is in the status, which is polled, and not in
  `/api/config`.
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

## `GET /api/log`

Read-only. The end of the log file, for the panel the log button shows in the main window. The lines are oldest first; the panel shows them newest first. It names the file, never its path.

```json
{
  "file": "java-aip-usage.log",
  "exists": true,
  "truncated": false,
  "lines": ["2026-10-08T14:24:53Z INFO    [UsageApp] Starting java-aip-usage", "..."]
}
```

- `lines`: the last 1,000 lines at most, oldest first, so the newest is last. Only the last 512
  kilobytes of the file are read, since the log is never rotated.
- `truncated`: `true` when earlier lines were left out. A line the byte limit cut in two is dropped,
  never shown half.
- `exists`: `false`, with no lines, when there is no log yet.
- `500` with `{"error": "The log could not be read."}` if the file cannot be read.

## `GET /api/history`

Read-only. The usage history, for the panel the history button opens in the main window.

```json
{
  "file": "java-aip-usage.csv",
  "exists": true,
  "columns": ["time", "used", "limit", "currency"],
  "total": 1440,
  "rows": [
    ["16:46:11", "260.66", "1000.00", "USD", "", "60", "412"],
    ["16:45:11", "260.66", "1000.00", "", "start", "60", "388"]
  ],
  "deltas": [{ "delta_used": 0.0, "delta_time": 60 }, { "delta_used": null, "delta_time": null }],
  "show": { "deltaUsed": false, "deltaTime": false, "date": false },
  "startTooltip": "The program started here"
}
```

- `columns`: the four the panel shows. The first is `time`, and `date time` when the date setting is on; the
  first field of a row is then the time of day, `16:46:11`, or the date and time, `2026-10-08 16:46:11` (a value
  that is not in that form is sent as it is). The file itself always holds the full date and time.
  A row has three more fields after the four: the `status` (`start`,
  `failed`, `start-failed` or empty), the `interval` in seconds and the `duration_ms`.
- `startTooltip`: what hovering over the first line of a run (a row whose status begins with `start`) says.
- `show`: `{deltaUsed, deltaTime, date}`, whether the settings switch on the two change columns of the table, and
  whether the times have the date. A client
  that shows them puts them after the currency, with the titles `delta used` and `delta time`.
- `deltas` also carry the finished texts, `delta_used_text` (`+0.05`) and `delta_time_text` (`1 m`), or `null`. A change in the amount used of zero has no `delta_used_text` (`null`, though `delta_used` is
  `0.0`), so no client shows `0.00`; the same goes for the status's `display.deltaUsed`. A client that shows the two columns titles them `\u0394 used` and `\u0394 time`.
- `deltas`: one for each of `rows`, in the same order: the change in the amount used and the seconds since the
  row before it **in the file**, as `GET /api/status`'s `change`. A row out of order is still compared with the
  one written before it.

- `rows`: the newest 1,000 at most, **sorted by the date and time, latest first**, each as strings as
  in the file (an empty field stays empty), apart from the time. It sorts by the column and does not merely reverse the
  file, so a file that is out of order is still right. A line of the file without the columns (a row from before the currency was one, with three, is
  given an empty currency), and the header, are not rows.
- `total`: how many rows the file has, which can be more than `rows` holds.
- `exists`: `false`, with no rows, when there is no history yet.
- `500` with `{"error": "The usage history could not be read."}` if the file cannot be read.

Both are `GET` only: any other method is `405` with an `Allow` header.

## `GET /api/errors`

Read-only. The errors of this run, for the error log panel: one for each failed refresh, an HTTP 429 and a problem
with the token included. They are kept in memory only and are gone when the program stops. Nothing here is a
credential or a file name.

```json
{
  "entries": [
    { "time": "11:34:42", "message": "Anthropic is rate limiting usage requests (HTTP 429). Next try in 2 min." },
    { "time": "11:20:01", "message": "Claude Code is not logged in. Log in, then refresh." }
  ]
}
```

Newest first, at most the newest 1,000. `time` is the local time of day with seconds, whatever the time format setting says.
`405` for any method but `GET`.

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

## Window host contract

One more contract exists between the page and the program that hosts it, and it is
not part of the HTTP API. The window is sized to fit the page, and only the host
can resize a window, so the page offers a function the host calls:

```js
window.contentSize()   // "400,42,0"
```

It returns `"<width>,<height>,<resizable>"`: the size in CSS pixels the page needs to show all
of itself, as whole numbers, and what the person may drag: `0` nothing, `1` the height, `2` the height
and the width, and `3` nothing either, but not the window with nothing open (the last may be left out,
meaning `0`). While the log or the usage history is shown the page asks for more: a height of ten times
the row's *as it was when the panel opened*, which it keeps reporting whatever the row does after, so a
message line coming or going does not move the window; and a width of the row's plus the width of the
panel's vertical scrollbar, which the page measures and always reserves (three rows plus it for the log).
Those are the starting point, not the size the window was dragged to. While the settings are shown it
reports flag `3` and the height of the whole page, the row and its message lines and the settings, so the
window is exactly as tall as they need, and follows them. An optional last field names the panel: `415,420,1,history`,
`1215,420,2,log` or `1215,420,2,errors` (the error log is as wide as the log). For the history the width is the page's measure of its own table plus padding and the scrollbar (never less than the row's), so every column
is whole. The host (`WindowFit`) remembers the height of the history and of the log, in the settings
file, and opens them at the larger of that and the page's own; the error log and the settings are not remembered. The host asks about every 150 ms and resizes its window when the answer changes, and lets the person
resize only what it is told may be. The page measures its own content, never the window, so resizing the
window to match does not change the answer. The Java host (`WindowFit`) ignores anything that is not that
shape, keeps the size between 160 x 32 and 2400 x 1600, and does not apply a size twice. A different host,
such as the future Go one, needs only to call the function and resize.

The page exposes nothing else to its host, and the host exposes nothing to the page.

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
