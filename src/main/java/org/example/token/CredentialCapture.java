package org.example.token;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The local server the {@code claude} subprocess is pointed at.
 *
 * <p>It answers every request itself and never forwards to the real backend,
 * so acquiring a token costs nothing. The first credential that arrives, in
 * {@code x-api-key} or as a bearer token, completes {@link #credential()}.
 * Requests without a credential are answered and otherwise ignored.
 *
 * <p>Adapted from {@code java-aip}, which adapted it from
 * {@code java-claude-code-fetch-oauth-token}. It binds loopback only, on a port
 * chosen by the operating system.
 */
final class CredentialCapture implements AutoCloseable {

    private static final String BEARER = "Bearer ";

    /** A minimal valid SSE stream ending in {@code message_stop}. */
    private static final String SSE_RESPONSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_mock","type":"message","role":"assistant",\
            "model":"claude-opus-4-5","content":[],"stop_reason":null,"stop_sequence":null,\
            "usage":{"input_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"pong"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String JSON_RESPONSE =
            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}";

    private final HttpServer server;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final CompletableFuture<String> credential = new CompletableFuture<>();

    private CredentialCapture(HttpServer server) {
        this.server = server;
    }

    static CredentialCapture start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        CredentialCapture capture = new CredentialCapture(HttpServer.create(address, 0));
        capture.server.createContext("/", capture::handle);
        capture.server.setExecutor(capture.executor);
        capture.server.start();
        return capture;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Completes with the first credential received. */
    CompletableFuture<String> credential() {
        return credential;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String found = credentialOf(exchange);
            if (found != null) {
                credential.complete(found);
            }
            respond(exchange, body);
        }
    }

    /** The credential the request carries, or {@code null}. Header lookup ignores case. */
    private static String credentialOf(HttpExchange exchange) {
        String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        String authorization = exchange.getRequestHeaders().getFirst("authorization");
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String token = authorization.substring(BEARER.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private static void respond(HttpExchange exchange, String requestBody) throws IOException {
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        boolean streaming = requestBody.replace(" ", "").contains("\"stream\":true");
        String payload = streaming ? SSE_RESPONSE : JSON_RESPONSE;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
