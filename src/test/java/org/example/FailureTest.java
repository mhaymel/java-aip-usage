package org.example;

import org.example.settings.SettingsException;
import org.example.usage.UsageFetchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What went wrong is said in a fixed set of texts of the program's own, whatever the system and its language say.
 * The failures here are real ones where one can be made, so that what is tested is what the system really reports.
 */
class FailureTest {

    @TempDir
    Path dir;

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    // ---- files

    @Test
    void aFileThatIsNotThereIsSaidSoForReadingAndForWriting() {
        IOException reading = assertThrows(IOException.class, () -> Files.readString(dir.resolve("nothing.json")));
        IOException writing = assertThrows(IOException.class, () -> Files.writeString(dir.resolve("no-folder").resolve("file.txt"), "x"));
        IOException old = assertThrows(IOException.class, () -> new java.io.FileInputStream(dir.resolve("nothing.json").toFile()).close());

        assertEquals("the file or its folder is not there", Failure.of(reading));
        assertEquals("the file or its folder is not there", Failure.of(writing));
        assertEquals("the file or its folder is not there", Failure.of(old));
    }

    @Test
    void aFileThatMayNotBeTouchedAndAnyOtherTroubleWithAFileHaveTheirTexts() {
        assertEquals("access is denied", Failure.of(new java.nio.file.AccessDeniedException("C:\\secret\\file.txt")));
        assertEquals("the file could not be read or written", Failure.of(new java.nio.file.FileSystemException("file.txt", null, "Der Prozess kann nicht auf die Datei zugreifen")));
        assertEquals("input or output failed", Failure.of(new IOException("Stream closed")));
    }

    // ---- the network

    @Test
    void aConnectionThatIsRefusedIsSaidSo() throws Exception {
        int port;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = taken.getLocalPort();
        }

        IOException refused = assertThrows(IOException.class, () -> get("http://127.0.0.1:" + port + "/"));

