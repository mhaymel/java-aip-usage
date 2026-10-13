package org.example;

import org.example.log.Log;
import org.example.json.Json;
import org.example.json.JsonException;
import org.example.json.JsonReader;
import org.example.json.JsonWriter;
import org.example.money.Amount;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.example.settings.IntervalRange;
import org.example.settings.IntervalSettings;
import org.example.settings.CostControl;
import org.example.settings.InvalidSettingException;
import org.example.settings.LogLevel;
import org.example.settings.Settings;
import org.example.settings.SettingsException;
import org.example.settings.TimeFormat;
import org.example.usage.HistoryDeltas;
import org.example.usage.HistoryReader;
import org.example.usage.Spend;
import org.example.usage.UsageService;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * The JSON API the frontend talks to; its contract is in {@code docs/api.md}.
 *
 * <p>Deliberately small: two reads and two actions. Reading the status never
 * causes a request to Anthropic; only {@code POST /api/refresh} can, and it
 * answers before the fetch is done. Nothing here ever touches a credential, so
 * nothing in a response can leak one.
 */
final class ApiHandler implements HttpHandler {

    private static final Log LOG = Log.of(ApiHandler.class);

    /** A settings update is a few dozen bytes; anything larger is not one. */
    private static final int MAX_BODY_BYTES = 4096;

    private static final String SOURCE = "anthropic-oauth-usage";

    /** What hovering over the first line of a run says. */
    private static final String START_TOOLTIP = "The program started here";

    /** How much of the log and of the history the windows show: the newest of each. */
    static final int LOG_LINES = 1000;

    static final int LOG_BYTES = 512 * 1024;

    static final int HISTORY_ROWS = 1000;

    private static final String USAGE_KEY = "usageIntervalSeconds";

    private final UsageService service;

    private final IntervalSettings settings;

    private final AppFiles files;

    private final UsageSources sources;

    /** The status is polled every second; the history is read again only when its file has changed. */
    private final LatestChangeCache latestChangeCache = new LatestChangeCache();

    /** @param sources where usage comes from at the moment, which in debug mode the debug settings change */
    ApiHandler(UsageService service, IntervalSettings settings, AppFiles files, UsageSources sources) {
        this.service = service;
        this.settings = settings;
        this.files = files;
        this.sources = sources;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (ApiException e) {
                if (e.allow != null) {
                    exchange.getResponseHeaders().set("Allow", e.allow);
                }
                send(exchange, e.status, Json.object().put("error", Failure.of(e)));
            } catch (RuntimeException e) {
                LOG.warning("API request failed: " + exchange.getRequestURI().getPath(), e);
                send(exchange, 500, Json.object().put("error", "Internal error; see the log."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        switch (exchange.getRequestURI().getPath()) {
            case "/api/config" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, config().toJson());
            }
            case "/api/debug" -> {
                // The debug settings are there in debug mode only; without it there is no such thing to ask for.
                if (!sources.debug()) {
                    throw new ApiException(404, "No such endpoint.", null);
                }
                if (method.equals("GET")) {
                    send(exchange, 200, debugBody().toJson());
                } else if (method.equals("POST")) {
                    updateDebug(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/settings" -> {
                if (method.equals("GET")) {
                    send(exchange, 200, settingsBody().toJson());
                } else if (method.equals("POST")) {
                    updateSettings(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/status" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, status(service.state()).toJson());
            }
            case "/api/log" -> {
                if (method.equals("GET")) {
                    send(exchange, 200, log(levelAsked(exchange.getRequestURI().getQuery())).toJson());
                } else if (method.equals("POST")) {
                    updateLog(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/log/reset", "/api/log/merge" -> {
                if (!method.equals("POST")) {
                    throw new ApiException(405, "Use POST.", "POST");
                }
                requireJson(exchange);
                logAction(exchange, exchange.getRequestURI().getPath().endsWith("/reset"));
            }
            case "/api/text-size" -> {
                if (!method.equals("POST")) {
                    throw new ApiException(405, "Use POST.", "POST");
                }
                updateTextSize(exchange);
            }
            case "/api/cost" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, cost().toJson());
            }
            case "/api/about" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, about().toJson());
            }
            case "/api/about/open", "/api/about/copy" -> {
                if (!method.equals("POST")) {
                    throw new ApiException(405, "Use POST.", "POST");
                }
                requireJson(exchange);
                aboutAction(exchange, exchange.getRequestURI().getPath().endsWith("/open"));
            }
            case "/api/cost/daily" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, dailyLimit(exchange.getRequestURI().getQuery()).toJson());
            }
            case "/api/errors" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, errors().toJson());
            }
            case "/api/history" -> {
                if (method.equals("GET")) {
                    send(exchange, 200, history(zeroLinesAsked(exchange.getRequestURI().getQuery())).toJson());
                } else if (method.equals("POST")) {
                    updateHistory(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/refresh" -> {
                if (!method.equals("POST")) {
                    throw new ApiException(405, "Use POST.", "POST");
                }
                requireJson(exchange);
                refresh(exchange);
            }
            default -> throw new ApiException(404, "No such endpoint.", null);
        }
    }

    // ---- /api/config

    private ConfigBody config() {
        return new ConfigBody(
                settings.usageSeconds(),
                settings.pollSeconds(),
                new Limits(new Range(IntervalRange.USAGE.min(), IntervalRange.USAGE.max())),
                sources.debug(),
                settings.current().listFontSize());
    }

    // ---- /api/debug: the debug settings, in debug mode only

    private DebugBody debugBody() {
        Settings now = settings.current();
        org.example.fake.FakeOptions fake = sources.fakeOptions();
        return new DebugBody(
                new DebugValues(now.fakeBackend(), now.fakeScenario().optionName(), now.fakeToken(),
                        fake.account().json(), fake.increaseCents(), Amount.ofCents(fake.budgetCents()).plain(),
                        Amount.ofCents(fake.startCents()).plain(), fake.refuseSeconds(), fake.delayMillis()),
                java.util.Arrays.stream(org.example.fake.Scenario.values()).map(org.example.fake.Scenario::optionName).toList(),
                java.util.Arrays.stream(org.example.fake.FakeOptions.Account.values()).map(org.example.fake.FakeOptions.Account::json).toList());
    }

    /**
     * How the fake backend's ordinary answer is shaped, from a request. A value that is not given stays what it is; one
     * that is given and cannot be is refused, and so are options that do not go together.
     */
    private org.example.fake.FakeOptions fakeOptions(Json body) {
        org.example.fake.FakeOptions now = sources.fakeOptions();
        org.example.fake.FakeOptions.Account account = now.account();
        Json kind = body.get("fakeAccount");
        if (kind != null && !kind.isNull()) {
            account = (kind.isText() ? org.example.fake.FakeOptions.Account.fromJson(kind.text())
                    : java.util.Optional.<org.example.fake.FakeOptions.Account>empty())
                    .orElseThrow(() -> new InvalidSettingException(
                            "The setting fakeAccount must be one of " + org.example.fake.FakeOptions.Account.names() + "."));
        }
        org.example.fake.FakeOptions wanted = new org.example.fake.FakeOptions(
                account,
                wholeOf(body, "fakeIncreaseCents", now.increaseCents(), "The largest increase must be a whole number of cents from 0 to "
                        + org.example.fake.FakeOptions.MAX_INCREASE_CENTS + "."),
                centsOf(body, "fakeBudget", now.budgetCents(), "The budget must be an amount from 0.01 to 1,000,000."),
                centsOf(body, "fakeStartAmount", now.startCents(), "The starting amount must be an amount from 0 to the budget."),
                wholeOf(body, "fakeRefuseSeconds", now.refuseSeconds(), "The time within which a request is refused must be a whole number of seconds from 0 to "
                        + org.example.fake.FakeOptions.MAX_REFUSE_SECONDS + "."),
                wholeOf(body, "fakeDelayMillis", now.delayMillis(), "The longest request must be a whole number of milliseconds from 0 to "
                        + org.example.fake.FakeOptions.MAX_DELAY_MILLIS + "."));
        wanted.fault().ifPresent(fault -> {
            throw new InvalidSettingException(fault);
        });
        return wanted;
    }

    private static int wholeOf(Json body, String key, int kept, String refusal) {
        Json value = body.get(key);
        if (value == null || value.isNull()) {
            return kept;
        }
        if (!value.fitsInt()) {
            throw new InvalidSettingException(refusal);
        }
        return value.intValue();
    }

    /**
     * An amount in dollars with two decimals at most, as cents. It may be a number or the text of one, `250` or
     * `"250.00"`: the page sends what was typed, and works nothing out.
     */
    private static long centsOf(Json body, String key, long kept, String refusal) {
        Json value = body.get(key);
        if (value == null || value.isNull()) {
            return kept;
        }
        try {
            Amount amount;
            if (value.isNumber()) {
                amount = Amount.parse(value.numberText());
            } else if (value.isText() && value.text().matches("\\d{1,9}(\\.\\d{1,2})?")) {
                amount = Amount.parse(value.text());
            } else {
                throw new InvalidSettingException(refusal);
            }
            return amount.exactCents();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new InvalidSettingException(refusal);
        }
    }

    /**
     * The three switches must be given; how the fake backend answers may be, and what is not given of that stays as it is.
     * They are saved and take effect at once: the source of usage is opened anew when a switch changed, the fake backend
     * that runs is told when only its answer did, and a refresh follows.
     */
    private void updateDebug(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        Json body = readJson(exchange);
        try {
            boolean fakeBackend = flag(body, "fakeBackend");
            Json name = body.get("fakeScenario");
            if (name == null || name.isNull()) {
                throw new InvalidSettingException("The setting fakeScenario is missing.");
            }
            org.example.fake.Scenario scenario =
                    (name.isText() ? org.example.fake.Scenario.ofOptionName(name.text()) : java.util.Optional.<org.example.fake.Scenario>empty())
                            .orElseThrow(() -> new InvalidSettingException(
                                    "The setting fakeScenario must be one of " + org.example.fake.Scenario.names() + "."));
            sources.applyDebug(fakeBackend, scenario, flag(body, "fakeToken"), fakeOptions(body));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, Failure.of(e), null);
        } catch (SettingsException e) {
            throw new ApiException(500, Failure.of(e), null);
        }
        send(exchange, 200, debugBody().toJson());
    }

