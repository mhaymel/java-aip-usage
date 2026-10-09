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

    static final String SHOW_INTERVAL_KEY = "showInterval";

    static final String SHOW_DELTA_USED_KEY = "showDeltaUsed";

    static final String SHOW_DELTA_TIME_KEY = "showDeltaTime";

    static final String TIME_FORMAT_KEY = "timeFormat";

    static final String HISTORY_DELTA_USED_KEY = "historyDeltaUsed";

    static final String HISTORY_DELTA_TIME_KEY = "historyDeltaTime";

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
                flag(root, SHOW_INTERVAL_KEY, defaults.showInterval()),
                flag(root, SHOW_DELTA_USED_KEY, defaults.showDeltaUsed()),
                flag(root, SHOW_DELTA_TIME_KEY, defaults.showDeltaTime()),
                timeFormat(root, defaults.timeFormat()),
                flag(root, HISTORY_DELTA_USED_KEY, defaults.historyDeltaUsed()),
                flag(root, HISTORY_DELTA_TIME_KEY, defaults.historyDeltaTime()));
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
        ObjectNode root = mapper.createObjectNode();
        root.put(USAGE_KEY, settings.usageIntervalSeconds());
        root.put(LOG_RESPONSE_KEY, settings.logResponse());
        root.put(SHOW_INTERVAL_KEY, settings.showInterval());
        root.put(SHOW_DELTA_USED_KEY, settings.showDeltaUsed());
        root.put(SHOW_DELTA_TIME_KEY, settings.showDeltaTime());
        root.put(TIME_FORMAT_KEY, settings.timeFormat().json());
        root.put(HISTORY_DELTA_USED_KEY, settings.historyDeltaUsed());
        root.put(HISTORY_DELTA_TIME_KEY, settings.historyDeltaTime());

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
