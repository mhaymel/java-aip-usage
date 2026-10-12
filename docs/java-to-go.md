# Java to Go: preparing the Java program for the port

A plan for changing the Java program so that it leans only on what Go also has. The Go version is then a
translation of this code, not a new design. The task is [new-requirements.md](new-requirements.md); the analysis it
builds on is [go-port.md](go-port.md), whose problem numbers are used here.

It is written against version 0.70. **Built so far: phase 1 (version 0.71)**; how it went is phase 83 of
[implementation-plan.md](implementation-plan.md).

## What was decided

| Question | Decision |
| --- | --- |
| Goal | The Java code becomes the template for the Go code: the same structure and the same behaviour. |
| JavaFX | Kept entirely. The window code is not touched; the window is designed anew during the port (problems 1 to 8 of go-port.md stay open). |
| Jackson | Replaced by JSON code of the program's own. No jar is left besides JavaFX. |
| JDK parts replaced | `BigDecimal`; the texts that come from exceptions; `Process.descendants()`; the logging system with its lock file; the lock, timed wait and interrupts of `UsageService`. |
| Behaviour | Changes only where it cannot be kept without Java. Each change is written into requirements.md. |
| Lock file of the log | Dropped, with its requirement. |
| Systems | Windows and macOS, as now. What differs per system is tested on Windows here; the macOS side is to be tried by hand. |
| Order | This plan first. Nothing is implemented before it is approved. |

## The phases at a glance

| # | Phase | Replaces | Solves in go-port.md | Size | Risk |
| --- | --- | --- | --- | --- | --- |
| 1 | JSON of the program's own | Jackson (`ObjectMapper`, `JsonNode`, `ObjectNode`, annotations) | 10, 11, 12 | large | medium |
| 2 | Money as a decimal of the program's own | `BigDecimal`, `RoundingMode`, `DecimalFormat`, `Double` amounts | 9 | medium | medium |
| 3 | Logging of the program's own | `java.util.logging`, `System.Logger`, `ShutdownSafeLogManager`, the `.lck` file | 22, part of 20 | medium | low |
| 4 | Texts of the program's own for every failure | `e.getMessage()`, `getSimpleName()`, stack traces in the log | 17 | medium | low |
| 5 | Stopping the process tree | `Process.descendants()` | 18 | small | medium |
| 6 | The refresh service as one loop with a queue | `ReentrantLock`, `Condition`, `Thread.interrupt()` | 13 | large | high |

The order is the one of the table. Phase 4 needs phase 1 (the text of a JSON that cannot be read is then the program's
own) and phase 3 (how a failure is written to the log). Phase 6 is last because it is the riskiest and gains from the
texts and the logging being settled.

**Each phase** is done the way the phases of implementation-plan.md were: the tests first where a rule changes, the
whole suite green (`./gradlew build` and `./gradlew frontendTest`), a run with `--debug` against the fake backend, the
changed sentences in requirements.md, an entry in implementation-plan.md numbered on from phase 81, and the version
raised by 0.01. A phase is one commit and can be undone alone.

## Phase 1: JSON of the program's own

**Now.** Jackson is used in five classes (`ApiHandler`, `SettingsStore`, `UsageParser`, `UsageClient`,
`StatusDisplay`) and three tests (`ApiTest` with about a hundred uses, `SettingsStoreTest`, `FakeBackendTest`):
`readTree` in 14 places, one `writeValueAsString` that turns the records of `ApiHandler` into JSON by reflection, two
pretty printers, `FAIL_ON_TRAILING_TOKENS`, six `@JsonProperty` and one `@JsonInclude`.

**To build**, in a new package `org.example.json`:

- **`Json`, the value**: a sealed type with object (keys in the order they were read or put), array, text, number, true
  and false, null. A number keeps the text it was written with, so `1000.00` stays `1000.00` and `45.0` is not taken for
  45. The questions `SettingsStore` asks today have the same names: whether a number is whole, whether it fits an `int`
  or a `long`.
- **`JsonReader`**: strict, as RFC 8259 says. It refuses text after the value, a comment, a single quote, a trailing
  comma, `NaN`, a control character in a text. A key that comes twice has its last value, as now. A failure says what
  and where in words of the program's own (`line 3, column 14: a value was expected`), which is the text phase 4 needs.
