package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves the bundled frontend over HTTP on the loopback interface only.
 *
 * <p>The port is chosen by the operating system so that several instances can
 * run side by side; {@link #baseUri()} reports the address actually bound.
 */
final class LocalWebServer implements AutoCloseable {

    private static final Logger LOG = System.getLogger(LocalWebServer.class.getName());

    /** Classpath directory holding the frontend assets. */
    private static final String RESOURCE_ROOT = "/web";

    private static final String INDEX = "index.html";

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml");

    private final HttpServer server;

    private LocalWebServer(HttpServer server) {
        this.server = server;
    }

    /** Binds to an ephemeral loopback port and starts serving the frontend. */
    static LocalWebServer start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        HttpServer server = HttpServer.create(address, 0);
        // Daemon threads so a closed window cannot keep the JVM alive.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", LocalWebServer::serveStatic);
        server.start();

        LocalWebServer started = new LocalWebServer(server);
        LOG.log(Level.INFO, "Frontend served at {0}", started.baseUri());
        return started;
    }

    /** The {@code http://127.0.0.1:<port>/} root the window should load. */
    URI baseUri() {
        InetSocketAddress bound = server.getAddress();
        return URI.create("http://" + bound.getHostString() + ":" + bound.getPort() + "/");
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.isEmpty() || path.equals("/")) {
                path = "/" + INDEX;
            }
            // Only ever read from the bundled resources, never the filesystem.
            String resource = RESOURCE_ROOT + path;
            if (path.contains("..")) {
                respondNotFound(exchange);
                return;
            }

            byte[] body;
            try (InputStream in = LocalWebServer.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOG.log(Level.WARNING, "No such frontend resource: {0}", resource);
                    respondNotFound(exchange);
                    return;
                }
                body = in.readAllBytes();
            }

            exchange.getResponseHeaders().set("Content-Type", contentTypeOf(path));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void respondNotFound(HttpExchange exchange) throws IOException {
        byte[] body = "Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(404, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String contentTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    @Override
    public void close() {
        server.stop(0);
        LOG.log(Level.INFO, "Frontend server stopped");
    }
}