    // ---- /api/settings

    /**
     * The settings as the window shows them: the interval is the wait the service is using, which after HTTP 429s is longer
     * than the configured one. The settings file keeps the configured one until a value is applied.
     */
    private Settings shown() {
        Settings current = settings.current();
        // While the program searches for the interval itself, the one it has found is in force, shorter or longer than the configured one.
        return current.withUsageIntervalSeconds(current.autoInterval()
                ? service.effectiveIntervalSeconds()
                : Math.max(current.usageIntervalSeconds(), service.effectiveIntervalSeconds()));
    }

    private SettingsBody settingsBody() {
        return new SettingsBody(
                toBody(shown()),
                toBody(Settings.defaults()),
                new Limits(new Range(IntervalRange.USAGE.min(), IntervalRange.USAGE.max())),
                settings.current().usageIntervalSeconds(),
                settings.current().cost().dailyIn(java.time.YearMonth.now()).map(CostControl::text).orElse(null));
    }

    /**
     * The limits of cost control, from a request. A key that is not given keeps what there is; a limit given as null or
     * as an empty text is no limit; one given as an amount, a number or the text of one, is that amount.
     */
    private static CostControl costOf(Json body, CostControl kept) {
        Long monthly = body.has("costMonthlyLimit") ? limitOf(body.get("costMonthlyLimit")) : kept.monthlyCents();
        Long daily = body.has("costDailyLimit") ? limitOf(body.get("costDailyLimit")) : kept.dailyCents();
        Json automatic = body.get("costDailyAutomatic");
        if (automatic != null && !automatic.isNull() && !automatic.isBool()) {
            throw new InvalidSettingException("The setting costDailyAutomatic must be true or false.");
        }
        CostControl wanted = new CostControl(monthly, daily, automatic == null || automatic.isNull() ? kept.dailyAutomatic() : automatic.bool());
        wanted.fault().ifPresent(fault -> {
            throw new InvalidSettingException(fault);
        });
        return wanted;
    }

