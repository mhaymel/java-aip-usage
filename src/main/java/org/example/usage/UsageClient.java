package org.example.usage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;

/**
 * Calls {@code https://api.anthropic.com/api/oauth/usage} with a bearer token.
 *
 * <p>Redirects are not followed, so the token can never be forwarded to
 * another host: a 3xx is reported as a failure. Only the status and timing are
 * logged, never a header or the body.
 */
public final class UsageClient implements UsageSource {

    public static final URI DEFAULT_URI = URI.create("https://api.anthropic.com/api/oauth/usage");

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final System.Logger LOG = System.getLogger(UsageClient.class.getName());

    private final HttpClient http;

    private final URI uri;

    private final Duration requestTimeout;

    private final Clock clock;

    private final UsageParser parser = new UsageParser();

    UsageClient(HttpClient http, URI uri, Duration requestTimeout, Clock clock) {
        this.http = http;
        this.uri = uri;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    /** The client the application uses: the real endpoint, with connect and request timeouts. */
    public static UsageClient create() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new UsageClient(http, DEFAULT_URI, REQUEST_TIMEOUT, Clock.systemUTC());
    }

    @Override
    public UsageSnapshot fetch(String token) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            logOutcome("timed out", started);
            throw new UsageFetchException(
                    "Anthropic did not answer within " + requestTimeout.toSeconds() + " seconds.", e);
        } catch (IOException e) {
            logOutcome("failed", started);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new UsageFetchException("Cannot reach " + uri.getHost() + ": " + detail, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsageFetchException("Interrupted while fetching usage.", e);
        }

        int status = response.statusCode();
        logOutcome("HTTP " + status, started);
        if (status / 100 != 2) {
            throw new UsageFetchException(describe(status), status);
        }
        return parser.parse(response.body(), clock.instant());
    }

    private void logOutcome(String outcome, long startedNanos) {
        long millis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        LOG.log(System.Logger.Level.INFO, "GET " + uri.getHost() + uri.getPath() + " -> " + outcome + " in " + millis + " ms");
    }

    private static String describe(int status) {
        return switch (status) {
            case 401 -> "Anthropic rejected the OAuth token (HTTP 401).";
            case 403 -> "Anthropic refused the usage request (HTTP 403).";
            case 429 -> "Anthropic is rate limiting usage requests (HTTP 429); this is retried at the next refresh.";
            default -> "Anthropic returned HTTP " + status + ".";
        };
    }
}
