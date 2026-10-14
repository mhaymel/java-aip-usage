package org.example;

import javafx.application.Application;

import org.example.log.Log;
import org.example.settings.LaunchOptions;
import org.example.settings.Settings;
import org.example.settings.SettingsStore;

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
            if (moveNote != null) {
                LOG.info(moveNote);
            }
            if (moveFault != null) {
                LOG.warning(moveFault);
            }
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
        SettingsStore store = new SettingsStore(files.settings());
        Settings settings = store.load();
        FileFolders folders = FileFolders.atStart(files, settings.logFolder(), "");
        moveOldLog(store, settings, files, folders.logFolder());
        return folders.log();
    }

    /**
     * Since 0.87 the log is kept in the folder of the usage history: a settings file of before that named another for the log has
     * the log and its old logs moved there, once, before the log is opened, and is then saved without its two folders of before.
     * A move that fails puts back what it moved, and is tried again at the next start; the log is opened in the one folder all
     * the same. What happened is logged once the log is open.
     */
    private static void moveOldLog(SettingsStore store, Settings settings, AppFiles files, Path folder) {
        String old = store.oldLogFolder();
        if (old == null) {
            return;
        }
        Path from = FileFolders.folderOf(old, FileFolders.programFolder(files));
        try {
            if (!from.equals(folder) && java.nio.file.Files.isDirectory(from)) {
                List<Path> moved = Logging.moveFiles(from.resolve(files.log().getFileName()), folder);
                moveNote = "The log and its old logs are now kept with the usage history in " + folder + "; moved from " + from + ": "
                        + moved.size() + (moved.size() == 1 ? " file" : " files");
            }
            store.save(settings);
        } catch (java.io.IOException | RuntimeException e) {
            moveFault = "Could not move the log from " + from + " to the folder of the usage history, " + folder + ": " + Failure.of(e)
                    + "; it is tried again at the next start";
        }
    }

    private static final Log LOG = Log.of(Main.class);

    /** What the move of the log of 0.86 did, or why it failed, to be logged once the log is open; null when there was none. */
    private static String moveNote;

    private static String moveFault;
}
