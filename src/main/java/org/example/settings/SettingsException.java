package org.example.settings;

/** A valid setting could not be saved. Safe to show to the user. */
public final class SettingsException extends RuntimeException {

    public SettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
