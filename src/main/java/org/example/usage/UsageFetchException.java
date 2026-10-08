package org.example.usage;

import java.time.Duration;

/**
 * A request to the usage endpoint failed. The message is written to be shown
 * to the user as it stands, and contains neither the token nor the response
 * body.
 */
public final class UsageFetchException extends RuntimeException {

    private static final int UNAUTHORIZED = 401;

    private final int status;

    private final Duration retryAfter;

    /** @param status the HTTP status, or {@code 0} if no response arrived */
    public UsageFetchException(String message, int status) {
        this(message, status, Duration.ZERO);
    }

    /** @param retryAfter how long the server asked to wait, or zero if it did not say */
    public UsageFetchException(String message, int status, Duration retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public UsageFetchException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.retryAfter = Duration.ZERO;
    }

    /** How long the server asked to wait before trying again; zero if it gave no usable answer. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The HTTP status, or {@code 0} if the request failed before any response. */
    public int status() {
        return status;
    }

    /** Whether the endpoint rejected the token, the one failure worth a fresh token. */
    public boolean isUnauthorized() {
        return status == UNAUTHORIZED;
    }
}
