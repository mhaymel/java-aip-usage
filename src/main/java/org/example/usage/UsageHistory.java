package org.example.usage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The usage history: a CSV file with one row per query, appended as they are made.
 *
 * <p>Each row is the time, the amount used, the budget, the currency, a status, the interval in
 * force and how long the request took: {@code 2026-10-08 16:24:53,186.02,1000.00,USD,start,60,412}.
 * The time is the local date and time to the second,
 * written {@code yyyy-MM-dd HH:mm:ss}: the form Excel recognises as a date and time when it
 * imports the file, and it still sorts correctly as text. It is the same clock as the window
 * shows. (Excel has no idea of a time zone, so there is none in the file, and an hour repeats
 * when the clocks go back.) The amounts are
 * plain numbers with two decimals and a dot, whatever the machine's language, with no
 * currency sign and no digit grouping, so every tool reads them the same way. The currency is
 * the code the response named. A missing amount, or currency, is an empty field.
 *
 * <p>The status is {@code start} on the first row this run wrote, {@code failed} on the row of a query
 * that did not succeed (which has no amounts), {@code start-failed} when that is the first row, and
 * empty otherwise. The interval is in whole seconds and the duration in whole milliseconds.
 *
 * <p>The header is written only when the file is new or empty, so successive runs add to the one
 * file. Nothing is ever rotated or removed. A reading with no amounts, that of an account that
 * reports no usage, writes nothing. A file with an older header is upgraded in place by the first row added to
 * it: the header gets the new columns and the rows already there empty fields, a {@code 1} in the old
 * {@code startup} column becoming {@code start}.
 */
public final class UsageHistory {

    static final String HEADER = "datetime,used,limit,currency,status,interval,duration_ms";

    /** The header of files written before the currency was a column. */
    private static final String OLD_HEADER = "datetime,used,limit";

    /** The header of files written before the startup mark was a column. */
    private static final String PREVIOUS_HEADER = "datetime,used,limit,currency";

    /** The header of files written before the startup mark became the status and the timing was added. */
    private static final String STARTUP_HEADER = "datetime,used,limit,currency,startup";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Path file;

    private final ZoneId zone;

    /** Whether a row has been written since this object was made, which is since the program started. */
    private boolean written;

    /** Writes times in the machine's own zone, which is the window's. */
    public UsageHistory(Path file) {
        this(file, ZoneId.systemDefault());
    }

    /** @param zone the zone the times are written in; a parameter so a test is not at the mercy of the machine's */
    public UsageHistory(Path file, ZoneId zone) {
        this.file = file;
        this.zone = zone;
    }

    public Path file() {
        return file;
    }

    /**
     * Adds the reading as a row. A reading with no amounts writes nothing.
     *
     * @param intervalSeconds the time between usage requests in force when the request was made
     * @param durationMillis how long the request took
     * @throws IOException if the file cannot be written
     */
    public synchronized void append(UsageSnapshot snapshot, int intervalSeconds, long durationMillis) throws IOException {
        Spend spend = snapshot.spend();
        if (spend == null) {
            return;
        }
        write(snapshot.fetchedAt(), amount(spend.used()), amount(spend.limit()),
                spend.currency() == null ? "" : spend.currency().replace(',', ' ').strip(),
                false, intervalSeconds, durationMillis);
    }

    /**
     * Adds the row of a query that did not succeed: the time it failed, no amounts.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void appendFailure(Instant at, int intervalSeconds, long durationMillis) throws IOException {
        write(at, "", "", "", true, intervalSeconds, durationMillis);
    }

    private void write(Instant at, String used, String limit, String currency, boolean failed,
                       int intervalSeconds, long durationMillis) throws IOException {
        String status = (written ? "" : "start") + (failed ? (written ? "failed" : "-failed") : "");
        String row = TIME.format(at.atZone(zone))
                + "," + used
                + "," + limit
                + "," + currency
                + "," + status
                + "," + intervalSeconds
                + "," + durationMillis
                + "\n";
        upgradeOldFile();
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        Files.writeString(
                file,
                needsHeader ? HEADER + "\n" + row : row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        written = true;
    }

    /** Brings a file with an older header up to date. Written beside it first, so a failure loses nothing. */
    private void upgradeOldFile() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        String header = lines.get(0).strip();
        // What the rows of the old file lack, and whether its fifth field is the old startup mark.
        String missing;
        boolean startupMark = false;
        if (header.equals(OLD_HEADER)) {
            missing = ",,,,";
        } else if (header.equals(PREVIOUS_HEADER)) {
            missing = ",,,";
        } else if (header.equals(STARTUP_HEADER)) {
            missing = ",,";
            startupMark = true;
        } else {
            return;
        }
        List<String> upgraded = new ArrayList<>();
        upgraded.add(HEADER);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                upgraded.add(line);
            } else if (startupMark) {
                upgraded.add(line.endsWith(",1") ? line.substring(0, line.length() - 1) + "start" + missing : line + missing);
            } else {
                upgraded.add(line + missing);
            }
        }
        Path beside = file.resolveSibling(file.getFileName() + ".tmp");
        // A line feed after every line, as the rows that are appended have, and not the platform's line ending:
        // the file would otherwise have two kinds of line ending on Windows.
        Files.writeString(beside, String.join("\n", upgraded) + "\n", StandardCharsets.UTF_8);
        // Atomic where the file system can: a plain replacing move deletes the old file first, and a reader
        // (the history panel) arriving in that moment would find no file.
        try {
            Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The newest reading in the file, for showing before the first refresh has finished: the newest row
     * that has amounts, so a failed row is passed over. Empty if there is no file, no such row, or it
     * cannot be read.
     */
    public Optional<UsageSnapshot> latest() {
        try {
            HistoryReader.Table table = HistoryReader.read(file, Integer.MAX_VALUE);
            List<String> row = table.rows().stream()
                    .filter(r -> !r.get(1).isBlank() || !r.get(2).isBlank())
                    .findFirst()
                    .orElse(null);
            if (row == null) {
                return Optional.empty();
            }
            Double used = number(row.get(1));
            Double limit = number(row.get(2));
            Instant at = LocalDateTime.parse(row.get(0), TIME).atZone(zone).toInstant();
            String currency = row.get(3).isBlank() ? null : row.get(3);
            Integer percent = used != null && limit != null && limit > 0 ? (int) Math.round(used / limit * 100) : null;
            return Optional.of(new UsageSnapshot(at, new Spend(used, limit, currency, percent, null)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Double number(String text) {
        return text.isBlank() ? null : Double.valueOf(text);
    }

    private static String amount(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
