# Local API

The contract between the frontend, the page with its style sheet and its scripts, and the backend.
It is deliberately small and independent of the language the backend is written in: every version of the
program serves the same frontend by providing the same endpoints with the same shapes. Nothing here is said in
terms of one implementation; what holds for one version only is in the requirements, in that version's section.

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
| `GET` | `/api/log` | The end of the log file, at the level the log panel shows |
| `POST` | `/api/log` | Save the two choices of the log panel, the level and the date |
| `POST` | `/api/log/open` | Show a file a line of the log names, one of the program's own, in its folder |
| `POST` | `/api/log/reset` | Reset the log: keep the file under a name with the date and time, and begin a new one |
| `POST` | `/api/log/merge` | Take the last reset back: put the newest old log in front of the log again |
| `GET` | `/api/history` | The usage history, as finished lines |
| `POST` | `/api/history` | Save the one choice of the history panel, whether its zero usage lines are shown |
| `GET` | `/api/errors` | The errors of this run (every failed refresh), newest first; in memory only |
| `POST` | `/api/text-size` | Save the size of the text of the three lists |
| `GET` | `/api/cost` | The cost control panel: the two bars, finished |
| `GET` | `/api/cost/daily` | The daily limit that follows from a monthly one, in the current month |
| `GET` | `/api/about` | What the About view says: the version, where the files are, what the program runs on |
| `POST` | `/api/about/open` | Show one of the program's three files in its folder |
| `POST` | `/api/folder` | Let the person choose a folder with the system's picker (since 0.81); saves nothing |
| `POST` | `/api/about/copy` | Put the path of one of the three files on the clipboard |