        assertEquals("the connection was refused", Failure.ofConnection(refused));
    }

    @Test
    void aHostThatIsNotKnownIsSaidSo() {
        // The top-level name "invalid" is reserved for names that never resolve.
        IOException unknown = assertThrows(IOException.class, () -> get("http://no-such-host.invalid/"));

        assertEquals("the host is not known", Failure.ofConnection(unknown));
        assertEquals("the host is not known", Failure.of(new java.net.UnknownHostException("no-such-host.invalid")));
    }

    @Test
    void aConnectionThatEndsBeforeAnAnswerIsAFailureOfTheConnectionInTheProgramsWords() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread closer = Thread.ofPlatform().daemon().start(() -> {
                try {
                    server.accept().close();
                } catch (IOException ignored) {
                    // The test is over.
                }
            });

            IOException cut = assertThrows(IOException.class, () -> get("http://127.0.0.1:" + server.getLocalPort() + "/"));
            closer.join(2000);

            // Which of them it is depends on how the system reports the end; it is one of the program's texts in any case.
            assertTrue(Set.of("the connection was closed", "the connection failed", "there was no answer in time").contains(Failure.ofConnection(cut)),
                    Failure.ofConnection(cut));
        }
    }

    @Test
    void theOtherFailuresOfAConnectionHaveTheirTexts() {
        assertEquals("the connection was closed", Failure.of(new java.net.SocketException("Connection reset")));
        assertEquals("the connection was closed", Failure.of(new java.io.EOFException("EOF reached while reading")));
        assertEquals("there was no answer in time", Failure.of(new java.net.http.HttpTimeoutException("request timed out")));
        assertEquals("there was no answer in time", Failure.of(new java.net.SocketTimeoutException("Read timed out")));
        assertEquals("the secure connection could not be made", Failure.of(new javax.net.ssl.SSLHandshakeException("PKIX path building failed")));
        assertEquals("the connection failed", Failure.ofConnection(new IOException("HTTP/1.1 header parser received no bytes")));
        assertEquals("the secure connection could not be made",
                Failure.ofConnection(new IOException("wrapped", new javax.net.ssl.SSLException("handshake"))), "the reason inside is found");
    }

    @Test
    void anAddressThatIsTakenIsSaidSo() throws Exception {
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            IOException taken = assertThrows(IOException.class,
                    () -> new ServerSocket(first.getLocalPort(), 1, InetAddress.getLoopbackAddress()).close());

            assertEquals("the address is in use or cannot be used", Failure.of(taken));
        }
    }

    // ---- starting a program

    @Test
    void aProgramThatIsNotThereOrIsNoProgramIsSaidSoWithNoneOfTheSystemsText() throws Exception {
        IOException missing = assertThrows(IOException.class, () -> new ProcessBuilder(dir.resolve("no-such-program").toString()).start());
        Path noProgram = Files.writeString(dir.resolve("claude"), "not a program");
        IOException refused = assertThrows(IOException.class, () -> new ProcessBuilder(noProgram.toString()).start());

        assertEquals("the file or its folder is not there", Failure.ofStart(missing));
        assertEquals(WINDOWS ? "it is not a program this system can run" : "access is denied", Failure.ofStart(refused));
    }

    @Test
    void theReasonAProgramDidNotStartIsReadFromTheNumberTheSystemGives() {
        assertEquals("the system did not start it", Failure.ofStart(new IOException("Cannot run program \"x\"")));
        assertEquals("the system did not start it", Failure.ofStart(new IOException("Cannot run program \"x\": error=9999, etwas anderes")));
        assertEquals("the system did not start it", Failure.ofStart(new IOException((String) null)));
        assertEquals(WINDOWS ? "access is denied" : "the system did not start it", Failure.ofStart(new IOException("CreateProcess error=5, Zugriff verweigert")));
        assertEquals(WINDOWS ? "the system did not start it" : "access is denied", Failure.ofStart(new IOException("error=13, Permission denied")));
        assertEquals("the file manager could not be started", Failure.ofStart(new Fault("the file manager could not be started")), "the program's own words stay");
    }

    // ---- the program's own, and what nobody expected

    @Test
    void aFailureTheProgramNamesItselfIsSaidInItsOwnWords() {
        assertEquals("the settings file is not a JSON object", Failure.of(new Fault("the settings file is not a JSON object")));
        assertEquals("Anthropic returned HTTP 500.", Failure.of(new UsageFetchException("Anthropic returned HTTP 500.", 500)));
        assertEquals("The settings could not be saved: access is denied",
                Failure.of(new SettingsException("The settings could not be saved: access is denied", new IOException("Zugriff verweigert"))));
    }

    @Test
    void whatNobodyExpectedIsSaidWithNoneOfItsDetail() {
        assertEquals("an unexpected fault", Failure.of(new IllegalStateException("internal detail")));
        assertEquals("an unexpected fault", Failure.of(new NullPointerException()));
        assertEquals("an unexpected fault", Failure.of(null));
        assertEquals("interrupted", Failure.of(new InterruptedException()));
    }

    // ---- that nothing goes round it

    @Test
    void noMessageOfTheProgramIsBuiltFromTheTextOfAnExceptionOrTheNameOfItsClass() throws IOException {
        // The rule is about what is absent, so it is checked on the sources: only Failure reads the text of an exception,
        // and only the log reads a stack trace and the name of a class.
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = source.getFileName().toString();
                String text = Files.readString(source);
                if (!name.equals("Failure.java")) {
                    assertFalse(text.contains(".getMessage()"), name + " reads the text of an exception");
                    assertFalse(text.contains(".getLocalizedMessage()"), name + " reads the text of an exception");
                }
                if (!name.equals("Log.java")) {
                    assertFalse(text.contains("printStackTrace"), name + " prints a stack trace");
                    assertFalse(text.contains(".getSimpleName()"), name + " uses the name of a class");
                    assertFalse(text.contains(".getClass().getName()") && !name.equals("Failure.java"), name + " uses the name of a class");
                }
            }
        }
    }

    private static HttpResponse<String> get(String uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theTextsAreTheFixedSet() {
        assertEquals(List.of(
                        "the file or its folder is not there", "access is denied", "the file could not be read or written", "the host is not known",
                        "the connection was refused", "the connection was closed", "the secure connection could not be made", "there was no answer in time",
                        "the address is in use or cannot be used", "the connection failed", "input or output failed", "interrupted", "an unexpected fault",
                        "it is not a program this system can run", "the system did not start it"),
                List.of(Failure.FILE_MISSING, Failure.ACCESS_DENIED, Failure.FILE_FAILED, Failure.HOST_UNKNOWN, Failure.REFUSED, Failure.CLOSED,
                        Failure.NOT_SECURE, Failure.TOO_LONG, Failure.ADDRESS, Failure.CONNECTION_FAILED, Failure.IO_FAILED, Failure.INTERRUPTED,
                        Failure.UNEXPECTED, Failure.NOT_A_PROGRAM, Failure.NOT_STARTED));
    }
}
