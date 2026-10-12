package org.example.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the Anthropic usage endpoint, inside this program. Started when the debug
 * settings switch it on, in debug mode, it lets the application run with no account, nobody logged
 * in and nothing spent on the real endpoint, and it answers badly on request so that
 * the failure paths can be seen without waiting for the real endpoint to misbehave.
 *
 * <p>It is a real HTTP server on the loopback interface, not a {@code UsageSource}
 * fitted in place of the client, so a fake run exercises the connection, the status
 * handling, the timeouts and the refusal to follow a redirect exactly as a real one
 * does. The application is given its address and cannot tell it is talking to itself.
 *
 * <p>It is a server of its own on its own port rather than a handler added to the
 * application's local web server, which keeps it out of the application's API and out
 * of reach of the page.
 */
public final class FakeBackend implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(FakeBackend.class.getName());

    /** Later than the request timeout of {@link org.example.usage.UsageClient}, so the fetch gives up first. */
    static final Duration SLOW_ANSWER = Duration.ofSeconds(25);

    /** How long a hanging request is held before it is dropped; any client has long since gone. */
    static final Duration HANG_HOLD = Duration.ofMinutes(5);

    /** Seconds named by {@link Scenario#HTTP_429_RETRY_AFTER}, which the back-off must honour. */
    static final int RETRY_AFTER_SECONDS = 42;

    private final HttpServer server;

    private final UsageDocument document;

    /** Draws a whole number from 0 to the most given: the cents an amount grows by, the milliseconds an answer waits. */
    private final java.util.function.IntUnaryOperator dice;

    private volatile FakeOptions options;

    /** When the request before this one was answered and not refused, by the clock, in milliseconds; 0 before any was. */
    private long lastAnsweredMillis;

    private final Clock clock;

    /** Counted down by {@link #close()} so that a waiting request stops waiting. */
    private final CountDownLatch closed = new CountDownLatch(1);

    /** Read on each request, written when the scenario is switched while the server runs. */
    private volatile Scenario scenario;

    private FakeBackend(HttpServer server, Scenario scenario, Clock clock, FakeOptions options, java.util.function.IntUnaryOperator dice) {
        this.server = server;
        this.scenario = scenario;
        this.clock = clock;
        this.options = options;
        this.dice = dice;
        this.document = new UsageDocument(options, dice);
    }

    /** Binds an ephemeral loopback port and starts answering with {@code scenario}. */
    public static FakeBackend start(Scenario scenario) throws IOException {
        return start(scenario, Clock.systemUTC());
    }

    /**
     * The fake backend as it was before it could be shaped, and without chance: an account billed by usage whose amount
     * grows by 1.37 at every answer, so that what it answers can be said in advance.
     */
    static FakeBackend start(Scenario scenario, Clock clock) throws IOException {
        return start(scenario, clock, FakeOptions.DEFAULTS, most -> most);
    }

    /** The fake backend shaped by the options, with chance: the amount grows, and an answer waits, by a number that is drawn. */
    public static FakeBackend start(Scenario scenario, FakeOptions options) throws IOException {
        return start(scenario, Clock.systemUTC(), options, most -> most <= 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextInt(most + 1));
    }

    /** @param dice given a most, a whole number from 0 to it: chance in the program, and what a test says in a test */
    static FakeBackend start(Scenario scenario, Clock clock, FakeOptions options, java.util.function.IntUnaryOperator dice) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        HttpServer server = HttpServer.create(address, 0);
        // Virtual threads are daemon threads, so a held request cannot keep the JVM alive,
        // and a slow or hanging answer does not stop the server answering anything else.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        FakeBackend backend = new FakeBackend(server, scenario, clock, options, dice);
        server.createContext("/", backend::notFound);
        server.createContext(org.example.usage.UsageClient.USAGE_PATH, backend::usage);
        server.createContext("/scenario", backend::switchScenario);
        server.start();

        LOG.log(System.Logger.Level.INFO,
                "Fake backend listening on " + backend.baseUrl() + ", answering " + scenario.optionName());
        return backend;
    }

    /** The base URL to fetch usage from, without the path. */
    public URI baseUrl() {
        InetSocketAddress bound = server.getAddress();
        return URI.create("http://" + bound.getHostString() + ":" + bound.getPort());
    }

    public Scenario scenario() {
        return scenario;
    }

    /** Changes what is answered from the next request on. */
    public void scenario(Scenario wanted) {
        this.scenario = wanted;
        LOG.log(System.Logger.Level.INFO, "The fake backend scenario is now " + wanted.optionName());
    }

    public FakeOptions options() {
        return options;
    }

    /**
     * Takes other options while it runs: the next answer is shaped by them. The amount used goes on from where it is,
     * unless the starting amount is another one.
     */
    public void configure(FakeOptions wanted) {
        this.options = wanted;
        document.configure(wanted);
        LOG.log(System.Logger.Level.INFO, "The fake backend is now " + describe(wanted));
    }

    /** As the log says the options. */
    static String describe(FakeOptions options) {
        return options.account() == FakeOptions.Account.SEAT
                ? "a seat-based account, refusing within " + options.refuseSeconds() + " s, answering within " + options.delayMillis() + " ms"
                : "a usage-based account growing by up to " + options.increaseCents() + " cents, refusing within " + options.refuseSeconds()
                        + " s, answering within " + options.delayMillis() + " ms";
    }

    @Override
    public void close() {
        closed.countDown();
        server.stop(0);
    }

    /**
     * The usage endpoint. The context matches everything beneath the path as well, so the
     * path is checked exactly: a fake endpoint that answered {@code /api/oauth/usage/extra}
     * would be a worse stand-in than one that does not.
     */
    private void usage(HttpExchange exchange) throws IOException {
        if (!org.example.usage.UsageClient.USAGE_PATH.equals(exchange.getRequestURI().getPath())) {
            notFound(exchange);
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "", 0);
            return;
        }
        Scenario answering = scenario;
        switch (answering) {
            case NORMAL -> ordinary(exchange);
            case HTTP_401 -> send(exchange, 401, UsageDocument.error("authentication_error"), 0);
            case HTTP_403 -> send(exchange, 403, UsageDocument.error("permission_error"), 0);
            case HTTP_429 -> send(exchange, 429, UsageDocument.error("rate_limit_error"), 0);
            case HTTP_429_RETRY_AFTER ->
                    send(exchange, 429, UsageDocument.error("rate_limit_error"), RETRY_AFTER_SECONDS);
            case HTTP_500 -> send(exchange, 500, UsageDocument.error("api_error"), 0);
            case NOT_JSON -> send(exchange, 200, "this is not JSON, it is a sentence", 0);
            case EMPTY -> send(exchange, 200, "", 0);
            case NO_SPEND_NO_WINDOWS -> send(exchange, 200, UsageDocument.neitherSpendNorWindows(), 0);
            case TRAILING_TEXT -> send(exchange, 200, document.withTrailingText(clock.instant()), 0);
            case SLOW -> {
                if (hold(SLOW_ANSWER)) {
                    send(exchange, 200, document.next(clock.instant()), 0);
                } else {
                    exchange.close();
                }
            }
            // Nothing is ever sent: the request is held until the client has given up and
            // this server is closed, which is what a server that does not answer looks like.
            case HANG -> {
                hold(HANG_HOLD);
                exchange.close();
            }
        }
    }

    /** Switches the scenario: {@code POST /scenario} with the name as the body. */
    /**
     * The ordinary answer, as the options shape it: held back for a time that is drawn, refused when the request comes too
     * soon after the answered one before it, and otherwise the usage document of the kind of account.
     */
    private void ordinary(HttpExchange exchange) throws IOException {
        FakeOptions now = options;
        int wait = now.delayMillis() <= 0 ? 0 : Math.max(0, Math.min(now.delayMillis(), dice.applyAsInt(now.delayMillis())));
        if (wait > 0 && !hold(Duration.ofMillis(wait))) {
            exchange.close();
            return;
        }
        if (tooSoon(now)) {
            send(exchange, 429, UsageDocument.error("rate_limit_error"), 0);
            return;
        }
        send(exchange, 200, now.account() == FakeOptions.Account.SEAT ? UsageDocument.seatBased() : document.next(clock.instant()), 0);
    }

    /**
     * Whether this request comes too soon after the one before it that was answered. A request that is refused is not
     * one that was answered, so a client that keeps asking too soon is refused until it has waited long enough.
     */
    private synchronized boolean tooSoon(FakeOptions now) {
        long at = clock.millis();
        if (now.refuseSeconds() > 0 && lastAnsweredMillis != 0 && at - lastAnsweredMillis < now.refuseSeconds() * 1000L) {
            return true;
        }
        lastAnsweredMillis = at;
        return false;
    }

    private void switchScenario(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                send(exchange, 405, "POST a scenario name", 0);
                return;
            }
            String name = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            Scenario wanted = Scenario.ofOptionName(name).orElse(null);
            if (wanted == null) {
                send(exchange, 400, "Unknown scenario \"" + name + "\"; one of " + Scenario.names(), 0);
                return;
            }
            scenario(wanted);
            send(exchange, 200, wanted.optionName(), 0);
        }
    }

    private void notFound(HttpExchange exchange) throws IOException {
        send(exchange, 404, "", 0);
    }

    /**
     * Waits, and says whether the wait finished rather than the server being closed under it.
     * Sleeping on the latch rather than on the clock means a closed server does not leave a
     * held request waiting out the rest of its delay.
     */
    private boolean hold(Duration howLong) {
        try {
            return !closed.await(howLong.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status, String body, int retryAfterSeconds) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        if (retryAfterSeconds > 0) {
            exchange.getResponseHeaders().set("Retry-After", Integer.toString(retryAfterSeconds));
        }
        // A request id of the shape the real endpoint sends, so the log line that reports
        // one has something to report.
        exchange.getResponseHeaders().set("request-id", "fake-backend-request");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