Only a path that begins `/api/` is the API's; a query on it is not looked at. Any
other path under `/api/` is `404` with `{"error":"No such endpoint."}`. Any other
method on these paths is `405`, with the right one in the body and in an `Allow`
header: `{"error":"Use GET."}` and `Allow: GET`; `{"error":"Use POST."}` and
`Allow: POST` for the refresh; `{"error":"Use GET or POST."}` and `Allow: GET, POST`
for the settings, the log and the history. A fault of the backend inside a request is `500` with
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
    "showCostIcon": true,
    "showInterval": false,
    "showDeltaUsed": false,
    "showDeltaTime": false,
    "timeFormat": "hh:mm",
    "historyDeltaUsed": false,
    "historyDeltaTime": false,
    "historyTime": true,
    "historyUsed": true,
    "historyLimit": true,
    "historyCurrent": true,
    "historyDate": false,
    "historyZeroLines": true,
    "historyFailedLines": true,
    "showTime": true,
    "showUsed": true,
    "showLimit": true,
    "showRefresh": true,
    "showCountdown": true,
    "showDuration": false,
    "stayOnTop": false,
    "showTitleBar": true,
    "restoreWindow": false,
    "severalViews": false,
    "logFolder": "",
    "historyFolder": ""
  },
  "defaults": { "...": "the same keys, with the defaults" },
  "limits": { "usageIntervalSeconds": { "min": 5, "max": 3600 } }
}
```

`timeFormat` is `"hh:mm"` or `"hh:mm:ss"`. The interval is one number, whole seconds within `limits` (5 to 3600), which the settings view takes in a single entry field; there is no
list of values, so an interval the file holds is shown as it is. `settings.usageIntervalSeconds` (and the status' `display.interval`) is the interval in force: the configured one, or, with `autoInterval` on, the one the search has found. There is no back-off since version 0.64: an HTTP 429
changes no delay with `autoInterval` off.

`historyTime`, `historyUsed`, `historyLimit`, `historyDeltaUsed`, `historyDeltaTime` and `historyCurrent` say which columns the history has, in the order of the table (`time`, `used`, `limit`, `Δ used`, `Δ time`, `Cur.`); the four first
named are on by default, and `POST /api/settings` may leave `historyTime`, `historyUsed`, `historyLimit` and `historyCurrent` out, which keeps what the settings have (the other keys must all be given). `GET /api/history` has
only the columns that are on, in `columns` and in each line's `cells`.
`restoreWindow` (off by default) says whether the window is opened at a start as it was when the program stopped, and
`severalViews` (since version 0.80, off by default) whether more than one view can be shown at the same time: an icon
then opens its view and leaves the others open. A `POST` may leave either out, which keeps what the settings have; so may it `showCostIcon`.
`logFolder` and `historyFolder` (since version 0.81) are the folders the log (with its old logs) and the usage history (with
the history of invented readings) are kept in, as full paths, and the empty text for the program's folder, which is the
default. A `POST` may leave them out. One that names another folder than the one in use moves the files there at once, before
the settings are saved; a file of the same name that is there already is used and not overwritten. A folder that is not a
full path, is not there, or cannot be written in is refused with `400` and `The folder cannot be written: <reason>.`, and
nothing is moved or saved. The settings file itself stays in the program's folder.
The same keys are in `settings.json`, which
is created with the defaults if it is missing.

### The automatic interval in the settings

`settings` and `defaults` have `autoInterval`, `false` by default: whether the program finds the time between usage
requests itself, between 10 and 300 seconds. `POST /api/settings` must give it like every other setting.

Beside `settings`, `defaults` and `limits` the answer has `configuredIntervalSeconds`: the interval that is saved.
`settings.usageIntervalSeconds` is the interval in force, which, with
`autoInterval` on, is the one the search has found, shorter or longer than the configured one. A page that shows the interval
in force in its field sends the configured one back when `autoInterval` is on, so that it is kept.

The two figures of the search, `autoIntervalSeconds` and `autoRefusedSeconds` (the interval at which the last HTTP 429 came; the key is left out for none), are in
`settings.json` and in no request: the program changes them by itself. The keys `autoLowerSeconds` and `autoUpperSeconds` of versions before 0.64 are not read, and are not written again.

### Cost control in the settings

`settings` and `defaults` have the two limits of cost control: `costMonthlyLimit` and `costDailyLimit`, each an amount in
US dollars as a finished text with two decimals, `"250.00"`, or `null` for none; and `costDailyAutomatic`, `true` by
default, which has the daily limit worked out from the monthly one. Beside them the answer has `costDailyWorkedOut`: the
daily limit in force this month, as a finished text, or `null`: the monthly limit divided by the weekdays, Monday to
Friday, of the current month, rounded down to the cent, while `costDailyAutomatic` is on, and else the one typed in. (Until version 0.79 the answer had `settingsTab` too, the tab the settings view was last on, and `POST /api/settings-tab` stored it; the tab is the page's own to remember since version 0.80, and neither is there any more.)

In a `POST` the three may be given or left out, and what is left out stays as it is. A limit is `null` or an empty
text for none, or an amount, as a number or the text of one, from 0.01 to 1,000,000 with two decimals at most. `400`:
`A limit must be an amount from 0.01 to 1,000,000, with two decimals at most.`; `The daily limit cannot be more than the
monthly limit.`, when the daily limit is typed in and above the monthly one; `The setting costDailyAutomatic must be true
or false.`

Since version 0.82 four more, each optional in a `POST`: `costCurrency` (`false` by default), whether the figures of the cost
control have the currency symbol; `costLimitSource`, `"monthly"` (default) or `"server"`, which limit is counted as the monthly
one (the server's while none is typed, with `monthly`); `costWorkdayHours`, a whole number from 1 to 24, 8 by default; and
`costWorkdays`, the working days typed for the current month, a whole number from 1 to 31, or `null` for Monday to Friday. A
number holds for the month it was given in (the file keeps that month as `costWorkdaysMonth`), and the answer has `null` once
the month is another. `400`: `The limit counted must be server or monthly.`, `The hours of a working day must be a whole number
from 1 to 24.`, `The working days must be a whole number from 1 to 31.` Beside the settings the answer has `serverLimit`, the
limit the server sent with the newest reading as a finished text or `null`, and `weekdays`, Monday to Friday of the month;
`costDailyWorkedOut` is worked out from the monthly limit counted and the working days.

### `GET /api/cost/daily?monthly=<amount>&workdays=<days>`

What the settings view asks while a monthly limit is typed and the daily one is worked out: `{"daily": "11.36",
"weekdays": 22}`, the daily limit that follows in the current month and the days it was divided by: the `workdays` given (since
0.82), a whole number from 1 to 31, or Monday to Friday of the month when it is empty or no such number. With no
`monthly`, or an empty one, `daily` is `null`. `400` for one that is no amount in the range. The page works no limit
out itself.

## `POST /api/settings`

The body is an object with all keys of `settings`. A missing key, a value of the wrong type, an
unknown `timeFormat` or an interval outside `limits` is a `400` and nothing is changed or saved; keys that
are not settings are ignored. A `null` counts as a missing key.

- `200`: applied and saved, effective at once; the body is the same as `GET /api/settings`. The interval
  is in force for the rest of the run and the next.
- `400`: the first fault found, the interval being checked first and then the other keys in the order above:
  `The usage interval must be a whole number of seconds from 5 to 3600.` (also for an interval that is not a
  whole JSON number), `The setting X is missing.`, `The setting X must be true or false.`,
  `The setting timeFormat must be "hh:mm" or "hh:mm:ss".`; and for the body itself
  `The request body is not valid JSON.` (also for a body with anything but white space after its JSON value)
  or `The request body must be a JSON object.`
- `413`: the body is larger than 4096 bytes: `The request body is too large.`
- `415`: the `Content-Type` does not begin with `application/json` (in any case; `application/json; charset=utf-8`
  passes), or there is none: `Send Content-Type: application/json.`
- `500`: the values are valid but could not be saved: `The settings could not be saved: <reason>`. Nothing is changed.


## `GET /api/debug` and `POST /api/debug`

The debug settings, in debug mode only: without `--debug` both answer `404` `No such endpoint.`, so a run that is not in
debug mode has no way to them. They are not among the settings of `/api/settings`, which neither gives nor takes them and
leaves them as they are.

```json
{
  "settings": {
    "fakeBackend": false, "fakeScenario": "normal", "fakeToken": false,
    "fakeAccount": "usage", "fakeIncreaseCents": 137, "fakeBudget": "1000.00", "fakeStartAmount": "186.02",
    "fakeRefuseSeconds": 0, "fakeDelayMillis": 0
  },
  "scenarios": ["normal", "http-401", "http-403", "http-429", "http-429-retry-after", "http-500", "not-json", "empty", "no-spend-no-windows", "trailing-text", "slow", "hang"],
  "accounts": ["usage", "seat"]
}
```

- `fakeBackend`: usage comes from the fake backend the program starts itself. `fakeScenario`: what that backend answers, one of
  `scenarios`. `fakeToken`: a placeholder bearer is sent to the real endpoint instead of a real token.
- **How the fake backend's ordinary answer is shaped**, for the scenario `normal`: `fakeAccount`, one of `accounts`
  (`usage`: an answer with spend; `seat`: one with no spend and the limits of a session and of a week);
  `fakeIncreaseCents`, the most the amount used grows by at an answer, 0 to 100000; `fakeBudget` and `fakeStartAmount`,
  amounts in dollars as finished texts with two decimals, the budget from 0.01 to 1,000,000 and the starting amount from 0
  to the budget; `fakeRefuseSeconds`, 0 to 3600: a request that comes less than that long after the answered one before
  it gets a 429; `fakeDelayMillis`, 0 to 60000: the longest an answer is held back.
- In a `POST` these six may be given or left out, and what is left out stays as it is. An amount may be a number or the
  text of one, `250` or `"250.00"`, with two decimals at most. When only these change, the fake backend that runs is
  told and a refresh follows; the source of usage is not opened anew.
- `400` for one of them that cannot be, with the reason: `The largest increase must be a whole number of cents from 0 to
  100000.`, `The budget must be an amount from 0.01 to 1,000,000.`, `The starting amount must be an amount from 0 to the
  budget.`, `The time within which a request is refused must be a whole number of seconds from 0 to 3600.`, `The longest
  request must be a whole number of milliseconds from 0 to 60000.`, `The setting fakeAccount must be one of usage, seat.`
- `POST` takes an object with the three keys. `200`: saved in `settings.json` and in force at once; the body is that of `GET`.
  Where usage comes from is opened anew: a fake backend is started or stopped, a usage request under way is given up, the reading
  on show is replaced by the newest of the new source's own history or by none, and a refresh follows.
- `400`: `The setting X is missing.`, `The setting X must be true or false.`, or
  `The setting fakeScenario must be one of <the names>.`; nothing is changed.
- `500`: `The debug settings could not be applied: <reason>` when the new source cannot be opened, or
  `The settings could not be saved: <reason>`; nothing is changed, and the source in use stays.

## `GET /api/config`

The frontend asks for this once at startup, and then polls `/api/status` every
`pollIntervalSeconds`.

```json
{
  "usageIntervalSeconds": 60,
  "pollIntervalSeconds": 1,
  "limits": {
    "usageIntervalSeconds": { "min": 5, "max": 3600 }
  },
  "debug": false,
  "listFontSize": 12
}
```

- `listFontSize`: the size of the text of the log, the usage history and the error log, in pixels, as saved: a whole
  number from 10 to 20, 12 by default. The page takes it at startup and changes it through `POST /api/text-size`.
- `usageIntervalSeconds`: how often the backend fetches usage from Anthropic.
  Default 60. It is changed, with the other settings, through `POST /api/settings`;
  this endpoint changes nothing.
- `pollIntervalSeconds`: how often the frontend asks the backend for the latest
  state. Always 1. It is read-only: nothing sets it, saves it or changes it. It has no entry under `limits` for that
  reason.
- `debug`: whether the program was started with `--debug`. The settings window then has a tab `Debug`; without it there are no
  debug settings to ask for (`/api/debug` is a `404`).

The usage interval is the configured one: the saved setting, else the default (see the requirements, Refresh behavior), until the
frontend changes it. `GET /api/settings` and the status' `display.interval` have the interval in force, which with `autoInterval` on is the search's.

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
      "percentTooltip": "19% of the monthly limit spent",
      "used": "186.02",
      "limit": "1,000.00",
      "usedTooltip": "Credits used, in USD",
      "limitTooltip": "Monthly limit sent by the server, in USD",
      "severityText": "normal"
    },
    "placeholder": null,
    "loading": false,
    "variants": {
      "timeShort": "14:00", "timeLong": "14:00:00",
      "usedPlain": "186.02", "usedSymbol": "$186.02", "limitPlain": "1,000.00", "limitSymbol": "$1,000.00"
    },
    "countdown": { "text": "42s", "tooltip": "Seconds until the next refresh" },
    "countdownAlert": null,
    "interval": { "text": "60s", "tooltip": "Time between usage requests" },
    "deltaUsed": { "text": "+0.05", "tooltip": "Change in the amount used since the previous reading, in USD" },
    "deltaTime": { "text": "60s", "tooltip": "Time since the previous reading" },
    "duration": { "text": "87ms", "tooltip": "How long the latest usage request took" },
    "message": null,
    "show": {
      "percentage": false, "currency": false, "interval": false, "deltaUsed": false, "deltaTime": false,
      "historyIcon": true, "logIcon": true, "errorIcon": true, "costIcon": true,
      "time": true, "used": true, "limit": true, "refresh": true, "countdown": true, "duration": false,
      "severalViews": false,
      "status": true, "dayPercentage": false, "dayUsed": false, "dayLimit": false
    }
  },
  "historyStamp": "48211-1791468293000",
  "heights": { "history": 612, "log": 0 }
}
```

