package org.example;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real {@link Main} in a child JVM, set up the way Gradle runs it
 * (JavaFX on the module path). Only the paths that end before any window opens
 * are exercised. They must also end the JVM: JavaFX starts a non-daemon thread
 * of its own, so a {@code main} that merely returns leaves the process running.
 */
class MainCommandLineTest {

    private static final int TIMEOUT_SECONDS = 15;

    private record Result(int exitCode, String output) {
    }

    @Test
    void helpPrintsTheUsageAndTheProcessEnds() throws Exception {
        Result result = run("--help");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Usage: java-aip-usage"), result.output());
        assertTrue(result.output().contains("5-3600"), result.output());
    }

    @Test
    void shortHelpFlagWorksToo() throws Exception {
        assertEquals(0, run("-h").exitCode());
    }

    @Test
    void aBadOptionIsReportedWithTheUsageAndExitCodeTwo() throws Exception {
        Result result = run("--usage-interval", "2");

        assertEquals(2, result.exitCode(), result.output());
        assertTrue(result.output().contains("--usage-interval must be from 5 to 3600 seconds."), result.output());
        assertTrue(result.output().contains("Usage: java-aip-usage"), result.output());
    }

    @Test
    void anUnknownOptionIsRefused() throws Exception {
        Result result = run("--nope");

        assertEquals(2, result.exitCode(), result.output());
        assertTrue(result.output().contains("Unknown option: --nope"), result.output());
    }

    /**
     * What an IDE does: everything on the plain classpath, JavaFX included, with no
     * module path. A main class that is itself a JavaFX {@code Application} cannot
     * start that way; the launcher stops with "JavaFX runtime components are missing".
     */
    @Test
    void startsFromThePlainClasspathAsAnIdeRunsIt() throws Exception {
        Result result = runOnClasspath("--help");

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Usage: java-aip-usage"), result.output());
        assertFalse(result.output().contains("JavaFX runtime components are missing"), result.output());
    }

    @Test
    void aBadOptionIsReportedAlsoWhenStartedFromThePlainClasspath() throws Exception {
        Result result = runOnClasspath("--usage-interval", "2");

        assertEquals(2, result.exitCode(), result.output());
        assertTrue(result.output().contains("--usage-interval must be from 5 to 3600 seconds."), result.output());
    }

    private static Result runOnClasspath(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName()));
        command.addAll(List.of(args));
        return execute(command);
    }

    private static Result run(String... args) throws IOException, InterruptedException {
        // JavaFX must be on the module path for Main, an Application, to start; the
        // test classpath has every JavaFX jar among its entries.
        List<String> modulePath = new ArrayList<>();
        List<String> classPath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            (Path.of(entry).getFileName().toString().startsWith("javafx-") ? modulePath : classPath).add(entry);
        }

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path", String.join(java.io.File.pathSeparator, modulePath),
                "--add-modules", "javafx.controls,javafx.web",
                "--enable-native-access=javafx.graphics,javafx.web",
                "-cp", String.join(java.io.File.pathSeparator, classPath),
                Main.class.getName()));
        command.addAll(List.of(args));
        return execute(command);
    }

    private static Result execute(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        boolean ended = false;
        try {
            ended = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            if (!ended) {
                // Stopped before its output is read: reading a live process would block.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!ended) {
            throw new AssertionError("the process did not end within " + TIMEOUT_SECONDS + " s:\n" + output);
        }
        return new Result(process.exitValue(), output);
    }
}
