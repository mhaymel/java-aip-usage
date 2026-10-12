# Java to Go: preparing the Java program for the port

The plan for changing the Java program so that it leans only on what Go also has, and the record of how it was
carried out. The Go version is then a translation of this code, not a new design. The task is
[new-requirements.md](new-requirements.md); the analysis it builds on is [go-port.md](go-port.md), whose problem
numbers are used here.

The plan was written against version 0.70. **All six phases are built**, as versions 0.71 to 0.76; how each went is in
phases 83 to 88 of [implementation-plan.md](implementation-plan.md), and what changed for the person is in
[requirements.md](requirements.md). **One thing is outstanding: the run of phase 5's tests on a Mac.**

## Where it stands

| # | Phase | Version | Phase of the implementation plan | Commit | State |
| --- | --- | --- | --- | --- | --- |
| 1 | JSON of the program's own | 0.71 | 83 | `1fdaa40` | built |
| 2 | Money as a decimal of the program's own | 0.72 | 84 | `5c8825f` | built |
| 3 | Logging of the program's own | 0.73 | 85 | `28c40a7` | built |
| 4 | Texts of the program's own for every failure | 0.74 | 86 | `7794b3c` | built |
| 5 | Stopping the process tree | 0.75 | 87 | `139dd73` | built; checked on Windows, **not yet on macOS** |
| 6 | The refresh service as one loop with a queue | 0.76 | 88 | `d510468` | built |

All of it is on `main` and pushed. `./gradlew build` and `./gradlew frontendTest` pass (about 730 Java test methods,
370 frontend tests).

**The program has gone on since.** Version 0.77 (phase 89 of the implementation plan, commit `a1ce03c`) is not part
of this plan: it changed the style sheet of the page alone, so that the placeholder of the row is a green frame with
square corners and nothing in it. Nothing of what is said here changes by it, and the port takes it over with the
frontend, which is copied.

**What the program no longer uses.** Jackson, so that JavaFX is the only library it is built with; `java.math` and
`java.text.DecimalFormat`; `java.util.logging` and `System.Logger`; the text of an exception and the name of its
class in any message; `Process.descendants()`; a lock with a condition, and `Thread.interrupt()`. Each of these is
held off by a test that reads the sources (`FailureTest`, `CancelTest`), so that it does not come back unnoticed.

**To do on the Mac**, in the repository, with nothing to install but what Gradle fetches:

```sh
./gradlew test --tests '*ClaudeTokenProviderTest' --tests '*ProcessTreeTest'
```

It shows whether `pgrep -P` and `kill -9` end `claude` and what it started there. If
`whatTheCliStartedIsKilledWithIt` fails, the fault is in `ProcessTree`, in its part for systems other than Windows.

### What the port takes over, class by class

What was built here is the part of the Go program that needed a design. Each is a small class with tests that state
its rules, to be translated with them.

| Here | Lines | In Go | Solves in go-port.md |
| --- | --- | --- | --- |
| `org.example.json`: `Json`, `JsonReader`, `JsonWriter`, `JsonException` | 940 | a package `json` of the same shape: one struct with a kind, a reader, a writer; or `encoding/json` where it is shown to give the same bytes | 10, 11, 12 |
| `toJson()` on the 32 answer records | | a method on each struct, key by key; no struct tags are needed | 11 |
| `org.example.money.Amount` | 300 | a struct of an `int64` and an `int`, with the same functions | 9 |
| `org.example.log.Log`, and `Logging` | 250 | a package `log` with an entry, sinks and one lock; `Logging` opens the console and the file | 22 |
| `Failure`, `Fault` | 190 | `failure.Of(err)` over `errors.Is` and `errors.As`, with the same fifteen texts; `Fault` is an error made with `errors.New` | 17 |
| `ProcessTree` | 140 | the same two commands through `os/exec`; a job object and a process group can take their place later | 18 |
| `Cancel` | 70 | `context.Context` with its cancel function; nothing to write | 13 |
| `UsageService` | 540 | one goroutine, `chan func()` for the messages, `select` with a timer, `atomic.Pointer` for what is published | 13 |
| `UsageFetch`, `TokenProvider.acquire(Cancel)`, `UsageSource.fetch(token, Cancel)` | | functions that take a `context.Context` first; the forms without it are not needed | 13 |

### What of go-port.md is still open