- **`JsonWriter`**: the compact form the API sends, and the form over several lines that `settings.json` and the logged
  response have today: `"key" : value`, two spaces, the same line endings. Text is escaped as Jackson escapes it (`<`,
  `>` and `&` are written as they are).
- **Records to JSON, written out**: each of the roughly thirty body records of `ApiHandler`, and `StatusDisplay`, gets
  a `toJson()` that builds its object key by key, in the order of today. Nothing is done by reflection, so every key
  the API sends is to be seen where it is written; the names `@JsonProperty` gave (`delta_used`, `fetched_at`) are
  written there as text. A null is written as `null`, as now. `send` takes a `Json` and no longer any object.

**Go.** The value, the reader and each `toJson()` translate line by line; or Go's `encoding/json` is used where it is shown to behave
the same. Either way the three differences of go-port.md (keys matched without regard to case, one wrong type failing
the whole file, the form as written) are no longer open, because the Java program no longer relies on a library for
them.

**How it is checked.** Jackson stays for the length of this phase as a test dependency only. One test reads and writes
the same documents with both, the fixtures, every answer of the API and a `settings.json` of every kind, and compares
the bytes. When it agrees, the three tests are moved to the program's own JSON and Jackson is removed from
`build.gradle.kts`.

**What changes for the person.** The wording after `The usage response is not valid JSON:`, which was Jackson's.
And, seen only when it was built: Jackson passed over whatever came after the JSON value of a settings file or of a
`POST` body; the strict reader refuses such a file and such a body.

**As built (version 0.71).** As planned, with two things settled on the way: the value is one class, `Json`, with a
kind, which in Go is one struct; and `JsonException` is an `IOException`, so that the places that read a file and its
JSON in one step keep one failure.

## Phase 2: money as a decimal of the program's own

**Now.** A `Spend` holds `Double used` and `Double limit`. `UsageParser` scales `amount_minor` by its exponent through
`BigDecimal` into a `double`; `Formatting`, `UsageHistory`, `HistoryDeltas`, `CostPanel`, `CostControl`, `SettingsStore`
and `ApiHandler` go back through `BigDecimal` to round at half upwards, to subtract, to divide and to write
`1,000.00`. The cost limits and the fake backend's figures are whole cents already.

**To build.**

- **`Amount`**: a small decimal of the program's own, a whole number (`long`) and the count of its decimals, so that
  `186.02` is 18602 with 2, and a row changed by hand to `1.005` is 1005 with 3. It keeps every decimal it was read
  with, as `BigDecimal` does today, and only a result is rounded; plain cents would not do that. In it, and nowhere
  else, is everything that is done with money: reading an amount from the history, from a typed limit and from
  `amount_minor` with its exponent; adding and subtracting exactly; rounding to two decimals at half upwards; the
  percentage of a limit; writing it plain (`186.02`), grouped (`1,000.00`) and with its sign (`+0.05`). An amount too
  large for it is refused where it is read, as a number that cannot be understood.
- **`Spend`, `HistoryDeltas.Delta`, the bodies of `ApiHandler`** hold an `Amount`. Where the API sends an amount as a
  number (`used`, `limit`, `delta_used`), it is written in the form it has today (`186.02`, `1000.0`), so api.md does
  not change.
- `java.math` and `java.text.DecimalFormat` are no longer imported anywhere outside the tests.

**Go.** A struct of an `int64` and an `int`, and the same functions. No decimal library is needed.

**How it is checked.** `FormattingTest`, `HistoryDeltasTest`, `CostPanelTest`, `CostControlTest`, `UsageParserTest`
and `UsageHistoryTest` hold the rules and must pass unchanged in what they expect. For the length of the phase a test
compares `Amount` with `BigDecimal` over the edges and over random amounts: `0.005`, `1.005`, a negative change that
rounds to zero, amounts with a different count of decimals subtracted from each other, an exponent of 0 and of 3, the
largest amount.

**What changes for the person.** Nothing: a history row with more than two decimals is taken exactly, as today.

## Phase 3: logging of the program's own

**Now.** Twelve classes log through `System.Logger`, which hands on to `java.util.logging`. `Logging` installs a
console handler and a `FileHandler`, and `ShutdownSafeLogManager` keeps the JDK from closing the log before the lines
of the stopping are written. That is why `Main` may not be an `Application` and `UsageApp` may not be loaded before
`Logging` is installed. The `FileHandler` keeps `java-aip-usage.log.lck`.

**To build.**

