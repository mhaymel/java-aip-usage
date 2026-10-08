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

> **Status:** feature-complete for a first run, not yet smoke-tested in the
> window. Fetching, the API, the settings and the UI are implemented and covered
> by automated tests; see the plan for what a person still has to check.

## Prerequisites

- **A JDK 25 toolchain** — the same one [`java-aip`](../java-aip) uses. Gradle
  downloads it if it is missing, so no manual JDK or Gradle install is needed;
  use the bundled `./gradlew` (Gradle 9.6).
- **The Claude Code CLI, installed and logged in.** The OAuth token is taken
  from it. Without it the window still opens, but it shows setup guidance
  instead of usage, and you can retry once you have logged in.

The first version targets macOS.

## Build and run

```sh
./gradlew build          # compile and run the tests
```

```sh
./gradlew run            # open the desktop window
```

Options go after `--args`:

```sh
./gradlew run --args="--usage-interval 10 --poll-interval 2"
./gradlew run --args="--help"
```

Or start `org.example.Main` from IntelliJ (▶ in the gutter next to `main`).

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

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/org/example/` | Application sources (`Main` window, `AppRuntime`, `LocalWebServer`, `ApiHandler`, `Logging`) |
| `src/main/java/org/example/usage/` | Usage model, parser, HTTP client, refresh service |
| `src/main/java/org/example/settings/` | Interval settings, `settings.json`, command-line options |
| `src/test/frontend/` | Node tests for the frontend |
| `src/main/java/org/example/token/` | OAuth token acquisition via the Claude Code CLI |
| `src/main/resources/web/` | Frontend: HTML, CSS, JavaScript |
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
