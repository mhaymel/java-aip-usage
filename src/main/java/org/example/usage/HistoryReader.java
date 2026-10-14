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

    /**
     * The fields of a row as the file has them: the shown columns, then the status, the interval and the duration, and since
     * 0.82 the monthly limit counted, the daily limit and how the daily limit came about, which an older row has empty.
     */
    static final int FIELDS = 10;

    /**
     * @param exists whether there is a history file at all
     * @param total how many rows the file has
     * @param rows the newest rows, latest first, as many as were asked for; each has the four columns and
     *     three more: the status ({@code start}, {@code failed},
     *     {@code start-failed} or empty), the interval in seconds and the duration in milliseconds
     * @param deltas the differences, one for each of {@code rows}, in the same order: the time against the previous row
     *     that is shown in the same run (see {@link HistoryDeltas#computeVisible}), and the change in the amount used against the
     *     last earlier row that has an amount (see {@link HistoryDeltas#amountChanges})
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
     * Which lines are left out. A <em>zero usage line</em> is a row whose change in the amount used is 0.00: the change the
     * history shows for it, against the last earlier row that has an amount, whatever lies between, a failed row or the
     * start of a run. So the lines that are hidden with the zero usage lines are exactly those whose change would be an
     * empty cell. A startup line is a row like any other in this; a failed row, which has no amounts, is not one, and a
     * failed startup line is a failed line. A <em>failed line</em> is a row of a failed query.
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
        // What a zero usage line is is decided on the whole file, by the change the history shows for a row, before anything is hidden.
        List<org.example.money.Amount> changes = HistoryDeltas.amountChanges(rows);
        boolean[] visible = new boolean[rows.size()];
        int hiddenZero = 0;
        int hiddenFailed = 0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            boolean failed = rows.get(i).get(4).contains("failed");
            // A failed line is checked first: it has no amount, and so no change. For every other the question is the one the
            // cell of the change asks: is it 0.00, to the cent?
            boolean zero = !failed && changes.get(i) != null && changes.get(i).cents() == 0;
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
        // The change in the amount used is not: it is against the last earlier row that has an amount, whatever is hidden.
        List<HistoryDeltas.Delta> all = HistoryDeltas.withAmountChanges(rows, HistoryDeltas.computeVisible(rows, visible));
        // Newest first; of two with the same time, the one written later.
        order.sort(Comparator.<Integer, String>comparing(i -> rows.get(i).get(0)).thenComparing(Comparator.naturalOrder()).reversed());
        List<Integer> shown = order.subList(0, Math.min(limit, order.size()));
        return new Table(true, rows.size(),
                shown.stream().map(rows::get).toList(),
                shown.stream().map(all::get).toList(),
                hiddenZero, hiddenFailed, order.size());
    }
}