The window, which was left alone on purpose, is all of what is left that needs a design: problems 1 to 8 (the window
library, the title bar while running, the tooltips, asking the page, dragging, pixels and scaling, the WebView2
runtime, console and window in one program). The rest, problems 14 to 16 and 19 to 34, cannot be prepared here and
belong to the port itself: how Go's HTTP client and server are set, how `claude` is found and signals are taken
there, decisions such as where the files are for a program started by double click, the build, and the port's own
tests. Problem 20 (signals and exit codes) is half prepared, in that the log is now closed by the program and by
nothing else.

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
| Order | This plan first; then one phase at a time, each built, checked, committed and merged before the next. |

## The phases

| # | Phase | Replaces | Solves in go-port.md |
| --- | --- | --- | --- |
| 1 | JSON of the program's own | Jackson (`ObjectMapper`, `JsonNode`, `ObjectNode`, annotations) | 10, 11, 12 |
| 2 | Money as a decimal of the program's own | `BigDecimal`, `RoundingMode`, `DecimalFormat`, `Double` amounts | 9 |
| 3 | Logging of the program's own | `java.util.logging`, `System.Logger`, `ShutdownSafeLogManager`, the `.lck` file | 22, part of 20 |
| 4 | Texts of the program's own for every failure | `e.getMessage()`, `getSimpleName()`, stack traces in the log | 17 |
| 5 | Stopping the process tree | `Process.descendants()` | 18 |
| 6 | The refresh service as one loop with a queue | `ReentrantLock`, `Condition`, `Thread.interrupt()` | 13 |

They were built in this order. Phase 4 needed phase 1 (the text of a JSON that cannot be read is then the program's
own) and phase 3 (how a failure is written to the log); phase 6 came last because it was the riskiest.

**Each phase** was done the way the phases of implementation-plan.md were: the tests first where a rule changed, the
whole suite green (`./gradlew build` and `./gradlew frontendTest`), a run with `--debug` against the fake backend, the
changed sentences in requirements.md, an entry in implementation-plan.md, and the version raised by 0.01. A phase is
one commit and can be undone alone.

What follows is each phase as it was built, in short; the whole of it, with its tests, is in the phase of
implementation-plan.md that is named.

### Phase 1: JSON of the program's own (version 0.71, phase 83)

- **Built.** `org.example.json`: `Json`, one class with a kind for every value, in which a number keeps the text it
  was written with; `JsonReader`, strict as RFC 8259 says, whose faults say where and what in the program's words
  (`line 3, column 14: a value was expected`); `JsonWriter`, with the form on one line for the API and the form over
  several lines (`"key" : value`) for `settings.json` and a logged response. Each of the 32 answer records writes
  itself with a `toJson()`, key by key and without reflection.
- **Checked.** For the length of the phase Jackson stayed as a test dependency and was compared with: no difference in
  a hundred texts, in every answer record with 200 random fillings each, and in the settings file.
- **Other than planned.** The value is one class, not a sealed type. `JsonException` is an `IOException`, so that
  reading a file and its JSON stays one failure.
- **For the person.** The words after `The usage response is not valid JSON:` are the program's own. A settings file,
  or the body of a `POST`, with text after its JSON value is refused.

### Phase 2: money as a decimal of the program's own (version 0.72, phase 84)

- **Built.** `org.example.money.Amount`: a whole number and the count of its decimals, so that `186.02` is exactly
  that and a history row changed by hand to `1.005` keeps its third decimal. Everything done with money is in it:
  reading, adding and subtracting, the share of a limit, and the written forms (`186.02`, `1,000.00`, `+0.05`). Only
  what is shown or written is rounded, to the cent and away from zero at half.
- **Checked.** A test for the length of the phase set it beside `BigDecimal`: the same for some 3,000 amounts and
  20,000 pairs.
- **Other than planned.** Plain cents, as first planned, would not have kept a third decimal, so it is a decimal with
  a count of decimals. It holds 18 digits, where `BigDecimal` held any number.
- **For the person.** Nothing in an ordinary run. At the edges: an amount beyond 18 digits in a response cannot be
  used; the percentage of the reading shown at startup is exact at a half (14.50 of 100.00 is 15%, not 14%).

### Phase 3: logging of the program's own (version 0.73, phase 85)