Since version 0.83 the tooltips of the percentage and the limit say `monthly limit`, and the severity is in no tooltip (it
is still sent, as `severityText`, for other clients). Every member of `display` is always there, as `null` when it has nothing to say. `severityText` is the
severity as the response sent it, `null` when it sent none; the window colours nothing by it. `message.kind` is
`stale` when a reading is still on show and `error` when there is none, and `message.text` is
`Refresh failed at <time>: <message>`. `interval` is always sent, whatever its switch says; `show.interval`
says whether it is shown. An amount that is missing is `—`. The forms of all these
texts are in the requirements (How the figures are written).

- `display`: what the window shows, finished: the frontend does no calculation or formatting of readings.
  `time` (the local time of the last reading, `14:24` or `14:24:53` by the `timeFormat` setting) and `timeTooltip`
  (`Last update: 8 Oct 2026, 14:24:53`); `spend` (`percentText`, `percentTooltip`, `used`, `limit`, `usedTooltip`,
  `limitTooltip`, `severityText`); `placeholder` (`No data`, `No usage reported`, or `null`: why there are no amounts; the row does not show the words, it shows gray boxes for the time and the amounts that have no value and says the reason in their tooltips, `Credits used: no data`); `loading` (`true` before the first reading and the first failure: the window then draws a gray box for each element that is switched on and has no value, and there is no placeholder);
  `variants` (the time as `timeShort` and `timeLong`, and the amounts as `usedPlain`, `usedSymbol`, `limitPlain` and `limitSymbol`, whatever the settings say, so that the settings view can show a change of the time format or of the symbol in the row before it is applied; `null` with no reading, and the four amounts `null` with no spend);
  `countdown` (with `countdownAlert`, the message of an HTTP 429, which makes the countdown red and is shown when it is hovered; the 429
  has no `message`; with the countdown switched off there is no `countdownAlert` and the 429 has a `message` like any other failure), `interval` (the time between usage requests in force, `60s`), `deltaUsed` and `deltaTime` as
  `{text, tooltip}` or `null` (the seconds of `countdown`, `interval` and `deltaTime` have no space before the `s`, and `deltaUsed` never has a currency symbol; an unchanged amount is `0.00`, with no sign, and `deltaUsed` is `null` only when the change is not known, which the row shows as a gray box, as it does a `deltaTime` and a `duration` of `null`); `duration` (how long the latest usage request that has ended took, a failed one included, as the history file has it: `87ms`, and `1s034ms` from a second on; `null` until one has ended); `message` as `{kind, text}` or `null` (`kind` is `stale` or `error`, for a failed refresh); `noSpend` (what is to be said of a reading that came and has no spend, or `null` for any other reading: the page shows it below the row, muted, while the mouse is on one of the gray boxes of the amounts, and not otherwise); `titleBar` (whether the setting gives the window its title bar; with it `false` and no panel shown the page draws a border of one pixel round the window, unless the red border of the fake backend is there); and `show`, which of `percentage`, `currency`, `interval`, `deltaUsed` and
  `deltaTime`, `time`, `used`, `limit`, `refresh`, `countdown` and `duration` the settings switch on, and which of the buttons `historyIcon`, `logIcon`, `errorIcon` and `costIcon` there are (the settings button is always there), and `severalViews`, the setting of that name, which is no element of the row: the page reads from it whether an icon leaves the other views open. Since version 0.82 also `status`, the status circle (on by default), and `dayPercentage`, `dayUsed` and `dayLimit`, the figures of the day (off), which the page draws from `spending`; the settings are `showStatus`, `showDayPercentage`, `showDayUsed` and `showDayLimit`, each optional in a `POST`. The row's `spend.limit` and `percentText` are of the limit counted since 0.82, and `limitTooltip` begins `Monthly limit of cost control` when that is the typed one. With `currency` on, `spend.used` and `spend.limit` have
  the symbol before the number: `$` for US dollars, any other currency its code and a space (`EUR 186.02`), none when the response named no currency. The raw values above stay for
  other clients. Numbers use a dot and `,` for thousands whatever the machine's language (the cells of the history are the exception: they are the file's own text, `1000.00`).
