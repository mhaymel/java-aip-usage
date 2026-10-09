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
import org.example.settings.Settings;
import org.example.settings.SettingsException;
import org.example.settings.TimeFormat;
import org.example.usage.HistoryDeltas;
import org.example.usage.HistoryReader;
import org.example.usage.Spend;
import org.example.usage.UsageService;
import org.example.usage.UsageSnapshot;
import org.example.usage.UsageState;
import org.example.usage.UsageWindow;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    /** How much of the log and of the history the windows show: the newest of each. */
    static final int LOG_LINES = 1000;

    static final int LOG_BYTES = 512 * 1024;

    static final int HISTORY_ROWS = 1000;

    private static final String USAGE_KEY = "usageIntervalSeconds";

    private final UsageService service;

    private final IntervalSettings settings;

    private final AppFiles files;

    private final ObjectMapper mapper = new ObjectMapper();

    /** The status is polled every second; the history is read again only when its file has changed. */
    private final LatestChangeCache latestChangeCache = new LatestChangeCache();

    ApiHandler(UsageService service, IntervalSettings settings, AppFiles files) {
        this.service = service;
        this.settings = settings;
        this.files = files;
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
                if (!method.equals("GET")) {
                    throw new ApiException(405, "Use GET.", "GET");
                }
                send(exchange, 200, log());
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
                new Limits(new Range(IntervalRange.USAGE.min(), IntervalRange.USAGE.max())));
    }

    // ---- /api/settings

    private SettingsBody settingsBody() {
        return new SettingsBody(
                toBody(settings.current()),
                toBody(Settings.defaults()),
                Settings.INTERVAL_CHOICES,
                new Limits(new Range(IntervalRange.USAGE.min(), IntervalRange.USAGE.max())));
    }

    private static SettingValues toBody(Settings s) {
        return new SettingValues(s.usageIntervalSeconds(), s.logResponse(), s.showPercentage(), s.showInterval(), s.showDeltaUsed(),
                s.showDeltaTime(), s.timeFormat().json(), s.historyDeltaUsed(), s.historyDeltaTime());
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
                    flag(body, "showInterval"),
                    flag(body, "showDeltaUsed"),
                    flag(body, "showDeltaTime"),
                    timeFormat(body),
                    flag(body, "historyDeltaUsed"),
                    flag(body, "historyDeltaTime"));
            settings.apply(given);
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

    private LogBody log() {
        try {
            LogTail.Tail tail = LogTail.read(files.log(), LOG_LINES, LOG_BYTES);
            return new LogBody(files.log().getFileName().toString(), tail.exists(), tail.truncated(), tail.lines());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the log: " + e.getMessage());
            throw new ApiException(500, "The log could not be read.", null);
        }
    }

    private HistoryBody history() {
        try {
            HistoryReader.Table table = HistoryReader.read(files.history(), HISTORY_ROWS);
            return new HistoryBody(
                    files.history().getFileName().toString(), table.exists(), table.columns(), table.total(), table.rows(),
                    table.deltas().stream().map(ApiHandler::deltaBody).toList(),
                    new HistoryShow(settings.current().historyDeltaUsed(), settings.current().historyDeltaTime()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the usage history: " + e.getMessage());
            throw new ApiException(500, "The usage history could not be read.", null);
        }
    }

    // ---- /api/status

    private static DeltaBody deltaBody(HistoryDeltas.Delta delta) {
        return DeltaBody.of(delta.used() == null ? null : delta.used().doubleValue(), delta.seconds());
    }

    private DeltaBody latestChange() {
        try {
            return latestChangeCache.get(files.history());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the usage history for the change: " + e.getMessage());
            return null;
        }
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
                StatusDisplay.build(state, countdown, change, settings.current(), Instant.now(), ZoneId.systemDefault()));
    }

    private static UsageBody usage(UsageSnapshot snapshot) {
        Spend spend = snapshot.spend();
        return new UsageBody(
                SOURCE,
                snapshot.fetchedAt().toString(),
                spend == null
                        ? null
                        : new SpendBody(spend.used(), spend.limit(), spend.currency(), spend.percent(), spend.severity()),
                snapshot.windows().stream().map(ApiHandler::window).toList());
    }

    private static WindowBody window(UsageWindow window) {
        return new WindowBody(window.key(), window.utilization(), window.resetsAt());
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

    record ConfigBody(int usageIntervalSeconds, int pollIntervalSeconds, Limits limits) {
    }

    record SettingValues(
            int usageIntervalSeconds,
            boolean logResponse,
            boolean showPercentage,
            boolean showInterval,
            boolean showDeltaUsed,
            boolean showDeltaTime,
            String timeFormat,
            boolean historyDeltaUsed,
            boolean historyDeltaTime) {
    }

    record SettingsBody(SettingValues settings, SettingValues defaults, List<Integer> intervalChoices, Limits limits) {
    }

    record RefreshBody(boolean started) {
    }

    record LogBody(String file, boolean exists, boolean truncated, List<String> lines) {
    }

    /** What changed since the row before: the amount used, and the seconds; either is null if it cannot be worked out. */
    record DeltaBody(
            @JsonProperty("delta_used") Double deltaUsed,
            @JsonProperty("delta_time") Long deltaTime,
            @JsonProperty("delta_used_text") String deltaUsedText,
            @JsonProperty("delta_time_text") String deltaTimeText) {

        static DeltaBody of(Double used, Long seconds) {
            return new DeltaBody(
                    used, seconds,
                    used == null ? null : Formatting.signedAmount(java.math.BigDecimal.valueOf(used)),
                    seconds == null ? null : Formatting.gap(seconds));
        }
    }

    /** Which optional columns of the history table are switched on. */
    record HistoryShow(boolean deltaUsed, boolean deltaTime) {
    }

    record HistoryBody(
            String file, boolean exists, List<String> columns, int total, List<List<String>> rows, List<DeltaBody> deltas,
            HistoryShow show) {
    }

    record ErrorBody(String message, String at) {
    }

    record SpendBody(Double used, Double limit, String currency, Integer percent, String severity) {
    }

    record WindowBody(String window, double utilization, @JsonProperty("resets_at") String resetsAt) {
    }

    record UsageBody(
            String source,
            @JsonProperty("fetched_at") String fetchedAt,
            SpendBody spend,
            List<WindowBody> windows) {
    }

    record StatusBody(
            boolean refreshing, boolean stale, Long nextRefreshInSeconds, ErrorBody error, UsageBody usage, DeltaBody change,
            StatusDisplay.View display) {
    }
}
