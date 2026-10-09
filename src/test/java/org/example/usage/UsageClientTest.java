package org.example.usage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs {@link UsageClient} against a real HTTP server on loopback, standing in for Anthropic. */
class UsageClientTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private static final String TOKEN = "sk-ant-oat01-client-test-token";

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    private final List<String> logLines = new CopyOnWriteArrayList<>();

    private final java.util.logging.Handler logCapture = new java.util.logging.Handler() {
        @Override
        public void publish(java.util.logging.LogRecord record) {
            logLines.add(record.getLevel() + " " + new java.util.logging.SimpleFormatter().formatMessage(record));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        java.util.logging.Logger.getLogger("").addHandler(logCapture);
    }

    @AfterEach
    void stopServers() {
        java.util.logging.Logger.getLogger("").removeHandler(logCapture);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void sendsTheTokenAsABearerTokenAndDecodesTheResponse() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        URI uri = serve("/usage", exchange -> {
            seen.add(exchange.getRequestMethod());
            seen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            seen.add(exchange.getRequestHeaders().getFirst("Accept"));
            reply(exchange, 200, fixture("usage-credits.json"));
        });

        UsageSnapshot snapshot = client(uri).fetch(TOKEN);

        assertEquals(List.of("GET", "Bearer " + TOKEN, "application/json"), seen);
        assertEquals(NOW, snapshot.fetchedAt());
        assertEquals(186.02, snapshot.spend().used());
    }

    @Test
    void reportsAnUnauthorizedResponseAsSuch() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 401, "{\"error\":\"nope\"}"));

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        assertTrue(e.isUnauthorized());
        assertEquals(401, e.status());
    }

    @Test
    void reportsOtherStatusesWithoutTreatingThemAsUnauthorized() throws Exception {
        for (int status : new int[] {400, 403, 404, 429, 500, 503}) {
            URI uri = serve("/usage", exchange -> reply(exchange, status, "{}"));

            UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

            assertEquals(status, e.status());
            assertFalse(e.isUnauthorized(), "status " + status);
            assertTrue(e.getMessage().contains(Integer.toString(status)), e.getMessage());
        }
    }

    @Test
    void reportsAnUnreachableServer() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, "{}"));
        servers.getFirst().stop(0);

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        assertEquals(0, e.status());
        assertTrue(e.getMessage().startsWith("Cannot reach"), e.getMessage());
    }

    @Test
    void givesUpOnAServerThatDoesNotAnswerInTime() throws Exception {
        URI uri = serve("/usage", exchange -> {
            sleep(2000);
            reply(exchange, 200, "{}");
        });
        UsageClient impatient =
                new UsageClient(plainHttpClient(), uri, Duration.ofMillis(300), Clock.fixed(NOW, ZoneOffset.UTC));

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> impatient.fetch(TOKEN));

        assertEquals(0, e.status());
        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
    }

    @Test
    void aResponseThatIsNotAUsageDocumentIsAParseFailureNotAFetchFailure() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, "<html>captive portal</html>"));

        assertThrows(UsageParseException.class, () -> client(uri).fetch(TOKEN));
    }

    @Test
    void doesNotFollowRedirectsSoTheTokenCannotBeForwarded() throws Exception {
        AtomicInteger redirectTargetHits = new AtomicInteger();
        List<String> authorizationAtTarget = new CopyOnWriteArrayList<>();
        HttpServer server = newServer();
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            authorizationAtTarget.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            reply(exchange, 200, fixture("usage-credits.json"));
        });
        server.createContext("/usage", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            reply(exchange, 302, "");
        });
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/usage");

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        assertEquals(302, e.status());
        assertEquals(0, redirectTargetHits.get());
        assertTrue(authorizationAtTarget.isEmpty());
    }

    @Test
    void failureMessagesNeverContainTheToken() throws Exception {
        for (int status : new int[] {401, 403, 429, 500}) {
            URI uri = serve("/usage", exchange -> reply(exchange, status, "echo " + TOKEN));

            UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

            assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
            assertFalse(e.getMessage().contains("echo"), e.getMessage());
            assertNull(e.getCause());
        }
    }

    // ---- what is logged about a request

    @Test
    void logsTheOutcomeOfARequestWithoutTheTokenOrTheBody() throws Exception {
        URI uri = serve("/usage", exchange -> {
            exchange.getResponseHeaders().add("request-id", "req_011CTestRequest");
            reply(exchange, 200, fixture("usage-credits.json"));
        });

        client(uri).fetch(TOKEN);

        String logged = String.join("\n", logLines);
        assertTrue(logged.matches("(?s).*GET 127\\.0\\.0\\.1/usage -> HTTP 200 in \\d+ ms \\(\\d+ bytes, request-id req_011CTestRequest\\).*"), logged);
        assertFalse(logged.contains(TOKEN), logged);
        assertFalse(logged.contains("amount_minor"), "no part of the body is logged: " + logged);
    }

    @Test
    void whileTheSettingIsOnTheResponseIsLoggedPrettyPrintedOverSeveralLines() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, fixture("usage-credits.json")));
        ResponseLog on = new ResponseLog();
        on.set(true);

        new UsageClient(plainHttpClient(), uri, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC), on).fetch(TOKEN);

        String logged = String.join("\n", logLines);
        assertTrue(logged.contains("Response of GET 127.0.0.1/usage (HTTP 200):" + System.lineSeparator() + "{"), logged);
        assertTrue(logged.contains(System.lineSeparator() + "  \""), "indented, one member to a line: " + logged);
        assertTrue(logged.contains("amount_minor"), logged);
        assertFalse(logged.contains(TOKEN), logged);
        assertFalse(logged.contains("Authorization"), "no header: " + logged);
    }

    @Test
    void theSettingIsAskedForEachResponseSoItTakesEffectAtOnce() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, fixture("usage-credits.json")));
        ResponseLog log = new ResponseLog();
        UsageClient client = new UsageClient(plainHttpClient(), uri, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC), log);

        client.fetch(TOKEN);
        assertFalse(String.join("\n", logLines).contains("Response of GET"), "off by default");

        log.set(true);
        client.fetch(TOKEN);
        assertTrue(String.join("\n", logLines).contains("Response of GET"));
    }

    @Test
    void aResponseThatIsNotJsonIsNotLoggedEvenWithTheSettingOn() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 503, "<html>LEAK secret page</html>"));
        ResponseLog on = new ResponseLog();
        on.set(true);

        assertThrows(UsageFetchException.class,
                () -> new UsageClient(plainHttpClient(), uri, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC), on).fetch(TOKEN));

        String logged = String.join("\n", logLines);
        assertTrue(logged.contains("(not JSON,"), logged);
        assertFalse(logged.contains("LEAK"), logged);
    }

    @Test
    void aCredentialInsideALoggedResponseIsMasked() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, "{\"access_token\": \"" + TOKEN + "\", \"windows\": []}"));
        ResponseLog on = new ResponseLog();
        on.set(true);

        try {
            new UsageClient(plainHttpClient(), uri, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC), on).fetch(TOKEN);
        } catch (RuntimeException expectedMaybe) {
            // the body is not a usage document; what is logged is what is being checked
        }

        // This test captures records before the formatter, so the masking is checked on the real formatter's input.
        assertTrue(org.example.Redaction.redact(String.join("\n", logLines)).contains("[redacted]"));
        assertFalse(org.example.Redaction.redact(String.join("\n", logLines)).contains(TOKEN));
    }

    @Test
    void logsAFailureStatusAndWhenToRetry() throws Exception {
        URI uri = serve("/usage", exchange -> {
            exchange.getResponseHeaders().add("retry-after", "30");
            reply(exchange, 429, "{\"error\":\"slow down " + TOKEN + "\"}");
        });

        assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        String logged = String.join("\n", logLines);
        assertTrue(logged.contains("HTTP 429"), logged);
        assertTrue(logged.contains("retry-after 30"), logged);
        assertFalse(logged.contains(TOKEN), logged);
        assertFalse(logged.contains("slow down"), logged);
    }

    @Test
    void leavesOutAHeaderValueThatDoesNotHaveTheExpectedShape() throws Exception {
        URI uri = serve("/usage", exchange -> {
            exchange.getResponseHeaders().add("request-id", "<script>alert(1)</script> sk-ant-oat01-LEAK");
            exchange.getResponseHeaders().add("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT");
            reply(exchange, 503, "{}");
        });

        assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        String logged = String.join("\n", logLines);
        assertFalse(logged.contains("script"), logged);
        assertFalse(logged.contains("LEAK"), logged);
        assertFalse(logged.contains("retry-after"), logged);
        assertTrue(logged.contains("HTTP 503"), logged);
    }

    @Test
    void passesOnHowLongTheServerAskedUsToWait() throws Exception {
        URI uri = serve("/usage", exchange -> {
            exchange.getResponseHeaders().add("retry-after", "45");
            reply(exchange, 429, "{}");
        });

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        assertEquals(429, e.status());
        assertEquals(Duration.ofSeconds(45), e.retryAfter());
    }

    @Test
    void aRetryAfterThatIsNotWholeSecondsCountsAsNoAnswer() throws Exception {
        for (String value : new String[] {"Wed, 21 Oct 2026 07:28:00 GMT", "-5", "1.5", "soon", "", "99999999999999999999"}) {
            URI uri = serve("/usage", exchange -> {
                exchange.getResponseHeaders().add("retry-after", value);
                reply(exchange, 429, "{}");
            });

            UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

            assertEquals(Duration.ZERO, e.retryAfter(), "retry-after: " + value);
        }
    }

    @Test
    void aZeroRetryAfterIsNoAnswerEither() throws Exception {
        URI uri = serve("/usage", exchange -> {
            exchange.getResponseHeaders().add("retry-after", "0");
            reply(exchange, 429, "{}");
        });

        assertEquals(Duration.ZERO, assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN)).retryAfter());
    }

    @Test
    void theRateLimitMessageDoesNotPromiseAnyParticularRetry() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 429, "{}"));

        UsageFetchException e = assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        assertEquals("Anthropic is rate limiting usage requests (HTTP 429).", e.getMessage());
    }

    @Test
    void logsAConnectionFailureWithoutTheTokenToo() throws Exception {
        URI uri = serve("/usage", exchange -> reply(exchange, 200, "{}"));
        servers.getFirst().stop(0);

        assertThrows(UsageFetchException.class, () -> client(uri).fetch(TOKEN));

        String logged = String.join("\n", logLines);
        assertTrue(logged.contains("failed in"), logged);
        assertFalse(logged.contains(TOKEN), logged);
    }

    private UsageClient client(URI uri) {
        return new UsageClient(plainHttpClient(), uri, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HttpClient plainHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI serve(String path, HttpHandler handler) throws IOException {
        HttpServer server = newServer();
        server.createContext(path, handler);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private HttpServer newServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        servers.add(server);
        return server;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = UsageClientTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