- `spending` (since version 0.82): how the day and the month stand against their limits, worked out by the backend from the
  history in use, the settings of cost control and the time (again when one of them changes, and every minute): `state`, the
  status circle's, `green`, `amber`, `red` or `gray`; `tooltip`, its text, two lines (`Today: …` and `Month: …`), or `No limits are
  set: set them in the settings, under Cost control.`; and `day` and `month`, each with `state`, `used`, `limit` and `percent` as
  finished texts (`"4.37"`, `"6.81"`, `"64%"`), `limit` and `percent` `null` with no limit. `spending` is `null` when the history
  cannot be read.
- `heights` (since version 0.80): the heights the window was last left at with the history and with the log shown, `history` and `log`, in pixels of its content with the row, `0` for none. The page gives a list that has another view below it the larger of this and its own ten rows; see the window host contract.
- `historyStamp`: a text that changes whenever a row is added to the history file in use (invented readings have a file of their own, `java-aip-usage.fake.csv`), a row of a failed refresh as well as a reading: the file's size in bytes, a `-`, and its time of last change in milliseconds.
  It is empty when there is no file, when what is there is not an ordinary file, and when the size or the time cannot be read. A client that
  shows the history reads it again when the stamp is not the one it read, and should treat the text as opaque.
- `change`: what changed between the newest reading (the newest history row that has amounts) and the row
  directly before it in the history file, worked out by the backend; `null` when there is no usage or no
  history. `delta_used` is the change in the amount used, `delta_time` the whole seconds between the
  two rows, with the finished texts `delta_used_text` (`+0.05`, `null` for a change of zero) and `delta_seconds_text` (`63 s`, in seconds, never minutes, as the
  history has it, with the space the row's `deltaTime` does not have; there is no short minutes form). Either is `null` when it cannot be worked out: the first row of a run has neither, a failed row
  before it leaves no `delta_used`. See the requirements, Changes between readings.
- `fake`: `true` while the fake backend is in use, so that the figures are invented; the window then has its red border. Always
  `false` in a run that is not in debug mode.
- `refreshing`: a fetch is running now.
- `nextRefreshInSeconds`: whole seconds until the next *scheduled* refresh, to the
  nearest second, worked out by the backend; a frontend shows it and does no counting
  of its own. It is counted from when the most recent request was triggered, by the
  schedule or by `POST /api/refresh`, plus the interval in force. So it starts again after a
  manual refresh, and moves when the interval changes.
  It **can be negative**: that means the refresh is overdue by that many seconds, for
  example because a request is taking longer than the interval. It keeps counting
  while a request runs. It is `null` until the first request has been triggered.
  It changes with time, so it is in the status, which is polled, and not in
  `/api/config`.
- `usage`: the most recent *successful* reading, or `null` before the first one.
  - `fetched_at`: when the backend received it (RFC 3339, UTC).
  - `spend`: the spend of the account; `null` for an account that reports no usage, which
    is a valid answer. Its members may each be `null`.
  - `windows`: always an empty list. It is there so that the reading keeps the shape
    `java-aip usage --format json` prints; the application reads nothing but the spend, and
    `display` has no member for a window at all.
- `error`: `null` after a successful refresh. After a failed one it is
  `{"message": "...", "at": "2026-10-08T12:01:00Z"}`, written to be shown to the
  user. It never contains a credential or any part of the response body.
- `stale`: `true` when `usage` is present *and* the latest refresh failed, so the
  reading may be out of date. The next success clears `error` and `stale`.

Before the first reading, `usage` is `null`: with `error` also `null` the first
fetch is still running; with `error` set it failed, and the message says what to
do (for example log in with Claude Code).

## `GET /api/log` and `POST /api/log`

`GET` gives the end of the log file, for the panel the log button shows in the main window, at the level saved for the panel, or at the one a query names: `GET /api/log?level=warning`, with `error`, `warning`, `info` or `debug`; any other name is `400`. The lines are oldest first; the panel shows them newest first. It names the file, never its path.

```json
{
  "file": "java-aip-usage.log",
  "exists": true,
  "truncated": false,
  "lines": ["2026-10-08 16:24:53 INFO    [UsageApp] Starting java-aip-usage v0.63", "..."],
  "hidden": false,
  "level": "info",
  "date": false,
  "oldLog": false,
  "links": [{ "line": 0, "from": 39, "to": 77, "file": "log" }]
}
```

A line is `yyyy-MM-dd HH:mm:ss LEVEL [Name] message` in local time. A client that groups the lines of an
entry, or marks where a run starts, goes by that form: an entry begins with a line that begins with the date
and time, and a run with the line that has `] Starting java-aip-usage`.

- `lines`: the last 1,000 lines at most of the level, oldest first, so the newest is last. Only the last 512
  kilobytes of the file are read, since the log is never rotated. A level gives its own lines and those of the more
  severe ones; the lines of an entry, a stack trace or a response's JSON, come or stay away with the line that begins
  it; the level is the word after the date and time (`SEVERE` is an error, `FINE` a debug line, a word that is not
  known counts as debug); lines before the first entry of what was read come with `info` and `debug`. The lines are
  as the file has them, the date included.
- `hidden`: `true` when the file has lines and none of the level, so that `lines` is empty.
- `level` and `date`: the two choices saved for the panel, `info` and `false` by default; `level` is the saved
  one also when the query asked for another. Whether the date is shown is the page's to do: with `date` false it
  cuts the first 11 characters of each line that begins an entry.
- `oldLog`: `true` when there is an old log, a file a reset has kept, that `POST /api/log/merge` would put back; the button of the log panel that does it can be pressed only then. It is `false` as well when the program does not write its log to the file, as in a run that logs to the console alone.
- `links`: where in the lines the path of a file of the program's own stands, for the page to make a link of: `line`, counted from 0 in `lines`; `from` and `to`, the place of the path in that line, the first character and the one after the last; and `file`, the name the page asks for the file by with `POST /api/log/open`: `settings`, `log`, `history` (the real usage history), `fake-history`, or `old:<name>` for an old log beside the log, which is known by the form of its name whether or not the file is still there. Only a whole path the program knows is listed; nothing else in a line is a link. A client that cuts the date from a line moves the places by what it cut.
- `truncated`: `true` when earlier lines were left out. When the file is larger than the 512 kilobytes the
  first line of what was read is dropped, so that none is ever shown half.
- `exists`: `false`, with no lines, when there is no log yet, or what is there is not an ordinary file.
- `500` with `{"error": "The log could not be read."}` if the file cannot be read.

`POST` saves the two choices, in `settings.json` under `logLevel` and `logDate`, and answers with them. Both must
be given: `{"level": "warning", "date": true}`. A level that is none of the four, a `date` that is not `true` or
`false`, or one of them missing is `400`, and nothing is saved; a file that cannot be written is `500` with
`The log settings could not be saved: <reason>`. The settings of `/api/settings` do not have the two, and saving
them leaves the two as they are.

## `POST /api/log/open`

Shows a file that a line of the log names in its folder, with the file marked, or the folder alone when the file is no longer there: `{"file": "old:java-aip-usage.2026-10-13_21-06-29.log"}`. The `file` is a name `GET /api/log` gave in its `links`, never a path: `settings`, `log`, `history`, `fake-history`, or `old:` and a name of the form an old log has. The answer is `{"done": true}`. Anything else is `400` with `The file must be one of settings, log, history, fake-history, or an old log.`, and nothing is opened; a desktop that refuses is `500` with `The folder could not be opened: <reason>`. Any other method is `405` with `Allow: POST`.

## `POST /api/log/reset` and `POST /api/log/merge`

The two buttons of the log panel. Neither reads its body, names a file or gives a path away: the program uses the names it knows.

- `POST /api/log/reset`: the log file is renamed to `java-aip-usage.<yyyy-MM-dd_HH-mm-ss>.log`, with the local date and time, in the same directory, which makes it an **old log**, and the program goes on logging to a new
  `java-aip-usage.log`, whose first line is `The log was reset: the lines before are in <the full path of the old log>`.
- `POST /api/log/merge`: the newest old log, the one whose name has the latest date and time, is put in front of the log again: its lines first, then those of the current log, written beside the log as `java-aip-usage.log.tmp` and put in its place
  in one step; the old log is deleted, and the line `The log was merged with <the full path of the old log>` is written at the end. Only a file whose name has exactly that form is an old log.

Both answer `200` with `{"oldLog": true}` or `{"oldLog": false}`: whether there is an old log now. Nothing is logged between the old file and the new: a line another part of the program logs meanwhile is in one of them, whole.

- `409` with `{"error": "There is no old log."}` for a merge with none.
- `500` with `The log could not be reset: <reason>` or `The log could not be merged: <reason>`, in the program's words for what went wrong, when the file cannot be renamed, an old log of that second is there already
  (`an old log of this second is there already`), the two cannot be joined, or the program does not write its log to the file (`the log is not written to a file`). Nothing is changed then.
- Any other method is `405` with `Allow: POST`; the `Content-Type` must be `application/json`, as for every `POST`.

## `GET /api/cost`

The cost control panel, finished: the page only draws it. Read when the panel opens and on each poll while it is open.

```json
{
  "rows": [
    { "label": "Today", "text": "12.50 of 50.00 \u00b7 25%", "tooltip": "Today: 12.50 of 50.00 (25%)", "fraction": 0.25, "over": false, "noLimit": false, "state": "green" },
    { "label": "Month", "text": "262.50 of 1,000.00 \u00b7 26%", "tooltip": "Month: 262.50 of 1,000.00 (26%)", "fraction": 0.2625, "over": false, "noLimit": false, "state": "green" }
  ],
  "note": null
}
```

`used`, `limit` and `percent` (since version 0.83) are the parts of the figures, each for a column of its own: the amount, the limit and `86%`, the last two `null` with no limit; `text` stays as it was. `state` (since version 0.82) is the colour of the bar: `green`, `amber` when the projection of the day or the pace of the month says the limit will be passed (the tooltip then has a second line that says so, as the status circle's does), `red` over the limit, `gray` with no limit. The limit of each row is the one counted (see Cost control in the settings), and the warning sign is gone since 0.82: a bar over its limit is red. `fraction` is the green share of the bar, 0 to 1, never more. `over` is true when the amount is more than the limit (the bar is full and the page shows the warning sign), and `noLimit` when there is no limit to compare with: the bar is gray and empty, the text is
`<used> \u00b7 no limit set`, and there is no percentage. The amounts are written as in the row, with the currency symbol when `showCurrency` is on. The month is the amount used of the newest reading, against the monthly limit; today is that amount less the one of the last
reading before today in the same month (else the first reading of today; a fall is 0.00), against the daily limit in force. With no reading to say what is used both rows are empty with `noLimit`, and `note` is `No usage has been read yet.`; else it is `null`. Any method but `GET` is `405`.

## `POST /api/text-size`

Saves the size of the text of the three lists, which the two buttons at the top of a list change: `{"size": 14}`, a
whole number of pixels from 10 to 20. It is saved in `settings.json` under `listFontSize`, and the answer is the size
that is saved, `{"size": 14}`; `GET /api/config` says it from then on. A `size` that is missing, not a whole number, or
out of the range is `400` with `The size must be a whole number of pixels from 10 to 20.`, and nothing is saved; a file
that cannot be written is `500` with `The text size could not be saved: <reason>`. The settings of `/api/settings` do
not have the size, and saving them leaves it as it is. Any other method is `405`.

## `GET /api/about`

Read-only. What the About view of the settings shows.

```json
{
  "name": "aip usage",
  "version": "0.57",
  "files": {
    "settings": "C:\\work\\aip\\settings.json",
    "log": "C:\\work\\aip\\java-aip-usage.log",
    "history": "C:\\work\\aip\\java-aip-usage.csv"
  },
  "runtime": { "name": "Java", "version": "25.0.1" },
  "toolkit": { "name": "JavaFX", "version": "25.0.4" },
  "system": "Windows 11 10.0",
  "debug": false,
  "fake": false
}
```

- `files`: the **full paths** of the three files. This is the one request that says where a file is; `/api/log` and
  `/api/history` still give a name at most. The server listens on the loopback address only. `history` is the file in
  use, so that of the invented readings while the fake backend is.
- `runtime`, `toolkit`: what the program is built on and the toolkit of its window, each with the `name` it is to be
  shown under and its `version`. The names are the backend's to say, and the page writes them as they come: in the
  example they are those of the version written in Java, and a version in another language names its own. The
  `version` of the toolkit is `unknown` in a run with no window, where it is the window that gives it. (Until
  version 0.77 these were two texts under the keys `java` and `javafx`, and the page had the two names in it.)
- `system`: the name and version of the operating system.
- `debug`, `fake`: whether the program was started with `--debug`, and whether the fake backend is in use.

The logo of the view is the program's icon at 256 pixels, which the server gives the page as `/icon.png`.

## `POST /api/about/open` and `POST /api/about/copy`

What a click on a path of the About view, and on the button beside it, ask for. The body names the file and never a
path: `{"file": "settings"}`, with `settings`, `log` or `history`. The program uses the path it knows for that
name.

- `open` shows the file in its folder: the Explorer on Windows and the Finder on macOS with the file marked, the folder
  elsewhere, and the folder alone when the file is not there yet.
- `copy` puts the full path on the clipboard, as text.
- Both answer `{"done": true}`.
- Any other `file`, a missing one, or one that is not text is `400` with `The file must be one of settings, log,
  history.`, and nothing is opened or copied. No request makes the program open or copy anything but these three.
- `500` with `The folder could not be opened: <reason>` or `The path could not be copied: <reason>` when the desktop
  refuses. Any other method is `405`.

## `POST /api/folder`

Since version 0.81. Opens the system's folder picker over the window, at the folder the body names, `{"start": "C:\logs"}`,
when that is a full path of a folder there, and at the program's folder otherwise; it answers when the person has chosen,
`{"folder": "D:\logs"}`, or `{"folder": null}` when they cancelled. Nothing is saved: the page puts the folder in the
settings form, and it is saved with the settings. `500` with `The folder could not be chosen: <reason>` when there is no
window to show the picker over. `GET /api/settings` says the program's folder, which a folder setting that is empty stands
for, as `programFolder`.

## `GET /api/history` and `POST /api/history`

`GET` gives the usage history, for the panel the history button opens in the main window, with its zero usage lines shown or left out as the setting says, or as a query names it:
`GET /api/history?zeroLines=false`, with `true` or `false`; anything else is `400`. The query saves nothing. The backend sends the lines **finished**: the
page only draws them, and works out, sorts and decides nothing. The status, the interval and the duration of a row never reach it.

```json
{
  "file": "java-aip-usage.csv",
  "exists": true,
  "columns": ["time", "used", "limit", "\u0394 used", "\u0394 time", "Cur."],
  "kinds": ["time", "used", "limit", "deltaUsed", "deltaTime", "currency"],
  "columnTitles": ["When the usage was read", "The amount used at that reading", "The budget at that reading", "The change in the amount used since the last earlier reading: + for a rise, - for a fall", "The seconds since the previous line that is shown", "The currency of the amounts"],
  "wide": false,
  "note": "Showing 640 of 1,500 lines: 700 zero usage and 60 failed hidden, 100 older not shown.",
  "noteHighlight": true,
  "total": 1500,
  "zeroLines": false,
  "lines": [
    { "cells": ["16:46:11", "260.66", "1000.00", "+0.05", "63 s", "$"], "start": false, "failed": false, "title": "" },
    { "cells": ["16:44:08", "failed", "", "", "121 s", ""], "start": false, "failed": true, "title": "" },
    { "cells": ["16:42:07", "260.61", "1000.00", "", "", "$"], "start": true, "failed": false, "title": "The program started here" }
  ]
}
```

- `kinds`: what each column is, in the order of `columns`, by a fixed name: `time`, `used`, `limit`, `monthly`, `daily`, `dailyBy` (the three since version 0.82, after `limit`, titled `month`, `day` and `by`, with the settings `historyMonthly`, `historyDaily` and `historyDailyBy`, off by default and optional in a `POST`; a row written before 0.82 has those cells empty), `deltaUsed`, `deltaTime` or `currency`. A column is known by this, not by its title or its place: the page gives it its width by it,
  and puts the red of a failed line on the cell of `used`.
- `columnTitles`: the tooltip of each title, in the order of `columns`, written by the backend: what the column is. With the date the first is `The date and time at which the usage was read`. The page puts each on its title as it is given.
- `columns`: the titles, in the order of the `cells`: the time (`time`, or `date time` when the date setting is on), `used`, `limit`, when their settings are on `\u0394 used` and `\u0394 time`, and
  last, the right-most column, the currency (`Cur.`). The currency cells are `$` for US dollars and the bare code for any other currency (`EUR`), empty when there is none.
- `lines`: the newest 1,000 of the lines that are shown, **sorted by the date and time, latest first** (not merely the file reversed, so a file that is out of order is still right; of two
  with the same time, the one written later is first). Each has `cells` (the strings of the table, an empty cell empty: the time as the setting says, `failed` in the place of the amount of a
  failed line, `\u0394 used` with its sign, `+0.05` for a rise and `-0.14` for a fall, and empty for a change of 0.00 and for a line with no amount, `\u0394 time` in whole seconds, `63 s`, never minutes), `start` (it is the first line of a run),
  `failed` (it is the line of a failed query) and `title` (the hover text, `The program started here` for a start, else empty). A line of the file without the columns (a row from before the
  currency was one, with three, is given an empty currency), and the header, are not lines.
- **Hidden lines.** The settings `historyZeroLines` and `historyFailedLines` (both on unless switched off) say whether the **zero usage lines** (a line whose change in the amount used is 0.00: the change of its `\u0394 used`, against the last earlier line that has an amount, whatever lies between; a startup line is a line like any other in this; never a failed line, and a failed startup line is a failed line) and the **failed lines** are shown; since version 0.81 the failed lines go with the zero usage lines, so a failed line is shown only when both are on (and when the query shows the zero usage lines). The changes of the lines that are shown (`\u0394 used`,
  `\u0394 time`) are worked out against the previous line that is shown in the same run, so with lines hidden they span them; a row that begins a run begins it even if it is hidden.
- `note`: one line for the page to show above the table, or `null` when everything is shown: `There is no usage history yet.`, `The history has no rows yet.`, `No column of the history is switched on. Switch one on in the settings, under History view.` when the file has rows and every column is switched off (`columns` and `lines` are then empty, and `noteHighlight` is `true`), or `Showing N of M lines:` with the zero usage and failed
  lines hidden and the older lines beyond the newest 1,000 that are not shown (counts with a comma for thousands).
- `noteHighlight`: `true` when the note says that lines are left out (the one that starts `Showing`), which the window shows in blue; `false` for the notes about there being no history, and `false` when there is no note.
- `wide`: whether the times have the date, so the first column is wider.
- `total`: how many rows the file has; `exists`: `false`, with no lines, when there is no history yet.
- `zeroLines`: whether the zero usage lines are shown as the setting `historyZeroLines` has it, which is what is saved; it is the saved one also when the query asked for the other. The button of the
  history panel takes its state from it.
- `500` with `{"error": "The usage history could not be read."}` if the file cannot be read.

`POST` saves the one choice of the panel, as the setting `historyZeroLines` of `settings.json`, which is the one the settings view has as `Zero usage lines`, and answers with it:
`{"zeroLines": false}`. A `zeroLines` that is not `true` or `false`, or is missing, is `400` (`The setting zeroLines must be true or false.`, `The setting zeroLines is missing.`), and nothing is saved;
a file that cannot be written is `500` with `The history settings could not be saved: <reason>`. The other settings are left as they are.

Any other method on `/api/history` is `405` with `Allow: GET, POST`; `/api/errors` is `GET` only.

## `GET /api/errors`

Read-only. The errors of this run, for the error log panel: one for each failed refresh, an HTTP 429 and a problem
with the token included. They are kept in memory only and are gone when the program stops. Nothing here is a
credential or a file name.

```json
{
  "entries": [
    { "time": "11:34:42", "message": "Anthropic is rate limiting usage requests (HTTP 429)." },
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
and the width, `3` nothing either, but not the window with nothing open, and `4` the width only, from the width
asked for up, the height being the content's (the last field may be left out, meaning `0`). While the log or the usage history is shown the page asks for more: a height of ten times
the row's *as it was when the panel opened*, which it keeps reporting whatever the row does after, so a
message line coming or going does not move the window; and a width of the row's plus the width of the
panel's vertical scrollbar, which the page measures and always reserves (three rows plus it for the log).
Those are the starting point, not the size the window was dragged to. While the settings are shown it
reports flag `3`, the name `settings` (`520,658,3,settings`) and the height of the whole page, the row and its message lines and the settings, so the
window is exactly as tall as they need, and follows them. While the tab `About` of the settings is shown the flag is `4` (`520,441,4,settings`): the window may be made wider, so that the paths can be read in a line each, and never narrower than the width reported, which is the same least width as on the other tabs whatever the window has; the height reported is that of the page at the width the window has, so it gets less as the window gets wider. That height is the one thing the page reports that follows from the window, and it is not the person's to drag. The last field names the panel that is open, and is left out when none is: `415,420,1,history`,
`1215,420,2,log` or `615,420,2,errors` (the error log is half as wide as the log, rounded up). The cost control panel reports flag `3`, with the width of the history's table and the height its two bars need: `415,160,3,cost`. For the history the width is the page's measure of its own table plus padding and the scrollbar (never less than the row's), so every column
is whole. The host remembers the height of the history and of the log, in the settings
file, and opens them at the larger of that and the page's own; the error log and the settings are not remembered. For as long as a panel is named that can be resized, which is every panel but `cost`, the host gives the window its title bar, whatever the setting `showTitleBar` says; with none named, and with `cost`, it follows the setting. The host asks about every 150 ms and resizes its window when the answer changes, and lets the person
resize only what it is told may be: nothing for `0` and `3`, the height for `1`, the height and the width for `2`, the width for `4`, and never
below the size last reported with `0`. A width the person has given the window with `2` or `4` is kept for as long as the same panel is named with the same flag: a new answer then changes the height only. The page measures its own content, never the window, so resizing the
window to match does not change the answer. The host ignores anything that is not that
shape (a width or a height of 0 or of more than five digits, a panel name other than `history`, `log`, `errors`, `cost`, `settings` and `debug`, the last of which the page no longer reports: the Debug tab is a tab of the settings), keeps the size it sets
between 160 x 32 and 2400 x 1600, and does not apply a size twice; a change of the flag or of the panel alone counts as a new size. A different host,
in a version written in another language, needs only to call the function and resize.

**Several views at once** (since version 0.80, with the setting `severalViews`). The views shown are named together, in
the order of their icons (`cost`, `history`, `log`, `errors` since version 0.81; `log`, `history`, `errors`, `cost` before) and joined by `+`, and a fifth field follows when a list
is among them: the height the lowest list has alone, the row included. `371,522,1,cost+history,490` is the cost control above
the history, the history at 490 with the row, and the two together 522. The width is that of the widest view by its
own rule, the height the row and every view at the height it has alone; a list that has another view below it is given
the larger of the height the status says under `heights` and its own ten rows, less what of the window is no view's (the row, its message lines, the borders and the room below the lowest view), so that it is as tall as when it is the lowest. The flag is `1` when a list is among them,
`2` when the log or the error log is, and `3` when none is. Since version 0.81 the settings may stand beside the views, when
they were opened with `severalViews` on: they are then named first, `settings+cost+history`, count with their least width and
the height of their tab, and the flag is `2` with a list on their tab `About`, `4` there with no list (the width free), and
as above otherwise. With `severalViews` off they replace the views, as before, and are named alone. The host gives the window its title bar when any view named is one but `cost`. What of the height is not the
lowest list's, the total less the fifth field, stays as the page asked; the lowest list takes what the person drags the
window to, and when it is the history or the log that height, less the views above it, is what the host remembers for
it, and gives it when the same list is next the lowest or alone. It never lets the window be dragged lower than the views
above that list and a row of it. A report that names a view twice, more than four, a name that is no view, the views out
of the order of their icons, or the settings anywhere but first is ignored like any other that is not this shape.

`window.currentView()` says what is shown, for the host to store when the program stops: `cost+history,` for views, and
`settings,general` for the settings and their tab, `settings+cost+history,general` for the settings beside views. The host stores the views in `lastPanel` of `settings.json` with commas,
`cost,history` or `settings,cost,history` (a file of 0.80, `history,cost`, is read in the new order), and at the next start hands them to `window.restoreView(panel, tab)`, which takes either; it opens every
view named when `severalViews` is on and the first of them when it is off (the settings alone, when they are named), leaves out a name it does not know, and
answers `false` while it cannot yet tell, so that the host asks again.

**The room of the screen** (since version 0.82). The host tells the page how tall its window may be on the screen it is on, in
CSS pixels of content, by `window.screenRoom(pixels)`, at the start and whenever that changes; the page answers `true`. When
several views need more than that, the page shares it among the lists, each at least five of its lines, asks for no more
than the room, and leaves out the fifth field, the height the lowest list has alone: the host then remembers nothing of the
height. The host itself never makes the window taller than the room.

The page exposes nothing else to its host, and the host exposes nothing to the page.

The host also asks the page where a move of the window may begin. While the window has no title bar, a press of the
left mouse button calls `window.dragStartsAt(x, y)`, with the place of the press in CSS pixels. The page answers `true`
anywhere but on something that is pressed for its own sake, a `button`, a `select`, an `input`, a `textarea`, a
`label` or a link, and anything inside one. On `true` the host moves the window with the mouse for as long as the
button is held, and keeps those moves of the mouse from the page; an answer that is not `true`, or a page that has no
such function, begins no move.

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
