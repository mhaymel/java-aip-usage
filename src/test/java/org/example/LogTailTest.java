package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailTest {

    @TempDir
    Path dir;

    private Path write(String content) throws IOException {
        Path file = dir.resolve("app.log");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    void aMissingLogIsNotAnError() throws IOException {
        LogTail.Tail tail = LogTail.read(dir.resolve("none.log"), 100, 10_000);

        assertFalse(tail.exists());
        assertFalse(tail.truncated());
        assertEquals(List.of(), tail.lines());
    }

    @Test
    void anEmptyLogHasNoLines() throws IOException {
        LogTail.Tail tail = LogTail.read(write(""), 100, 10_000);

        assertTrue(tail.exists());
        assertEquals(List.of(), tail.lines());
        assertFalse(tail.truncated());
    }

    @Test
    void aSmallLogIsGivenWholeInOrder() throws IOException {
        LogTail.Tail tail = LogTail.read(write(lines(3)), 100, 10_000);

        assertEquals(List.of("line 1", "line 2", "line 3"), tail.lines());
        assertFalse(tail.truncated());
    }

    @Test
    void aTrailingNewlineDoesNotMakeAnEmptyLastLine() throws IOException {
        assertEquals(List.of("a", "b"), LogTail.read(write("a\nb\n"), 100, 10_000).lines());
        assertEquals(List.of("a", "b"), LogTail.read(write("a\nb"), 100, 10_000).lines());
    }

    @Test
    void moreLinesThanAskedForGivesTheLastOnesAndSaysSo() throws IOException {
        LogTail.Tail tail = LogTail.read(write(lines(20)), 5, 100_000);

        assertEquals(List.of("line 16", "line 17", "line 18", "line 19", "line 20"), tail.lines());
        assertTrue(tail.truncated());
    }

    @Test
    void exactlyAsManyLinesAsAskedForIsNotTruncated() throws IOException {
        LogTail.Tail tail = LogTail.read(write(lines(5)), 5, 100_000);

        assertEquals(5, tail.lines().size());
        assertFalse(tail.truncated());
    }

    @Test
    void aLogLargerThanTheByteLimitIsReadFromTheEndOnly() throws IOException {
        LogTail.Tail tail = LogTail.read(write(lines(2000)), 1000, 200);

        assertTrue(tail.truncated());
        assertEquals("line 2000", tail.lines().get(tail.lines().size() - 1), "the end is intact");
        assertTrue(tail.lines().size() < 40, "only about 200 bytes were read: " + tail.lines().size());
    }

    @Test
    void theLineTheByteLimitCutInTwoIsLeftOutNotShownHalf() throws IOException {
        LogTail.Tail tail = LogTail.read(write(lines(2000)), 1000, 200);

        // Every line shown is a whole one: "line " and a number, never a fragment of either.
        for (String line : tail.lines()) {
            assertTrue(line.matches("line \\d+"), "a whole line: " + line);
        }
        assertTrue(Integer.parseInt(tail.lines().get(0).substring(5)) > 1900);
    }

    @Test
    void aCharacterCutInTwoByTheByteLimitLeavesNoGarbage() throws IOException {
        // Every character here takes two bytes, so a cut lands inside one half the time.
        String content = "éééééééé\n".repeat(200);
        for (int limit = 100; limit < 110; limit++) {
            LogTail.Tail tail = LogTail.read(write(content), 1000, limit);

            for (String line : tail.lines()) {
                assertFalse(line.contains("�"), "no replacement character at limit " + limit + ": " + line);
                assertEquals("éééééééé", line);
            }
        }
    }

    @Test
    void aStackTraceKeepsItsLines() throws IOException {
        LogTail.Tail tail = LogTail.read(write("ERROR boom\njava.lang.IllegalStateException: x\n\tat a.B.c(B.java:1)\n\tat d.E.f(E.java:2)\n"), 100, 10_000);

        assertEquals(4, tail.lines().size());
        assertEquals("\tat a.B.c(B.java:1)", tail.lines().get(2));
    }
}
