package org.example.settings;

/** A setting was refused because its value is not acceptable. Safe to show to the user. */
public final class InvalidSettingException extends RuntimeException {

    public InvalidSettingException(String message) {
        super(message);
    }
}
