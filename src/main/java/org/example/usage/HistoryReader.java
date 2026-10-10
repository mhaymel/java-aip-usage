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
     * @param deltas the differences, one for each of {@code rows}, in the same order, against the previous row
     *     that is shown in the same run; see {@link HistoryDeltas#computeVisible}
     * @param hiddenZero how many rows the filter left out for being zero usage lines
     * @param hiddenFailed how many rows it left out for being failed lines
     * @param visible how many rows are left once the filter has hidden what it hides, which can be more than {@code rows}
     *     holds if more were found than were asked for
     */
    public record Table(
            boolean exists, int total, List<List<String>> rows, List<HistoryDeltas.Delta> deltas,
            int hiddenZero, int hiddenFailed, int visible) {

        public List<String> columns() {
            return COLUMNS;
        }
    }

    private HistoryReader() {
    }

    /**
     * Which lines are left out. A <em>zero usage line</em> is a row whose change in the amount used, against the row directly
     * before it, is exactly zero, or a startup line (the first row of a run, which has no change); a failed row, which has
     * no amounts, is not one, and a failed startup line is a failed line. A <em>failed line</em> is a row of a failed query.
     */
    public record Filter(boolean showZero, boolean showFailed) {

        public static final Filter ALL = new Filter(true, true);
    }

    /** @param limit the most rows to return, the newest of them; nothing is left out */
    public static Table read(Path file, int limit) throws IOException {
        return read(file, limit, Filter.ALL);
    }

    /**
     * @param limit the most rows to return, the newest of those that are shown
     * @param filter which lines are left out before the rows are sorted, cut and worked out
     */
    public static Table read(Path file, int limit, Filter filter) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Table(false, 0, List.of(), List.of(), 0, 0, 0);
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
        // What a zero usage line is is decided on the whole file, against the row directly before each row, before anything is hidden.
        List<HistoryDeltas.Delta> against = HistoryDeltas.compute(rows);
        boolean[] visible = new boolean[rows.size()];
        int hiddenZero = 0;
        int hiddenFailed = 0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            boolean failed = rows.get(i).get(4).contains("failed");
            // A failed startup line (start-failed) is a failed line, checked first. A startup line that read the usage has no change, and is handled like the
            // lines that show none: a zero usage line.
            boolean startup = rows.get(i).get(4).startsWith("start");
            boolean zero = !failed && (startup || (against.get(i).used() != null && against.get(i).used().signum() == 0));
            if (failed && !filter.showFailed()) {
                hiddenFailed++;
            } else if (zero && !filter.showZero()) {
                hiddenZero++;
            } else {
                visible[i] = true;
                order.add(i);
            }
        }
        // The differences of what is shown are of each row to the previous one shown in its run, so they are worked out before sorting.
        List<HistoryDeltas.Delta> all = HistoryDeltas.computeVisible(rows, visible);
        // Newest first; of two with the same time, the one written later.
        order.sort(Comparator.<Integer, String>comparing(i -> rows.get(i).get(0)).thenComparing(Comparator.naturalOrder()).reversed());
        List<Integer> shown = order.subList(0, Math.min(limit, order.size()));
        return new Table(true, rows.size(),
                shown.stream().map(rows::get).toList(),
                shown.stream().map(all::get).toList(),
                hiddenZero, hiddenFailed, order.size());
    }
}
