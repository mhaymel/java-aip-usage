package org.example.usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the usage history back, newest first, for showing in a table.
 *
 * <p>Rows are sorted by their {@code datetime} column, latest first. They are not just
 * reversed: the file is appended to in the order readings arrive, which is usually but not always
 * time order, for instance after the clock was set back. The time is written
 * {@code yyyy-MM-dd HH:mm:ss}, which sorts correctly as text. A line that does not have the
 * columns (or the first three, four or five, as in a file from before the currency, the startup mark or the
 * timing was a column) is skipped, so a damaged line cannot hide the rest.
 */
public final class HistoryReader {

    /** The columns the history panel shows, in the order they are in the file. */
    static final List<String> COLUMNS = List.of("datetime", "used", "limit", "currency");

    /** The fields of a row as the file has them: the shown columns, then the status, the interval and the duration. */
    static final int FIELDS = 7;

    /**
     * @param exists whether there is a history file at all
     * @param total how many rows the file has
     * @param rows the newest rows, latest first, as many as were asked for; each has the four columns and
     *     three more: the status ({@code start}, {@code failed},
     *     {@code start-failed} or empty), the interval in seconds and the duration in milliseconds
     * @param deltas the differences from the row before it in the file, one for each of {@code rows}, in the
     *     same order; see {@link HistoryDeltas}
     */
    public record Table(boolean exists, int total, List<List<String>> rows, List<HistoryDeltas.Delta> deltas) {

        public List<String> columns() {
            return COLUMNS;
        }
    }

    private HistoryReader() {
    }

    /** @param limit the most rows to return, the newest of them */
    public static Table read(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Table(false, 0, List.of(), List.of());
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            List<String> fields = List.of(line.split(",", -1));
            boolean header = fields.get(0).equals(COLUMNS.get(0));
            // Fewer columns are a row from an older file, which had no value for the others.
            boolean complete = fields.size() >= COLUMNS.size() - 1 && fields.size() <= FIELDS;
            if (complete && !header && !fields.get(0).isBlank()) {
                List<String> row = new ArrayList<>(fields);
                while (row.size() < FIELDS) {
                    row.add("");
                }
                rows.add(List.copyOf(row));
            }
        }
        // The differences are of each row to the one before it in the file, so they are worked out before sorting.
        List<HistoryDeltas.Delta> all = HistoryDeltas.compute(rows);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        // Newest first; of two with the same time, the one written later.
        order.sort(Comparator.<Integer, String>comparing(i -> rows.get(i).get(0)).thenComparing(Comparator.naturalOrder()).reversed());
        List<Integer> shown = order.subList(0, Math.min(limit, order.size()));
        return new Table(true, rows.size(),
                shown.stream().map(rows::get).toList(),
                shown.stream().map(all::get).toList());
    }
}
