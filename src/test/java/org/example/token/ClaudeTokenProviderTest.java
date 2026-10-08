package org.example.token;

import org.example.token.TokenException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeTokenProviderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final List<String> logged = new CopyOnWriteArrayList<>();

    private final List<String> warned = new CopyOnWriteArrayList<>();

    @TempDir
    Path dir;

    @Test
    void capturesABearerToken() {
        assertEquals(FakeClaude.TOKEN, provider(Map.of(), TIMEOUT, "bearer").acquire());
    }

    @Test
    void capturesAnXApiKey() {
        assertEquals(FakeClaude.TOKEN, provider(Map.of(), TIMEOUT, "x-api-key").acquire());
    }

    @Test
    void ignoresRequestsWithoutACredentialAndKeepsWaiting() {
        assertEquals(FakeClaude.TOKEN, provider(Map.of(), TIMEOUT, "preflight-then-bearer").acquire());
    }

    @Test
    void neverLogsTheToken() {
        provider(Map.of(), TIMEOUT, "bearer").acquire();

        assertFalse(logged.isEmpty());
        assertTrue(logged.stream().noneMatch(line -> line.contains(FakeClaude.TOKEN)), logged.toString());
        assertTrue(warned.isEmpty());
    }

    @Test
    void reportsNotLoggedInWhenTheCliSendsNoCredential() {
        TokenException e = assertThrows(
                TokenException.class, () -> provider(Map.of(), TIMEOUT, "no-credential").acquire());

        assertEquals(Reason.NOT_LOGGED_IN, e.reason());
        assertTrue(e.getMessage().contains("/login"), e.getMessage());
        assertTrue(String.join("\n", warned).contains("Not logged in"), warned.toString());
    }

    @Test
    void reportsNotLoggedInWhenTheCliExitsWithoutAnyRequest() {
        TokenException e = assertThrows(
                TokenException.class, () -> provider(Map.of(), TIMEOUT, "exit-silently").acquire());

        assertEquals(Reason.NOT_LOGGED_IN, e.reason());
    }

    @Test
    void canBeRetriedAfterTheUserLogsIn() throws IOException {
        Path loginMarker = dir.resolve("logged-in");
        ClaudeTokenProvider provider = provider(Map.of(), TIMEOUT, "bearer-if-file-exists", loginMarker.toString());

        assertEquals(Reason.NOT_LOGGED_IN, assertThrows(TokenException.class, provider::acquire).reason());

        Files.createFile(loginMarker);
        assertEquals(FakeClaude.TOKEN, provider.acquire());
    }

    @Test
    void reportsNotInstalledWhenTheCliCannotBeStarted() {
        ClaudeTokenProvider provider = new ClaudeTokenProvider(
                Map.of(), List.of("no-such-claude-executable"), TIMEOUT, logged::add, warned::add);

        TokenException e = assertThrows(TokenException.class, provider::acquire);

        assertEquals(Reason.NOT_INSTALLED, e.reason());
        assertTrue(e.getMessage().startsWith("Claude Code could not be found on the PATH."), e.getMessage());
        // The operating system's own wording is noise to a user.
        assertFalse(e.getMessage().contains("error="), e.getMessage());
        assertFalse(e.getMessage().contains("Exec failed"), e.getMessage());
    }

    @Test
    void sayingNotInstalledAlsoExplainsWhenARestartIsNeeded() {
        ClaudeTokenProvider provider = new ClaudeTokenProvider(
                Map.of(), List.of("no-such-claude-executable"), TIMEOUT, logged::add, warned::add);

        String message = assertThrows(TokenException.class, provider::acquire).getMessage();

        assertTrue(message.contains("retry"), message);
        assertTrue(message.contains("restart this application"), message);
    }

    @Test
    void aCommandThatIsFoundButCannotBeRunReportsTheRealReason() throws IOException {
        // A file with no execute permission: present, but the system refuses to run it.
        Path notExecutable = Files.createFile(dir.resolve("claude"));
        ClaudeTokenProvider provider = new ClaudeTokenProvider(
                Map.of(), List.of(notExecutable.toString()), TIMEOUT, logged::add, warned::add);

        TokenException e = assertThrows(TokenException.class, provider::acquire);

        assertEquals(Reason.NOT_INSTALLED, e.reason());
        assertTrue(e.getMessage().startsWith("Claude Code was found but could not be started:"), e.getMessage());
        assertTrue(e.getMessage().contains("Permission denied"), e.getMessage());
        assertFalse(e.getMessage().contains("not be found on the PATH"), e.getMessage());
    }

    @Test
    void timesOutAndKillsTheCli() throws Exception {
        Path pidFile = dir.resolve("pid");

        TokenException e = assertThrows(
                TokenException.class,
                () -> provider(Map.of(), Duration.ofSeconds(3), "hang", pidFile.toString()).acquire());

        assertEquals(Reason.TIMEOUT, e.reason());
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "CLI still running");
    }

    @Test
    void stopsTheCaptureServerOnceDone() throws Exception {
        provider(Map.of(), TIMEOUT, "bearer").acquire();

        Matcher listening = Pattern.compile("127\\.0\\.0\\.1:(\\d+)").matcher(String.join("\n", logged));
        assertTrue(listening.find(), logged.toString());
        int port = Integer.parseInt(listening.group(1));
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
    }

    @Test
    void stopsTheCaptureServerAfterAFailureToo() {
        assertThrows(TokenException.class, () -> provider(Map.of(), TIMEOUT, "no-credential").acquire());

        Matcher listening = Pattern.compile("127\\.0\\.0\\.1:(\\d+)").matcher(String.join("\n", logged));
        assertTrue(listening.find(), logged.toString());
        int port = Integer.parseInt(listening.group(1));
        assertThrows(IOException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
    }

    @Test
    void refusesToRunWhileAnEnvironmentCredentialIsSet() {
        // A command that cannot start proves the CLI was never launched.
        ClaudeTokenProvider provider = new ClaudeTokenProvider(
                Map.of("ANTHROPIC_API_KEY", "sk-ant-api03-ENV-SECRET", "ANTHROPIC_AUTH_TOKEN", "other-secret"),
                List.of("no-such-claude-executable"), TIMEOUT, logged::add, warned::add);

        TokenException e = assertThrows(TokenException.class, provider::acquire);

        assertEquals(Reason.ENV_CONFLICT, e.reason());
        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"), e.getMessage());
        assertTrue(e.getMessage().contains("ANTHROPIC_AUTH_TOKEN"), e.getMessage());
        assertFalse(e.getMessage().contains("ENV-SECRET"), e.getMessage());
        assertFalse(e.getMessage().contains("other-secret"), e.getMessage());
    }

    @Test
    void aBlankEnvironmentCredentialCountsAsUnset() {
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "  ", "ANTHROPIC_AUTH_TOKEN", "");

        assertEquals(FakeClaude.TOKEN, provider(env, TIMEOUT, "bearer").acquire());
    }

    @Test
    void redactsCredentialsFromLoggedCliOutput() {
        assertThrows(TokenException.class, () -> provider(Map.of(), TIMEOUT, "leak-output").acquire());

        String output = String.join("\n", warned);
        assertTrue(output.contains("auth failed"), output);
        assertFalse(output.contains("LEAKED-SECRET"), output);
        assertFalse(output.contains("LEAKED-BEARER"), output);
        assertTrue(output.contains("[redacted]"), output);
    }

    /** A provider whose "claude" is {@link FakeClaude} run in this test's JVM. */
    private ClaudeTokenProvider provider(Map<String, String> env, Duration timeout, String... fakeArguments) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                FakeClaude.class.getName()));
        command.addAll(List.of(fakeArguments));
        return new ClaudeTokenProvider(env, command, timeout, logged::add, warned::add);
    }
}
