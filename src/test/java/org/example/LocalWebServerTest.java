package org.example;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalWebServerTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void bindsToLoopbackOnly() throws Exception {
        try (LocalWebServer server = LocalWebServer.start()) {
            assertTrue(java.net.InetAddress.getByName(server.baseUri().getHost()).isLoopbackAddress());
        }
    }

    @Test
    void servesIndexAtRoot() throws Exception {
        try (LocalWebServer server = LocalWebServer.start()) {
            HttpResponse<String> response = get(server.baseUri());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(response.body().contains("java-aip usage"));
        }
    }

    @Test
    void servesStaticAssetsWithMatchingContentType() throws Exception {
        try (LocalWebServer server = LocalWebServer.start()) {
            HttpResponse<String> js = get(server.baseUri().resolve("app.js"));
            assertEquals(200, js.statusCode());
            assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));

            HttpResponse<String> css = get(server.baseUri().resolve("app.css"));
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        }
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        try (LocalWebServer server = LocalWebServer.start()) {
            assertEquals(404, get(server.baseUri().resolve("missing.html")).statusCode());
        }
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        try (LocalWebServer server = LocalWebServer.start()) {
            URI traversal = URI.create(server.baseUri() + "../Main.class");
            assertEquals(404, get(traversal).statusCode());
        }
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
}
