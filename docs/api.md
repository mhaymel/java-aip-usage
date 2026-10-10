# Local API

The contract between the frontend (`src/main/resources/web/`) and the backend.
It is deliberately small and independent of Java, so a future Go backend can
serve the same frontend by providing the same endpoints. The Java implementation
is `ApiHandler`.

The backend listens on `127.0.0.1` only, on a port chosen by the operating
system, and serves the frontend at `/`. Every response from `/api/` is JSON
(`application/json; charset=utf-8`) with `Cache-Control: no-store` and
`X-Content-Type-Options: nosniff`, except the plain-text `403` described under
Protections. Errors are `{"error": "<message fit to show a user>"}`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/config` | The configured usage interval and the polling interval (read-only) |
| `GET` | `/api/settings` | Every setting, the defaults, and the limits of the interval |
| `POST` | `/api/settings` | Apply a full set of settings |
| `GET` | `/api/status` | The latest usage reading and refresh outcome, raw and finished for display (read-only) |
| `POST` | `/api/refresh` | Start a usage fetch now |
| `GET` | `/api/log` | The end of the log file (read-only) |
| `GET` | `/api/history` | The usage history, as finished lines (read-only) |
| `GET` | `/api/errors` | The errors of this run (every failed refresh), newest first; in memory only |

Only a path that begins `/api/` is the API's; a query on it is not looked at. Any
other path under `/api/` is `404` with `{"error":"No such endpoint."}`. Any other
method on these paths is `405`, with the right one in the body and in an `Allow`
header: `{"error":"Use GET."}` and `Allow: GET`; `{"error":"Use POST."}` and
`Allow: POST` for the refresh; `{"error":"Use GET or POST."}` and `Allow: GET, POST`
for the settings. A fault of the backend inside a request is `500` with
`{"error":"Internal error; see the log."}`.

A request is checked in this order: the path (`404`), the method (`405`), and for a
`POST` the `Content-Type` (`415`), the size of the body (`413`), whether it is JSON
(`400`), and then what it says.

What the program does behind each endpoint is specified in
[requirements.md](requirements.md); this document is the shapes and the statuses.

## `GET /api/settings`

Every setting as the backend has it, read afresh each time the settings view is opened; the frontend keeps none of its own.

```json
{
  "settings": {
    "usageIntervalSeconds": 60,
    "logResponse": false,
    "showPercentage": false,
    "showCurrency": false,
    "showHistoryIcon": true,
    "showLogIcon": true,
    "showErrorIcon": true,
    "showInterval": false,
    "showDeltaUsed": false,
    "showDeltaTime": false,
    "timeFormat": "hh:mm",
    "historyDeltaUsed": false,
    "historyDeltaTime": false,
    "historyDate": false,
    "historyZeroLines": true,
    "historyFailedLines": true
  },
  "defaults": { "...": "the same keys, with the defaults" },
  "limits": { "usageIntervalSeconds": { "min": 5, "max": 3600 } }
}
```

`timeFormat` is `"hh:mm"` or `"hh:mm:ss"`. The interval is one number, whole seconds within `limits` (5 to 3600), which the settings view takes in a single entry field; there is no
list of values, so an interval set on the command line or in the file is shown as it is. `settings.usageIntervalSeconds` (and the status' `display.interval`) is the interval in force: the configured one, or, while the application
waits longer after HTTP 429s, that longer wait in whole seconds rounded up. Applying values saves what is sent, so applying while backing off saves the longer
wait as the configured interval. `defaults` and `GET /api/config` are not changed by a back-off.
The same keys are in `settings.json`, which
is created with the defaults if it is missing.

## `POST /api/settings`

The body is an object with all keys of `settings`. A missing key, a value of the wrong type, an
unknown `timeFormat` or an interval outside `limits` is a `400` and nothing is changed or saved; keys that
are not settings are ignored. A `null` counts as a missing key.

- `200`: applied and saved, effective at once; the body is the same as `GET /api/settings`. The interval
  replaces any command-line override for the rest of the run.
- `400`: the first fault found, the interval being checked first and then the other keys in the order above:
  `The usage interval must be a whole number of seconds from 5 to 3600.` (also for an interval that is not a
  whole JSON number), `The setting X is missing.`, `The setting X must be true or false.`,
  `The setting timeFormat must be "hh:mm" or "hh:mm:ss".`; and for the body itself
  `The request body is not valid JSON.` or `The request body must be a JSON object.`
- `413`: the body is larger than 4096 bytes: `The request body is too large.`
- `415`: the `Content-Type` does not begin with `application/json` (in any case; `application/json; charset=utf-8`
  passes), or there is none: `Send Content-Type: application/json.`
- `500`: the values are valid but could not be saved: `The settings could not be saved: <reason>`. Nothing is changed.


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
  Default 60. It is changed, with the other settings, through `POST /api/settings`;
  this endpoint changes nothing.
- `pollIntervalSeconds`: how often the frontend asks the backend for the latest
  state. Default 1. It is read-only: it is set on the command line, for one run, and
  is never saved or changed through this API. It has no entry under `limits` for that
  reason.

The usage interval is the configured one: a command-line option, else the saved setting, else the default (see the requirements, Refresh behavior), until the
frontend changes it. It is not the longer wait of a back-off after HTTP 429s; `GET /api/settings` and the status' `display.interval` have that one.

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
  "change": { "delta_used": 0.05, "delta_time": 60, "delta_used_text": "+0.05", "delta_seconds_text": "60 s" },
  "display": {
    "time": "14:00",
    "timeTooltip": "Last update: 8 Oct 2026, 14:00:00",
    "spend": {
      "percentText": "19%",
      "percentTooltip": "19% of the budget spent. Severity: normal",
      "used": "186.02",
      "limit": "1,000.00",
      "usedTooltip": "Credits used, in USD",
      "limitTooltip": "Credit budget, in USD",
      "severityText": "normal",
      "severityKind": "normal"
    },
    "windows": [],
    "placeholder": null,
    "countdown": { "text": "42 s", "tooltip": "Seconds until the next refresh (negative when overdue)" },
    "countdownAlert": null,
    "interval": { "text": "60 s", "tooltip": "Time between usage requests" },
    "deltaUsed": { "text": "+0.05", "tooltip": "Change in the amount used since the previous reading, in USD" },
    "deltaTime": { "text": "60 s", "tooltip": "Time since the previous reading" },
    "message": null,
    "show": {
      "percentage": false, "currency": false, "interval": false, "deltaUsed": false, "deltaTime": false,
      "historyIcon": true, "logIcon": true, "errorIcon": true
    }
  },
  "historyStamp": "48211-1791468293000"
}
```

