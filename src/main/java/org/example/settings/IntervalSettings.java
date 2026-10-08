package org.example.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The two intervals in force, and how the usage interval changes.
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
    private SettingsStore.Saved saved;

    private int usageSeconds;

    private final int pollSeconds;

    private Consumer<Duration> usageListener = interval -> { };

    private IntervalSettings(SettingsStore store, SettingsStore.Saved saved, int usageSeconds, int pollSeconds) {
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
        SettingsStore.Saved saved = store.load();
        int usage = cliUsage.orElse(saved.usageIntervalSeconds() != null
                ? saved.usageIntervalSeconds()
                : IntervalRange.USAGE.defaultValue());
        int poll = cliPoll.orElse(IntervalRange.POLL.defaultValue());
        IntervalSettings settings = new IntervalSettings(store, saved, usage, poll);
        LOG.log(System.Logger.Level.INFO, "Usage interval " + usage + " s, poll interval " + poll + " s");
        return settings;
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

        SettingsStore.Saved updated = new SettingsStore.Saved((int) seconds);
        if (!updated.equals(saved)) {
            try {
                store.save(updated);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not save settings to " + store.file(), e);
                throw new SettingsException("The setting could not be saved: " + e.getMessage(), e);
            }
            saved = updated;
        }

        if (seconds != usageSeconds) {
            usageSeconds = (int) seconds;
            usageListener.accept(Duration.ofSeconds(usageSeconds));
        }
    }
}
