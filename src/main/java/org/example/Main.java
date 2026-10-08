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
            System.err.println(e.getMessage());
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
        try (Logging logging = Logging.install(Path.of(Logging.LOG_FILE_NAME).toAbsolutePath())) {
            // Also tidies up if the process is told to stop; see ShutdownHook. UsageApp must not be
            // loaded before this point: its logger would start the logging system with the JDK's
            // own manager, and Logging could no longer choose the one that keeps the log open.
            ShutdownHook.install(() -> UsageApp.shutDown(), logging);
            Application.launch(UsageApp.class, args);
        }
    }
}
