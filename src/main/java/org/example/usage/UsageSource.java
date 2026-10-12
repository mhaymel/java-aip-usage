package org.example.usage;

import org.example.Cancel;

/** Fetches one {@link UsageSnapshot} from the usage endpoint. */
public interface UsageSource {

    /**
     * @param token the OAuth access token to send as a bearer token
     * @throws UsageFetchException if the request fails or is refused
     * @throws UsageParseException if the response is not a usage document
     */
    UsageSnapshot fetch(String token);

    /**
     * As {@link #fetch(String)}, giving up when it is told to. A source that answers at once has nothing to give up
     * and need not say so.
     *
     * @param cancel told when the reading is no longer wanted
     * @throws UsageFetchException also if the request was given up
     */
    default UsageSnapshot fetch(String token, Cancel cancel) {
        return fetch(token);
    }
}
