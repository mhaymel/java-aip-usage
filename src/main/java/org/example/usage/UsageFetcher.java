package org.example.usage;

import org.example.token.TokenProvider;

import java.util.function.Supplier;

/**
 * One usage reading, with the token handled as the requirements specify: a
 * token is fetched once and reused, and only an HTTP 401 triggers a fresh one,
 * followed by exactly one retry. No other failure, HTTP or network, replaces
 * the token or is retried.
 *
 * <p>Meant to be called by one thread at a time; calls are serialised anyway.
 */
public final class UsageFetcher implements Supplier<UsageSnapshot> {

    private static final System.Logger LOG = System.getLogger(UsageFetcher.class.getName());

    private final TokenProvider tokens;

    private final UsageSource source;

    /** The token in use, or {@code null} until one is needed. Never logged. */
    private String token;

    public UsageFetcher(TokenProvider tokens, UsageSource source) {
        this.tokens = tokens;
        this.source = source;
    }

    /**
     * @throws org.example.token.TokenException if no token can be obtained
     * @throws UsageFetchException if the request fails or is refused
     * @throws UsageParseException if the response is not a usage document
     */
    @Override
    public synchronized UsageSnapshot get() {
        if (token == null) {
            token = tokens.acquire();
        }
        try {
            return source.fetch(token);
        } catch (UsageFetchException rejected) {
            if (!rejected.isUnauthorized()) {
                throw rejected;
            }
        }

        LOG.log(System.Logger.Level.INFO, "The token was rejected (HTTP 401); acquiring a fresh one");
        // Dropped first, so a failure to get a new token leaves nothing stale behind.
        token = null;
        token = tokens.acquire();
        try {
            return source.fetch(token);
        } catch (UsageFetchException again) {
            if (again.isUnauthorized()) {
                token = null;
                throw new UsageFetchException(
                        "Anthropic rejected a freshly obtained OAuth token (HTTP 401)."
                                + " Log in again with Claude Code, then refresh.",
                        again.status());
            }
            throw again;
        }
    }
}