- **`Log`**: four levels (`error`, `warning`, `info`, `debug`), one line format (the one of today, with the time as
  `yyyy-MM-dd HH:mm:ss`), two places it writes to at once: the console and the file opened for appending, as UTF-8.
  One lock round a write. `close()` flushes and closes, twice without harm.
- The twelve classes call `Log` in the place of `System.Logger`.
- **Removed**: `ShutdownSafeLogManager`, the `java.util.logging.manager` property, the warning `Logging was already in
  use…`, the lock file, its line in `.gitignore` and its requirement (Details of the behaviour, "While the program runs
  there is a lock file beside the log").
- The masking (`Redaction`) is applied where a line is written, as now.

**Go.** A small package of the same shape, or `log/slog` with a handler that writes this line.

**How it is checked.** `LoggingTest`, `LoggingEndToEndTest`, `LoggingTimestampTest`, `ShutdownHookTest` and `LogTailTest`
hold what the log must say and must never say. They are kept; what they ask of `java.util.logging` itself is rewritten
against `Log`.

**What changes for the person.** No lock file. And lines that JavaFX itself logs, such as its warning about the
unnamed module, are no longer in the log file, only on the console, since they do not come through the program's
`Log`; the file then holds the program's own lines only, as the Go version's will.

## Phase 4: texts of the program's own for every failure

**Now.** About thirty places end a message with the text of a Java exception or the name of its class. Some reach the
window, most only the log:

| Where | What is shown today | Reaches |
| --- | --- | --- |
| `UsageClient` (a request that fails) | the text of the `IOException`, or the name of its class | window, error log, log |
| `UsageParser` | Jackson's text | window, log (done in phase 1) |
| `UsageService` (an error nobody expected) | `Unexpected error (<class name>); see the log.` and a stack trace | window, log |
| `ClaudeTokenProvider` (capture server, starting `claude`) | the text of the `IOException` | window, log |
| `UsageApp` | `Could not start` with a stack trace; `Frontend failed to load` with the error | log |
| `SettingsStore`, `IntervalSettings`, `AppRuntime`, `ApiHandler` (files) | `Could not …: ` and the text of the `IOException`, some with a stack trace | log |

**To build.**

- **`Failure`**: one function that names what went wrong in a fixed set of texts: the file is not there, access is
  denied, the file is in use, the disk is full; the connection was refused, the host is not known, the connection was
  closed, the answer was not secure (TLS); anything else, `input or output failed`. It is the only place that looks at
  the kind of an exception.
- Every place of the table takes its text from it. No message is built from `getMessage()` of an exception that is not
  the program's own, and none from a class name.
- **No stack trace is written.** A failure is logged as one line, the message and what caused it, joined with `: ` (as
  Go writes a wrapped error). In debug mode the stack trace is still printed, to the console only, as a help while the
  Java version is worked on.
- requirements.md: the sentence "`Could not start` carries the stack trace" and each place where an example shows a
  Java text.

**Go.** The same function over `errors.Is` and `errors.As` (`fs.ErrNotExist`, `fs.ErrPermission`,
`syscall.ECONNREFUSED`, `*net.DNSError`), with the same texts.

**How it is checked.** A test per kind that makes the real failure (a file that is not there, a directory in the place
of a file, a port nobody listens on, a host name that does not exist, a server that closes the connection) and expects
the fixed text; and a check of the sources, like the one for the frontend's arithmetic, that no `getMessage()` of a
foreign exception is left in a message.

**What changes for the person.** The detail after the colon in the messages of a failed request and of a file that
cannot be written. The messages become the same on Windows and macOS, which they are not today.

## Phase 5: stopping the process tree

**Now.** `ClaudeTokenProvider.terminate` kills `claude` and everything it started with
`process.descendants().forEach(ProcessHandle::destroyForcibly)`. Go has no such call.

**To build.**

- **`ProcessTree.kill(pid)`**, with two ways behind it, both of which Go can do the same:
  - Windows: `taskkill /PID <pid> /T /F`, which ends the process and its children.
  - macOS and other systems: the children are found with `pgrep -P <pid>`, level by level, and ended from the
    deepest up with `kill -9`.
- `terminate` calls it and then waits the two seconds as now. A command that is missing or fails is logged at debug
  level, and the process itself is still ended directly.

**Go.** The same two commands through `os/exec`. A job object on Windows and a process group on macOS would be the
native way and can take the place of the commands later behind the same function.

**How it is checked.** The stand-in `FakeClaude` gets a mode in which it starts a child that sleeps; the test asks for
the token, lets it time out and expects that neither process is left. It runs on Windows here. On macOS it is run by
hand on the Mac (`./gradlew test --tests '*ClaudeTokenProviderTest'`), and the phase is not finished before that run
has passed.

**What changes for the person.** Nothing.

## Phase 6: the refresh service as one loop with a queue

**Now.** `UsageService` (460 lines) guards its state with a `ReentrantLock`, waits for the next refresh with
`Condition.awaitNanos`, and is woken by `signalAll` in five places. A request in flight is given up by
interrupting the thread, and `AppRuntime` and `ClaudeTokenProvider` look at the interrupt flag. None of this exists in
Go.

**To build.**

- **One thread owns the state.** Whatever is asked of the service (refresh now, a new interval, automatic on or off,
  another source, stop) is put in a queue as a message. The thread takes the next message or waits until the next
  refresh is due, whichever comes first (`BlockingQueue.poll` with a time; in Go, `select` over a channel and a
  timer). No lock, no condition.
- **What is read from outside** (the status poll, every second) is one unchangeable snapshot the loop replaces after
  each step, held in an `AtomicReference`. A poll never waits for the loop.
- **Giving up is said, not interrupted.** A small `Cancel` (in Go, `context.Context`) is handed to the request and to
  the token flow. The usage request is sent with `sendAsync` and cancelled through it; `ClaudeTokenProvider` waits on
  the credential or the cancel, whichever comes first. `Thread.interrupt()` and the interrupt flag are no longer used
  by the program.
- **The request runs beside the loop**, so that the loop can still take a message while one is in flight; its result
  comes back as a message. The rule that there is never a second request while one runs is then a fact of the loop's
  state, not of a lock.
- Stopping: the stop message, cancel, and the wait of 5 seconds for the thread, with the same two lines in the log.

**Go.** One goroutine with `select`, a channel of messages, `context` for giving up, `atomic.Pointer` for the
snapshot. `UsageService` in Go is this class translated.

**How it is checked.** `UsageServiceTest` (1,000 lines), `RefreshScheduleTest`, `IntervalSearchTest` and the parts of
`ApiTest` about refreshing are the specification and must pass in what they expect; where a test reaches into the lock
or interrupts a thread itself, it is rewritten to say the same through the new means. Each of the rules of Testing and
verification ("a manual refresh returns at once and starts at most one request", "an interval changed while a request
is in flight") is broken once on purpose to see its test fail.

**What changes for the person.** Nothing is meant to. This is the phase where a change that was not meant is most
likely, which is why it is last and alone.

## What is left as it is, and why

| Java | Why it stays |
| --- | --- |
| JavaFX, and with it `UsageApp`, `StageSwap`, `WindowDrag`, `Desk`'s clipboard, `AppIcons`, the tooltips | Decided. The window is the port's own work. |
| `HttpServer`, `HttpClient`, virtual threads | Go's `net/http` and goroutines are the same thing. Redirects and the proxy (problems 14, 15) are settings in Go, not code here. |
| `java.time`, `DateTimeFormatter` | Go has layouts for the three forms used. |
| `java.util.regex` | The seven patterns use nothing Go's engine lacks. |
| Records, `Optional`, `CompletableFuture` in `CredentialCapture` and `Desk` | Structs, a value with a flag, channels: a translation, not a design. |
| `Files.move` with `ATOMIC_MOVE`, `FileChannel` | `os.Rename`, `ReadAt`. |
| `Runtime.addShutdownHook`, `RunOnce` | Signals and `sync.Once`; the order of the stopping is already the program's own. |
| `LaunchOptions`, `ClaudeTokenProvider.windowsCommand` | Already written by hand, and port line by line. |
| Gradle, JUnit, the JDK toolchain | They build and test the Java version and are not part of the program. |

## Points confirmed after the plan was written

| Point | Decision |
| --- | --- |
| Records to JSON (phase 1) | A `toJson()` written out for each body; no reflection. |
| A history row with more than two decimals (phase 2) | Exact arithmetic is kept, with a decimal of the program's own; plain cents are not enough. |
| JavaFX's own log lines (phase 3) | On the console only. |
| Stack traces (phase 4) | On the console in debug mode only; one line per failure in the log file. |
| macOS and the process tree (phase 5) | The test is run by hand on the Mac, and the phase waits for it. |
