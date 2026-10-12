package org.example;

import org.example.fake.Scenario;
import org.example.usage.UsageSnapshot;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where usage comes from, which in debug mode can change while the program runs: the fake backend
 * is switched on and off in the debug settings. What the API needs to know of it is here.
 */
interface UsageSources {

    /** Whether the program was started in debug mode; without it the debug settings count for nothing. */
    boolean debug();

    /** Whether the fake backend is in use at this moment, so that the readings are invented. */
    boolean fake();

    /** The history file of the source in use: invented readings have one of their own. */
    Path history();

    /** How long the latest usage request that has ended took, or a negative number when none has. */
    long latestDurationMillis();

    /** The desktop the program asks to show a file in its folder and to take a text on its clipboard. */
    default Desk desk() {
        return Desk.SYSTEM;
    }

    /**
     * Saves the debug settings and makes them take effect at once: the source is opened anew and a refresh follows.
     *
     * @throws org.example.settings.SettingsException if the source cannot be opened or the settings cannot be saved;
     *     nothing is changed then
     */
    void applyDebug(boolean fakeBackend, Scenario scenario, boolean fakeToken);

    /** How the fake backend's ordinary answer is shaped at the moment. */
    default org.example.fake.FakeOptions fakeOptions() {
        return org.example.fake.FakeOptions.DEFAULTS;
    }

    /**
     * The three switches of debug mode, and how the fake backend's ordinary answer is shaped. Options alone that change
     * are given to the fake backend that runs; a switch that changes opens the source of usage anew.
     */
    default void applyDebug(boolean fakeBackend, Scenario scenario, boolean fakeToken, org.example.fake.FakeOptions options) {
        applyDebug(fakeBackend, scenario, fakeToken);
    }

    /**
     * One source of usage.
     *
     * @param fetcher produces one reading from it
     * @param fake whether it is the fake backend
     * @param base the base URL it fetches from, for the log to name; empty when there is none to name
     * @param resource what is closed when the source is left, the fake backend; or null
     */
    record Source(Supplier<UsageSnapshot> fetcher, boolean fake, Optional<URI> base, AutoCloseable resource) {
    }

    /** Opens a source as the debug settings say. In a run that is not in debug mode it is asked once, for the real one. */
    interface Factory {

        Source open(boolean fakeBackend, Scenario scenario, boolean fakeToken) throws Exception;

        /** As above, with how the fake backend's ordinary answer is shaped; a factory that has no use for that need not say so. */
        default Source open(boolean fakeBackend, Scenario scenario, boolean fakeToken, org.example.fake.FakeOptions options) throws Exception {
            return open(fakeBackend, scenario, fakeToken);
        }
    }
}
