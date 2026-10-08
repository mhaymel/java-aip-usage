package org.example.usage;

/**
 * A request to the usage endpoint failed. The message is written to be shown
 * to the user as it stands, and contains neither the token nor the response
 * body.
 */
public final class UsageFetchException extends RuntimeException {

    private static final int UNAUTHORIZED = 401;

    private final int status;

    /** @param status the HTTP status, or {@code 0} if no response arrived */
    public UsageFetchException(String message, int status) {
        super(message);
        this.status = status;
    }

    public UsageFetchException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
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
