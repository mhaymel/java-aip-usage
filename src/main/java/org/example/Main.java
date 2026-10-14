package org.example;

import javafx.application.Application;

import org.example.settings.LaunchOptions;

import java.nio.file.Path;
import java.util.List;

/**
 * The entry point: reads the command line, sets up logging, and starts the window.
 *
 * <p>This class is on purpose not a JavaFX {@code Application}. Java refuses to start
 * such a class from the plain classpath, which is how an IDE runs a {@code main}
 * method, with "JavaFX runtime components are missing". Starting the window from here,
 * with {@link Application#launch}, works from the classpath and from the module path
 * that {@code ./gradlew run} uses alike.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(List.of(args));
        } catch (LaunchOptions.InvalidOptionsException e) {
            System.err.println(Failure.of(e));
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(LaunchOptions.USAGE);
            return;
        }

        // Relative to the working directory, which is the project root for
        // `./gradlew run` and for IDE run configurations.
        // In debug mode the console also shows where a failure came from; the log file never does.
        // The folder of the log may be another (since 0.81): the settings say, and one that cannot be used is passed over, which
        // the runtime logs when it reads the settings again.
        Path logFile = logFileAtStart();
        try (Logging logging = Logging.install(logFile, options.debug())) {
            // Also tidies up if the process is told to stop; see ShutdownHook.
            ShutdownHook.install(() -> UsageApp.shutDown(), logging);
            // On macOS a program started as java is shown under that name: the system is told the program's own.
            MacName.beforeLaunch();
            Application.launch(UsageApp.class, args);
        }
    }

    /**
     * The log file in the folder the settings name, or in the program's folder when they name none or one that cannot be used.
     * Read before anything is logged, so that the first line is in the file it belongs in.
     */
    private static Path logFileAtStart() {
        AppFiles files = AppFiles.inWorkingDirectory();
        String folder = new org.example.settings.SettingsStore(files.settings()).load().logFolder();
        return FileFolders.atStart(files, folder, "").log();
    }
}
