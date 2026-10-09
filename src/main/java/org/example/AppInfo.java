package org.example;

/** What the program is called, and which version of it this is. */
final class AppInfo {

    /** The name shown in the title bar, and the title of the page in the window. */
    static final String NAME = "aip usage";

    /**
     * The version, shown in the title bar. It is written here by hand and increased by
     * hand whenever the program changes. It is unrelated to the Gradle project version.
     */
    static final String VERSION = "0.11";

    private AppInfo() {
    }

    /** The title of the window: the name and the version, for example {@code aip usage v0.01}. */
    static String windowTitle() {
        return NAME + " v" + VERSION;
    }
}
