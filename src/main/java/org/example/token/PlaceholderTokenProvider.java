package org.example.token;

/**
 * Stands in for the token flow when a run asks for no real token
 * ({@code --fake-token}, or {@code --fake-backend}, which implies it). Obtains
 * nothing and cannot fail: there is no {@code claude} to start, nobody to be
 * logged in, and no account to have.
 *
 * <p>Sent to a server that does not look at it, such as the fake backend, it is
 * simply ignored; sent to the real endpoint it is rejected, which is the ordinary
 * HTTP 401 path and needs no handling of its own.
 */
public final class PlaceholderTokenProvider implements TokenProvider {

    /**
     * Deliberately not shaped like a credential: nothing here matches what
     * {@link org.example.Redaction} masks, so a log that mentions it stays readable
     * and nobody reading one has to wonder whether a real token leaked.
     */
    public static final String TOKEN = "placeholder-no-real-token-was-obtained";

    @Override
    public String acquire() {
        return TOKEN;
    }
}
