# Requirements

## Goal

This project provides a small client for monitoring Anthropic OAuth usage data. It should fetch the current usage snapshot and present it in a readable, continuously refreshed form.

It draws on the neighbouring repositories for how to authenticate and how to handle the OAuth token (see Implementation context).

**This document holds for every version of the program, whatever language it is written in.** It says what the program
does, not what it is made with. The first version is written in Java; a second is to be written in Go, and both are to
meet what is said here, so that one can take the place of the other: the same window, the same files with the same
names and the same bytes in them, the same local API, the same lines in the log. What holds for one version only, how
it is built and started, which toolkit hosts its window and which systems it is made for, is in a section of its own
for that version, at the end: The Java implementation, and The Go implementation.

Where a rule says "since version 0.50", the number is the Java program's, in which these requirements grew; it says
when the rule came, and a version in another language has all of them from its first.

## Source data

The application fetches usage data from the Anthropic OAuth usage endpoint:

`https://api.anthropic.com/api/oauth/usage`

The part before the path, `https://api.anthropic.com`, is the base URL, and it is
configurable: a command-line option (`--anthropic-url <url>`, see Command line)
points the application at another server. The default is Anthropic's own, so a run
that says nothing about it behaves as it always has. In debug mode a run can instead fetch from
the application's own fake backend (see Debug mode and Fake backend), which needs
no URL because the application knows where it put it.
The path is always `/api/oauth/usage` and is appended to whatever
base URL is in force; it is never configurable, because what the application
parses is the document that path serves.

The JSON examples below show the application's normalised view of a usage
reading, the same shape `java-aip usage --format json` prints. They are not the
raw HTTP response. The endpoint sends no `source` or `fetched_at`: the
application stamps `fetched_at` with the time it received the response. It
sends spend as minor units under a `spend.enabled` flag (`{"amount_minor":
18602, "exponent": 2}` is 186.02), among unrelated and placeholder keys. The application maps the
raw response to the shape below; real responses are in
`src/test/resources/fixtures/`.

Of the response the application reads `spend` and nothing else: whatever the response carries beside
it is passed over. The reading contains the spend details and a `windows` array that is always empty,
kept so that the shape stays the one `java-aip` prints:

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

## Authentication

The client must obtain an OAuth access token before it can fetch usage data.

- Reuse and adapt the token-fetching implementation from the sibling `java-aip` project; do not add a runtime dependency on that repository.
- The token flow requires the Claude Code CLI to be installed and logged in.
- The token should be fetched once and then reused for usage requests.
- The token is kept in the memory of the running program and nowhere else. It is never written to the settings file, the usage history, the log or any other file, and no request to the local server returns it; a new run obtains it again.
- If the usage endpoint responds with HTTP 401, fetch a fresh token and retry the request once. Do not refresh the token for other HTTP or network failures.
- **The token is obtained at the first refresh, not before it**, so the window is on the screen while `claude` runs.
- **The version of Claude Code is logged once at the start** (since version 0.90), at the information level: the program runs
  `claude --version` (the request said `claude -version`, which the CLI does not take) and logs `Claude Code version: <what it
  printed, the first line>`. It is run in the background, so that the window does not wait for it, and with a time limit of 10
  seconds. When it cannot be run, or does not answer in time, one line at the information level says so: `Claude Code version
  could not be read: <reason>`. **At every start, whatever the source of the readings**, the fake backend and the placeholder token
  included, so that the log always says which version is installed (first written to leave those out).
- **What is kept after each outcome.** A failure that is not a 401, whatever it is (another status, the network, a response that cannot be read), keeps the token for the next refresh and is not tried again within the refresh. On a 401 the rejected token is dropped before a fresh one is asked for, so that it is never sent again: if getting the fresh one fails, the refresh fails with the message of the token flow and the next refresh starts the flow anew. If the retry is rejected with a 401 as well, no token is kept, and every later refresh runs the token flow until one is accepted. If the retry fails in any other way, that failure is reported as it is and the fresh token is kept; a 429 answered to the retry is treated as any 429 (see Refresh behavior).
- If the user is not logged in or the token is unavailable, the application should show a clear user-facing message instead of failing silently. The same goes when `claude` cannot be found on the `PATH` (the message says what to do, including that a changed `PATH` needs a restart) and when it is found but cannot be run.
- If `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` is set, `claude` would send that credential instead of its OAuth login token, so the application does not capture one: it shows a message naming the variable, never its value, and asks for it to be unset and the application restarted.
- In debug mode the whole token flow can be left out with the debug setting `Placeholder token` (see Debug mode). The application then obtains no token and sends a fixed placeholder bearer instead, `placeholder-no-real-token-was-obtained`, so it runs with no Claude Code installed, nobody logged in, and no paid account. Obtaining it cannot fail. The placeholder is deliberately not shaped like a credential, so the masking of the log (see The log) has nothing to hide in such a run and its lines stay readable, and so that nobody can mistake it for a key that leaked. It exists for running against a server that does not look at the token, and for not spending real requests on the usage endpoint while developing. A run that uses it is said so in the log, so that a reading taken this way cannot be mistaken for a real one.
- The fake backend leaves the token flow out by itself, because it ignores the token: with it in use no token is obtained, whatever the setting of the placeholder token says.
- The placeholder token and `--anthropic-url` are independent. A placeholder bearer sent to the real endpoint is rejected, which is the ordinary HTTP 401 path and not a special case; a real token is used against a custom URL, which is what pointing the application at a proxy in front of the real endpoint needs. Neither combination is refused.
- Without `--debug` a real token is always obtained: the debug settings count for nothing then.

Example token output:

```json
{
  "access_token": "sk-ant-..."
}
```

## Refresh behavior

- Fetch usage immediately when the application starts, then repeat at the configured interval.
- The default backend usage-fetch interval is 60 seconds. Configure it in seconds in the settings view (see Settings), and nowhere else: there is no command-line option for it. The settings file and the settings view accept values from 5 through 3600 seconds, the settings view in a single entry field. The default is a minute because the usage endpoint appears to accept about one request a minute over the long run: a faster pace, such as 30 seconds, is allowed but draws HTTP 429 after roughly ten minutes.
- Save a usage-fetch interval changed in the frontend to the settings file (see Settings). A committed valid frontend value is sent to the backend and is in force at once.
- Changing the backend usage-fetch interval does not cancel a request already in progress. Apply the new interval to the next scheduled request, measuring the interval from when the current/most recent request was triggered. If the new interval has already elapsed, start the next request as soon as no request is running; otherwise wait until the interval elapses. Changing the interval does not otherwise trigger an extra immediate request.
  - If a refresh fails, keep the last successful data visible, mark it as stale, and show the error. Resume normal display after the next successful refresh.
- **An HTTP 429 does not make the application ask less often** (since version 0.64). Before, it started a back-off: the wait doubled with each 429, up to 5 minutes
  or the `Retry-After` of the server, and each success took an eighth off it. That is gone. What follows a 429 depends on the checkbox `Automatic` of the settings:
  - **With `Automatic` off the delay between two requests is exactly the configured interval**, always: after a success, after a 429 and after any other failure. A `Retry-After` of the server is written in
    the log line of the request and changes nothing, for a 429 as for any other status. The 429 itself is shown as it was, the red countdown or the message line and the error log, and the next request
    comes after the interval.
  - **With `Automatic` on the program searches for the interval** (see The automatic interval); the delay is the interval of the search, and a longer wait that the server names with a 429 is honoured.
- **The interval in force** is at every moment the delay to the next request: the configured interval with `Automatic` off, the interval of the search with it on. It is the one the row's interval item shows, and
  the countdown counts down to it. The refresh button is never held back, since a person asked.
- **The message of a 429 does not say when the next try is** (since version 0.70). Until 0.69 it ended with `Next try in N s` or `N min`, the interval rounded up to whole minutes, which read as another figure than the interval item (175 s was `3 min`). It is now
  `Anthropic is rate limiting usage requests (HTTP 429).` and nothing more; the time is in the countdown and in the interval item of the row, which are exact.
- **A manual refresh** starts a request at once and the next scheduled one comes an interval after it. With `Automatic` on it moves the search like a scheduled request.
- **Changing the interval** does not end the request that is running, and the next one is due an interval after the one before, as the line above says.
- **A request that takes longer than the interval in force is followed by the next one as soon as it ends**, with no pause, whether or not the interval was changed meanwhile.
- **Only an HTTP 429 moves the search** (with `Automatic` on): a `Retry-After` on any other status, a 503 for example, is written in the log line of the request and changes nothing.
- The backend reports the time until the next scheduled refresh together with the
  status, in whole seconds and possibly negative, as described under Compact window.
  The frontend does not work it out.
- The backend alone fetches usage from Anthropic on this interval. UI status polling must not trigger an Anthropic request.
- The UI-to-backend status polling interval, how often the window asks the backend for the latest state, is not a setting. It is 1 second, always: it has no command-line option, no frontend control, is never changed while the application runs, and is never saved, and a value left in an older settings file is ignored.
- When the UI starts, it must request the effective intervals from the backend, and it uses the polling interval to poll the backend for the latest available state. It shows the usage-fetch interval, and every other setting, when the settings view is opened, and it asks the backend for the values each time the view is opened, not only at startup. If the backend's values have changed in the meantime, for instance because another client changed them, the view shows the new values. If the backend cannot be reached at that moment, the view says so and shows no values, since the frontend keeps none of its own.
- Provide a UI action to fetch usage immediately. It must call the backend, which starts an Anthropic usage request without waiting for the next scheduled refresh and returns immediately. Do not run overlapping usage requests; if a refresh is already in progress, return immediately without starting another one. Keep the manual-refresh action enabled; extra clicks while a request is in progress do not start additional requests.
- Closing the application window must terminate the program and stop its backend server, scheduled tasks, and other background resources.
- The program shuts down the same way, with the same lines in the log, when the process is asked to stop without the window being closed, for example by `SIGTERM` or `SIGINT`. A forced kill (`SIGKILL`) cannot be caught by any program, so it leaves nothing in the log.

## Display requirements

**The frontend does no calculation.** Every figure the window shows that is worked out from
others is worked out by the backend and sent ready: the differences between readings, the time
until a reset or the next refresh, the percentage, the ordering of the history, and the cut of a time
to hours and minutes or to seconds. The frontend only places what it is given, and chooses between
what the backend sent and what to show where a setting says so. It stores no settings of its own.