Every member of `display` is always there, as `null` when it has nothing to say. `severityKind` is one of
`normal`, `warning`, `critical` and `other` (a severity the backend does not know), and `null` with
`severityText` when the response sent no severity; a client makes its colour from it. `message.kind` is
`stale` when a reading is still on show and `error` when there is none, and `message.text` is
`Refresh failed at <time>: <message>`. `interval` is always sent, whatever its switch says; `show.interval`
says whether it is shown. An amount that is missing is `—`. A plan window is
`{"name": "five_hour", "utilizationText": "12.3%", "resetsText": "in 2 h 5 min"}`, where `resetsText` can also be
`reset unknown`, `reset due`, or `resets ` and the text as sent when it is not a time. The forms of all these
texts are in the requirements (How the figures are written).

- `display`: what the window shows, finished: the frontend does no calculation or formatting of readings.
  `time` (the local time of the last reading, `14:24` or `14:24:53` by the `timeFormat` setting) and `timeTooltip`
  (`Last update: 8 Oct 2026, 14:24:53`); `spend` (`percentText`, `percentTooltip`, `used`, `limit`, `usedTooltip`,
  `limitTooltip`, `severityText`, `severityKind`) for a usage-based account; `windows` (`name`, `utilizationText`,
  `resetsText` such as `in 2 h 5 min`) for a plan account; `placeholder` (`Loading…`, `No data`, `No usage reported`);
  `countdown` (with `countdownAlert`, the message of an HTTP 429, which makes the countdown red and is shown when it is hovered; the 429
  has no `message`), `interval` (the time between usage requests in force, `60 s`), `deltaUsed` and `deltaTime` as
  `{text, tooltip}` or `null`; `message` as `{kind, text}` or `null`; and `show`, which of `percentage`, `currency`, `interval`, `deltaUsed` and
  `deltaTime` the settings switch on (the countdown is always shown), and which of the buttons `historyIcon`, `logIcon` and `errorIcon` there are (the settings button is always there). With `currency` on, `spend.used` and `spend.limit` have
  the symbol before the number: `$` for US dollars, any other currency its code and a space (`EUR 186.02`), none when the response named no currency. The raw values above stay for
  other clients. Numbers use a dot and `,` for thousands whatever the machine's language (the cells of the history are the exception: they are the file's own text, `1000.00`).
