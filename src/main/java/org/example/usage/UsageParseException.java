package org.example.usage;

/**
 * The usage response could not be understood. The message is written to be
 * shown to the user as it stands, and never contains the response body.
 */
public final class UsageParseException extends RuntimeException {

    public UsageParseException(String message) {
        super(message);
    }

    public UsageParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
