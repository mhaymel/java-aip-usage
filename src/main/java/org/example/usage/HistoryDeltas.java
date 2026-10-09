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
            deltas.add(i == 0 || beginsRun(rows.get(i)) ? Delta.NONE : between(rows.get(i - 1), rows.get(i)));
        }
        return deltas;
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
