package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.settings.LaunchOptions;
import org.example.usage.UsageClient;
import org.example.usage.UsageFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path a refresh takes, from the application wiring through real HTTP
 * to the log, with credentials and response content planted at every point
 * where they could leak: the token in the request, a body that echoes it, and
 * markers inside the responses. What is checked is what ends up written to the
 * log file and to the console.
 */
class LoggingEndToEndTest {

    private static final String FIRST_TOKEN = "sk-ant-oat01-E2E-FIRST-TOKEN";

    private static final String SECOND_TOKEN = "sk-ant-oat01-E2E-SECOND-TOKEN";

    private static final String BODY_MARKER = "BODY-MARKER-9f3a";

    private record Output(String file, String console) {
    }

    @TempDir
    Path dir;

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    private final PrintStream originalErr = System.err;

    @AfterEach
    void cleanUp() {
        System.setErr(originalErr);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void aSuccessfulRefreshLeavesItsEventsInTheFileAndTheConsoleAndNothingSecret() throws Exception {
        URI endpoint = serve(exchange -> reply(exchange, 200, fixture().replace("{", "{\"internal_note\":\"" + BODY_MARKER + "\",")));

        Output output = run(endpoint, tokens(FIRST_TOKEN), app -> app.service().state().snapshot() != null);

        for (String text : List.of(output.file(), output.console())) {
            assertContainsInOrder(text,
                    "Logging to",
                    "Usage interval 60 s, poll interval 1 s",
                    "Frontend served at",
                    "GET 127.0.0.1/usage -> HTTP 200 in",
                    "Usage refresh succeeded",
                    "Usage refresh stopped",
                    "Frontend server stopped");
            assertNothingSensitive(text);
        }
    }

    @Test
    void aFailedRefreshIsRecordedWithItsReasonButNotTheResponse() throws Exception {
        URI endpoint = serve(exchange -> reply(exchange, 500, "{\"error\":\"" + BODY_MARKER + " echo " + FIRST_TOKEN + "\"}"));

        Output output = run(endpoint, tokens(FIRST_TOKEN), app -> app.service().state().error() != null);

        for (String text : List.of(output.file(), output.console())) {
            assertTrue(text.contains("HTTP 500 in"), text);
            assertTrue(text.contains("Usage refresh failed: Anthropic returned HTTP 500."), text);
            assertNothingSensitive(text);
        }
    }

    @Test
    void aRejectedTokenAndItsReplacementAreRecordedWithoutEitherToken() throws Exception {
        URI endpoint = serve(exchange -> {
            boolean first = exchange.getRequestHeaders().getFirst("Authorization").endsWith(FIRST_TOKEN);
            reply(exchange, first ? 401 : 200, first ? "{\"error\":\"bad " + FIRST_TOKEN + "\"}" : fixture());
        });

        Output output = run(endpoint, tokens(FIRST_TOKEN, SECOND_TOKEN), app -> app.service().state().snapshot() != null);

        for (String text : List.of(output.file(), output.console())) {
            assertContainsInOrder(text,
                    "HTTP 401 in",
                    "The token was rejected (HTTP 401); acquiring a fresh one",
                    "HTTP 200 in",
                    "Usage refresh succeeded");
            assertNothingSensitive(text);
        }
    }

    @Test
    void anUnreachableEndpointIsRecordedWithoutTheToken() throws Exception {
        URI endpoint = serve(exchange -> reply(exchange, 200, "{}"));
        servers.getFirst().stop(0);

        Output output = run(endpoint, tokens(FIRST_TOKEN), app -> app.service().state().error() != null);

        for (String text : List.of(output.file(), output.console())) {
            assertTrue(text.contains("Usage refresh failed: Cannot reach 127.0.0.1"), text);
            assertNothingSensitive(text);
        }
    }

    @Test
    void successiveRunsAppendToTheSameFile() throws Exception {
        URI endpoint = serve(exchange -> reply(exchange, 200, fixture()));

        run(endpoint, tokens(FIRST_TOKEN), app -> app.service().state().snapshot() != null);
        Output second = run(endpoint, tokens(FIRST_TOKEN), app -> app.service().state().snapshot() != null);

        assertEquals(2, second.file().split("Usage interval 60 s", -1).length - 1, "both runs are in the file");
        assertEquals(1, second.console().split("Usage interval 60 s", -1).length - 1, "the console shows only this run");
    }

    // ---- helpers

    /** Runs the application once against {@code endpoint} and returns what it logged. */
    private Output run(URI endpoint, org.example.token.TokenProvider tokens, Function<AppRuntime, Boolean> done) throws Exception {
        Path logFile = dir.resolve("java-aip-usage.log");
        long fileStart = Files.exists(logFile) ? Files.size(logFile) : 0;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));

        try (Logging logging = Logging.install(logFile)) {
            AppRuntime app = AppRuntime.start(
                    new AppFiles(dir.resolve("settings.json"), dir.resolve("history.csv"), logFile), LaunchOptions.none(),
                    new UsageFetcher(tokens, UsageClient.create(endpoint)));
            try {
                await(() -> done.apply(app));
            } finally {
                app.close();
            }
        }
        System.setErr(originalErr);
        return new Output(Files.readString(logFile), console.toString(StandardCharsets.UTF_8));
    }

    /** Hands out the given tokens in turn, the last one repeatedly. */
    private static org.example.token.TokenProvider tokens(String... tokens) {
        AtomicInteger next = new AtomicInteger();
        return () -> tokens[Math.min(next.getAndIncrement(), tokens.length - 1)];
    }

    private static void assertNothingSensitive(String log) {
        for (String forbidden : new String[] {
                FIRST_TOKEN, SECOND_TOKEN, "E2E-FIRST", "E2E-SECOND", "sk-ant-", BODY_MARKER, "Bearer", "Authorization",
                "amount_minor", "internal_note", "seven_day"}) {
            assertFalse(log.contains(forbidden), "\"" + forbidden + "\" must not be logged:\n" + log);
        }
    }

    private static void assertContainsInOrder(String text, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int at = text.indexOf(fragment, from);
            assertTrue(at >= 0, "expected \"" + fragment + "\" after offset " + from + " in:\n" + text);
            from = at + fragment.length();
        }
    }

    private URI serve(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/usage", exchange -> {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                exchange.close();
                throw e;
            }
        });
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/usage");
    }

    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String fixture() throws IOException {
        try (InputStream in = LoggingEndToEndTest.class.getResourceAsStream("/fixtures/usage-credits.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
