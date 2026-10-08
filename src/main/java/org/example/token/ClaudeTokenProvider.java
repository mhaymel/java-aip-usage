package org.example.token;

import org.example.Redaction;
import org.example.token.TokenException.Reason;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Gets the Claude Code OAuth token by running the {@code claude} CLI against a
 * local {@link CredentialCapture} server and reading the credential out of the
 * request it makes. Claude Code must be installed and logged in.
 *
 * <p>Adapted from {@code java-aip}; there is no dependency on it. The token is
 * never logged. The subprocess output is, redacted, because it is the only
 * clue to why a login failed.
 */
public final class ClaudeTokenProvider implements TokenProvider {

    static final String API_KEY = "ANTHROPIC_API_KEY";

    static final String AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN";

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final List<String> DEFAULT_COMMAND = List.of("claude", "-p", "ping");

    /** How much subprocess output is kept for the log. */
    private static final int MAX_OUTPUT_CHARS = 2000;

    private static final Duration OUTPUT_GRACE = Duration.ofSeconds(2);

    private static final Duration KILL_GRACE = Duration.ofSeconds(2);

    private final Map<String, String> env;

    private final List<String> command;

    private final Duration timeout;

    private final Consumer<String> log;

    private final Consumer<String> warn;

    /**
     * @param env the environment to check for conflicting credentials, a
     *     parameter so the rule is testable
     * @param command the CLI invocation, a parameter so tests can substitute a
     *     stand-in
     * @param log receives progress messages
     * @param warn receives the redacted subprocess output after a failure
     */
    public ClaudeTokenProvider(
            Map<String, String> env, List<String> command, Duration timeout,
            Consumer<String> log, Consumer<String> warn) {
        this.env = env;
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.log = log;
        this.warn = warn;
    }

    /** The provider the application uses: the real environment and the real CLI. */
    public static ClaudeTokenProvider create() {
        System.Logger logger = System.getLogger(ClaudeTokenProvider.class.getName());
        return new ClaudeTokenProvider(
                System.getenv(), DEFAULT_COMMAND, DEFAULT_TIMEOUT,
                message -> logger.log(System.Logger.Level.INFO, message),
                message -> logger.log(System.Logger.Level.WARNING, message));
    }

    @Override
    public String acquire() {
        rejectConflictingEnvironment();

        try (CredentialCapture capture = CredentialCapture.start()) {
            log.accept("Listening on 127.0.0.1:" + capture.port() + " to capture the token");
            Process process = launch(capture.port());
            try {
                Output output = Output.collect(process);
                CompletableFuture<String> credential = capture.credential();
                process.onExit().thenRun(() -> credential.completeExceptionally(new ExitedWithoutCredential()));
                return await(credential, process, output);
            } finally {
                terminate(process);
            }
        } catch (IOException e) {
            throw new TokenException(
                    Reason.CAPTURE_FAILED,
                    "Cannot start the local server used to read the Claude Code token: " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@code claude} prefers an environment credential over its stored OAuth
     * one, so capturing while one is set would yield an API key and call it
     * the OAuth token. Only the variable names are reported, never the values.
     */
    private void rejectConflictingEnvironment() {
        List<String> present = new ArrayList<>(2);
        for (String name : List.of(API_KEY, AUTH_TOKEN)) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                present.add(name);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        boolean one = present.size() == 1;
        throw new TokenException(
                Reason.ENV_CONFLICT,
                String.join(" and ", present) + (one ? " is" : " are")
                        + " set, so Claude Code would send that credential instead of its OAuth login token."
                        + " Unset " + (one ? "it" : "them") + " and restart this application.");
    }

    private Process launch(int port) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("ANTHROPIC_BASE_URL", "http://127.0.0.1:" + port + "/");
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw notStarted(e);
        }
        try {
            // Nothing to send; an open stdin could make the CLI wait for input.
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process is already gone or going; the wait below will report it.
        }
        log.accept("Started " + String.join(" ", command));
        return process;
    }

    /**
     * Says what is wrong when the CLI cannot be started. A command that is on no
     * PATH directory is simply missing; one that is found but will not start has
     * a reason of its own (permissions, say) that the user needs to see.
     */
    private TokenException notStarted(IOException cause) {
        String name = command.get(0);
        if (isBareName(name) && !onPath(name)) {
            return new TokenException(
                    Reason.NOT_INSTALLED,
                    "Claude Code could not be found on the PATH. Install it, then retry."
                            + " If it is installed somewhere the PATH does not cover, add that directory and"
                            + " restart this application, which keeps the PATH it was started with.",
                    cause);
        }
        return new TokenException(
                Reason.NOT_INSTALLED,
                "Claude Code was found but could not be started: " + cause.getMessage().strip()
                        + ". Check that it is installed correctly and can be run, then retry.",
                cause);
    }

    /** A command with no directory part, which the operating system looks up on the PATH. */
    private static boolean isBareName(String name) {
        return !name.contains("/") && !name.contains(File.separator);
    }

    private static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isBlank() && Files.isExecutable(Path.of(directory, name))
                    && Files.isRegularFile(Path.of(directory, name))) {
                return true;
            }
        }
        return false;
    }

    private String await(CompletableFuture<String> credential, Process process, Output output) {
        try {
            String token = credential.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            log.accept("Token captured");
            return token;
        } catch (TimeoutException e) {
            reportOutput(output, false);
            throw new TokenException(
                    Reason.TIMEOUT,
                    "Claude Code did not send a credential within " + timeout.toSeconds() + " seconds."
                            + " Run `" + command.get(0) + "` in a terminal to check that it works and is logged in,"
                            + " then retry.");
        } catch (ExecutionException e) {
            reportOutput(output, true);
            throw new TokenException(
                    Reason.NOT_LOGGED_IN,
                    "Claude Code exited without sending a credential, so it is probably not logged in."
                            + " Run `" + command.get(0) + "` in a terminal and log in with /login, then retry.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException(Reason.INTERRUPTED, "Interrupted while reading the Claude Code token.", e);
        }
    }

    private void reportOutput(Output output, boolean processExited) {
        String text = output.text(processExited ? OUTPUT_GRACE : Duration.ZERO);
        if (!text.isBlank()) {
            warn.accept(command.get(0) + " output:" + System.lineSeparator() + Redaction.redact(text));
        }
    }

    /**
     * Kills the process and anything it started ({@code claude} is a wrapper
     * around further processes), then waits briefly so it is gone on return.
     */
    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Marks that the CLI exited before any credential arrived. */
    private static final class ExitedWithoutCredential extends Exception {

        ExitedWithoutCredential() {
            super("exited without sending a credential", null, false, false);
        }
    }

    /**
     * The subprocess's combined output. It is always drained, so a chatty CLI
     * cannot block on a full pipe, but only the start is kept.
     */
    private static final class Output {

        private final StringBuilder kept = new StringBuilder();

        private final Thread reader;

        private Output(Process process) {
            reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[512];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        synchronized (kept) {
                            int room = MAX_OUTPUT_CHARS - kept.length();
                            if (room > 0) {
                                kept.append(buffer, 0, Math.min(read, room));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // The process was killed; whatever arrived is enough.
                }
            });
        }

        static Output collect(Process process) {
            return new Output(process);
        }

        /** Waits up to {@code wait} for the stream to end, then returns what was read. */
        String text(Duration wait) {
            try {
                reader.join(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (kept) {
                return kept.toString();
            }
        }
    }
}