- `historyStamp`: a text that changes whenever a row is added to the history file, a row of a failed refresh as well as a reading: the file's size in bytes, a `-`, and its time of last change in milliseconds.
  It is empty when there is no file, when what is there is not an ordinary file, and when the size or the time cannot be read. A client that
  shows the history reads it again when the stamp is not the one it read, and should treat the text as opaque.
- `change`: what changed between the newest reading (the newest history row that has amounts) and the row
  directly before it in the history file, worked out by the backend; `null` when there is no usage or no
  history. `delta_used` is the change in the amount used, `delta_time` the whole seconds between the
  two rows, with the finished texts `delta_used_text` (`+0.05`, `null` for a change of zero) and `delta_seconds_text` (`63 s`, in seconds, never minutes, as the
  history has it; there is no short minutes form). Either is `null` when it cannot be worked out: the first row of a run has neither, a failed row
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
  "lines": ["2026-10-08 16:24:53 INFO    [UsageApp] Starting java-aip-usage v0.25", "..."]
}
```

A line is `yyyy-MM-dd HH:mm:ss LEVEL [Name] message` in local time. A client that groups the lines of an
entry, or marks where a run starts, goes by that form: an entry begins with a line that begins with the date
and time, and a run with the line that has `] Starting java-aip-usage`.

- `lines`: the last 1,000 lines at most, oldest first, so the newest is last. Only the last 512
  kilobytes of the file are read, since the log is never rotated.
- `truncated`: `true` when earlier lines were left out. When the file is larger than the 512 kilobytes the
  first line of what was read is dropped, so that none is ever shown half.
- `exists`: `false`, with no lines, when there is no log yet, or what is there is not an ordinary file.
- `500` with `{"error": "The log could not be read."}` if the file cannot be read.

## `GET /api/history`

Read-only. The usage history, for the panel the history button opens in the main window. The backend sends the lines **finished**: the
page only draws them, and works out, sorts and decides nothing. The status, the interval and the duration of a row never reach it.

```json
{
  "file": "java-aip-usage.csv",
  "exists": true,
  "columns": ["time", "used", "limit", "\u0394 used", "\u0394 time", "Cur."],
  "wide": false,
  "note": "Showing 640 of 1,500 lines: 700 zero usage and 60 failed hidden, 100 older not shown.",
  "noteHighlight": true,
  "total": 1500,
  "lines": [
    { "cells": ["16:46:11", "260.66", "1000.00", "+0.05", "63 s", "$"], "start": false, "failed": false, "title": "" },
    { "cells": ["16:44:08", "failed", "", "", "121 s", ""], "start": false, "failed": true, "title": "" },
    { "cells": ["16:42:07", "260.61", "1000.00", "", "", "$"], "start": true, "failed": false, "title": "The program started here" }
  ]
}
```

- `columns`: the titles, in the order of the `cells`: the time (`time`, or `date time` when the date setting is on), `used`, `limit`, when their settings are on `\u0394 used` and `\u0394 time`, and
  last, the right-most column, the currency (`Cur.`). The currency cells are `$` for US dollars and the bare code for any other currency (`EUR`), empty when there is none.
- `lines`: the newest 1,000 of the lines that are shown, **sorted by the date and time, latest first** (not merely the file reversed, so a file that is out of order is still right; of two
  with the same time, the one written later is first). Each has `cells` (the strings of the table, an empty cell empty: the time as the setting says, `failed` in the place of the amount of a
  failed line, `\u0394 used` with its sign and empty for no change or for a change of zero, `\u0394 time` in whole seconds, `63 s`, never minutes), `start` (it is the first line of a run),
  `failed` (it is the line of a failed query) and `title` (the hover text, `The program started here` for a start, else empty). A line of the file without the columns (a row from before the
  currency was one, with three, is given an empty currency), and the header, are not lines.
- **Hidden lines.** The settings `historyZeroLines` and `historyFailedLines` (both on unless switched off) say whether the **zero usage lines** (a line whose change in the amount used, against the row
  directly before it in the same run, is exactly zero, or a startup line, the first line of a run, which has no change; never a failed line, and a failed startup line is a failed line) and the **failed lines** are shown. The changes of the lines that are shown (`\u0394 used`,
  `\u0394 time`) are worked out against the previous line that is shown in the same run, so with lines hidden they span them; a row that begins a run begins it even if it is hidden.
- `note`: one line for the page to show above the table, or `null` when everything is shown: `There is no usage history yet.`, `The history has no rows yet.`, or `Showing N of M lines:` with the zero usage and failed
  lines hidden and the older lines beyond the newest 1,000 that are not shown (counts with a comma for thousands).
- `noteHighlight`: `true` when the note says that lines are left out (the one that starts `Showing`), which the window shows in blue; `false` for the notes about there being no history, and `false` when there is no note.
- `wide`: whether the times have the date, so the first column is wider.
- `total`: how many rows the file has; `exists`: `false`, with no lines, when there is no history yet.
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
    { "time": "11:20:01", "message": "Anthropic returned HTTP 500." }
  ]
}
```

