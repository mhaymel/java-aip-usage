package org.example;

import org.example.log.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the log and the usage history are kept now: the program's folder, or the folders the settings name (since 0.81). Every
 * reader and writer of those files asks this, so that a folder changed while the program runs is used by all of them at once.
 * The settings file stays in the program's folder, since it is what says where the others are.
 */
final class FileFolders {

    private static final Log LOG = Log.of(FileFolders.class);

    private final AppFiles base;

    private volatile Path logFolder;

    private volatile Path historyFolder;

    private FileFolders(AppFiles base, Path logFolder, Path historyFolder) {
        this.base = base;
        this.logFolder = logFolder;
        this.historyFolder = historyFolder;
    }

    /** The program's own folder for both, as before 0.81. */
    static FileFolders of(AppFiles base) {
        return new FileFolders(base, programFolder(base), programFolder(base));
    }

    /**
     * The folders the settings name, each where it can be used; one that is no longer there, or cannot be written to, is not
     * used: the program's folder is, and the log says so. The setting is left as it is, so that the folder is used again once
     * it is back.
     */
    static FileFolders atStart(AppFiles base, String logSetting, String historySetting) {
        return new FileFolders(base, usableOr(base, logSetting, "log"), usableOr(base, historySetting, "usage history"));
    }

    private static Path usableOr(AppFiles base, String setting, String what) {
        Path program = programFolder(base);
        Path named = folderOf(setting, program);
        String fault = named.equals(program) ? null : fault(named);
        if (fault != null) {
            LOG.warning("The folder of the " + what + " cannot be used, so the program's folder is: " + named + ": " + fault);
            return program;
        }
        return named;
    }

    /** The folder the log and the history are kept in when the settings name none: the folder of the settings file. */
    static Path programFolder(AppFiles base) {
        return base.settings().toAbsolutePath().getParent();
    }

    /** The folder a setting names: the program's for an empty one, else the path it holds. */
    static Path folderOf(String setting, Path program) {
        return setting == null || setting.isBlank() ? program : Path.of(setting.strip()).toAbsolutePath().normalize();
    }

    /**
     * Why a file cannot be kept in this folder, in the program's own words, or null when one can: the folder is there, is a
     * folder, and a file can be written in it and deleted again.
     */
    static String fault(Path folder) {
        if (!Files.isDirectory(folder)) {
            return "the file or its folder is not there";
        }
        try {
            Path probe = Files.createTempFile(folder, ".aip-usage-", ".tmp");
            Files.delete(probe);
            return null;
        } catch (IOException | RuntimeException e) {
            return Failure.of(e);
        }
    }

    Path settings() {
        return base.settings();
    }

    Path log() {
        return logFolder.resolve(base.log().getFileName());
    }

    Path history() {
        return historyFolder.resolve(base.history().getFileName());
    }

    Path fakeHistory() {
        return historyFolder.resolve(AppFiles.FAKE_HISTORY_NAME);
    }

    Path logFolder() {
        return logFolder;
    }

    Path historyFolder() {
        return historyFolder;
    }

    Path programFolder() {
        return programFolder(base);
    }

    void logFolder(Path folder) {
        this.logFolder = folder;
    }

    void historyFolder(Path folder) {
        this.historyFolder = folder;
    }
}
