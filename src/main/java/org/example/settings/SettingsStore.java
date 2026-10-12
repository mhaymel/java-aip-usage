package org.example.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The settings file, in the same directory as the usage history: every setting, as JSON. In
 * particular it never holds a credential. The update interval is not kept here: it is set on
 * the command line, for one run. A file written by an earlier version may still carry it, and
 * it is ignored, as is any other key that is not a setting.
 *
 * <p>Reading is forgiving, because a hand-edited or damaged file must not stop the
 * application from starting: a key the file lacks, or whose value is unusable, has its default,
 * and a file that cannot be read at all gives the defaults and is left alone until a value is
 * saved. A missing file is created with the defaults.
 * Writing is atomic, so a crash cannot leave half a file behind.
 */
public final class SettingsStore {

    static final String USAGE_KEY = "usageIntervalSeconds";

    static final String LOG_RESPONSE_KEY = "logResponse";

    static final String SHOW_PERCENTAGE_KEY = "showPercentage";

    static final String SHOW_CURRENCY_KEY = "showCurrency";

    static final String SHOW_HISTORY_ICON_KEY = "showHistoryIcon";

    static final String SHOW_LOG_ICON_KEY = "showLogIcon";

    static final String SHOW_ERROR_ICON_KEY = "showErrorIcon";

    static final String SHOW_INTERVAL_KEY = "showInterval";

    static final String SHOW_DELTA_USED_KEY = "showDeltaUsed";

    static final String SHOW_DELTA_TIME_KEY = "showDeltaTime";

    static final String TIME_FORMAT_KEY = "timeFormat";

    static final String SHOW_TIME_KEY = "showTime";

    static final String SHOW_USED_KEY = "showUsed";

    static final String SHOW_LIMIT_KEY = "showLimit";

    static final String SHOW_REFRESH_KEY = "showRefresh";

    static final String SHOW_COUNTDOWN_KEY = "showCountdown";

    static final String SHOW_DURATION_KEY = "showDuration";

    static final String STAY_ON_TOP_KEY = "stayOnTop";

    static final String SHOW_TITLE_BAR_KEY = "showTitleBar";

    static final String FAKE_BACKEND_KEY = "fakeBackend";

    static final String FAKE_SCENARIO_KEY = "fakeScenario";

    static final String FAKE_TOKEN_KEY = "fakeToken";

    static final String LOG_LEVEL_KEY = "logLevel";

    static final String LOG_DATE_KEY = "logDate";

    static final String LIST_FONT_SIZE_KEY = "listFontSize";

    static final String FAKE_ACCOUNT_KEY = "fakeAccount";

    static final String FAKE_INCREASE_KEY = "fakeIncreaseCents";

    static final String FAKE_BUDGET_KEY = "fakeBudget";

    static final String FAKE_START_KEY = "fakeStartAmount";

    static final String FAKE_REFUSE_KEY = "fakeRefuseSeconds";

    static final String FAKE_DELAY_KEY = "fakeDelayMillis";

    /** The scenario there was for a seat-based account, which the account of the options took the place of. */
    private static final String OLD_SEAT_SCENARIO = "seat-based";

    static final String AUTO_INTERVAL_KEY = "autoInterval";

    static final String AUTO_SECONDS_KEY = "autoIntervalSeconds";

    static final String AUTO_LOWER_KEY = "autoLowerSeconds";

    static final String AUTO_UPPER_KEY = "autoUpperSeconds";

    static final String HISTORY_DELTA_USED_KEY = "historyDeltaUsed";

    static final String HISTORY_DELTA_TIME_KEY = "historyDeltaTime";

    static final String HISTORY_DATE_KEY = "historyDate";

    static final String HISTORY_ZERO_LINES_KEY = "historyZeroLines";

    static final String HISTORY_FAILED_LINES_KEY = "historyFailedLines";

    static final String HISTORY_HEIGHT_KEY = "historyHeight";

    static final String LOG_HEIGHT_KEY = "logHeight";

    /** The tallest height that is believed, in pixels: more than any screen. */
    static final int MAX_HEIGHT = 10_000;

