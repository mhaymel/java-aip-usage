package org.example.token;

/**
 * No token could be obtained. The message tells the user what to do about it
 * and is meant to be shown as it stands. It never contains a credential.
 */
public final class TokenException extends RuntimeException {

    /** Why acquisition failed, so the caller can choose how to present it. */
    public enum Reason {
        /** The {@code claude} executable could not be started. */
        NOT_INSTALLED,
        /** {@code claude} ran but sent no credential: not logged in. */
        NOT_LOGGED_IN,
        /** An environment credential would be sent instead of the OAuth token. */
        ENV_CONFLICT,
        /** {@code claude} sent no credential in time. */
        TIMEOUT,
        /** The local capture server could not be started. */
        CAPTURE_FAILED,
        /** The calling thread was interrupted. */
        INTERRUPTED
    }

    private final Reason reason;

    public TokenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TokenException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
