package org.example.token;

import org.example.Cancel;

/** Source of the OAuth access token used to call the usage endpoint. */
public interface TokenProvider {

    /**
     * Obtains a token, fresh on every call: callers cache it, and call again
     * when the endpoint rejects it.
     *
     * @throws TokenException if no token can be obtained
     */
    String acquire();

    /**
     * As {@link #acquire()}, giving up when it is told to. A provider that has its token at once has nothing to give
     * up and need not say so.
     *
     * @param cancel told when the token is no longer wanted
     * @throws TokenException if no token can be obtained, or the waiting was given up
     */
    default String acquire(Cancel cancel) {
        return acquire();
    }
}
