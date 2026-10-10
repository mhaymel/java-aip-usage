# java-aip-usage

A small Java client that monitors Anthropic OAuth usage. It fetches the
current usage snapshot from `https://api.anthropic.com/api/oauth/usage` and
shows it in a compact JavaFX WebView window, refreshed on a timer.

It shows the spend of the account (`used`, `limit`, `currency`, `percent`,
`severity`). When a refresh fails the last good figures stay on screen,
dimmed, with the error underneath.

## The window

A status strip to keep beside your work: one row, as small as its content, with
bold readable text. From the left it shows when usage was last refreshed, what has
been spent and the budget, a refresh button, the countdown to the next refresh, and
small buttons for the log, the usage history, the error log and the settings. Hover
over an item to see what it is.

Everything the window shows and does — the row, the panels, the settings, the
messages and every state — is specified in the
[requirements](docs/requirements.md#compact-window), and only there.

It doubles as a playground for exercising the neighboring
[`java-aip`](../java-aip) tooling and reusing auth patterns from the sibling
repos.

- [Requirements](docs/requirements.md) — what the tool must do
- [Implementation plan](docs/implementation-plan.md) — how it is to be built

> **Status:** first version complete. It has run end to end on macOS against a
> real account: startup, scheduled and manual refresh, saving a setting,
> a missing Claude Code CLI, and clean shutdown. See [Known limitations](#known-limitations).

## Prerequisites

- **A JDK 25 toolchain** — the same one [`java-aip`](../java-aip) uses. Gradle
  downloads it if it is missing, so no manual JDK or Gradle install is needed;
  use the bundled `./gradlew`.
- **The Claude Code CLI, installed and logged in**, with `claude` on the `PATH`
  of the shell you start this from. The OAuth token is taken from it, which takes
  about a second at startup. `--fake-backend` (below) runs without it.
- **Direct network access to `api.anthropic.com`.**

The first version targets macOS. What the application does when one of these is
missing is in the requirements
([Authentication](docs/requirements.md#authentication) and Details of the behaviour).

## Build and run

```sh
./gradlew build          # compile and run the tests
```

```sh
./gradlew run            # open the desktop window
```

Run it from the project directory, in a terminal where `claude` works. The log
and `settings.json` are written to the directory you start it from, which is the
project root for `./gradlew run`.

Options go after `--args`:

```sh
./gradlew run --args="--usage-interval 10 --poll-interval 2"
./gradlew run --args="--help"
```

### Running without Anthropic

One option runs the whole application against a fake Anthropic backend it starts
itself, so it needs no account, nobody logged in and no network, and spends nothing
on the real endpoint:

```sh
./gradlew run --args="--fake-backend"
```

The readings are invented, and the rows land in the usage history of whatever
directory you start it from — start it somewhere else if you would rather not mix
them with your real ones.

`--fake-scenario <name>` says what it should answer, and the scenario can be changed
while the application runs; the log line
`Fake backend listening on http://127.0.0.1:<port>` gives the port:

```sh
./gradlew run --args="--fake-backend --fake-scenario http-429-retry-after"
curl -X POST --data "http-500" http://127.0.0.1:<port>/scenario
```

The scenario names, `--anthropic-url` and `--fake-token` are described in the
requirements ([Fake backend](docs/requirements.md#fake-backend) and Command line),
and `--help` lists every option.

Or start `org.example.Main` from IntelliJ (▶ in the gutter next to `main`). It needs no
VM options or module path. Set the run configuration's working directory to the
project root so the log and settings land there, and check that IntelliJ's
environment finds `claude`: a program started from an IDE or the Finder can have a
shorter `PATH` than your terminal, which shows up as "Claude Code could not be found
on the PATH".

Started that way JavaFX sits on the plain classpath, so two warnings appear that
`./gradlew run` avoids: JavaFX's "Unsupported JavaFX configuration: classes were
loaded from 'unnamed module'", and the JDK's note that native access will need to be
enabled in a future release. Both are harmless. To silence the second, add
`--enable-native-access=ALL-UNNAMED` to the run configuration's VM options.

The test that opens the real window (the panels, and the size the page asks for) runs only when asked for, since it puts a window on the screen for a few seconds:

```sh
./gradlew test --tests '*PanelWindowTest' -Daipusage.windows=true
```

The frontend's logic is tested with Node, outside the JVM build:

```sh
./gradlew frontendTest     # needs node on the PATH; same as: node --test src/test/frontend
```

If Gradle reports `problem occurred starting process 'command 'node''`, its
daemon was started without `node` on its `PATH`; run `./gradlew --stop` from a
shell that has it and try again.

## Configuration

Settings are changed in the window, behind the gear, and saved in `settings.json`.
A few things are command-line options for one run only, given after `--args` as
above. What each setting and option does, which wins over which, and what is saved
are specified in the requirements
([Refresh behavior](docs/requirements.md#refresh-behavior), Settings and Command line).

The window talks to the application through a small local API, described in
[docs/api.md](docs/api.md).

## Known limitations

What the program deliberately does not do is in the requirements
([Scope and limits](docs/requirements.md#scope-and-limits)). What follows is what
has not been verified:

- **macOS only.** Windows and Linux are untested.
- **Only spend is shown.** The session and weekly limits of a Pro or Max plan
  are not read; such an account shows the spend it reports, or `No usage reported`.
- **The rate limit is an inference.** The usage endpoint seems to accept about one
  request a minute over the long run, after a burst of about ten, and restarting the
  application repeatedly can draw HTTP 429 sooner, since each start fetches at once.
  This is from observed behaviour, not a published limit.
- **An undocumented endpoint.** `/api/oauth/usage` is the one Claude Code polls,
  not a published API. If its format changes you may need a parser update.
- **Not checked:** which signal an IDE's stop button sends, and how the usage
  history file opens in Excel.

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/org/example/` | Application sources (`Main` window, `WindowFit`, `AppRuntime`, `LocalWebServer`, `ApiHandler`, `Logging`, `Redaction`) |
| `src/main/java/org/example/token/` | OAuth token acquisition via the Claude Code CLI |
| `src/main/java/org/example/usage/` | Usage model, parser, HTTP client, refresh service |
| `src/main/java/org/example/settings/` | Interval settings, `settings.json`, command-line options |
| `src/main/resources/web/` | Frontend: the strip with its log and history panel (`index.html`), with its CSS and JavaScript |
| `src/test/java/`, `src/test/frontend/` | JUnit tests, and Node tests for the frontend |
| `docs/` | Requirements, implementation plan, and the local API contract |
| `build.gradle.kts` | Build config — JDK 25 toolchain, JavaFX, JUnit 6 |
| `settings.json` | UI-edited settings, written at run time (git-ignored) |
| `java-aip-usage.log` | Append-mode run log, written at run time (git-ignored) |
| `java-aip-usage.csv` | The usage history, one row per refresh, written at run time (git-ignored) |

## Related repos

The sibling repositories next to this one are a source of knowledge and
reusable ideas — not a hard dependency. In particular, the token-fetching code
is adapted from `java-aip` into this repo rather than called across the
directory boundary:

| Repo | Why it is useful |
| --- | --- |
| [`java-aip`](../java-aip) | Endpoint behavior, response mapping, token patterns |
| [`java-claude-login`](../java-claude-login) | Login/auth flow references |
| [`java-claude-code-fetch-oauth-token`](../java-claude-code-fetch-oauth-token) | OAuth token retrieval patterns |
| [`java-claude-code-model-list`](../java-claude-code-model-list) | Data-fetching and output formatting examples |
| [`java-interceptor`](../java-interceptor) | Request/response interception and debugging |
| [`doc-and-ref-repos`](../doc-and-ref-repos) | Specs and reference material |