Newest first, at most the newest 1,000. `time` is the local time of day with seconds, whatever the time format setting says.
`405` for any method but `GET`.

## `POST /api/refresh`

Body: `{}`, sent as `application/json`. The body is not read: with the right
`Content-Type`, any body or none will do. Starts a usage fetch now instead of
waiting for the next scheduled one, and returns at once without waiting for the
result. Poll `/api/status` to see it.

- `202` with `{"started": true}`: a fetch was started.
- `200` with `{"started": false}`: none was started, because one is already
  running or already requested, or because refreshing has not started yet or has
  been stopped. Extra requests are therefore harmless, and the frontend keeps its
  button enabled.

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
`1215,420,2,log` or `615,420,2,errors` (the error log is half as wide as the log, rounded up). For the history the width is the page's measure of its own table plus padding and the scrollbar (never less than the row's), so every column
is whole. The host (`WindowFit`) remembers the height of the history and of the log, in the settings
file, and opens them at the larger of that and the page's own; the error log and the settings are not remembered. The host asks about every 150 ms and resizes its window when the answer changes, and lets the person
resize only what it is told may be: nothing for `0` and `3`, the height for `1`, the height and the width for `2`, and never
below the size last reported with `0`. The page measures its own content, never the window, so resizing the
window to match does not change the answer. The Java host (`WindowFit`) ignores anything that is not that
shape (a width or a height of 0 or of more than five digits, a panel name other than the three), keeps the size it sets
between 160 x 32 and 2400 x 1600, and does not apply a size twice; a change of the flag or of the panel alone counts as a new size. A different host,
such as the future Go one, needs only to call the function and resize.

The page exposes nothing else to its host, and the host exposes nothing to the page.

## Protections

The server holds usage data, so it refuses requests that another web page on the
same machine could make:

- A request whose `Host` header is not `127.0.0.1:<port>` or `localhost:<port>`
  (in any case), or that has none, gets `403` with the plain text `Forbidden`,
  for the page's files as for the API. This stops a web page from reaching the
  API through a hostname it controls that resolves to `127.0.0.1` (DNS rebinding).
- Every `POST` must send `Content-Type: application/json`, otherwise `415`. A page
  on another origin cannot do this without a CORS preflight, which the server
  never answers with permission: no `Access-Control` header is ever sent.

These guard against other web pages, not against other programs on the same
machine, which can call the API like the window does; there is no password.

No endpoint accepts, returns or logs the OAuth token. Requests of the window
that succeed are not logged, the status polls among them, since they arrive
every second; one that fails is.
