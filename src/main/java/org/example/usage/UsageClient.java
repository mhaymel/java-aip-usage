package org.example.usage;

import org.example.Failure;
import org.example.log.Log;
import org.example.Cancel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.example.json.Json;
import org.example.json.JsonException;
import org.example.json.JsonReader;
import org.example.json.JsonWriter;

/**
 * Calls {@link #USAGE_PATH} on a base URL with a bearer token, Anthropic's own
 * unless the run named another.
 *
 * <p>Redirects are not followed, so the token can never be forwarded to a host
 * nobody named: a 3xx is reported as a failure. Where the request goes in the
 * first place is the run's to choose. Only the status and timing are
 * logged, never a header, and the body only while the setting to log the response is on.
 */
public final class UsageClient implements UsageSource {

    /**
     * The path of the usage endpoint, appended to whatever base URL is in force. Not
     * configurable: what this class and {@link UsageParser} understand is the document
     * this path serves.
     */
    public static final String USAGE_PATH = "/api/oauth/usage";

    /** Anthropic's own base URL, which is where usage comes from unless a run says otherwise. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.anthropic.com");

    public static final URI DEFAULT_URI = usageUri(DEFAULT_BASE_URL);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private static final Log LOG = Log.of(UsageClient.class);

    /** Shapes a header value must have to be logged; anything else is left out. */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,6}");

    private final HttpClient http;

    private final URI uri;

    private final Duration requestTimeout;

    private final Clock clock;

    private final UsageParser parser = new UsageParser();

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

    /**
     * The endpoint to fetch on a base URL. Joined as text rather than with
     * {@link URI#resolve}, which would throw away a path the base URL already has and
     * so break a server reached under one, such as a proxy at {@code http://host/proxy}.
     */
    public static URI usageUri(URI baseUrl) {
        String base = baseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + USAGE_PATH);
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
        return fetch(token, new Cancel());
    }

    @Override
    public UsageSnapshot fetch(String token, Cancel cancel) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        // Sent so that it can be given up: the answer is waited for here, and whoever cancels ends the waiting and the request.
        CompletableFuture<HttpResponse<String>> answer = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        cancel.onCancel(() -> answer.cancel(true));
        try {
            response = answer.get();
        } catch (CancellationException e) {
            throw new UsageFetchException("Interrupted while fetching usage.", e);
        } catch (ExecutionException e) {
            // A request that was given up ends in one way or the other, as the client sees fit; it is the same thing.
            if (cancel.isCancelled() || e.getCause() instanceof CancellationException) {
                throw new UsageFetchException("Interrupted while fetching usage.", e.getCause());
            }
            if (e.getCause() instanceof HttpTimeoutException) {
                logOutcome("timed out", started);
                throw new UsageFetchException(
                        "Anthropic did not answer within " + requestTimeout.toSeconds() + " seconds.", e.getCause());
            }
            logOutcome("failed", started);
            String detail = Failure.ofConnection(e.getCause());
            throw new UsageFetchException("Cannot reach " + uri.getHost() + ": " + detail, e.getCause());
        } catch (InterruptedException e) {
            // Nothing of the program does this; whatever did wants the request gone.
            answer.cancel(true);
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
        try {
            return parser.parse(response.body(), clock.instant());
        } catch (UsageParseException e) {
            logUnprocessed(e, response.body());
            throw e;
        }
    }

    /** How much of a response that could not be processed is written to the log with the error. */
    static final int UNPROCESSED_CHARACTERS = 2000;

    /**
     * An error for a response the program read and could not make a reading of, because of its format: the reason, and
     * on the lines after it the beginning of the body, so that a format that has changed can be seen in the log. The log
     * masks anything shaped like a credential on its way out, as for every line. It is written whether or not the
     * responses are logged; the failed refresh has its own line, a warning, as every failed refresh has.
     */
    private static void logUnprocessed(UsageParseException reason, String body) {
        String text = body == null || body.isEmpty() ? "(empty)" : body;
        String cut = "";
        if (text.length() > UNPROCESSED_CHARACTERS) {
            cut = " (the first " + UNPROCESSED_CHARACTERS + " of " + text.length() + " characters)";
            text = text.substring(0, UNPROCESSED_CHARACTERS);
        }
        LOG.error(
                "The usage response could not be processed: " + Failure.of(reason) + cut + System.lineSeparator() + text);
    }

    /**
     * The response as the endpoint sent it, pretty printed over several lines after one line that says
     * whose it is. The log masks anything shaped like a credential on its way out, as for every line.
     */
    private void logBody(int status, String body) {
        String text;
        try {
            Json json = JsonReader.read(body);
            text = json == null ? "(empty)" : JsonWriter.pretty(json, System.lineSeparator());
        } catch (JsonException e) {
            text = "(not JSON, " + body.getBytes(StandardCharsets.UTF_8).length + " bytes, not logged)";
        }
        LOG.info(
                "Response of GET " + shown(uri) + " (HTTP " + status + "):" + System.lineSeparator() + text);
    }

    /**
     * The address as the log writes it (since 0.94): the scheme, the host and the port when there is one, and the path, where it
     * was the host and the path alone. The query, of which there is none, is left out.
     */
    static String shown(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
    }

    private void logOutcome(String outcome, long startedNanos) {
        logOutcome(outcome, startedNanos, "");
    }

    private void logOutcome(String outcome, long startedNanos, String details) {
        long millis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        String line = "GET " + shown(uri) + " -> " + outcome + " in " + millis + " ms" + details;
        // A request that succeeds is a line every few seconds: at DEBUG since 0.92, a Fine line since 0.95. One that fails stays at
        // INFO, before its ERROR.
        if ("HTTP 200".equals(outcome)) {
            LOG.fine(line);
        } else {
            LOG.info(line);
        }
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
