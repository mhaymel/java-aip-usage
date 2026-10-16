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
        List<List<String>> rows = rows(file);
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

    /** The rows of the file in its order, each with all its fields; a line that is no row is passed over. */
    private static List<List<String>> rows(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The rows of these lines, in their order; a line that is no row is passed over. */
    private static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.isEmpty()) {
                continue;
            }
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
        return rows;
    }

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);

    /**
     * The history grouped by an interval (since 0.94): every row of the file is read, and the successful readings of each interval
     * make one row. Its time is the beginning of the interval, written as the file writes a time; its other fields are those of
     * the interval's last reading, with no status, so that it begins no run and is no failed line. Its change in the amount used is
     * the sum of the changes of its readings ({@link HistoryDeltas#amountChanges}, against the reading before, so that a new month
     * within the interval counts right), and its time the seconds from the reading before its first to its last. An interval with
     * no successful reading has no row.
     *
     * <p>A year of readings a minute is half a million rows: what was grouped is kept, for each file and interval, and while the
     * file only grows, only the lines added since are read and added (see {@link Grouping}).
     *
     * @param limit the most rows to return, the newest of those that are shown
     * @param showZero whether an interval whose change in the amount used is 0.00 is shown
     * @return the intervals newest first, {@code total} and {@code visible} counting intervals; nothing is a failed line
     */
    public static Table readGrouped(Path file, int limit, boolean showZero, org.example.settings.HistoryGroup group) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Table(false, 0, List.of(), List.of(), 0, 0, 0);
        }
        synchronized (GROUPINGS) {
            String key = file.toAbsolutePath() + "|" + group.key();
            Grouping grouping = GROUPINGS.get(key);
            if (grouping == null || !grouping.follow(file)) {
                grouping = Grouping.of(file, group);
                GROUPINGS.put(key, grouping);
            }
            List<List<String>> grouped = new ArrayList<>();
            List<HistoryDeltas.Delta> deltas = new ArrayList<>();
            int hiddenZero = 0;
            for (var entry : grouping.intervals.descendingMap().entrySet()) {
                Interval interval = entry.getValue();
                if (!showZero && interval.used != null && interval.used.cents() == 0) {
                    hiddenZero++;
                    continue;
                }
                if (grouped.size() < limit) {
                    List<String> row = new ArrayList<>(interval.last);
                    row.set(0, TIME.format(entry.getKey()));
                    row.set(4, "");
                    grouped.add(List.copyOf(row));
                    deltas.add(new HistoryDeltas.Delta(interval.used, interval.seconds()));
                }
            }
            int intervals = grouping.intervals.size();
            return new Table(true, intervals, grouped, deltas, hiddenZero, 0, intervals - hiddenZero);
        }
    }

    /** What was grouped, by the file's path and the interval's key; a few at most, one for each interval chosen in a run. */
    private static final java.util.Map<String, Grouping> GROUPINGS = new java.util.HashMap<>();

    /** How many bytes before the end of what was read are kept, to tell that the file is the same one and was only added to. */
    private static final int TAIL = 256;

    /**
     * The intervals of one file, and what is needed to add to them: how far the file was read, the bytes just before that, the
     * latest time, the last amount and the latest good reading's time.
     */
    private static final class Grouping {

        private final org.example.settings.HistoryGroup group;

        private final java.util.TreeMap<java.time.LocalDateTime, Interval> intervals = new java.util.TreeMap<>();

        /** How many bytes of the file were read, up to the end of its last whole line. */
        private long read;

        private byte[] tail = new byte[0];

        private String lastTime = "";

        private org.example.money.Amount lastAmount;

        private java.time.LocalDateTime previous;

        private Grouping(org.example.settings.HistoryGroup group) {
            this.group = group;
        }

        /** Everything grouped anew: the rows in the order of time, the changes worked out in the order of the file. */
        static Grouping of(Path file, org.example.settings.HistoryGroup group) throws IOException {
            Grouping grouping = new Grouping(group);
            byte[] bytes = Files.readAllBytes(file);
            int whole = wholeLines(bytes, 0);
            List<List<String>> rows = parse(new String(bytes, 0, whole, StandardCharsets.UTF_8));
            List<org.example.money.Amount> changes = HistoryDeltas.amountChanges(rows);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                order.add(i);
            }
            // In the order of time, as the history shows it; of two with the same time, the one written later after.
            order.sort(Comparator.<Integer, String>comparing(i -> rows.get(i).get(0)).thenComparing(Comparator.naturalOrder()));
            for (int i : order) {
                grouping.add(rows.get(i), changes.get(i));
            }
            for (List<String> row : rows) {
                org.example.money.Amount amount = amountOf(row);
                if (amount != null) {
                    grouping.lastAmount = amount;
                }
            }
            grouping.mark(bytes, whole);
            return grouping;
        }

        /**
         * Adds what was written to the file since it was last read; false when that cannot be done and the file is to be grouped
         * anew: it is shorter, the bytes before where it was read to are others, or a time was added that is earlier than one before.
         */
        boolean follow(Path file) throws IOException {
            long size = Files.size(file);
            if (size < read) {
                return false;
            }
            long from = read - tail.length;
            byte[] added;
            try (var channel = Files.newByteChannel(file)) {
                channel.position(from);
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 8, size - from));
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // On until the buffer is full or the file ends.
                }
                added = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            }
            if (added.length < tail.length || !java.util.Arrays.equals(added, 0, tail.length, tail, 0, tail.length)) {
                return false;
            }
            int whole = wholeLines(added, tail.length);
            List<List<String>> rows = parse(new String(added, tail.length, whole - tail.length, StandardCharsets.UTF_8));
            for (List<String> row : rows) {
                if (row.get(0).compareTo(lastTime) < 0) {
                    return false;
                }
            }
            for (List<String> row : rows) {
                org.example.money.Amount amount = amountOf(row);
                org.example.money.Amount change = null;
                if (amount != null) {
                    try {
                        change = lastAmount == null ? amount : amount.minus(lastAmount);
                    } catch (ArithmeticException e) {
                        change = null;
                    }
                    lastAmount = amount;
                }
                add(row, change);
            }
            read = from;
            mark(added, whole);
            return true;
        }

        /** Remembers how far the file was read: {@code whole} bytes of {@code bytes}, which begin at {@link #read}. */
        private void mark(byte[] bytes, int whole) {
            read += whole;
            tail = java.util.Arrays.copyOfRange(bytes, Math.max(0, whole - TAIL), whole);
        }

        private void add(List<String> row, org.example.money.Amount change) {
            if (row.get(0).compareTo(lastTime) > 0) {
                lastTime = row.get(0);
            }
            java.time.LocalDateTime at;
            try {
                at = java.time.LocalDateTime.parse(row.get(0), TIME);
            } catch (java.time.format.DateTimeParseException e) {
                return;
            }
            // A good reading has an amount and is no failed line; the rest are not grouped.
            if (row.get(4).contains("failed") || row.get(1).isBlank() || change == null) {
                return;
            }
            intervals.computeIfAbsent(group.start(at), start -> new Interval()).add(row, at, change, previous);
            previous = at;
        }

        /** How many of the bytes from {@code from} on are whole lines: up to the last line feed, and nothing of a line still being written. */
        private static int wholeLines(byte[] bytes, int from) {
            for (int i = bytes.length - 1; i >= from; i--) {
                if (bytes[i] == '\n') {
                    return i + 1;
                }
            }
            return from;
        }

        private static org.example.money.Amount amountOf(List<String> row) {
            try {
                return row.get(1).isBlank() ? null : org.example.money.Amount.parse(row.get(1));
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
        }
    }

    /** The successful readings of one interval, as they are added in the order of time. */
    private static final class Interval {

        private List<String> last;

        private org.example.money.Amount used;

        /** The reading before the interval's first, or its first when there is none before; and its last. */
        private java.time.LocalDateTime from;

        private java.time.LocalDateTime to;

        private boolean fromBefore;

        void add(List<String> row, java.time.LocalDateTime at, org.example.money.Amount change, java.time.LocalDateTime previous) {
            if (last == null) {
                from = previous == null ? at : previous;
                fromBefore = previous != null;
            }
            last = row;
            to = at;
            try {
                used = used == null ? change : used.plus(change);
            } catch (ArithmeticException e) {
                used = null;
            }
        }

        /** The seconds the interval's readings cover, from the reading before its first; none for the very first reading of all. */
        Long seconds() {
            return fromBefore || !from.equals(to) ? java.time.Duration.between(from, to).toSeconds() : null;
        }
    }
}
