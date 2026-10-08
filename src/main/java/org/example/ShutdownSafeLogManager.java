package org.example;

import java.util.logging.LogManager;

/**
 * A log manager that leaves the log handlers open when the JVM shuts down.
 *
 * <p>The standard one registers a shutdown hook of its own that calls {@link #reset()}, which
 * removes and closes every handler. The JVM runs hooks side by side in no set order, so that
 * hook can win against the program's own, and then the lines saying how the program stopped go
 * nowhere. {@link Logging} closes its handlers itself, last, after those lines are written, so
 * nothing is left unclosed.
 *
 * <p>Nothing else in this program asks for a reset. It is named in the
 * {@code java.util.logging.manager} property, which the JDK reads once, so it must be set before
 * anything uses logging; {@link Logging#install} does that.
 */
public final class ShutdownSafeLogManager extends LogManager {

    @Override
    public void reset() {
        // Deliberately nothing: see the class comment.
    }
}
