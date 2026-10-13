package org.example;

import org.example.log.Log;

import java.net.URL;
import java.util.Locale;

/**
 * Gives the system the name of the program and its icon, where a program that is started as {@code java} would be
 * shown under that name: on macOS. The menu beside the apple then reads {@code Hide aip usage} and
 * {@code Quit aip usage}, and the Dock shows the gauge.
 *
 * <p>It is done by the program itself, so that it holds for a start from an IDE as for one from the build, with no
 * option and no application bundle. Each step is tried by itself, and one that cannot be taken is a debug line in the
 * log and stops nothing: the program is the same without it, under the name the system gives it.
 *
 * <p>What the program cannot do for itself is the name the menu bar shows in bold beside the apple, and the name under
 * the icon in the Dock: the system takes those from the bundle of an application, and a program started as
 * {@code java} has none.
 */
final class MacName {

    private static final Log LOG = Log.of(MacName.class);

    /**
     * Says whether the steps are taken, whatever the system: {@code true} or {@code false}. Not set, they are taken on
     * macOS and nowhere else. It is there to switch them off, should one of them do harm on some machine, and for the
     * test of the first step, which the toolkit takes the same way on every system.
     */
    static final String SWITCH = "aipusage.macname";

    private MacName() {
    }

    /** Whether the system is macOS, by the name it gives itself. */
    static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("mac");
    }

    /** Whether the steps are taken in this run: as the switch says, and on macOS when it says nothing. */
    static boolean applies() {
        String asked = System.getProperty(SWITCH);
        if (asked != null) {
            return asked.equals("true");
        }
        return isMac(System.getProperty("os.name"));
    }

    /**
     * Before the toolkit is started: the name for whatever of the system's own Java asks for one. The program loads that
     * part of Java only for the icon in the Dock, and it would otherwise name the program after its main class.
     */
    static void beforeLaunch() {
        if (!applies()) {
            return;
        }
        System.setProperty("apple.awt.application.name", AppInfo.NAME);
    }

    /**
     * Names the toolkit's application, which is what the toolkit writes into the menu beside the apple: {@code Hide} and
     * {@code Quit}, each followed by the name. It must be called while the application object is made, on the toolkit's
     * own thread and before anything else names the application: the toolkit takes a name only while it has none, and
     * gives the application the name of its class as soon as the object is made.
     *
     * <p>The toolkit has no public way to do this, so its own class is asked, by name. Where the toolkit is on the module
     * path that class is not open to the program unless the start says so, as the build's does; then this is a debug line.
     *
     * @return the name the toolkit's application has afterwards, or {@code null} if it could not be asked
     */
    static String nameTheApplication() {
        if (!applies()) {
            return null;
        }
        try {
            Class<?> glass = Class.forName("com.sun.glass.ui.Application");
            Object application = glass.getMethod("GetApplication").invoke(null);
            glass.getMethod("setName", String.class).invoke(application, AppInfo.NAME);
            String name = (String) glass.getMethod("getName").invoke(application);
            LOG.debug("The application is named for the system: " + name);
            return name;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.debug("The application could not be named for the system: " + Failure.of(e));
            return null;
        }
    }

    /**
     * Gives the Dock the program's icon, once the window is there. The toolkit of the window has no way to do it, so the
     * system's own Java is asked, on a thread of its own: it is loaded for this alone, and whatever it does or fails to
     * do there holds up neither the window nor the refreshing.
     *
     * <p>Not done when the start already named an icon for the Dock, which the build's start does and the toolkit follows.
     */
    static void showTheIconInTheDock() {
        if (!applies() || System.getenv("APP_ICON_" + ProcessHandle.current().pid()) != null) {
            return;
        }
        Thread dock = new Thread(MacName::setTheDockIcon, "dock-icon");
        dock.setDaemon(true);
        dock.start();
    }

    private static void setTheDockIcon() {
        try {
            URL largest = MacName.class.getResource(AppIcons.resource(AppIcons.SIZES.get(AppIcons.SIZES.size() - 1)));
            java.awt.Image icon = javax.imageio.ImageIO.read(largest);
            if (!java.awt.Taskbar.isTaskbarSupported() || !java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                LOG.debug("The system takes no icon for the Dock from the program");
                return;
            }
            java.awt.Taskbar.getTaskbar().setIconImage(icon);
            LOG.debug("The Dock has the program's icon");
        } catch (Exception | LinkageError e) {
            LOG.debug("The Dock could not be given the program's icon: " + Failure.of(e));
        }
    }
}
