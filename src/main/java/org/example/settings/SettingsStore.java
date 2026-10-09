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
                flag(root, HISTORY_FAILED_LINES_KEY, defaults.historyFailedLines()));
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
        write(load(), heights);
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
        write(settings, Files.exists(file) ? loadHeights() : Heights.NONE);
    }

    private void write(Settings settings, Heights heights) throws IOException {
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
