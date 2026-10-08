package org.example;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Serves the bundled frontend and the JSON API over HTTP on the loopback
 * interface only.
 *
 * <p>The port is chosen by the operating system so that several instances can
 * run side by side; {@link #baseUri()} reports the address actually bound.
 * Requests whose {@code Host} header is not this server's own address are
 * refused, which stops a web page elsewhere from reaching the API by pointing a
 * hostname of its own at 127.0.0.1 (DNS rebinding).
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

    /**
     * Binds to an ephemeral loopback port and starts serving.
     *
     * @param api handles everything under {@code /api/}
     */
    static LocalWebServer start(HttpHandler api) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        HttpServer server = HttpServer.create(address, 0);
        // Virtual threads are daemon threads, so a closed window cannot keep the JVM alive.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        int port = server.getAddress().getPort();
        Filter hostCheck = new HostFilter(Set.of("127.0.0.1:" + port, "localhost:" + port));
        server.createContext("/", LocalWebServer::serveStatic).getFilters().add(hostCheck);
        server.createContext("/api/", api).getFilters().add(hostCheck);
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
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void respondNotFound(HttpExchange exchange) throws IOException {
        respond(exchange, 404, "Not found");
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String contentTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    /** Refuses any request not addressed to this server by its own loopback name. */
    private static final class HostFilter extends Filter {

        private final Set<String> allowed;

        HostFilter(Set<String> allowed) {
            this.allowed = allowed;
        }

        @Override
        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (host != null && allowed.contains(host.toLowerCase(Locale.ROOT))) {
                chain.doFilter(exchange);
                return;
            }
            try (exchange) {
                respond(exchange, 403, "Forbidden");
            }
        }

        @Override
        public String description() {
            return "Only requests addressed to this server's own loopback host are accepted";
        }
    }

    @Override
    public void close() {
        server.stop(0);
        LOG.log(Level.INFO, "Frontend server stopped");
    }
}
