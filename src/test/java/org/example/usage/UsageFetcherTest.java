package org.example.usage;

import org.example.token.TokenException;
import org.example.token.TokenException.Reason;
import org.example.token.TokenProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageFetcherTest {

    private static final UsageSnapshot SNAPSHOT = new UsageSnapshot(Instant.EPOCH, null);

    private final List<String> tokensIssued = new ArrayList<>();

    private final List<String> tokensSent = new ArrayList<>();

    /** Hands out token-1, token-2, ... and records how often it was asked. */
    private final TokenProvider tokens = () -> {
        String token = "token-" + (tokensIssued.size() + 1);
        tokensIssued.add(token);
        return token;
    };

    private UsageFetcher fetcherAnswering(Function<String, UsageSnapshot> answer) {
        return new UsageFetcher(tokens, token -> {
            tokensSent.add(token);
            return answer.apply(token);
        });
    }

    private static UsageFetchException http(int status) {
        return new UsageFetchException("HTTP " + status, status);
    }

    @Test
    void fetchesWithAFreshlyAcquiredToken() {
        UsageFetcher fetcher = fetcherAnswering(token -> SNAPSHOT);

        assertSame(SNAPSHOT, fetcher.get());
        assertEquals(List.of("token-1"), tokensSent);
    }

    @Test
    void reusesTheTokenForLaterFetches() {
        UsageFetcher fetcher = fetcherAnswering(token -> SNAPSHOT);

        fetcher.get();
        fetcher.get();
        fetcher.get();

        assertEquals(1, tokensIssued.size());
        assertEquals(List.of("token-1", "token-1", "token-1"), tokensSent);
    }

    @Test
    void anUnauthorizedResponseGetsAFreshTokenAndExactlyOneRetry() {
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (token.equals("token-1")) {
                throw http(401);
            }
            return SNAPSHOT;
        });

        assertSame(SNAPSHOT, fetcher.get());

        assertEquals(List.of("token-1", "token-2"), tokensIssued);
        assertEquals(List.of("token-1", "token-2"), tokensSent);
    }

    @Test
    void theFreshTokenIsReusedAfterwards() {
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (token.equals("token-1")) {
                throw http(401);
            }
            return SNAPSHOT;
        });

        fetcher.get();
        fetcher.get();

        assertEquals(2, tokensIssued.size());
        assertEquals(List.of("token-1", "token-2", "token-2"), tokensSent);
    }

    @Test
    void aSecondUnauthorizedResponseIsNotRetriedAgain() {
        UsageFetcher fetcher = fetcherAnswering(token -> {
            throw http(401);
        });

        UsageFetchException e = assertThrows(UsageFetchException.class, fetcher::get);

        assertTrue(e.isUnauthorized());
        assertTrue(e.getMessage().contains("freshly obtained"), e.getMessage());
        assertEquals(List.of("token-1", "token-2"), tokensSent);
    }

    @Test
    void afterRepeatedRejectionTheNextFetchStartsWithAFreshToken() {
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (token.equals("token-1") || token.equals("token-2")) {
                throw http(401);
            }
            return SNAPSHOT;
        });

        assertThrows(UsageFetchException.class, fetcher::get);
        assertSame(SNAPSHOT, fetcher.get());

        assertEquals(List.of("token-1", "token-2", "token-3"), tokensIssued);
        assertEquals("token-3", tokensSent.get(tokensSent.size() - 1));
    }

    @Test
    void otherHttpFailuresNeitherRetryNorReplaceTheToken() {
        for (int status : new int[] {400, 403, 404, 429, 500, 503}) {
            tokensIssued.clear();
            tokensSent.clear();
            UsageFetcher fetcher = fetcherAnswering(token -> {
                throw http(status);
            });

            UsageFetchException e = assertThrows(UsageFetchException.class, fetcher::get);

            assertEquals(status, e.status());
            assertEquals(1, tokensIssued.size(), "status " + status);
            assertEquals(1, tokensSent.size(), "status " + status);
        }
    }

    @Test
    void aFailureThatIsNotAnUnauthorizedResponseKeepsTheTokenForNextTime() {
        boolean[] failNext = {true};
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw http(500);
            }
            return SNAPSHOT;
        });

        assertThrows(UsageFetchException.class, fetcher::get);
        fetcher.get();

        assertEquals(1, tokensIssued.size());
    }

    @Test
    void networkFailuresAreNotRetriedAndKeepTheToken() {
        boolean[] failNext = {true};
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw new UsageFetchException("Cannot reach host", new IOException("down"));
            }
            return SNAPSHOT;
        });

        UsageFetchException e = assertThrows(UsageFetchException.class, fetcher::get);
        assertEquals(0, e.status());
        assertFalse(e.isUnauthorized());
        fetcher.get();

        assertEquals(1, tokensIssued.size());
        assertEquals(2, tokensSent.size());
    }

    @Test
    void anUnparseableResponseIsNotRetriedAndKeepsTheToken() {
        boolean[] failNext = {true};
        UsageFetcher fetcher = fetcherAnswering(token -> {
            if (failNext[0]) {
                failNext[0] = false;
                throw new UsageParseException("The usage response is not a JSON object.");
            }
            return SNAPSHOT;
        });

        assertThrows(UsageParseException.class, fetcher::get);
        fetcher.get();

        assertEquals(1, tokensIssued.size());
    }

    @Test
    void aMissingTokenIsReportedAndAcquiredAgainOnTheNextFetch() {
        boolean[] loggedIn = {false};
        TokenProvider flaky = () -> {
            if (!loggedIn[0]) {
                throw new TokenException(Reason.NOT_LOGGED_IN, "Log in first.");
            }
            return "token-1";
        };
        UsageFetcher fetcher = new UsageFetcher(flaky, token -> SNAPSHOT);

        TokenException e = assertThrows(TokenException.class, fetcher::get);
        assertEquals(Reason.NOT_LOGGED_IN, e.reason());

        loggedIn[0] = true;
        assertSame(SNAPSHOT, fetcher.get());
    }

    @Test
    void failingToGetAFreshTokenAfterRejectionIsReportedAndTheRejectedTokenIsNotReused() {
        int[] acquisitions = {0};
        TokenProvider provider = () -> {
            acquisitions[0]++;
            // The second acquisition, the one made after the 401, fails.
            if (acquisitions[0] == 2) {
                throw new TokenException(Reason.NOT_LOGGED_IN, "Log in first.");
            }
            return "token-" + acquisitions[0];
        };
        List<String> sent = new ArrayList<>();
        UsageFetcher fetcher = new UsageFetcher(provider, token -> {
            sent.add(token);
            if (token.equals("token-1")) {
                throw http(401);
            }
            return SNAPSHOT;
        });

        TokenException e = assertThrows(TokenException.class, fetcher::get);
        assertEquals(Reason.NOT_LOGGED_IN, e.reason());

        assertSame(SNAPSHOT, fetcher.get());
        // token-1 was rejected and must not be sent again; token-3 came from a new acquisition.
        assertEquals(List.of("token-1", "token-3"), sent);
    }
}