- **Built.** `org.example.log.Log`: a class says `error`, `warning`, `info` or `debug`, and the entry goes to every
  sink that listens, one at a time. `Logging` only opens and closes the two sinks of a run, the console and the file,
  which is written through after every line. The form of a line and the words for its level (`SEVERE`, `WARNING`,
  `INFO`, `FINE`) are as they were.
- **Gone.** `ShutdownSafeLogManager`, the rule that logging is set up before `UsageApp` is loaded, and the lock file
  `java-aip-usage.log.lck`.
- **For the person.** No lock file. What JavaFX and the Java runtime log of themselves is on the console only.

### Phase 4: texts of the program's own for every failure (version 0.74, phase 86)

- **Built.** `Failure`, the one place that looks at the kind of an exception, answers with one of fifteen fixed texts
  (`the connection was refused`, `the file or its folder is not there`, …), or with the program's own words for a
  failure it names itself, which is a `Fault`. Every message that used the text of a Java exception or the name of
  its class asks it. A failure is one line of the log, ending with a colon and the text; the stack trace is on the
  console in debug mode only.
- **Other than planned.** `the file is in use` and `the disk is full` have no text of their own: Java reports them only
  in the system's words, with no kind to tell them by. Why a program did not start is read from the number in the
  system's text (`error=13`), the one thing taken from such a text.
- **For the person.** The words after the colon in a failed refresh, a failed save and a token problem, the same on
  every system and in every language of the system; `Unexpected error; start the program with --debug to see on the
  console where it came from.`; no stack trace in the log file.

### Phase 5: stopping the process tree (version 0.75, phase 87)

- **Built.** `ProcessTree.kill(pid)`: on Windows `taskkill /PID <pid> /T /F`; elsewhere what the process started is
  found with `pgrep -P`, level by level, and ended with `kill -9`, the deepest first. A command that is missing or
  fails is noted at debug level, and `claude` itself is still ended directly.
- **Checked.** On Windows: a stand-in `claude` that starts a second process; neither is left, and the test fails when
  the call is taken out. **Not yet on macOS**, where it is to be run by hand (see Where it stands).
- **For the person.** Nothing.

### Phase 6: the refresh service as one loop with a queue (version 0.76, phase 88)

- **Built.** `UsageService` is one loop that owns the schedule, the search and the request under way. Whatever is
  asked of it is handed to it as a message, a closure, and whoever asks waits for the answer, so that what was asked
  is in force when the call returns. A request runs beside the loop and hands its outcome back as a message, so
  requests still never overlap. What the window asks every second is read from what the loop last published and
  never waits for it. A request that is no longer wanted is told so through a `Cancel`, which the HTTP client and the
  token flow both follow; nothing is interrupted.
- **Checked.** The existing suite passed against the new service before a test was touched, but for the two that
  asked whether the fetch thread was interrupted. Four rules were broken on purpose, one at a time, and each was
  caught by a test.
- **Other than planned.** What is published is a field replaced whole, not an `AtomicReference`. `acquire()` and
  `fetch(token)` stay beside the forms that take a `Cancel`, for what answers at once.
- **For the person.** Nothing.

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
| macOS and the process tree (phase 5) | The test is run by hand on the Mac, and the phase waits for it. Still to be done. |

## What turned out other than planned

Seen only when a phase was built, and written into requirements.md with it:

| Phase | What | For the person |
| --- | --- | --- |
| 1 | Jackson passed over whatever came after a JSON value; the strict reader does not. | A settings file, or the body of a `POST`, with text after its JSON is refused. |
| 2 | An `Amount` holds 18 digits, where `BigDecimal` held any number. | A response with a larger amount cannot be used; two history rows too far apart have no change shown. |
| 2 | The percentage of the reading shown at startup was worked out with binary fractions. | It is exact at a half now: 14.50 of 100.00 is 15%, not 14%. |
| 2 | An amount of ten million or more was written in the API with an exponent. | `10000000.0` in the place of `1.0E7`, the same number. |
| 4 | Java reports a file in use and a full disk only in the system's words. | Both are `the file could not be read or written`; Go can name them. |
| 4 | A fault nobody expected had the name of its Java class in its message. | `Unexpected error; start the program with --debug to see on the console where it came from.` |
| build | `./gradlew frontendTest` gave Node a directory, which Node 24 refuses. | The task names its test files. |
