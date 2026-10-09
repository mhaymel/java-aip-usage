package org.example.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The settings in force, the two intervals among them, and how they change.
 *
 * <p>The usage interval, how often usage is fetched from Anthropic, comes at startup
 * from the command line if given, else from the settings file, else the default. A
 * value committed in the UI replaces the command-line one for the rest of the run,
 * takes effect at once, and is saved. A command-line value is never saved on its own:
 * it is an override for one run, so the file keeps what the user last chose in the UI.
 *
 * <p>The update interval, how often the window asks for the latest state, is not a
 * setting. It is the command-line value if given, else the default, and nothing
 * changes or saves it.
 */
public final class IntervalSettings {

    private static final System.Logger LOG = System.getLogger(IntervalSettings.class.getName());

    private final SettingsStore store;

    /** What the file holds; the effective usage interval may differ, through a command-line override. */
    private Settings saved;

    private int usageSeconds;

    private final int pollSeconds;

    private Consumer<Duration> usageListener = interval -> { };

    private Consumer<Settings> settingsListener = settings -> { };

    /** The heights the window had with the history and the log open; see {@link SettingsStore.Heights}. */
    private SettingsStore.Heights heights;

    private IntervalSettings(SettingsStore store, Settings saved, int usageSeconds, int pollSeconds) {
        this.store = store;
        this.saved = saved;
        this.usageSeconds = usageSeconds;
        this.pollSeconds = pollSeconds;
    }

    /**
     * @param cliUsage the {@code --usage-interval} override, if given; already validated
     * @param cliPoll the {@code --poll-interval} value, if given; already validated
     */
    public static IntervalSettings load(SettingsStore store, OptionalInt cliUsage, OptionalInt cliPoll) {
        Settings saved = store.load();
        int usage = cliUsage.orElse(saved.usageIntervalSeconds());
        int poll = cliPoll.orElse(IntervalRange.POLL.defaultValue());
        IntervalSettings settings = new IntervalSettings(store, saved, usage, poll);
        settings.heights = store.loadHeights();
        LOG.log(System.Logger.Level.INFO, "Usage interval " + usage + " s, poll interval " + poll + " s");
        return settings;
    }

    /** The height the window had the last time {@code panel} ({@code history} or {@code log}) was open, or 0. */
    public synchronized int storedHeight(String panel) {
        return heights.of(panel);
    }

    /**
     * Remembers the height of the window with {@code panel} open, in pixels of its content. A file that cannot
     * be written is logged and nothing more: the height is only a convenience.
     */
    public synchronized void storeHeight(String panel, int pixels) {
        SettingsStore.Heights updated = heights.withHeight(panel, pixels);
        if (updated.equals(heights)) {
            return;
        }
        try {
            store.saveHeights(updated);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save the height of the " + panel + " to " + store.file() + ": " + e.getMessage());
            return;
        }
        heights = updated;
        LOG.log(System.Logger.Level.INFO, "The " + panel + " window height is now " + pixels + " px");
    }

    /** Every setting as in force: what the file holds, but with the usage interval the run is using. */
    public synchronized Settings current() {
        return saved.withUsageIntervalSeconds(usageSeconds);
    }

    public synchronized int usageSeconds() {
        return usageSeconds;
    }

    /** Fixed for the run: the command-line value, or the default. */
    public int pollSeconds() {
        return pollSeconds;
    }

    public synchronized Duration usageInterval() {
        return Duration.ofSeconds(usageSeconds);
    }

    /** Registers who is told when the usage interval changes; replaces any earlier listener. */
    public synchronized void onUsageIntervalChange(Consumer<Duration> listener) {
        this.usageListener = listener;
    }

    /**
     * Registers who is told, with all the settings, when they change; replaces any earlier listener. It is
     * not told the settings the run starts with, which are {@link #current()}.
     */
    public synchronized void onChange(Consumer<Settings> listener) {
        this.settingsListener = listener;
    }

    /**
     * Commits a usage interval chosen in the UI. Nothing changes if it is out of
     * range, or if the file cannot be written.
     *
     * @throws InvalidSettingException if the value is outside its range
     * @throws SettingsException if the value could not be saved
     */
    public synchronized void updateUsage(long seconds) {
        if (!IntervalRange.USAGE.contains(seconds)) {
            throw new InvalidSettingException(IntervalRange.USAGE.describeLimit());
        }
        apply(current().withUsageIntervalSeconds((int) seconds));
    }

    /**
     * Commits a full set of settings chosen in the UI: they take effect at once, and are saved. The
     * usage interval replaces any command-line one for the rest of the run. Nothing changes if a
     * value is invalid or the file cannot be written.
     *
     * @throws InvalidSettingException if the usage interval is outside its range
     * @throws SettingsException if the settings could not be saved
     */
    public synchronized void apply(Settings settings) {
        if (!IntervalRange.USAGE.contains(settings.usageIntervalSeconds())) {
            throw new InvalidSettingException(IntervalRange.USAGE.describeLimit());
        }
        if (!settings.equals(saved)) {
            try {
                store.save(settings);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not save settings to " + store.file(), e);
                throw new SettingsException("The settings could not be saved: " + e.getMessage(), e);
            }
            LOG.log(System.Logger.Level.INFO, "Settings changed: " + settings);
            saved = settings;
            settingsListener.accept(current());
        }

        if (settings.usageIntervalSeconds() != usageSeconds) {
            usageSeconds = settings.usageIntervalSeconds();
            usageListener.accept(Duration.ofSeconds(usageSeconds));
        }
    }
}
