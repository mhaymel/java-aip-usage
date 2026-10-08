package org.example.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The two intervals in force, and how they change.
 *
 * <p>At startup a value comes from the command line if given, else from the
 * settings file, else the default. A value committed in the UI replaces the
 * command-line one for the rest of the run, takes effect at once, and is saved.
 * A command-line value is never saved on its own: it is an override for one
 * run, so the file keeps what the user last chose in the UI.
 */
public final class IntervalSettings {

    private static final System.Logger LOG = System.getLogger(IntervalSettings.class.getName());

    private final SettingsStore store;

    /** What the file holds; the effective values may differ, through a command-line override. */
    private SettingsStore.Saved saved;

    private int usageSeconds;

    private int pollSeconds;

    private Consumer<Duration> usageListener = interval -> { };

    private IntervalSettings(SettingsStore store, SettingsStore.Saved saved, int usageSeconds, int pollSeconds) {
        this.store = store;
        this.saved = saved;
        this.usageSeconds = usageSeconds;
        this.pollSeconds = pollSeconds;
    }

    /**
     * @param cliUsage the {@code --usage-interval} override, if given; already validated
     * @param cliPoll the {@code --poll-interval} override, if given; already validated
     */
    public static IntervalSettings load(SettingsStore store, OptionalInt cliUsage, OptionalInt cliPoll) {
        SettingsStore.Saved saved = store.load();
        int usage = cliUsage.orElse(orDefault(saved.usageIntervalSeconds(), IntervalRange.USAGE));
        int poll = cliPoll.orElse(orDefault(saved.pollIntervalSeconds(), IntervalRange.POLL));
        IntervalSettings settings = new IntervalSettings(store, saved, usage, poll);
        LOG.log(System.Logger.Level.INFO, "Usage interval " + usage + " s, poll interval " + poll + " s");
        return settings;
    }

    private static int orDefault(Integer value, IntervalRange range) {
        return value != null ? value : range.defaultValue();
    }

    public synchronized int usageSeconds() {
        return usageSeconds;
    }

    public synchronized int pollSeconds() {
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
     * Commits values chosen in the UI. Both are checked before either is
     * applied, and nothing changes if the file cannot be written.
     *
     * @param usage the new usage interval in seconds, or {@code null} to leave it
     * @param poll the new poll interval in seconds, or {@code null} to leave it
     * @throws InvalidSettingException if a value is outside its range
     * @throws SettingsException if the values could not be saved
     */
    public synchronized void update(Long usage, Long poll) {
        if (usage == null && poll == null) {
            throw new InvalidSettingException("No setting was given.");
        }
        if (usage != null && !IntervalRange.USAGE.contains(usage)) {
            throw new InvalidSettingException(IntervalRange.USAGE.describeLimit());
        }
        if (poll != null && !IntervalRange.POLL.contains(poll)) {
            throw new InvalidSettingException(IntervalRange.POLL.describeLimit());
        }

        SettingsStore.Saved updated = new SettingsStore.Saved(
                usage != null ? Integer.valueOf(usage.intValue()) : saved.usageIntervalSeconds(),
                poll != null ? Integer.valueOf(poll.intValue()) : saved.pollIntervalSeconds());
        if (!updated.equals(saved)) {
            try {
                store.save(updated);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not save settings to " + store.file(), e);
                throw new SettingsException("The setting could not be saved: " + e.getMessage(), e);
            }
            saved = updated;
        }

        if (poll != null) {
            pollSeconds = poll.intValue();
            LOG.log(System.Logger.Level.INFO, "Poll interval is now " + pollSeconds + " s");
        }
        if (usage != null && usage.intValue() != usageSeconds) {
            usageSeconds = usage.intValue();
            usageListener.accept(Duration.ofSeconds(usageSeconds));
        }
    }
}