The layout of the window is governed by [Compact window](#compact-window); where
that section and the descriptions below differ on presentation, it wins.

The usage data must be displayed in a simple graphical user interface (GUI)
that is easy to read at a glance. `fetched_at` must always be shown. The
remaining fields are those of `spend` (see Source data). Present:

- `used` and `limit`, as two plain numbers, with no currency sign unless the setting for it is on (see Settings);
- `currency`, in the tooltips of those two numbers;
- `percent`, as a percentage;
- `severity`, named in the percentage's tooltip and shown nowhere else: it colours nothing.

Section Compact window says where each goes and what the tooltips say.

The application is a desktop program: it shows the browser-based interface in a minimal
window of its own, without the usual browser controls. Which systems a version of the
program is made for is said for that version (see The Java implementation and The Go
implementation). Keep the frontend as independent from the backend as practical, so that
it is the same in every version, whatever language the backend is written in.

The application should serve its HTML, CSS, and JavaScript
interface locally and open it automatically in its window.
Keep the frontend dependency-light and independent of the details of any one
implementation.

As built, the frontend is plain HTML, CSS and JavaScript, four files served as they
are written: no framework, no library of anyone else's, no TypeScript and no build
step, and nothing fetched from the network. A build toolchain is to be added only if
the interface grows enough to justify one.

**The window is a host for the page**: a web view, with nothing of a browser round it,
in a window the program owns and can size, place and style. Which web view and which
window toolkit that is, is each version's own choice, said in its section; what the host
must do is the same for all of them, and is said here and in the contract with the page.

**The local API is a contract, and it is written down.** The window and the application
talk over the small local JSON API, and its endpoints, the shape of every response and
the rules the server applies are kept in a document of their own,
[docs/api.md](api.md), and kept level with the program. The API is to stay small, and
its shapes are defined in terms of JSON rather than of the types of a language behind them, so
that a backend written in any language serves the same contract and keeps the
page it is read by. A change to what an endpoint returns is a change to that document.

**Resizing the window is the host's business, and outside that contract.** A window that
is as small as its content has to change size when a message appears or a panel opens, and
only the program that owns the window can resize it, so the page tells its host the size it
needs and which panel is open, and the host sizes the window and remembers the heights (see
Remembered heights). This is window management, and every version of the program has a
window host of its own for it: the page, and what it asks of its host, are the same for
all of them. The page-to-host exchange is documented with the API.

## Compact window

The window is a status strip that a software engineer keeps beside their work,
so it must take as little screen space as it can while staying easy to read.

**Size and text**

- The window's title is `aip usage` followed by the program's version, for example
  `aip usage v0.01`. The version is written in the source code as one constant, is
  increased by hand whenever the program changes, and starts at 0.01. The page inside
  the window is titled `aip usage` without the version, so the version is in one place.
  It is the program's own version and has nothing to do with any version the build tool keeps:
  changing that one changes nothing the window shows, and raising this
  constant is the whole of releasing a new version.
- The window is as small as its content allows, with no empty space around it.
  In its normal state it is a single row, roughly 330 by 35 pixels of content, which is
  about 62 pixels tall with the title bar. The window cannot be resized by hand: it always
  fits its content and follows it as it changes. The exceptions are while the usage history or the
  error log is shown, when it is ten times as tall as the single row (the history, and the log, may open
  taller, as the person last left them) and can be resized in height, and, for the
  log, three times as wide, and the error log, one and a half times as wide, both resizable in width too (see The log panel, The usage history panel and The error log panel),
  and while the settings are shown, when the window is as tall as the row, its message lines and the settings
  need, exactly (see Settings). A row that would be wider than about 900
  pixels wraps onto a second line.
- **A refresh does not change the window's size.** New figures, the countdown ticking and a changed
  time never make the window bigger or smaller: the fields that change have room for their usual
  values (which, and what happens to a longer one, is under The look of the page). The window still changes size when something other than a refresh asks it to: a message line
  appearing or going, a panel opening or closing, and a setting that adds or removes an item of the row.
- **The window's width is set by the row alone.** A message line, such as an error, never makes the
  window wider: it is wrapped to the width the row has and takes as many extra lines as it needs,
  and the window grows in height only. The same holds for the log. The one thing that sets the width of the
  history is its own table (see The usage history panel).
- The text is easy to read: a sans-serif font of at least 14 pixels, with strong
  contrast. It is set bold throughout (weight 700), with the percentage and the
  spent and budget heavier still (800). No thin or light weights, no fine print. The exceptions
  are the usage history, the log and the error log shown below the row, which are deliberately small and
  condensed, like a log file (see The usage history panel), and the settings view, which is set in 12 pixel
  text so that it needs less room; none of them is ever thin.
- Every time the row and its message lines show is the local time of day only, with no date, as
  hours and minutes, for example `14:24`, or with seconds, for example `14:24:53`, as the time format
  setting says; the default is hours and minutes. This applies to the time of the last refresh and to the
  time of an error alike. The backend cuts the time; the window shows it as received. The history panel
  and the log keep their own forms, with seconds. The one exception is the tooltip on the time (see Tooltips),
  which gives the full date and time.

**The row**

The controls sit in one horizontal row, one after another, with small gaps, in
this order. The refresh button sits snug against the amounts before it, and the countdown
snug against the button, so they read as one group:

1. the time the usage was last refreshed (`fetched_at`), first of all;
2. the percentage spent, when its setting is on (it is off by default);
3. what has been spent and the budget: `used` and `limit`, as two plain numbers
   with no currency symbol, for example `186.02 / 1,000.00`, unless the setting for the symbol is on, when each number
   has it before it, for example `$186.02 / $1,000.00`: a `$` for US dollars and, for any other currency, its code
   and a space, `EUR 186.02 / EUR 1,000.00`; no symbol when the response named no currency. The unit is also given by
   the tooltips;
4. a small refresh button;
5. a countdown to the next refresh, in seconds and with its unit, for example
   `42s`, and `-3s` when the refresh is overdue, with no space before the `s`; it is shown unless it is switched off;
6. the time between usage requests that is in force, the delay, for example `60s`, when its setting is on
   (it is off by default); it is not the countdown, which counts down to the next request, but the interval
   the countdown starts from, as set in the settings, or the longer wait the application is using after
   an HTTP 429 (see Refresh behavior);
7. the change since the previous reading, when its settings are on (both are off by default): first
   the change in the amount used, with its sign and never a currency symbol, for example `+0.05`, then the time since the previous
   reading, **in whole seconds, never in minutes or hours**, for example `63s` or `105s`; see Changes between readings; and after
   them, when its setting is on (it is off by default), how long the latest usage request took, for example `87ms` or `1s034ms`;
8. a small button that shows the log below the row, and hides it again, an icon rather than a word, when its
   setting is on (it is on by default);
9. next to it, a small button that shows the usage history below the row, and hides it again,
   likewise an icon, when its setting is on (on by default);
10. a small button that shows the error log below the row, and hides it again, an icon rather than a word
    (see The error log panel), when its setting is on (on by default);
11. at the right-hand end, after the other buttons, the settings button, a very small icon of
    a gear, not a word and not sliders. It is always there, so that the other buttons can be brought back.

**Every element of the row can be hidden**, the gear alone excepted. The elements of the row, below, names the thirteen, and says how
they are switched and what the row shows before the first reading.

**An item of the row whose switch is on keeps its room.** The interval, the two changes and the request duration (the percentage is part of the figures) are in the row when their
switches are on, and when the program has no value for one at that moment, for instance a time that cannot be worked out, the item is a gray box of the width it would have (see A gray box for a figure that is not known), so the row does not change width from
one refresh to the next. An item whose switch is off is not there and takes no room.

A button that is switched off is not shown and takes no room, so the row is narrower by it (the window follows when the
settings are applied, as for any other item). Without its button a panel cannot be opened, except through the
settings, where every panel button is shown (see The panel buttons while the settings are shown). **A panel whose button is switched off when the settings are left is not given back by them** (see Settings): the row alone is shown, or the
other views that were open. (So since version 0.80, as until version 0.78; in version 0.79 the panel was given back whatever its switch said.)

**While the settings are shown all the panel buttons are in the row** (since version 0.79; before, the buttons followed their switches there as well), and one that is
switched off is dimmed, as the checkboxes stand in the view at that moment, so
that a click on a checkbox is seen in the row (see The panel buttons while the settings are shown). The button of a panel that is shown is always shown,
whatever its switch says, so that the panel can be closed again; when the panel is closed the button goes if it is switched off. A
button that is switched off is therefore never taken away from a panel that is open.

The four icon buttons sit close together: the space between them is small, no
more than about 2 pixels, not the strip's usual gap.

**The icons and the gear stand at the right edge of the window** (since version 0.83; in 0.82 they stood right after the last figure, with the log open the rest of the row empty to
their right, and with only the status circle shown the gear stood beside it):
whenever the window is wider than the row needs, with a list open, a long message line, or a row of few elements in a
window of its least width, the four icons and the gear move together to the right edge, and the room left over is between
the last figure and the first icon. The figures stay at the left. A row as wide as the window is as before.

**The button of the open panel is blue, and bigger.** Of the log, history, error log and settings buttons, the one whose panel is
shown stands out so that it is seen at a glance: it is **blue, the blue of the notes and of the selected tab** (`#0969da` on the light theme and `#58a6ff` on the dark one; since version 0.66, before it was green: a vivid green until 0.64 and the green of the figures in 0.65), its button is 24 pixels instead of 20 and its icon 16 pixels
instead of 12, and its lines are heavier (about 2.2 instead of 1.6; the gear, which is 13 pixels and drawn on a finer grid, is made to look the same). Twenty-four pixels is
the height of the refresh button, so the row keeps its height and the window does not change size when a panel is opened or
closed. When that panel is closed, or another is opened, the new one turns blue and bigger and the one that was goes back to
what it was, as before: gray, 20 and 12 pixels, light lines. No more than one is blue, and none is when no panel is shown; with `Several views at once` on (since version 0.80), the icon of every view that is shown is blue and bigger. The other icons are as they were.

**One green, and the colours of the bars.** Since version 0.82 the amount used and the percentage spent are **red** (`#cf222e` / `#f85149`, the
red of errors) while the amount used is more than the limit the row shows, and today's spend and today's percentage while
today's spend is more than the daily limit (see Limits, projections and the status of spending). **Since version 0.84 they take
the colour of their bar** in the cost control, whatever it is: today's spend and percentage that of `Today`, the amount used and
the percentage that of `Month`, green, orange or red; the orange of a figure is that of the status circle and the bars,
`#ff9900` (since version 0.86; a darker `#c26a00` in 0.85, and the yellow `#b8a000` in 0.84). The limits stay green. The rest of this paragraph holds otherwise. The percentage spent, the amount used, the budget, the change in the amount used, the time since
the previous reading and the request duration are all in one green, `#1a7f37` on the light theme and `#3fb950` on the dark one. The green is
fixed: it does not follow the severity, so nothing in the row turns amber or red as the budget is spent, and it is the
same whether a severity was sent or not, whether a change is up or down, and whatever the settings are. A missing
amount, the dash, is green like one that is there. The time of the reading, the countdown and the time between usage
requests are in the colour of the text, and the countdown is red after an HTTP 429, until a request is answered without one (see Countdown).
**The severity is named nowhere** (since version 0.83; until then in the tooltip of the percentage, as
`132% of the budget spent. Severity: normal`, even with the percentage in red): the tooltip of the percentage says `<percent>% of the monthly limit spent`, and the tooltips of the amounts
say what the number is and its unit, and nothing about the severity.

**Countdown**

- The countdown is the number of whole seconds until the next scheduled refresh, to
  the nearest second.
- It is calculated by the backend, which reports it with the status. The window
  shows it as received and does not work it out itself.
- It is counted from when the most recent request was triggered, whether by the
  schedule or by the refresh button, plus the interval in force (see Refresh behavior). It
  therefore starts again after a manual refresh.
- **With the automatic interval on the countdown is blue** (since version 0.66): its text is in the blue of the open panel's icon and of the notes, `#0969da` on the light theme and `#58a6ff` on the dark one, so that it can be seen at a glance that the program
  is finding the interval. **After an HTTP 429 the red wins**: the countdown is red until a request is answered without one, then blue again. With the automatic interval off it is in the colour of the text, as it was. The interval item of the row, if shown,
  stays green like the other figures.
- **Its tooltip says which mode it is in** (since version 0.66): a second line under the first, `Seconds until the next refresh`. With the automatic interval on it is
  `Automatic interval is on: the interval is now <N> s`, with the interval of the search; with it off, `Automatic interval is off: the configured interval is <N> s`. The backend writes both lines, and the tooltip of the interval item says the same second line.
- It may be negative. A negative value means the next refresh is overdue by that
  many seconds, for example because a request is taking longer than the interval.
  The window shows it as it is, with the minus sign.
- Until the first request has been triggered there is nothing to count to, and the
  countdown is left empty.
- The countdown can be switched off like every element of the row; it is on by default. The time between usage requests is a
  separate item, with its own setting (see Settings).
- **With the countdown switched off an HTTP 429 has a message line** like any other failed refresh, `Refresh failed at <time>:
  <message>`, since there is no countdown to be red.
- **After an HTTP 429 the countdown is red**, until a request is answered without one,, and hovering over it
  shows the error message, in bold red, in a line under the row that is there while the pointer is over the
  countdown (a plain `title` tooltip cannot be coloured, and a tooltip of the page's own would not fit in a window
  as small as the row, which grows for the line as for any message and goes back when the pointer leaves). This is
  the only sign of a 429 in the row: it has no message line, and the figures are not dimmed, since the
  data is not out of date because the server asked us to wait. The red goes at the
  next request that is answered without a 429. The 429 is written to the log and to the error log like every failure.

**Tooltips**

Hovering over these shows what they mean. The currency is the one the response
names, shown as its code, here `USD`.

- The time: `Last update: ` followed by the full local date and time of the last
  refresh, for example `Last update: 8 Oct 2026, 14:24:53`. This is the one place a
  date appears.
- The used amount (the first number): `Credits used, in USD`; since version 0.84 `Monthly credit used, in USD` (`docs/img/img_52.png`), and with no
  currency `Monthly credit used`.
- The budget (the second number): `Credit budget, in USD`; since version 0.83 `Monthly limit, in USD` or `Monthly limit sent by the server, in USD` (see Limits, projections and the status of spending).
- The percentage: how much of the budget is spent, and the severity, for example
  `19% of the budget spent. Severity: normal`, which is the only place the severity shows. Since version 0.83 it is
  `19% of the monthly limit spent`, and the severity shows nowhere.
- The countdown: that it is the seconds until the next refresh, and negative when
  overdue.
- The refresh button says `Refresh now`, and the settings button says `Show the settings`, and
  `Hide the settings` while the view is shown.
- The change in the amount used says `Change in the amount used since the previous reading, in USD`,
  and the time since the previous reading says what it is.
- The log button says `Show the log`, and `Hide the log` while the log is shown. The history button says `Show the usage history`, and
  `Hide the usage history` while the history is shown. The error log button says `Show the error log`, and
  `Hide the error log` while it is shown.
- The countdown, after an HTTP 429: the error message, in bold red, in a line under the row (see Countdown).
- The exact texts: the countdown, `Seconds until the next refresh` (since version 0.67 without the words `(negative when overdue)`, which it had until 0.66; the countdown itself still goes negative), and a line under it that says whether the automatic interval is on, and the interval (see Countdown); the time since the previous
  reading, `Time since the previous reading`; the interval item, `Time between usage requests`. When the response named
  no currency the amounts say `Credits used` and `Credit budget`, and the change `Change in the amount used since the
  previous reading`, with no `, in ...` after them. A percentage that the response did not send has no text, and its
  tooltip is `Share of the budget spent`; `. Severity: <word>` follows only when a severity was sent. (Since version 0.83:
  `Share of the monthly limit spent`, and no severity.)

**How the figures are written**

The backend writes every figure, and it writes them the same on every machine, whatever its language: a dot for the
decimals, a comma for the thousands, English for the month, and the 24-hour clock in the machine's own time zone.

- **Amounts in the row** have two decimals and a comma for the thousands, `1,234,567.89` and `0.00`. A third decimal
  is rounded to the nearer second one and upwards when it is exactly half, on the decimal form of the number, exactly
  as the history file rounds it (see Usage history), so the row and the file always show the same amount. An amount that is missing is shown as a dash, `—`, not as zero and not as nothing, and
  has no currency symbol even when the symbol is on.
- **The currency symbol.** Only the code `USD`, written so, is `$`. A code of blanks alone is no currency. In the history
  the cell is the same without the space after a code.
- **The percentage spent** is the whole number the response sent, with `%`; the program does not round it or work it out
  (but see the reading shown at startup, under Details of the behaviour).
- **The change in the amount used** has two decimals, rounded to the nearer and upwards at half, the comma for
  thousands, and always its sign: `+0.05`, `-1.20`, `+1,234.50`. It never has a currency symbol, whatever the
  setting `Currency symbol` says: the symbol is for the amount used and the budget alone.
- **Seconds in the row** have no space before the `s`: the countdown, `42s` and `-3s`; the time between usage requests,
  `60s`; and the time since the previous reading, `63s`. Everywhere else the space is there: the `Δ time` column of the
  history, `63 s`, and the tooltips (`Automatic interval is on: the interval is now 35 s`).
- **The time since the previous reading** is never less than `0s`.
- **The times** are `HH:mm` or `HH:mm:ss`, with leading zeros. The date of the time's tooltip is `d MMM yyyy,
  HH:mm:ss`, the day without a leading zero and the month in English, `8 Oct 2026, 14:24:53`. The times of the error
  log always have seconds, whatever the time format setting says, and are in the zone the machine has when the panel
  asks.
- **The severity** is the text the response sent, as it was sent. A response with no severity has none: no
  word in the tooltip.
- **The message line** is `Refresh failed at <time>: <message>`, the time as the time format setting says, with or
  without seconds.
- **A reading with no usage** has the time of that reading and its tooltip, since there is a reading; with no reading
  at all the time is a gray box (see Gray boxes in the place of `No usage reported` and `No data`).
- **The change shown in the row** is that of the newest row of the history that has a `used` or a `limit`. There is
  none while the program has no reading, even when the history has rows.

**Changes between readings**

- **The change in the amount used** (since version 0.65) is the amount used of a line minus the amount used of **the last earlier line that has an amount used**, whatever lies between: failed lines
  have none and are ignored, and so is the run they belong to; the start line of a run is a line like any other. Only a line that has an amount used has a change in the amount used. **When there is no earlier
  line with an amount used, the change is the amount used itself.** The arithmetic is exact, on the text of the file. This is worked out on the whole file: the lines the history hides (see Hiding lines) change
  no figure. **The history shows every change but one of exactly 0.00** (since version 0.80): a rise with its plus, `+0.86`, and a fall with its minus, `-0.14`, as the row writes them; a change of 0.00 is an empty cell. (From version 0.65 to 0.79 a fall was an empty cell too, so that the column showed what was spent; with the zero usage lines hidden that left lines with no value in it.) **The row shows it whenever the reading has an amount used**,
  also a change of 0.00, as written below, and a gray box when the reading has none. (This takes the place of the rules of earlier versions: against the row directly before, in the same run only, with nothing for a
  start line, for a line after a failed one, and for a fall.)
- **The time since the previous reading** is the time between two rows. The backend works
  both out from the history file and sends them with the status. The row shows the values of the
  newest reading, and the history panel those of each row (see Settings for the switches).
- **The time since the previous reading** (since version 0.67) is counted to the row directly before it in the file, **whatever run it belongs to**: a failed row included, so a failed row has a time and the row after it counts from the failed row. The first row of a run
  has one too: the time since the last row of the run before, which is how long the program was not running (it can be hours or days, `90000 s`). Only the first row of the file has none. (Before, a run was a border and the first row of a run showed an empty cell.)
- A value that cannot be worked out is left empty, not shown as zero.
- **In the history the time since the previous reading is worked out on the lines that are shown.** When the history leaves lines out (see Hiding lines),
  the time of a line is that against the previous line that is shown, not against the row directly before it in the file: with the unchanged readings hidden, the time is the
  time since the previous reading that is shown. The first line shown has none. The change in the amount used is not affected by what the history hides (above), and neither are the main row's items.
- **The time is a value even at no seconds at all**, `0 s`; only a negative one is empty. A time is given when it can be worked out, whether or not the change can.
- **A zero usage line is a line whose change in the amount used is exactly 0.00** (since version 0.80), by the rule above: its amount used is that of the last earlier line that has an amount used, whatever lies between, a failed line or the start of a run. So the lines that are hidden
  with the zero usage lines are exactly those whose `Δ used` would be empty, and every line that is shown then has a value there, a failed line alone excepted. (Until version 0.79 the amount was compared with the row directly before it in the same run for this, and the startup lines counted as zero usage lines whatever their change: see Hiding lines.)
- **The time is the difference of the two local times as they are written.** Across a change of the clocks it is
  therefore an hour too long when they go forward, and too short, or empty, when they go back.
- **A row that follows a failed row is a zero usage line when its amount is the one of the last good reading** (since version 0.80; until then it never was one, and was always shown): its change in the amount used, in the column and in the row, is against the last line that has an amount, and a change of 0.00 makes it a zero usage line like any other.

**Settings**

- Pressing the settings button shows the settings view in the same place as the log and the history,
  below the row and its message lines, sharing the one panel area with them: showing one replaces the
  other (the log, the history, the error log and the cost control can be shown together when `Several views at once` is on, see Several views at once; with it on the settings are shown with them since version 0.81, and with it off they are shown alone, as before). Its tooltip and behaviour are those of the other two buttons. It replaces the config button
  and the interval field in the row, which are gone.
- **Leaving the settings brings back the view that was shown before**, with `Several views at once` off; with it on the
  settings replace no view since version 0.81, and leaving them closes them alone (see Several views at once). If the log, the history or the error log was shown when
  the settings button was pressed, it is shown again when the settings are left, whichever way they are left (Save, Cancel, or the
  settings button once more), at the size it had before, so the person finds the window as they left it: the history and the log at
  their remembered height, the error log at its opening size. It shows the changes the settings made: the history with its new
  columns, times and date, the row with its new items. If nothing was shown before, nothing is shown afterwards, as before.
  **A view whose button is switched off when the settings are left is not brought back** (since version 0.80): unchecking the icon of the view that is open, in `Main view`, and leaving the settings
  by `Save`, by the gear or by the button of another panel, closes that view; `Cancel`, which changes nothing, brings it back.
  Pressing the button of another panel while the settings are shown shows that panel, and the settings are left without coming back
  to anything: what was shown before the settings is then forgotten.
- **Leaving the settings by a button of the row saves them**, not only `Save`: the gear pressed again, and the button of any
  other panel, the log, the history or the error log, each save what the view holds, exactly as `Save` does, and then do what
  they do, close the view or show their panel. **`Cancel` is the one way to leave without saving**: it closes the view, nothing
  is changed, and what the row previewed is taken back.
- **When the save fails on leaving, the view stays.** If a value is refused, an interval that is no number or out of range, or
  the settings cannot be saved, the view stays open with its message in red, as after `Save`, and the other panel is not shown:
  nothing is lost without a word, and `Cancel` is still there to leave.
- **Nothing changed, nothing sent.** Leaving a view in which nothing was changed sends nothing to the backend. `Save` always sends.
- **Closing the window** while the settings are open does not save them.
- **The window is exactly as tall as the row, its message lines and the settings need**, so the
  settings are shown whole, with no vertical scrollbar, and with no empty space below them. The height of the
  row counts: the settings are never clipped by it. The height follows the content: when the row changes,
  in particular when a message line such as an error appears, grows or goes, the window follows at once, so the
  settings stay whole and the window is no taller than needed. The window cannot be resized by hand while the
  settings are shown, except in width while the tab `About` is shown (since version 0.79, see The tab About can be dragged wider).
- **The settings have a least width.** Hiding elements makes the row, and with it the window, narrower. While the settings are
  shown the window is never narrower than the settings need for two things: **every checkbox has its text on one line**, a
  property beside it included, and **the buttons of the view, `Save` and `Cancel`, are in one row**. When
  the row is wider than that, the window is as wide as the row. When the settings are left the window goes back to the width of
  the row. The least width is worked out from the view itself, from its texts and controls as they are drawn, and is not a fixed
  number of pixels, so it stays right when a text or the font changes. Nothing in the view wraps; only its message in red may
  break over several lines.
- The view shows every setting with its current value, which the frontend has just asked the backend for,
  each time the view is opened. The frontend keeps no setting of its own: what is shown is what the
  backend has.
- Changes made in the view are sent and saved when the **Save** button is pressed, or when the view is left by a button of the
  row (above). Those of `Main view` are shown in the row at once, as a preview, and are taken back when the view is left by
  `Cancel` (see The elements of the row); those of the other tabs take effect when they are saved. Save sends all the
  values to the backend, which keeps them and saves them in the settings file, and then **closes the
  view**; the main view shows the changes at once. A **Cancel** button closes the view, and no value
  is changed; it asks nothing and takes back the preview. **There is no `Restore defaults`** (since version 0.64). If the backend refuses a
  value, or the settings cannot be saved, the view stays open and shows a brief message in red; nothing
  is changed.
- **A field that is wrong or empty when the settings are saved gets the focus** (since version 0.89, `docs/img/img_61.png`): the
  view switches to the tab the field is on, puts the cursor in it and selects its text, so that it can be typed over at once,
  and gives it a **red frame** (`#cf222e` / `#f85149`, 1 pixel, where its border is) until it is changed. The red message stays
  as it is. With more than one wrong field the first in the order of the tabs and, within a tab, from the top, is the one; the
  backend names the setting it refused (`docs/api.md`), so a value it refuses is found as well as one the page refuses. A
  failure that is no field's, a settings file that cannot be written, focuses nothing.
- The settings, with their defaults:

  | Setting | Values | Default |
  | --- | --- | --- |
  | Interval (the time between usage requests) | a single entry field for a whole number of seconds from 5 to 3600, showing the backend's current value; there is no dropdown list | 60 s |
  | Log the response | on, off | off |
  | Show the percentage spent in the row | on, off | off |
  | Show the currency symbol in the row, before the amounts | on, off | off |
  | Show the time in the row | on, off | on |
  | Show the amount used in the row | on, off | on |
  | Show the monthly limit in the row (`Monthly limit`, `Budget` until 0.82) | on, off | on |
  | Show the status circle in the row (`Status`, since version 0.82) | on, off | on |
  | The status circle blinks (`Blinks`, beside `Status`, since version 0.88) | on, off | on |
  | Show today's percentage, today's spend and the daily limit in the row (three checkboxes, since version 0.82) | on, off | off |
  | Show the refresh button in the row | on, off | on |
  | Show the countdown in the row | on, off | on |
  | Show the history button in the row | on, off | on |
  | Show the log button in the row | on, off | on |
  | Show the error log button in the row | on, off | on |
  | Show the cost button in the row | on, off | on |
  | Show the time between usage requests in the row | on, off | off |
  | Show the change in the amount used in the row | on, off | off |
  | Show the time since the previous reading in the row | on, off | off |
  | Show the request duration in the row | on, off | off |
  | Keep the window above every other window (`Stay on top`) | on, off | off |
  | Show the title bar of the window (`Title bar`) | on, off | on |
  | Restore the window at start (`Restore the window at start`) | on, off | off |
  | Show several views at once (`Several views at once`) | on, off | off |
  | The folder of the log (`Log folder`, since version 0.81) | a folder, chosen with the system's folder picker | the program's folder |
  | The folder of the usage history (`History folder`, since version 0.81) | a folder, chosen with the system's folder picker | the program's folder |
  | Time format in the row | hours and minutes, or hours, minutes and seconds | hours and minutes |
  | Show the date in the history, as well as the time | on, off | off |
  | Show the zero usage lines in the history | on, off | on |
  | Show the failed lines in the history | on, off | on |
  | Show the time in the history | on, off | on |
  | Show the amount used in the history | on, off | on |
  | Show the limit in the history | on, off | on |
  | Show the change in the amount used in the history | on, off | off |
  | Show the time since the previous reading in the history | on, off | off |
  | Show the current marker (`Cur.`) in the history | on, off | on |
  | Show the configured monthly limit, the daily limit and how the daily limit came about in the history (three checkboxes, since version 0.82) | on, off | off |
  | The limit counted (`Server limit` or `Monthly limit`, in `Cost control`, since version 0.82) | one of the two | `Monthly limit` |
  | The hours of a working day (in `Cost control`, since version 0.82) | a whole number from 1 to 24 | 8 |
  | The working days of the month (in `Cost control`, since version 0.82) | empty for Monday to Friday, or a whole number from 1 to 31 for the current month | empty |
  | Show the currency symbol in the cost control (`Currency symbol`, in `Cost control`, since version 0.82) | on, off | off |

- A value typed in the interval box that is not a whole number from 5 to 3600 keeps the view open
  and shows a brief message in red when Save is pressed; the backend refuses it, as before. The current value,
  whatever it is (also one from an old settings file), is what the field shows. The field has
  its unit, `s`, beside it, and there is no list of values to pick from.
- **The texts of the view.** The section that holds the interval is headed `Request` (not `Usage requests`), and the interval's
  label reads `Interval`, and so does the checkbox for the row's item;
  the row item's tooltip keeps `Time between usage requests`. The checkbox for the log reads `Log the response`.
  In the tab `History view` the checkboxes read `Date`, `Zero usage lines`, `Failed lines`, and one for each column of the history, in the order of the view:
  `time`, `used`, `limit`, `Δ used`, `Δ time` and `Cur.` (like the titles of the columns they switch on; saved under `historyTime`, `historyUsed`, `historyLimit`, `historyDeltaUsed`, `historyDeltaTime` and `historyCurrent`): no `Show` in front, no `Column`, and no colon in them. Under `Main view` the two checkboxes for the row keep their longer
  texts, `Change in the amount used` and `Time since the previous reading`. The other ones in `Main view` read as The elements of the row names them, the four icons `Log icon`, `History icon`,
  `Error log icon` and `Cost icon` (since version 0.80; until then `Log button`, `History button`, `Error log button` and `Cost button`), and the property of the amounts reads `Currency symbol`.
- **A checkbox and its text are centred on each other vertically**, and so is every other control and its label
  (the interval's field, the time format), in every line of the view, so that nothing sits higher or lower than its
  label.
- **Every control is wide enough for its own value**, the arrow of a dropdown included: the value is never
  covered by the arrow, nor cut off.
- **The text of the view cannot be selected**, like everything in the window (see Nothing in the window can be selected);
  until version 0.55 it could, and be copied. What is typed in the field of the interval can.
- **The view is arranged in tabs** (since version 0.64), in this order, each with its title:
  `General`, `Main view`, `History view`, `Cost control`, `About` and `Debug` (only in debug mode, see Debug mode); **since
  version 0.90 `General`, `Main`, `History`, `Log`, `Cost control`, `About` and `Debug`** (see The settings rearranged)
  (since version 0.79 `General` is the first tab; until then it was the last but one, before `About`). **Since version 0.90
  `About` comes before `Debug`** (`docs/img/img_67.png`), so that the tab of debug mode alone is the last; until then `About` was
  always the last.
  - `Main view` holds the elements of the row and the properties of the amounts and of the time; `History view` the checkboxes of the history (below);
    `Cost control` the limits (see Cost control); `Debug` the debug settings; `General` the groups `Request`, `Window` and `Log`; `About` the About view (see The About view).
  - **The titles are a strip**, all of them in one line over the tab, each clickable. **The tab that is shown is marked in blue, not green** (since version 0.65): its title and its line are in the blue of the notes,
    `#0969da` on the light theme and `#58a6ff` on the dark one.
  - **There are no arrows** (since version 0.65; version 0.64 had two, to step to the previous and the next tab). A tab is reached by its title.
  - **The strip is always whole**: while the settings are shown the window is never narrower than the whole strip of the titles needs, in one line, besides what the widest tab needs. So the
    settings have **no horizontal scrollbar** and every title is seen, also when every element of the main row is hidden and the row alone would make the window very narrow
    (the fault that was: with nothing in the main row the window was so narrow that the strip did not fit and a horizontal scrollbar hid the tabs).
  - **The settings open on the tab they were last on, and on `General` the first time after a start** (since version 0.80). However the view was left, by `Save`, by `Cancel`, by the gear or by the button of another panel, the next time it is shown in the same run it
    is on the tab it was on when it was left, `About` included. **The first time the settings are shown after the program was started they are on the first tab, `General`**, whatever tab they were on when the program was stopped: the tab is not kept across starts.
    The key `settingsTab` of `settings.json`, under which the tab was stored from version 0.68 to 0.79, is read no more and is not written again. The one exception is the window that is restored at the start with the settings open, which is restored on the tab it had (see The window is restored at start). The window is as tall as the shown tab needs, and the height follows when the tab is changed; the
    least width is that of the widest tab, so the window does not change its width from tab to tab.
  - **The buttons `Save` and `Cancel` are under every tab**, `About` included, and are the same on each.
- **The title bar.** The setting `Title bar`, in the group `Window` before `Stay on top`, on by default and saved under the key
  `showTitleBar`, shows or hides the title bar of the window. It is not previewed: it takes effect when the settings are saved.
  - **With the title bar hidden the window is the row alone**, with no title, no buttons of the system and no frame to drag.
  - **Since version 0.87 only the settings bring the title bar back** (the rest of this point, and the next, held until version
    0.86). With the setting off no view has a title bar, the lists as little as the cost control, alone or several together: the
    window has its border and is moved by dragging it (see A border round the window that has no title bar and Dragging the
    window that has no title bar), and a list is made taller and shorter by the bottom edge of the window (since version 0.89;
    by two buttons of its own in 0.87 and 0.88; see Taller and shorter by the bottom edge). The settings, alone or beside views, and their tab `Debug`, have the title bar whatever the setting says, so
    that the window can be closed there.
  - **While a panel that can be resized is shown the title bar is there**, whatever the setting says: the log and the error log,
    which can be dragged in height and in width, the usage history, in height, and the settings, whose tab `About` can be dragged
    wider and which keep the title bar on every tab, so that the window is not exchanged from tab to tab. **The cost control
    panel, which cannot be resized, has no title bar when the setting is off** (since version 0.79; until then every panel had
    the title bar): the window is then moved by dragging it and has its border, as with the row alone (see A border round the
    window that has no title bar and Dragging the window that has no title bar). So the window can be moved and closed as usual, and a panel can be resized by dragging, which a window
    with no frame does not allow: to move or close a window with no title bar, the settings button, or the button of any panel but the cost control,
    is pressed. When the panel is closed the title bar is shown or hidden as configured. Only the row by itself is ever without
    a title bar; with it hidden and no panel open the window is not dragged by the row and has no close button of its own.
  - **It works while the program runs**, not at the next start: the title bar comes when a panel is opened and goes when it is
    closed, within a moment, and follows the setting when it is saved. A program started with the title bar switched off opens
    without one.
  - **The window is exchanged for another to do it.** A window's frame cannot be changed once the window is there, so the
    program opens a new window with or without the title bar, with the same page in it, and closes the old one. The page is
    not loaded again and loses nothing: what it shows, an open panel and where it was scrolled to stay. The program itself goes
    on running through this: nothing is fetched again because of it, and nothing is logged as a start or a stop. That the window
    flickers for a moment as it changes is accepted. There is never more than one window, and never none.
  - **The window keeps its place**: the row stays where it is on the screen, and the title bar appears above it or goes from
    there. A window that stays on top still does afterwards.
  - Its tooltip is `Shows the title bar of the window. Off, no view has one; it comes back for as long as the settings are open,
    so open them to close the window.` (since version 0.87; until then `Shows the title bar of the window. Off, the window is the
    row alone; the title bar comes back for as long as a panel that can be resized is open, so open one to close the window.`,
    and until version 0.78 its end read `a panel is open, so open one to move or close the window.`) The log says `The window has its title bar` and `The window has
    no title bar` when it changes, as debug lines.
- **The window on top.** At startup the window comes to the front, above all other windows. The setting `Stay on top`, in the
  group `Window`, off by default and saved under the key `stayOnTop`, says what happens after that: on, the window stays above
  every other window for as long as the program runs; off, it was brought to the front at startup only and can be covered
  afterwards as usual. The setting is not previewed: it takes effect when the settings are saved, within a moment, without a
  restart. Its tooltip is `Keeps the window above every other window for as long as the program runs. Off, it only comes to the
  front when the program starts.` The log says `The window stays on top` and `The window no longer stays on top` when it changes.
- Turning on logging of the response makes the application write, for each response, the JSON it was
  sent, pretty printed over several lines, in the log (see Non-functional requirements).

**The log panel**

The log is shown the way the usage history is: inside the main window, not in a window of its own.

- Pressing the log button makes the main window ten times as tall, as for the history, **and three times
  as wide as the single row**, and shows the
  end of the log file, `java-aip-usage.log`, in a panel below the first row and its message lines.
  Pressing it again hides the panel and the window goes back to the size it had. While it is shown the
  window can be resized in height **and in width**, and the panel takes the room there is. Unlike the
  history, the log has long lines, which is why it gets the extra width. Opening it again starts again
  at three times the row's width, and at the height it was last left at (see Remembered heights), never
  less than ten times the row's.
- **A new entry does not change the width** (since version 0.66). The width of the window while the log, the usage history or the error log is shown is the one the person has set: when they have
  made the window narrower or wider and a new line comes, the window keeps that width, where before it went back to the first one (three rows for the log). Only opening the panel, or changing to it from another, starts at the usual
  width, as before. The height is the one the person has set as before.
- The log panel has the width of three rows, plus the width of the vertical scrollbar, which is always reserved
  and never covers text. As for the history, the window's height does not follow the main view while the
  panel is shown: when a message line appears or goes, the window keeps the height it has and the panel takes
  up the difference.
- The log, the history, the error log and the settings share the one panel area: showing one while another is shown replaces
  it, and the window takes the size the shown one has (so it is wider for the log than for the history, and
  as tall as its content for the settings),
  and each button's tooltip says `Hide…` only for the one
  that is shown.
- One line of the file is one line of the panel, as written, newest first like the history, in the
  same small, condensed, regular-weight, fixed-width text, and it is never wrapped (a long line
  scrolls sideways). The response JSON that the log setting writes (see Non-functional requirements) is
  several lines, each of them a line of the panel, in the order the entry reads from the top
  down even though the panel is newest first: the lines of one entry stay together and in order.
- It shows the end of the log: the newest 1,000 lines, reading at most the last 512 kilobytes of the
  file, since the log is not rotated by itself and can be large. If lines were left out, a line above them
  says how many are shown.
- While the panel is shown it keeps up with the file: new lines appear at the top soon after they
  are written, without pressing anything, and what the person has scrolled to does not move. There
  is no reload button.
- The line that records the start of a run (see Non-functional requirements) is **in blue text, with no background of its own**
  (since version 0.79): the blue of the notes and of the open panel's icon, `#0969da` on the light theme and `#58a6ff` on the dark one, so where each run begins can be seen at a glance, the same as the start line of the usage history. (From version 0.66 it had a very light blue background, about `#ddf4ff` and `#0c2d6b`, and before that a gray one, with its text in the colour of every line.) The log is appended to across runs, so
  there can be several such lines; each is marked. Only the colour differs: the words are the same, and the weight is regular.
- If there is no log yet, or it is empty, the panel says so in one line. If it cannot be read, the
  panel says so in red, and shows what it had.
- The file itself is handled like the usage history file: in the working directory,
  added to and never rotated or overwritten by the program of its own accord (a person can reset it, see The log can be reset, and the reset undone), kept out of version control, and its text comes from
  the application through a read-only request that reveals nothing about where the file is.

**The level and the date of the log panel** (since version 0.40)

- **The log panel has a line of its own at the top**, above the lines of the log, with two controls: a dropdown
  `Level` and a checkbox `Date`. The line stays where it is while the log is scrolled. It is in the log panel only:
  the settings view does not have the two, and the error log and the history have no such line.
- **The dropdown has four levels**, `Error`, `Warning`, `Info` and `Debug`, in that order, from the most severe
  to the least. The one selected is the least severe level shown, and the more severe ones are shown with it:

  | Selected | Shown |
  | --- | --- |
  | `Error` | errors |
  | `Warning` | errors and warnings |
  | `Info` | errors, warnings and information |
  | `Debug` | errors, warnings, information and debug lines: everything the file has |

- **The two controls are at the left of their line**, in this order: the label `Level`, the dropdown, and the
  checkbox with its text `Date`, written and spaced like the controls of the settings view.
- **The dropdown has the same four levels in every run**, with `--debug` and without it. The level `Debug` has
  nothing to do with debug mode.
- **After a change of the level or of the date the log stands at the top**, at its newest line, whatever it was
  scrolled to before.
- **`Debug` shows the debug lines of the program as well** (see Debug lines in the log), which `Info`, the default,
  leaves out. Before version 0.44 the program wrote none, and `Debug` showed what `Info` shows.
- **The level of a line is the word after its date and time.** The words are fixed, whatever the program is written in:
  an error is `SEVERE` and a debug line `FINE` (since version 0.95 a Fine line `FINE` and a debug line `DEBUG`, with a fifth choice `Fine`; see Version 0.95). (They are the words of the logging the first version wrote the file with,
  and are kept so that every version writes and reads the same file.) `SEVERE` and `ERROR` are errors, `WARNING` and `WARN` warnings,
  `INFO` and `CONFIG` information, and every other word, should the file ever hold one, is shown with `Debug` only.
- **The dropdown chooses what is shown, not what is written.** It does not make the program log more or less.

- **A selection takes effect at once**, on the log that is shown, without a button to press and without closing the
  panel.
- **An entry is shown or left out whole.** The lines that belong to an entry, a stack trace or the response JSON, go
  with the line that begins it. Lines the panel cannot give a level, those before the first entry of what was read
  (see The log, exactly), are shown with `Info` and with `Debug`, and with neither of the other two.
- **The 1,000 lines are 1,000 lines of the level selected.** The filter comes first: of the last 512 kilobytes of the
  file, the newest 1,000 lines that the level lets through are shown, so `Error` shows up to 1,000 lines of errors
  and not what is left of the newest 1,000 of everything. The note above the lines, `Showing the newest N lines of
  the log.`, counts the lines shown and is there when lines of the level were left out, and, as before, when the file is
  larger than what is read of it.
- **When the file has lines and none of the level**, the panel says `The log has no lines at this level.` in the
  blue of its other notes.
- **The checkbox `Date` shows or hides the date of each line.** Off, the line begins with its time:
  `2026-10-11 13:24:05 INFO    [Name] message` is shown as `13:24:05 INFO    [Name] message`. On, the line is as
  it is in the file. The time is always there. The lines that follow in an entry have no date and are not changed.
  Like the level, it takes effect at once.
- **Only what is shown changes.** The log file is written as before, every level and the full date; the line that
  begins a run is marked as before, with or without its date.
- **The defaults are `Info` and the date off.** So the panel shows every line the program writes today, as before,
  and, other than before, without the date.
- **Both are remembered.** A change is saved at once, in `settings.json` with the other settings, under the keys
  `logLevel` (the text `error`, `warning`, `info` or `debug`) and `logDate` (true or false), and the next time the
  panel is shown, in this run or the next, it comes up with what was saved. A value in the file that is none of the
  four has the default, as any other value that is out of range. There is no `Save` and no `Cancel` for them.
- **The settings view leaves them alone**: they are not in it, saving it does not change them.
- **When a change cannot be saved**, it still holds for the panel as it is shown, and a red line below the two controls
  says `The log settings could not be saved: <reason>`. The line goes with the next change that is saved. When the panel
  is shown the next time it comes up with what is saved, which is then not what was chosen.
- **The two controls are shown when the panel has its first answer**, which says what is saved; a log that does not
  exist yet, or is empty, has them too.
- **The error log panel is not changed by this.** It lists the failed refreshes of the run, as before, whatever level
  is selected for the log, and its times have no date, as before.
- **The window is not sized to the line**: the log panel is as wide and as tall as before, and the line of the two
  controls takes its height from the lines of the log.

**The usage history panel**

The history is shown inside the main window, not in a window of its own.

- Pressing the history button makes the main window **ten times as tall** as it was, and shows the
  recorded usage in a panel in the added space below the first row, below any message line, as lines
  of text like a log file. Pressing the button again hides the panel, and the window goes back to the
  size it had, which is the single row again.
- **While the panel is shown the window can be resized in height**, by dragging its bottom edge, down
  to the size the window has with the panel hidden (it can be made as small as it originally was), and the
  panel takes all the height there is: a taller window shows more lines. Its width stays fixed. When the
  panel is hidden the window is back to fitting its row and cannot be resized. Opening the panel again
  starts at the height the person last left it at, never less than ten times the row's height (see
  Remembered heights).
- **The window is as wide as the table needs, so that every column is whole**, never clipped: the time (with or
  without the date), `used`, `limit`, when their settings are on `Δ used` and `Δ time`, and the currency, plus the
  width of the vertical scrollbar, which is always reserved so that nothing shifts when it appears or goes. It is
  never narrower than the row and the scrollbar. The page measures the table, so the width follows it when a
  setting adds or removes a column or the date, and nothing is computed from fixed numbers. The width cannot be
  dragged: the person changes the height only. The scrollbar never covers a column: the last one is whole next to it. The table keeps a margin at its right
  edge as wide as a scrollbar, always, so that a scrollbar that is drawn over the content (as on macOS, where it takes no room of its
  own) does not hide the title of the last column, `Cur.` or a change column, either.
  Nothing else in the row moves.
- **While the panel is shown, the window's height does not follow the main view.** When a message line
  appears or goes, or the row changes in any other way, the window keeps the height it has, as opened
  or as the person dragged it, and the panel takes up the difference.
- **Since version 0.82 the history has three more columns, after `limit`**: `month`, the configured monthly limit counted
  when the reading was made; `day`, the daily limit in force then; and `by`, `auto` when that daily limit was worked out and
  `set` when it was typed. **Since version 0.89 that column says it in words** (`docs/img/img_59.png`): its title is `daily limit
  is` and its cells `worked out` and `typed`, so that a line reads `45.45 worked out`; its checkbox in `History view` is `daily
  limit is`, its tooltip `Whether the daily limit in force at the reading was worked out from the monthly limit, or typed`. The
  file keeps `auto` and `set` (`daily_auto`); only the view words them. Its width is 14 characters. **It stands one character
  further from `day`** (`docs/img/img_58.png`: the right-aligned figures of `day` touched the left-aligned words): its cells and
  its title have one character of room at the left. **Since version 0.90 its title and its cells are centred** (`docs/img/img_68.png`;
  they stood at the right). `limit` stays the limit the server sent. Each has a checkbox in `History view`, after `limit`, **off by
  default**, saved under `historyMonthly`, `historyDaily` and `historyDailyBy`; a row written before 0.82 has empty cells there.
  Widths, in characters: `month` 10, `day` 10, `by` 4. Tooltips of the titles: `The monthly limit counted when the reading was
  made`, `The daily limit in force when the reading was made`, `Whether the daily limit was worked out (auto) or typed (set)`.
- **Every column can be switched** (since version 0.64) by a checkbox in the tab `History view`, in the order of the view: `time`, `used`, `limit`, `Δ used`, `Δ time` and `Cur.`.
  By default `Δ used` and `Δ time` are off and the other four on. The history has no `interval` column; the file does (see Usage history). With a column off the others close up, and the
  right-most one still is at the right edge.
- The history is a table with a header row (the columns whose settings are on, from `time`, `used`, `limit`, `Δ used`, `Δ time` to `Cur.`; it does not scroll away) and one line for each reading, with space between the
  columns so that nothing is cut off (in particular the last title). **The time is at the left, and every other column is at
  the right side**: `used`, `limit`, when switched on `Δ used` and `Δ time`, and **the currency last, as the right-most column**, are packed against the right edge of the
  panel, each right-aligned in its own column, so that the numbers are lined up by their last digit, and the free space of the
  panel is between the time and `used`. **The currency's title is `Cur.`**, and its cells show `$` for US dollars; for any other currency they keep the code the
  response named, for example `EUR`, and are empty when it named none. **There is a little more space between `used` and `limit`** than between the others, so
  the two numbers do not run together: about three characters of room between them, besides the usual gap, and no more. For example, as wide as the row:

  ```
  time        used     limit  Δ used  Δ time  Cur.
  21:01:22   263.89   1000.00   +0.26    72 s     $
  20:46:11   260.66   1000.00           63 s     $
  ```

- **The first column is the time of day only**, `21:01:22`, and its title is `time`. A setting (see
  Settings) shows the date as well, `2026-10-08 21:01:22`, and then the title is `date time`. The file keeps
  the full date and time either way. The backend cuts the time; the window shows it as received.
- **Hovering over a line of the first row of a run**, the blue one below, shows the
  text `The program started here`.
- **A horizontal scrollbar** appears at the bottom of the panel when the columns are wider than the panel, which
  happens only if the table is wider than the largest window there is (2,400 pixels), so that nothing is out of
  reach. It takes no room when it is not needed.

- The column titles are aligned like their columns: `time` at the left, the others at the right.
- `Δ used` and `Δ time` are the change in the amount used and the time since the previous
  reading, as described under Changes between readings, worked out by the backend for each row from the
  file and sent with the rows. The titles are a delta symbol, a space, and `used` or `time`: they sit
  beside the `used` column and are told apart from it by the symbol. Each column is shown only when its setting
  is on, and both are off by default; the columns are between `limit` and the currency, which stays the last. An empty value is an
  empty cell, and a change in the amount used of exactly 0.00 is an empty cell too, not `0.00`; a fall is written with its minus, `-0.14` (since version 0.80, see Changes between readings). **The time is in whole seconds, with the unit,
  never in minutes or hours**: `63 s`, `126 s`, `3600 s`. (The row's item is in seconds too, but written without the space, `63s`.)
- **Hiding lines.** Two settings (see Settings) say whether the history shows its zero usage lines and its failed lines; both are
  shown unless switched off. A **zero usage line** is a line whose change in the amount used, as under Changes between readings (against the
  last earlier line that has an amount used), is exactly 0.00: the reading is the same as the last one before it. **A startup line is a line like any other in this** (since version 0.80): the first line of a run, the blue one
  with the `The program started here` hover text, is hidden with the zero usage lines when its change is 0.00 and shown when it has a change, a rise or a fall. (Until version 0.79 every startup line counted as a zero usage line,
  so one that showed a change was hidden, and a line whose amount fell had an empty cell and stayed: the two things that read as a fault.) A startup line of a failed query (it has no amounts) is a failed line
  and goes with those. A **failed line** is a row of a failed query. What is hidden is
  left out of the table, and so of its sorting, its count of 1,000 lines and the changes worked out on it (see Changes between readings).
  The file itself is not touched.
- **When not all the data is shown, the panel says so.** One line above the table, written by the backend, says how many lines there are,
  how many are shown, and why the others are not: hidden zero usage lines, hidden failed lines, and older lines beyond the newest 1,000. For example
  `Showing 640 of 1,500 lines: 700 zero usage and 60 failed hidden, 100 older not shown.` **It has a tooltip** (since version 0.89,
  `docs/img/img_60.png`), written by the backend with the parts that apply: `The history shows at most the newest 1,000 lines of
  the file.` then, with lines hidden, ` Lines with no change in the amount used and failed requests are hidden by the filter
  button at the right of this line; press it to show them.` and, with older lines left out, ` Older lines are not shown here,
  but stay in the file.` It is there whenever anything is left out, and not
  otherwise; it replaces the line that said how many of the lines were shown. The window shows it as received. **It is blue** (about `#0969da` on the
  light theme and `#58a6ff` on the dark one), in the panel's own small text, **and bold** (weight 700, since version 0.80; until then regular), so that it stands out as the sign that not everything is shown. **The error log's `There are no errors in this run.` is blue as well**,
  the same blue, since it too is a line that tells the person something about what they are looking at; the other notes (no history yet, no rows yet, no log yet) keep their gray and their regular weight. **Every blue note is bold**: this line of the history, the log's
  `Showing the newest N lines of the log.` and `The log has no lines at this level.`, and the error log's `There are no errors in this run.`
- **The page does no calculation and makes no decision about the lines.** The backend sends each line finished: its cells, as the table
  shows them (the time as the setting says, the amounts, `failed` in the place of the amount of a failed line, the changes), whether it is the first
  line of a run, whether it is a failed line, and its hover text. The page only puts them on the screen. The status, the interval and the duration of
  a row never reach it.
- A row of a failed query (status `failed` or `start-failed`, see Usage history) is shown with its
  time, empty amounts and empty currency, and the word `failed` in red in the `used` column.
- The line of the first row recorded after the program started (a row whose `status` is `start` or
  `start-failed` in the file, see Usage history) is shown **in blue text**, the whole line, so
  where each run begins can be seen at a glance: the blue of the notes and of the open panel's icon (`#0969da` on the light theme,
  `#58a6ff` on the dark one; since version 0.79, until then it was the green of the figures). **It has no background of its own** (since version 0.79; from version 0.66 it had a very light blue one, about `#ddf4ff` on the light theme and `#0c2d6b` on the dark one),
  the same as the start line of the log (see The log panel), and the line is not bold. (A failed startup line keeps its red `failed` in the place of the amount; the rest of its
  text is blue.) The file can hold several such rows, one for each run; each is marked. The `status`, `interval` and `duration_ms` columns themselves are not shown in
  the panel.
- **The lines are sorted by the date and time, latest first.** They are sorted by that, not merely
  taken in reverse file order, so a file that is out of order is still shown right. Two lines of the
  same second keep the file's own order, the later written one first, so that a reading and the failure
  or startup line beside it do not change places from one reading of the file to the next.
- **The columns have room for the amounts that occur, not for every amount.** The widths are fixed so
  that the numbers of every line are under one another, which they could not be if each line were as wide
  as its own content; they hold amounts up to `9999999.99`, ten characters, written as the history writes them, without digit grouping. A larger amount than that overruns its
  column rather than widening the table. This is the one place in the window where a figure is given a
  fixed width in characters.
- The text is **small and condensed**, in the manner of a log file: a fixed-width font of about
  12 pixels with tight line spacing and no padding between lines, so that many readings fit in
  little space. This is the one place the 14-pixel minimum does not apply. It is regular weight,
  not bold, and not thin; only the header row of the history, and that of the error log, is bold.
  **The typeface is a sturdy one on every system** (since version 0.80, see A sturdier typeface for the lists).
- The panel scrolls: older readings are reached by scrolling it, or by making the window taller. It
  holds the newest 1,000 of the lines that are not hidden; the line above the table says so (see above).
- While the panel is shown it keeps up with the history: a new reading appears at the top soon
  after it is recorded, without pressing anything. It does not move what the person has scrolled to.
- A line of the file that does not have at least the columns `datetime`, `used` and `limit` is left out, so one damaged line cannot
  hide the others (what exactly a row is, is under Details of the behaviour). If there is no history yet, or it has no readings, the panel says so in one line.
  If it cannot be read, the panel says so in red, and the window shows what it had.
- The panel's data comes from the application, through a read-only request that reveals nothing
  about where the file is and adds nothing to the log or the history.

**Remembered heights**

- The height of the window while the history is shown, and while the log is shown, is remembered for each
  of the two, as `historyHeight` and `logHeight` in the settings file, in pixels of the window's content. The
  width is not (but for the log's since version 0.93, see Version 0.93). They are not settings of the settings view: they are neither shown nor applied there, and
  nothing there changes them.
- When the person changes the window's height while the history or the log is shown, the new height is stored,
  once the change has settled, not at every pixel of a drag. The program, not the page, notices: the page never
  reports a height the window was dragged to.
- When the history or the log is opened, the window gets the stored height. If there is none, or it is smaller than
  the height the program works out itself, ten times the row's height as it is at that moment, the worked-out
  height is used instead, and it is stored as the new value.
- The error log and the settings do not remember a height: the error log opens at ten times the row's height, and the
  settings are as tall as their content.
- A height that cannot be stored is logged and nothing more: the window keeps the size it has, the height is not
  taken as remembered, and the next change tries again. A height is not worth a damaged settings file: if
  `settings.json` is there and is not a JSON object, the height is not stored and the file is left as it is, as it is
  until a setting is applied. A height equal to the one remembered writes and logs nothing. A stored `0`, like no key
  at all, is no remembered height.

**The error log panel**

The error log is a third panel in the same area as the log and the history, for the errors of the program as
they happen, so that a person can see what went wrong and when without opening the log file.

- A small icon button, between the history button and the settings button, shows the panel and hides it again, as
  for the others. It shares the panel area with the log, the history and the settings: showing one replaces the other.
- It lists the errors of **this run only**. It is kept in the application's memory and is not written
  to a file: when the program stops, it is gone. The next run starts with an empty error log. (The same errors are in the log file, as
  always.)
- One line for each error, newest first, in two columns, `time` as `hh:mm:ss` (the local time of day, with
  seconds) and `message`, in the small, condensed text of the log. A message is never wrapped (a long
  one scrolls sideways).
- **Every failed refresh is an error of the list**, with the message the row would show for it:
  an HTTP 429 (with how long the application now waits), any other HTTP status, a network failure or timeout,
  a response that cannot be read, and every problem with the token: Claude Code not logged in, `claude` not
  found on the `PATH` or not able to run, and a credential in the environment that has to be unset. A failure that
  has no message line, an HTTP 429, is in the list all the same. One line is added for each failed refresh, not for each
  poll of the window.
- It holds the newest 1,000 errors. If there are none yet, the panel says so in one line, `There are no errors in this run.`, in blue.
- While the panel is shown it keeps up: a new error appears at the top soon after it happens, without pressing
  anything, and what the person has scrolled to does not move.
- **Its size is half that of the log in width**: ten times the row's height, and one and a half times the row's width plus the
  vertical scrollbar, resizable in height and in width, the height not following the main view, and a horizontal scrollbar when a
  line is wider than the panel. It opens at that width, not narrower; a long message scrolls sideways.
- The data comes from the application through a read-only request that reveals no credential and no file name.

**Messages and states**

- A failed refresh, stale data, or a missing or logged-out Claude Code must still be
  visible, as required elsewhere, except for an HTTP 429, which has its own, quieter sign (see Countdown).
  In the compact window this is a short message on
  a second line, shown only while the condition lasts, in the form
  `Refresh failed at 14:25:01: <what went wrong>`. The window grows to hold it and
  returns to its single-row size afterwards.
- **Error text is red.** Every error message is shown in red: the failed refresh, whether
  or not older figures are still on show, the loss of contact with the application, and an
  invalid value in the settings view. No error is shown in another colour.
- The last good figures stay in the row after a failed refresh, dimmed to show that
  they may be out of date, and the dimming goes when a refresh succeeds again. After an HTTP 429 they
  are not dimmed.
- **On startup the newest reading in the history file is loaded and shown at once**, as the row's
  figures with the time of that reading, until the first refresh replaces it, so the row is not empty
  while the first request is on its way. If that refresh fails, the figures stay, dimmed, with the usual
  failure message. It is not written to the history again. The reading shown is the newest row that has
  amounts, so a failed row is passed over. If there is no history, or no row has amounts, nothing is shown in
  advance.
- Before the first reading the row has a gray box for each element that is switched on and has no value yet (see The elements of the
  row), and no word. If the first refresh failed, and if the account reports no usage at all, it has gray boxes as well, and
  their tooltips say which of the two it is.
- If the window cannot reach the application, it says so in red (`Lost contact with the
  application. Still trying.`), keeps trying, and shows what it last had.
- The window also resizes to fit when a setting adds or removes an item of the row, as the settings view
  is applied.
- A message line, the warnings among them, shown in the row is also written to the log (see Non-functional
  requirements).

The refresh button stays enabled at all times, and the behavior of everything
behind these controls is unchanged.

## Usage history

Every reading the application gets is kept, so the usage can be looked at afterwards.

- Each refresh that has amounts, and each failed one, adds one row to a CSV file named
  `java-aip-usage.csv` in the working directory, beside the log. The file is not
  committed to version control. It is added to, never overwritten, so successive runs build
  one history, and nothing in it is ever rotated or deleted by the application.
- **Since version 0.82 three columns follow**, `monthly_limit`, `daily_limit` and `daily_auto`: the configured monthly limit
  counted and the daily limit in force when the reading was made, as amounts, empty for none, and `auto` or `set` for whether
  the daily limit was worked out or typed. A file of an earlier version is added to with them; its rows have none, and are read
  as before. **The header of a file that has the seven columns of before is rewritten once**, to the ten names, before the first
  row with ten is added: the file is written anew beside the old one and put in its place in one step, so that a failure leaves
  it as it was (and the row is then added to it as it is, with ten fields, and read by place).
- The columns are `datetime`, `used`, `limit`, `currency`, `status`, `interval` and `duration_ms`, with a
  header row written when the file is new or empty and never again, for example:

  ```
  datetime,used,limit,currency,status,interval,duration_ms
  2026-10-08 16:24:53,186.02,1000.00,USD,start,60,412
  2026-10-08 16:25:53,186.07,1000.00,USD,,60,388
  2026-10-08 16:26:53,,,,failed,60,5003
  ```

- `status` says what the row is. It is `start` on the first row written after the program started,
  `failed` on the row of a query that did not succeed, `start-failed` when the first row of a run is
  a failed one, and empty on every other. Each run has one row marked `start` or `start-failed`, so a file
  built over several runs has one per run, each where that run's first row was recorded. A run that
  records no row marks nothing. The mark is the first row actually written, so a reading with no amounts,
  which writes nothing, leaves it for the next row that is written.
- `interval` is the time between usage requests that was set when the request was made, in whole seconds.
  `duration_ms` is how long the query took, from the start of the refresh to the answer or the failure, which **includes getting the token** (a
  run of `claude`, up to 30 seconds) and the one retry after an HTTP 401, in whole milliseconds, so the first row of a run is usually the longest.
  The `interval` is the interval the application was using: the configured one with `Automatic` off, the one of the search with it on. Both are on every row, failed ones included, and plain numbers.
- A failed query's row has `datetime` of the time of the failure, since there is no `fetched_at`, and
  empty `used`, `limit` and `currency`. What went wrong is in the log, not in the file.

- `datetime` is the time of the reading, which is `fetched_at`, as the local date and time
  to the second in the form `yyyy-MM-dd HH:mm:ss`, with a space between the date and the
  time and no `T`, no `Z` and no zone offset. That is the form Excel recognises as a date
  and time when it imports the file, where an ISO 8601 `2026-10-08T14:24:53Z` it leaves as
  text. It is the machine's local time, the same clock the window shows, so the two agree.
  The file does not say which zone it is in, because Excel has no use for one, and the hour
  in which the clocks go back appears twice. It still sorts correctly as text.
- The columns are separated by commas and the numbers use a dot. A copy of Excel set to use a
  decimal comma may open the file as one column or read `260.36` as text; import it with
  Data, From Text/CSV, and choose comma as the delimiter and the dot as the decimal.
- `used` and `limit` are plain numbers with two decimals and a dot, whatever the machine's
  language, with no currency sign, no digit grouping and no quoting. A third decimal, which the
  endpoint can send, is rounded to the nearer second decimal and upwards when it is exactly half,
  so the same reading always writes the same text. A missing amount is an
  empty field. `currency` is the code the response names, for example `USD`, or empty when it named none.
- A file written before these columns is upgraded in place the first time a row is added: the header
  gets the new columns and the rows already in it get empty fields where the file never said. The header
  `datetime,used,limit` gets `currency`, `status`, `interval` and `duration_ms`; `datetime,used,limit,currency` gets `status`, `interval` and
  `duration_ms`; and the header `datetime,used,limit,currency,startup` is replaced by the new one, and a `1` in `startup` becomes
  `start` in `status`. Only an exact match of one of the three old headers is upgraded; any other header leaves the file as it is. Nothing else in it changes.
- A reading with no amounts, that of an account that reports no usage, writes nothing. A
  failed query does write a row, as above.
- A history that cannot be written is logged and nothing more. The reading is good, so the
  refresh still counts as a success, and the window shows it as usual.
- **A file is never seen half written.** Adding a row is an append, so a reader sees whole rows. The two
  writes that replace a file instead — the upgrade of an old header above, and the settings file — write a
  new file beside the old one and then put it in the old one's place in one step, so that the window, which
  reads the history while the application writes it, never finds a file that is missing, empty or half
  converted. Where the file system cannot do it in one step, the plain replacement is used, which is as
  good as that file system allows.
- The file holds spending figures, so it stays on this machine, and nothing in it is a
  credential. At the default interval of a minute it grows by about 60 kilobytes a day.
- **The file as bytes.** It is UTF-8. Every row the program adds, and the header, ends with a single line feed
  on every operating system, Windows included; the header of a new file and its first row are written together.
  A file that is upgraded from an older header is written the same way, every line of it, so a file never has
  two kinds of line ending because of the program.
  A file whose lines end with a carriage return and a line feed is read all the same.
- **What is written, more exactly.** A reading with one of the two amounts missing still writes a row, with
  that field empty. An amount used of zero is written `0.00`, not left empty. The `datetime` is cut to the
  second, not rounded: 14:24:53.987 is `14:24:53`. A comma in the currency is written as a space, so that the
  field can never break the columns; nothing else is escaped or quoted. A negative amount is rounded away from
  zero when it is exactly half.
- **The upgrade, more exactly.** The old header is compared with blanks round it taken off. A blank line stays
  as it is; every other line gets the fields it lacks, empty, whatever is in it; under the `startup` header a
  line that ends `,1` gets `start` and every other an empty status. The new file is written beside the old one
  as `java-aip-usage.csv.tmp`, and none is left behind. If the upgrade fails, the row of that refresh is not
  written. A row is added to a file whose header is none of the known ones without a header being added.

## The elements of the row

Which elements the row has, what each is called, how each is switched on and off in the settings, and what the row shows before
the first reading.

### The thirteen elements and their names

The row has fourteen elements that can be shown or hidden (the fourteenth, the cost button, since version 0.69; wherever this document says thirteen it counts those of before), and the settings button, the gear, which is
always there. (The four that show a view are called icons since version 0.80, `Log icon`, `History icon`, `Error log icon` and `Cost icon`, where their checkboxes read `Log button` and so on before: they are small icons, and the word says which thing of the
row is meant. Where an older passage of this document says the log button, the history button, the error log button or the cost button, it is the same four. `Refresh button` keeps its name: it does something, and shows no view.) Each has a name, which is the text of its checkbox in the settings and the word this
document uses for it. From left to right (in version 0.68, with every element of the time shown, the row read `16:43:44 34% 341.99 of 1,000.00`, the refresh arrow, `44s 175s +0.09 129s 102ms`, and the icons of the log, the history and the error log before the gear; the four icons are in the order below since version 0.81, and were the log, the history, the error log and the cost until then):

| # | Name | What it is | Example | Shown by default | Key in `settings.json` |
| --- | --- | --- | --- | --- | --- |
| 0 | `Status` | whether the day and the month are within their limits: a dot, green, amber, red or gray (since version 0.82) | a filled circle | yes | `showStatus` |
| 1 | `Time` | when the usage was last read | `20:28` | yes | `showTime` |
| 2 | `Percentage spent` | the share of the limit counted that is spent | `26%` | no | `showPercentage` |
| 3 | `Amount used` | what has been spent | `262.74` | yes | `showUsed` |
| 4 | `Monthly limit` | the limit counted: the server's, or the configured monthly limit (since version 0.82, see Limits, projections and the status of spending); called `Budget` until 0.82 | `1,000.00` | yes | `showLimit` |
| 4a | `Today's percentage` | the share of the daily limit spent today (since version 0.82) | `64%` | no | `showDayPercentage` |
| 4b | `Today's spend` | what was spent today (since version 0.82) | `4.37` | no | `showDayUsed` |
| 4c | `Daily limit` | the daily limit in force (since version 0.82) | `6.81` | no | `showDayLimit` |
| 5 | `Refresh button` | reads the usage now | the arrow round a circle | yes | `showRefresh` |
| 6 | `Countdown` | the seconds until the next refresh | `17s` | yes | `showCountdown` |
| 7 | `Interval` | the time between usage requests in force | `30s` | no | `showInterval` |
| 8 | `Change in the amount used` | the amount used less that of the reading before | `+1.37` | no | `showDeltaUsed` |
| 9 | `Time since the previous reading` | the seconds between the two readings | `30s` | no | `showDeltaTime` |
| 10 | `Request duration` | how long the latest usage request took | `87ms` | no | `showDuration` |
| 11 | `Cost icon` | shows and hides the cost control panel (since version 0.69) | the two bars | yes | `showCostIcon` |
| 12 | `History icon` | shows and hides the usage history | the table | yes | `showHistoryIcon` |
| 13 | `Log icon` | shows and hides the log | the sheet with lines | yes | `showLogIcon` |
| 14 | `Error log icon` | shows and hides the error log | the triangle | yes | `showErrorIcon` |

- **The four new elements of version 0.82** keep the numbers of the others: `Status` is the first of the row, before the time,
  and the three of the day follow the budget, after a thin gray line, `64% 4.37 of 6.81`. **Since version 0.83 the day comes
  first** (`docs/img/img_44.png`): after the time the three of the day, then the thin gray line, then the month's percentage,
  amount used and monthly limit, and their checkboxes in `Main view` in that order; the line stands between the two groups
  only when both have something shown. The rest of this paragraph holds: the word `of` between the two
  amounts as for the month and only when both are shown. They are in the green of the figures, and red as One green says. Their
  checkboxes in `Main view` are in the order of the row. With no daily limit, today's percentage and the daily limit are gray
  boxes whose tooltip says `No daily limit is set`.
- **The order of the icons is the cost control, the usage history, the log and the error log** (since version 0.81), and the
  gear after them, as ever. The checkboxes of the four in `Main view` follow it, since they are in the order of the row: `Cost
  icon`, `History icon`, `Log icon`, `Error log icon`.
- The time, the amount used, the budget, the refresh button and the countdown were always shown before version 0.32. They are on
  by default, so a settings file that says nothing of them changes nothing.
- Every element can be hidden, so the row can be emptied down to the gear. An element that is hidden
  is not there and takes no room.
- The word `of` between the amount used and the budget is there only when both are shown.
- **With the refresh button hidden** there is no manual refresh in the window; the scheduled one goes on.
- **With the countdown hidden** an HTTP 429 is shown in a message line (see Countdown).

### The request duration

- **The request duration shows how long the usage request took.** It is the latest request that has ended, a failed one
  included, and the figure is the one the history file records for that request in `duration_ms`.
- **How it is written.** Under a second, the milliseconds and `ms` with no space: `87ms`, `999ms`. From a second on,
  the whole seconds, `s`, the remaining milliseconds in three digits and `ms`: 1,034 milliseconds is `1s034ms`, 12,005
  is `12s005ms`, and 1,000 is `1s000ms`. It is never written in minutes.
- **Where it is.** After the time since the previous reading: the last of the figures, before the buttons.
- **It is off by default and green**, the green of the other figures, and its tooltip in the row is `How long the latest
  usage request took`.
- **It behaves like the other figures:** it is dimmed with the old figures after a failed refresh, it is a gray box at
  the start until a request has ended, and its room is that of `999ms`.
  A reading taken from the history at startup has no request behind it, so until the first request has ended the element is
  still a gray box, beside the figures of that reading.

### A checkbox for each element, in the order of the row

- The section `Main view` of the settings has one checkbox for each of the thirteen elements, with the
  name of the element as its text, **in the order of the row, from left to right**, so the first
  checkbox is the leftmost element.
- **A property of an element is beside its checkbox**, in the same line, not somewhere else in the view:

  | Element | Property beside its checkbox |
  | --- | --- |
  | `Status` | `Blinks`, whether the circle fades in and out (since version 0.88); its tooltip is `The circle fades in and out while it is green, orange or red. Off, it stands still.` |
  | `Time` | the time format, `hh:mm` or `hh:mm:ss` |
  | `Amount used` | `Currency symbol`, which puts the symbol before the amount used and before the budget |

  The other elements have none. `Currency symbol` is one switch for both amounts; it can be set while the amount used is
  hidden, and then applies to the budget.
- **A tooltip on the text of each checkbox** says in full what the element shows and what it means:

  | Element | Tooltip |
  | --- | --- |
  | `Status` | `A dot that says how the day and the month stand against their limits: green, amber when a limit is about to be passed, red when one is passed.` (since version 0.82) |
  | `Time` | `The time of day at which the usage shown was last read from Anthropic.` |
  | `Percentage spent` | `How much of the budget is spent, as a percentage, as Anthropic reports it.` |
  | `Amount used` | `What the account has spent in the current period, in the currency of the account.` |
  | `Monthly limit` | `The monthly limit counted: the one the server sends, or the one typed under Cost control.` (since version 0.83; `Budget` and `The spend limit of the account: the most that can be spent in the current period.` until then) |
  | `Today's percentage` | `The share of the daily limit spent today.` (since version 0.82) |
  | `Today's spend` | `What was spent today, counted from the last reading before today.` (since version 0.82; `What was spent today.` until 0.83, too short to say what it counts) |
  | `Daily limit` | `The daily limit in force: the one typed, or the monthly limit divided by the working days.` (since version 0.82) |
  | `Refresh button` | `A button that reads the usage now, without waiting for the next refresh.` |
  | `Countdown` | `The seconds until the usage is read again. It is negative when a refresh is overdue, and red while Anthropic asks the program to slow down.` |
  | `Interval` | `The time between two usage requests, in seconds, as set under Request, or the longer wait in force after Anthropic asked the program to slow down.` |
  | `Change in the amount used` | `How much the amount used has changed since the reading before this one. 0.00 when it has not changed.` |
  | `Time since the previous reading` | `The seconds between this reading and the one before it.` |
  | `Request duration` | `How long the latest usage request to Anthropic took, in milliseconds, and in seconds and milliseconds from one second on.` |
  | `Log icon` | `The icon that shows the log of the program below the row, and hides it again.` |
  | `History icon` | `The icon that shows the usage history, one line for each reading, below the row, and hides it again.` |
  | `Error log icon` | `The icon that shows the failed refreshes of this run below the row, and hides it again.` |

  The two properties have one as well: the time format, `Whether the time is shown with or without
  seconds.`; the currency symbol, `Puts the currency symbol before the amount used and the budget: the dollar sign for
  US dollars, the code for any other currency.`

### A button that selects every element of `Main view`, or none

- **The group `Main view` of the settings has a small button** (since version 0.46) in the line of its heading, at the right of the
  heading. It is an icon with no text: a small square with a tick in it, drawn like the icons of the row, and the same
  whatever is selected. Its tooltip is `Select every element, or none`. It has no border but
  while the mouse is on it, and is no taller than the heading.
- **A click switches the thirteen checkboxes of the elements**: when every one of them is on, all are switched off;
  otherwise all are switched on. So with none on, and with some on, a click switches all on.
- **The two properties are not touched**: `Currency symbol` and the time format stay as they are.
- **It is a click like any other in `Main view`**: the row shows it at once, as a preview, and it is saved when the
  view is saved, by `Save` or by leaving through a button of the row; `Cancel` puts the row back.
- **The three panel buttons follow as for a click on their own checkbox**: the button of a panel that is open stays
  until the panel is closed.

### A click is shown in the row at once

- **A click on a checkbox of `Main view` changes the row at once**, while the settings are still
  shown, so that it can be seen which element the checkbox belongs to. The same holds for the two
  properties.
- **It is a preview.** Nothing is sent to the backend and nothing is saved until the view is saved, by **Save** or by
  leaving it through a button of the row (see Settings). **Cancel** puts the row back to what the backend has. The settings
  of the other sections, `Request`, `History view` and `Log`, have no preview: they take effect when they are saved.
- The preview is of the row's figures only: the time in the other format and the amounts with or without the symbol are the
  backend's own texts, which it sends in both forms. The time in a message line, `Refresh failed at ...`, changes on Save.
- After a save the row keeps what was previewed until the next status says the same, so it does not go back to the old state for a moment.
- **The panel buttons do not go while the settings are shown** (since version 0.79; before, they followed their checkboxes there too): one that is switched off is dimmed (see The panel buttons while the settings are shown). The button of a panel that is open
  still stays until the panel is closed.
- An element that is switched on in the preview and has no value to show is a gray box in its room.

### A placeholder at the start

- When the program starts, and until the first reading or the first failure is there, **each element that is switched on
  and has no value yet is shown as a green line where the foot of its text would be**, about as wide as the element usually is.
  There is no word: the row does not say `Loading…`.
- **The line is all there is of it** (since version 0.80): 2 pixels thick, the thickness of the line under the selected tab of the
  settings, in the same green as the text of the figures, at the bottom of the place the element has; nothing is drawn at its
  top or at its sides, and nothing fills it (see Gray boxes in the place of `No usage reported` and `No data`). In versions 0.77
  to 0.79 it was a rectangle with a frame of one pixel all the way round; until version 0.76 the box was filled, first with gray
  and then with a light green, and its corners were rounded by 3 pixels.
- The placeholder has **the tooltip of its element**, in the form that needs no value: `Last update`,
  `Share of the budget spent`, `Credits used`, `Credit budget` (since version 0.83 `Share of the monthly limit spent` and `Monthly limit`), `Seconds until the next refresh`,
  `Change in the amount used since the previous reading`, `Time since the previous reading` and `How long the latest usage request took`.
- **Which elements have one.** The time, the percentage, the amount used, the budget, the countdown, the two changes and the
  request duration. The
  interval is known from the settings and the buttons are drawn at once, so they have none; a countdown that already has a value
  shows it. A reading taken from the history at startup is a reading, so the row then has its figures and no gray box.
- For the countdown it is for the start only. The time and the amounts are gray boxes again when there is no reading at
  all or no usage is reported, and the two changes and the request duration whenever they have no value (see the two
  passages below).
- Before the page has its first answer from the application it does not know which elements are switched on, so for that moment
  it shows only the refresh button and the panel buttons.

### A gray box for a figure that is not known

- **The gray box is not for the start only.** At any time, an element of the row that is switched on, and whose value
  is not known, is shown as the gray box of A placeholder at the start, with the tooltip it has there. Before version 0.39 it
  kept its room and was not seen.
- **Which elements, and when their value is not known:**

  | Element | Not known when |
  | --- | --- |
  | `Change in the amount used` | there is no reading before this one to compare with, or the row has no amount used: `No data`, `No usage reported` |
  | `Time since the previous reading` | there is no reading before this one, or the application has no time for either of the two |
  | `Request duration` | no usage request of this run has ended yet, as when the reading shown was taken from the history at startup |

  The case that brought this up: `No usage reported`, both changes switched on, and, before, an empty gap
  where the two are; there are two gray boxes there now.
- **The other elements.** The countdown is a gray box at the start only. The time, the percentage, the amount used and
  the budget have their figures after it, or are gray boxes with no usage and with no data (see the next passage); with
  a reading that has usage, the percentage is not there when Anthropic reports none.
- **A change of nothing is written, not left out.** When the amount used is the same as that of the reading before, the
  `Change in the amount used` shows `0.00`: no sign, no currency symbol, two decimals, in the green of the other
  figures. Before version 0.39 the element was empty then. A rise is `+1.37` and a fall `-1.37`, as before.
- **There is no empty, unseen element.** A switched-on figure of the row is its value or a gray box. The row
  still does not change its width from one refresh to the next: the gray box of an element is as wide as the room the
  element kept before.
- **The gray box is not dimmed with the old figures** after a failed refresh; it has no figure to be old.
- **The tooltip of the checkbox `Change in the amount used`** in the settings reads `How much the amount used has
  changed since the reading before this one. 0.00 when it has not changed.`
- **The history panel is not changed by this**: its columns `Δ used` and `Δ time` are as they were.

### Gray boxes in the place of `No usage reported` and `No data`, and a green border round every gray box

- **The row does not say `No usage reported`** (it did, before version 0.41). When Anthropic reports no usage, the elements of the amounts that
  are switched on, the percentage, the amount used and the budget, are each shown as a gray box, as at the start. An
  element that is switched off is not there, as always.
- **Nor does it say `No data`** (as it did). When there is no reading at all, the first request having failed, the same elements
  are gray boxes as well, and so is the time, which has no value then. That a refresh failed is said by the message
  line, or by the red countdown for an HTTP 429, as before.
- **The time, with no usage reported, is the time of the reading**, as before: that reading was taken.
- **The word `of` between the amount used and the budget** is there when both are shown, between the two boxes as between
  the two figures.
- **The tooltip of such a box says why it is one**: the element's own short tooltip, a colon, and `no usage reported`
  or `no data`. So `Credits used: no usage reported`, `Credit budget: no data`, `Share of the budget spent: no usage
  reported`, `Last update: no data`. A box at the start, and the box of a figure that is not known, keep the tooltip
  they have.
- **No gray box of the row has a background colour** (since version 0.77): nothing fills it, and the background of the window is seen in its place, on both themes. It had a light green one from version 0.66 (about `#dafbe1` on the light theme and `#12301d` on the dark one), and a gray one before that. Where
  these requirements say a gray box, this is the placeholder: its name stays, though nothing of it is gray any more, nor a box.
- **Every gray box of the row is a green line at its bottom, and the line is all there is of it** (since version 0.80): 2 pixels thick, like the line under the selected tab of the settings, in the same green as the text of the figures (`#1a7f37` on the light theme, `#3fb950` on the dark one), at its full
  strength, as wide as the box. It has no frame at its top or at its sides (in versions 0.77 to 0.79 it had a frame of 1 pixel all the way round, with square corners). That holds for every gray box, at the start, for a figure that is not known,
  and for no usage and no data alike.
- **The place of a gray box is as wide and as tall as it was**, and it is the whole of that place the mouse is on it: the tooltip of the box, and the line that says an answer has no spend, come with the mouse anywhere in the place the figure would have, not on the 2 pixels of the line alone.
- **The lines are not dimmed** after a failed refresh.
- **The two changes and the request duration** are as A gray box for a figure that is not known says; with no usage
  and with no data the two changes are gray boxes, since there is nothing to compare.
- **Nothing else says `No usage reported` in the window.** The log still records each reading as it does.

### A border round the window that has no title bar

- **While the window has no title bar it has a border**: a rectangle all the way round its content, 1 pixel wide, drawn
  by the page. It shows where the window ends, which the frame of the system does when there is a title bar.
- **Its colour is that of the text**: black on the light theme, and the light colour of the text on the dark one, where
  black would not be seen.
- **It is there exactly while the window has no title bar**: the setting `Title bar` is off and the settings are not shown (since
  version 0.87, the lists included; until then no panel was shown, or none but the cost control, since version 0.79).
  With the title bar shown, by the setting or because a panel is open, there is no such border.
- **It is left out while the red border of the fake backend is shown**, which is round the window then. In debug mode
  with the fake backend off, and no title bar, the black border is there.
- **The window is larger by the border**, 1 pixel on every side, so nothing in the row is covered.
- **The border is at the edges of the window**, the right and the bottom one as well as the left and the top one, not a
  fraction of a pixel inside them; the same holds for the red border of the fake backend round the row.
- It is there since version 0.42.

### Dragging the window that has no title bar

- **While the window has no title bar it is moved by dragging it**: the mouse button pressed anywhere inside the
  window, and the mouse moved with the button held, moves the window with it; releasing the button leaves it there.
- **The buttons are as they were** (the window can be dragged since version 0.43). A press on a button of the row, the refresh button, the log, history and error log
  buttons, and the gear, does what the button does and never begins a drag.
- **A press with no movement does nothing**: the window does not move, and no tooltip, hover or countdown line is lost
  by it.
- **Anywhere else inside the border begins a drag**: the figures, the gray boxes, the room between the elements, a
  message line, and the border itself, the red one of the fake backend included. Since version 0.87, with a list shown and no
  title bar, its lines and its line of controls as well; **the scrollbars of a list do not**: a press on one scrolls the list as
  always, and the wheel scrolls it. **Since version 0.89 also where the scrollbar takes no room** (a fault of 0.87 and 0.88 seen
  on macOS, whose scrollbars lie over the lines and appear only while the list is scrolled): a press within 16 pixels of the
  right edge of a list that scrolls in height, or of the bottom edge of one that scrolls in width, is a press on its scrollbar
  and moves the slider, never the window, whether the scrollbar is drawn at that moment or not. **Nor does the bottom edge of the
  window** while a list is shown: it makes the list taller and shorter (see Taller and shorter by the bottom edge).
- **The left mouse button only.** Another button moves nothing.
- **While the window is dragged the page is not told of the moves**, so the text of the row is not selected by a drag
  that moves the window; the press and the release reach it as always.
- **Only without the title bar.** With the title bar the window is moved by it, and a drag inside the window moves
  nothing; so nothing changes while a panel that has the title bar is shown, which since version 0.87 is the settings alone. With the cost control shown and the title bar
  switched off (since version 0.79) the window is dragged as the row alone is, by the row and by the panel.
- **The window stays where it was dropped** for the run, also when the title bar comes and goes for a panel. Where it
  was is not remembered for the next start: the program starts where it always does. (Remembering the position was
  considered and decided against.)

### Taller and shorter by the bottom edge

- **Since version 0.89 a list is made taller and shorter by dragging the bottom edge of the window** while the setting `Title
  bar` is off, as it is saved (`docs/img/img_57.png`): the usage history, the log and the error log, alone or among several views.
  **The two buttons `Taller by five lines` and `Shorter by five lines`** that 0.87 and 0.88 had in each list's line of controls
  **are gone**, in every view. With the title bar on the window is dragged larger by its frame, as before.
- **The edge** is the bottom 6 pixels of the window, the border the page draws included. While the pointer is there it is the
  cursor of a vertical resize; a press there and a drag changes the height and never moves the window (see Dragging the window
  that has no title bar). The page draws it; the window has no frame of the system to drag.
- **A grip shows while the pointer is near it**: a short bar, 24 by 2 pixels with rounded ends, centred 2 pixels above the
  bottom edge, in the muted gray (`#8a8f98`). It appears when the pointer is in the edge or within 10 pixels above it, and goes
  when the pointer leaves, so that the edge can be found where the cursor does not change. It takes no room and no click of its
  own: a press on it is a press on the edge.
- **The height that changes is that of the lowest list**, as when the window is dragged by its frame: the views above keep
  theirs. Never shorter than the height a list opens with, ten times the row's, and never taller than the program makes a window
  (1,600 pixels) or the screen has room for.
- **The height is remembered** for the usage history and for the log, as one dragged by the frame is (see Remembered heights),
  and the list opens at it the next time; the error log's holds while it stays open, as a dragged one does.
- **The width stays**: the log and the error log keep the width they open with; there is no edge to drag at the right.
- **Only with a list shown.** The row alone, the cost control alone and the settings have no such edge: their height is their
  content's.
- A height that cannot be saved is logged, as one dragged by the frame is, and the list keeps the height it was dragged to.

### The row goes while the pointer is outside the window

- **Since version 0.85 the row goes while the pointer is outside the window**, and the views that are shown stand at the top
  of it; the row comes back the moment the pointer is inside again. It does so only when all of these hold:
  - the setting `Title bar` is off, as it is saved;
  - a view is shown: the cost control, a list, several views, or the settings;
  - of the row only icons are switched on: the refresh button and the icons of the views may be on, and every figure is off, the
    time, the percentage, the amount used, the limit, the countdown, the interval, the two changes, the request duration and the
    three figures of the day; **and the status circle is off** (since version 0.86, `docs/img/img_54.png`: in 0.85 the circle counted
    as an icon, and the row went with the circle shown). As the settings are saved: what the open settings preview does not count.
- **Outside is outside the whole window**, its title bar and its frame included: the pointer on them is still in the program, so
  that the row stays while the window is moved by its title bar or dragged larger by its frame.
- **The pointer is outside for half a second** before the row goes, so that a pointer that brushes past the edge moves nothing.
  It is back at once.
- **The window is less tall by the row**, its message lines with it, while the row is away; its width stays, and so does its place:
  the top of the window stays where it is. A list keeps its height of its own, so that the height the person gave it is remembered
  as it would be with the row there, and the window is as tall again when the row comes back. What is stored for the next start
  is the size with the row.
- **The cost control has room above it while the row is away** (since version 0.86, `docs/img/img_55.png`): 6 pixels more at its top,
  so that its bars do not touch the border of the window; the window is taller by them. With the row there nothing changes, and
  the sides and the bottom are as they were.
- **The row comes back when the conditions no longer hold**: the last view is closed, or the settings are saved with the title bar
  on, a figure of the row or the status circle on.
- Asked for in `new-requirements.md` with the orange of 0.85; the choices, the conditions, a shorter window and half a second,
  were the person's.

### Debug lines in the log

- **The program writes debug lines** (since version 0.44; since version 0.95 some of them are Fine lines, see Version 0.95). They are written to the log file and to the console in every run, with
  `--debug` and without it, whatever level the log panel shows.
- **The log panel shows them with `Debug` only**, so with the default, `Info`, they are not seen. `Debug` now shows
  more than `Info` does.
- **Three lines are debug lines**, which were written at the information level; their wording is unchanged:

  | Line | Written when |
  | --- | --- |
  | `Window fitted to <width>x<height>`, with what follows it | the window is sized to the page |
  | `The <panel> window height is now <N> px` | the height of the history or of the log is remembered |
  | `The window has its title bar` and `The window has no title bar` | the title bar comes or goes |

- **Every other line keeps its level**, the two lines of each refresh, `GET ... -> HTTP 200 in ... ms` and `Usage
  refresh succeeded`, among them: they are information.
- **Which lines are debug lines** is said here, line by line; a line that is not in the table is not one.
- A debug line has `FINE` as the word for its level in the file (why that word is said with the log panel, under The level of a line).
- **Only the program's own debug lines are written.** What the runtime and the libraries would write at that level is
  not: their lines are written from the information level up, as before.
- Nothing finer than a debug line is written.

### An error in the log when the response has a format the program cannot process

- **When a usage response cannot be processed because of its format, an error is written to the log** (since version
  0.45), at the error level: `The usage response could not be processed: <reason>`, and after it the beginning of the response.
- **The cases** are those in which the program reads the response and cannot make a reading of it: it is empty, it is
  not JSON, it is not a JSON object, or what it holds of the spend is not in the form the program knows. A response
  that is an HTTP error, a timeout, and a failure to obtain a token are not among them.
- **The response is written with it**: the first 2,000 characters of the body, masked like everything the log gets,
  on the lines after the first, so that the new format can be seen in the log. A longer body is cut there and the first
  line says so after the reason, ` (the first 2000 of N characters)`; an empty one is written as `(empty)`.
- **The warning stays.** `Usage refresh failed: <the message>` is written as for every failed refresh, the row shows
  the failure as before, and the error log panel has its one entry.
- It is written whether or not `Log the response` is on.
- **The reasons** are the messages the row shows for these failures: `The usage response is not valid JSON: <where and what>`, the place and the fault
  as the program's JSON reader says them, for example `line 1, column 1: a value was expected`; `The usage response is not a JSON object.`, `The usage response has no "spend" object; its format may
  have changed.` and `The usage response enables spend but reports neither "used" nor "limit"; its format may have
  changed.`; and, since version 0.72, `The usage response has an amount larger than the program can hold; its format may have changed.`
- In the file the word for the level of an error is `SEVERE`; the log panel shows the line at every level.

### The title bar is not previewed

- **`Title bar` takes effect when the settings are saved, not when it is clicked.** It was considered, with the
  requirements of version 0.41 to 0.46, to let a click
  change the window at once, and decided against: while the settings are shown the window has its title bar whatever
  the setting says, so a click could not be seen. It stays as Settings describes it.

### An answer that came and has no spend is said explicitly

- **When Anthropic answers and the answer has no spend, the row says so in a message line** below it, like the line of
  a failed refresh, with the gray boxes of the amounts above it (since version 0.47). Since version 0.56 the line is
  shown only while the mouse is on one of those boxes (see The text of an answer with no spend is shown while the mouse
  is on a gray box). Before, the gray boxes and their
  tooltips were all that said it, and the log said `Usage refresh succeeded`.
- **What it is.** The answer is an HTTP 200 that the program reads without a fault, and whose `spend` is not enabled:
  there is no amount used and no budget in it. It is not a response whose format cannot be processed, and writes no
  error of that kind.
- **A seat-based account is named as the likely cause.** When the answer has no spend and has limits of a session or of
  a week, a list `limits` with something in it or one of the windows `five_hour` and `seven_day`, the line reads
  `Received, but this account reports no spend: it looks like a seat-based account, which is not supported yet.` The
  program does not show a seat-based account: it reads spend only (see Scope and limits).
- **With no such limits no kind of account is guessed**: `Received, but Anthropic reports no spend for this account
  (spend is not enabled).`
- **The line is muted gray, not red**, since nothing failed: the gray of the notes, `#8a8f98`. It is otherwise a
  message line like the others, and the window is taller by it.
- **It goes with the next reading that has spend**, and it gives way to the red line of a failed refresh, which says
  what failed; when that failure is over and the answer still has no spend, it is back.
- **It is a reading, not a failure.** It is written to the history as a reading with no amounts, it is not in the error
  log, the row is not marked as old, and the time is that of the reading.
- **The log says it in the place of `Usage refresh succeeded`**, with the same text: as a **warning** when it begins,
  which is the first such reading of a run and the first after a reading that had spend, and as **information** at the
  refreshes that follow while it lasts, so that the log is not filled with warnings.
- **The tooltips of the gray boxes** are as they were: `Credits used: no usage reported`.

### The width of the row and of its message lines

- **The fault that was** (before version 0.48). A message line is as wide as the row, and a row can be as narrow as the
  gear. With the settings shown, which are far wider, a message then stood in a column of the gear's width at the left of the window, a syllable to a
  line, and pushed the settings out of sight below it (the case that brought it up: every element of the row switched off,
  and the message of an answer with no spend).
- **While a panel is shown the row is as wide as the window.** That holds for every panel: the settings, the debug
  settings, the log, the usage history and the error log. The message lines below the row are as wide with it, so a
  message breaks at the edge of the window and nowhere before.
- **The window is no wider for it.** With the settings and the debug settings the window has the least width the view
  needs, as before, and the row takes that width; with the other panels the window has the width it had.
- **The elements of the row stay at the left**, in their places; the room that is added is at the right of the last
  button.
- **With no panel, a message line gives the window a least width.** While a message line is shown and no panel is, the
  window is at least 300 pixels wide, which is room for about forty characters of the message in a line. A row that is
  wider keeps its own width, and a window with no message line is as wide as its row, as before.
- **Which lines count**: every message line below the row, that of a failed refresh, that of an answer with no spend,
  the line of an HTTP 429 over the countdown, and `Lost contact with the application`.
- **The height follows.** The window is as tall as the row and its message lines at the width they have then, so with
  the least width a message takes fewer lines and the window is lower than it was.
- The 900 pixels at which a row wraps are unchanged.
- **The least width is of the page**, 300 pixels of its content; the border of a window with no title bar, and the red
  border of the fake backend, are added to it as to any width.

### The word `of` between the amount used and the budget

- **Between the amount used and the budget is the word `of`**: `187.39 of 1,000.00`. Before version 0.49 it was a slash.
- **The word is set back**, so that the two figures stand out: in the muted gray, `#8a8f98`, at 14 pixels, which is the least any text of the row has, and regular
  weight, on the baseline of the figures, with the room of a blank either side.
- **It is there when both figures are shown**, as the slash was, between two gray boxes as between two figures, and it
  is dimmed with the old figures after a failed refresh.
- Nothing else changes: the tooltips, the history, whose columns have no such sign, and the log.

### Bigger tooltips

- **Every tooltip of the window is 14 pixels**, the size of the message lines (since version 0.50; it was about 11 before): those of the row's
  figures, gray boxes and buttons, those of the settings and the debug settings, and those of the lines of the history.
- The texts of the tooltips are unchanged, and so is when a tooltip appears and goes. **Since version 0.91 how long a tooltip
  stays is a setting**: `Tooltip duration`, a field in milliseconds in the group `Window` of `General`, after `Show where the
  window is`, saved under `tooltipMillis`. **0, the default, keeps a tooltip for as long as the pointer is on what it belongs to**
  (the toolkit's 5 seconds were too short); any other value is a whole number from 500 to 3,600,000. Its tooltip: `How long a
  tooltip stays, in milliseconds: 0 keeps it for as long as the pointer is on it, or from 500 to 3,600,000.` A wrong value is
  refused as the other fields are, with the focus on it. It takes effect when the settings are saved, for every tooltip of the
  window. **Should the toolkit of the window give no way to keep its tooltips that long, the page draws its own**, in the same
  look and size, so that the setting always holds; a tooltip of the page never reaches beyond the window.
- A tooltip may reach beyond the window, as before: the window is often no larger than the row.
- **The tooltips are drawn by the window, not by the page**: the page says their text, and the size is a style of the
  window. Their typeface, their colours and their frame are the system's, as before.

### An icon of the program's own

- **The window has an icon of the program's own** (since version 0.51) in the place of the one the system or the toolkit gives a program that has none: in the title bar
  and in the taskbar, and wherever else the system shows the icon of a window.
- **It is a gauge**: a half circle with a needle, in the green of the figures, `#1a7f37` with the lighter `#3fb950` for
  the needle, on a dark rounded square, `#0d1117`. The needle points to the upper right, at about two thirds.
- **It is given in several sizes**, 16, 32, 48 and 256 pixels, each drawn for its size and not scaled from another, so
  that it is clear in the title bar as in a large view.
- **Every window of the program has it**: the one with the title bar and the one without, which are exchanged for each
  other.
- The icon is a file of the program, drawn once and kept with its sources; it is not downloaded.
- **The gauge, exactly.** The arc is the upper half of a ring with round ends, and the needle goes from a round hub at
  the middle of the ring's foot; the corners of the square are rounded by a fifth of its side and see-through beyond
  that. In the small sizes the lines are thicker in proportion, so that they are seen.

### A zoom for the lists: the log, the usage history and the error log

- **The icons of the line at the top of a list stand at its right end** (since version 0.82): the two of the zoom, the funnel
  of the history and the two of the log's file, in that order, close together; the log's `Level` and `Date` stay at the left.
  The line is as wide as the list, so the icons are above the right edge of the lines.

- **The text of the three lists can be made larger and smaller** (since version 0.52) with two small buttons, each an icon with no text: a
  magnifying glass with a plus, and one with a minus. Their tooltips are `Larger text` and `Smaller text`.
- **The icons of this line are easy to see** (since version 0.80): the two of the zoom, the one of the zero usage lines in the history, and the two of the log's reset and merge are drawn **in the colour of the text**, not in the muted gray,
  **16 pixels** large where they were 13, with a line of about 2 where it was 1.6; the button round each is 22 pixels. One that cannot be pressed is at a third of its strength, as before, and the button of the zero usage lines is blue while the lines are hidden.
  The icons of the row, the log, history, error log and cost icons and the gear, are as they were.
- **Where they are.** In a line at the top of each list, at its left, which stays while the list is scrolled: in the
  log it is the line of `Level` and `Date`, after those two; the history and the error log get such a line for them.
- **One size for all three lists.** A change made in one holds for the others.
- **The sizes.** One press is one pixel. The text is 12 pixels by default, as it was, at least 10 and at most 20. At 10
  the minus is disabled, at 20 the plus.
- **A line is a quarter taller than its text**, as 15 pixels are to 12: 25 pixels at the largest size.
- **Everything of a list follows**: its lines and their height, the header of a table, and the widths of the history's
  columns, which are in characters and so grow with the text; the notes of the list and the line of the two controls
  keep their size.
- **The window follows where it is sized to the list**: with the history shown the window is as wide as the table
  needs at the size chosen. The log and the error log keep their width, and their lines scroll sideways as before.
- **A change takes effect at once and is saved at once**, in `settings.json` under the key `listFontSize`, a whole
  number from 10 to 20; a value in the file that is not one has the default. So it holds when another list is shown,
  and at the next start.
- **The settings view does not have it**, like the two choices of the log.
- **When it cannot be saved** it holds for the run, and a red line in the list says `The text size could not be saved:
  <reason>`, until a size is saved.
- **The page has the size from the application when it starts**, with its configuration; a size it is not told, or one
  out of the range, is 12.
- **The line of the two buttons is shown with each list and with nothing else**: not with the settings, the debug
  settings, or no panel. The two choices of the log are in that line for the log alone, before the buttons.

### The automatic interval

- **The group `Request` of the settings has a checkbox `Automatic`** (since version 0.53), in the line of the interval, after the field and
  its `s`. Its tooltip: `Lets the program find the shortest time between usage requests that Anthropic accepts, between
  10 and 300 seconds.` It is **off by default**, saved under the key `autoInterval`, and takes effect when the settings
  are saved, like the interval.
- **Off, the program uses the configured interval exactly** and does not change it: there is no back-off, and an HTTP 429, with its `Retry-After`, changes nothing of the delay.
- **On, the field of the interval is disabled** and shows, grayed, the interval the search is using now; it follows the
  search while the settings are shown. Switching `Automatic` off puts the configured interval back into the field,
  which can be typed in again. The configured interval is kept while `Automatic` is on, and is in force again when it
  is switched off.
- **On, the program searches for the interval** (the search changed in version 0.64; before it was a binary search that jumped from 10 to 300 when its
  bounds came close). The rule is **slow down quickly, speed up slowly, with a memory of the refused interval**. The least interval is
  **10 seconds** and the most **300 seconds**, both fixed in the program and not settings. The search begins at **60 seconds**, with no refused interval known.
- **The program remembers one figure, the refused interval `R`**: the interval at which the last 429 came, in whole seconds, or none.
- **The steps are of 5 seconds** (since version 0.66; before, one second up to a tenth): the interval is always **a multiple of 5 between 10 and 300**, and the search begins at 60.
- **After an HTTP 429** `R` becomes the interval that was refused, and the new interval is a tenth longer, rounded up to the next multiple of 5, and at least `R + 5`, at most 300. So 20 seconds refused is 25, 100 refused is 110, 10 refused is 15.
- **Speeding up is slow.** After **8 answers in a row without a 429** the interval is 5 seconds shorter, never less than `R + 5` and never less than 10. The count starts
  again at 0 after each step and after each 429. So the interval stays just above what was refused, and sinks one step at a time while nothing is refused, which is why
  the search keeps near the limit and mostly does not run into it.
- **`R` is forgotten by time** (since version 0.79): for every **10 minutes without a 429** `R` is 5 seconds lower, whatever the interval is; below 10 it is none. The 10 minutes are counted while the program runs with `Automatic` on, and
  start again after each step and after each 429; at the start of the program and when `Automatic` is switched on they begin with the first answer that comes then, since the time is not saved with the search. The step is made with an answer without a 429, the first that comes when the 10 minutes are over, and no more than one step with one answer, so it is still only
  an answer of Anthropic that moves the search. A step of `R` writes the line of the log, as a step of the interval does. (Until version 0.78 `R` was 5 seconds lower after 60 answers in a row without a 429, which at an interval of 200 seconds is more than three hours for one step:
  the interval stayed where it was although nothing was refused, which read as a fault.) **What then holds the interval is its own pace**, a step of 5 seconds for 8 answers, which is 27 minutes at 200 seconds and
  2 minutes at 15: at a long interval it comes down by 5 seconds about every half hour, no faster. **Once it has settled a 429 comes no more often than every ten minutes**, since the refused interval has to be forgotten a step before it is tried again. So when
  the limit of the server has gone down the search follows it down.
- **Before a refusal is known** (no `R`) the interval goes down by a tenth with each answer without a 429, rounded up to a multiple of 5 and at least 5 less than it was, not below 10, so the search does not
  take minutes to come down from 60.
- **A search that has found the limit is refused again now and then**, one second below what the server accepts: with `Automatic` on an HTTP 429 now and then is part of how it works, and not a fault, but each one
  is followed by a large enough step up, so that there are few.
- **Only an answer of Anthropic moves the search.** A refresh that fails in another way, an HTTP error that is not a
  429, a timeout, a token that cannot be obtained, leaves the interval, `R` and the counts as they are. A manual refresh
  moves the search as a scheduled one does.
- **With `Automatic` on a 429 does not double a wait**: the next request comes after the new interval. A `Retry-After` of the server is still honoured: when it is longer than the new
  interval, the interval becomes that wait (see With the automatic interval, the search follows a longer wait the server names).
- **The row shows it as it shows the interval in force**: the element `Interval` has the interval of the search, the
  countdown counts down to it, and a 429 is the red countdown or the message line as before.
- **What is typed while `Automatic` is off is the configured interval**, also when `Automatic` is then checked before the
  view is saved; with `Automatic` checked the field is not checked for being a number, since it is not the person's to
  fill.
- **The settings say both intervals** to the page: the one in force, and the configured one (`docs/api.md`).
- **The search is remembered.** The interval and `R` are saved in `settings.json` whenever they change, under
  the keys `autoIntervalSeconds` and `autoRefusedSeconds` (`R`, a whole number from 10 to 300, or the key left out for none), and the next start goes on from them.
  Values in the file that are not whole seconds from 10 to 300 are as if there were none, so the search begins at 60 with no `R`; an interval that is not a multiple of 5 is rounded up to the next one, and one at or below `R` is raised to `R + 5` (a saved `R` is rounded up as well).
  The keys `autoLowerSeconds` and `autoUpperSeconds` of earlier versions are read no more and are not written again. **Switching `Automatic` on begins the search anew** (since version 0.90; until then it went on from what was saved): 60 seconds
  and no refused interval, which is saved at once. Only the start of the program goes on from what was saved; a search that had
  sunk to 10 seconds with the fake backend, which never refuses, no longer holds the interval there when it is switched on again.
- **The log says each step, and how the new interval was calculated** (since version 0.89; until then only `Automatic interval: <N> s
  (refused at <R> s)` or `(no refusal yet)`), at the information level, so that it is clear the interval is the program's and not
  the configured one. Every line begins `Automatic interval: <N> s, calculated: ` and then says the reason, with the figures:
  - after an HTTP 429: `refused at <R> s, a tenth longer rounded up to a multiple of 5, at least <R + 5> s` (`<R>` is the
    interval that was refused, which `R` now is);
  - after a 429 whose `Retry-After` is longer: `the server asked to wait <W> s, refused at <R> s`;
  - after 8 answers in a row without a 429, with a refused interval known: `8 answers in a row without a refusal, 5 s shorter,
    not below <R + 5> s (refused at <R> s)`;
  - with none known: `no refusal yet, a tenth shorter rounded up to a multiple of 5, not below 10 s`;
  - when `R` is lowered by time: `10 minutes without a refusal, the refused interval lowered to <R> s` (or `the refused interval
    forgotten` when it falls below 10); the interval in the line is then the one in force, unchanged;
  - a step that the least or the most holds says so at the end: `, held at 10 s` or `, held at 300 s`.
  For example `Automatic interval: 110 s, calculated: refused at 100 s, a tenth longer rounded up to a multiple of 5, at least
  105 s`. A search that has settled, and whose interval an answer leaves as it is, writes nothing. Switching it on writes
  `Automatic interval on: <N> s, calculated (refused at <R> s)` or `(no refusal yet)`, and switching it off `Automatic interval
  off: the usage fetch interval is <N> s, configured`.
- **A change of the configured interval while `Automatic` is on** is saved and waits: it is in force when `Automatic` is
  switched off.
- **The usage history** records for each row the interval in force, which with `Automatic` on is that of the search.

### With the automatic interval, the search follows a longer wait the server names

- **The fault that was** (before version 0.54). With `Automatic` on, Anthropic answered a 429 with `retry-after 300`. The search worked out 156
  seconds and said so in the log, the row showed that as its interval, and the next try came after 5 minutes, as the
  message said. Two figures for one wait, and `Next try in 5 min` beside `Automatic interval: 156 s`, read as a fault (since version 0.70 the message has no such text).
- **The search takes the wait the server names.** After a 429 the search makes its step as above, and when the server
  names a wait that is longer than the interval it then has, **the interval becomes that wait**. So the log, the
  element `Interval` of the row and the countdown all say the same.
- **Never more than 300 seconds.** A wait of the server above 300 seconds makes the interval 300.
- **`R` stays the interval that was refused**, not the wait the server named.
- **It comes down again by itself**, by the slow steps above.
- **The log** says the interval the search has after that, in the line `Automatic interval: <N> s (...)`.
- **A wait of the server is counted in seconds, rounded up to a multiple of 5**, only a whole number of seconds is believed, and one that is no longer than the step the search
  made leaves the step as it is: the server's wait never shortens the interval.
- With `Automatic` off the `Retry-After` is only logged (see Refresh behavior).

### Nothing in the window can be selected

- **No text of the window can be selected** (since version 0.55), by dragging, by a double or triple click, or by a key for selecting
  everything. Before, a drag or a double click selected text as in a web page, and the row, a message line or a whole
  list stood in the blue of a selection.
- **That holds for everything**: the row and its message lines, the lines of the log, the usage history and the error
  log and their headers, the settings and the debug settings, and the About view.
- **So nothing can be copied out of the window.** The text of the settings could be selected and copied until now, and
  so could the lines of the lists; that is given up. The log and the history are files, and the About view says where
  they are.
- **The field of the interval is the one exception**: what is typed in it can be selected and replaced, as in any
  field to type in.
- Nothing else changes: a click on a button, the tooltips, and dragging the window that has no title bar.

### The text of an answer with no spend is shown while the mouse is on a gray box

- **The line that says an answer came and has no spend is not always there** (since version 0.56; it was, in 0.47 to
  0.55). It is shown below the row **while the
  mouse pointer is over one of the gray boxes** of the amounts, the percentage, the amount used or the budget, and it
  goes when the pointer leaves them.
- **It is the line there was**: the same two texts, in the muted gray, below the row, and the window is taller by it
  while it is shown, as for the line of an HTTP 429 over the countdown.
- **Only in that state**: a reading with no spend. A gray box at the start, and one with no data, has no such line; the
  failure with no data has its own red line, which is always there.
- **The tooltips of the boxes are as they were**, `Credits used: no usage reported`.
- **The log is as it was**: the text in the place of `Usage refresh succeeded`, as a warning when it begins.
- **The least width a message line gives the window** holds for this line while it is shown.
- **Going from one gray box to the next keeps the line**, and it goes at once when the reading under the mouse has spend
  again.
- **After a failed refresh** that follows a reading with no spend, the red line of the failure is there as always, and
  this line beside it while the mouse is on a box.

### The window is restored at start

- **The program stores on shutdown what the window showed, and starts as it was** (since version 0.65), when the checkbox `Restore the window at start` is on. It is in the tab `General`, in the group `Window`,
  **off by default** and saved under the key `restoreWindow`. Off, the program starts as before, with the row alone, and stores nothing.
- **What is stored**, in `settings.json`, when the program stops (by closing the window or by the system's stop): the panel that was open, `log`, `history`, `errors`, `cost` or `settings` or none, or, with `Several views at once`, every view that was open, their names with commas between them in the order of the icons (`cost,history` since version 0.81, `history,cost` before), the settings first when they were open with them (`settings,cost,history`, since version 0.81), and for the settings the tab that was shown; the size of
  the window, its width and its height, in pixels of its content; and the place of the window on the screen. The keys are `lastPanel`, `lastTab`, `windowWidth`, `windowHeight`, `windowX` and `windowY`. They are no settings
  of the settings view: it neither shows nor changes them, and saving the settings leaves them as they are.
- **What is restored.** At the next start with the checkbox on, the window has that size and place and the panel is open on the tab as it was, as if the person had opened it. **With several views stored, all of them are opened again**, stacked as they were, the settings among them on their tab, when `Several views at once` is on at that start; when it is off, the first of them alone. A file of version 0.80, with the names in the old order, is read in the new one. With the settings open the tab
  is the stored one, `lastTab`, and the first tab, `General`, when the program is not in debug mode and it was `Debug`. The remembered heights of the history and the log (see Remembered heights) are kept as they are; the stored size of
  the window is the one that is used at this start.
- **Nothing that cannot be is restored.** A value in the file that is not a whole number, a panel or tab that is not known, or a place that no screen shows (a screen that has gone) is left out, and the window
  then has its usual size or place; the other values are still used. A window is never restored larger than the screen it is on.
- **The title bar** is as the rules say: a panel that is open has it, whatever the setting says.
- **The log** says at the start, at the debug level, `The window is restored: <panel>, <width> x <height> at <x>, <y>`.
- **It is stored once**, at the end, and not while the program runs: a program that was stopped by force keeps what was stored the time before.

### The About view

- **The program has an About view** (since version 0.57), which says what the program is and shows its logo larger than an icon can.
- **It is the last tab of the settings but `Debug` in debug mode** (since version 0.90; the last until then), titled `About` (since version 0.64; before, a button `About` of the settings view opened it in place of the form). There is no button
  for it in the row of the window, and no window of its own.
- **It is shown in the place of the other tabs**, in the panel area below the row, with the strip of the titles above it and `Save` and `Cancel` below; the window is as tall as the row and the About view need,
  and as wide as the least width of the tabs.
- **The logo is at the left, and everything else at the right of it** (since version 0.80; until then the logo, the name and the version were centred above the table). The view is two columns: the logo alone in the
  left one, **in the middle of its height** (since version 0.81; at its top in version 0.80, which left an empty space below
  it); and in the right one, from the top, the name, the version and the table. So the view is lower than it was, and the table begins beside the logo, not under it.
- **What it shows**:
  - the **logo**, the gauge of the program's icon, **96 pixels** wide and tall, drawn from the largest file of the icon;
  - the **name**, `aip usage`, and under it the **version**, as the title bar has it: `v0.57` in the version that brought it;
  - **where the files are**, each with its full path: `Settings`, `Log` and `Usage history`. The history is the file
    in use, so in debug mode with the fake backend it is the file of the invented readings;
  - **What it runs on**: the runtime the version of the program is built on and the toolkit of its window, each by its
    name and with its version, and `System` with the name and the version of the operating system. The names of the
    first two are the version's own (for the Java version, `Java` and `JavaFX`; see The Java implementation), and the
    page has neither in it: the backend says them, and the page writes the names it is given (since version 0.78; until
    then the two names were written in the page, which was the one thing in it that belonged to one language);
  - **`Debug mode`**: `off`; or `on` when the program was started with `--debug`; or `on, the fake backend is in
    use` while it is.
- **The lines are a table of two columns**, the name of a thing at the left in the muted gray and what it is at the
  right, in the type of the settings view. The name and the version are above it, at the left edge of the table, and the logo is at the left of all three.
- **The text cannot be selected**, like everything in the window (see Nothing in the window can be selected); a path
  is copied with the button beside it (see The paths of the About view are links, with a button to copy each). A path that is longer than the view is wide is broken over lines; it never makes the
  window wider.
- **It has no button of its own**: `Back` is gone, the other tabs are reached by the strip.
- **The settings are kept as they were.** Showing the tab saves nothing and loses nothing: what was changed in the other tabs
  and not saved is still there when they are shown again, the preview in the row stays as it was, and `Save` and `Cancel` do what they always do.
- **A button of the row pressed while About is shown** does what it does from any tab: the settings are saved, as
  they stand, and the gear closes the view, or the other panel is shown.
- **Opening the settings shows the tab it was last on**, `About` too, as for every tab (see Settings).
- **The version of the window's toolkit is `unknown`** in a run that has no window, as some tests are, where it is a window that gives it.
- **While it is shown the window has its title bar**, as with the settings, of which it is a part.
- **The figures are read when the view is opened**, so the path of the history and the line of debug mode say what
  holds at that moment. If they cannot be read, the logo, the name and the version are shown and a red line says
  `The details could not be read: <reason>`.
- **The paths are given to the window alone.** The requests for the log and the history still say nothing of where a
  file is; the About view is where the program says it, to the person in front of it.

### The no-spend text is shown over every gray box

- **While a reading has no spend, the line that says so is shown with the mouse on any gray box of the row** (since
  version 0.58), not on
  those of the amounts alone: the percentage, the amount used, the budget, the change in the amount used, the time
  since the previous reading and the request duration, each while it is a gray box.
- Nothing else of that line changes: its two texts, its colour, that the window is taller by it, and that it goes when
  the mouse leaves the boxes. An element that shows a figure has no such line.

### Less room is reserved in the row

- **The fault that was** (before version 0.59). The row kept room for long values, so that its width does not change at
  every refresh: `+12.34` for the change in the amount used, `3600s` for the time since the previous reading, `1s034ms`
  for the request duration. With the usual short values that room was empty, and the elements stood far apart (the clipboard picture of
  the note: `60s`, `0.00`, `60s`, `4ms` with wide gaps between them and before the buttons).
- **Room is kept for the usual values only:**

  | Element | Room for | Before |
  | --- | --- | --- |
  | `Change in the amount used` | `+9.99` | `+12.34` |
  | `Time since the previous reading` | `99s` | `3600s` |
  | `Request duration` | `999ms` | `1s034ms` |

  The interval keeps its room for `300s` and the countdown its own. (The requirement was first written with `+99.99`
  and `999s`, which would have been no less room than before for the first and little less for the second; the
  figures here are the ones that close the gaps of the picture.)
- **A longer value makes the row wider for as long as it is shown**, and the window with it; nothing is cut off. That
  was so before for values longer than the room; it happens more often now, which is the price of the smaller gaps.
- **A gray box is as wide as the room of its element**, so the boxes are narrower by as much.
- The values stay at the left of their room, as before.

### The usage history spreads its spare width over all columns

- **The fault that was** (before version 0.60). When the history is wider than its columns need, which it is whenever the row is wide, all the spare
  width was given to the first column: the time stood at the left edge and the other columns far away at the right.
- **The spare width is shared out equally as room between the columns.** Each column has its fixed width, in
  characters, as before; the first is at the left edge of the list and the last at the right edge, and the gaps between
  neighbouring columns are all the same.
- **Within a column nothing changes**: the time at the left, the figures right-aligned so that they line up by their
  last digit, the header over its column, and `failed` where the amount used would be.
- **When there is no spare width** the table is as it was, and a table wider than the window scrolls sideways.

### The paths of the About view are links, with a button to copy each

- **The three paths of the About view are not bold, but for the name of the file** (since version 0.61; the name is bold since version 0.79): the folders of a path are in regular weight, and the name of the file alone, what follows
  the last separator of the path, `settings.json` for one, is bold, so that it is found at a glance. The rest of the view has the weight of the
  settings.
- **Each path is a link**: in the blue of the notes, `#0969da` on the light theme and `#58a6ff` on the dark one,
  underlined, with the pointer of a link. A click **opens the folder the file is in, with the file marked** where the
  system can do that (the Explorer on Windows, the Finder on macOS); elsewhere it opens the folder. The file itself is
  not opened: the log and the history are in use.
- **When the file is not there yet**, as a log that was never written, the folder is opened without a file marked.
- **Beside each path is a small button that copies it**: an icon with no text, two sheets one over the other, with the
  tooltip `Copy the path`. A click puts the full path on the clipboard, as text.
- **A copy is confirmed** in the button itself: its icon is a tick for a second and a half, and its tooltip `Copied`,
  then it is the copy icon again.
- **Nothing can be selected still.** The button is how a path is copied; the rule that no text of the window can be
  selected stays as it is.
- **The page names the file, never a path**: it asks for `settings`, `log` or `history` to be opened or copied, and
  the program uses the path it knows. No request makes the program open or copy anything else.
- **The link has the tooltip `Open the folder of this file`.** The button is 18 pixels, with no border but while the
  mouse is on it, and its tick is green.
- **On another system than Windows and macOS** the folder is opened with `xdg-open`.
- **When it cannot be done**, the system refusing to open the folder or the clipboard not being there, a red line in
  the view says `The folder could not be opened: <reason>` or `The path could not be copied: <reason>`.

### A fake backend that can be configured

- **The ordinary answer of the fake backend can be shaped** (since version 0.62), in the tab `Debug`, beside the switch of the fake
  backend and its scenario. The settings below are for the scenario `normal`; the other scenarios stay the fixed
  test cases they are.
- **The kind of account**, a dropdown `Account` with two values:

  | Value | The answer |
  | --- | --- |
  | `Usage-based` | a usage document with spend: an amount used that grows, and a budget; the default |
  | `Seat-based` | a usage document with no spend and the limits of a session and of a week, as such an account has |

- **The scenario `seat-based` goes**, which was the one way to get the second kind since version 0.56: the account
  takes its place. A settings file that still names it has `normal` with the account `Seat-based`.
- **How much the amount used grows.** A field `Largest increase`, in **cents**: at each answer the amount used grows
  by a whole number of cents drawn at random, evenly, from 0 to that value. 0 means it never grows. Default 137, which is the step
  it grew by at every answer until now, from 0 to 100,000. It has no meaning for a seat-based account.
- **The budget and the amount it starts with.** Two fields, in dollars with up to two decimals: `Budget`, the spend
  limit the fake account reports, default 1,000.00; and `Starting amount`, the amount used it begins with, default
  186.02. Both are what they were fixed at until now. The budget is from 0.01 to 1,000,000 and the starting amount
  from 0 to the budget. They have no meaning for a seat-based account.
- **The amount used begins at the starting amount** when the fake backend is switched on, at the start of the program
  and whenever the starting amount is changed; a change of another setting lets it go on from where it is. It goes on
  past the budget when it reaches it, as an account that has spent more than its limit, and the percentage with it.
- **When the fake backend refuses.** A field `Refuse within`, in seconds: a request that comes less than that long
  after the request before it that was answered gets an **HTTP 429**, with no `retry-after`; any other gets the usage
  document. 0 means it never refuses. Default 0, from 0 to 3,600. A refused request does not count as answered, so a
  client that keeps asking too soon is refused until it has waited long enough.
- **How long an answer takes.** A field `Longest request`, in milliseconds: each answer, a 429 included, is held back
  for a time drawn at random, evenly, from 0 to that value. 0 means it answers at once. Default 0, from 0 to 60,000.
- **They are debug settings like the others**: saved in `settings.json`, under the keys `fakeAccount` (`usage` or
  `seat`), `fakeIncreaseCents`, `fakeBudget`, `fakeStartAmount`, `fakeRefuseSeconds` and `fakeDelayMillis`; they take effect when the tab `Debug` is
  saved, without a restart; and without `--debug` they count for nothing.
- **A value that is not a whole number in its range** keeps the view open with the reason in red, as a wrong interval
  does in the settings; such a value in the file has the default.
- **Where they are in the view**: a group of their own, headed `Fake backend, scenario normal`, below the group of the
  three switches; each field has its unit after it, `cents`, `USD`, `s` or `ms`, and a tooltip that says what it does.
  The page has no currency sign, so the unit of the two amounts is the code.
- **The amounts are typed in dollars**, `250` or `250.00`, and shown with two decimals.
- **What is not given of them in a request stays as it is**, so the three switches can be saved alone.
- **A change of them alone does not open the source anew**: the fake backend that runs is told, and a refresh follows.
  A change of a switch opens it anew, and the amount then begins at the starting amount.
- **In the program the numbers are drawn by chance; in a test they are what the test says.**
- **The log** says the options when they change: `The fake backend is now a usage-based account growing by up to <N>
  cents, refusing within <N> s, answering within <N> ms`, or `a seat-based account, refusing within ...`.
- **What they are for**: the automatic interval can be tried against a backend that refuses requests that come too
  soon, and the row against amounts that move by a known step.

### Cost control: a monthly and a daily limit, as settings

- **The settings have a group `Cost control`** (since version 0.63), after `Request`, for an account that is billed by usage. It holds two
  limits, in US dollars:

  | Setting | What it is | Default |
  | --- | --- | --- |
  | `Monthly limit` | what may be spent in a month | none: the field is empty |
  | `Daily limit` | what may be spent in a day | worked out from the monthly limit |

- **The daily limit is worked out unless it is given.** Beside its field is a checkbox `Automatic`, on by default. On,
  the field is disabled and shows the limit the program works out; off, the limit is typed in.
- **How it is worked out**: the monthly limit divided by the number of **weekdays, Monday to Friday, of the current
  month**, rounded down to the cent. October 2026 has 22 weekdays, so a monthly limit of 250.00 gives a daily limit of
  11.36. With no monthly limit there is none to work out, and the field is empty.
- **The month is the calendar month of the machine's clock**, and the figure in the field is worked out anew when the
  settings are opened, so it changes with the month.
- **The amounts** are typed in dollars with up to two decimals, `250` or `250.00`, with no sign, from 0.01 to
  1,000,000; an empty monthly limit means there is none. Anything else keeps the view open with the reason in red.
- **A daily limit that is typed in may not be more than the monthly limit**: the view stays open with `The daily limit
  cannot be more than the monthly limit.` in red. With no monthly limit a daily limit may be any amount in the range.
- **They are saved with the other settings**, when the view is saved, under the keys `costMonthlyLimit`,
  `costDailyLimit` (both an amount, or nothing for none) and `costDailyAutomatic`.
- **The unit after each field is `USD`**: the page has no currency sign.
- **The daily limit is worked out by the program, not by the page**: when a monthly limit is typed, the page asks what
  follows from it and shows the answer, and an answer that arrives after the field was changed again is not shown.
  Until a monthly limit is an amount there is nothing to show.
- **Unchecking `Automatic` leaves the figure in the field** (since version 0.89, `new-requirements.md`; until then it brought
  back what was typed before, and an empty field when nothing was): the daily limit that was worked out stays, and can be typed
  over. With no monthly limit, and so nothing worked out, the field is empty as before. Checking `Automatic` again shows the
  worked-out limit; what was typed is kept in the settings while it is worked out, and saved with them either way.
- **A limit that is not given in a request stays as it is**, so settings saved by something that knows nothing of cost
  control leave the limits alone.
- **In the file a limit there is none of is written as `null`**, so the three keys are always there.
- **A limit in the file that is no amount in its range** is logged and counts as none; a daily limit above the monthly
  one is dropped.
- **What the limits are used for** is said in The cost control panel and, since version 0.82, in Limits, projections and the
  status of spending. (The two paragraphs that stood here until 0.81, that the program did nothing with the limits, are
  void.)
- **Since version 0.82 the tab has four more settings**, in this order after the limits:
  - **`Limit counted`**: two choices, `Server limit` and `Monthly limit`, one of which is on. `Server limit` counts the
    limit the server sends with each reading as the monthly limit, everywhere: in the row, the Month bar and the daily limit
    worked out from it; the field `Monthly limit` is then disabled, and what it holds is kept, unused, until `Monthly limit`
    is chosen again. `Monthly limit` counts the typed one; while none is typed, the server's is counted all the same, so that
    there is always a limit to count when the server sends one. The default is `Monthly limit`. Key `costLimitSource`,
    `server` or `monthly`. Its tooltip: `Which limit the row, the bars and the daily limit count: the one the server sends
    with each reading, or the monthly limit typed here.`
  - **`Hours of a working day`**: a whole number from 1 to 24, 8 by default, with `h` after it; what today's spend is projected
    over. Key `costWorkdayHours`. Its tooltip: `How long a working day is: today's spend is projected over it. The default is 8.`
  - **`Working days`**: the number of working days of the current month, which the daily limit worked out and the month's pace
    go by. Beside it a checkbox `Automatic`, on by default: on, the field is disabled and shows the days from Monday to Friday
    of the month; off, a whole number from 1 to 31 is typed, and **it holds for the month it was typed in**: when a new month
    begins it is Automatic again. **Unchecking `Automatic` leaves the number in the field** (since version 0.89): the days from
    Monday to Friday stay, and can be typed over; it is not emptied. Keys `costWorkdays` (the number, or `null`) and
    `costWorkdaysMonth` (`2026-10`, the month it is for). Its tooltip: `The working days of this month, Monday to Friday unless a number is typed; a typed number holds for this
    month only.`
  - **`Currency symbol`**: whether the figures of the cost control have the currency symbol before the amounts, **off by
    default** (until 0.81 they followed the row's `Currency symbol`). Key `costCurrency`. Its tooltip: `Puts the currency symbol
    before the amounts of the cost control.`
- **The daily limit worked out** is the monthly limit counted divided by the working days of the month, by the setting above.
- **Since version 0.89 the tab has a line `Figures`** (`docs/img/img_63.png`), after `Currency symbol`: three checkboxes, `Spent`,
  `Limit` and `Percentage`, **all on by default**, which say which columns of figures the cost control panel shows (see The cost
  control panel). Keys `costShowUsed`, `costShowLimit` and `costShowPercent`. Tooltips: `Shows what is spent, today and this
  month, in the cost control.`, `Shows the daily and the monthly limit in the cost control.` and `Shows the share of each limit
  that is spent, in the cost control.` They are previewed in the cost control panel while it is shown beside the settings, as
  the checkboxes of `Main view` are in the row, and take effect when the settings are saved.

### The cost control panel: two bars

- **A panel, `Cost control`, shows how much of the limits is spent** (since version 0.69): two bar charts, one for the day and one for the month, after the sketch the panel was drawn from: a monospaced
  list with one row per period, its name at the left, a horizontal bar of solid dark blocks for what is spent standing on
  a light dotted track for the rest, and the amount at the bar's right (a warning sign after it where the budget is
  passed). It is a panel like
  the history, log and error log: below the row, in the one panel area, replacing the one that is shown, with a button of its own in the row, the cost icon (its checkbox `Cost icon` in `Main view`; `Cost button` until version 0.79, on by default, saved under
  `showCostIcon`; its icon is two short horizontal bars, one longer than the other; its tooltips are `Show the cost control` and `Hide the cost control`). The limits stay settings, in the tab `Cost control` of the settings (see Cost control).
- **The panel is as wide as its own rows and the row need, and never as wide as the history's table** (since version 0.84;
  `docs/img/img_45.png` to `docs/img/img_47.png`: until then it took the width of the table, which the page knows only once the history
  has been shown, so that the window was wider after the history had been opened and closed). Its width is the label, the bar
  at its least width (120 pixels), the four columns of the figures with the room between them, and 8 pixels of room at the
  right; **no figure is ever cut at the window's edge** (`docs/img/img_53.png`). The window is as wide as that or as the row,
  whichever is wider.
- **The panel is as tall as its two bars need and as wide as the history's table** (until version 0.83; see above), and it cannot be dragged to another size. It has the title bar only when the setting `Title bar` is on (since version 0.79; before, like every panel, always: see Settings, The title bar). The settings give it back as they give back the others
  (see Settings). It is one of the panels the window restores at the start (`cost`, see The window is restored at start).
- **Two rows**, each: a label, a bar and, at its right, the figures. The labels are `Today` and `Month` (since version 0.79; the second was `This month`). The bar is **green for what is consumed and gray for what is left**, and nothing else is coloured but the warning sign (below), in the
  green of the figures `#1a7f37` / `#3fb950` and a gray, muted, `#8a8f98` at a third of its strength. The bar is 14 pixels tall and **its corners are square** (since version 0.79; they were rounded), those of the green part as well. **The two bars are equally wide** (since version 0.79; before, each took what its own label and figures left, so the
  bar with the shorter text was the longer one): the labels are in one column, as wide as the wider of the two, the figures in one column, as wide as the wider of the two texts with the room of the warning sign, and the bars take what is between, beginning and ending under one another; the green part is the share used, never more than the whole. **There is little room between them** (since version 0.80): the column of the labels is as wide as the wider label and no wider
  (it had a least width of five and a half characters, which left a gap after `Today` and `Month`), 6 pixels are between a label and its bar and between a bar and its figures, and 4 pixels between the two rows, where there were 8.
  **Tighter still since version 0.81**: 1 pixel between the two rows, a line height of 1.2 where it was 1.45, 2 pixels of room
  above and below the panel, the text 11 pixels where it was 12, and the bars 12 pixels tall where they were 14.
- **A bar is drawn in blocks of 10 %** (since version 0.81): a gap of 1 pixel, in the background of the window, stands after
  every tenth of the bar, nine gaps in all, through the green part and the gray part alike, so that the share can be read off
  in tenths. The green part ends where the share ends, inside a block if it does: 11 % is one block and a sliver of the next.
- **Since version 0.84 a block is 14 pixels**, 13 of colour and the gap of 1 (`docs/img/img_49.png`), **whatever the width of the bar**: a
  wider bar has more blocks, and a block is then less than 5 %. The used part still ends exactly where the share ends, inside a
  block if it does. (The rest of this rule, of twenty blocks, held in versions 0.82 and 0.83.)
- **Since version 0.82 a bar is twenty blocks of 5 %**, as in the sketch the panel was drawn from: the gap of 1 pixel stands after every
  twentieth, nineteen gaps, through both parts. **The part used is green, and the rest a light green** (the green of the figures
  at a quarter of its strength), where the rest was gray. The used part ends where the share ends, inside a block if it does.
- **The colour of the used part says the state** (since version 0.82): **green** while the bar is within its limit and on
  pace; **amber**, an orange since version 0.85 (`#ff9900` on both themes; the bright yellow `#ffff33` in 0.84, `docs/img/img_51.png`; the golden `#e3b341` in 0.83; `#bf8700` / `#d29922`, which looked light brown, in 0.82), the bars and the status circle in it (the circle with a ring of 1 pixel in the darker `#c26a00` round it, `#b8a000` in 0.84, so that it is seen on
  the background), when the projection says the limit will be passed (see Limits, projections and the
  status of spending); **red** (`#cf222e` / `#f85149`) when the limit is passed, and then the bar is full, every block red. Its
  rest takes the light form of the same colour. A bar with no limit stays gray and empty, as before.
- **The figures are four columns** (since version 0.83; in 0.82 each row had one text, as `38.89 of 45.45 · 86%` above
  `236.56 of 1,000.00 · 24%`, right-aligned as a whole, so the amounts of the two rows did not line up), shared by the two rows: the amount spent, right-aligned;
  `of`, centred; the limit, right-aligned; and `· <percent>%`, right-aligned; so that the decimal points of each column are under
  one another. A row with no limit has its amount in the first column and `· no limit set` in the rest of the line.
- **Each column can be switched off** (since version 0.89, `docs/img/img_63.png`) by the checkboxes `Spent`, `Limit` and
  `Percentage` of the tab `Cost control`; the others close up, and the panel is narrower by what is gone. **`of` is shown only
  when both the amount spent and the limit are**, as in the row; the `·` before the percentage only when something stands before
  it. A row with no limit writes `no limit set` in the place of the limit, or of the percentage when the limit is off, and
  nothing when both are off. With all three off the panel is the labels and the bars alone. The tooltips of the bars keep every
  figure.
- **The figures** are written `<used> of <limit> · <percent>%`, for example `12.40 of 25.00 · 50%`: the amount used, the limit, both in the form of the amounts of the row (two decimals, the currency symbol when `Currency symbol` is on), and the percentage, a
  whole number, rounded to the nearer and upwards at half. The amount is the one of the figures, so it is `$` for US dollars with the symbol on and the plain number with it off, as the row writes it.
- **The monthly bar**: the amount used of the newest reading, against the monthly limit of the cost control. **The daily bar**: the amount spent today against the daily limit in force this month (the typed one, or the one worked out from the monthly limit, see Cost control).
  What is spent today is the amount used of the newest reading less the amount of **the last reading before today** (today being the local date) in the history in use, **of the same month**; when there is none, the first reading of today, so that a
  program started today shows what was spent since it started (and a first day of a month starts at its first reading, as the amount used begins again then). A fall of the amount (a new month, or a correction) is no spending: it is 0.00.
- **No limit.** A bar whose limit is not set is **gray and empty** with the text `<used> · no limit set` (for the day, the amount spent today; for the month, the amount used), and no percentage.
- **Over the limit.** The bar is full and red (since version 0.82; until then full green with a **warning sign** after the
  figures, which is gone), and the text says the true percentage (`112%`). The figures stay in the colour of the text.
- **The currency symbol** of the figures is the cost control's own setting since version 0.82, off by default (see Cost
  control), and no longer the row's.
- **No reading yet.** With no reading to say what is used, both bars are empty and gray, and a note under them says `No usage has been read yet.` Failed refreshes change nothing: the last reading counts, and the text is not dimmed.
- **It keeps up with the readings**, like the history: the bars are drawn again when a new reading comes, without pressing anything. The backend works the figures out and writes the texts (`GET /api/cost`, see `docs/api.md`); the page only draws them.
- **The tooltips**: each bar says `<Label>: <used> of <limit> (<percent>%)`, or `<Label>: <used>, no limit set`.

### The words for what went wrong

- **Whatever went wrong is said in words of the program's own** (since version 0.74), wherever a message or a line of the log names a reason: `<reason>` and `<detail>` in this document are these words. Until then they were the text the runtime of the language or the operating system gave, which differs from one system to the next and is in the system's language, and for a fault nobody expected the name of its kind.
- **The words are a fixed set.** For a file: `the file or its folder is not there`, `access is denied`, `the file could not be read or written`. For a connection: `the host is not known`, `the connection was refused`, `the connection was closed`, `the secure connection could not be made`, `there was no answer in time`, `the address is in use or cannot be used`, and `the connection failed` for a request that failed in a way that is none of these. For a program that could not be started: `the file or its folder is not there`, `access is denied`, `it is not a program this system can run`, `the system did not start it`. For anything else that was read or written: `input or output failed`. And `interrupted`, and `an unexpected fault` for what nobody expected.
- **A failure the program sees for itself keeps the words it has**: `the settings file is not a JSON object`, `the clipboard did not answer`, `the file manager could not be started: <reason>`, and every message of a refresh.
- **A failure is one line of the log**: the message, a colon, and the words, as `Could not save settings to <path>: access is denied`. No stack trace is written to the log file.
- **In debug mode the console may also show where in the program a failure came from**, in whatever form the language of the version has for that, after the line of the failure and masked like every line, as a help for whoever works on the program. In the Java version it is the stack trace. The log file is the same with and without debug mode.
- **Nothing of what a fault says reaches the window or the file**, so nothing a library puts into the text of a fault, a path or a part of a response, can leak that way.

### The panel buttons while the settings are shown

- **While the settings are shown, all four panel buttons are in the row** (since version 0.79), whatever their switches say: the
  log button, the history button, the error log button and the cost button. So a panel whose button is switched off can still be
  reached, through the settings. Before, the buttons followed their checkboxes while the settings were shown, and one that was
  switched off was not there.
- **A button that is switched off is dimmed**: drawn at 40 percent of its strength, in its place and at its size, so that it is
  seen that it is switched off and is shown all the same. It follows its checkbox as that stands in the view at the moment: a
  click on the checkbox dims the button or brings it back to full strength at once, as a preview, and the row is neither wider
  nor narrower for it.
- **It can be clicked like any other.** A click leaves the settings, which are saved as when they are left by any button of the
  row (see Settings), and shows the panel. The button is then that of the open panel: blue and bigger, not dimmed, and it stays
  until the panel is closed, as before; then it goes. With `Several views at once` on (since version 0.81) a click opens or
  closes its view and leaves the settings open, unsaved, beside it.
- **Its tooltip says that it will go**: the usual text of the button, and a second line under it, `Switched off: this button
  goes when the settings are closed.` A button that is switched on has its usual tooltip alone.
- **When the settings are left, the buttons that are switched off go**, as before, and the window follows the narrower row.
- **A panel whose button is switched off when the settings are left is not given back** (since version 0.80, which takes back
  the rule of version 0.79, that it was given back whatever its switch said). Leaving the settings by `Save`, by the gear or by
  the button of another panel saves them, and a view whose button the saved settings switch off is then closed: the row alone is
  shown, or, with `Several views at once`, the other views that were open. So unchecking the icon of the view that is open
  closes that view. `Cancel` saves nothing, so it brings back what was shown, as it was. A panel that was reached through a dimmed
  button, and from which the gear was pressed, is therefore not shown again unless its switch was switched on meanwhile.
- **Only the four panel buttons.** The refresh button and the figures of the row follow their checkboxes while the settings
  are shown, as before: one that is switched off is not there. The gear is always there.

### A button in the history that hides and shows the zero usage lines

- **The usage history has a small button that hides the lines with no change, and shows them again** (since version 0.79), so
  that a history with many unchanged readings can be folded to the readings that changed with one click, without the settings.
- **Where it is.** In the line at the top of the history, after the two zoom buttons. It is an icon with no text, drawn like the
  icons of the row: **a funnel** (since version 0.81; until then two short arrows that pointed at a line between them, from above
  and from below, which did not say what the button is for), its outline only, 13 pixels like the zoom.
- **It hides the failed lines as well** (since version 0.81): while it hides the zero usage lines the lines of failed requests
  are hidden too. A failed line is hidden when the button hides, or when the checkbox `Failed lines` of `History view` is off;
  it is shown when neither does. The key and the setting stay `historyZeroLines`: the checkbox `Zero usage lines` and the
  button are one switch, and its tooltip in the settings says that it hides the failed lines too.
- **It is the setting `Zero usage lines`, and nothing else**: a click switches that setting, and what it hides and shows is what
  the setting hides and shows (see Hiding lines): the zero usage lines, the startup lines among them. The time since the previous
  reading is then that against the previous line that is shown, as before, so the lines that are shown have a `Δ time` each, the
  oldest alone excepted, and the note above the table says how many lines are hidden.
- **A click takes effect at once and is saved at once**, in `settings.json` under the key the setting has, `historyZeroLines`,
  with no `Save` to press. The checkbox `Zero usage lines` of the tab `History view` shows the same value the next time the
  settings are opened, and a change made there is seen in the button.
- **The button says which state it is in.** While the zero usage lines are hidden it is blue, the blue of the notes, and its
  tooltip is `Show the zero usage lines and the failed lines`; while they are shown it is gray like the zoom buttons and its
  tooltip is `Hide the zero usage lines and the failed lines` (since version 0.81; until then without `and the failed lines`).
- **The note counts both** (since version 0.81): `Showing 320 of 835 lines: 499 zero usage and 16 failed hidden.`, a kind that
  has none left out, as `Showing 336 of 835 lines: 499 zero usage hidden.`.
- **After a click the history stands at the top**, at its newest line, whatever it was scrolled to before.
- **When it cannot be saved** it holds for the run, also when the history is closed and shown again, and a red line in the history says `The history settings could not be saved:
  <reason>`, until a change is saved. The settings view shows what is saved, which is then not what the button says.
- **The button is shown when the history has its first answer**, which says what is saved, like the two choices of the log.
- **Only the history has it.** The log and the error log have no such button.

### A button that selects every checkbox of `History view`, or none

- **The group `History view` of the settings has the button that `Main view` has** (since version 0.79; see A button that
  selects every element of `Main view`, or none): in the line of its heading, at the right of the heading, the same icon, a small
  square with a tick in it, with no border but while the mouse is on it. Its tooltip is `Select every checkbox, or none`.
- **A click switches all nine checkboxes of the tab**: `Date`, `Zero usage lines`, `Failed lines`, and the six of the columns,
  `time`, `used`, `limit`, `Δ used`, `Δ time` and `Cur.` When every one of them is on, all are switched off; otherwise all are
  switched on.
- **It is a click like any other in `History view`**: nothing is previewed, and it is saved when the view is saved, by `Save` or
  by leaving through a button of the row; `Cancel` leaves the settings as they were.
- **With every column off the history says that no column is switched on** (since version 0.80, see The lines of the usage
  history are always shown), as when the six are switched off one by one; the button adds no rule of its own.

### A tooltip for every control of the settings

- **Every control of the settings view has a tooltip** (since version 0.79), on every tab: each checkbox, each field, each
  dropdown and each button. It says what the control is for, and for a control that takes a value, which values it takes. Many
  had one before; this says that none is without.
- **The tooltip is on the control and on its text alike**, so that it shows wherever the mouse is in the line of the control.
- **A value range is said in the tooltip** where there is one: the least and the most, the unit, and the default where it helps.
- **The texts that are new**, by tab (those that were there are kept, in `Main view` and elsewhere):

  | Tab | Control | Tooltip |
  | --- | --- | --- |
  | `General` | `Interval` | `The time between two usage requests: a whole number of seconds from 5 to 3600. The default is 60.` |
  | `General` | `Restore the window at start` | `Opens the window at the next start as it was when the program was stopped: the same panel, the same size and the same place.` |
  | `General` | `Log the response` | `Writes the JSON of every usage response to the log. Anything shaped like a credential is masked.` |
  | `Main view` | `Cost icon` | `The icon that shows the cost control, a bar for the day and one for the month, below the row, and hides it again.` |
  | `History view` | `Date` | `Shows the date before the time of each line.` |
  | `History view` | `Zero usage lines` | `Shows the lines whose amount used is that of the reading before. Off, they are left out, and the lines of failed requests with them.` |
  | `History view` | `Failed lines` | `Shows the lines of the usage requests that failed. Off, they are left out.` |
  | `History view` | `time` | `The column with the time of the reading.` |
  | `History view` | `used` | `The column with the amount used.` |
  | `History view` | `limit` | `The column with the budget.` |
  | `History view` | `Δ used` | `The column with the change in the amount used since the last earlier reading: a rise with a plus, a fall with a minus. It is empty when nothing changed.` |
  | `History view` | `Δ time` | `The column with the seconds since the previous line that is shown.` |
  | `History view` | `Cur.` | `The column with the currency: the dollar sign for US dollars, the code for any other.` |
  | `Cost control` | `Monthly limit` | `What may be spent in a month, in US dollars: from 0.01 to 1,000,000, with up to two decimals. Empty for no limit.` |
  | `Cost control` | `Daily limit` | `What may be spent in a day, in US dollars: from 0.01 to 1,000,000, with up to two decimals, and not more than the monthly limit.` |
  | `Cost control` | `Automatic` | `Works the daily limit out: the monthly limit divided by the weekdays, Monday to Friday, of the current month.` |

- **The tab `Debug`**: each field of the fake backend says its range and its unit in its tooltip, as the passage on it gives
  them: `Largest increase`, 0 to 100,000 cents; `Budget`, 0.01 to 1,000,000 USD; `Starting amount`, 0 to the budget; `Refuse
  within`, 0 to 3,600 s; `Longest request`, 0 to 60,000 ms.
- **The tooltip of `Title bar`** is the one said under The title bar, and those of `Stay on top`, of `Automatic` beside the
  interval, of `Save` and `Cancel`, of the two select buttons and of the links and copy buttons of `About` are as they were.
- **The titles of the tabs have none**: they are not controls of a setting, and say what they are.

### The tab `About` can be dragged wider

- **While the tab `About` is shown the window can be made wider by hand** (since version 0.79), by dragging its left or its right
  edge, so that the paths, which are long, can be read in one line each. It cannot be made narrower than the least width of the
  settings, and its height cannot be dragged: the window is as tall as the row and the tab need at the width it has, so it gets
  lower as the paths need fewer lines.
- **A path is broken over lines only when the window is too narrow for it**, as before; in a window that is wide enough each path
  is on one line.
- **The width is for this tab, and is not remembered.** When another tab is chosen the window goes back to the least width of
  the settings, and the tab `About` begins at that width each time it is shown, also when the settings open on it. (The window
  restored at start has the size that was stored, as for any panel.)
- **The other tabs cannot be dragged**, as before.
- **The title bar is there**, as on every tab of the settings, so there is an edge to drag whatever the setting `Title bar` says.

### The settings have their own width, whatever was shown before

- **The fault that was** (before version 0.79). With the log shown, which is three times as wide as the row, a click on the gear
  showed the settings in a window that kept the width of the log: far wider than the settings need. The cause was in the page: the
  strip of the tab titles was measured as it lay, and a strip is as wide as the view it is in when the view is the wider, so a
  window that was wide asked to stay as wide, and never came back to the settings' own width however often they were opened.
- **Nothing the settings are measured by follows the width the window has.** The strip of the titles is measured by its titles,
  the form by its content and the buttons by themselves, each let go of the window for the moment of the measuring.
- **When the settings are shown the window has the width the settings have**, the least width of the view or the width the row
  has by itself, whichever is more (see Settings), whatever panel was shown before and whatever width that panel had, also one
  the person had dragged it to. The width of a panel is that panel's own and is not carried to the next.
- **The panel that is given back** when the settings are left has the size it had before, as Settings says.

### The usage history has no horizontal scrollbar

- **The fault that was** (before version 0.79; seen on macOS). With both change columns shown and the currency switched off the
  table was wider than the window: the history had a horizontal scrollbar, and the beginning of the first column, the title `time`
  and the first digit of each time, was out of sight at the left. The cause was not the system: the columns had their widths by
  their place and their number, so with five columns the last was 5 characters wide, the width of the currency, whatever it held,
  and `Δ time` with its values stuck out of it at the right. It came with version 0.64, when every column could be switched.
- **A column has its width by what it is**, whichever columns are shown beside it: the time 8 characters, or 19 with the date,
  `used` and `limit` 10, `Δ used` 9, `Δ time` 8 and `Cur.` 5. The backend says what each column is, and the page looks at no
  title. The time and its title are at the left of their column and every other column and its title at the right, also when the
  time is switched off and a figure is the first column.
- **The red of `failed` is on the amount used**, wherever that column is, not on the second cell.
- **The page asks for more when the list lacks room.** The width asked for is worked out from the table and from the width of a
  scrollbar, each measured apart. Where that sum falls short, as where the scrollbar of the list takes more room than the one
  that was measured, the list can be scrolled sideways by what it lacks, and the page then asks for that much more, once the
  window has the width that was asked for, and keeps to it while the table is the same. It is never more than 48 pixels, and
  nothing is ever taken away again.
- **The window is as wide as the table needs**, as The usage history panel says, on every system the version is made for, with
  every column on, the date and both change columns among them, and at every size of the text of the lists: every column is
  whole, the first from its first character, and there is no horizontal scrollbar. The one case with one stays the table that is
  wider than the largest window there is.
- **The margin for a scrollbar that lies over the content** (macOS) is part of the width the window is given, not taken from the
  room of the columns.

### The log can be reset, and the reset undone

- **The log panel has two more buttons** (since version 0.79), in the line at its top, after the two zoom buttons, each an icon
  with no text: one that resets the log, and one that takes the last reset back.
- **Reset.** The log file is renamed and a new, empty one is begun. The file that was `java-aip-usage.log` becomes
  `java-aip-usage.<yyyy-MM-dd_HH-mm-ss>.log`, with the local date and time of the reset, for example
  `java-aip-usage.2026-10-13_14-30-05.log`, in the same directory; such a file is an **old log**. The program goes on logging to
  a new `java-aip-usage.log`, whose first line says `The log was reset: the lines before are in <the full path of the old log>`, at
  the information level, and the panel shows the new file at once, so it is nearly empty.
- **Merge, the undo.** The current log is joined with the **newest old log**, the one whose name has the latest date and time:
  the lines of the old log come first and the lines of the current one after them, as if the reset had not been, the result is
  `java-aip-usage.log`, and **the old log is deleted**. The line `The log was merged with <the full path of the old log>` is written
  at the end, at the information level. With several old logs a second click joins the next older one in the same way.
- **The button of the merge is disabled while there is no old log**, and the program says with the log whether there is one.
- **The tooltips** are `Reset the log: the file is kept under a name with the date and time, and a new one is started` and `Undo
  the reset: put the newest old log back in front of this one`; disabled, the second says `There is no old log`.
- **There is no question to answer**: a click does it. Nothing is lost by a reset, and the merge is its undo.
- **Nothing of the log is lost on the way.** A line the program writes while a reset or a merge is under way is in the new file
  or in the joined one, never in neither, and the merged file is put in place in one step, like the other files the program
  replaces (see A file is never seen half written).
- **The page names no file**: it asks for the log to be reset or merged, and the program uses the names it knows. The requests
  are documented with the API (`docs/api.md`), give no path away, and are logged as what a person asked for.
- **When it cannot be done**, the file cannot be renamed, an old log of that second is there already, or the two cannot be
  joined, nothing is changed and a red line in the panel says `The log could not be reset: <reason>` or `The log could not be
  merged: <reason>`.
- **The start of a run** is marked in the panel wherever its line is: after a reset the line of the run is in the old log, so
  the new file has no start line until the next start, and after a merge it is shown again.
- **An old log that cannot be deleted after a merge** is said in the log as a warning, with its name, since its lines are in the log by then and the next merge would put them there a second time. The merge itself has been done.
- **Only a file of exactly the form of the name is an old log**, `java-aip-usage.` followed by a date and time in that form and `.log`: no other file beside the log is ever joined or deleted, whatever it is called.
- **A run that logs to the console alone**, because the log file could not be opened, has nothing to reset: the reset button says `The log could not be reset: the log is not written to a file`, and the merge is disabled.
- **Only the log.** The usage history has no reset; its file is still never rotated or deleted by the program. The old logs
  are kept out of version control like the log, and the program never deletes one but by a merge.
- **The About view** names the path of the current log, as before, and nothing of the old ones.

### The name of the program, as the system shows it

- **Wherever the system shows the name of the program, it is `aip usage`** (since version 0.79), the title of the window without
  the version: on macOS in the menu bar beside the apple, in the entries of that menu, `Hide aip usage` and `Quit aip usage`, in
  the Dock and in the list of running programs; on Windows in the taskbar and where the system names a program. It is never
  the name of the runtime the version is built on, `java` for one, nor the name of a class of the program.
- **The icon goes with the name**: the Dock and the list of running programs show the gauge (see An icon of the program's own),
  not the icon the system gives a program that has none.
- **`Quit aip usage` ends the program as closing the window does**, with the same lines in the log.
- **The name in the log is as it was**, `java-aip-usage`, and so are the names of the files.

### Several views at once

- **More than one view can be shown at the same time** (since version 0.80), the cost control and the usage history for one,
  when the checkbox `Several views at once` is on. It is in the tab `General`, in the group `Window`, after `Restore the window
  at start`, **off by default**, saved under the key `severalViews`, and takes effect when the settings are saved. Its tooltip is
  `Lets more than one view be shown at the same time: an icon opens its view and leaves the others open. Off, opening a view
  closes the one that is shown.`
- **Off, nothing is changed**: showing a view replaces the one that is shown, as before.
- **On, an icon opens its view and leaves the others open.** A press on the icon of a view that is not shown shows it, and the
  icon turns blue and bigger, as usual; a view that is open already stays open, and its icon stays blue. A press on a blue icon
  closes that view, and that view alone, and its icon is gray again. So any number of the four can be blue at once, where before
  no more than one was.
- **Which views.** The log, the usage history, the error log, the cost control, and **the settings with them** (since version
  0.81; in version 0.80 the settings were always shown alone, in the place of every view that was open, and gave them back when
  they were left). With `Several views at once` on, the gear is an icon like the others: a press shows the settings and leaves
  every view open, a press on the blue gear leaves the settings, saving them, and closes them alone. **`Save` and `Cancel`
  close the settings alone**: the other views stay open as they are, at their sizes. A press on the icon of a view while the
  settings are shown opens or closes that view and leaves the settings open. A view whose icon the saved settings switch off is
  closed when they are saved, as Settings says. With `Several views at once` off, the settings take the place of the view that
  is shown and give it back when they are left, as before.
- **The views are stacked below the row in the order of their icons**, whatever order they were opened in: the cost control,
  the usage history, the log, the error log, from the top down (since version 0.81; in version 0.80 the log, the usage history,
  the error log and the cost control). **The cost control is always first**, right below the row, whatever other views are shown,
  the settings among them (since version 0.86). **The settings follow it**, above the other views: right below the row when the cost
  control is not shown (until version 0.85 the settings were at the top, above the cost control). With `Several views at once` off
  nothing changes: the settings still take the place of the view that is shown. A view that is opened takes its place among those that are
  shown, and the others move to make room; a view that is closed leaves no gap.
- **Each view has the height it has alone.** The usage history and the log open at their remembered heights (see Remembered
  heights), the error log at ten times the row's height, the cost control is as tall as its two bars need, and the settings as
  tall as their tab. The window is as
  tall as the row, its message lines and all of them together, and never taller than the program makes a window (1,600 pixels):
  when the views would need more, the lowest list is given what is left, and no less than three times the row's height.
  **Since version 0.82 the window is never taller than the screen it is on**, and the lists share what there is: see Views that
  do not fit on the screen. (In version 0.81, with the cost control, the history, the log and the error log open, the four views were taller than
  the screen: the log ran off its bottom edge, and the error log, the lowest, had no room at all.)
- **The window is as wide as the widest of the open views needs**: three times the row with the log, one and a half times with
  the error log, the table with the usage history, its own rows with the cost control (since 0.84; the history's table or
  its bars until then), and the least width of the settings with the settings.
- **Leaving the settings closes the settings alone** (a fault of 0.83, `docs/img/img_50.png`, put right in 0.84): a view beside them
  whose icon is checked stays open, the cost control as much as any other. Every view is as
  wide as the window; a list that is wider than it has to be shows its lines as it does in a window dragged wider.
- **What may be dragged.** The height, when any of the three lists is open: dragging it changes the height of **the lowest
  list**, and the views above keep theirs. The width, when the log or the error log is among the open views, or the tab `About` is shown. With the cost
  control alone, and with the settings alone on another tab than `About`, nothing, as before.
- **A dragged height is remembered** for the usage history and for the log as before, when that list is the lowest one and it
  is its height that was dragged; the height of the error log is not remembered, with other views or alone.
- **The title bar** is there whenever something of the window can be dragged, which is whenever a list is among the open views;
  with the cost control alone and the title bar switched off there is none, as before.
- **Each view keeps up by itself**, as it does alone: the log and the error log with every poll, the usage history when a row
  is added, the cost control with every poll. Each has its own line at its top (whose icons stand at its right end since version
  0.82, see A zoom for the lists), with the buttons that belong to it (the zoom
  for each list, the level and the date and the two buttons of its file for the log, the button of the zero usage lines for the
  history), its own note and its own red line; the size of the text is still one for all three lists.
- **The window restored at start** has every view that was open (see The window is restored at start).
- **Switched off while several views are shown**, by saving the settings, the first of them in the order of the icons stays
  and the others are closed; the settings close, being saved.
- **A line between the views** (since version 0.81): see A line between the row and the views.
- **The page says which views are open** to its host, in the order of the icons, with what may be dragged and the size it
  needs for all of them (`docs/api.md`, Window host contract).

### A sturdier typeface for the lists

- **The fault that was** (before version 0.80, on Windows). The lists, the usage history, the log and the error log, were set in
  the system's fixed-width font, and on Windows the page was given Courier New for that, a thin typeface: the lines were hard
  to read, and looked lighter than the regular weight they are.
- **The lists name sturdy fixed-width typefaces, and the system's own last**: `ui-monospace`, then `Cascadia Mono`, `SF Mono`,
  `Menlo` and `Consolas`, and `monospace` when none of them is there. So Windows shows Cascadia Mono or Consolas and macOS SF
  Mono or Menlo, and none is fetched: a typeface that is not on the machine is passed over.
- **Where the window does not take these names**, and the lists are still thin, **the program brings a typeface of its own**:
  a fixed-width one under an open licence, kept among the program's files with its licence, served to the page by the program
  like the page's other files, and named first. It is still not fetched from anywhere. Whether a version needs this is seen in
  its window, and is said in that version's section once it is known.
- **Since version 0.81 the program brings Cascadia Mono**, as the rule above has it: the window on macOS did not take the names
  and showed Courier (in version 0.80). Cascadia Mono, the typeface Windows showed, at
  its regular weight, is among the program's files with its licence (the SIL Open Font License), served to the page by the
  program and named first, so the lists, the cost control and every other place set in the lists' typeface look the same on
  Windows and on macOS. The names after it stay, for a page that cannot have it.
- **The weight stays regular**, and the header of a table and the blue notes bold; the size, the line height and the widths of
  the history's columns, which are in characters, are as they were.
- **The copy the history's table is measured on has the same typefaces**, so that the window is as wide as the table is.

### The first reading of a day is underlined in the history

- **Since version 0.91 the line is 3 pixels and blue**, in the blue of the notes, `#0969da` on the light theme and `#58a6ff` on
  the dark one, where it was 1 pixel of light gray; the rest of this section holds.
- **Since version 0.89 the first reading of each day has a light gray line under it** (`docs/img/img_62.png`): a line of 1 pixel
  across the whole row of the table, in the gray of the grid, `#d0d7de` on the light theme and `#3d444d` on the dark one. As the
  history is newest first, the first reading of a day is the lowest line of that day, and the line stands between two days.
- **With lines hidden** (zero usage, failed) **the line goes under the earliest line of the day that is shown**, so that the
  days are told apart in what is shown; a day with no line shown has none. The oldest day of the 1,000 lines shown is marked as
  well, though older lines of it may not be shown.
- **The day is the local date of the reading.** The backend says of each line whether it is the first of its day among those
  shown (`firstOfDay`, `docs/api.md`); the page draws the line.
- **A line that is both** the start of a run and the first of a day has both marks.

### A tooltip for each column title of the usage history

- **Each title of the history's table has a tooltip** (since version 0.80), which says what the column is:

  | Title | Tooltip |
  | --- | --- |
  | `time`, `date time` | `When the usage was read`, and with the date `The date and time at which the usage was read` |
  | `used` | `The amount used at that reading` |
  | `limit` | `The budget at that reading` |
  | `Δ used` | `The change in the amount used since the last earlier reading: + for a rise, - for a fall` |
  | `Δ time` | `The seconds since the previous line that is shown` |
  | `Cur.` | `The currency of the amounts` |

- **The backend writes them** and sends them with the titles; the page puts them on the titles as it is given them.
- The lines keep the tooltips they have: `The program started here` on the first line of a run, and none on the others.

### The paths in the log are links

- **The lines of a reset and of a merge of the log name the old log by its full path** (since version 0.80; before, by its name
  alone): `The log was reset: the lines before are in <the full path of the old log>` and `The log was merged with <the full path
  of the old log>`.
- **A path in a line of the log panel is a link when it is the path of a file of the program's own**: the settings file, the log
  file, an old log, and the two usage history files, the real one and the one of the fake backend. The link is the whole path,
  in the blue of the notes and underlined, like the paths of the About view; the rest of the line is as it was.
- **A click opens the folder the file is in, with the file marked**, in the Explorer on Windows and the Finder on macOS, and the
  folder alone when the file is no longer there, as for an old log that was merged since: the same as a click on a path of the
  About view (see The paths of the About view are links). The file itself is not opened.
- **Nothing else is a link**, whatever a line holds: not a path the program does not know, not a web address. A line of the log
  can therefore never make the program open another folder than those its own files are in.
- **The backend says which parts of a line are such paths**, and the page marks them: the page looks for no path itself. For a
  click the page names the file by what the backend called it, never by a path, and the program opens the path it knows; a
  name it does not know is refused.
- **Its tooltip is `Open the folder of this file`.** The text of the log can still not be selected.
- **When the folder cannot be opened** a red line in the log panel says `The folder could not be opened: <reason>`.
- **In the log file nothing changes** but the two lines above: the file is text, and the links are the panel's.

### The lines of the usage history are always shown

- **The fault that was** (before version 0.80). With lines hidden, the note above the table said how many lines were shown,
  `Showing 35 of 48 lines: 12 zero usage and 1 failed hidden.`, and under it was nothing: the lines were not drawn.
- **What it was.** Every column of the history was switched off: the button in the heading of `History view`, which version
  0.79 brought, switches all nine checkboxes off when all are on, the six of the columns among them, and with them the two
  kinds of lines, which is the picture exactly. The lines were there, each with nothing in it, and the note counted them.
- **With no column switched on the history says so, in the place of its table**: `No column of the history is switched on.
  Switch one on in the settings, under History view.`, in the blue of the notes that tell what is not shown, and bold. It
  does not say how many lines are shown, and no lines are drawn. With one column on it is a table again. (This takes the
  place of the rule of version 0.79, that with every column off the history has no table to show and nothing more is said.)
- **A history that is not there, or has no rows**, keeps its own note, whatever columns are on.
- **Whenever the note says that lines are shown, they are shown**: after the history was opened in the place of another view,
  the cost control or the settings among them, after the button of the zero usage lines was pressed, and after the settings
  were left. A view that has no lines of its own, the cost control, leaves the list of the next view as that view needs it.
- **A view that is opened while another is still being read is read as soon as that read has ended**, the cost control
  included: until version 0.79 a view opened while the cost control was being read waited for the next poll, a second, with
  nothing under its icon.

### A line between the row and the views

- **A line separates the row from the view below it, and each view from the next** (since version 0.81; in 0.80 the zoom buttons and the header of the view stood right under the row, with nothing between them):
  1 pixel, solid, in a middle gray on the light theme and a dark gray on the dark one, as wide as the window. It is there with
  several views, the settings counted among them; there is none below the lowest view, and none with no view shown.
- **With one view there is no line** (since version 0.86; `docs/img/img_54.png`): one view alone, or the settings alone, stands right
  below the row with nothing between. Until version 0.85 the line was there with one view as with several. With two views or more
  every line is as before, the one below the row included.
- **It is the top edge of the view below it**, which is a pixel lower for it: the sizes the page asks for, the heights the views
  have alone and the remembered heights are as they were. With one view the pixel of the line is the view's own.

### The folders of the log and of the usage history

- **Since version 0.90 the log and the usage history have a folder each again** (`docs/img/img_66.png`), each in its own tab: the
  log's in `Log`, the history's in `History` (see The settings rearranged). What follows of one folder held in versions 0.87 to
  0.89; the rest of this section holds for each of the two folders.
- **The folder of the log and of the usage history can be chosen** (since version 0.81; **one folder for both since version
  0.87**, `docs/img/img_56.png`, where until then each had its own). By default it is the program's folder, the working directory,
  as before. It holds the log and its old logs, the usage history and, in debug mode, the history of invented readings
  (`java-aip-usage.fake.csv`).
- **Where.** A group `Files` in the tab `General`, after `Window`, with one line, `Log and history folder` (until version 0.86 two,
  `Log folder` and `History folder`): the full path of the folder as text (since version 0.84 only once another folder is chosen: while the folder is the
  default its line says `Program folder` in gray, with no path, `docs/img/img_48.png`), a button `Choose…` that opens the system's folder picker at that folder, and a
  button `Default`, which puts back the program's folder. Nothing is typed. Saved under the key `dataFolder`, as a full path; a
  key that is not there, or empty, is the program's folder.
- **A settings file of version 0.86 or before**, which has the two keys `logFolder` and `historyFolder` and no `dataFolder`: the
  folder of the usage history is the one folder, and wins over the log's. At the first start the log and its old logs are moved
  from the log's folder into it, before the log is opened, each unless a file of its name is there already, as `Save` moves them;
  the log says so, at the info level, once it is open. The file is then saved with `dataFolder` and without the two keys. A move
  that fails puts back what it moved, is logged as a warning, and is tried again at the next start; the log is opened in the
  one folder all the same. When the two keys name the same folder nothing is moved.
- **Its tooltips**: the line `The folder the log, its old logs and the usage history are kept in. The default is the program's
  folder.`; `Choose…` `Opens a window to choose the folder.`; `Default` `Puts back the program's folder.`
- **It takes effect at once, on `Save`**: the program switches to the new folder there and then, and the log, the error log
  and the usage history read from it; the About view shows the new paths.
- **The files are moved.** On `Save` the log and its old logs are moved into the new log folder, and the usage history (and the
  history of invented readings) into the new history folder, so that nothing is lost and nothing is left twice. A file of the
  same name that is in the new folder already is not overwritten: it is used, the one of the old folder is left where it is,
  and the log says so. The program goes on logging to the moved log.
- **A folder that cannot be used is refused**: one that is not there, or cannot be written to. `Save` is then refused with a
  red line under the group `Files`, `The folder cannot be written: <reason>`, nothing is moved, and the folder in use stays. A move that
  fails half way puts back what was moved, and is refused in the same way.
- **At a start**, a folder that is no longer there, or cannot be written to, is not used: the program uses its own folder for
  that file, and the log says so with a warning. The setting stays as it is, so that the folder is used again once it is back.
- **The log** says at the info level which folder is now used for what, and the full path of every file that was moved.
- **macOS and the others**: the folder picker is the system's own on each; on macOS it is built on Windows and not checked there.

### Limits, projections and the status of spending

- **Since version 0.82 the program compares what is spent with the limits**, for the day and for the month, and says how it
  stands in three places: the colour of the bars of the cost control, the red of the row's figures, and the status circle.
- **The limit counted** for the month is the server's limit or the typed monthly limit, by `Limit counted` (see Cost control);
  the daily limit is the typed one, or the one worked out from the monthly limit counted and the working days. The row's
  `Budget` and percentage show the limit counted, where they showed the server's until 0.81. **Their tooltips say `monthly limit`**
  (since version 0.83): the percentage `<percent>% of the monthly limit spent`, the limit `Monthly limit, in USD` when the typed
  one is counted and `Monthly limit sent by the server, in USD` when the server's is.
- **What is spent today, and in the month**, is as The cost control panel says.
- **The day's projection.** The working day begins at **the first reading of today whose amount used rose** over the one
  before it. From then, what is spent today is projected over the hours of a working day (8 by default): spent today ÷ hours
  since the day began × hours of a working day. No projection is made in the first 15 minutes, nor when the day has had no
  rise; once the working day is over, the projection is what was spent. On a Saturday or a Sunday there is no daily limit,
  and so no projection.
- **The month's pace.** The month is ahead of its pace when the amount used is more than the monthly limit counted × the
  working days begun so far ÷ the working days of the month, today counted as begun. Working days are Monday to Friday, or
  the typed number, in which case the days begun are the weekdays begun, at most the typed number.
- **The state of each**, the day and the month: **red** when what is spent is more than its limit; **amber** when not, but
  the day's projection is more than the daily limit, or the month is ahead of its pace; **green** otherwise; **gray** when it
  has no limit.
- **The status circle blinks** (since version 0.88; decided against in version 0.83, since a dot that fades in and
  out has the window repaint all the time, and now asked for in `new-requirements.md`): while it is green, orange or red it
  fades in and out, a slow breath of **2 seconds**, its opacity eased from 100% to 30% and back. **Gray does not blink**: with
  no limit there is nothing to watch.
  - **The rhythm is the same for every colour**; a change of state changes the colour at once, and the fade goes on where it was.
  - **It stops while the window cannot be seen**: when it is minimized the dot stands still, so that
    nothing is repainted, and blinks again once the window is shown.
  - **`Blinks` off** (beside the checkbox `Status` in `Main view`, on by default) has it stand still in its colour, as it did
    until 0.87. The settings preview shows the change at once. `Blinks` can be set while `Status` is off, as `Currency symbol` can while the
    amount used is hidden.
- **The status circle** (the element `Status`, the first of the row, shown by default) is a filled dot, 10 pixels: red when
  either is red, amber when either is amber and none red, green when both are green, gray when neither has a limit (one
  without a limit counts as green beside the other). Its tooltip says each, in two lines, then the figures:
  - `Today: within the limit` / `Today: on course to pass the daily limit: 4.37 spent, about 9.80 by the end of the day, of
    6.81` / `Today: over the daily limit: 7.20 of 6.81` / `Today: no daily limit is set`;
  - `Month: within the limit and on pace` / `Month: ahead of pace: 120.00 spent, 95.45 by now, of 150.00` / `Month: over the
    monthly limit: 202.04 of 150.00` / `Month: no limit is set`.
  With no limit at all its tooltip is `No limits are set: set them in the settings, under Cost control.`
- **The backend works all of it out** and sends the states, the projection and the texts with the status (`docs/api.md`);
  the page draws them.

### Views that do not fit on the screen

- **The window is never taller than the screen it is on** (since version 0.82), less the frame of its title bar. When the views
  shown need more, **the lists share the height there is**: the views that are no list (the cost control, the settings) keep
  theirs, and each list is given an equal part of the rest, never less than five of its lines. If even that is more than the
  screen, the window is as tall as the screen and the lowest list is cut at the bottom.
- **A list that is given less than its height alone** keeps the height it has alone remembered as it was: the sharing is not
  a height the person gave it.
- **Dragging the window** taller or shorter still changes the lowest list alone.

### The settings rearranged

- **Since version 0.90 the tabs are** `General`, `Main`, `History`, `Log`, `Cost control`, `About` and `Debug` (the last in debug
  mode only). `Main view` and `History view` lose the word `view` (`docs/img/img_65.png`), in their tab titles and in the headings of
  their groups, which are `Main` and `History`. `Log` is new, after `History`.
- **`General`** keeps the group `Window`, with `Show where the window is` added (see The window shows where it is when it comes
  to the front). The group `Request` goes to `Cost control`, the group `Files` and the group `Log` go to the tab `Log` and the
  tab `History` (`docs/img/img_66.png`). Below `Window` is the path of the settings file, as the About view had it: a link that
  opens its folder, with the button that copies it (see The paths of the About view are links, with a button to copy each).
- **`Main`** has the elements of the row as before but for the four icons of the views, which are a group of their own,
  **`Icons`**, below the group `Main` (`docs/img/img_64.png`). Their checkboxes are `Cost`, `History`, `Log` and `Error log`, where
  they were `Cost icon`, `History icon`, `Log icon` and `Error log icon`; their keys and tooltips stay. **Beside each checkbox is
  the icon itself**, as the row draws it (the two bars, the table, the sheet with lines, the triangle), in the muted gray; **a click
  on the icon checks its checkbox**, and a click on it while it is checked leaves it checked; the checkbox alone unchecks it. It is
  previewed in the row as a click on the checkbox is. **The button that selects every element of the row, or none, is in the
  heading of `Main` and takes the elements of that group alone**; the heading of `Icons` has the same button for the four icons,
  with the tooltip `Checks every icon, or none when every one is checked.` **Closing a view by its icon in the row never changes its checkbox**: the checkbox says whether the icon is in
  the row, not whether its view is open.
- **`History`** has the checkboxes of the history's columns as before, and below them the group **`Files`** with the history's
  folder, `History folder`, its `Choose…` and `Default` as before, and its path as a link with its copy button, which the About
  view had. Its tooltip: `The folder the usage history is kept in. The default is the program's folder.`
- **`Log`** (new) has `Log the response`, which was in the group `Log` of `General`, and the group **`Files`** with the log's
  folder, `Log folder`, its `Choose…` and `Default`, and the log's path as a link with its copy button. Its tooltip: `The folder the
  log and its old logs are kept in. The default is the program's folder.`
- **`Cost control`** begins with the group **`Request`**, as it was in `General`: `Interval`, its field, `s` and `Automatic`, with
  their tooltips and their behaviour; then the group `Cost control` as before.
- **`About`** keeps the name, the version, what the program runs on and the line of debug mode; **the three paths are gone from
  it**, to `General`, `History` and `Log`.
- **Two folders again.** The keys `logFolder` and `historyFolder` are written again, and `dataFolder` is no longer written. A file
  of 0.87 to 0.89, which has `dataFolder`, gives that folder to both, so nothing is moved at the first start. `Save` moves the
  log and its old logs into the log's folder and the usage history into the history's, each as it did until 0.86, and a folder
  that cannot be written is said in red under its own group `Files`, with the focus on its `Choose…`.
- **The window is as wide as the widest tab**, `Log` and its paths included, as before.

### The window shows where it is when it comes to the front

- **Since version 0.89, whenever the window becomes the active window, a rectangle zooms onto it**, so that it is found at
  once (`new-requirements.md`): when it is brought up from the taskbar or the Dock, restored from minimized, chosen with
  Alt-Tab or Cmd-Tab, clicked while another window was active, and at the start. **Not** when the active window was one of the
  program's own, the folder picker of the settings, and focus comes back from it.
- **How it looks.** An outline of 3 pixels in the blue of the notes, `#0969da`, with rounded corners of 6 pixels, starts around
  the middle of the screen the window is on, 60 % of the screen's width and height, and in **0.6 seconds** shrinks and moves
  until it lies exactly on the window's edges, easing out; it then fades in 0.15 seconds and is gone. A second activation while
  it runs starts it again from the beginning.
- **It is drawn by a window of its own**, transparent, above all others, which takes no focus, no click and no place in the
  taskbar; the program's window is not moved or changed by it. Where the system cannot draw a transparent window, nothing is
  drawn.
- **It is not a setting.** (Until version 0.89; see below.)
- **Since version 0.90 it is a setting, off by default** (**on by default since version 0.91**, `docs/img/img_69.png`): `Show where the window is`, a checkbox in the group `Window` of the
  tab `General`, after `Several views at once`, saved under `locateWindow`. Its tooltip: `When the window comes to the front, a
  rectangle zooms onto it so that it is found at once. Not when you click into the window.` It takes effect when the settings
  are saved.
- **Not when the window is clicked into** (since version 0.90): an activation that comes with a press of the mouse inside the
  window, within half a second, shows nothing; the taskbar, the Dock, Alt-Tab or Cmd-Tab, a restore from minimized and the start
  still do.
- **Not when the title bar is clicked either** (since version 0.91): a click on the title bar of the system is no press the program
  is told of, so **the rectangle is shown only when the pointer is outside the window as it becomes the active window** (the
  taskbar, the Dock, a restore from minimized) or at the start. Alt-Tab or Cmd-Tab with the pointer over the window shows nothing
  then; that is the price of telling a click on the title bar apart.

### Version 0.91: the error log goes into the log, and smaller things

- **The error log is gone** (`new-requirements.md`): its icon, the triangle, its view, its checkbox `Error log` in the group `Icons`
  and the setting `showErrorIcon` (still read in a file, and ignored). What this document says of the error log, its panel, its
  width, its height and its notes holds no more. A file of 0.90 that stored the error log among the views shown at the start opens
  without it.
- **Every failed refresh is written to the log at the level `ERROR`**, with the message the row showed for it (where some were
  `WARNING` until 0.90), so that the log holds what the error log held; an HTTP 429 with the automatic interval, which is part of
  how the search works, stays at `INFO`.
- **The log viewer shows the lines of `ERROR` in red** (`#cf222e` on the light theme, `#f85149` on the dark one) **and those of
  `WARNING` in orange** (`#c26a00`, the darker orange of the status circle, readable as text on both themes), the whole line, its
  time and level included. The gap between the level and the text stays as the file has it (`INFO    [`).
- **The log opens larger**: 30 of its lines tall where it was 10, and half again as wide, within the screen; a height dragged to is
  still remembered and wins, as before.
- **The icons in the group `Icons` are gone** (`docs/img/img_71.png`): the checkboxes `Cost`, `History` and `Log` stand alone, and a click
  on an icon checks nothing, there being none.
- **A right edge, like the bottom one** (see Taller and shorter by the bottom edge): while the title bar is off and a view whose
  width the person may change is shown, the log alone or among others, the right 6 pixels of the window make it wider and narrower,
  with the cursor of a horizontal resize and a grip on hover (2 by 24 pixels, centred, in the muted gray). The width is kept as one
  dragged by the frame is. The history alone has none: its width is its table's.
- **The paths in `Log` and `History`** (`docs/img/img_72.png`): the line of the folder shows the folder in full, the program's folder too
  (its path in gray while it is the default, where it said `Program folder`); the line of the file shows the file's name alone,
  still a link that opens its folder, with the button that copies the full path.
- **Every column of the history is centred** (`docs/img/img_73.png`), its title and its cells, as `daily limit is` is since 0.90.

### Version 0.92: quieter log, the icons in one line, the screen as the largest size

- **A request that succeeds is logged at `DEBUG`** (`docs/img/img_74.png`): its line `GET <host><path> -> HTTP 200 in <n> ms (…)` and the
  line `Usage refresh succeeded`, where both were `INFO`, so that the log at its usual level is not a line every few seconds. A
  request that fails keeps its `GET` line at `INFO`, before its `ERROR` line, so that a failure has its context at the usual level.
  Everything else stays as it was; the log viewer at `Info` shows the starts, the changes of the interval, of the settings and of
  the source, and the failures.
- **The checkboxes of the group `Icons` are in one line** (`docs/img/img_75.png`): `Cost`, `History` and `Log` side by side, after the
  heading's button, as the properties beside a checkbox are.
- **The largest size is the screen's**, the visible area of the screen the window is on (its work area, without the taskbar or the
  Dock), in width and in height: for what the program sets (where `WindowFit.MAX` was 2,400 by 1,600), for the height a list opens
  at and a remembered one, and for a drag by the frame or by an edge. A window on a smaller screen than before is never larger than
  it; one moved to another screen takes that screen's size as its largest at the next change of its size.

### Version 0.93: a view grows towards the room there is

- **A view grows towards the room there is** (`tmp.md`): the window grew from its top left corner, so that a window low or on the
  right of the screen ran over its bottom and right edges when the log opened, and had to be moved. When a view is opened, or
  replaces another, and the window gets larger, **the corner of the window nearest the corner of the screen stays where it is**:
  a window in the lower half of the screen keeps its bottom edge and grows upwards, one in the right half keeps its right edge
  and grows to the left; one in the upper left keeps its top left corner, as before. Which half the window is in is decided by
  the middle of the window as it is before it grows, on the screen it is on. **The same corner stays where it is whenever the
  program changes the size while a view stays shown**: one of several views closed, another added, a list made taller or
  shorter by its own buttons, so that a window at the bottom of the screen keeps its bottom edge there. A new line in a view that stays open, a drag by
  the frame or by an edge, and a row going away while the pointer is outside the window move nothing.
- **What does not fit is moved onto the screen**: when the window, grown so, would still lie partly off the visible area of the
  screen, it is moved the least that puts it wholly on it, in width and in height. It is never larger than that area (see
  Version 0.92), so it always fits.
- **Closing the view puts the window back where it was**: the place the window had before the view was opened is kept, and when
  the window goes back to the row alone it goes back to that place, so that the row is where the person left it. Not when the
  person has moved the window while the view was open: then the row keeps the place it has, its corner nearest the corner of the
  screen staying where it is, as it does when the window grows. (**Since version 0.94 always**, moved or not; see Version 0.94.)
  Replacing one view with another keeps the place of the row alone, so that closing the second goes back to it too.
- **The width of the log is remembered**, as its height is (see Remembered heights): `logWidth` in the settings file, in pixels
  of the window's content, stored once a change by the person has settled, as the height is. The log opens at the stored width,
  never less than the width the page asks for, within the screen. The history keeps no width, its width being its table's.

### Version 0.94: the whole address in the log, a button that ends the program, the history grouped

- **The request line has the whole address** (`new-requirements.md`): the scheme, the host, the port when there is one, and the
  path, `GET http://127.0.0.1:52011/api/oauth/usage -> HTTP 200 in 2 ms (…)` and `GET https://api.anthropic.com/api/oauth/usage
  -> …`, where it had the host and the path alone. The same in the line `Response of GET <address> (HTTP <status>):` of `Log
  the response`. The levels stay those of 0.92: a request that succeeds at `DEBUG`, one that fails at `INFO`.
- **Every line is written to the file, at every level, whatever the log view's `Level` shows** — as it is since version 0.44
  (see Debug lines in the log and The level and the date of the log panel): the dropdown chooses what is shown, and nothing of
  what is written. The file keeps its words for the levels, `FINE` for a debug line and `SEVERE` for an error.
- **A button that ends the program** (`new-requirements.md`): an icon, a cross (×) in the muted gray like the other icons, the
  last in the row, right after the gear. A click ends the program at once, without asking, exactly as closing the window does:
  the place, the size and the views of the window are stored as then, and the lines of the stopping are in the log. Its tooltip
  and its label: `Close aip usage`. It is there with the title bar too, beside the system's own cross.
  - **It can be switched off**: a checkbox `Exit` in the group `Icons` of the tab `Main`, after `Log`, **on by default**, saved
    under `showExitIcon`, with the tooltip `The icon that ends the program, after the gear.` It is previewed in the row: unchecked
    while the settings are open, the cross is gone at once (where the icons of the views stay, dimmed, so that their views can still
    be reached), and back with Cancel. The heading's button of `Icons` takes it with the others. Without it the program is ended by closing the
    window, as before; with the title bar off that is the cross switched on, or the system's own way (Alt-F4, the taskbar).
- **The history can be grouped** (`new-requirements.md`): a dropdown in the line of the history's zoom buttons, before them,
  with the label `Group` and the choices `none`, `5 min`, `15 min`, `30 min`, `1 h`, `1 day`, `1 week` and `month`; `none` by
  default, which shows every reading as before. Its tooltip: `Combines the readings of each interval into one line.` A choice
  takes effect at once, with no button, and is saved under `historyGroup` in the settings file (`none`, `5m`, `15m`, `30m`,
  `1h`, `1d`, `1w`, `month`), so that the history opens grouped as it was left, after a restart too. A value that is none of
  them is `none`. It is no setting of the settings view, as the log's `Level` is none.
  - **An interval** begins on the clock in the machine's zone: 5, 15 and 30 minutes at `:00`, `:05`, …, `:15`, …, `:30`; an hour
    at the full hour; a day at midnight; a week on Monday at midnight (ISO); a month on its first day.
  - **One line for each interval that has a successful reading**, newest first; an interval with none has no line. Failed readings
    are not combined, and the blue lines that begin a run are not shown while grouped; `Failed lines` has nothing to hide then.
    `Zero usage lines` off hides the intervals whose `Δ used` is 0.
  - **Its cells**: the time is the beginning of the interval; `used`, `limit`, `month`, `day`, `daily limit is` and `Cur.` are
    those of the interval's last successful reading; `Δ used` is the sum of the changes of its readings, the first's from the reading
    before it, so that a new month within the interval is counted right; `Δ time` is the time from the reading before the interval's
    first to its last, as the readings' own `Δ time` add up.
  - **The time is written** `14:15` for 5 minutes to an hour, with the date before it when `Date` is on, as now; `2026-10-16` for a
    day; the Monday, `2026-10-12`, for a week; `2026-10` for a month. The table is as wide as that needs, as it is for the date.
  - **The first line of a day is underlined** as before (see The first reading of a day is underlined in the history),
    with the lines that are shown: the first interval of each day, for 5 minutes to an hour (`firstOfDay`, as now); with a day or
    longer every line begins a day, and none is underlined.
  - **Every reading of the file is grouped**, not only the newest 1,000 the history shows without grouping, so that a week or a
    month holds all of its readings. **The note above the table counts lines**, which are intervals then: `Showing 96 of 96
    lines.`, and the newest 1,000 of them at most, as for readings.
  - With `Several views at once` the history keeps its dropdown in its own line.
- **Closing the views puts the row back where it was, always**: where version 0.93 left the row where the person had moved it
  while a view was open, it now goes back to the place it had before the first view was opened, moved or not, onto the screen if
  that place is no longer on one. Every view keeps that place, the cost control and the settings too.

### Version 0.95: a level Fine, and a shorter line for a change of the settings

- **Five levels** (`new-requirements.md`, `docs/img/img_77.png`): `Error`, `Warning`, `Info`, **`Fine`** and `Debug`, in that order, in the
  log view's `Level` dropdown (values `error`, `warning`, `info`, `fine`, `debug`). Each shows its own lines and those of the levels
  above it: `Fine` shows Error to Fine, `Debug` shows everything. The choice is saved under `logLevel` as before, `fine` being new;
  a saved `debug` stays `Debug`. Its tooltip is unchanged.
- **What happened is Fine, how it was done is Debug.** These lines, which were debug lines, are Fine lines now:

  | Line | Written when |
  | --- | --- |
  | `GET <address> -> HTTP 200 in <n> ms (…)` and `Usage refresh succeeded` | a request succeeds (see Version 0.92) |
  | `Settings changed: …` (see below) | a setting is saved, by the settings view or by a choice of a list |
  | `The <panel> window height is now <N> px`, `The log window width is now <N> px` | a size of a list is remembered |
  | `The window is restored: …` | the start has put the window as it was |

  The others stay debug lines: `Window fitted to …`, `The window has its title bar` and `… has no title bar`, the naming of the
  program and its icon for macOS and the Dock, the outline that shows where the window is, the listing of the processes `claude`
  started, `Could not work out the spending`, and `The stored place of the window is on no screen`.
- **The words in the file**: a Fine line is written `FINE`, a debug line **`DEBUG`** (where every debug line was `FINE` until 0.94);
  the others as before, `SEVERE`, `WARNING`, `INFO`. The column stays 7 characters wide. The log view reads the `FINE` lines of an
  older file as Fine lines, and `DEBUG` as Debug; the console writes the same words.
- **A change of the settings is a Fine line, and names only what changed** (`docs/img/img_78.png`, where the whole of the settings
  was written at `Info` at every press of the zoom): `Settings changed: <key> <before> -> <after>`, one for each setting that is
  another, joined by `, `, in the order of the settings file, by the keys of the file and their values as the file writes them:
  `Settings changed: listFontSize 12 -> 13`, `Settings changed: historyGroup none -> 15m, historyZeroLines true -> false`. A text is
  written as it is, an empty one as `""`. A save that changes nothing writes nothing, as before. At `Info` the log view no longer
  shows it.

### Version 0.96: the figures of the history line up, and no empty room in a narrow row

- **The figures of the history line up at the decimal point** (`new-requirements.md`, `docs/img/img_79.png`, where `-1.22` stood
  centred under `+247.16`): the columns stay centred under their titles, as since 0.91, but **within a column every cell is as
  wide as its widest**, padded at the left, so that the figures stand as one right-aligned block in the middle of the column and,
  the text being of fixed width and every amount having two decimals, line up by their decimal points.
  - **Which columns**: `used`, `limit`, `month`, `day`, `Δ used` and `Δ time` (`  610 s` under `35061 s`, lined up by the
    number). The time, `daily limit is` and `Cur.` stay centred, each cell by itself.
  - **The widest is of the lines that are shown**, grouped or not, so it follows the filter and the grouping. An empty cell stays
    empty and counts for nothing; `failed`, in the place of a failed line's amount, is a cell like the others.
  - **The width of the column is unchanged**: it was already as wide as its widest cell, or as its title.
- **A narrow row has no empty room** (`docs/img/img_80.png`, the status circle and the gear with some 100 pixels between them): the
  program made the window at least 160 pixels wide, which the row with nothing but the circle and the gear does not fill, and the
  gear stood at the right edge. **The least width the program gives the window is now 40 pixels** of its content, so the window is
  as wide as the row: the circle and the gear side by side, with the gap of the row between them. The least height, 32 pixels, is
  unchanged. Where the system keeps a window with a title bar wider than that, the room is between the last figure and the
  gear, as before (see The icons and the gear stand at the right edge of the window).

## Debug mode

What the program does when it is started with `--debug`, and only then: the tab `Debug` of the settings, the debug settings, the fake
backend switched while the program runs, and the mark that says its figures are invented.

- **One command-line option, `--debug`, starts the program in debug mode.** It takes no value. Without it the program
  is not in debug mode, and nothing of what follows is shown or can be reached.
- **There are no options `--fake-backend`, `--fake-scenario` and `--fake-token`** (there were, before version 0.37). What
  they did is set in the tab `Debug` of the settings instead.
- **There are no options `--usage-interval` and `--poll-interval` either.** The time between usage requests is set in the
  settings view and nowhere else, and the window asks the application for its state once a second, which is not to be
  changed. The interval that `settings.json` holds is the one in force from the start.
- **What is left of the command line** is `--debug`, `--anthropic-url <url>`, and `-h` and `--help`.
- **Without `--debug` the debug settings count for nothing.** A start without it fetches from Anthropic, or from the
  URL `--anthropic-url` names, with a real token, whatever `settings.json` says of the fake backend, the scenario and
  the placeholder token, so that invented figures are never shown without the marks of debug mode.
- **`--anthropic-url <url>` stays** on the command line, as it is: it names the base URL for the run and is not a setting.
  It no longer has an option to contradict it; while the fake backend is in use the URL is simply not used.
- **There is no debug button** (since version 0.64; before, a yellow bug button at the right of the gear opened a view of its own). **The debug settings are the tab `Debug` of the
  settings**, between `Cost control` and `About`, reached through the gear like every other tab, and only in debug mode: without `--debug` there is no such tab, and nothing in
  the strip tells of it. It is saved by `Save` and left by `Cancel` like the others (see Settings), and a save that fails keeps the view open.
- **The tab `Debug` is part of the settings for the title bar**: while it is shown the window has its title bar, whatever the setting `Title bar` says (see Settings).
- **The tab, exactly.** One group, headed `Debug mode`: a checkbox `Fake backend` with a dropdown beside it, in the same
  line, that names the scenarios the backend knows; and a checkbox `Placeholder token`. Each has a tooltip that says what it
  does. The tab is as wide as its texts need, like the others, and the window as tall as the row and the tab.
- **The debug settings**, all kept in `settings.json` with the others, under the keys `fakeBackend`, `fakeScenario` (the name
  of the scenario) and `fakeToken`:

  | Setting | Values | Default |
  | --- | --- | --- |
  | Use the fake backend | on, off | off |
  | What the fake backend answers (the scenario) | the scenario names of Fake backend | `normal` |
  | Send a placeholder token instead of obtaining a real one | on, off | off |

- **The fake backend can be turned on and off while the program runs**, by `Save` in the tab `Debug`: on, the
  program starts it and fetches from it; off, the program stops it and fetches from the base URL again, Anthropic or the one `--anthropic-url` names. The switch is
  saved, so it also says **whether the fake backend is started at startup** the next time the program is started in
  debug mode.
- **The debug tab applies at once.** A change of the fake backend, the scenario or the placeholder token takes effect
  when the view is saved, followed by a refresh, without a restart. A usage request that is under way at that moment is given
  up: its answer is of the source that was left, so it is neither shown nor written to any history, and it is no failed refresh.
- **The reading of the other source is not kept on show.** When the source changes, the row shows the newest reading of the
  new source's own history, or, when that has none, begins again as at startup, with its gray boxes, until the refresh has an
  answer. A real reading is never shown beside the red border, and an invented one never without it.
- **When the new source cannot be opened**, the fake backend cannot be started or a token provider refuses, nothing is
  changed: the debug settings are not saved, the source in use stays, and the view stays open with
  `The debug settings could not be applied: <reason>` in red.
- **The ordinary settings do not know the debug settings.** They are not in the settings view, not in what it reads and not in
  what it sends, and saving the settings leaves them as they are. They have a request of their own, which answers that
  there is no such thing when the program is not in debug mode (see `docs/api.md`).
- **The fake backend leaves out the token flow**, as it always has: with it on no token is obtained, whatever the
  switch of the placeholder token says, and the base URL is not used.
- **Invented readings have a history file of their own.** While the fake backend is in use, readings and failed
  queries are written to `java-aip-usage.fake.csv`, beside `java-aip-usage.csv` and in the same form, and
  `java-aip-usage.csv` is not written to: the real history never holds an invented figure. The history panel shows the
  file in use, and the change in the amount used and the time since the previous reading are worked out within that
  file, so a real reading is never compared with an invented one. When the fake backend is switched on or off the
  program changes to the other file with the refresh that follows, and an open history panel shows the other file. The
  reading shown at startup comes from the file of the source in force. The file is logged at startup and at each
  change, in the form of the line there is, `Usage history is written to <the file>`. The first row a run writes to
  either file is marked as a start, each file for itself.
- **A very visible mark while the fake backend is in use**, hard to overlook: **the window has a solid red border**,
  all the way round its content, which **slowly changes from red to the usual colour and back**, again and again. The
  usual colour is that of the window's background, so at one end of the change the border is not seen and at the other
  it is fully red. It takes the place of every other mark: there is no band and no text.
- **The border is there for exactly as long as the fake backend is in use.** It says that the figures are invented. It
  comes when the fake backend is switched on and goes when it is switched off; in debug mode with the fake backend off
  there is no border, and the tab `Debug` is the one sign of debug mode.
- **The border, exactly.** It is 3 pixels wide and in the red of errors, `#cf222e` on the light theme and `#f85149` on
  the dark one. One change from red to the background and back takes 4 seconds, at an even pace. It is drawn by the
  page, so it is inside the frame of the system; it is there with the title bar hidden as well, and round an open
  panel too. The window is larger by the border, so nothing in the row is covered. It is there while the figures are old
  as well, and is not dimmed with them.
- **The border goes round everything the window shows**, whatever is shown: the row alone, a panel, the settings view and
  the settings, the debug tab included. Its four sides are at the four edges of the window's content.
- **It is never round the row alone while a view is shown below it.** Before version 0.38, with the settings view or the
  debug settings view wider than the row, the border was drawn as wide as the row and as tall as the window, so that it ran
  down through the view, across its checkboxes and its `Save` button, and the right part of the view was outside it
  (the case that brought it up had every element of the row hidden).
- **The view is inside the border, and nothing of it is covered or cut off.** The window is as wide as the wider of the
  row and the view, and the border, and as tall as the row and the view, and the border. Nothing is scrolled.

## Details of the behaviour

These are things the program does that the sections above do not say, written down so that the requirements are the whole of it.

**Command line**

- The options are `--debug`, `--anthropic-url <url>`, and `-h` and `--help`. There are no others: the time between usage
  requests is set in the settings view, and what the fake backend, its scenario and the placeholder token were options for is in the
  debug settings (see Debug mode).
- `--debug` starts the program in debug mode. It takes no value.
- `--anthropic-url <url>`, also as `--anthropic-url=<url>`, replaces the base URL of the usage endpoint for the run. The value is
  trimmed. It is accepted when it is an absolute URL whose scheme is `http` or `https` and which has a host; any host is allowed, this
  machine or any other, and no warning is given, because the person naming the URL is the one deciding where the token may go. A
  trailing slash is ignored, so `http://127.0.0.1:8080` and `http://127.0.0.1:8080/` are the same. A path is kept as a prefix, so
  `http://127.0.0.1:8080/proxy` is fetched as `http://127.0.0.1:8080/proxy/api/oauth/usage`, which is what a proxy under a path
  needs. A URL with a query or a fragment is a mistake, since neither has a meaning here. The default is
  `https://api.anthropic.com`. It is never saved and has no setting or window control: it belongs to one run. While the fake
  backend is in use the URL is not used.
- **A mistake** prints one of these messages and then the usage text on the error output, and the program ends with exit code 2
  before anything is logged or shown: `Unknown option: X`, `--debug takes no value.`, `<option> was given more than once.`,
  `--anthropic-url needs a URL.`, and `--anthropic-url must be an absolute http or https URL, not "x".`
- **The options that are gone** are unknown options like any other word: `--fake-backend`, `--fake-scenario`, `--fake-token`,
  `--usage-interval` and `--poll-interval` each draw `Unknown option: <as typed>`.
- **Help.** `-h` and `--help` print the usage text and the program ends without opening a window. The text goes to the standard
  output, the program ends with exit code 0, and it ends before the log is set up: no log file is created or added to and nothing is
  logged. A mistake wins over help: the whole command line is read before help is acted on, so `--help --bogus` prints the mistake
  and the usage text on the error output and ends with exit code 2. `-h` and `--help` may be given more than once, and anywhere among
  the other options, without that being a mistake. `--help=x` is not help: it is `Unknown option: --help=x`.
- **What is an option.** The names are matched exactly and with their case; there are no abbreviations. Anything that is not one of
  them is an unknown option, a bare argument included (`10` draws `Unknown option: 10`), and the message gives the whole argument as
  typed, with its `=value` if it had one. The options may come in any order.
- **Where the value comes from.** In the two-argument form the value is the next argument whatever it is, even one that looks like an
  option. `needs a URL` is said when the option is the last argument, when the value after `=` is empty, and when it is blanks alone.
- **One mistake is reported, the first.** The arguments are checked from left to right and the first mistake ends it. For
  `--anthropic-url` the order is: no value, given more than once, a bad value. For `--debug`, `takes no value` comes before
  `given more than once`.
- **The URL, more exactly.** The scheme is read without regard to case, so `HTTP://127.0.0.1:8080` is accepted, and kept as typed. One
  trailing slash is dropped, not several. The message of a bad URL gives it trimmed.
- **The scenario names** of the fake backend, which the debug settings offer, are `normal`, `http-401`, `http-403`, `http-429`,
  `http-429-retry-after`, `http-500`, `not-json`, `empty`, `no-spend-no-windows`, `trailing-text`, `slow` and `hang` (see Fake
  backend for what each answers).
- **The usage text** is this, word for word. The default URL in it is the one the program uses, so the text cannot say one thing and
  the program do another:

  ```
  Usage: java-aip-usage [--debug] [--anthropic-url <url>]

    --debug                     debug mode: a tab Debug in the settings, whose settings say whether
                                usage comes from a fake backend inside this program, what it answers,
                                and whether a placeholder token is sent
    --anthropic-url <url>       where to fetch usage from, without the path (default https://api.anthropic.com)
    -h, --help                  show this help

  The debug settings are kept in settings.json like the others, and count only in debug mode: a
  run without --debug fetches from Anthropic, or from the URL given, with a real token. Everything
  else, the time between usage requests among it, is set in the window.
  ```

**The usage request**

- The connection is made within 10 seconds and the answer comes within 20 seconds, else the refresh fails with `Anthropic did not answer within 20 seconds.` A redirect is never followed, so the token cannot
  be sent to another host; it fails like any other status, with the status it had, for example `Anthropic returned HTTP 302.` A connection that is not made within the 10 seconds draws the same
  message as an answer that does not come, the one that names 20 seconds, and both are `timed out` in the log.
- **The connection is direct, and that is a decision, not an omission.** No proxy is used and no proxy
  setting of the environment is read, `HTTPS_PROXY` and `HTTP_PROXY` among them. On a network that lets
  nothing out but a proxy every refresh therefore fails with `Cannot reach <host>: <detail>`, like any
  other host that cannot be reached, and the window says so rather than appearing to work. There is no
  option, no setting and no window control for a proxy: a proxy in front of the endpoint is reached by
  naming it as the base URL (`--anthropic-url`, see Command line), which is the one way to put another
  server in the path.
- The messages of a failed refresh (after `Refresh failed at HH:mm: `): `Anthropic rejected the OAuth token (HTTP 401).`, `Anthropic refused the usage request (HTTP 403).`,
  `Anthropic is rate limiting usage requests (HTTP 429).`, `Anthropic returned HTTP n.` for any other status, `Cannot reach <host>: <detail>` (the one message with no full stop of its own, since it ends with the words for what went wrong) where the host is the one
  of the base URL in force, without its port, so the default run reads `Cannot reach api.anthropic.com: <detail>` and a run against the fake backend reads `Cannot reach 127.0.0.1: <detail>`,
  `Interrupted while fetching usage.`, and `Unexpected error; start the program with --debug to see on the console where it came from.` When the retry with a fresh token is rejected too: `Anthropic rejected a freshly obtained OAuth token (HTTP 401). Log in again with Claude Code, then refresh.`
- The response is read as follows. `spend` counts only when its `enabled` flag is true; amounts are minor units divided by ten to the power of the exponent (0 if none); the currency is that of `used`, else of `limit`; the percentage is used as sent
  if it is a number; the severity is the text sent. A response that is not JSON, not an object, empty, has trailing text,
  enables spend but reports neither `used` nor `limit`, or has no `spend` object is a failed refresh whose message says what is wrong (for example
  `The usage response has no "spend" object; its format may have changed.`). A `spend` that is not enabled is an account that reports no usage.
- The wait and the amount of a `Retry-After` header are only believed as a whole number of at most six digits; a date is taken as no header.
- A manual refresh that has been asked for and has not started yet shows a countdown of 0 or less. `POST /api/refresh` answers 202 with `{"started":true}` when it started a request and 200 with `{"started":false}` when one is already
  running or asked for, and also when refreshing has not started yet or has been stopped.
- **What is sent.** One `GET` with two headers of the program's own, `Authorization: Bearer <token>` and `Accept: application/json`, and no body. It sends no `anthropic-version`, no `anthropic-beta` and no user agent of its
  own, keeps no cookies and answers no challenge. The security of the connection is the platform's: its certificates and its defaults, with nothing of the program's own added or taken away.
- **Which statuses are a success.** Any status from 200 to 299 is one, and its body is read as a usage document; every other status is a failed refresh whose body is never read. The messages of a status and of a time-out
  always say `Anthropic`, whatever the base URL; only `Cannot reach` names the host. `<detail>` in that message is one of the program's texts for what went wrong (see The words for what went wrong), `the connection was refused` for one; until version 0.73 it was the system's own text, in the system's language.
- **`Retry-After`, exactly.** It is believed only as a whole number of seconds above zero, of at most six digits: `0`, a negative number, a decimal, a date and any other text are taken as no header. Only the first value of the
  header is looked at.
- **The messages of a response that cannot be used**, each of which begins `The usage response`: `The usage response is not valid JSON: <where and what>`, in the program's own words since version 0.71, as `line 3, column 14: a value was expected` (until then the words were those of the JSON library); `The usage response is not a JSON object.`, which is
  also what a body with nothing in it, or with blanks alone, draws, and `null`, a number, a text and a list; `The usage response enables spend but reports neither "used" nor "limit"; its format may have changed.`; `The usage response has an amount larger than the program can hold; its format may have changed.`, for an amount that is more whole cents than a 64-bit number holds; and the one
  about no `spend` object, above. (`The usage response was empty.` is the message for a response that has no body at all, which an HTTP answer never is.) What the JSON reader says can name the first
  thing it could not read, a word of the body, so that message is the one place where a few characters of a response can reach the window and the log; it is masked like everything else.
- **A response is never guessed at.** One that cannot be understood is a failed refresh that says so; it is never shown as an empty reading, as zero, or as the figures of the reading before. The endpoint is not a published
  one, and if its format changes the window is to say what is wrong, not to show wrong numbers.
- **The spend, more exactly.** A `spend` with no `enabled` at all is not enabled. An amount is an object with a numeric `amount_minor`; anything else is an amount that is missing. One of the two is enough: a `used` with no
  `limit`, or the other way round, is a reading with one amount. An `amount_minor` with a fraction is cut to a whole number before it is scaled, and the scaling is exact (1 with an exponent of 2 is 0.01); an exponent that is
  missing or not a number is 0. A `percent` with a fraction is cut to a whole number (19.9 is 19), and one that is not a number is missing, not a mistake. The severity and the currency are taken as text whatever was sent, a
  number as its digits; `null` and a missing key are none. The currency of `limit` is used also when that of `used` is `null`.
- **What is beside `spend` is not read.** Every other top-level member, whatever it holds, and every key the program does not know, anywhere, is passed over without a word.
- **While a refresh runs, what the last one left stays**: the message, its time and the sign of a 429 are there until the running request has ended, and only a success takes them away; a new failure replaces the message and
  its time. The time of a failure, in the message line and in the error log, is when the failed request ended, not when it was started.
- **Old figures, exactly.** The figures are old, and dimmed, when there is a reading, the latest refresh failed, and the failure was not a 429. A failure with no reading yet is not that, and a reading taken from the history
  at startup is not old until a refresh fails.
- **An unexpected fault does not stop the refreshing.** It is the failed refresh `Unexpected error; start the program with --debug to see on the console where it came from.` The log has the warning `Usage refresh failed unexpectedly: an unexpected fault`; what the fault itself says, with its
  stack trace, is on the console of a run in debug mode, and never in the window or the log file. The next refresh comes at its usual time.

**Getting the token**

- Each time a token is needed the program runs `claude -p ping` with its base URL pointed at a small server of its own on the loopback interface, on a port the operating system chooses so that no port has to be free for the program to work, takes the credential `claude` sends, and stops `claude` and what it started; nothing is sent to
  Anthropic by it. It waits 30 seconds at most. After a failure no token is kept, so while the person is logged out a new run of `claude` starts with every scheduled refresh.
- The messages, as the row shows them: `Claude Code did not send a credential within 30 seconds. Run `claude` in a terminal to check that it works and is logged in, then retry.`, `Claude Code exited without sending a credential, so it is probably
  not logged in. Run `claude` in a terminal and log in with /login, then retry.`, `Claude Code could not be found on the PATH. Install it, then retry. If it is installed somewhere the PATH does not cover, add that directory and restart this
  application, which keeps the PATH it was started with.`, and `Claude Code was found but could not be started: <reason>. Check that it is installed correctly and can be run, then retry.`
- The credential variables count only if they are not empty; the message names the one or both that are set, and the check is made on every acquisition, from the environment the program started with.
- After a failed attempt the first 2,000 characters of what `claude` printed are written to the log at the warning level as `claude output: ...`, masked like everything the log gets.
- **How `claude` is run.** It is started as `claude -p ping` with the environment the program has, plus `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>/`, in the program's own working directory. What it prints on its error
  output is taken together with its ordinary output, and its input is closed at once, so that it cannot sit waiting to be typed to.
- **What the capture server answers.** It answers every request itself, whatever the path and the method, with HTTP 200, and passes nothing on; it does not look at the `Host` header. A `HEAD` gets no body. A request whose
  body asks for a stream (`"stream":true`, spaces aside) gets `Content-Type: text/event-stream` and the shortest event stream that is a complete answer, the one word `pong`: `message_start` (message `msg_mock`, model
  `claude-opus-4-5`, one input token), `content_block_start`, a `content_block_delta` with `pong`, `content_block_stop`, `message_delta` (`end_turn`, one output token) and `message_stop`. Any other request gets
  `Content-Type: application/json` and `{"type":"message","role":"assistant","content":[{"type":"text","text":"pong"}]}`. `claude` is therefore given a real answer and ends by itself if it is left to.
- **Which header is the credential.** `x-api-key` first, if it is there and not blank, taken as it stands; otherwise `Authorization`, if it begins with `Bearer ` (in any case), the rest of it trimmed and not empty. Header
  names are read without regard to case. An empty bearer, or any other kind of authorization, is no credential. The first request that carries one settles it and later ones are not looked at; a request without one is
  answered like any other and the wait goes on, so a request `claude` makes before the real one does not end it.
- **`claude` ending before a credential has come is "not logged in"**, whatever its exit code, zero included, and whether or not it made a request.
- **Clearing up, in every outcome**, success, time-out, exit and interruption alike: every process `claude` started is killed, then `claude` itself, and the program waits up to 2 seconds for it to be gone; then the capture
  server is stopped at once, so nothing listens on that port afterwards. An acquisition can therefore take a little over the 30 seconds: the 30 for the credential, up to 2 for `claude` to be gone and, after an exit, up to 2
- **How the processes are killed** (since version 0.75): with the commands the system has for it, and through nothing that only one language's runtime offers. On Windows `taskkill /PID <pid> /T /F` ends `claude` and everything it started in one step, which matters there because `claude.cmd` is run through `cmd.exe` and the program proper is a process that started. On macOS and other systems the processes `claude` started are found with `pgrep -P <pid>`, level by level, and each is ended with `kill -9`, the deepest first and `claude` last. A command that is missing or fails stops nothing: it is noted in the log at debug level, and `claude` itself is ended directly in any case. A `claude` that has already ended is not searched for what it started.
  for its output to end.
- Two more messages, besides those above: `Cannot start the local server used to read the Claude Code token: <detail>` when the loopback server cannot be started, and `Interrupted while reading the Claude Code token.`
- **The message for a credential in the environment**, word for word: `ANTHROPIC_API_KEY is set, so Claude Code would send that credential instead of its OAuth login token. Unset it and restart this application.`, and with
  both, `ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN are set, so Claude Code would send that credential instead of its OAuth login token. Unset them and restart this application.`, the API key always named first. A variable
  that holds blanks alone counts as not set. The check comes before anything is started: no server is opened and `claude` is not run.
- **On Windows the command is found the way a shell finds it.** Windows starts a program only by the full name of its file, and a command typed without an ending is found by trying the endings of `PATHEXT` (`.COM;.EXE;.BAT;.CMD`
  when the environment names none) in each directory of the `PATH`, in the order of the `PATH`. The program does the same for `claude`, since Claude Code installed with npm is `claude.cmd`, beside a script called just
  `claude` that is for other shells and that Windows cannot run. A file found with the ending `.cmd` or `.bat` is run through `cmd.exe /c`; any other is started by its full name. On Windows a file of the bare name
  `claude`, with no ending, is therefore not Claude Code: with nothing else on the `PATH` the message is the one for not found. On macOS and Linux the command is started as it stands.
- **"Not found" and "could not be started" are told apart only after the system has refused to start it.** It is not found when the command is a bare name and no directory of the `PATH` holds an ordinary file of exactly that
  name that may be run; a `PATH` that is not set at all counts as not found. In every other case it was found and could not be started, and `<reason>` is one of the program's texts for why a program did not start (see The words for what went wrong), no longer the operating system's own message.
- **The output of `claude` is logged only after a time-out or an exit without a credential**, not when it could not be started and never after a success. The line is `claude output:` with the output beginning on the next
  line. After a time-out it is what has arrived by then; nothing is logged if it is blank. The output is read as UTF-8, and all of it is read however much there is, so that `claude` is never held up by nobody reading.
- Each acquisition writes three lines to the log, none of which carries the token: `Listening on 127.0.0.1:<port> to capture the token`, `Started claude -p ping`, and, when it succeeded, `Token captured`.

**The log**

- **What counts as credential-shaped**, and is therefore masked in every line and every stack trace before it is written: a key that begins `sk-ant-`, a `Bearer` followed by a value, and the words `x-api-key`, `authorization`, `api_key`, `access_token`, `refresh_token`, `password` and `secret` followed by a colon or an equals sign and a value. The masking is the last defence behind not logging these things at all, so a line that was never meant to carry one is masked just the same.
- A line is `yyyy-MM-dd HH:mm:ss LEVEL [Name] message`, with the level padded to seven characters; the console gets the same lines. A line about a failure ends with a colon and the words for what went wrong; no stack trace follows it in the file (since version 0.74). If the log file cannot be opened the program goes on with the
  console only and says `Cannot write log file <file>; logging to console only`.
- The line for each request to Anthropic is `GET api.anthropic.com/api/oauth/usage -> HTTP 200 in 412 ms (1234 bytes, request-id <id>)`, with `retry-after <n>` when the server sent one; the request id is written only if it is made of letters, digits,
  `_` and `-`. A timeout or a failure is `timed out` or `failed` with the time and no size. The host and path are those of the URL actually fetched, so a run against another base URL names that one; the line is the place
  the log shows where a reading came from.
- With the setting to log the response on, the line is `Response of GET <host><path> (HTTP <status>):` (since version 0.94 `<scheme>://<host>[:<port>]<path>`) and the pretty printed JSON on the lines after it, for every status; a body that is not JSON is `(not JSON, N bytes, not logged)`, N being its size in bytes as UTF-8, and an empty one is written as `null`, which is how the JSON reader takes a body with nothing in it. A body that has text after its first JSON value is logged as that value alone, although the refresh then fails for the text. The entry is at the information level, comes
  after the request's own line and before the status is judged, so it is written for a failed status as for a good one; when there was no answer at all there is nothing to write. The setting is asked for each response, so switching it takes effect with the next one, without a restart.
- **The window's status polls are not logged.** They arrive every second, so a line for each would bury everything the log is kept for. The same holds for the window's reads of the log, the history and the error log panels, and for the page and its files: nothing the window asks for is logged, unless it goes wrong. What is logged is what the application does of its own accord, and what a person asked for: a manual refresh is logged, since there is one of those and not sixty a minute, as `Manual refresh requested`, and only when it started a request; a press that starts nothing writes nothing.
- **What a request of the window writes when it goes wrong**, each at the warning level: `Could not read the log: <reason>` and `Could not read the usage history: <reason>` when a panel's file cannot be read, once for each request that failed, the reason going to the log only and never into the answer; `Could not read the usage history for the change: <reason>` when the file cannot be read for the status, at every poll for as long as it lasts, since a read that failed is tried again; `No such frontend resource: /web/<path>` for a file of the page that does not exist; and `API request failed: <path>` with the stack trace for a fault of the program inside a request.
- A change of settings is logged with all the values, a change of the interval also as `Usage fetch interval is now N s`, and a new remembered height as `The history window height is now N px`, which is a debug line. Applying settings that change nothing writes and logs nothing.
- Two programs started at the same time in one directory write to the same history and settings files; running several at once is not something the program is built for (what happens to the log file then is up to the logging system, and has not been checked).
- **What is written, and where.** Lines of the information level and above are written, and nothing finer. The console is the standard error output, not the standard output. The file is UTF-8. The levels are written as
  `INFO`, `WARNING` and `SEVERE`; what this document calls logged "as an error" is a `SEVERE` line. `[Name]` is the name of the part of the program that wrote the line, for example `[UsageApp]` or
  `[ApiHandler]`. The names are fixed, so that every version of the program writes the same lines, whatever its own parts are called: `ApiHandler`, `AppRuntime`, `ClaudeTokenProvider`, `FakeBackend`, `IntervalSettings`, `LocalWebServer`, `Logging`, `ProcessTree`, `SettingsStore`, `UsageApp`, `UsageClient`, `UsageFetcher` and `UsageService`. The time is cut to the second, not rounded. What the console of a run in debug mode shows of where a failure came from begins on the line after its message and is masked like it.
- **`Logging to <file>`** gives the full path. When the file cannot be opened that line is not written, and `Cannot write log file <file>; logging to console only` is a warning that ends with the words for what went wrong. If
  something had started the logging before the program set it up, a warning says so: `Logging was already in use, so lines logged while the process is being stopped may be lost`.
- **The masking, exactly.** What is masked is replaced by `[redacted]`. A key beginning `sk-ant-` is masked to the end of its run of letters, digits, `_` and `-`. `Bearer <value>` becomes `Bearer [redacted]`, in any case, up
  to the next blank. The named words are found in any case, also written `api-key`, `apikey`, `access-token`, `accesstoken`, `refresh-token` and `refreshtoken`, and with quotation marks round the name and the value; the value
  ends at a blank, a quotation mark, a comma or a `}`, and the name and its separator stay, so `{"access_token":"abc"}` becomes `{"access_token":"[redacted]"}` and `Authorization: Bearer abc` becomes
  `Authorization: [redacted]`. Every occurrence is masked, on every line of an entry.
- **The request line, exactly.** It is at the information level. The size is that of the body in bytes as UTF-8. The request id is the `request-id` header, written only if it is 1 to 100 characters of letters, digits, `_`
  and `-`. Inside the brackets the order is the size, the request id, the `retry-after`. A request cut short by the program stopping writes no request line at all.
- **The lines of a refresh.** Every refresh that succeeded writes `Usage refresh succeeded`, or, when the answer has no spend, the line that says so (see An answer that came and has no spend is said explicitly). A rejected token writes `The token was rejected (HTTP 401); acquiring a fresh one`. An HTTP 429 writes one warning, the line `Usage refresh failed: ...` (until version 0.69 there was a line before it, `Rate limited; next try in ...`). An unexpected fault writes the warning `Usage refresh failed unexpectedly` with its stack trace, and then the `Usage refresh failed: Unexpected error ...` line.
- **The lines of the settings.** At startup, when the settings have been read: `Usage interval N s, poll interval M s`, with the values in force for the run. `There was no settings file; created <path> with the defaults`
  when one was made, and the warning `Could not create the settings file <path>: <reason>` when it could not be, which stops nothing: the defaults are used and the run goes on. The warnings for a file or a value that is
  not right: `Ignoring unreadable settings file <path>: <reason>`; `Ignoring settings file <path>: not a JSON object`, which is also what an empty file and a list draw; `Ignoring usageIntervalSeconds in <path>: The usage
  interval must be a whole number of seconds from 5 to 3600.`; `Ignoring <key> in <path>: it must be true or false`; `Ignoring timeFormat in <path>: it must be "hh:mm" or "hh:mm:ss"`; and `Ignoring <key> in <path>: it must be
  a whole number of pixels from 0 to 10000`. A change of the settings is one line, `Settings changed: ` and every setting with its value, by its key (since version 0.95 a Fine line with only what changed, see Version 0.95); in that line the time format is written `HOURS_MINUTES` or
  `HOURS_MINUTES_SECONDS`. Settings that cannot be saved write the warning `Could not save settings to <path>: <reason>`. The height of the log has its own line, `The log window height is now N px`, and a height
  that cannot be stored the warning `Could not save the height of the <history or log> to <path>: <reason>`.
- **The lines of the files and the window.** `Showing the newest reading in the usage history until the first refresh is done`, when a reading was taken from the history at startup. `Could not add the reading to <file>:
  <reason>`, a warning, for a history row that cannot be written. `Settings are stored in <path>`, the line that gives the path of the settings file. `Frontend served at http://127.0.0.1:<port>/`, and `Frontend loaded` when
  the page has loaded. Each time the program gives the window a new size, as a debug line: `Window fitted to <w>x<h> (window <W>x<H>)`, the size of the content and then of the window with its title bar, with ` (resizable: height)` or
  ` (resizable: both)` between them when it may be dragged. If the page cannot be asked for its size, the warning `Could not ask the page for its size: <reason>`, once in a run and not at every try.
- **The order of the lines at startup**: `Logging to`, the start line, the settings path, the lines of the settings, in debug mode
  `Debug mode (--debug)`, the history path, the lines about the fake backend, the base URL and the token (see Non-functional
  requirements), the line about the reading taken from the history, `Frontend served at`, and then the first refresh. The history
  path comes after the settings because, in debug mode, the settings say which of the two history files is in use. One line is out of
  that order in a run that starts with the fake backend: the fake backend is started when the source is opened, so its own
  `Fake backend listening on http://127.0.0.1:<port>, answering <scenario>` comes before `Debug mode (--debug)`.

**The local server**

- The `Host` header must be `127.0.0.1:<port>` or `localhost:<port>` (case does not matter), else the answer is 403 `Forbidden`; this holds for the page as well as the API.
- A `POST` must have a `Content-Type` that starts with `application/json`, else 415 `Send Content-Type: application/json.`; a body over 4,096 bytes is 413 `The request body is too large.`; a body that is not JSON is 400 `The request body is not valid JSON.`, which since version 0.71 is also a body with anything but white space after its JSON value; one that is not an object is
  400 `The request body must be a JSON object.` A wrong method is 405 with an `Allow` header, an unknown path 404 `{"error":"No such endpoint."}`, a fault of the program 500 `Internal error; see the log.`
- Every API answer is JSON, and the answers carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. No `Access-Control` header is ever sent, and under `/api/` an `OPTIONS` is refused like any other wrong method, so another page cannot post to it. (The page's own files are answered whatever the method, `OPTIONS` included, with the file and still no `Access-Control` header.)
- The page and its files are served from inside the program only, never from the file system, and a path with `..` is 404. `/api/log` and `/api/history` name their file by its bare file name, never its path.
- The settings are strict: every one of them must be in a `POST /api/settings`, else 400 `The setting X is missing.`; a flag must be true or false (`The setting X must be true or false.`); the time format `hh:mm` or `hh:mm:ss`; and the interval a whole number
  within 5 to 3600 (`The usage interval must be a whole number of seconds from 5 to 3600.`). A file that cannot be written is 500 `The settings could not be saved: <reason>`, and nothing changes.
- **The settings are checked in a fixed order and the first fault is the one reported**: the interval first, then the switches in the order of the settings table, with the time format between `showDeltaTime` and
  `historyDeltaUsed`, and `showTime`, `showUsed`, `showLimit`, `showRefresh`, `showCountdown`, `showDuration`, `stayOnTop` and `showTitleBar` last, in that order. An interval that is not a whole JSON number (`30.5`, `"30"`, `true`, a number too large) draws the same message as one out of range. A `null` counts as missing, for every key. The message for the time
  format is `The setting timeFormat must be "hh:mm" or "hh:mm:ss".`, also when it is not a text. A key that is not a setting is ignored. An interval out of range refuses the whole set before anything is written, so no other
  value of that request is kept. A set that was applied is answered with the settings as they now are, in the shape `GET /api/settings` has, which is what the view shows next.
- **The order of the checks on a request**: the path (404), then the method (405), then the `Content-Type` (415), then the size (413), then whether it is JSON (400), then the settings themselves. So a `POST` of the wrong type
  to a path that does not exist is 404. The 405 says which method is right: `{"error":"Use GET."}` with `Allow: GET` for the config, the status and the errors; `{"error":"Use POST."}` with
  `Allow: POST` for the refresh; `{"error":"Use GET or POST."}` with `Allow: GET, POST` for the settings, for the log, which takes its two choices, and (since version 0.79) for the history, which takes its one.
- **The `Content-Type` and the body, exactly.** The type is read without regard to case and by its beginning, so `application/json; charset=utf-8` passes; a request with no `Content-Type` is 415. The limit is 4,096 bytes
  exactly: 4,096 is taken and 4,097 is 413. A body with nothing in it is 400. `POST /api/refresh` does not read its body: with the right type, any body or none will do.
- **The `Host` check, exactly.** The 403 is plain text, `Forbidden`, not JSON. It is also the answer when there is no `Host` header, when the port is missing or another, and for every other way of naming this machine,
  `[::1]:<port>` and its host name among them.
- **Which requests are the API's.** Only a path that begins `/api/` is; `/api` without the slash is taken as a file of the page and is the plain 404 below. A query on the path is not looked at.
- **The files of the page.** They are `index.html`, `app.css`, `app.js` and `view.js`, and `/` is `index.html`. The type follows the ending of the name, in any case: `text/html; charset=utf-8`, `text/css; charset=utf-8`,
  `text/javascript; charset=utf-8`, `application/json; charset=utf-8` for `json`, `image/svg+xml` for `svg`, and `application/octet-stream` for anything else. They carry `Cache-Control: no-store` and
  `X-Content-Type-Options: nosniff` like the API's answers, which are `application/json; charset=utf-8`. A file that is not there is 404 with the plain text `Not found`, and so is a path with `..`, written plainly or as
  `%2e%2e`. The method is not looked at for these: a file is given whatever the method.
- **A file that is not a file is no file.** A log or a history that is there but is not an ordinary file, a directory for example, is answered as if there were none: no lines, no stamp, no change, and no error.
- **The local server is not locked.** It guards against other web pages, by the checks above, and not against other programs on the same machine: any of them can read the status and the history, change the settings and ask
  for a refresh, as on any loopback port, and there is no password and no token for it. It is therefore not for a machine shared with people who are not trusted. What it can never give away is a credential, since no answer
  holds one.

**Starting, stopping and the files**

- If the program cannot start (for example the port cannot be bound) it logs `Could not start` as an error and ends without a message in a window; if the page cannot be loaded the window is empty and `Frontend failed to load` is logged.
- When the window is closed, or the process is told to stop, the program logs `Shutting down`, `Usage refresh stopped` and `Frontend server stopped`, tells a request that is running to give up, waits up to 5 seconds for it, and then ends; the interrupted request
  adds no error, no log line of failure and no row to the history. A request cut short is not a failure.
- **The shutdown happens once, whichever way it is reached.** Closing the window and stopping the process are two ways to the same cleanup, and they can happen together — closing a window is one way of ending a process — so the lines above are written once and each resource is released once, with the second caller waiting for the first rather than doing it again. A fake backend of that run (see Fake backend) is stopped on the same path, so a run that ends either way leaves no listening socket behind.
- The history on the first run is shown from the newest row that has amounts: the percentage is worked out from `used` and `limit` when the budget is above zero, and there is no severity, so the tooltip of the percentage names none until the first refresh.
- A line of the history file is a row if it has 3 to 7 fields (older files had fewer) and a date and time; the missing fields are empty. The change in time of a row is empty if the row is earlier than the one before it (the clock was set back) or a
  time cannot be read; a change in the amount of less than half a cent counts as zero in the row and the history cell.
- The newest lines read of the log are those of its last 512 kilobytes (524,288 bytes), at most 1,000. Whenever the file is larger than that, the first line of what was read is dropped, whether or not the cut fell inside it, so that none is ever shown half, and the note says how many lines are shown. A line ends at a line feed, a carriage return, or both; an empty file is a log with no lines.
- **What a row of the history file is, exactly.** A line is cut at every comma, there being no quoting. It is a row if it has 3 to 7 fields and its first field is not blank and is not the word `datetime`; a line that begins `datetime` is a header wherever it stands. The first field is not checked to be a date and time: a row whose first field is something else is shown with that text as it is, and takes its place in the order as text. Blank lines, and lines with fewer than 3 or more than 7 fields, are left out. The count of lines in the note above the table is the count of rows, not of the lines of the file.
- **How a row's status is read.** A row is that of a failed query if its status has `failed` in it, and begins a run if its status begins `start`; any other text there is an ordinary row. The first row of a file begins a run even when nothing marks it, so the rows of an old file with no status form one run from the first; such a first row is not a startup line and is not blue; it has the amount used itself as its change, so it is no zero usage line.
- **The order of the history is that of the text.** The lines are put in order by the `datetime` as it is written, compared as text, which for the form the program writes is the order of time.
- **The first cell of a history line.** The time of day is what follows the date in the `datetime` as written; a `datetime` that has not the usual form is shown as it stands, with or without the date setting. The amounts are the file's text as written, `1000.00`, without the digit grouping the row has.
- **The line above the table, exactly.** `Showing <shown> of <all> lines: `, then `<n> zero usage` and `<n> failed`, joined by ` and ` when both are there, followed by ` hidden`; then, after a comma when something came before, `<n> older not shown`; then a full stop. A part whose count is 0 is left out, so with only older lines left out it reads `Showing 1,000 of 1,500 lines: 500 older not shown.` The counts have a comma for thousands whatever the machine's language.
- **The reading shown at startup, exactly.** It is the newest by its `datetime`, not the last line of the file, among the rows that have a `used` or a `limit`; either is enough. If that one row cannot be understood, an amount that is not a number or a time that is not `yyyy-MM-dd HH:mm:ss`, nothing is shown in advance: older rows are not tried. A file that cannot be read shows nothing and is no error. The time is the file's local time taken in the zone the machine has now; the percentage is the amount used as a share of the budget, to the nearest whole number and upwards at half (14.50 of 100.00 is 15%; exactly so since version 0.72), and there is none when an amount is missing or the budget is not above zero.
- **`interval` and `duration_ms` of a row.** The interval is the one in force at the moment the query starts. The duration is cut to whole milliseconds, not rounded.
- **`--help`, and a mistake on the command line, leave no trace**: no log file, no settings file, no window.
- **A start that fails.** `Could not start: <reason>` and `Frontend failed to load: <reason>` say what went wrong in the program's words; neither has a stack trace in the file. `Shutting down` is not written for a start that failed.
- **The order of stopping**: the refreshing, then the local server, then a fake backend, and the log last, so that the lines of the stopping are in it. The local server stops at once, without waiting for a request that is being answered. If the refreshing has not ended after the 5 seconds, the warning `The usage refresh thread did not stop in time` is written and `Usage refresh stopped` after it all the same. A cleanup that fails is not tried a second time.
- **There is no lock file beside the log** (since version 0.73): the log is the one file. Until then the logging library then in use kept one, `java-aip-usage.log.lck`, for as long as the program ran.
- **How the process ends.** With exit code 0 when the window is closed, 143 after `SIGTERM` and 130 after `SIGINT`. Where the operating system ends a process without letting it clean up, as Windows does when a process is terminated from outside, there are no lines of the stopping, as after a forced kill.

**The window**

- **Since version 0.92 the largest the window is ever made is the visible area of the screen it is on**, in place of the 2,400 by
  1,600 pixels that follow here: what the program sets, the height a list opens at, a remembered height when it is used, and a
  drag by the frame or by an edge of a window with no title bar (see Version 0.92). The rest of this point holds with the screen's
  size for those figures.
- The program never gives the window a size smaller than 160 by 32 (40 by 32 since version 0.96, see Version 0.96) or larger than 2,400 by 1,600 pixels of content, and until the page has told its size it is 420 by 50. Those limits are on what the program sets, not on what a person drags: while a panel that can be
  resized is open, the window may be dragged larger than that, as far as the desktop lets it. A remembered height is stored once the person has not changed it for half a second, as it is, with no upper limit of its own, and is
  kept only if it is more than 0; when a panel is opened at a remembered height, 1,600 is the most that is used for that opening, and the stored value stays what it was.
- The window title is `aip usage v0.37`; the program is called `java-aip-usage v0.37` in the log.
- **The program asks the page for its size about every 150 milliseconds** and gives the window a new size when the answer is a new one, so the window follows its content within a moment. The page measures its own content, never the window, so that giving the window that size does not change the answer; the same size is not set twice. The page says nothing but its size, what may be dragged and which panel is open, and the program tells the page nothing.
- **What may be dragged.** With no panel, with the cost control, and with the settings, nothing; the one exception is the width while the tab `About` of the settings is shown (since version 0.79, see The tab About can be dragged wider). With the history, the height only, the width staying what the page asked for. With the log and the error log, the height and the width. Neither can be dragged below the size the window has with no panel open, which is the size the page last asked for with none; until there has been one, the size the panel opened at is the least.
- **An answer of the page that is not a size is passed over**: one that is not text, one with a width or a height of 0 or of more than five digits, a panel name other than `history`, `log`, `errors`, `settings` and `debug`. A change of what may be dragged, or of the panel, with the same numbers, counts as a new size.
- **The room the title bar takes is measured once**, when the window is first given a size, and used from then on. Until the window has been laid out no size is set, and the next time of asking tries again.
- **A dragged height, exactly.** Only the height is watched, and only while the history or the log is shown. The half second starts again with every change by the person and is dropped whenever the program itself sets a size. A height that has settled is stored only if it is not the one stored already. A stored height equal to the one the program works out is kept and not written again.

**The look of the page**

- **Colours** follow the operating system's light or dark setting, and there is no setting for it. `muted` is `#8a8f98` in both; the green of the figures is `#1a7f37` and `#3fb950`, the red of errors `#cf222e` and `#f85149`, the blue of notes, of the selected tab, of the icon of the open panel, of the start lines of the history and of the log, and of the countdown with the automatic interval on `#0969da` and `#58a6ff`. Background and text are the system's. There is no amber, and no colour stands for a severity.
- The refresh icon turns round and round while a refresh is running. When a refresh has failed and the figures are old they are dimmed to 60 percent: the time, the amounts, the percentage, the change in the amount used, the time since the previous reading and the request duration, not the countdown, the interval or the buttons. It is the same green, less opaque, not another colour.
- The icons are: refresh, an arrow round a circle; log, a sheet with lines; history, a table; error log, a triangle with an exclamation mark; settings, a gear. An icon button has a border that is invisible and turns muted gray when the pointer is over it or it has the keyboard focus.
- A time that the backend gave no tooltip for has the tooltip `Last update`.
- Under the row, in this order, there can be the message line, the line with the message of an HTTP 429 that shows while the pointer is over the countdown, and the red line `Lost contact with the application. Still trying.`. The last one is the window's own: it shows when a request to the
  program fails, including the one for the refresh button, keeps showing the last figures, and goes with the next answer. Before the program has answered at all the window asks again every 2 seconds, the row has neither figures nor a word, and the buttons do nothing until the first answer; every request to the program
  is given up after 10 seconds.
- **The settings view:** the buttons are Save (`Save the settings and close the view`) and Cancel (`Close without changing anything`), in that order, under every tab; Save is disabled when the settings could not be read.
  The time format's two choices read `hh:mm` and `hh:mm:ss`. The interval field is 5 characters wide, right-aligned, with `s` after it. When Save is pressed the window checks that the field is a whole number
  (`The interval must be a whole number of seconds.`) and sends nothing otherwise; whether it is within 5 to 3600 is for the backend. Nothing else is bound to the keyboard: there is no Enter to apply and no Escape to cancel; the panels' lines can be reached with Tab, and the buttons say
  whether their panel is open to a screen reader.
- **The panels:** while a panel is on show it is read again when its content changes. The log and the error log are read at every poll of the status that was answered, so every second, and not at all while contact is lost; the history whenever a row has been added to its file, a row of a failed refresh as well as a reading (the status carries a stamp of the file, `historyStamp`, which changes when a row is added; it is empty when there is no file). If nothing has changed the lines are left alone; when new lines come in above, what the person
  is reading does not move, and a panel scrolled to the top stays at the top. If a read fails the old lines stay and a red line says `The usage history could not be read: <reason>` (`The log could not be read: ...`, `The error log could not be read: ...`). A panel with nothing to list shows only its note. The notes of the log are `There is no log file yet.`, `The log is empty.`
  and `Showing the newest N lines of the log.`; those of the history are written by the program, as above.
- Text the program sends is put on the page as text, never as HTML, the tooltips included.
- **Type and page.** The row is 15 pixels, weight 700, with a line height of 1.3, in the font `-apple-system, BlinkMacSystemFont, "Helvetica Neue", Helvetica, Arial, sans-serif`; the message lines are 14. Every figure that changes has digits of equal width, so that a changing
  digit does not move what is beside it. The page itself never scrolls; only the lines of a panel do. Checkboxes, the dropdown and the scrollbars follow the system's light or dark setting like the rest. The page is in English.
- **The spacing of the row.** The row and its message lines have 5 pixels above and below and 8 at the sides, so a 24 pixel button gives a row of 34. The items are 10 pixels apart, centred on each other, and none is broken in two; a row that wraps has 2 pixels between its lines. The 900 pixels of the wrap
  include the 8 at each side. The percentage and the amounts are 8 pixels apart; the ` / ` between the amounts is the page's. The percentage is never smaller or grayer than the amounts. The refresh button is drawn 7 pixels nearer the amounts than the gap, about 8 pixels from the number, and the countdown 8
  pixels nearer the button, 2 pixels from it.
- **The buttons.** The refresh button is 24 by 24 pixels with an icon of 14, a line of 1.7, in the colour of the text, and it is never green. The log, history and error log icons are 12 pixels with a line of 1.6; the gear is 13, drawn on a finer grid with a line of 2, which becomes 3.3 when its panel is open
  (the 2.2 of the others at its scale). The button of the open panel takes back the 4 pixels it grew by on every side, so that the row is neither wider nor taller and nothing beside it moves. An icon button has corners rounded by 4 pixels and no focus ring of the browser's; the border that shows is its sign of
  focus. The refresh icon turns once a second, at an even speed.
- **The room of the items that change.** The countdown has room for `-3s` to `99s`; the interval for `120s`; the change in the amount for `+9.99`; the time since the previous reading for `99s`; the request duration for `999ms` (see Less room is reserved in the row). A longer value, such as a countdown of `120s` with an interval of 120 seconds, makes the row wider for
  as long as it shows. The time, the amounts and the percentage have no room kept for them: their digits are of equal width, so a change of a digit moves nothing, and a figure that gains a digit, `99.99` becoming `100.00`, makes the row that much wider. An item whose switch is on and which has no
  value keeps that least width, not the width of the value it lacks. The percentage is the exception among the optional items: with its switch on and no percentage sent, it is not there and takes no room. A countdown the backend did not send is likewise not there.
- **Colours not said elsewhere.** The line of a 429 that shows over the countdown is weight 800, heavier than the 700 of the other message lines. A message line is 3
  pixels below the row and breaks anywhere, inside a long word if it must.
- **Before the first answer** the row shows the refresh button and all four panel buttons, and no word, no time, no figures, no countdown and no optional item. A status that says nothing of a button leaves it shown, so the three panel buttons are there until the first status that switches one off says so.
- **What the page asks the application.** At startup `GET /api/config`, once, then the status at once, and never a refresh of its own. The polls never overlap: the next one is asked for the poll interval after the one before was answered, failed or given up, so the time between two is the interval and the time
  the answer took. Pressing the refresh button sends `POST /api/refresh` with `Content-Type: application/json` and the body `{}`, does not look at what comes back, and asks for the status at once, whether or not the press got through; so does a successful Save. A status asked for at once while a poll is under
  way is not sent, and the change shows with the next poll. Every request says not to use a stored answer. Of the status the page uses what is finished for it, whether the figures are old, whether a refresh is running, and the stamp of the history; it reads none of the raw figures.
- **Lost contact, exactly.** The line shows for three failures only: the first request at startup, a poll of the status, and the press of the refresh button. A panel, the settings or an Save that fails says so in its own red line and not here. A failure is no answer within the 10 seconds, no connection, or an
  answer whose status is not a success, so an application that answers a poll with an error is "lost contact" too. The line goes with the next status that is answered. While it shows, an open panel is not read again.
- **What the application's refusal reads as.** The reason a red line gives is the `error` text of the answer when it has one, else `The application answered HTTP <status>.` With the answers the panels' files give, the line reads `The usage history could not be read: The usage history could not be read.`, and the
  same for the log.
- **The settings view, exactly.** The groups and their order: `Request`, with `Interval`, the field and `s`; `Main view`, with the thirteen checkboxes of The elements of the row in the order of the row, the time format dropdown beside `Time` and `Currency symbol` beside `Amount used`, 12 pixels from it;
  `Window`, with `Title bar` and `Stay on top`; `History view`, with `Date`, `Zero usage lines`, `Failed lines`, `Δ used`, `Δ time`; and `Log`, with `Log the response`. The text is bold, 12 pixels on a line of 1.45, in the system's own interface font, not condensed. Each group is a box with a thin gray border
  and its heading set in the border; each setting has a line of its own, the control 4 pixels from its text. The buttons are 6 pixels apart, with a gray border that takes the colour of the text under the pointer or with the focus; a disabled one is at half strength. `Save` and `Cancel` have the tooltips said above. The dropdown is at
  least 12 characters wide. The red line of the view is at its top, above the groups; it stays until the view is opened again, whatever is typed or pressed meanwhile. When the settings cannot be read it says `The settings could not be read: <reason>` and the groups are not shown at all, not shown empty; Cancel
  still works. A refusal of the backend is shown in its own words. The check of the interval field is: blanks at its ends aside, digits and nothing else, so ` 60 ` and `0060` are taken, the latter as 60, and `-5`, `5.0`, `1e3`, `60s` and an empty field are not. Save sends all sixteen settings in one request.
  The page has no defaults of its own. A setting the backend's answer lacks is shown switched off.
- **The panels, exactly.** The text is 12 pixels on a line of 15, in a fixed-width font (`ui-monospace, "Cascadia Mono", "SF Mono", SFMono-Regular, Menlo, Consolas, monospace` since version 0.80; until then `ui-monospace, SFMono-Regular, Menlo, monospace`), regular weight, the letters drawn very slightly closer. From the top: the note, the red line of a read that failed, the lines. Opening a panel, or changing to another,
  clears the lines, the note and the red line of the one before at once, so nothing of another panel is ever under the new button, and an answer that comes for a panel that is no longer the one shown is dropped. A panel with no lines shows no box of lines, only its note. The red line goes with the next read that
  succeeds, even one that brings nothing new. The log and the error log are read again at every poll, and the history when its stamp is not the one it was last read and shown at; a read that failed is tried again at the next poll, for the history as for the others, whether or not a row was added meanwhile. Only one panel is read at a time: a panel opened while another is still being read is read as soon as that read has ended, without waiting for a poll or a new row. The header of a table stays at the
  top with the page's background behind it; the log has none. The 16 pixels kept clear at the right are kept in the log and the error log as in the history. **6 pixels stand between the end of the last column and the scrollbar** (since version 0.81), in the three lists and whatever room the system's scrollbar takes, so that the `s` of `Δ time` does not touch it (in version 0.80 on macOS the `s` stood half under the scrollbar); the window is as much wider. How far the lines are moved to keep a person's place is worked out from the average height of a line.
- **The history, exactly.** The columns are, in characters: the time at least 8, or 19 with the date, and taking the room that is left; `used` 10; `limit` 10; `Δ used` 9; `Δ time` 8; `Cur.` 5; with 8 pixels between them. (Until version 0.78 the widths went by the place and the number of the columns: one change column was 9 wide whichever it was, and the last column 5 whatever it was; see The usage history has no horizontal scrollbar.) The room between
  `used` and `limit` is what an amount shorter than its column leaves, not a gap of its own. The width of the first column follows what the backend says of the date, and the red of `failed` is put on the amount used of a line the backend marks as failed (the second cell, until version 0.78): the page looks for no word, and knows a column by the kind the backend names. The header is shown only when
  there is a line. The table is measured on a copy that is not seen, holding the header and the three lines with the most characters, each time the program asks for the size; the width last measured is kept while the panel is closed. The width asked for is the larger of the row's and the table's with 16 pixels
  for the panel's sides, and the scrollbar's width, and what the list was seen to lack at that width (since version 0.79).
- **The log, exactly.** A line begins an entry when it begins with a date, a time and a blank, `yyyy-MM-dd HH:mm:ss `; every other line belongs to the entry before it. Lines at the beginning of what was read that come before the first such line, the end of an entry the 512 kilobytes cut, are kept together and
  shown at the very bottom. The start of a run is a line that has `] Starting java-aip-usage` followed by a blank or by the end of the line. The number in `Showing the newest N lines of the log.` is the number of lines received, which are the lines of the level chosen. The date that is cut from a line with `Date` off is its first 11 characters, `yyyy-MM-dd `, and only of a line that begins an entry.
- **The error log, exactly.** The time is 8 characters wide and the message as wide as its text, 8 pixels apart, both at the left; its header, `time` and `message`, stays at the top and is bold. `There are no errors in this run.` is the page's own text.
- **The sizes the page asks for, exactly.** The width and the height of the row are those of the row with its message lines and the room round them, each rounded up to a whole pixel. The height of a panel is ten times that height as it is at the moment the panel is shown, a message line that shows then
  included, and it is taken again when one panel replaces another and when the settings give one back. The log's width is three times the row's rounded width, and the scrollbar's; the error log's is one and a half times it, rounded up, and the scrollbar's. The settings are as tall as the whole page and as wide
  as the row. The scrollbar is measured once, at startup; where scrollbars lie over the content it is 0. The line of a 429 over the countdown is part of the row's height, so the window grows for it with no panel and with the settings, and not with the log, the history or the error log.
- **For a screen reader.** The message line and the line of a 429 are announced as a status, the lost-contact line and the red lines of a panel and of the settings as an alert. Each icon button is named by its tooltip, `Show…` or `Hide…`, and says whether its panel is open; the icons themselves are passed over.
  The box of a panel's lines can be reached with the keyboard and is named `Newest first`. The interval field is named `Interval in seconds, from 5 to 3600`, and the dropdown `Time format`.
- **What the page does not have.** No currency sign of its own, no window of its own that it opens, no setting it keeps, and nothing that it loads from anywhere but the application.

## Fake backend

The application carries a fake Anthropic backend of its own, so that it can be run and
watched without a paid Anthropic account, without Claude Code installed or logged in, and
without spending real requests on the usage endpoint. It is switched on in the debug settings (see Debug
mode); nothing else is needed and nothing else has to be started.

- The debug setting `Fake backend` makes the application start a fake usage server inside its own
  process and fetch from it, until the setting is switched off again, when the server is stopped. A
  server is started anew each time, on a new port. There is no second program and no second command:
  the fake backend is part of the application and is reached only in debug mode.
- It is a real HTTP server on the loopback interface, on a port the operating system chooses,
  and the application fetches from it over HTTP exactly as it fetches from Anthropic. It is not a
  substitute for the usage client fitted in place of it: the connection, the status, the headers,
  the timeouts and the refusal to follow a redirect are all the real ones, which is what makes a
  run against it worth watching. The application cannot tell that the server is fake.
- It serves `GET /api/oauth/usage`, and the one request that changes its scenario (below), and nothing
  else. Any other path is HTTP 404 with no body, a path beneath the usage path such as
  `/api/oauth/usage/extra` included, and any other method on the usage path is HTTP 405 with no body. It
  never looks at the bearer token and never reaches the network.
- Because it ignores the token, the fake backend also leaves out the token flow, exactly as the
  placeholder token does, whose setting need not be on with it (see Authentication).
- With `--anthropic-url` given as well, the URL is simply not used while the fake backend is in use,
  and is used again when it is switched off.
- Its normal answer is a plausible usage document: a `spend` object that is enabled, among the keys a
  real response carries beside it. The figures move a little on each request, so
  that the row, the history and the differences between readings all have something to show rather
  than the same numbers forever.
- It can be made to answer otherwise, so that the behaviour the requirements describe for a bad
  answer can be seen without waiting for the real endpoint to misbehave. The scenarios are at least:
  the normal answer; HTTP 401, 403, 429 (with and without `retry-after`), and a 500; a body that is
  not JSON, an empty body, a body that is JSON but has no `spend` object, and a
  body with trailing text; and an answer that is slow or never comes, past the 20 second request limit
  of The usage request. (Both take the connection at once, so neither goes past the 10 second connect
  limit; no scenario does.)
- The scenario is chosen in the debug settings, the normal answer by default, and can also be
  changed while the application runs without them so that one run can be taken through several of them. How it is
  changed is an implementation decision, provided it is on the loopback interface, is not part of the
  application's own local API, and cannot be reached from the page the application serves. **The log
  says where it listens**, so that a person can find it and change the scenario of a run that is
  already going; it is the only place that address appears, as nothing in the window mentions the
  fake backend at all.
- **How the scenario is changed, as built.** `POST /scenario` on the fake backend's own address,
  `http://127.0.0.1:<port>`, with the name of the scenario as the body, as plain text; the name is trimmed
  and read without regard to case, and no `Content-Type` is asked for. The answer is 200 with the name; 400
  `Unknown scenario "<name>"; one of <the names>` for a name it does not know, a body with nothing in it
  included, and then nothing changes; and 405 `POST a scenario name` for any other method. The new scenario
  holds from the next request: the one that applies to a request is the one in force when the request
  arrives, and a request already being held by `slow` or `hang` is not touched. A change is logged even when
  the name is the one already in force.
- **What each scenario answers** to `GET /api/oauth/usage`:

  | Scenario | Status | Answer |
  | --- | --- | --- |
  | `normal` | 200 | the usage document below |
  | `http-401` | 401 | `{"type": "error", "error": {"type": "authentication_error", "message": "fake backend"}}` |
  | `http-403` | 403 | the same with `permission_error` |
  | `http-429` | 429 | the same with `rate_limit_error`, and no `Retry-After` |
  | `http-429-retry-after` | 429 | the same, with `Retry-After: 42` |
  | `http-500` | 500 | the same with `api_error` |
  | `not-json` | 200 | the text `this is not JSON, it is a sentence` |
  | `empty` | 200 | no body |
  | `no-spend-no-windows` | 200 | `{"detail": "this is not a usage document", "limits": [], "member_dashboard_available": false}` |
  | `trailing-text` | 200 | the usage document, and on a line after it `and then some text that has no business here` |
  | `slow` | 200 | the usage document, after 25 seconds |
  | `hang` | none | nothing: the request is held for up to 5 minutes and then closed without an answer |

  Every answer has the header `request-id: fake-backend-request`, so that the log line of the request has an
  id to show. Every answer with a body is sent as `Content-Type: application/json`, the sentence that is not
  JSON included. Requests that are being held do not keep the server from answering others, and when the fake
  backend is stopped they are let go at once and closed without an answer.
- **The usage document**, whose members are, in this order, with `n` the number of documents made so far in
  the run, this one counted, so 1 for the first:

  | Member | Value |
  | --- | --- |
  | `five_hour`, `seven_day`, `seven_day_oauth_apps`, `seven_day_opus`, `seven_day_sonnet` | each `null`, as in a real response |
  | `juniper_tide`, `cedar_ember` | each `null`: stand-ins for the keys with invented names that a real response carries |
  | `extra_usage` | `is_enabled` `true`, `monthly_limit` 100000, `used_credits` and `utilization` that follow the spend (the amount used in minor units, and that as a percentage of the limit with three decimals), `currency` `USD`, `decimal_places` 2: the credit figures said a second time, as a real response says them |
  | `limits` | an empty list |
  | `spend` | `used` of 18602 + 137 × n minor units and `limit` of 100000, both `USD` with an exponent of 2; `percent`, the share used as a whole number, rounded down; `severity` `warning` from 80 percent and `normal` below; `enabled` `true`; `disabled_reason` `null` |
  | `member_dashboard_available` | `true` |
  | `seven_day_breakdown` | `null` |

  The first reading is therefore 187.39 of 1,000.00 US dollars, 18 percent, and each reading adds 1.37 to
  the amount used. The document is, key for key, what a real response is,
  awkward parts and all, because those are what a parser must get right: keys that are present and
  `null`, keys with invented names, and an `extra_usage` that says the spend again. Read by the
  application it is a reading with spend. The amount used is not held at the budget, so on a long run it passes it and the percent
  passes 100; and the severity is `normal` or `warning` only, never critical. `normal`, `trailing-text` and a
  `slow` answer that was sent share the one count, which starts again with every run.
- **A fake reading is kept like any other.** The application does not know that its backend is fake, so a
  reading from it is shown in the row, written to the usage history and counted in the differences between
  readings exactly as a real one is. The rows land in the history file of the directory the run was started
  from, mixed in with the real ones, and the only thing that tells them apart afterwards is the log of that
  run, which says the fake backend was in use (see Non-functional requirements). Someone who would rather
  not mix them starts the run in another directory, which the application neither does nor advises on its
  own: nothing is invented about the history file because the backend is.
- It ships inside the application rather than beside it, and that is the whole of its presence: it is
  started only by its option, never otherwise; nothing in the window mentions it or shows that it is in
  use; it adds no endpoint to the application's own local web server; and the log says plainly that it
  is on (see Non-functional requirements), so that a reading invented by it cannot be mistaken for a
  real one.

## Testing and verification

What the sections above require is to be held up by tests, and the few things no test can judge are to be
looked at by a person. This section says which is which, so that "it works" means something.

- **The behaviour that can be tested is tested.** The parsing of the usage response and of every
  malformed body, from fixtures of real responses; the settings file over the default; the refresh schedule, including an interval changed while a request is in flight
  and a due time that has already passed; that an HTTP 429 changes no delay with Automatic off, and the automatic search; and the move
  between fresh, stale and failed state.
- **No automated test needs an account.** Everything that talks HTTP talks to a server on loopback: the
  token flow to a stand-in for `claude`, the usage requests to doubles, and the application as a whole to
  the fake backend of Fake backend, which is the end-to-end one — a real usage client against it with a
  placeholder token, and its error, malformed, slow and hanging answers for the failure paths that would
  otherwise need the real endpoint to misbehave. The real endpoint, the real `claude` and the real window
  stay outside the suite, and are named where they are relied on instead.
- **The rules about requests are tested as rules**: that polling the status triggers no Anthropic request,
  that a manual refresh returns at once and starts at most one request and never a second while one runs,
  that the update interval can be neither changed nor saved from the window, and that an applied usage
  interval is in force at once and survives a restart.
- **The log is tested for what it must say and for what it must never say**: the lifecycle lines in their
  order, each refresh and its outcome, and the HTTP diagnostics, in the console and in the file; and the
  absence of every credential, header and response body, checked by planting credential-shaped text in a
  request, a response and an error message and finding it masked.
- **That the frontend does no calculation is itself tested**, by a check of the page scripts for date
  parsing, rounding and number formatting, since the rule is about what is absent and cannot be seen in
  any one output.
- **The frontend is tested apart from the backend**, with Node, through a task of the build
  (it runs `node --test` over the frontend's test files, and needs `node` on the `PATH`): the view logic against the shapes the
  backend sends, the page against a fake document and a fake backend, and the style sheet for the rules
  this document fixes, such as no text under 14 pixels outside the panels. The frontend keeps its own
  tests so that it stays testable if the backend is rewritten.
- **The rules of the window are tested as rules as well**: that the page gets its polling interval from the
  backend at startup; that the settings are read from the backend each time the view opens, and that Save,
  Cancel do what Settings says, and that the tabs are shown and stepped through as Settings says; that an HTTP 429 gives no message line and no dimming
  but a red countdown; that every failed refresh reaches the error log; that a remembered height is stored
  and is never smaller than the one worked out; and that a change in the amount used of zero is shown
  nowhere, in no history cell and no item of the row, while a time of zero is.
- **Where usage comes from is tested where it is decided**: the base URL and the placeholder token on the
  command line, at the one place the usage client is made, and in the lines of the log at startup; and the
  host in the message of a failure and in the log line of a request is the configured one. The fake backend
  does not take the place of the smaller doubles, which stay because they are faster.
- **A test that opens a real window is asked for, not run by default.** It puts a window on the screen for
  a few seconds, so it runs only when it is asked for by name, in the way the version's build has for that; everything else runs
  with no display, on a machine with no window server.
- **A rule that cannot fail a test is not counted.** Each new rule is to be seen failing a test when it is
  deliberately broken, which is the only evidence that the test watches it.
- **What is left is for a person, by eye, on each system the version is made for**: startup and the first reading; each panel, its size
  and its scrollbars; the history as wide as its table with every column whole, with the date and both
  change columns on and off again; the window growing and shrinking around a message; the settings whole,
  without a scrollbar, giving back the panel that was open before them; the interval applied; a manual
  refresh; a failed refresh and the quiet sign of an HTTP 429; the blue of the open panel's button and the
  blue startup lines of the history and the log on a light and a dark theme; the gear and the spacing of the icons; the width of the
  dropdown; text that can be selected in the settings; readable text
  and the time of day only; and that closing the window ends the program. These are listed because they are
  the parts no assertion covers, not because they matter less.

## Scope and limits

What the first version deliberately is not, so that none of it is taken for an oversight.

- **No installer.** No version of the program installs itself, updates itself or registers itself with the system:
  it is a program that is started, and its files are where it was started. Which systems a version is made for, and
  how it is started and handed out, is said in that version's section; Linux is a target of none so far.
- **Claude Code is the only way to sign in.** The program has no login of its own. When Claude Code is
  missing or logged out the window says what to do, and the refresh button tries again once it has been
  done; the program cannot do it for the person.
- **One program at a time.** Nothing stops a second copy from being started in the same directory; the two
  would share the settings, the history and the log, and the last to write would win.
- **A direct connection.** No proxy, as The usage request says.
- **The local server is for this machine's one user**, as The local server says.
- **The endpoint is not a published one.** `/api/oauth/usage` is the one Claude Code itself asks, and the
  default interval and the automatic search rest on how it was seen to behave, not on a documented limit.
- **No charts.** The history is kept, and shown as lines; drawing it is a future extension.

## Future extension

The project should be designed to evolve toward a richer dashboard, potentially
including:

- charts showing usage trends over time, drawn from the usage history above
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

- **The program is built with no library but the one for its window** (since version 0.71). It reads and writes JSON itself: strictly as the standard says when it reads, and in the two forms it has always written, on one line for the local API and over several lines, with a space on both sides of each colon, for the settings file and a logged response. A version in another language is to be a translation of the first, and what a library did in the one would have to be imitated in the other.
- **Money is reckoned in decimal numbers, exactly** (since version 0.72, with a type of the program's own; until then with a decimal type of the language's library). An amount is a whole number and the count of its decimals, so `186.02` is that and not a binary fraction near it. It keeps every decimal it was read with, from the response as from a history row somebody changed by hand, and only what is shown or written is rounded: to the cent, away from zero at half. It holds 18 digits; a response with an amount beyond that cannot be used, and two amounts of the history whose difference needs more have no change shown. No amount the program shows or writes is worked out with binary fractions; the one figure that is a binary fraction is how much of a cost bar is filled, from 0 to 1, which is made from the exact share.
- **Building it asks little of the machine.** Each version says the one tool that must be there to build it, and the build fetches the rest; what a version needs besides that is said in its section. For the frontend tests, in every version, `node` must be on the `PATH`.
- The fake backend (see Fake backend) is part of the application and needs no task of the build, no second entry point and no second command of its own: the option `--debug`, however the version is started, and the switch in the debug settings are the whole of it. It is in the shipped artefact, reachable only in debug mode.
- Prefer simple, testable interaction boundaries between token acquisition, usage fetching, and rendering.
- **A poll is cheap.** The window asks for the status every second for as long as the application runs, so that answer costs no more work than it has to: the figures it carries are the ones the refresh already worked out, and the history file is read again only when it has changed (its size or its time), not once a second. A panel that is open is read again on the same terms: the history when its file has changed, the log and the error log on the poll, and the lines on the page are replaced only when they differ from what is already there, so that what a person is reading does not move for an answer that says nothing new.
- Write application logs to both the console and a log file named `java-aip-usage.log` in the working directory, appending to the file on each run rather than overwriting it. Never log access tokens or other credentials.
- **The program writes its log itself** (since version 0.73), through no logging library: each line goes to the console, which is the error output of the process, and to the file, and is written through to the file at once, so the file is whole to its last line whenever it is looked at. The form of a line and the words for its level are unchanged (`SEVERE`, `WARNING`, `INFO`, `FINE`). The file holds the program's own lines only: what the runtime or the toolkit of the window say of themselves is on the console alone and not in the file.
- **One loop does the refreshing** (since version 0.76). Everything the refreshing knows, when the next request is due, the search for the interval, which request is under way, belongs to one loop, and whatever is asked of it, a manual refresh, a new interval, another source, the stopping, is handed to it and done there one after the other; a request runs beside it and hands its answer back the same way. What the window asks every second, the state, the countdown and the interval, is read from what the loop last published and never waits for it. A request that is no longer wanted, because the program stops or usage now comes from elsewhere, is told to give up, and stops waiting for the network or for `claude` at once; nothing is stopped from outside in the middle of what it does. What the person sees is unchanged: the rules of Refresh behavior hold as before.
- Each run of the log begins with the line `Logging to <the log file>`, and then a line saying the program was started, with its version
  (`Starting java-aip-usage v0.37`), and, after the lines of the settings, a line that says which file the usage history CSV is written to, as a full path, for example
  `Usage history is written to /path/to/java-aip-usage.csv`. The path is logged only here, never
  revealed to the window or any request (see The log panel). The line that says the program was started
  is the one the log panel marks as the start of a run (the log panel finds it by its wording, `Starting java-aip-usage`, so that wording and the form of the log line are part of this requirement).
  After the history path comes a line naming the base URL usage is fetched from, `Usage is fetched from https://api.anthropic.com`, on every run and not only a configured one, so that the log of any run says where
  its readings came from; while the fake backend is in use it names the address the fake backend was given, preceded by a line of its own, `The fake backend is in use: these readings are invented (debug settings,
  scenario <name>)`. When no token is obtained, a line follows: `No token is obtained: a placeholder is sent instead (debug settings, the placeholder token)`, and `(debug settings, the fake backend)` when that is what left the token out.
  The same lines, from the history path on, are written again each time the debug settings change where usage comes from.
  A change of scenario while the application runs is logged too, as `The fake backend scenario is now <name>`. These lines come after the one the log panel marks, so the marking is unaffected.
- The settings file, `settings.json` in the same directory as the usage history file, holds all the
  settings, and the two remembered heights (see Remembered heights), stored as JSON, and nothing else. If there is none at startup a new one is created with the
  defaults, and the event is logged. The full path of the settings file is logged at startup, in a line of its
  own, like that of the history file. A settings file that cannot be read, or whose content is not a JSON object, is logged and
  the defaults are used for the run, without overwriting it until a setting is applied. A single value that is not valid (the wrong type, an
  interval outside 5 to 3600, a time format other than `hh:mm` or `hh:mm:ss`, a height outside 0 to 10,000) is logged and has its default, and the other keys are kept. A key the file
  lacks has its default, and a key it has that is not a setting, such as the old `pollIntervalSeconds`,
  is ignored. The keys are `usageIntervalSeconds`, `logResponse`, `showPercentage`, `showCurrency`, `showHistoryIcon`, `showLogIcon`, `showErrorIcon`, `showInterval`, `showDeltaUsed`,
  `showDeltaTime`, `timeFormat` (the text `hh:mm` or `hh:mm:ss`), `historyDeltaUsed`, `historyDeltaTime`, `historyDate`, `historyZeroLines`, `historyFailedLines`, `showTime`, `showUsed`, `showLimit`, `showRefresh`, `showCountdown`, `showDuration`, `stayOnTop`, `showTitleBar`, `restoreWindow`, `severalViews`, `logFolder`, `historyFolder`, and since version 0.82 `showStatus`, `showDayPercentage`, `showDayUsed`, `showDayLimit`, `historyMonthly`, `historyDaily`, `historyDailyBy`, `costLimitSource`, `costWorkdayHours`, `costWorkdays`, `costWorkdaysMonth` and `costCurrency`, since version 0.88 `statusBlink`, since version 0.89 `costShowUsed`, `costShowLimit` and `costShowPercent`, since version 0.90 `locateWindow` (and `logFolder` and `historyFolder` again in the place of `dataFolder`), since version 0.91 `tooltipMillis` (and `showErrorIcon` no more), the three debug settings `fakeBackend`, `fakeScenario` and `fakeToken`, the two choices of the log panel `logLevel` (the text `error`, `warning`, `info` or `debug`) and `logDate`, the size of the lists' text `listFontSize` (a whole number from 10 to 20), `autoInterval`, the three figures of its search `autoIntervalSeconds`, `autoLowerSeconds` and `autoUpperSeconds`, and the two heights
  `historyHeight` and `logHeight`. The three files, the settings, the log and the usage history, are in the working directory the program was
  started from; where that is for a version started in its usual way is said in its section. Since version 0.81 the log and
  the usage history can be in other folders (see The folders of the log and of the usage history); the settings file stays in
  the working directory, since it says where the others are. The keys `logFolder` and `historyFolder` come after
  `severalViews`.
- **The settings file as written.** One JSON object, printed over several lines, with the keys always in the order above, the two heights last and always there, as `0` when none is remembered. The keys of the restored window (`lastPanel`, `lastTab`, `windowWidth`, `windowHeight`, `windowX`, `windowY`) come after the heights, and are written only when the program has stored them. Every save writes the whole file
  anew from what the program holds, so a key that is not a setting, the old `pollIntervalSeconds` or one added by hand, is gone after the first save, and a value that was not valid is replaced by its default there;
  storing a remembered height is such a save. The new file is written beside the old one as `settings.json.tmp`, which is not left behind whether or not the save succeeded; a save that fails leaves the file as it was.
- **How the settings file is read.** No value is turned into another: the interval and the heights must be whole JSON numbers (`"45"`, `45.5` and `true` are refused), a switch must be `true` or `false` (`"yes"` is
  refused), and the time format exactly the text `hh:mm` or `hh:mm:ss`. A key whose value is `null` is a key the file lacks: it has its default and nothing is logged. An empty file, and one that holds a list, are files that
  are not a JSON object. A file with anything but white space after its object is one that cannot be read (since version 0.71; until then what came after was passed over). A file added to by hand keeps its extra keys until the next save.
- **Applying settings that are the file's own** writes and logs nothing.
- Each line of the log begins with its time in the same form as the usage history file: the local date and time to the second, `yyyy-MM-dd HH:mm:ss`, for example `2026-10-08 16:24:53`, so the log, the history and the window agree on the clock.
- The log records startup and shutdown, each refresh and how it ended, token acquisition and rejection, settings changes, and for each request to Anthropic its status, duration, size and `request-id`. It never records a token or a header. It records no part of a response body, except that, while the setting to log the response is on, it writes the JSON of each response, pretty printed over several lines, after one entry line that says whose it is. That text is masked like everything else, so anything shaped like a credential in it is hidden before it is written, as a last line of defence. The setting is off by default.
- Every failed refresh is also written to the log, once when it happens, at the warning level, as `Usage refresh failed: <the message>` (the message without its `Refresh failed at HH:mm` wrapper), an HTTP 429 included although it has no message line. The window's own `Lost contact with the application` message is the window's only and is not logged.
- The local web server listens on the loopback interface only, on a port chosen by the operating system. It refuses a request whose `Host` header is not its own address, which stops another web page from reaching it by DNS rebinding, and it accepts a `POST` only as `application/json`, which another origin cannot send without a preflight the server never grants. No endpoint accepts, returns or logs a credential.

## The Java implementation

What holds for the version written in Java, and only for it. Everything above holds for it as well.

- **Java 25, built with Gradle.** The toolchain is declared, so Gradle fetches the JDK 25 it needs when the machine has
  none, and the bundled wrapper fetches the one Gradle version the project declares, so every machine builds it with
  the same one. Use the wrapper rather than a Gradle installed by hand.
- **Nothing has to be installed by hand to build it.** A clone and `./gradlew` are the whole of the setup; the only
  thing the build expects of the machine is a network and, for the frontend tests, `node` on the `PATH`.
- **Building and starting.** `./gradlew build` compiles the sources and runs the tests, and `./gradlew run` opens the
  window. The options of Command line are given to the `run` task after `--args`, for example
  `./gradlew run --args="--debug"`, so every option of the program can be used without a packaged launcher.
- **It also starts from an IDE as a plain `main` method**, with no module path and no VM options, as well as with
  `./gradlew run`.
- **Where the files are.** The working directory is the project root, beside `gradlew`, for `./gradlew run` and for
  an IDE run configuration whose working directory is set to it; that is where the settings, the log and the usage
  history are then.
- **The window is a JavaFX WebView.** It is a direct fit for a Java desktop application and brings no browser runtime
  of its own to ship. That choice is to be revisited only if a concrete limitation of the frontend appears, not for
  preference. JavaFX is the only library this version is built with.
- **The About view** names the runtime `Java` and the toolkit of the window `JavaFX`, each with its version; `JavaFX`
  is `unknown` in a run that has no window.
- **The name on macOS.** Started as it is, a Java program is `java` to macOS, and its menu reads `Quit org.example.UsageApp`.
  This version does for itself what a program can do there without an app bundle (see The name of the program, as the system
  shows it), and the same when it is started with `./gradlew run` and from an IDE as a plain `main` method:
  - **The entries of the menu beside the apple** read `Hide aip usage` and `Quit aip usage`. The toolkit of the window writes
    them with the name of its application, and takes a name only while it has none: the program gives its own when the
    application object is made, before the toolkit gives the name of the class.
  - **The icon in the Dock** is the gauge. Started with `./gradlew run`, the start names the icon and the toolkit follows it;
    started from an IDE, the program asks the system's own Java for it, on a thread of its own, once the window is shown. That
    part of Java is loaded for this alone.
  - **Not met by this version: the name the menu bar shows in bold beside the apple, and the name under the icon in the Dock.**
    macOS takes both from the bundle of an application, and a program started as `java` has none; nothing the program can do
    for itself changes them, and the toolkit of the window does not follow the option the start has for it. They stay `java`
    until there is an app bundle, which this version leaves out on purpose (see Systems below). It is an open point, not a
    decision against the requirement.
  - **The steps can be switched off** with `-Daipusage.macname=false`, should one of them do harm on some machine; each that
    cannot be taken is a debug line in the log and stops nothing.
  - **None of it has been seen on a Mac yet**: it was built on Windows, where the first step can be seen in the toolkit and the
    others cannot.
- **The frontend tests** are run by `./gradlew frontendTest`, a task of its own that is not part of `build`, so that
  building needs nothing but a JDK.
- **The test that opens a real window** runs only with `-Daipusage.windows=true`.
- **Systems.** It was written for macOS and is also run and tested on Windows; what is left for a person to check by
  eye is checked on each of the two. Linux is not a target. There is no packaged application: a macOS app bundle and
  a Windows installer are left out on purpose, and it is started from Gradle or from an IDE.
- **The version numbers** in this document, in "since version 0.50", are this version's.

## The Go implementation

What is asked of the version written in Go, and only of it. It is not yet built; everything above is asked of it as
well, and the analysis of what that takes is in [go-port.md](go-port.md).

- **A native program for Windows**, which is what this version is for, in one file that is handed out and started;
  and it is to be possible to build that Windows program on macOS (cross-compiled).
- **The same frontend.** The page, its style sheet and its scripts are taken over as they are, with their tests.
- **The same files.** It reads and writes `settings.json`, `java-aip-usage.log`, `java-aip-usage.csv` and
  `java-aip-usage.fake.csv` under these names and in these forms, so that it can be started where the Java version
  ran and go on from there, and the other way round.
- **A window host of its own**, which does what the Java version's does for the page; which toolkit that is, and what
  of the window's behaviour cannot be had as it is here, is to be decided for it (go-port.md, The window).
- **What it names in the About view**, in the place of `Java` and `JavaFX`, is its own runtime and its own toolkit.
- **Its own version number**, and its own way of being built, started and tested, which this section is to say once
  they are decided.
