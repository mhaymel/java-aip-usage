# java-aip-usage

A small Java client that monitors Anthropic OAuth usage. It fetches the
current usage snapshot from `https://api.anthropic.com/api/oauth/usage` and
displays it in a minimal JavaFX WebView window, refreshed on a timer.

Depending on the account, the snapshot carries either usage-based spend
(`used`, `limit`, `currency`, `percent`, `severity`) or the Pro/Max plan
windows (`window`, `utilization`, `resets_at`) — the UI shows whichever shape
the endpoint returns. When a refresh fails the last good data stays on screen,
marked stale, with the error.

It doubles as a playground for exercising the neighboring
[`java-aip`](../java-aip) tooling and reusing auth patterns from the sibling
repos.

- [Requirements](docs/requirements.md) — what the tool must do
- [Implementation plan](docs/implementation-plan.md) — how it is to be built

> **Status:** first version complete. It has run end to end on macOS against a
> usage-based account: startup, scheduled and manual refresh, saving a setting,
> a missing Claude Code CLI, and clean shutdown. The Pro/Max plan-window view has
> only been exercised with test data. See [Known limitations](#known-limitations).

## Prerequisites

- **A JDK 25 toolchain** — the same one [`java-aip`](../java-aip) uses. Gradle
  downloads it if it is missing, so no manual JDK or Gradle install is needed;
  use the bundled `./gradlew` (Gradle 9.6).
- **The Claude Code CLI, installed and logged in**, with `claude` on the `PATH`
  of the shell you start this from. The OAuth token is taken from it (see
  [Where the token comes from](#where-the-token-comes-from)). Without it the
  window still opens, but it shows what to do instead of usage, and the Refresh
  button tries again.
- **Direct network access to `api.anthropic.com`.** Proxies are not used.

The first version targets macOS.

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

Or start `org.example.Main` from IntelliJ (▶ in the gutter next to `main`). Set
the run configuration's working directory to the project root so the log and
settings land there, and check that IntelliJ's environment finds `claude`: a
program started from an IDE or the Finder can have a shorter `PATH` than your
terminal, which shows up as "Claude Code could not be found on the PATH".

The frontend's logic is tested with Node, outside the JVM build:

```sh
./gradlew frontendTest     # needs node on the PATH; same as: node --test src/test/frontend
```

If Gradle reports `problem occurred starting process 'command 'node''`, its
daemon was started without `node` on its `PATH`; run `./gradlew --stop` from a
shell that has it and try again.

Closing the window terminates the program, including the backend server and
its scheduled work.

## Configuration

Two independent intervals, both settable on the command line and from the UI.
For each, the command line wins over the saved setting, which wins over the
default:

| Setting | CLI option | Default | Range |
| --- | --- | --- | --- |
| Backend usage fetch from Anthropic | `--usage-interval <seconds>` | 30 s | 5–3600 |
| UI polling of the local backend | `--poll-interval <seconds>` | 1 s | 1–60 |

Only the backend talks to Anthropic; UI polls read local state and never
trigger a request. A manual refresh button fetches immediately, and refreshes
never overlap.

Changes committed in the UI are persisted to `settings.json` and replace the
corresponding CLI value for the rest of the run. A CLI value is not saved on its
own, so the file keeps what you last chose in the window. The file holds only the
two intervals, never a credential.

The window talks to the application through a small local API, described in
[docs/api.md](docs/api.md).

## Where the token comes from

The OAuth token is the one Claude Code itself uses, and the application gets it
the way [`java-aip`](../java-aip) does:

1. It starts a small server on `127.0.0.1` and runs `claude -p ping` with
   `ANTHROPIC_BASE_URL` pointing at it.
2. The server answers `claude` itself, so nothing is sent to Anthropic and
   nothing is billed, and reads the credential out of the request.
3. The token is kept in memory only and reused for every usage request. If
   Anthropic answers HTTP 401, a fresh one is obtained and the request retried
   once. Nothing else triggers a new token.

This takes about a second, at startup and after a 401. If `ANTHROPIC_API_KEY` or
`ANTHROPIC_AUTH_TOKEN` is set, the application refuses to start the capture,
because `claude` would send that instead of its login token: unset it and restart.

## Logging

Everything is written to the console and appended to `java-aip-usage.log` in the
working directory. The file is never rotated; delete it when it gets large.

```
2026-10-08T14:15:15.202Z INFO    [ApiHandler] Manual refresh requested
2026-10-08T14:15:15.804Z INFO    [UsageClient] GET api.anthropic.com/api/oauth/usage -> HTTP 200 in 602 ms (992 bytes, request-id req_011C…)
2026-10-08T14:15:15.805Z INFO    [UsageService] Usage refresh succeeded
```

It records startup and shutdown, each refresh and its outcome, token acquisition
and rejection, settings changes, and for each HTTP request the status, duration,
size, and Anthropic's `request-id` (useful if you ask support about a request).
Status polls from the window are not logged: they arrive every second.

It never records the token, any header, or any part of a response body. Beyond
not writing them in the first place, every log line passes through a filter that
masks anything shaped like a credential, so one that slipped into an error message
would still not reach the file.

## Known limitations

- **macOS only.** Windows and Linux are untested. A Go rewrite is the plan for
  Windows, which is why the frontend is kept independent of the Java backend.
- **Needs Claude Code.** There is no other way to sign in. The window helps when
  it is missing, but cannot fix it for you. Changing your `PATH` needs an
  application restart, because a running program keeps the `PATH` it started with.
- **Direct connection only, by design.** `HTTPS_PROXY` is deliberately not read,
  so a network that requires a proxy will show "Cannot reach api.anthropic.com".
- **An undocumented endpoint.** `/api/oauth/usage` is the one Claude Code polls,
  not a published API. If its format changes you get an error naming the problem
  rather than wrong numbers, but you may need a parser update.
- **Plan windows are tested with fixtures only.** Verified live against a
  usage-based account; the Pro/Max view has not met a real Pro or Max account.
- **Not a locked-down server.** The local API is open to any program on the same
  machine, as it is on any loopback port; other web pages are refused (see
  [docs/api.md](docs/api.md)). Do not run it on a machine shared with people you
  do not trust.
- **One instance at a time.** Nothing stops two copies from starting; they would
  share `settings.json` and the log, and the last write wins.
- **No packaged app.** `./gradlew run` is the way to start it. A macOS app bundle
  is deliberately left out of the first version.
- **No charts or history.** Only the latest reading is kept, in memory.

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/org/example/` | Application sources (`Main` window, `AppRuntime`, `LocalWebServer`, `ApiHandler`, `Logging`, `Redaction`) |
| `src/main/java/org/example/token/` | OAuth token acquisition via the Claude Code CLI |
| `src/main/java/org/example/usage/` | Usage model, parser, HTTP client, refresh service |
| `src/main/java/org/example/settings/` | Interval settings, `settings.json`, command-line options |
| `src/main/resources/web/` | Frontend: HTML, CSS, JavaScript |
| `src/test/java/`, `src/test/frontend/` | JUnit tests, and Node tests for the frontend |
| `docs/` | Requirements, implementation plan, and the local API contract |
| `build.gradle.kts` | Build config — JDK 25 toolchain, JavaFX, JUnit 6 |
| `settings.json` | UI-edited settings, written at run time (git-ignored) |
| `java-aip-usage.log` | Append-mode run log, written at run time (git-ignored) |

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
