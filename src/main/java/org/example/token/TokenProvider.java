package org.example.token;

/** Source of the OAuth access token used to call the usage endpoint. */
public interface TokenProvider {

    /**
     * Obtains a token, fresh on every call: callers cache it, and call again
     * when the endpoint rejects it.
     *
     * @throws TokenException if no token can be obtained
     */
    String acquire();
}
