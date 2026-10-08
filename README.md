# java-aip-usage

A small Java client that monitors Anthropic OAuth usage. It fetches the
current usage snapshot from `https://api.anthropic.com/api/oauth/usage` and
presents it in a minimal desktop window, refreshed continuously (30 s by
default).

Depending on the account, the snapshot carries either usage-based spend
(`used`, `limit`, `currency`, `percent`, `severity`) or the Pro/Max plan
windows — both are displayed.

It doubles as a playground for exercising the neighboring
[`java-aip`](../java-aip) tooling and reusing auth patterns from the sibling
repos.

- [Requirements](docs/requirements.md) — what the tool must do
- [Implementation plan](docs/implementation-plan.md) — how it is to be built

> **Status:** scaffold. The Gradle build and a `Main` entry point are in
> place; the client itself is still to be written.

## Requirements

Requires a JDK 25 toolchain — the same one [`java-aip`](../java-aip) uses.
Gradle will download it if it is missing, so no manual JDK or Gradle install
is needed; use the bundled `./gradlew` (Gradle 9.6).

The first version targets macOS.

## Build and run

```sh
./gradlew build          # compile and run the tests
```

```sh
./gradlew run            # not wired up yet; run Main from the IDE for now
```

Until a `run` task exists, start `org.example.Main` straight from IntelliJ
(▶ in the gutter next to `main`).

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/org/example/` | Application sources |
| `docs/` | Requirements and implementation plan |
| `build.gradle.kts` | Build config — Java plugin, JDK 25 toolchain, JUnit 6 on the test path |

## Related repos

The sibling repositories next to this one are a source of knowledge and
reusable ideas — not a hard dependency:

| Repo | Why it is useful |
| --- | --- |
| [`java-aip`](../java-aip) | The CLI this project exercises; see its README and `docs/` |
| [`java-claude-login`](../java-claude-login) | Login/auth flow references |
| [`java-claude-code-fetch-oauth-token`](../java-claude-code-fetch-oauth-token) | OAuth token retrieval patterns |
| [`java-claude-code-model-list`](../java-claude-code-model-list) | Data-fetching and output formatting examples |
| [`java-interceptor`](../java-interceptor) | Request/response interception and debugging |
| [`doc-and-ref-repos`](../doc-and-ref-repos) | Specs and reference material |