    private static Long limitOf(Json value) {
        if (value == null || value.isNull() || (value.isText() && value.text().isBlank())) {
            return null;
        }
        try {
            if (value.isNumber()) {
                return Amount.parse(value.numberText()).exactCents();
            }
            if (value.isText()) {
                return CostControl.cents(value.text()).orElseThrow(() -> new InvalidSettingException(CostControl.AMOUNT_FAULT));
            }
        } catch (ArithmeticException | NumberFormatException e) {
            // More than two decimals.
        }
        throw new InvalidSettingException(CostControl.AMOUNT_FAULT);
    }

    /**
     * The daily limit that follows from a monthly one in the current month, for the settings view to show while the
     * daily limit is worked out: `GET /api/cost/daily?monthly=250`. The page works nothing out itself.
     */
    private static DailyLimitBody dailyLimit(String query) {
        String monthly = "";
        for (String part : query == null ? new String[0] : query.split("&")) {
            if (part.startsWith("monthly=")) {
                monthly = java.net.URLDecoder.decode(part.substring("monthly=".length()), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        java.time.YearMonth month = java.time.YearMonth.now();
        if (monthly.isBlank()) {
            return new DailyLimitBody(null, CostControl.weekdays(month));
        }
        long cents = CostControl.cents(monthly).orElseThrow(() -> new ApiException(400, CostControl.AMOUNT_FAULT, null));
        if (cents < CostControl.MIN_CENTS || cents > CostControl.MAX_CENTS) {
            throw new ApiException(400, CostControl.AMOUNT_FAULT, null);
        }
        return new DailyLimitBody(CostControl.text(CostControl.dailyFor(cents, month)), CostControl.weekdays(month));
    }

    private static SettingValues toBody(Settings s) {
        return new SettingValues(s.usageIntervalSeconds(), s.logResponse(), s.showPercentage(), s.showCurrency(), s.showHistoryIcon(), s.showLogIcon(),
                s.showErrorIcon(), s.showInterval(), s.showDeltaUsed(),
                s.showDeltaTime(), s.timeFormat().json(), s.historyDeltaUsed(), s.historyDeltaTime(), s.historyDate(),
                s.historyZeroLines(), s.historyFailedLines(), s.showTime(), s.showUsed(), s.showLimit(), s.showRefresh(), s.showCountdown(),
                s.showDuration(), s.stayOnTop(), s.showTitleBar(), s.autoInterval(),
                s.cost().monthlyCents() == null ? null : CostControl.text(s.cost().monthlyCents()),
                s.cost().dailyCents() == null ? null : CostControl.text(s.cost().dailyCents()),
                s.cost().dailyAutomatic(),
                s.historyTime(), s.historyUsed(), s.historyLimit(), s.historyCurrent(), s.restoreWindow(), s.showCostIcon());
    }

    /** All the settings must be given, so that what is applied is exactly what the view showed. */
    private void updateSettings(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        Json body = readJson(exchange);
        try {
            Long usage = seconds(body, USAGE_KEY, IntervalRange.USAGE);
            if (usage == null) {
                throw new InvalidSettingException("The setting " + USAGE_KEY + " is missing.");
            }
            Settings current = settings.current();
            Settings given = new Settings(
                    usage.intValue(),
                    flag(body, "logResponse"),
                    flag(body, "showPercentage"),
                    flag(body, "showCurrency"),
                    flag(body, "showHistoryIcon"),
                    flag(body, "showLogIcon"),
                    flag(body, "showErrorIcon"),
                    flag(body, "showInterval"),
                    flag(body, "showDeltaUsed"),
                    flag(body, "showDeltaTime"),
                    timeFormat(body),
                    flag(body, "historyDeltaUsed"),
                    flag(body, "historyDeltaTime"),
                    flag(body, "historyDate"),
                    flag(body, "historyZeroLines"),
                    flag(body, "historyFailedLines"),
                    flag(body, "showTime"),
                    flag(body, "showUsed"),
                    flag(body, "showLimit"),
                    flag(body, "showRefresh"),
                    flag(body, "showCountdown"),
                    flag(body, "showDuration"),
                    flag(body, "stayOnTop"),
                    flag(body, "showTitleBar"),
                    false, org.example.fake.Scenario.NORMAL, false, LogLevel.INFO, false, Settings.DEFAULT_LIST_FONT_SIZE,
                    flag(body, "autoInterval"),
                    costOf(body, settings.current().cost()),
                    flag(body, "historyTime", current.historyTime()),
                    flag(body, "historyUsed", current.historyUsed()),
                    flag(body, "historyLimit", current.historyLimit()),
                    flag(body, "historyCurrent", current.historyCurrent()),
                    flag(body, "restoreWindow", current.restoreWindow()),
                    flag(body, "showCostIcon", current.showCostIcon()));
            // The debug settings are not among these: they are kept as they are, and changed by /api/debug alone. Nor are
            // the two choices of the log panel, which POST /api/log changes, and the size of the lists' text, which POST /api/text-size changes.
            Settings kept = settings.current();
            settings.apply(given.withDebug(kept.fakeBackend(), kept.fakeScenario(), kept.fakeToken()).withLog(kept.logLevel(), kept.logDate())
                    .withListFontSize(kept.listFontSize()));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, Failure.of(e), null);
        } catch (SettingsException e) {
            throw new ApiException(500, Failure.of(e), null);
        }
        send(exchange, 200, settingsBody().toJson());
    }

    /** A flag that may be left out, which keeps what the settings have. */
    private static boolean flag(Json body, String key, boolean current) {
        Json value = body.get(key);
        return value == null || value.isNull() ? current : flag(body, key);
    }

    private static boolean flag(Json body, String key) {
        Json value = body.get(key);
        if (value == null || value.isNull()) {
            throw new InvalidSettingException("The setting " + key + " is missing.");
        }
        if (!value.isBool()) {
            throw new InvalidSettingException("The setting " + key + " must be true or false.");
        }
        return value.bool();
    }

    private static TimeFormat timeFormat(Json body) {
        Json value = body.get("timeFormat");
        if (value == null || value.isNull()) {
            throw new InvalidSettingException("The setting timeFormat is missing.");
        }
        return (value.isText() ? TimeFormat.fromJson(value.text()) : java.util.Optional.<TimeFormat>empty())
                .orElseThrow(() -> new InvalidSettingException("The setting timeFormat must be \"hh:mm\" or \"hh:mm:ss\"."));
    }

    /** The value of {@code key}, or {@code null} if absent. Only whole numbers qualify. */
    private static Long seconds(Json body, String key, IntervalRange range) {
        Json value = body.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.fitsLong()) {
            throw new InvalidSettingException(range.describeLimit());
        }
        return value.longValue();
    }

    // ---- /api/log and /api/history: read-only views of the two files

    /**
     * The end of the log at the level asked for, or at the saved one when none is asked for, with the two saved choices of
     * the panel: the page takes them when it shows the panel, and asks for the level it has chosen after that.
     */
    private LogBody log(LogLevel asked) {
        Settings now = settings.current();
        try {
            LogTail.Tail tail = LogTail.read(files.log(), LOG_LINES, LOG_BYTES, asked != null ? asked : now.logLevel());
            return new LogBody(files.log().getFileName().toString(), tail.exists(), tail.truncated(), tail.lines(), tail.hidden(),
                    now.logLevel().json(), now.logDate(), Logging.of(files.log()).map(Logging::hasOldLog).orElse(false));
        } catch (IOException e) {
            LOG.warning("Could not read the log", e);
            throw new ApiException(500, "The log could not be read.", null);
        }
    }

    /**
     * The two buttons of the log panel. Reset: the log file is kept under a name with the date and time, and a new one is
     * begun. Merge, the undo: the newest of the files so kept is put in front of the log again, and deleted. The page
     * names no file and is told no path; the body of the request is not read.
     */
    private void logAction(HttpExchange exchange, boolean reset) throws IOException {
        String failed = reset ? "The log could not be reset: " : "The log could not be merged: ";
        Logging logging = Logging.of(files.log()).orElseThrow(() -> new ApiException(500, failed + "the log is not written to a file", null));
        try {
            if (reset) {
                logging.reset(java.time.LocalDateTime.now());
            } else {
                logging.merge();
            }
        } catch (Logging.NoOldLog e) {
            throw new ApiException(409, "There is no old log.", null);
        } catch (IOException e) {
            LOG.warning(reset ? "Could not reset the log" : "Could not merge the log", e);
            throw new ApiException(500, failed + Failure.of(e), null);
        }
        send(exchange, 200, new LogFilesBody(logging.hasOldLog()).toJson());
    }

    /**
     * The size of the text of the three lists, which the buttons at the top of a list change. It is saved at once, and
     * the other settings are left as they are.
     */
    private void updateTextSize(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        Json size = readJson(exchange).get("size");
        String range = "The size must be a whole number of pixels from " + Settings.MIN_LIST_FONT_SIZE + " to " + Settings.MAX_LIST_FONT_SIZE + ".";
        if (size == null || !size.fitsInt() || !Settings.isListFontSize(size.intValue())) {
            throw new ApiException(400, range, null);
        }
        try {
            settings.apply(settings.current().withListFontSize(size.intValue()));
        } catch (SettingsException e) {
            throw new ApiException(500, "The text size could not be saved: " + Failure.of(e.getCause()), null);
        }
        send(exchange, 200, new TextSizeBody(settings.current().listFontSize()).toJson());
    }

    /** The cost control panel, finished: the two bars, worked out from the history in use and the limits. */
    private CostPanel.View cost() {
        try {
            Settings now = settings.current();
            HistoryReader.Table table = HistoryReader.read(sources.history(), Integer.MAX_VALUE);
            return CostPanel.build(table.rows(), now.cost(), now.showCurrency(), java.time.LocalDate.now());
        } catch (IOException e) {
            LOG.warning("Could not read the usage history", e);
            throw new ApiException(500, "The usage history could not be read.", null);
        }
    }

    /**
     * What the About view says of the program: its name and version, where its files are, what it runs on, and whether it
     * is in debug mode. The paths are full ones, which no other request gives: this is where the program says them, to
     * the person in front of it.
     */
    private AboutBody about() {
        return new AboutBody(
                AppInfo.NAME,
                AppInfo.VERSION,
                new AboutFiles(absolute(files.settings()), absolute(files.log()), absolute(sources.history())),
                new Named("Java", System.getProperty("java.version", "unknown")),
                // Set by JavaFX once its toolkit runs; a run with no window, as in a test, has none.
                new Named("JavaFX", System.getProperty("javafx.runtime.version", "unknown")),
                (System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "")).trim(),
                sources.debug(),
                sources.fake());
    }

