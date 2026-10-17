# Porting java-aip-usage to Go

An analysis of what it takes to build a Go version of this program in a repository of its own, with the
same look and feel and the same functions. The inputs are [requirements.md](requirements.md),
[implementation-plan.md](implementation-plan.md), [api.md](api.md) and the sources of this repository.

It was written against version 0.70 of the Java program (about 8,100 lines of Java in 60 files, 3,100 lines of
frontend, 11,300 lines of JUnit tests and four Node test files).

**Brought up to date for version 0.77** (about 10,300 lines of Java in 70 files, 12,500 lines of JUnit tests). Since
the analysis, the Java program was changed so that it leans only on what Go also has: the six phases of
[java-to-go.md](java-to-go.md). Jackson, `BigDecimal`, `java.util.logging`, the texts of exceptions,
`Process.descendants()` and the lock with its interrupts are gone, each replaced by a small class of the program's own
that is to be translated. What that settled is marked here: in the summary, in the steps, in the table of what ports
directly, and in a column of the table of problems. The analysis of the window is as it was, since the window was
left alone on purpose.

That work ended with version 0.76. Version 0.77 changed the page alone: the placeholder of the row, the "gray box",
is a green frame with square corners and no fill. It is one rule of `app.css` and comes to the Go version with the
frontend, which is copied; it adds nothing to the port and nothing to the problems below.

**Version 0.97** adds the burndown of the month's credit, and it too adds nothing to the problems below. `Burndown` (about 200
lines) is pure arithmetic on the rows of the history, with `Amount`, `Formatting` and the limit counted of `CostControl`, and
translates directly; so do `FakeMonth`, the invented readings the history of the fake backend is begun with, and
`UsageHistory.seed`, which puts them in the file in the order of time. The API has one route more, `GET /api/burndown`;
`POST /api/height` takes `burndown`; the settings file has two keys more, `showBurndownIcon` and `burndownHeight`. The window
host names one view more, `burndown`, which takes the height of the window as a list does and whose height is remembered. The
chart is SVG drawn by the page, which is copied.

**Version 0.99** adds nothing to the problems below either. `Formatting.shortAmount` writes the burndown's labels short (`1k`),
in whole cents; `Burndown.againstIdeal` marks a point `below` the ideal line and puts one where the line crosses it. The settings
file has one key more, `burndownWidth`, which the host stores as it stores `logWidth`, and the burndown reports flag `2` with a
least width of 200 (`WindowFit.Size.leastWidth`).

## Summary

- **About four fifths of the program ports without difficulty.** The backend (usage client, refresh service, history,
  settings, formatting, API handler, fake backend, log) uses only the JDK, and everything it uses has a
  direct counterpart in Go's standard library. Some of it becomes simpler in Go. Since version 0.76 this holds without
  the reservations of the first analysis: the parts that had no counterpart are now code of the program's own.
- **The frontend is not ported at all.** `index.html`, `app.css`, `app.js` and `view.js` are plain files served over HTTP,
  with no build step, and their Node tests do not touch the JVM. They are copied as they are. This is what the
  requirements planned for ("keep the frontend as independent from the backend as practical").
- **The one hard part is the window.** JavaFX gives the Java program a WebView, a window whose every property can be set,
  mouse event filters, a clipboard, screens and tooltips, as one library on every system. Go has nothing like it in its
  standard library, and no single Go library covers all of what `UsageApp` does. About 900 lines of Java
  (`UsageApp`, `StageSwap`, `WindowDrag`, `Desk`, `AppIcons`) have to be designed anew, and partly written per
  operating system.
- **Four smaller parts needed care, and are prepared**: money without `BigDecimal` (`Amount`), stopping the `claude`
  process tree (`ProcessTree`, whose run on macOS is outstanding), the strict reading of `settings.json` (the own
  `Json`), and the refresh service's lock with a timed wait (one loop with a queue). In Go each is a translation.
- **Some requirements described Java, not the program.** The lock file of the log and the stack trace of a failed
  start are gone from the program and from the requirements, and no message carries a text of Java's any more. What
  is left to rewrite for the Go version is the build: Gradle and the JDK toolchain, `./gradlew run --args`, "starts
  from an IDE as a plain `main` method".

