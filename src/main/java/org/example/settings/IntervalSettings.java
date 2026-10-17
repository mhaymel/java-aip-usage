package org.example.settings;

import org.example.Failure;
import org.example.log.Log;
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

    private static final Log LOG = Log.of(IntervalSettings.class);

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
     * @param cliUsage an interval that is used for this run in place of the file's and is never saved, if given; the command
     *     line had an option for it once and has none now, so the application gives none
     * @param cliPoll how often the window asks for the latest state, if not the default; likewise no longer given
     */
    public static IntervalSettings load(SettingsStore store, OptionalInt cliUsage, OptionalInt cliPoll) {
        Settings saved = store.load();
        int usage = cliUsage.orElse(saved.usageIntervalSeconds());
        int poll = cliPoll.orElse(IntervalRange.POLL.defaultValue());
        IntervalSettings settings = new IntervalSettings(store, saved, usage, poll);
        settings.heights = store.loadHeights();
        LOG.info("Usage interval " + usage + " s, poll interval " + poll + " s");
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
            LOG.warning("Could not save the height of the " + panel + " to " + store.file(), e);
            return;
        }
        heights = updated;
        LOG.fine("The " + panel + " window height is now " + pixels + " px");
    }

    /** The width the window had the last time {@code panel}, the log (since 0.93) or the burndown (since 0.99), was shown alone, or 0. */
    public synchronized int storedWidth(String panel) {
        return heights.widthOf(panel);
    }

    /** Remembers the width of the window with the log or the burndown shown alone, as {@link #storeHeight} remembers a height. */
    public synchronized void storeWidth(String panel, int pixels) {
        SettingsStore.Heights updated = heights.withWidth(panel, pixels);
        if (updated.equals(heights)) {
            return;
        }
        try {
            store.saveHeights(updated);
        } catch (IOException e) {
            LOG.warning("Could not save the width of the " + panel + " to " + store.file(), e);
            return;
        }
        heights = updated;
        LOG.fine("The " + panel + " window width is now " + pixels + " px");
    }

    /** How the fake backend's ordinary answer is shaped, as saved; the defaults if it never was. */
    public synchronized org.example.fake.FakeOptions fakeOptions() {
        return store.loadFake();
    }

    /**
     * Saves how the fake backend's ordinary answer is shaped.
     *
     * @throws SettingsException if the file cannot be written; nothing is changed then
     */
    public synchronized void storeFake(org.example.fake.FakeOptions options) {
        try {
            store.saveFake(options);
        } catch (IOException e) {
            LOG.warning("Could not save the fake backend's options to " + store.file(), e);
            throw new SettingsException("The settings could not be saved: " + Failure.of(e), e);
        }
        LOG.info("The fake backend's options changed: " + options);
    }

    /** What the window showed when the program last stopped, as stored; nothing if it never was. */
    public synchronized SettingsStore.LastWindow lastWindow() {
        return store.loadLast();
    }

    /** Remembers what the window shows. A file that cannot be written is logged and nothing more: it is only a convenience. */
    public synchronized void storeLastWindow(SettingsStore.LastWindow last) {
        try {
            store.saveLast(last);
        } catch (IOException e) {
            LOG.warning("Could not save the last window to " + store.file(), e);
        }
    }

    /** Where the search for the automatic interval was when it was last saved; its beginning if it never was. */
    public synchronized SettingsStore.Search storedSearch() {
        return store.loadSearch();
    }

    /**
     * Remembers where the search for the automatic interval is. A file that cannot be written is logged and nothing more:
     * the search goes on in this run, and begins where it was last saved in the next.
     */
    public synchronized void storeSearch(int interval, Integer refused) {
        try {
            store.saveSearch(new SettingsStore.Search(interval, refused));
        } catch (IOException e) {
            LOG.warning("Could not save the automatic interval to " + store.file(), e);
        }
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
            throw new InvalidSettingException(IntervalRange.USAGE.describeLimit(), "usageIntervalSeconds");
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
            throw new InvalidSettingException(IntervalRange.USAGE.describeLimit(), "usageIntervalSeconds");
        }
        if (!settings.equals(saved)) {
            try {
                store.save(settings);
            } catch (IOException e) {
                LOG.warning("Could not save settings to " + store.file(), e);
                throw new SettingsException("The settings could not be saved: " + Failure.of(e), e);
            }
            // What changed and no more, and a Fine line (since 0.95): the lists' own choices save the settings at every press.
            String changes = SettingsStore.changes(saved, settings);
            if (!changes.isEmpty()) {
                LOG.fine("Settings changed: " + changes);
            }
            saved = settings;
            settingsListener.accept(current());
        }

        if (settings.usageIntervalSeconds() != usageSeconds) {
            usageSeconds = settings.usageIntervalSeconds();
            usageListener.accept(Duration.ofSeconds(usageSeconds));
        }
    }
}
