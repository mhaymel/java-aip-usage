package org.example;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWebServerTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** A stand-in for the API: answers every request under /api/ with "api". */
    private static LocalWebServer start() throws IOException {
        return LocalWebServer.start(exchange -> {
            try (exchange) {
                byte[] body = "api".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
    }

    @Test
    void bindsToLoopbackOnly() throws Exception {
        try (LocalWebServer server = start()) {
            assertTrue(InetAddress.getByName(server.baseUri().getHost()).isLoopbackAddress());
        }
    }

    @Test
    void servesIndexAtRoot() throws Exception {
        try (LocalWebServer server = start()) {
            HttpResponse<String> response = get(server.baseUri());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(response.body().contains("java-aip usage"));
        }
    }

    @Test
    void servesStaticAssetsWithMatchingContentType() throws Exception {
        try (LocalWebServer server = start()) {
            HttpResponse<String> js = get(server.baseUri().resolve("app.js"));
            assertEquals(200, js.statusCode());
            assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));

            HttpResponse<String> css = get(server.baseUri().resolve("app.css"));
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        }
    }

    @Test
    void staticAssetsAreNeverCachedSoAnUpdateIsAlwaysSeen() throws Exception {
        try (LocalWebServer server = start()) {
            HttpResponse<String> response = get(server.baseUri());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        }
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        try (LocalWebServer server = start()) {
            assertEquals(404, get(server.baseUri().resolve("missing.html")).statusCode());
        }
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        try (LocalWebServer server = start()) {
            assertEquals(404, statusOf(server, "/../Main.class", host(server)));
            assertEquals(404, statusOf(server, "/%2e%2e/Main.class", host(server)));
        }
    }

    @Test
    void routesApiPathsToTheApiHandler() throws Exception {
        try (LocalWebServer server = start()) {
            HttpResponse<String> response = get(server.baseUri().resolve("api/anything"));
            assertEquals(200, response.statusCode());
            assertEquals("api", response.body());
        }
    }

    @Test
    void acceptsTheServersOwnNamesInTheHostHeader() throws Exception {
        try (LocalWebServer server = start()) {
            int port = server.baseUri().getPort();
            assertEquals(200, statusOf(server, "/", "127.0.0.1:" + port));
            assertEquals(200, statusOf(server, "/", "localhost:" + port));
            assertEquals(200, statusOf(server, "/", "LOCALHOST:" + port));
        }
    }

    @Test
    void refusesAnyOtherHostHeaderOnPagesAndApiAlike() throws Exception {
        try (LocalWebServer server = start()) {
            int port = server.baseUri().getPort();
            for (String host : new String[] {
                    "evil.example", "evil.example:" + port, "127.0.0.1", "127.0.0.1:" + (port + 1),
                    "127.0.0.1.evil.example:" + port, "localhost", ""}) {
                assertEquals(403, statusOf(server, "/", host), "page, Host: " + host);
                assertEquals(403, statusOf(server, "/api/status", host), "api, Host: " + host);
            }
        }
    }

    @Test
    void refusesARequestWithNoHostHeader() throws Exception {
        try (LocalWebServer server = start();
             Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.baseUri().getPort())) {
            socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertTrue(in.readLine().contains("403"));
        }
    }

    private static String host(LocalWebServer server) {
        return "127.0.0.1:" + server.baseUri().getPort();
    }

    /** Sends a GET by hand, so the Host header can be anything. */
    private static int statusOf(LocalWebServer server, String path, String host) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.baseUri().getPort())) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return Integer.parseInt(in.readLine().split(" ")[1]);
        }
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
}
