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
 * The settings file: the two intervals the user has chosen in the UI, and
 * nothing else. In particular it never holds a credential.
 *
 * <p>Reading is forgiving, because a hand-edited or damaged file must not stop
 * the application from starting: anything unusable is logged and ignored.
 * Writing is atomic, so a crash cannot leave half a file behind.
 */
public final class SettingsStore {

    static final String USAGE_KEY = "usageIntervalSeconds";

    static final String POLL_KEY = "pollIntervalSeconds";

    private static final System.Logger LOG = System.getLogger(SettingsStore.class.getName());

    /** What the file holds; {@code null} for a setting it does not hold. */
    public record Saved(Integer usageIntervalSeconds, Integer pollIntervalSeconds) {

        public static final Saved NONE = new Saved(null, null);
    }

    private final Path file;

    private final ObjectMapper mapper = new ObjectMapper();

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the file. Never fails: a missing or unusable file, or value, counts as absent. */
    public Saved load() {
        if (!Files.exists(file)) {
            return Saved.NONE;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring unreadable settings file " + file + ": " + e.getMessage());
            return Saved.NONE;
        }
        if (root == null || !root.isObject()) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring settings file " + file + ": not a JSON object");
            return Saved.NONE;
        }
        return new Saved(
                value(root, USAGE_KEY, IntervalRange.USAGE),
                value(root, POLL_KEY, IntervalRange.POLL));
    }

    private Integer value(JsonNode root, String key, IntervalRange range) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || !range.contains(node.intValue())) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring " + key + " in " + file + ": " + range.describeLimit());
            return null;
        }
        return node.intValue();
    }

    /**
     * Replaces the file's contents.
     *
     * @throws IOException if the file cannot be written; it is then unchanged
     */
    public void save(Saved settings) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        if (settings.usageIntervalSeconds() != null) {
            root.put(USAGE_KEY, settings.usageIntervalSeconds());
        }
        if (settings.pollIntervalSeconds() != null) {
            root.put(POLL_KEY, settings.pollIntervalSeconds());
        }

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
