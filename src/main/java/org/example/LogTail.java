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

/**
 * The end of the log file, for showing in a window.
 *
 * <p>The log is appended to forever and never rotated, so it can be large. Only its tail is read:
 * at most the last {@code maxBytes} bytes, and of those at most the last {@code maxLines} lines.
 */
final class LogTail {

    /**
     * @param exists whether there is a log file at all
     * @param truncated whether earlier lines were left out
     * @param lines the last lines, oldest first
     */
    record Tail(boolean exists, boolean truncated, List<String> lines) {
    }

    private LogTail() {
    }

    static Tail read(Path file, int maxLines, int maxBytes) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Tail(false, false, List.of());
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
        boolean droppedLines = lines.size() > maxLines;
        if (droppedLines) {
            lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
        }
        return new Tail(true, cutAtTheStart || droppedLines, List.copyOf(lines));
    }
}
