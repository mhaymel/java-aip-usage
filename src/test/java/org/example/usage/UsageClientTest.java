package org.example.usage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void stopServers() {
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