    private static final System.Logger LOG = System.getLogger(SettingsStore.class.getName());

    private final Path file;

    private final ObjectMapper mapper = new ObjectMapper();

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Reads the file, creating it with the defaults if there is none. Never fails: anything unusable
     * is logged and replaced by its default.
     */
    public Settings load() {
        Settings defaults = Settings.defaults();
        if (!Files.exists(file)) {
            try {
                save(defaults);
                LOG.log(System.Logger.Level.INFO, "There was no settings file; created " + file + " with the defaults");
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not create the settings file " + file + ": " + e.getMessage());
            }
            return defaults;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable settings file " + file + ": " + e.getMessage());
            return defaults;
        }
        if (root == null || !root.isObject()) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring settings file " + file + ": not a JSON object");
            return defaults;
        }
        return new Settings(
                interval(root, defaults.usageIntervalSeconds()),
                flag(root, LOG_RESPONSE_KEY, defaults.logResponse()),
                flag(root, SHOW_PERCENTAGE_KEY, defaults.showPercentage()),
                flag(root, SHOW_CURRENCY_KEY, defaults.showCurrency()),
                flag(root, SHOW_HISTORY_ICON_KEY, defaults.showHistoryIcon()),
                flag(root, SHOW_LOG_ICON_KEY, defaults.showLogIcon()),
                flag(root, SHOW_ERROR_ICON_KEY, defaults.showErrorIcon()),
                flag(root, SHOW_INTERVAL_KEY, defaults.showInterval()),
                flag(root, SHOW_DELTA_USED_KEY, defaults.showDeltaUsed()),
                flag(root, SHOW_DELTA_TIME_KEY, defaults.showDeltaTime()),
                timeFormat(root, defaults.timeFormat()),
                flag(root, HISTORY_DELTA_USED_KEY, defaults.historyDeltaUsed()),
                flag(root, HISTORY_DELTA_TIME_KEY, defaults.historyDeltaTime()),
                flag(root, HISTORY_DATE_KEY, defaults.historyDate()),
                flag(root, HISTORY_ZERO_LINES_KEY, defaults.historyZeroLines()),
                flag(root, HISTORY_FAILED_LINES_KEY, defaults.historyFailedLines()),
                flag(root, SHOW_TIME_KEY, defaults.showTime()),
                flag(root, SHOW_USED_KEY, defaults.showUsed()),
                flag(root, SHOW_LIMIT_KEY, defaults.showLimit()),
                flag(root, SHOW_REFRESH_KEY, defaults.showRefresh()),
                flag(root, SHOW_COUNTDOWN_KEY, defaults.showCountdown()),
                flag(root, SHOW_DURATION_KEY, defaults.showDuration()),
                flag(root, STAY_ON_TOP_KEY, defaults.stayOnTop()),
                flag(root, SHOW_TITLE_BAR_KEY, defaults.showTitleBar()),
                flag(root, FAKE_BACKEND_KEY, defaults.fakeBackend()),
                scenario(root, defaults.fakeScenario()),
                flag(root, FAKE_TOKEN_KEY, defaults.fakeToken()),
                logLevel(root, defaults.logLevel()),
                flag(root, LOG_DATE_KEY, defaults.logDate()),
                listFontSize(root, defaults.listFontSize()),
                flag(root, AUTO_INTERVAL_KEY, defaults.autoInterval()));
    }

    /**
     * The heights the window had the last time the history and the log were open, in pixels of its content; {@code 0} for
     * none. They are in the settings file but are not settings of the settings view.
     */
    public record Heights(int history, int log) {

        public static final Heights NONE = new Heights(0, 0);

        public Heights withHeight(String panel, int pixels) {
            return switch (panel) {
                case "history" -> new Heights(pixels, log);
                case "log" -> new Heights(history, pixels);
                default -> throw new IllegalArgumentException("No remembered height for " + panel);
            };
        }

        public int of(String panel) {
            return switch (panel) {
                case "history" -> history;
                case "log" -> log;
                default -> 0;
            };
        }
    }

    /** The remembered heights; none if the file is missing or unusable. Never fails. */
    public Heights loadHeights() {
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                return Heights.NONE;
            }
            return new Heights(height(root, HISTORY_HEIGHT_KEY), height(root, LOG_HEIGHT_KEY));
        } catch (IOException e) {
            return Heights.NONE;
        }
    }

    private int height(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0 || node.intValue() > MAX_HEIGHT) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + key + " in " + file + ": it must be a whole number of pixels from 0 to " + MAX_HEIGHT);
            return 0;
        }
        return node.intValue();
    }

    /**
     * Stores the heights, keeping the settings the file has.
     *
     * @throws IOException if the file cannot be written; it is then unchanged
     */
    public void saveHeights(Heights heights) throws IOException {
        if (Files.exists(file)) {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                // A damaged file is left as it is until a setting is applied; a height is not worth replacing it for.
                throw new IOException("the settings file is not a JSON object");
            }
        }
        write(load(), heights, loadSearch(), loadFake());
    }

    /** The scenario of the fake backend, by its name; a name that is none of them is logged and passed over. */
    private org.example.fake.Scenario scenario(JsonNode root, org.example.fake.Scenario fallback) {
        JsonNode node = root.get(FAKE_SCENARIO_KEY);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isTextual() && OLD_SEAT_SCENARIO.equals(node.textValue())) {
            // What that scenario answered is the ordinary answer of a seat-based account now (see loadFake).
            return org.example.fake.Scenario.NORMAL;
        }
        java.util.Optional<org.example.fake.Scenario> named =
                node.isTextual() ? org.example.fake.Scenario.ofOptionName(node.textValue()) : java.util.Optional.empty();
        if (named.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring " + FAKE_SCENARIO_KEY + " in " + file + ": it must be one of " + org.example.fake.Scenario.names());
            return fallback;
        }
        return named.get();
    }

    /** The level the log panel shows, by its name; a name that is none of them is logged and passed over. */
    private LogLevel logLevel(JsonNode root, LogLevel fallback) {
        JsonNode node = root.get(LOG_LEVEL_KEY);
        if (node == null || node.isNull()) {
            return fallback;
        }
        java.util.Optional<LogLevel> named = node.isTextual() ? LogLevel.fromJson(node.textValue()) : java.util.Optional.empty();
        if (named.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + LOG_LEVEL_KEY + " in " + file + ": it must be one of " + LogLevel.names());
            return fallback;
        }
        return named.get();
    }

    /** The size of the text of the lists; a value that is not a whole number of pixels in its range is logged and passed over. */
    private int listFontSize(JsonNode root, int fallback) {
        JsonNode node = root.get(LIST_FONT_SIZE_KEY);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || !Settings.isListFontSize(node.intValue())) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + LIST_FONT_SIZE_KEY + " in " + file + ": it must be a whole number of pixels from "
                    + Settings.MIN_LIST_FONT_SIZE + " to " + Settings.MAX_LIST_FONT_SIZE);
            return fallback;
        }
        return node.intValue();
    }

    private int interval(JsonNode root, int fallback) {
        JsonNode node = root.get(USAGE_KEY);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || !IntervalRange.USAGE.contains(node.intValue())) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + USAGE_KEY + " in " + file + ": " + IntervalRange.USAGE.describeLimit());
            return fallback;
        }
        return node.intValue();
    }

    private boolean flag(JsonNode root, String key, boolean fallback) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + key + " in " + file + ": it must be true or false");
            return fallback;
        }
        return node.booleanValue();
    }

    private TimeFormat timeFormat(JsonNode root, TimeFormat fallback) {
        JsonNode node = root.get(TIME_FORMAT_KEY);
        if (node == null || node.isNull()) {
            return fallback;
        }
        return (node.isTextual() ? TimeFormat.fromJson(node.textValue()) : java.util.Optional.<TimeFormat>empty())
                .orElseGet(() -> {
                    LOG.log(System.Logger.Level.WARNING,
                            "Ignoring " + TIME_FORMAT_KEY + " in " + file + ": it must be \"hh:mm\" or \"hh:mm:ss\"");
                    return fallback;
                });
    }

    /**
     * Replaces the file's contents.
     *
     * @throws IOException if the file cannot be written; it is then unchanged
     */
    public void save(Settings settings) throws IOException {
        boolean there = Files.exists(file);
        write(settings, there ? loadHeights() : Heights.NONE, there ? loadSearch() : Search.BEGINNING,
                there ? loadFake() : org.example.fake.FakeOptions.DEFAULTS);
    }

    /**
     * How the fake backend's ordinary answer is shaped, as saved; a value the file lacks, or one that cannot be, has its
     * default, and options that do not go together are the defaults. They are debug settings: in the settings file, and
     * no settings of the settings view. Never fails.
     */
    public org.example.fake.FakeOptions loadFake() {
        org.example.fake.FakeOptions defaults = org.example.fake.FakeOptions.DEFAULTS;
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                return defaults;
            }
            JsonNode account = root.get(FAKE_ACCOUNT_KEY);
            JsonNode scenario = root.get(FAKE_SCENARIO_KEY);
            org.example.fake.FakeOptions.Account kind = account != null && account.isTextual()
                    ? org.example.fake.FakeOptions.Account.fromJson(account.textValue()).orElse(defaults.account())
                    // A file of before the account, whose scenario was the one for a seat-based account.
                    : scenario != null && scenario.isTextual() && OLD_SEAT_SCENARIO.equals(scenario.textValue())
                            ? org.example.fake.FakeOptions.Account.SEAT : defaults.account();
            org.example.fake.FakeOptions read = new org.example.fake.FakeOptions(
                    kind,
                    whole(root, FAKE_INCREASE_KEY, defaults.increaseCents()),
                    cents(root, FAKE_BUDGET_KEY, defaults.budgetCents()),
                    cents(root, FAKE_START_KEY, defaults.startCents()),
                    whole(root, FAKE_REFUSE_KEY, defaults.refuseSeconds()),
                    whole(root, FAKE_DELAY_KEY, defaults.delayMillis()));
            if (read.fault().isPresent()) {
                LOG.log(System.Logger.Level.WARNING, "Ignoring the fake backend's options in " + file + ": " + read.fault().get());
                return new org.example.fake.FakeOptions(kind, defaults.increaseCents(), defaults.budgetCents(), defaults.startCents(),
                        defaults.refuseSeconds(), defaults.delayMillis());
            }
            return read;
        } catch (IOException e) {
            return defaults;
        }
    }

    private static int whole(JsonNode root, String key, int fallback) {
        JsonNode node = root.get(key);
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : fallback;
    }

    /** An amount of the file, in dollars with two decimals at most, as cents; anything else is the fallback. */
    private static long cents(JsonNode root, String key, long fallback) {
        JsonNode node = root.get(key);
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        try {
            return node.decimalValue().movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            return fallback;
        }
    }

    /**
     * Stores the fake backend's options, keeping the settings, the heights and the search the file has.
     *
     * @throws IOException if the file cannot be written; it is then unchanged
     */
    public void saveFake(org.example.fake.FakeOptions options) throws IOException {
        if (Files.exists(file)) {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                throw new IOException("the settings file is not a JSON object");
            }
        }
        write(load(), loadHeights(), loadSearch(), options);
    }

    /**
     * Where the search for the automatic interval is: the interval and its two bounds, in seconds. They are in the
     * settings file but are no settings of the settings view: the program changes them by itself.
     */
    public record Search(int interval, int lower, int upper) {

        /** Where a search begins: 60 seconds, between 10 and 300. */
        public static final Search BEGINNING = new Search(60, 10, 300);
    }

    /**
     * The search as saved; its beginning if the file is missing, unusable, or has not all three as whole numbers. Whether
     * the three can be a state of the search is the search's to say. Never fails.
     */
    public Search loadSearch() {
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                return Search.BEGINNING;
            }
            JsonNode interval = root.get(AUTO_SECONDS_KEY);
            JsonNode lower = root.get(AUTO_LOWER_KEY);
            JsonNode upper = root.get(AUTO_UPPER_KEY);
            for (JsonNode node : new JsonNode[] {interval, lower, upper}) {
                if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
                    return Search.BEGINNING;
                }
            }
            return new Search(interval.intValue(), lower.intValue(), upper.intValue());
        } catch (IOException e) {
            return Search.BEGINNING;
        }
    }

    /**
     * Stores the search, keeping the settings and the heights the file has.
     *
     * @throws IOException if the file cannot be written; it is then unchanged
     */
    public void saveSearch(Search search) throws IOException {
        if (Files.exists(file)) {
            JsonNode root = mapper.readTree(Files.readString(file));
            if (root == null || !root.isObject()) {
                throw new IOException("the settings file is not a JSON object");
            }
        }
        write(load(), loadHeights(), search, loadFake());
    }

    private void write(Settings settings, Heights heights, Search search, org.example.fake.FakeOptions fake) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put(USAGE_KEY, settings.usageIntervalSeconds());
        root.put(LOG_RESPONSE_KEY, settings.logResponse());
        root.put(SHOW_PERCENTAGE_KEY, settings.showPercentage());
        root.put(SHOW_CURRENCY_KEY, settings.showCurrency());
        root.put(SHOW_HISTORY_ICON_KEY, settings.showHistoryIcon());
        root.put(SHOW_LOG_ICON_KEY, settings.showLogIcon());
        root.put(SHOW_ERROR_ICON_KEY, settings.showErrorIcon());
        root.put(SHOW_INTERVAL_KEY, settings.showInterval());
        root.put(SHOW_DELTA_USED_KEY, settings.showDeltaUsed());
        root.put(SHOW_DELTA_TIME_KEY, settings.showDeltaTime());
        root.put(TIME_FORMAT_KEY, settings.timeFormat().json());
        root.put(HISTORY_DELTA_USED_KEY, settings.historyDeltaUsed());
        root.put(HISTORY_DELTA_TIME_KEY, settings.historyDeltaTime());
        root.put(HISTORY_DATE_KEY, settings.historyDate());
        root.put(HISTORY_ZERO_LINES_KEY, settings.historyZeroLines());
        root.put(HISTORY_FAILED_LINES_KEY, settings.historyFailedLines());
        root.put(SHOW_TIME_KEY, settings.showTime());
        root.put(SHOW_USED_KEY, settings.showUsed());
        root.put(SHOW_LIMIT_KEY, settings.showLimit());
        root.put(SHOW_REFRESH_KEY, settings.showRefresh());
        root.put(SHOW_COUNTDOWN_KEY, settings.showCountdown());
        root.put(SHOW_DURATION_KEY, settings.showDuration());
        root.put(STAY_ON_TOP_KEY, settings.stayOnTop());
        root.put(SHOW_TITLE_BAR_KEY, settings.showTitleBar());
        root.put(FAKE_BACKEND_KEY, settings.fakeBackend());
        root.put(FAKE_SCENARIO_KEY, settings.fakeScenario().optionName());
        root.put(FAKE_TOKEN_KEY, settings.fakeToken());
        root.put(FAKE_ACCOUNT_KEY, fake.account().json());
        root.put(FAKE_INCREASE_KEY, fake.increaseCents());
        root.put(FAKE_BUDGET_KEY, java.math.BigDecimal.valueOf(fake.budgetCents(), 2));
        root.put(FAKE_START_KEY, java.math.BigDecimal.valueOf(fake.startCents(), 2));
        root.put(FAKE_REFUSE_KEY, fake.refuseSeconds());
        root.put(FAKE_DELAY_KEY, fake.delayMillis());
        root.put(LOG_LEVEL_KEY, settings.logLevel().json());
        root.put(LOG_DATE_KEY, settings.logDate());
        root.put(LIST_FONT_SIZE_KEY, settings.listFontSize());
        root.put(AUTO_INTERVAL_KEY, settings.autoInterval());
        root.put(AUTO_SECONDS_KEY, search.interval());
        root.put(AUTO_LOWER_KEY, search.lower());
        root.put(AUTO_UPPER_KEY, search.upper());
        root.put(HISTORY_HEIGHT_KEY, heights.history());
        root.put(LOG_HEIGHT_KEY, heights.log());

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