    /**
     * Shows one of the program's three files in its folder, or puts its path on the clipboard. The request names the file,
     * `settings`, `log` or `history`, and never a path: the path is the one the program knows.
     */
    private void aboutAction(HttpExchange exchange, boolean open) throws IOException {
        Json name = readJson(exchange).get("file");
        java.nio.file.Path file = switch (name != null && name.isText() ? name.text() : "") {
            case "settings" -> files.settings();
            case "log" -> files.log();
            case "history" -> sources.history();
            default -> throw new ApiException(400, "The file must be one of settings, log, history.", null);
        };
        try {
            if (open) {
                sources.desk().reveal(file);
            } else {
                sources.desk().copy(absolute(file));
            }
        } catch (IOException | RuntimeException e) {
            String what = open ? "The folder could not be opened: " : "The path could not be copied: ";
            LOG.warning(what + Failure.of(e));
            throw new ApiException(500, what + Failure.of(e), null);
        }
        send(exchange, 200, Json.object().put("done", true));
    }

    private static String absolute(java.nio.file.Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    /** The level a request for the log names in its query, {@code level=warning}; none if it names none. */
    private static LogLevel levelAsked(String query) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("level=")) {
                return LogLevel.fromJson(part.substring("level=".length()))
                        .orElseThrow(() -> new ApiException(400, "The level must be one of " + LogLevel.names() + ".", null));
            }
        }
        return null;
    }

    /** The two choices of the log panel, both of which must be given. They are saved at once, and the other settings are left as they are. */
    private void updateLog(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        Json body = readJson(exchange);
        LogLevel level;
        try {
            Json name = body.get("level");
            if (name == null || name.isNull()) {
                throw new InvalidSettingException("The setting level is missing.");
            }
            level = (name.isText() ? LogLevel.fromJson(name.text()) : java.util.Optional.<LogLevel>empty())
                    .orElseThrow(() -> new InvalidSettingException("The setting level must be one of " + LogLevel.names() + "."));
            settings.apply(settings.current().withLog(level, flag(body, "date")));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, Failure.of(e), null);
        } catch (SettingsException e) {
            throw new ApiException(500, "The log settings could not be saved: " + Failure.of(e.getCause()), null);
        }
        Settings now = settings.current();
        send(exchange, 200, new LogSettingsBody(now.logLevel().json(), now.logDate()).toJson());
    }

    /** Whether the request names how it wants the zero usage lines, shown or hidden: {@code null} when it does not, and the setting decides. */
    private static Boolean zeroLinesAsked(String query) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("zeroLines=")) {
                String value = part.substring("zeroLines=".length());
                if (!value.equals("true") && !value.equals("false")) {
                    throw new ApiException(400, "zeroLines must be true or false.", null);
                }
                return value.equals("true");
            }
        }
        return null;
    }

    /**
     * The one choice of the history panel, whether its zero usage lines are shown: the setting of that name, which the button
     * of the panel switches. It is saved at once, and the other settings are left as they are.
     */
    private void updateHistory(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        Json body = readJson(exchange);
        try {
            settings.apply(settings.current().withHistoryZeroLines(flag(body, "zeroLines")));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, Failure.of(e), null);
        } catch (SettingsException e) {
            throw new ApiException(500, "The history settings could not be saved: " + Failure.of(e.getCause()), null);
        }
        send(exchange, 200, new HistorySettingsBody(settings.current().historyZeroLines()).toJson());
    }

    /**
     * The history, with its lines finished: the page only draws them. The settings decide which lines are left out (the zero usage and
     * the failed ones), whether the time has the date and which change columns there are; the changes are worked out on the lines
     * that are shown; and the note says whenever not everything is shown.
     *
     * @param zeroLines whether the zero usage lines are shown, when the request says so; {@code null} for what the setting says
     */
    private HistoryBody history(Boolean zeroLines) {
        try {
            Settings now = settings.current();
            HistoryReader.Table table = HistoryReader.read(
                    sources.history(), HISTORY_ROWS,
                    new HistoryReader.Filter(zeroLines != null ? zeroLines : now.historyZeroLines(), now.historyFailedLines()));
            // Each column is there when its setting is on, in the order of the view: the time, the amounts, the changes and the currency.
            // With each title goes what the column is, by a fixed name: the page gives a column its width by that, not by its place.
            // And the tooltip of each title, which says what the column is.
            List<String> columns = new java.util.ArrayList<>();
            List<String> kinds = new java.util.ArrayList<>();
            List<String> tips = new java.util.ArrayList<>();
            if (now.historyTime()) {
                columns.add(now.historyDate() ? "date time" : "time");
                kinds.add("time");
                tips.add(now.historyDate() ? "The date and time at which the usage was read" : "When the usage was read");
            }
            if (now.historyUsed()) {
                columns.add("used");
                kinds.add("used");
                tips.add("The amount used at that reading");
            }
            if (now.historyLimit()) {
                columns.add("limit");
                kinds.add("limit");
                tips.add("The budget at that reading");
            }
            if (now.historyDeltaUsed()) {
                columns.add("\u0394 used");
                kinds.add("deltaUsed");
                tips.add("The change in the amount used since the last earlier reading: + for a rise, - for a fall");
            }
            if (now.historyDeltaTime()) {
                columns.add("\u0394 time");
                kinds.add("deltaTime");
                tips.add("The seconds since the previous line that is shown");
            }
            if (now.historyCurrent()) {
                columns.add("Cur.");
                kinds.add("currency");
                tips.add("The currency of the amounts");
            }
            if (columns.isEmpty() && table.exists() && table.total() > 0) {
                // Every column is switched off, as the button in the heading of History view leaves them when all were on: there
                // is nothing a line could show. That is said, and not how many lines are shown, under which there would be none.
                return new HistoryBody(
                        sources.history().getFileName().toString(), true, columns, kinds, tips, now.historyDate(),
                        NO_HISTORY_COLUMN, true, table.total(), now.historyZeroLines(), List.of());
            }
            List<HistoryLine> lines = new java.util.ArrayList<>();
            for (int i = 0; i < table.rows().size(); i++) {
                List<String> row = table.rows().get(i);
                boolean failed = row.get(4).contains("failed");
                boolean start = row.get(4).startsWith("start");
                DeltaBody delta = deltaBody(table.deltas().get(i));
                List<String> cells = new java.util.ArrayList<>();
                if (now.historyTime()) {
                    cells.add(historyTime(row.get(0), now.historyDate()));
                }
                if (now.historyUsed()) {
                    cells.add(failed ? "failed" : row.get(1));
                }
                if (now.historyLimit()) {
                    cells.add(row.get(2));
                }
                if (now.historyDeltaUsed()) {
                    // The history shows every change but one of 0.00: a rise with its plus, a fall with its minus.
                    cells.add(delta.deltaUsedText() == null ? "" : delta.deltaUsedText());
                }
                if (now.historyDeltaTime()) {
                    cells.add(delta.deltaSecondsText() == null ? "" : delta.deltaSecondsText());
                }
                // US dollars as $, any other currency by its code.
                if (now.historyCurrent()) {
                    cells.add(Formatting.historyCurrency(row.get(3)));
                }
                lines.add(new HistoryLine(cells, start, failed, start ? START_TOOLTIP : ""));
            }
            return new HistoryBody(
                    sources.history().getFileName().toString(), table.exists(), columns, kinds, tips, now.historyDate(),
                    historyNote(table), historyNoteHighlighted(table), table.total(), now.historyZeroLines(), lines);
        } catch (IOException e) {
            LOG.warning("Could not read the usage history", e);
            throw new ApiException(500, "The usage history could not be read.", null);
        }
    }

    /** What the history says in the place of its table when no column of it is switched on. */
    static final String NO_HISTORY_COLUMN = "No column of the history is switched on. Switch one on in the settings, under History view.";

    /** The one line above the table: why there is nothing, or what is left out; {@code null} when everything is shown. */
    private static String historyNote(HistoryReader.Table table) {
        if (!table.exists()) {
            return "There is no usage history yet.";
        }
        if (table.total() == 0) {
            return "The history has no rows yet.";
        }
        int older = table.visible() - table.rows().size();
        if (table.hiddenZero() + table.hiddenFailed() + older == 0) {
            return null;
        }
        List<String> hidden = new java.util.ArrayList<>();
        if (table.hiddenZero() > 0) {
            hidden.add(count(table.hiddenZero()) + " zero usage");
        }
        if (table.hiddenFailed() > 0) {
            hidden.add(count(table.hiddenFailed()) + " failed");
        }
        List<String> parts = new java.util.ArrayList<>();
        if (!hidden.isEmpty()) {
            parts.add(String.join(" and ", hidden) + " hidden");
        }
        if (older > 0) {
            parts.add(count(older) + " older not shown");
        }
        return "Showing " + count(table.rows().size()) + " of " + count(table.total()) + " lines: " + String.join(", ", parts) + ".";
    }

    /** Whether the note says that not everything is shown, which the window shows in blue; the notes about there being no history are not. */
    private static boolean historyNoteHighlighted(HistoryReader.Table table) {
        return table.exists() && table.total() > 0
                && table.hiddenZero() + table.hiddenFailed() + (table.visible() - table.rows().size()) > 0;
    }

    /** A count with a comma for thousands, whatever the machine's language, like the amounts. */
    private static String count(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    // ---- /api/errors: the errors of this run, newest first

    private ErrorsBody errors() {
        ZoneId zone = ZoneId.systemDefault();
        return new ErrorsBody(service.errors().newestFirst().stream()
                .map(e -> new ErrorEntryBody(Formatting.time(e.at(), TimeFormat.HOURS_MINUTES_SECONDS, zone), e.message()))
                .toList());
    }

    // ---- /api/status

    /** {@code 2026-10-08 21:01:22} as {@code 21:01:22}, or whole when the date is wanted; anything else as it is. */
    private static String historyTime(String raw, boolean withDate) {
        boolean shaped = raw.length() >= 19 && raw.charAt(10) == ' ';
        return withDate || !shaped ? raw : raw.substring(11);
    }

    private static DeltaBody deltaBody(HistoryDeltas.Delta delta) {
        return DeltaBody.of(delta.used(), delta.seconds());
    }

    private DeltaBody latestChange() {
        try {
            return latestChangeCache.get(sources.history());
        } catch (IOException e) {
            LOG.warning("Could not read the usage history for the change", e);
            return null;
        }
    }

    private OptionalLong duration() {
        long millis = sources.latestDurationMillis();
        return millis < 0 ? OptionalLong.empty() : OptionalLong.of(millis);
    }

    private StatusBody status(UsageState state) {
        UsageSnapshot snapshot = state.snapshot();
        OptionalLong countdown = service.secondsUntilNextRefresh();
        DeltaBody change = snapshot == null ? null : latestChange();
        return new StatusBody(
                state.refreshing(),
                state.stale(),
                countdown.isPresent() ? countdown.getAsLong() : null,
                state.error() == null ? null : new ErrorBody(state.error(), state.errorAt().toString()),
                snapshot == null ? null : usage(snapshot),
                change,
                StatusDisplay.build(state, countdown, change, duration(), shown(), Instant.now(), ZoneId.systemDefault()),
                historyStamp(),
                sources.fake());
    }

    /**
     * Changes whenever a row is added to the history file, a failed one included, so that a window that shows the history knows to read it
     * again. It is the file's size and time, which is all that is needed to tell that the file is not the same; it is empty when there is no file.
     */
    private String historyStamp() {
        Path history = sources.history();
        try {
            return Files.isRegularFile(history) ? Files.size(history) + "-" + Files.getLastModifiedTime(history).toMillis() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static UsageBody usage(UsageSnapshot snapshot) {
        Spend spend = snapshot.spend();
        return new UsageBody(
                SOURCE,
                snapshot.fetchedAt().toString(),
                spend == null
                        ? null
                        : new SpendBody(spend.used(), spend.limit(), spend.currency(), spend.percent(), spend.severity()),
                // Always empty: it is there so that a reading has the shape java-aip prints.
                List.of());
    }

    // ---- /api/refresh

    private void refresh(HttpExchange exchange) throws IOException {
        boolean started = service.refreshNow();
        if (started) {
            LOG.info("Manual refresh requested");
        }
        // 202: the fetch has been started, not finished. 200: one was already under way.
        send(exchange, started ? 202 : 200, new RefreshBody(started).toJson());
    }

    // ---- plumbing

    /**
     * Every POST must declare JSON. A web page on another origin cannot do
     * that without a CORS preflight, which this server never grants, so no
     * other page can drive these actions.
     */
    private static void requireJson(HttpExchange exchange) {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new ApiException(415, "Send Content-Type: application/json.", null);
        }
    }

    private Json readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "The request body is too large.", null);
        }
        Json body;
        try {
            body = JsonReader.read(bytes);
        } catch (JsonException e) {
            throw new ApiException(400, "The request body is not valid JSON.", null);
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "The request body must be a JSON object.", null);
        }
        return body;
    }

    private void send(HttpExchange exchange, int status, Json body) throws IOException {
        byte[] bytes = JsonWriter.compact(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class ApiException extends RuntimeException {

        final int status;

        final String allow;

        ApiException(int status, String message, String allow) {
            super(message);
            this.status = status;
            this.allow = allow;
        }
    }

    // ---- response shapes: the contract in docs/api.md. Each writes its own JSON, key by key, in the order of the contract.

    /** An amount as a number of JSON, exactly: {@code 186.02}, {@code 1000.0}; null for none. */
    private static Json number(Amount amount) {
        return amount == null ? Json.NULL : Json.number(amount.number());
    }

    record Range(int min, int max) {

        Json toJson() {
            return Json.object().put("min", min).put("max", max);
        }
    }

    record Limits(Range usageIntervalSeconds) {

        Json toJson() {
            return Json.object().put("usageIntervalSeconds", usageIntervalSeconds.toJson());
        }
    }

    /** @param listFontSize the size of the text of the lists, in pixels, as saved: the page has it from here when it starts */
    record ConfigBody(int usageIntervalSeconds, int pollIntervalSeconds, Limits limits, boolean debug, int listFontSize) {

        Json toJson() {
            return Json.object()
                    .put("usageIntervalSeconds", usageIntervalSeconds)
                    .put("pollIntervalSeconds", pollIntervalSeconds)
                    .put("limits", limits.toJson())
                    .put("debug", debug)
                    .put("listFontSize", listFontSize);
        }
    }

    record TextSizeBody(int size) {

        Json toJson() {
            return Json.object().put("size", size);
        }
    }

    /**
     * @param runtime what the program is built on, by its name and with its version; the name is this version's own to say
     * @param toolkit the toolkit of the window, likewise; its version is {@code unknown} in a run that has no window
     * @param fake whether the fake backend is in use, which it can be only in debug mode
     */
    record AboutBody(String name, String version, AboutFiles files, Named runtime, Named toolkit, String system, boolean debug, boolean fake) {

        Json toJson() {
            return Json.object()
                    .put("name", name)
                    .put("version", version)
                    .put("files", files.toJson())
                    .put("runtime", runtime.toJson())
                    .put("toolkit", toolkit.toJson())
                    .put("system", system)
                    .put("debug", debug)
                    .put("fake", fake);
        }
    }

    /** A thing the program runs on, as the About view names it: what it is called, and its version. */
    record Named(String name, String version) {

        Json toJson() {
            return Json.object().put("name", name).put("version", version);
        }
    }

    /** The full paths of the three files; the history is the one in use, that of the invented readings while the fake backend is. */
    record AboutFiles(String settings, String log, String history) {

        Json toJson() {
            return Json.object().put("settings", settings).put("log", log).put("history", history);
        }
    }

    /** @param fakeBudget in dollars, as a finished text with two decimals, as is {@code fakeStartAmount}; the increase is in cents */
    record DebugValues(boolean fakeBackend, String fakeScenario, boolean fakeToken, String fakeAccount, int fakeIncreaseCents,
            String fakeBudget, String fakeStartAmount, int fakeRefuseSeconds, int fakeDelayMillis) {

        Json toJson() {
            return Json.object()
                    .put("fakeBackend", fakeBackend)
                    .put("fakeScenario", fakeScenario)
                    .put("fakeToken", fakeToken)
                    .put("fakeAccount", fakeAccount)
                    .put("fakeIncreaseCents", fakeIncreaseCents)
                    .put("fakeBudget", fakeBudget)
                    .put("fakeStartAmount", fakeStartAmount)
                    .put("fakeRefuseSeconds", fakeRefuseSeconds)
                    .put("fakeDelayMillis", fakeDelayMillis);
        }
    }

    /** @param accounts the kinds of account the fake backend can answer for, as {@code scenarios} are what it can answer */
    record DebugBody(DebugValues settings, List<String> scenarios, List<String> accounts) {

        Json toJson() {
            return Json.object()
                    .put("settings", settings.toJson())
                    .put("scenarios", Json.texts(scenarios))
                    .put("accounts", Json.texts(accounts));
        }
    }

    record SettingValues(
            int usageIntervalSeconds,
            boolean logResponse,
            boolean showPercentage,
            boolean showCurrency,
            boolean showHistoryIcon,
            boolean showLogIcon,
            boolean showErrorIcon,
            boolean showInterval,
            boolean showDeltaUsed,
            boolean showDeltaTime,
            String timeFormat,
            boolean historyDeltaUsed,
            boolean historyDeltaTime,
            boolean historyDate,
            boolean historyZeroLines,
            boolean historyFailedLines,
            boolean showTime,
            boolean showUsed,
            boolean showLimit,
            boolean showRefresh,
            boolean showCountdown,
            boolean showDuration,
            boolean stayOnTop,
            boolean showTitleBar,
            boolean autoInterval,
            String costMonthlyLimit,
            String costDailyLimit,
            boolean costDailyAutomatic,
            boolean historyTime,
            boolean historyUsed,
            boolean historyLimit,
            boolean historyCurrent,
            boolean restoreWindow,
            boolean showCostIcon) {

        Json toJson() {
            return Json.object()
                    .put("usageIntervalSeconds", usageIntervalSeconds)
                    .put("logResponse", logResponse)
                    .put("showPercentage", showPercentage)
                    .put("showCurrency", showCurrency)
                    .put("showHistoryIcon", showHistoryIcon)
                    .put("showLogIcon", showLogIcon)
                    .put("showErrorIcon", showErrorIcon)
                    .put("showInterval", showInterval)
                    .put("showDeltaUsed", showDeltaUsed)
                    .put("showDeltaTime", showDeltaTime)
                    .put("timeFormat", timeFormat)
                    .put("historyDeltaUsed", historyDeltaUsed)
                    .put("historyDeltaTime", historyDeltaTime)
                    .put("historyDate", historyDate)
                    .put("historyZeroLines", historyZeroLines)
                    .put("historyFailedLines", historyFailedLines)
                    .put("showTime", showTime)
                    .put("showUsed", showUsed)
                    .put("showLimit", showLimit)
                    .put("showRefresh", showRefresh)
                    .put("showCountdown", showCountdown)
                    .put("showDuration", showDuration)
                    .put("stayOnTop", stayOnTop)
                    .put("showTitleBar", showTitleBar)
                    .put("autoInterval", autoInterval)
                    .put("costMonthlyLimit", costMonthlyLimit)
                    .put("costDailyLimit", costDailyLimit)
                    .put("costDailyAutomatic", costDailyAutomatic)
                    .put("historyTime", historyTime)
                    .put("historyUsed", historyUsed)
                    .put("historyLimit", historyLimit)
                    .put("historyCurrent", historyCurrent)
                    .put("restoreWindow", restoreWindow)
                    .put("showCostIcon", showCostIcon);
        }
    }

    /** @param daily the daily limit worked out, as a finished text, or null when there is no monthly limit to work it out from */
    record DailyLimitBody(String daily, int weekdays) {

        Json toJson() {
            return Json.object().put("daily", daily).put("weekdays", weekdays);
        }
    }

    /**
     * @param costDailyWorkedOut the daily limit in force this month, as a finished text: the one worked out from the monthly
     *     limit while that is asked for, else the one typed in; null when there is none
     * @param configuredIntervalSeconds the interval that is saved, which {@code settings.usageIntervalSeconds} is not while
     *     a back-off or the automatic interval has another in force
     */
    record SettingsBody(SettingValues settings, SettingValues defaults, Limits limits, int configuredIntervalSeconds, String costDailyWorkedOut) {

        Json toJson() {
            return Json.object()
                    .put("settings", settings.toJson())
                    .put("defaults", defaults.toJson())
                    .put("limits", limits.toJson())
                    .put("configuredIntervalSeconds", configuredIntervalSeconds)
                    .put("costDailyWorkedOut", costDailyWorkedOut);
        }
    }

    record RefreshBody(boolean started) {

        Json toJson() {
            return Json.object().put("started", started);
        }
    }

    /**
     * @param hidden whether the file has lines and none of the level asked for
     * @param level the level saved for the panel, whatever level was asked for
     * @param date whether the panel shows the date of each line, as saved
     */
    /** After a reset or a merge of the log: whether there is an old log to put back. */
    record LogFilesBody(boolean oldLog) {

        Json toJson() {
            return Json.object().put("oldLog", oldLog);
        }
    }

    /** @param oldLog whether there is an old log, kept by a reset, that a merge would put back */
    record LogBody(String file, boolean exists, boolean truncated, List<String> lines, boolean hidden, String level, boolean date, boolean oldLog) {

        Json toJson() {
            return Json.object()
                    .put("file", file)
                    .put("exists", exists)
                    .put("truncated", truncated)
                    .put("lines", Json.texts(lines))
                    .put("hidden", hidden)
                    .put("level", level)
                    .put("date", date)
                    .put("oldLog", oldLog);
        }
    }

    /** The one choice of the history panel as it is saved now. */
    record HistorySettingsBody(boolean zeroLines) {

        Json toJson() {
            return Json.object().put("zeroLines", zeroLines);
        }
    }

    record LogSettingsBody(String level, boolean date) {

        Json toJson() {
            return Json.object().put("level", level).put("date", date);
        }
    }

    /** What changed since the row before: the amount used, and the seconds; either is null if it cannot be worked out. */
    record DeltaBody(Amount deltaUsed, Long deltaTime, String deltaUsedText, String deltaSecondsText) {

        static DeltaBody of(Amount used, Long seconds) {
            return new DeltaBody(
                    used, seconds,
                    // A change of nothing says nothing: no text for it, so no cell and no item show it.
                    used == null || used.cents() == 0 ? null : Formatting.signedAmount(used),
                    // The time is in whole seconds, in the history and in the row, never in minutes.
                    seconds == null ? null : Formatting.seconds(seconds));
        }

        Json toJson() {
            return Json.object()
                    .put("delta_used", number(deltaUsed))
                    .put("delta_time", deltaTime)
                    .put("delta_used_text", deltaUsedText)
                    .put("delta_seconds_text", deltaSecondsText);
        }
    }

    /** One line of the history table, finished: what its cells say, and whether it begins a run, whether it is a failed one, and its hover text. */
    record HistoryLine(List<String> cells, boolean start, boolean failed, String title) {

        Json toJson() {
            return Json.object().put("cells", Json.texts(cells)).put("start", start).put("failed", failed).put("title", title);
        }
    }

    /**
     * @param kinds what each column is, in the order of {@code columns}: {@code time}, {@code used}, {@code limit}, {@code deltaUsed},
     *     {@code deltaTime} or {@code currency}
     * @param columnTitles the tooltip of each title, in the order of {@code columns}: what the column is
     * @param zeroLines whether the zero usage lines are shown as the setting has it, which is what is saved, not what a request asked for
     */
    record HistoryBody(
            String file, boolean exists, List<String> columns, List<String> kinds, List<String> columnTitles, boolean wide, String note, boolean noteHighlight, int total,
            boolean zeroLines, List<HistoryLine> lines) {

        Json toJson() {
            Json all = Json.array();
            for (HistoryLine line : lines) {
                all.add(line.toJson());
            }
            return Json.object()
                    .put("file", file)
                    .put("exists", exists)
                    .put("columns", Json.texts(columns))
                    .put("kinds", Json.texts(kinds))
                    .put("columnTitles", Json.texts(columnTitles))
                    .put("wide", wide)
                    .put("note", note)
                    .put("noteHighlight", noteHighlight)
                    .put("total", total)
                    .put("zeroLines", zeroLines)
                    .put("lines", all);
        }
    }

    record ErrorEntryBody(String time, String message) {

        Json toJson() {
            return Json.object().put("time", time).put("message", message);
        }
    }

    record ErrorsBody(List<ErrorEntryBody> entries) {

        Json toJson() {
            Json all = Json.array();
            for (ErrorEntryBody entry : entries) {
                all.add(entry.toJson());
            }
            return Json.object().put("entries", all);
        }
    }

    record ErrorBody(String message, String at) {

        Json toJson() {
            return Json.object().put("message", message).put("at", at);
        }
    }

    record SpendBody(Amount used, Amount limit, String currency, Integer percent, String severity) {

        Json toJson() {
            return Json.object()
                    .put("used", number(used))
                    .put("limit", number(limit))
                    .put("currency", currency)
                    .put("percent", percent)
                    .put("severity", severity);
        }
    }

    /** @param windows always empty: it is there so that a reading has the shape java-aip prints */
    record UsageBody(String source, String fetchedAt, SpendBody spend, List<Object> windows) {

        Json toJson() {
            return Json.object()
                    .put("source", source)
                    .put("fetched_at", fetchedAt)
                    .put("spend", spend == null ? null : spend.toJson())
                    .put("windows", Json.array());
        }
    }

    record StatusBody(
            boolean refreshing, boolean stale, Long nextRefreshInSeconds, ErrorBody error, UsageBody usage, DeltaBody change,
            StatusDisplay.View display, String historyStamp, boolean fake) {

        Json toJson() {
            return Json.object()
                    .put("refreshing", refreshing)
                    .put("stale", stale)
                    .put("nextRefreshInSeconds", nextRefreshInSeconds)
                    .put("error", error == null ? null : error.toJson())
                    .put("usage", usage == null ? null : usage.toJson())
                    .put("change", change == null ? null : change.toJson())
                    .put("display", display.toJson())
                    .put("historyStamp", historyStamp)
                    .put("fake", fake);
        }
    }
}
