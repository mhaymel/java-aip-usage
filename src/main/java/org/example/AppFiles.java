package org.example;

import java.nio.file.Path;

/**
 * The files the application keeps in its working directory, which is the project root.
 *
 * @param settings the usage interval chosen in the window
 * @param history the CSV of readings
 * @param log the run log, shown in the log window
 */
record AppFiles(Path settings, Path history, Path log) {

    static final String SETTINGS_NAME = "settings.json";

    static final String HISTORY_NAME = "java-aip-usage.csv";

    /** The usual places: next to {@code gradlew}, in the directory the program was started from. */
    static AppFiles inWorkingDirectory() {
        return new AppFiles(
                Path.of(SETTINGS_NAME).toAbsolutePath(),
                Path.of(HISTORY_NAME).toAbsolutePath(),
                Path.of(Logging.LOG_FILE_NAME).toAbsolutePath());
    }

    /** All three in one directory, under their usual names; for tests. */
    static AppFiles in(Path directory) {
        return new AppFiles(
                directory.resolve(SETTINGS_NAME), directory.resolve(HISTORY_NAME), directory.resolve(Logging.LOG_FILE_NAME));
    }
}
