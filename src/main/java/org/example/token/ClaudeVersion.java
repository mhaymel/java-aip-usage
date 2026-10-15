package org.example.token;

import org.example.Failure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The version of Claude Code, as {@code claude --version} prints it (since 0.90), for the log at the start. It is found as the
 * token provider finds {@code claude}: on Windows by the endings of {@code PATHEXT}, so that {@code claude.cmd} is the one run.
 */
public final class ClaudeVersion {

    /** The command the program runs. The request named {@code claude -version}, which the CLI does not take. */
    public static final List<String> COMMAND = List.of("claude", "--version");

    /** How long it may take before it is given up. */
    public static final Duration LIMIT = Duration.ofSeconds(10);

    private ClaudeVersion() {
    }

    /**
     * Runs the command and says, as the log says it, what it printed or why it could not be read: {@code Claude Code version:
     * 2.1.3 (Claude Code)}, or {@code Claude Code version could not be read: <reason>}. Never fails.
     */
    public static String line(List<String> command, Duration limit) {
        List<String> run = ClaudeTokenProvider.isWindows()
                ? ClaudeTokenProvider.windowsCommand(command, System.getenv("PATH"), System.getenv("PATHEXT"))
                : command;
        Process process;
        try {
            process = new ProcessBuilder(run).redirectErrorStream(true).start();
        } catch (IOException e) {
            return unread(Failure.ofStart(e));
        }
        try {
            // Nothing to send; an open stdin could make the CLI wait for input.
            process.getOutputStream().close();
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessTree.kill(process.pid());
                process.destroyForcibly();
                return unread("it did not answer within " + limit.toSeconds() + " s");
            }
            String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String first = printed.lines().map(String::strip).filter(text -> !text.isEmpty()).findFirst().orElse("");
            if (process.exitValue() != 0) {
                return unread("it ended with exit code " + process.exitValue() + (first.isEmpty() ? "" : ": " + first));
            }
            return first.isEmpty() ? unread("it printed nothing") : "Claude Code version: " + first;
        } catch (IOException e) {
            return unread(Failure.of(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return unread(Failure.INTERRUPTED);
        }
    }

    private static String unread(String reason) {
        return "Claude Code version could not be read: " + reason;
    }
}
