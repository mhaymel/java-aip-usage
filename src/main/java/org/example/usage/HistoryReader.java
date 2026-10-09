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
 * columns (or the first three or four, as in a file from before the currency or the startup mark was a column)
 * is skipped, so a damaged line cannot hide the rest.
 */
public final class HistoryReader {

    static final List<String> COLUMNS = List.of("datetime", "used", "limit", "currency");

    /**
     * @param exists whether there is a history file at all
     * @param total how many rows the file has
     * @param rows the newest rows, latest first, as many as were asked for; each has the four columns and
     *     a fifth, {@code 1} for the first row written after a program start and empty otherwise
     */
    public record Table(boolean exists, int total, List<List<String>> rows) {

        public List<String> columns() {
            return COLUMNS;
        }
    }

    private HistoryReader() {
    }

    /** @param limit the most rows to return, the newest of them */
    public static Table read(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Table(false, 0, List.of());
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            List<String> fields = List.of(line.split(",", -1));
            boolean header = fields.get(0).equals(COLUMNS.get(0));
            // Three columns are a row from before the currency was one, four from before the startup mark: no value.
            boolean complete = fields.size() >= COLUMNS.size() - 1 && fields.size() <= COLUMNS.size() + 1;
            if (complete && !header && !fields.get(0).isBlank()) {
                List<String> row = new ArrayList<>(fields);
                while (row.size() < COLUMNS.size() + 1) {
                    row.add("");
                }
                rows.add(List.copyOf(row));
            }
        }
        rows.sort(Comparator.<List<String>, String>comparing(row -> row.get(0)).reversed());
        return new Table(true, rows.size(), List.copyOf(rows.subList(0, Math.min(limit, rows.size()))));
    }
}