The first decision, on which most of the rest depends, is the window library ([The window](#the-window-the-hard-part)).

## The steps

The requirements the port is measured by are language-neutral already: `requirements.md` holds for every version of
the program, with what is Java's in one section at its end and what is asked of the Go version alone in another. The
file names and the log's start line keep `java-aip-usage`.

1. **Decide the target systems and the window library**, and prove the choice with a spike before anything else is
   written: a window with no title bar that loads a page from loopback, is resized from Go to a size the page reports,
   is dragged by the page, gets and loses its title bar while running, and stays on top. Windows first, since
   "Windows is what the Go rewrite is for" (Scope and limits); then macOS.
2. **Set up the repository**: `go.mod` with a `toolchain` line, a package layout that follows the Java packages
   (`internal/usage`, `internal/settings`, `internal/token`, `internal/fake`, `internal/web`, `internal/window`,
   `cmd/aip-usage`), a `Makefile` or Taskfile for build, test, frontend test and cross-compilation, and the Windows
   resource file with the icon and the manifest.
3. **Copy the frontend and its tests** (`src/main/resources/web`, `src/test/frontend`, `src/main/icons`) and embed
   them with `//go:embed`. Run the Node tests in the new repository unchanged.
4. **Port the backend from the bottom up, each package with its tests**:
   the program's own foundations first, which everything else stands on (`json`, `money.Amount`, `Redaction`,
   `Failure` and `Fault`, `log.Log`; `Cancel` is `context.Context` and needs no code) → value types and parser
   (`UsageSnapshot`, `Spend`, `UsageParser`, with the fixtures) → `Formatting`, `CostControl`,
   `HistoryDeltas` → `UsageHistory`, `HistoryReader`, `LatestChangeCache`, `LogTail` → `Settings`, `SettingsStore`,
   `IntervalSettings`, `LaunchOptions` → `Logging` → `UsageClient`, `UsageFetcher` → `RefreshSchedule`,
   `IntervalSearch`, `UsageService` → `FakeBackend` → `StatusDisplay`, `CostPanel`, `ApiHandler`, `LocalWebServer`
   → `AppRuntime`.
5. **Port the token flow** (`ClaudeTokenProvider`, `CredentialCapture`, `ProcessTree`) with a stand-in for `claude`
   built from the test binary itself, which like `FakeClaude` can start a second process to be ended with it.
6. **Run the program without a window**: at this point `go run . --debug` with a browser pointed at the loopback
   address shows the whole interface against the fake backend. This is the check that the API is the same as
   `api.md` says, before the window exists.
7. **Build the window host** from the spike: fitting, limits, remembered heights, title bar, stay on top, dragging,
   restoring the window, icon, bringing to the front.
8. **Port the stopping**: signals, exit codes, the order of the cleanup, the last lines of the log.
9. **Package**: a Windows `.exe` with icon and without a console window; for macOS at least a binary, and an app
    bundle if the icon and the Dock are to be right. Cross-compile Windows on macOS as the requirements ask.
10. **Compare with the Java program side by side** against the fake backend, scenario by scenario, with the same
    `settings.json` and the same CSV: the bytes of the files written, the JSON of each endpoint, and the window by
    eye, with the list at the end of Testing and verification.
11. **Update the documents** of the new repository: in the requirements, the section The Go implementation, which is
    to say how the Go version is built, started and tested and which toolkit hosts its window; the plan; `api.md`,
    which is language-neutral already (what the program runs on is named by the backend, under `runtime` and
    `toolkit` of `/api/about`), and whose window host contract is to be changed if the contract changed; the README.

## What ports directly

| Java | Go | Note |
| --- | --- | --- |
| `com.sun.net.httpserver.HttpServer` on virtual threads | `net/http` | A goroutine per request is the default. `Host` check and `Content-Type` check become one middleware. |
| `java.net.http.HttpClient` | `net/http` client | See problems 14 and 15 for the two defaults that differ. |
| The program's own JSON (`Json`, `JsonReader`, `JsonWriter`, a `toJson()` on each answer record); Jackson until 0.70 | the same package translated, or `encoding/json` where it is shown to give the same bytes | The differences of problems 10 to 12 no longer matter: nothing depends on a library. |
| `Amount`; `BigDecimal` until 0.71 | a struct of an `int64` and an `int` | No decimal library is needed (problem 9). |
| Records (`Settings`, `UsageSnapshot`, `Size`, …) | structs | `Optional` and boxed `Long`/`Integer` become pointers or a value with an `ok` flag. |
| Exceptions with a reason (`TokenException.Reason`, `UsageFetchException.status()`) | error types with fields, `errors.As` | |
| `Failure` and `Fault`; the texts of exceptions until 0.73 | `failure.Of(err)` over `errors.Is` and `errors.As` | The same fifteen texts (problem 17). Go can also name a file in use and a full disk, which Java could not tell apart. |
| `Log` and `Logging`; `java.util.logging` until 0.72 | the same package translated, or `log/slog` with a handler that writes this line | `ShutdownSafeLogManager` and the rule that logging is set up before `UsageApp` is loaded are gone already. |
| `Cancel`, and `UsageService` as one loop with a queue; a lock, a condition and interrupts until 0.75 | `context.Context`; one goroutine, `chan func()`, `select` with a timer | A translation (problem 13). |
| `ProcessTree`; `Process.descendants()` until 0.74 | the same two commands through `os/exec` | A job object and a process group can take their place later (problem 18). |
| `Runtime.addShutdownHook`, `RunOnce` | `os/signal`, `sync.Once` | See problem 20. |
| Class path resources `/web`, `/icons` | `embed.FS` | One binary with everything in it. |
| `DateTimeFormatter` patterns | layouts: `2006-01-02 15:04:05`, `15:04`, `2 Jan 2006, 15:04:05` | Go's month names are always English, which is what `Locale.ENGLISH` was for. |
| `Clock` as a parameter | a `func() time.Time` | |
| `Pattern` (`Redaction`, `LogTail`, `WindowFit`, `UsageClient`) | `regexp` | Checked: none of the seven patterns uses a lookahead, lookbehind or back-reference, which Go's engine does not have. |
| `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` | `os.Rename` | Replaces an existing file on Windows too. |
| `FileChannel` reading the end of the log | `os.File.ReadAt` | |
| `ProcessBuilder` for the Explorer, the Finder, `xdg-open` | `os/exec` | `Desk.revealCommand` ports line by line. |
| `Locale.ROOT`, `Locale.US` given everywhere | nothing | Go formats independently of the machine's language. |
| `Main` not being an `Application`, `--enable-native-access`, "unnamed module" warnings | nothing | Java only. |
| JUnit, `@TempDir`, `@ParameterizedTest`, `@Timeout` | `testing`, `t.TempDir()`, table tests, `httptest` | |

## The window: the hard part

### What the Java window does

All of this is in `UsageApp` and its helpers, and all of it is required (Compact window, The elements of the row,
window host contract in `api.md`):

| # | Function | How JavaFX does it |
| --- | --- | --- |
| a | Shows the page from `http://127.0.0.1:<port>/` with no browser controls | `WebView` |
| b | Asks the page every 150 ms for its size and its view, and gets the answer back at once | `executeScript` returns the value synchronously |
| c | Sets the content size exactly, and limits what the person may drag (nothing, height, both) | `setWidth/Height`, `setMin/Max…`, measured decoration |
| d | No title bar, by setting; a title bar for as long as a panel is open; changed while running | a second `Stage` in the other style takes over the scene (`StageSwap`) |
| e | Moves a window without title bar by dragging, after asking the page `dragStartsAt(x, y)` | mouse event filters above the WebView |
| f | Stay on top, changed while running | `setAlwaysOnTop` |
| g | Comes to the front at start | on top for 400 ms |
| h | Remembers a dragged height after 500 ms of rest | height listener and `PauseTransition` |
| i | Restores place, size, panel and tab, only if the place is on a screen | `Screen.getScreensForRectangle` |
| j | Window icon in four sizes | `stage.getIcons()` |
| k | Clipboard | `Clipboard` on the toolkit's thread |
| l | Tooltips of 14 px, drawn by the window, reaching beyond it | JavaFX draws `title` itself; styled in `window.css` |
| m | Closing the window ends the program | `Application.stop()` |

### What Go offers

| Option | Windows | macOS | Covers | Does not cover | Cross-compiling Windows on macOS |
| --- | --- | --- | --- | --- | --- |
| **Wails v2** | WebView2, without cgo | WKWebView, cgo | a, c, f, i (screens), k, m; frameless window; drag by a CSS property; size, min/max, position | d: frameless is fixed at start. b: no synchronous script result (use bindings/events). Wants its own project layout and asset server. | Supported |
| **Wails v3** | as v2 | as v2 | as v2, plus several windows and more window control | Was still pre-release when last checked: check its state before choosing it | Supported |
| **webview/webview_go** | WebView2, cgo | WKWebView, cgo | a, m; size with min/max/fixed hints; `Bind`, `Init`, `Eval`; gives the native window handle | d, e, f, g, i, j, k: all through the native handle, per system. `Eval` returns nothing. | Needs a mingw-w64 C++ compiler |
| **go-webview2** (jchv, or Wails' fork) | WebView2, without cgo | none | as webview_go on Windows | macOS entirely | Plain `GOOS=windows go build` |
| **Own Win32 host on a WebView2 loader** + a second host for macOS | full control | full control (Objective-C through cgo) | everything | nothing, but it is all to be written, twice | Windows part: yes |
| **Chromium in app mode** (as the sibling `go-with-web-ui` does) | Edge/Chrome `--app=` | Chrome | a | c to j: the program does not own the window. Not usable for a window that fits itself to one row. | Yes |

No ready-made library offers (d), (e) and (g) as they are. They are all possible from Go: the program makes the system
calls itself, in a small file per system (about a hundred lines each).
On Windows these are `SetWindowLong(GWL_STYLE)` with `SetWindowPos(SWP_FRAMECHANGED)` for the title bar,
`SetWindowPos(HWND_TOPMOST)`, `WM_GETMINMAXINFO`, `MonitorFromRect` and `AdjustWindowRectExForDpi`, called directly in
the system's DLLs without cgo. On macOS the same is done through `NSWindow` (`styleMask`, `level`, `setContentSize`),
usually with cgo. What cgo is, and what doing without it costs, is in [Pure Go or cgo](#pure-go-or-cgo).

**Recommendation.** If Windows is the target and macOS comes second: the WebView2 loader without cgo (go-webview2) and
an own, small Win32 window file. It gives one `.exe`, cross-compiles from macOS with nothing installed, and every item
of the table can be done exactly. The macOS host is then a second implementation of the same Go interface, on
webview_go. If both systems are wanted at once with the least own code: Wails, accepting its project layout, and a
workaround for (d).

### Pure Go or cgo

**What cgo is.** cgo is the part of the Go toolchain that lets a Go program call C code, and on macOS Objective-C. The C
is written in a comment above `import "C"`, and `go build` then calls a real C compiler (gcc or clang) for it and links
the result into the binary. A program that uses none of it is "pure Go". Using cgo has three consequences:

- **A C compiler must be installed**, on macOS the Xcode command line tools. Pure Go needs only Go.
- **Cross-compiling gets hard.** Pure Go builds a Windows `.exe` on a Mac with `GOOS=windows go build` and nothing else.
  With cgo a C compiler for the other system is needed, and for macOS as the target that means, in practice, building
  on a Mac.
- **Builds are slower, and a call between Go and C costs a little.** Neither matters for a window that is resized a few
  times a second.

**Why Windows does not need it.** The Win32 API is a set of C functions in DLLs (`user32.dll` and others), and Go can
load a DLL and call a function in it at run time, with no C compiler involved:

```go
user32 := windows.NewLazySystemDLL("user32.dll")
setWindowPos := user32.NewProc("SetWindowPos")
setWindowPos.Call(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE|SWP_NOSIZE)
```

`golang.org/x/sys/windows` gives the mechanism; each function the program needs is declared by hand in a line or two.
WebView2 is reached the same way, by a loader written in pure Go (go-webview2).

**Why macOS usually does.** The window API there (Cocoa, `NSWindow`, `WKWebView`) is Objective-C, not functions in a
DLL, and the usual way to it from Go is a few lines of Objective-C compiled in through cgo. The WebView libraries for
macOS (webview_go, Wails) do it this way.

**What is lost with pure Go on both systems.**

| System | Lost with pure Go | Note |
| --- | --- | --- |
| Windows | Nothing | WebView2 and every window call of the table are reachable. This is the recommended way. |
| macOS, calling the Objective-C runtime from pure Go (the `purego` library) | No function in principle, but every ready-made library: the whole host is own code, with the awkward parts (delegates and callback blocks for script results, messages from the page, window events) written by hand | Not verified that every piece works this way; it needs a spike of its own and is the riskiest part of the port. |
| macOS, the page in a browser in app mode (as `go-with-web-ui` does) | The program no longer owns the window: fitting it to the row, no title bar, stay on top, dragging, the remembered heights, the restored place, the program's own icon | Trivial to build, but it is no longer a status strip. |

**What pure Go would buy on macOS** is only that the Mac binary can be built on Windows or Linux, and that no C compiler
is needed. A Mac is needed all the same, to test the window and to sign and notarize the program if it is given to
others.

**Recommendation.** Pure Go on Windows, cgo on macOS, behind one Go interface. The requirement is that the Windows
binary can be cross-compiled on a Mac, which this meets; nothing asks for the reverse.

### What the Windows version is, in short

On Windows the Go version can be one `.exe` in pure Go, with no cgo and no C compiler, that has every function of the
Java version except one, with these reservations:

- **The tooltips are the exception** (problem 3). The Java version draws them itself, at 14 px, and they may reach
  beyond the window. In WebView2 the system draws them, at its own size. To keep 14 px the host needs a small tooltip
  window of its own; otherwise the requirement becomes "the system's size" for the Go version.
- **The same functions, not the same pixels.** WebView2 is Chromium and JavaFX uses WebKit. The page is the same, but
  the checkboxes, the dropdown, the scrollbars and the fonts will look slightly different.
- **WebView2 must be on the machine.** It is part of Windows 11 and usually there on Windows 10, but it is not in the
  `.exe`. The Claude Code CLI is needed as before.
- **This is analysis, not proof.** Nothing has been built. The window (no title bar, the title bar on and off while
  running, dragging, fitting to the page) is to be proven with a spike before the rest is ported (step 1).

### Building the macOS version with little access to a Mac

cgo needs only Go and a C compiler on the Mac; Go fetches the libraries itself. So the first thing is to see whether
the Mac already has the compiler, which a developer's Mac often has:

```sh
xcode-select -p
clang --version
```

If both answer, `go build` with cgo works on that Mac and nothing more has to be installed. If not:

| Way | How | Cost |
| --- | --- | --- |
| **A Mac in the cloud** (recommended) | A GitHub Actions workflow of about ten lines builds the binary on a macOS runner, which has clang and the SDK; the binary is downloaded | Free for a public repository; for a private one the macOS minutes count against the plan at a higher rate |
| **Pure Go on macOS, built on Windows** | `purego` in place of cgo, then `GOOS=darwin GOARCH=arm64 go build` on Windows | The whole macOS host is own code, not verified (see the table above) |
| **Cross-compiling cgo on Windows** | A cross compiler (osxcross under WSL, or `zig cc`) and Apple's macOS SDK | Not advised: fiddly to set up, and Apple's licence allows the SDK on Apple hardware only |

Whichever is chosen:

- **Testing needs the Mac.** The window can be tried only there.
- **A downloaded binary is blocked at first.** macOS quarantines a file that came through a browser; it has to be
  allowed once (right-click and Open, or `xattr -d com.apple.quarantine <file>`), which a restricted Mac may forbid. A
  binary built on the Mac itself is not affected.

### Things that turn out differently in any Go host

- **The title bar no longer needs a second window.** Windows and macOS can change the style of a window that is showing.
  `StageSwap`, and the measuring of the decoration again afterwards, shrink to one call and a new measure. This is
  easier than in Java.
- **The page cannot be asked and answered in one call.** Every Go WebView returns script results later or not at all.
  The contract can stay as it is if the host injects a script at start (`Init`) that calls `window.contentSize()` and
  `window.currentView()` on a timer and hands the answers to a bound Go function. The page is not changed; the sentence
  in `api.md` "the host exposes nothing to the page" is then no longer quite true and is to be reworded.
- **Dragging moves to the page side.** There is no event filter above a native WebView. The injected script listens for
  `mousedown`, asks `dragStartsAt`, and calls a bound function; on Windows the host answers with the usual
  `ReleaseCapture` and `WM_NCLBUTTONDOWN/HTCAPTION`, and the system moves the window. The rule that the page gets no
  mouse moves meanwhile is then met by the system.
- **Pixels.** JavaFX works in logical pixels throughout. Win32 works in physical pixels, and the page reports CSS
  pixels: every size and place has to be multiplied by the scale of the monitor the window is on, the process has to
  declare itself per-monitor DPI aware in its manifest, and the scale changes when the window is dragged to another
  monitor. The stored `windowWidth`, `windowHeight`, `windowX`, `windowY` and the two heights need a stated unit.
- **Another browser engine.** JavaFX's WebKit, Edge's Chromium (WebView2) and Safari's WebKit (WKWebView) draw form
  controls, scrollbars and fonts differently. The page measures itself, so the window follows; but the look of the
  dropdown, the checkboxes and the scrollbars will differ from the Java version, and from Windows to macOS. "The same
  look and feel" can mean the same page, not the same pixels.
- **A WebView has browser habits** that JavaFX's lacks or hides and that have to be switched off: the context menu,
  reload with F5, zoom with Ctrl and the wheel, the developer tools, the status bar, drag and drop of a file into the
  window (which would navigate away).

## Table of potential problems

Severity: **high** — may force a change of the requirements or a large piece of own code; **medium** — solvable, but
easy to get subtly wrong; **low** — a known difference to remember.

The columns up to "Way out" are the analysis as it was made at version 0.70, and say "In Java" of the program as it was
then. The last column says where each problem stands at version 0.77, which is where it stood at 0.76: **prepared** means the Java program now does it
in a way Go can do the same, so that the port translates and does not design; **open** means it is the port's to
solve. Eight of the thirty-four are prepared or settled (9 to 13, 17, 18 and 22), which are the ones that needed a
design in the backend; the eight of the window, three of them rated high, are open, and so are the rest, which are
settings, decisions, the build and the tests of the port itself.

| # | Area | In Java | The problem in Go | Severity | Way out | State at 0.77 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Window library | JavaFX: one library, every system | No equivalent. Each option lacks part of what `UsageApp` does (see the table above) | high | Decide first, prove with a spike (step 1). Hide the window behind one Go interface so that the two systems are two files. | open |
| 2 | Title bar on and off while running | `StageSwap` | No Go library offers it; frameless is a start-up option in Wails v2 and absent in webview_go | high | Native style change on the window handle. Simpler than the Java way once written. | open |
| 3 | Tooltips | Drawn by JavaFX, 14 px by `window.css`, may reach beyond the window | In WebView2 and WKWebView the `title` tooltip is the system's: its size cannot be set. A tooltip drawn by the page in HTML could be 14 px but cannot leave the window, which is often one row high | high | Change the requirement "Bigger tooltips" for the Go version (system size), or build a second, borderless tooltip window in the host. 61 `title` uses in the page are affected. | open |
| 4 | Asking the page | `executeScript` returns the value | Script results are asynchronous, or not returned | medium | Injected script and bound functions (above). Reword the host contract in `api.md`. | open |
| 5 | Dragging the window | Mouse event filters, `WindowDrag` | No event filters over a native WebView | medium | Page-side `mousedown` through the injected script, system move on the host side. `WindowDrag` is then not needed. | open |
| 6 | Pixels and scaling | Logical pixels | Physical pixels in Win32, per-monitor scale | medium | One conversion in the window package; DPI-aware manifest; test on a 150 % monitor and across two monitors. | open |
| 7 | WebView2 runtime | The engine is in the JavaFX jars | The runtime must be on the machine (it is on Windows 11, usually on 10). It starts several `msedgewebview2.exe` processes and writes a user-data folder, by default beside the `.exe` | medium | Check at start and say what to install. Put the folder under `%LOCALAPPDATA%`; say so in the requirements, which now promise that only three files are written. | open |
| 8 | Console and window in one program | A JVM program has both | A Windows `.exe` is either a console program (a black window opens with it) or a GUI program (no output in the terminal: `--help`, the console log and the usage text on a mistake go nowhere) | medium | Build as GUI (`-H windowsgui`) and attach to the parent's console at start (`AttachConsole`), or ship two binaries. | open |
| 9 | Money | `BigDecimal`, `RoundingMode.HALF_UP`, `DecimalFormat("#,##0.00")` | No decimal type and no grouping format in the standard library. `fmt.Sprintf("%.2f", 1.005)` gives `1.00`; the Java program gives `1.01`, because it rounds the decimal form | medium | Keep amounts as `int64` cents wherever possible (the response has `amount_minor`, the settings already use cents). Where a `float64` remains, go through `strconv.FormatFloat(v, 'f', -1, 64)` (the same shortest form as `BigDecimal.valueOf`) into `shopspring/decimal` or own code. Write the grouping by hand. Port `FormattingTest`, `HistoryDeltasTest` and `CostPanelTest` first. | **prepared**: `Amount` (phase 2 of java-to-go.md) |
| 10 | Strict reading of `settings.json` | `JsonNode`: `isIntegralNumber`, `canConvertToInt`, `null` means absent, each key judged alone | Decoding into a struct fails as a whole on one wrong type, turns `45.0` into 45, and matches keys **without regard to case** (`"LogLevel"` would be taken for `logLevel`) | medium | Decode into `map[string]json.RawMessage` and judge each key by hand, as `SettingsStore` does today; `json.Number` for the whole numbers. | **prepared**: own `Json`, each key judged alone (phase 1) |
| 11 | JSON as written | Jackson's pretty printer: `"key" : value`, `System.lineSeparator()` at the end; no escaping of `<`, `>`, `&` | `MarshalIndent` writes `"key": value`; the encoder writes `\u003c` for `<` unless told otherwise; map keys are sorted, struct fields keep their order; invalid UTF-8 is replaced silently | low | Use a struct for the fixed key order and `SetEscapeHTML(false)`. Decide whether the space before the colon matters (it is in every existing `settings.json`; both forms are read). Affects the pretty-printed response in the log too. | **prepared**: own `JsonWriter` and `toJson()` fix the bytes (phase 1) |
| 12 | Trailing text after the JSON | `FAIL_ON_TRAILING_TOKENS` | `json.Unmarshal` refuses it, a `Decoder` does not | low | Use `Unmarshal`, or check for `io.EOF` after the first value. The wording of "not valid JSON: …" comes from Jackson and will differ (see 17). | **prepared**: the own reader refuses it (phase 1) |
| 13 | Refresh service | `ReentrantLock` with a `Condition` and a timed wait; `Thread.interrupt()` to stop a wait or a request | `sync.Cond` has no timed wait, and a goroutine cannot be interrupted | medium | Redesign, do not transliterate: one goroutine with `select` over a timer, a "changed" channel and a `context`. The 1,000 lines of `UsageServiceTest` are the specification; `testing/synctest` makes the timing tests fast and exact. | **prepared**: one loop with a queue, `Cancel` (phase 6) |
| 14 | Redirects | `Redirect.NEVER`, so the token never reaches another host | Go's client follows up to 10 redirects by default (it drops `Authorization` only when the host changes) | medium | `CheckRedirect` returning `http.ErrUseLastResponse`. Keep the test. | open |
| 15 | Proxy | The requirement is a direct connection | Go's default transport reads `HTTPS_PROXY` and `HTTP_PROXY` from the environment | low | An own `Transport` with `Proxy: nil`. Also for the token capture and the tests on loopback. | open |
| 16 | Timeouts | Connect timeout and request timeout apart, `HttpTimeoutException` | One has to be built from `net.Dialer.Timeout`, a `context` deadline and `errors.Is(err, context.DeadlineExceeded)` | low | Small; covered by `UsageClientTest` (the hanging and slow answers). | open |
| 17 | Texts that come from exceptions | Several messages the window shows or the log writes end in `e.getMessage()` (`UsageClient` line 129, the JSON parse error, the capture server) | Go's error texts are others (`dial tcp 127.0.0.1:1: connectex: …`, and they differ between Windows and macOS) | medium | Give each of these places a text of the program's own, so that the requirements say what is shown. `Could not start` "carries the stack trace": a Go error has none. | **prepared**: `Failure`, fifteen fixed texts (phase 4) |
| 18 | Stopping `claude` and its children | `process.descendants().forEach(destroyForcibly)` | `os/exec` kills one process. On Windows `claude.cmd` runs through `cmd.exe`, which starts `node`: killing `cmd.exe` leaves `node` running | medium | Windows: a job object with `KILL_ON_JOB_CLOSE` (`golang.org/x/sys/windows`). Unix: an own process group and a signal to the group. Two files with build tags. | **prepared**: `ProcessTree`; macOS run outstanding (phase 5) |
| 19 | Finding `claude` on Windows | Own search through `PATH` and `PATHEXT` (`windowsCommand`), `.cmd` through `cmd.exe /c` | `exec.LookPath` does the search, but its rules are its own: check them against the requirement (Details of the behaviour, "On Windows the command is found the way a shell finds it"), in particular the script `claude` beside `claude.cmd`, the real case of the file name, and that Go refuses a program found in the current directory. Quoting arguments for a batch file is a known trap | medium | Keep the own search as a pure function with its tests (`ClaudeTokenProviderTest`), and use `LookPath` only if it is shown to agree. | open |
| 20 | Signals and exit codes | One shutdown hook; the JVM gives 143 and 130 | No hooks: nothing runs at `os.Exit` or when `main` returns unless called. Exit codes are set by hand. Windows has no `SIGTERM`; only Ctrl+C and Ctrl+Break arrive | medium | `signal.Notify`, then the same cleanup through `sync.Once`, then `os.Exit(130)` or `143`. Take care that the window's main loop and the signal goroutine do not both exit first. | half prepared: the log is closed by the program alone (phase 3) |
| 21 | The main thread | JavaFX has its application thread; `Platform.runLater` | The window must run on the OS thread `main` started on (`runtime.LockOSThread`), and everything touching it must be dispatched there; the rest runs in goroutines | medium | One `Dispatch` function in the window interface. The clipboard on Windows is easier than in Java: it needs no toolkit thread. | open |
| 22 | Lock file of the log | `java-aip-usage.log.lck`, kept by `FileHandler`, is a stated requirement | Go's logging makes none | low | Drop the requirement, or write and remove the file by hand if a second copy of the program is to be noticed by it. | **settled**: no lock file (phase 3) |
| 23 | Time zones and local times | `LocalDateTime.atZone` has fixed rules for a time that does not exist or exists twice (clock change) | `time.ParseInLocation` and `time.Date` do not promise which of the two it takes | low | Only the history's local timestamps are affected, for one hour a year. Decide and test it; `CostControl.weekdays` and the month and day arithmetic port with `time.Date` and `AddDate`. | open |
| 24 | Local server details | `HttpServer` does only what the handler says | `http.ServeMux` redirects unclean paths (`//`, `..`) with 301, `http.FileServer` redirects `/index.html` to `/` and lists directories, and a missing `Content-Type` is guessed from the body | low | Serve the embedded files with an own handler as `LocalWebServer.serveStatic` does, set every `Content-Type` and `X-Content-Type-Options: nosniff`. `LocalWebServerTest` and `ApiTest` (2,500 lines) will find the rest. | open |
| 25 | Command line | `LaunchOptions`, own texts, exit code 2 | The `flag` package also accepts `-debug`, prints its own usage text, and has its own messages | low | Port `LaunchOptions` by hand (130 lines) and do not use `flag`. | open |
| 26 | Icon | PNGs in four sizes given to the stage | Windows wants an `.ico` in a resource compiled into the `.exe` (`.syso`), macOS an `.icns` in an app bundle; a bare macOS binary has no Dock icon | low | Extend `draw-icon.cjs` to write `.ico` and `.icns`; `go-winres` or `rsrc` in the build. | open |
| 27 | Where the files are | The working directory, which is the project root for `./gradlew run` | A double-clicked `.exe` or a shortcut has a working directory nobody chose (it can be `C:\Windows\System32`) | medium | Decide before the port starts: the directory of the `.exe`, or the user's configuration directory, and keep the working directory only when started from a terminal. This changes About, the README and the requirements. | open |
| 28 | Build without installing anything | Gradle wrapper and toolchain fetch Gradle and the JDK | Go must be installed; `go.mod` can then fetch the exact Go version. With cgo (macOS, or webview_go on Windows) a C compiler is needed as well | low | State it. Without cgo on Windows the promise "a clone and one command" nearly holds. | open |
| 29 | Tests of the window | `TitleBarWindowTest`, `PanelWindowTest` and three probes drive real JavaFX windows | They test JavaFX objects and cannot be ported; they have to be written anew against the new host | medium | Keep the rule: asked for by a variable, never in the normal run. Keep the pure parts (`WindowFit`, `RememberedHeights`) pure, as now; their tests port directly. | open |
| 30 | Stand-in for `claude` in tests | `FakeClaude`, a Java `main` started as a subprocess with the test class path | Go has no class path to start a second `main` from | low | The usual pattern: the test binary starts itself with an environment variable and plays `claude`. | open; `FakeClaude` now also starts a child |
| 31 | Check that the frontend calculates nothing, and the style rules | Tested with Node, outside the JVM | Nothing to port, but the Go build needs a task that runs `node --test` | low | A `Makefile` target, as `frontendTest` is now. | open |
| 32 | Bringing the window to the front | On top for 400 ms | Windows lets a program to the front only under conditions; the same trick works with `HWND_TOPMOST`, to be checked when started from a terminal, from the Explorer and from an IDE | low | Part of the spike. | open |
| 33 | Line endings | `System.lineSeparator()` for `settings.json` and inside one log message; a fixed line feed for the CSV | Go has no such constant | low | Decide per file and write it down; the CSV rule is already exact. | open |
| 34 | Size of the port's verification | 11,300 lines of tests are the real specification of many details | They do not run against Go. Porting them is as much work as porting the code, and skipping them loses the details | medium | Port tests with each package (step 4). `ApiTest` can be made language-neutral: it speaks only HTTP, so it could be kept once and run against both programs. | open; about 12,500 lines of tests now |

## What becomes easier in Go

- **One file to hand out.** A single `.exe` of 10 to 15 MB with the page and the icon in it, starting in a fraction of a
  second, in place of a JDK, JavaFX and Gradle. This is the reason for the port.
- **The title bar** (problem 2) once the native call exists, and the clipboard.
- **Logging and stopping**: no warnings about modules and native access. (The log manager to outwit and the
  class-loading order to keep are gone from the Java program too, since version 0.73.)
- **Finding the program on Windows**, if `LookPath` is shown to agree with the requirement.
- **Concurrency**: goroutines and `context` in place of virtual threads, `CompletableFuture` and a daemon
  thread. `UsageService` is already a loop with messages; in Go it loses the queue and the futures for a channel.
- **Failures**: Go's errors carry their kind, so `Failure` needs no reading of a number from a text, and can name a
  file in use and a full disk.
- **The language of the machine**: nothing to guard against.

## Open decisions before the port starts

1. Windows only at first, or Windows and macOS together? (Decides the window library and whether cgo is needed.)
2. Which window library? (Recommendation above; to be proven by the spike.)
3. Tooltips: the system's size, or an own tooltip window? (Problem 3.)
4. Where are the settings, the log and the history, for a program started by double click? (Problem 27.)
5. Are the HTTP tests of the API kept once for both programs? (Problem 34.)

Decided since the analysis: the lock file of the log is not kept (problem 22), and is gone from the Java program; the
file names and the log's start line keep `java-aip-usage`, in every version; and both versions read and write the
same files in the same forms, `settings.json` among them, so that one can go on where the other ran (problems 10
and 11 are requirements now, and the Go version meets them by translating the Java program's own code).

## Outstanding in the Java program

One thing, before the Java program is the finished template: `ProcessTree` ends a process tree with `pgrep` and
`kill` on macOS, and that has been tested for the commands it builds and their order only, never with a real tree
there. To be run on a Mac: `./gradlew test --tests '*ClaudeTokenProviderTest' --tests '*ProcessTreeTest'`.
