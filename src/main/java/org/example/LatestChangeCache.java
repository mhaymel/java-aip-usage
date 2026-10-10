package org.example;

import org.example.usage.HistoryDeltas;
import org.example.usage.HistoryReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

/**
 * The change since the previous row for the newest reading in the history, which the status carries. The
 * status is polled every second, and the history grows for good, so the file is read again only when its
 * size or time has changed.
 */
final class LatestChangeCache {

    private Path file;

    private long size = -1;

    private FileTime modified;

    private ApiHandler.DeltaBody change;

    synchronized ApiHandler.DeltaBody get(Path history) throws IOException {
        if (!Files.isRegularFile(history)) {
            file = null;
            return null;
        }
        long nowSize = Files.size(history);
        FileTime nowModified = Files.getLastModifiedTime(history);
        if (history.equals(file) && nowSize == size && nowModified.equals(modified)) {
            return change;
        }
        HistoryReader.Table table = HistoryReader.read(history, Integer.MAX_VALUE);
        List<List<String>> rows = table.rows();
        // The newest reading is the newest row that has amounts, as the window shows it.
        Optional<Integer> newest = java.util.stream.IntStream.range(0, rows.size())
                .boxed()
                .filter(i -> !rows.get(i).get(1).isBlank() || !rows.get(i).get(2).isBlank())
                .findFirst();
        HistoryDeltas.Delta delta = newest.map(table.deltas()::get).orElse(null);
        change = delta == null ? null
                : ApiHandler.DeltaBody.of(delta.used() == null ? null : delta.used().doubleValue(), delta.seconds());
        file = history;
        size = nowSize;
        modified = nowModified;
        return change;
    }
}
