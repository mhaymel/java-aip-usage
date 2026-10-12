package org.example.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The differences between the readings of the usage history, worked out here so that no frontend has to.
 *
 * <p>Both are taken against the row directly before in the file. The change in the amount used is the amount minus the
 * previous row's, within a run: a row whose status begins a run ({@code start} or {@code start-failed}) has none, and it is
 * empty if either row has no amounts, so after a failed row there is none. It only decides what a zero usage line is. The time
 * is the seconds between the two rows, whatever their runs, a failed one included, so a failed row has a time and so has the
 * first row of a run; only the first row of the file has none. It is empty if a time cannot be read or the row
 * is earlier than the one before it (the clock was set back). The times are the local times of the
 * file, which does not say its zone, so across a change of the clocks the difference is that of the
 * wall clock.
 *
 * <p>The change in the amount used that is shown is another figure ({@link #amountChanges}): against the last earlier row of the
 * whole file that has an amount, failed rows and run borders ignored, and the amount itself when there is none. The first,
 * against the row directly before, only says which rows are zero usage lines.
 */
public final class HistoryDeltas {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * @param used the change in the amount used, or {@code null} if it cannot be worked out
     * @param seconds the whole seconds since the previous row, or {@code null}
     */
    public record Delta(BigDecimal used, Long seconds) {

        public static final Delta NONE = new Delta(null, null);
    }

    private HistoryDeltas() {
    }

    /**
     * @param rows the rows in the order of the file, each as {@link HistoryReader} gives them
     * @return one delta for each row, in the same order
     */
    public static List<Delta> compute(List<List<String>> rows) {
        List<Delta> deltas = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            // The change in the amount, which only decides what a zero usage line is, stops at the border of a run; the time does not.
            deltas.add(i == 0 ? Delta.NONE : new Delta(
                    beginsRun(rows.get(i)) ? null : used(rows.get(i - 1), rows.get(i)), seconds(rows.get(i - 1), rows.get(i))));
        }
        return deltas;
    }

    /**
     * The same, on the rows that are shown: each shown row is compared with the previous shown row, whatever its run, not with the row
     * directly before it. What is hidden has no delta ({@link Delta#NONE}).
     *
     * @param rows the rows in the order of the file
     * @param visible for each row, whether it is shown
     * @return one delta for each row, in the same order
     */
    public static List<Delta> computeVisible(List<List<String>> rows, boolean[] visible) {
        List<Delta> deltas = new ArrayList<>(rows.size());
        int previous = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!visible[i]) {
                deltas.add(Delta.NONE);
                continue;
            }
            deltas.add(previous < 0 ? Delta.NONE : between(rows.get(previous), rows.get(i)));
            previous = i;
        }
        return deltas;
    }

    /**
     * The change in the amount used of each row, against the last earlier row of the file that has an amount: failed rows have none
     * and are passed over, and so are the borders of runs. A row with no amount has no change, and when no earlier row has an amount the
     * change is the amount itself. Not affected by what the history hides.
     *
     * @param rows the rows in the order of the file
     * @return one change for each row, in the same order; {@code null} for a row that has no amount used
     */
    public static List<BigDecimal> amountChanges(List<List<String>> rows) {
        List<BigDecimal> changes = new ArrayList<>(rows.size());
        BigDecimal last = null;
        for (List<String> row : rows) {
            BigDecimal amount = amount(row);
            if (amount == null) {
                changes.add(null);
                continue;
            }
            changes.add(last == null ? amount : amount.subtract(last));
            last = amount;
        }
        return changes;
    }

    /** The deltas of the shown rows: the time of {@code times}, and the change in the amount used of {@link #amountChanges}. */
    public static List<Delta> withAmountChanges(List<List<String>> rows, List<Delta> times) {
        List<BigDecimal> changes = amountChanges(rows);
        List<Delta> deltas = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            deltas.add(new Delta(changes.get(i), times.get(i).seconds()));
        }
        return deltas;
    }

    private static BigDecimal amount(List<String> row) {
        try {
            return row.get(1).isBlank() ? null : new BigDecimal(row.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean beginsRun(List<String> row) {
        return row.get(4).startsWith("start");
    }

    private static Delta between(List<String> before, List<String> row) {
        return new Delta(used(before, row), seconds(before, row));
    }

    private static BigDecimal used(List<String> before, List<String> row) {
        try {
            if (before.get(1).isBlank() || row.get(1).isBlank()) {
                return null;
            }
            return new BigDecimal(row.get(1)).subtract(new BigDecimal(before.get(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long seconds(List<String> before, List<String> row) {
        try {
            long seconds = Duration.between(
                    LocalDateTime.parse(before.get(0), TIME), LocalDateTime.parse(row.get(0), TIME)).toSeconds();
            return seconds < 0 ? null : seconds;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
