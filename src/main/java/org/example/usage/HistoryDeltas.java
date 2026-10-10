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
 * <p>Both are taken against the row directly before in the file, and only within a run: a row whose
 * status begins a run ({@code start} or {@code start-failed}), and the first row of the file, have
 * neither. The change in the amount used is the amount minus the previous row's, and is empty if either
 * row has no amounts, so after a failed row there is none. The time is the seconds between the two rows,
 * a failed one included, so a failed row has a time. It is empty if a time cannot be read or the row
 * is earlier than the one before it (the clock was set back). The times are the local times of the
 * file, which does not say its zone, so across a change of the clocks the difference is that of the
 * wall clock.
 */
public final class HistoryDeltas {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * @param used the change in the first figure of a row, or {@code null} if it cannot be worked out: the amount used in the
     *     usage-based format, the percentage of the five-hour session limit in the seat-based one
     * @param other the change in the percentage of the weekly limit, in the seat-based format; always {@code null} in the other
     * @param seconds the whole seconds since the previous row, or {@code null}
     */
    public record Delta(BigDecimal used, BigDecimal other, Long seconds) {

        public static final Delta NONE = new Delta(null, null, null);

        /** A delta of the usage-based format, which has one figure. */
        public Delta(BigDecimal used, Long seconds) {
            this(used, null, seconds);
        }

        /** Whether the figures of the row are the same as those of the row before: a change of exactly zero in each. */
        public boolean isZero(UsageFormat format) {
            boolean first = used != null && used.signum() == 0;
            return format == UsageFormat.SEAT_BASED ? first && other != null && other.signum() == 0 : first;
        }
    }

    private HistoryDeltas() {
    }

    /**
     * @param rows the rows in the order of the file, each as {@link HistoryReader} gives them
     * @return one delta for each row, in the same order
     */
    public static List<Delta> compute(List<List<String>> rows) {
        return compute(rows, UsageFormat.USAGE_BASED);
    }

    /** @param format the format of the file the rows are of, which says where their figures and their status are */
    public static List<Delta> compute(List<List<String>> rows, UsageFormat format) {
        List<Delta> deltas = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            deltas.add(i == 0 || beginsRun(rows.get(i), format) ? Delta.NONE : between(rows.get(i - 1), rows.get(i), format));
        }
        return deltas;
    }

    /**
     * The same, on the rows that are shown: each shown row is compared with the previous shown row of its run, not with the row
     * directly before it. What is hidden has no delta ({@link Delta#NONE}) but still counts for the runs: a row whose status
     * begins a run begins it even if it is hidden, so the first row shown after it has no previous row to compare with.
     *
     * @param rows the rows in the order of the file
     * @param visible for each row, whether it is shown
     * @return one delta for each row, in the same order
     */
    public static List<Delta> computeVisible(List<List<String>> rows, boolean[] visible) {
        return computeVisible(rows, visible, UsageFormat.USAGE_BASED);
    }

    public static List<Delta> computeVisible(List<List<String>> rows, boolean[] visible, UsageFormat format) {
        List<Delta> deltas = new ArrayList<>(rows.size());
        int previous = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (beginsRun(rows.get(i), format)) {
                previous = -1;
            }
            if (!visible[i]) {
                deltas.add(Delta.NONE);
                continue;
            }
            deltas.add(previous < 0 ? Delta.NONE : between(rows.get(previous), rows.get(i), format));
            previous = i;
        }
        return deltas;
    }

    private static boolean beginsRun(List<String> row, UsageFormat format) {
        return row.get(HistoryReader.statusIndex(format)).startsWith("start");
    }

    private static Delta between(List<String> before, List<String> row, UsageFormat format) {
        return new Delta(
                change(before, row, 1),
                format == UsageFormat.SEAT_BASED ? change(before, row, HistoryReader.SEVEN_DAY_INDEX) : null,
                seconds(before, row));
    }

    /** The figure in that field of the row, less that of the row before. */
    private static BigDecimal change(List<String> before, List<String> row, int field) {
        try {
            if (before.get(field).isBlank() || row.get(field).isBlank()) {
                return null;
            }
            return new BigDecimal(row.get(field)).subtract(new BigDecimal(before.get(field)));
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
