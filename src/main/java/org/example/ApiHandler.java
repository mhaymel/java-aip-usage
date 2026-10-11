package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.example.settings.IntervalRange;
import org.example.settings.IntervalSettings;
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
import java.util.Map;
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

    private static final System.Logger LOG = System.getLogger(ApiHandler.class.getName());

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

    private final ObjectMapper mapper = new ObjectMapper();

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
                send(exchange, e.status, Map.of("error", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "API request failed: " + exchange.getRequestURI().getPath(), e);
                send(exchange, 500, Map.of("error", "Internal error; see the log."));
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
                send(exchange, 200, config());
            }
            case "/api/debug" -> {
                // The debug settings are there in debug mode only; without it there is no such thing to ask for.
                if (!sources.debug()) {
                    throw new ApiException(404, "No such endpoint.", null);
                }
                if (method.equals("GET")) {
                    send(exchange, 200, debugBody());
                } else if (method.equals("POST")) {
                    updateDebug(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/settings" -> {
                if (method.equals("GET")) {
                    send(exchange, 200, settingsBody());
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
                send(exchange, 200, status(service.state()));
            }
            case "/api/log" -> {
                if (method.equals("GET")) {
                    send(exchange, 200, log(levelAsked(exchange.getRequestURI().getQuery())));
                } else if (method.equals("POST")) {
                    updateLog(exchange);
                } else {
                    throw new ApiException(405, "Use GET or POST.", "GET, POST");
                }
            }
            case "/api/text-size" -> {
                if (!method.equals("POST")) {
                    throw new ApiException(405, "Use POST.", "POST");
                }
                updateTextSize(exchange);
            }
            case "/api/errors" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, errors());
            }
            case "/api/history" -> {
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, history());
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
        return new DebugBody(
                new DebugValues(now.fakeBackend(), now.fakeScenario().optionName(), now.fakeToken()),
                java.util.Arrays.stream(org.example.fake.Scenario.values()).map(org.example.fake.Scenario::optionName).toList());
    }

    /** All three must be given. They are saved and take effect at once: the source of usage is opened anew and a refresh follows. */
    private void updateDebug(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        JsonNode body = readJson(exchange);
        try {
            boolean fakeBackend = flag(body, "fakeBackend");
            JsonNode name = body.get("fakeScenario");
            if (name == null || name.isNull()) {
                throw new InvalidSettingException("The setting fakeScenario is missing.");
            }
            org.example.fake.Scenario scenario =
                    (name.isTextual() ? org.example.fake.Scenario.ofOptionName(name.textValue()) : java.util.Optional.<org.example.fake.Scenario>empty())
                            .orElseThrow(() -> new InvalidSettingException(
                                    "The setting fakeScenario must be one of " + org.example.fake.Scenario.names() + "."));
            sources.applyDebug(fakeBackend, scenario, flag(body, "fakeToken"));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, e.getMessage(), null);
        } catch (SettingsException e) {
            throw new ApiException(500, e.getMessage(), null);
        }
        send(exchange, 200, debugBody());
    }

    // ---- /api/settings

    /**
     * The settings as the window shows them: the interval is the wait the service is using, which after HTTP 429s is longer
     * than the configured one. The settings file keeps the configured one until a value is applied.
     */
    private Settings shown() {
        Settings current = settings.current();
        return current.withUsageIntervalSeconds(Math.max(current.usageIntervalSeconds(), service.effectiveIntervalSeconds()));
    }

    private SettingsBody settingsBody() {
        return new SettingsBody(
                toBody(shown()),
                toBody(Settings.defaults()),
                new Limits(new Range(IntervalRange.USAGE.min(), IntervalRange.USAGE.max())));
    }

    private static SettingValues toBody(Settings s) {
        return new SettingValues(s.usageIntervalSeconds(), s.logResponse(), s.showPercentage(), s.showCurrency(), s.showHistoryIcon(), s.showLogIcon(),
                s.showErrorIcon(), s.showInterval(), s.showDeltaUsed(),
                s.showDeltaTime(), s.timeFormat().json(), s.historyDeltaUsed(), s.historyDeltaTime(), s.historyDate(),
                s.historyZeroLines(), s.historyFailedLines(), s.showTime(), s.showUsed(), s.showLimit(), s.showRefresh(), s.showCountdown(),
                s.showDuration(), s.stayOnTop(), s.showTitleBar());
    }

    /** All the settings must be given, so that what is applied is exactly what the view showed. */
    private void updateSettings(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        JsonNode body = readJson(exchange);
        try {
            Long usage = seconds(body, USAGE_KEY, IntervalRange.USAGE);
            if (usage == null) {
                throw new InvalidSettingException("The setting " + USAGE_KEY + " is missing.");
            }
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
                    false, org.example.fake.Scenario.NORMAL, false, LogLevel.INFO, false, Settings.DEFAULT_LIST_FONT_SIZE);
            // The debug settings are not among these: they are kept as they are, and changed by /api/debug alone. Nor are
            // the two choices of the log panel, which POST /api/log changes, and the size of the lists' text, which POST /api/text-size changes.
            Settings kept = settings.current();
            settings.apply(given.withDebug(kept.fakeBackend(), kept.fakeScenario(), kept.fakeToken()).withLog(kept.logLevel(), kept.logDate())
                    .withListFontSize(kept.listFontSize()));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, e.getMessage(), null);
        } catch (SettingsException e) {
            throw new ApiException(500, e.getMessage(), null);
        }
        send(exchange, 200, settingsBody());
    }

    private static boolean flag(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            throw new InvalidSettingException("The setting " + key + " is missing.");
        }
        if (!value.isBoolean()) {
            throw new InvalidSettingException("The setting " + key + " must be true or false.");
        }
        return value.booleanValue();
    }

    private static TimeFormat timeFormat(JsonNode body) {
        JsonNode value = body.get("timeFormat");
        if (value == null || value.isNull()) {
            throw new InvalidSettingException("The setting timeFormat is missing.");
        }
        return (value.isTextual() ? TimeFormat.fromJson(value.textValue()) : java.util.Optional.<TimeFormat>empty())
                .orElseThrow(() -> new InvalidSettingException("The setting timeFormat must be \"hh:mm\" or \"hh:mm:ss\"."));
    }

    /** The value of {@code key}, or {@code null} if absent. Only whole numbers qualify. */
    private static Long seconds(JsonNode body, String key, IntervalRange range) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
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
                    now.logLevel().json(), now.logDate());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the log: " + e.getMessage());
            throw new ApiException(500, "The log could not be read.", null);
        }
    }

    /**
     * The size of the text of the three lists, which the buttons at the top of a list change. It is saved at once, and
     * the other settings are left as they are.
     */
    private void updateTextSize(HttpExchange exchange) throws IOException {
        requireJson(exchange);
        JsonNode size = readJson(exchange).get("size");
        String range = "The size must be a whole number of pixels from " + Settings.MIN_LIST_FONT_SIZE + " to " + Settings.MAX_LIST_FONT_SIZE + ".";
        if (size == null || !size.isIntegralNumber() || !size.canConvertToInt() || !Settings.isListFontSize(size.intValue())) {
            throw new ApiException(400, range, null);
        }
        try {
            settings.apply(settings.current().withListFontSize(size.intValue()));
        } catch (SettingsException e) {
            throw new ApiException(500, "The text size could not be saved: " + e.getCause().getMessage(), null);
        }
        send(exchange, 200, new TextSizeBody(settings.current().listFontSize()));
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
        JsonNode body = readJson(exchange);
        LogLevel level;
        try {
            JsonNode name = body.get("level");
            if (name == null || name.isNull()) {
                throw new InvalidSettingException("The setting level is missing.");
            }
            level = (name.isTextual() ? LogLevel.fromJson(name.textValue()) : java.util.Optional.<LogLevel>empty())
                    .orElseThrow(() -> new InvalidSettingException("The setting level must be one of " + LogLevel.names() + "."));
            settings.apply(settings.current().withLog(level, flag(body, "date")));
        } catch (InvalidSettingException e) {
            throw new ApiException(400, e.getMessage(), null);
        } catch (SettingsException e) {
            throw new ApiException(500, "The log settings could not be saved: " + e.getCause().getMessage(), null);
        }
        Settings now = settings.current();
        send(exchange, 200, new LogSettingsBody(now.logLevel().json(), now.logDate()));
    }

    /**
     * The history, with its lines finished: the page only draws them. The settings decide which lines are left out (the zero usage and
     * the failed ones), whether the time has the date and which change columns there are; the changes are worked out on the lines
     * that are shown; and the note says whenever not everything is shown.
     */
    private HistoryBody history() {
        try {
            Settings now = settings.current();
            HistoryReader.Table table = HistoryReader.read(
                    sources.history(), HISTORY_ROWS, new HistoryReader.Filter(now.historyZeroLines(), now.historyFailedLines()));
            // The currency is the right-most column; the change columns, when they are on, sit between the budget and it.
            List<String> columns = new java.util.ArrayList<>(List.of(
                    now.historyDate() ? "date time" : "time", "used", "limit"));
            if (now.historyDeltaUsed()) {
                columns.add("\u0394 used");
            }
            if (now.historyDeltaTime()) {
                columns.add("\u0394 time");
            }
            columns.add("Cur.");
            List<HistoryLine> lines = new java.util.ArrayList<>();
            for (int i = 0; i < table.rows().size(); i++) {
                List<String> row = table.rows().get(i);
                boolean failed = row.get(4).contains("failed");
                boolean start = row.get(4).startsWith("start");
                DeltaBody delta = deltaBody(table.deltas().get(i));
                List<String> cells = new java.util.ArrayList<>(List.of(
                        historyTime(row.get(0), now.historyDate()),
                        failed ? "failed" : row.get(1),
                        row.get(2)));
                if (now.historyDeltaUsed()) {
                    cells.add(delta.deltaUsedText() == null ? "" : delta.deltaUsedText());
                }
                if (now.historyDeltaTime()) {
                    cells.add(delta.deltaSecondsText() == null ? "" : delta.deltaSecondsText());
                }
                // US dollars as $, any other currency by its code.
                cells.add(Formatting.historyCurrency(row.get(3)));
                lines.add(new HistoryLine(cells, start, failed, start ? START_TOOLTIP : ""));
            }
            return new HistoryBody(
                    sources.history().getFileName().toString(), table.exists(), columns, now.historyDate(),
                    historyNote(table), historyNoteHighlighted(table), table.total(), lines);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the usage history: " + e.getMessage());
            throw new ApiException(500, "The usage history could not be read.", null);
        }
    }

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
        return DeltaBody.of(delta.used() == null ? null : delta.used().doubleValue(), delta.seconds());
    }

    private DeltaBody latestChange() {
        try {
            return latestChangeCache.get(sources.history());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the usage history for the change: " + e.getMessage());
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
            LOG.log(System.Logger.Level.INFO, "Manual refresh requested");
        }
        // 202: the fetch has been started, not finished. 200: one was already under way.
        send(exchange, started ? 202 : 200, new RefreshBody(started));
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

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "The request body is too large.", null);
        }
        JsonNode body;
        try {
            body = mapper.readTree(bytes);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "The request body is not valid JSON.", null);
        }
        if (body == null || !body.isObject()) {
            throw new ApiException(400, "The request body must be a JSON object.", null);
        }
        return body;
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
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

    // ---- response shapes: the contract in docs/api.md

    record Range(int min, int max) {
    }

    record Limits(Range usageIntervalSeconds) {
    }

    /** @param listFontSize the size of the text of the lists, in pixels, as saved: the page has it from here when it starts */
    record ConfigBody(int usageIntervalSeconds, int pollIntervalSeconds, Limits limits, boolean debug, int listFontSize) {
    }

    record TextSizeBody(int size) {
    }

    record DebugValues(boolean fakeBackend, String fakeScenario, boolean fakeToken) {
    }

    record DebugBody(DebugValues settings, List<String> scenarios) {
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
            boolean showTitleBar) {
    }

    record SettingsBody(SettingValues settings, SettingValues defaults, Limits limits) {
    }

    record RefreshBody(boolean started) {
    }

    /**
     * @param hidden whether the file has lines and none of the level asked for
     * @param level the level saved for the panel, whatever level was asked for
     * @param date whether the panel shows the date of each line, as saved
     */
    record LogBody(String file, boolean exists, boolean truncated, List<String> lines, boolean hidden, String level, boolean date) {
    }

    record LogSettingsBody(String level, boolean date) {
    }

    /** What changed since the row before: the amount used, and the seconds; either is null if it cannot be worked out. */
    record DeltaBody(
            @JsonProperty("delta_used") Double deltaUsed,
            @JsonProperty("delta_time") Long deltaTime,
            @JsonProperty("delta_used_text") String deltaUsedText,
            @JsonProperty("delta_seconds_text") String deltaSecondsText) {

        static DeltaBody of(Double used, Long seconds) {
            return new DeltaBody(
                    used, seconds,
                    // A change of nothing says nothing: no text for it, so no cell and no item show it.
                    used == null || Math.abs(used) < 0.005 ? null : Formatting.signedAmount(java.math.BigDecimal.valueOf(used)),
                    // The time is in whole seconds, in the history and in the row, never in minutes.
                    seconds == null ? null : Formatting.seconds(seconds));
        }
    }

    /** One line of the history table, finished: what its cells say, and whether it begins a run, whether it is a failed one, and its hover text. */
    record HistoryLine(List<String> cells, boolean start, boolean failed, String title) {
    }

    record HistoryBody(
            String file, boolean exists, List<String> columns, boolean wide, String note, boolean noteHighlight, int total,
            List<HistoryLine> lines) {
    }

    record ErrorEntryBody(String time, String message) {
    }

    record ErrorsBody(List<ErrorEntryBody> entries) {
    }

    record ErrorBody(String message, String at) {
    }

    record SpendBody(Double used, Double limit, String currency, Integer percent, String severity) {
    }

    record UsageBody(
            String source,
            @JsonProperty("fetched_at") String fetchedAt,
            SpendBody spend,
            List<Object> windows) {
    }

    record StatusBody(
            boolean refreshing, boolean stale, Long nextRefreshInSeconds, ErrorBody error, UsageBody usage, DeltaBody change,
            StatusDisplay.View display, String historyStamp, boolean fake) {
    }
}
