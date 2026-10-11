package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.example.settings.LogLevel;

/**
 * The end of the log file, for showing in a window.
 *
 * <p>The log is appended to forever and never rotated, so it can be large. Only its tail is read:
 * at most the last {@code maxBytes} bytes, and of those at most the last {@code maxLines} lines of the level asked for.
 *
 * <p>A line that begins with a date and a time begins an entry, and has the level of the entry after them; the lines
 * that follow it, a stack trace or a response's JSON, belong to it and are shown or left out with it.
 */
final class LogTail {

    /**
     * @param exists whether there is a log file at all
     * @param truncated whether earlier lines were left out
     * @param lines the last lines of the level asked for, oldest first
     * @param hidden whether the file has lines and none of them is of the level asked for
     */
    record Tail(boolean exists, boolean truncated, List<String> lines, boolean hidden) {
    }

    /** A line that begins an entry: its date, its time, and the word for its level. */
    private static final Pattern ENTRY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} (\\S*)");

    private LogTail() {
    }

    /** Every line, whatever its level. */
    static Tail read(Path file, int maxLines, int maxBytes) throws IOException {
        return read(file, maxLines, maxBytes, LogLevel.DEBUG);
    }

    /**
     * @param level the least severe level to give; the lines of the others are left out before the newest
     *     {@code maxLines} are taken, so those are {@code maxLines} lines of this level
     */
    static Tail read(Path file, int maxLines, int maxBytes, LogLevel level) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Tail(false, false, List.of(), false);
        }
        String text;
        boolean cutAtTheStart;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, size - maxBytes);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            long position = start;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position);
                if (read < 0) {
                    break;
                }
                position += read;
            }
            text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            cutAtTheStart = start > 0;
        }

        List<String> lines = new ArrayList<>(text.lines().toList());
        if (cutAtTheStart && !lines.isEmpty()) {
            // The read began somewhere inside a line, perhaps inside a character, so that line is no good.
            lines.remove(0);
        }
        boolean any = !lines.isEmpty();
        lines = ofLevel(lines, level);
        boolean droppedLines = lines.size() > maxLines;
        if (droppedLines) {
            lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
        }
        return new Tail(true, cutAtTheStart || droppedLines, List.copyOf(lines), any && lines.isEmpty());
    }

    /**
     * The lines of the entries the level lets through. The lines before the first entry, the end of an entry whose
     * beginning was not read, have no level that is known: they are informative, shown when information is.
     */
    private static List<String> ofLevel(List<String> lines, LogLevel level) {
        List<String> kept = new ArrayList<>();
        boolean keeping = level.shows(LogLevel.INFO);
        for (String line : lines) {
            Matcher entry = ENTRY.matcher(line);
            if (entry.find()) {
                keeping = level.shows(LogLevel.ofLogWord(entry.group(1)));
            }
            if (keeping) {
                kept.add(line);
            }
        }
        return kept;
    }
}
