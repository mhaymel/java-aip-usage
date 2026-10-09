package org.example.usage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls {@code https://api.anthropic.com/api/oauth/usage} with a bearer token.
 *
 * <p>Redirects are not followed, so the token can never be forwarded to
 * another host: a 3xx is reported as a failure. Only the status and timing are
 * logged, never a header, and the body only while the setting to log the response is on.
 */
public final class UsageClient implements UsageSource {

    public static final URI DEFAULT_URI = URI.create("https://api.anthropic.com/api/oauth/usage");

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final System.Logger LOG = System.getLogger(UsageClient.class.getName());

    /** Shapes a header value must have to be logged; anything else is left out. */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");

    private final HttpClient http;

    private final URI uri;

    private final Duration requestTimeout;

    private final Clock clock;

    private final UsageParser parser = new UsageParser();

    private final ObjectMapper mapper = new ObjectMapper();

    private final BooleanSupplier logResponse;

    UsageClient(HttpClient http, URI uri, Duration requestTimeout, Clock clock) {
        this(http, uri, requestTimeout, clock, () -> false);
    }

    UsageClient(HttpClient http, URI uri, Duration requestTimeout, Clock clock, BooleanSupplier logResponse) {
        this.http = http;
        this.uri = uri;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
        this.logResponse = logResponse;
    }

    /** The client the application uses: the real endpoint, with connect and request timeouts. */
    public static UsageClient create() {
        return create(DEFAULT_URI);
    }

    /** The same client, pointed at another endpoint. */
    public static UsageClient create(URI uri) {
        return create(uri, () -> false);
    }

    /** @param logResponse asked for each response: whether its JSON is written to the log, pretty printed */
    public static UsageClient create(URI uri, BooleanSupplier logResponse) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new UsageClient(http, uri, REQUEST_TIMEOUT, Clock.systemUTC(), logResponse);
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
        logOutcome("HTTP " + status, started, diagnostics(response));
        if (logResponse.getAsBoolean()) {
            logBody(status, response.body());
        }
        if (status / 100 != 2) {
            throw new UsageFetchException(describe(status), status, retryAfter(response));
        }
        return parser.parse(response.body(), clock.instant());
    }

    /**
     * The response as the endpoint sent it, pretty printed over several lines after one line that says
     * whose it is. The log masks anything shaped like a credential on its way out, as for every line.
     */
    private void logBody(int status, String body) {
        String text;
        try {
            JsonNode json = mapper.readTree(body);
            text = json == null ? "(empty)" : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (IOException e) {
            text = "(not JSON, " + body.getBytes(StandardCharsets.UTF_8).length + " bytes, not logged)";
        }
        LOG.log(System.Logger.Level.INFO,
                "Response of GET " + uri.getHost() + uri.getPath() + " (HTTP " + status + "):" + System.lineSeparator() + text);
    }

    private void logOutcome(String outcome, long startedNanos) {
        logOutcome(outcome, startedNanos, "");
    }

    private void logOutcome(String outcome, long startedNanos, String details) {
        long millis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        LOG.log(System.Logger.Level.INFO,
                "GET " + uri.getHost() + uri.getPath() + " -> " + outcome + " in " + millis + " ms" + details);
    }

    /**
     * What is worth knowing about a response without recording any of it: its
     * size, and the few headers that identify the request to Anthropic's support
     * or explain a refusal. The body is never included, nor any other header,
     * and a header value is shown only if it has the plain shape expected.
     */
    private static String diagnostics(HttpResponse<String> response) {
        StringBuilder details = new StringBuilder(" (")
                .append(response.body().getBytes(StandardCharsets.UTF_8).length).append(" bytes");
        appendHeader(details, response, "request-id", "request-id", REQUEST_ID);
        appendHeader(details, response, "retry-after", "retry-after", SECONDS);
        return details.append(')').toString();
    }

    private static void appendHeader(
            StringBuilder details, HttpResponse<String> response, String header, String label, Pattern shape) {
        response.headers().firstValue(header).filter(value -> shape.matcher(value).matches())
                .ifPresent(value -> details.append(", ").append(label).append(' ').append(value));
    }

    /** The wait the server asked for, if it said so in whole seconds; anything else counts as no answer. */
    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after")
                .filter(value -> SECONDS.matcher(value).matches())
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(Duration.ZERO);
    }

    private static String describe(int status) {
        return switch (status) {
            case 401 -> "Anthropic rejected the OAuth token (HTTP 401).";
            case 403 -> "Anthropic refused the usage request (HTTP 403).";
            case 429 -> "Anthropic is rate limiting usage requests (HTTP 429).";
            default -> "Anthropic returned HTTP " + status + ".";
        };
    }
}
