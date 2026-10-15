package org.example.token;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeVersionTest {

    private static List<String> fake(String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                FakeClaude.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    @Test
    void theFirstLineItPrintsIsTheVersion() {
        assertEquals("Claude Code version: 2.1.3 (Claude Code)", ClaudeVersion.line(fake("version"), Duration.ofSeconds(30)));
    }

    @Test
    void aCommandThatFailsSaysSo() {
        assertEquals("Claude Code version could not be read: it ended with exit code 1", ClaudeVersion.line(fake("exit-silently"), Duration.ofSeconds(30)));
        String missing = ClaudeVersion.line(List.of("no-such-claude-command-anywhere"), Duration.ofSeconds(5));
        assertTrue(missing.startsWith("Claude Code version could not be read: "), missing);
    }

    @Test
    void oneThatDoesNotAnswerInTimeIsGivenUp(@TempDir Path dir) {
        long started = System.nanoTime();
        String line = ClaudeVersion.line(fake("hang", dir.resolve("pid").toString()), Duration.ofSeconds(2));
        assertEquals("Claude Code version could not be read: it did not answer within 2 s", line);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20, "and the wait ends");
    }

    @Test
    void theCommandIsClaudeWithTwoDashes() {
        assertEquals(List.of("claude", "--version"), ClaudeVersion.COMMAND, "the CLI takes --version, not -version");
        assertEquals(Duration.ofSeconds(10), ClaudeVersion.LIMIT);
    }
}
