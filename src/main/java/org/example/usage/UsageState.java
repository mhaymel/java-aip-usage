package org.example.usage;

import java.time.Instant;

/**
 * What the UI shows: the latest good reading, and the outcome of the latest
 * refresh. After a failed refresh the last good {@code snapshot} is kept and
 * the state is {@link #stale()}; the next success replaces it and clears the
 * error. Before the first success there is no snapshot, only an error.
 *
 * @param snapshot the most recent successful reading, or {@code null}
 * @param error what went wrong with the latest refresh, or {@code null}
 * @param errorAt when that happened, or {@code null}
 * @param refreshing whether a refresh is running now
 * @param rateLimited whether {@code error} is an HTTP 429, the server's request to slow down: the reading is
 *     not out of date for it, so it is not {@link #stale()}, and the window shows it more quietly
 */
public record UsageState(UsageSnapshot snapshot, String error, Instant errorAt, boolean refreshing, boolean rateLimited) {

    public UsageState(UsageSnapshot snapshot, String error, Instant errorAt, boolean refreshing) {
        this(snapshot, error, errorAt, refreshing, false);
    }


    static UsageState initial() {
        return new UsageState(null, null, null, false);
    }

    /** Whether {@code snapshot} predates a failed refresh and may be out of date. */
    public boolean stale() {
        return snapshot != null && error != null && !rateLimited;
    }

    UsageState startedRefresh() {
        return new UsageState(snapshot, error, errorAt, true, rateLimited);
    }

    UsageState succeeded(UsageSnapshot fresh) {
        return new UsageState(fresh, null, null, false);
    }

    UsageState failed(String message, Instant at, boolean rateLimited) {
        return new UsageState(snapshot, message, at, false, rateLimited);
    }
}
