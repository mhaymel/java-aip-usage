package org.example.token;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for the {@code claude} CLI, run as a real subprocess. It does what
 * the real one does that matters here: reads {@code ANTHROPIC_BASE_URL} and
 * makes an HTTP request there, with or without a credential.
 *
 * <p>Arguments: {@code mode [argument]}.
 */
public final class FakeClaude {

    public static final String TOKEN = "sk-ant-oat01-test-token-value";

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String argument = args.length > 1 ? args[1] : null;
        String base = System.getenv("ANTHROPIC_BASE_URL");

        switch (mode) {
            // Sends the token as a bearer token, as an OAuth login does.
            case "bearer" -> post(base, "Authorization", "Bearer " + TOKEN, "{\"stream\":true}");
            case "x-api-key" -> post(base, "x-api-key", TOKEN, "{}");
            // A request with no credential first, as a preflight would be, then the real one.
            case "preflight-then-bearer" -> {
                post(base, null, null, "{}");
                post(base, "Authorization", "Bearer " + TOKEN, "{}");
            }
            // Sends the token only once the file named by the argument exists: "logged in".
            case "bearer-if-file-exists" -> {
                if (Files.exists(Path.of(argument))) {
                    post(base, "Authorization", "Bearer " + TOKEN, "{}");
                } else {
                    System.out.println("Not logged in. Please run /login");
                    System.exit(1);
                }
            }
            // A request without a credential, then exit: logged out.
            case "no-credential" -> {
                post(base, null, null, "{}");
                System.out.println("Not logged in. Please run /login");
                System.exit(1);
            }
            case "exit-silently" -> System.exit(1);
            // Prints credential-shaped text, as a careless CLI might.
            case "leak-output" -> {
                System.out.println("auth failed for sk-ant-oat01-LEAKED-SECRET");
                System.out.println("Authorization: Bearer LEAKED-BEARER");
                System.exit(1);
            }
            // Never answers, and records its pid so the test can check it was killed.
            case "hang" -> {
                Files.writeString(Path.of(argument), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(120_000);
            }
            default -> throw new IllegalArgumentException(mode);
        }
    }

    private static void post(String base, String header, String value, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "v1/messages"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (header != null) {
            request.header(header, value);
        }
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.out.println("unexpected status " + response.statusCode());
            System.exit(2);
        }
    }

    private FakeClaude() {
    }
}
