package org.example.usage;

import org.example.money.Amount;

import java.io.IOException;
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

    static final String HEADER = "datetime,used,limit,currency,status,interval,duration_ms,monthly_limit,daily_limit,daily_auto";

    /** The header of files written before the limits of cost control were columns (until 0.81). */
    private static final String SEVEN_HEADER = "datetime,used,limit,currency,status,interval,duration_ms";

    /**
     * The three columns of the limits of cost control at a reading (since 0.82), given the limit the server sent with it in
     * cents or null: the monthly limit counted, the daily limit in force, and {@code auto} or {@code set}, each empty for none.
     */
    private volatile java.util.function.Function<Long, String[]> limits = server -> new String[] {"", "", ""};

    /** The header of files written before the currency was a column. */
    private static final String OLD_HEADER = "datetime,used,limit";

    /** The header of files written before the startup mark was a column. */
    private static final String PREVIOUS_HEADER = "datetime,used,limit,currency";

    /** The header of files written before the startup mark became the status and the timing was added. */
    private static final String STARTUP_HEADER = "datetime,used,limit,currency,startup";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private volatile Path file;

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

    /** Says what the three columns of the limits are for a reading; until it is said they are empty. */
    public void limitsFrom(java.util.function.Function<Long, String[]> columns) {
        this.limits = columns;
    }

    /**
     * Keeps the history in this folder from now on: the file is moved there, under its name, unless a file of that name is
     * there already, which is then the history and is not overwritten; the one in the old folder is left as it is. Nothing is
     * written meanwhile.
     *
     * @return whether the file was moved; false when there was none to move or one was there already
     * @throws IOException if the file cannot be moved; the history is then where it was
     */
    public synchronized boolean moveTo(Path folder) throws IOException {
        Path target = folder.resolve(file.getFileName());
        if (target.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            return false;
        }
        boolean moved = !Files.exists(target) && Files.exists(file);
        if (moved) {
            Files.move(file, target);
        }
        file = target;
        return moved;
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
        Long server;
        try {
            server = spend.limit() == null ? null : spend.limit().cents();
        } catch (ArithmeticException e) {
            server = null;
        }
        write(snapshot.fetchedAt(), amount(spend.used()), amount(spend.limit()),
                spend.currency() == null ? "" : spend.currency().replace(',', ' ').strip(),
                false, intervalSeconds, durationMillis, limits.apply(server));
    }

    /**
     * Adds the row of a query that did not succeed: the time it failed, no amounts.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void appendFailure(Instant at, int intervalSeconds, long durationMillis) throws IOException {
        write(at, "", "", "", true, intervalSeconds, durationMillis, limits.apply(null));
    }

    private void write(Instant at, String used, String limit, String currency, boolean failed,
                       int intervalSeconds, long durationMillis, String[] limitColumns) throws IOException {
        String status = (written ? "" : "start") + (failed ? (written ? "failed" : "-failed") : "");
        String row = TIME.format(at.atZone(zone))
                + "," + used
                + "," + limit
                + "," + currency
                + "," + status
                + "," + intervalSeconds
                + "," + durationMillis
                + "," + limitColumns[0] + "," + limitColumns[1] + "," + limitColumns[2]
                + "\n";
        try {
            upgradeOldFile();
        } catch (IOException e) {
            // The file is as it was, with its old header: the row is added all the same, and every reader takes it by place.
            org.example.log.Log.of(UsageHistory.class).warning("Could not bring the header of " + file + " up to date", e);
        }
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        Files.writeString(
                file,
                needsHeader ? HEADER + "\n" + row : row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        written = true;
    }

    /**
     * Begins the month with invented readings (since 0.97, for the fake backend): when the file has no reading of their month
     * before the first of them, they are put in it in the order of time, before the first row that is not earlier than them,
     * with no status, so that they begin no run. Nothing is changed when the month has a reading already.
     *
     * @param readings the times and the amounts used, in the order of time
     * @param limit the limit they were read against
     * @param currency its code
     * @return whether they were added
     * @throws IOException if the file cannot be written; it is then as it was
     */
    public synchronized boolean seed(List<org.example.fake.FakeMonth.Reading> readings, Amount limit, String currency) throws IOException {
        if (readings.isEmpty()) {
            return false;
        }
        LocalDateTime first = readings.get(0).at();
        LocalDateTime last = readings.get(readings.size() - 1).at();
        LocalDateTime monthStart = first.toLocalDate().withDayOfMonth(1).atStartOfDay();
        upgradeOldFile();
        List<String> lines = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
        int insertAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",", -1);
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(fields[0], TIME);
            } catch (java.time.format.DateTimeParseException e) {
                continue;
            }
            boolean reading = fields.length > 1 && !fields[1].isBlank();
            if (reading && !at.isBefore(monthStart) && !at.isAfter(last)) {
                return false;
            }
            if (insertAt < 0 && !at.isBefore(first)) {
                insertAt = i;
            }
        }
        Long server;
        try {
            server = limit.cents();
        } catch (ArithmeticException e) {
            server = null;
        }
        String[] limitColumns = limits.apply(server);
        List<String> rows = new ArrayList<>();
        for (org.example.fake.FakeMonth.Reading reading : readings) {
            rows.add(TIME.format(reading.at()) + "," + Amount.ofCents(reading.usedCents()).plain() + "," + limit.plain() + "," + currency
                    + ",,,," + limitColumns[0] + "," + limitColumns[1] + "," + limitColumns[2]);
        }
        List<String> written = new ArrayList<>(lines);
        if (written.isEmpty() || written.get(0).isBlank()) {
            written.clear();
            written.add(HEADER);
        }
        written.addAll(insertAt < 0 ? written.size() : insertAt, rows);
        Path beside = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(beside, String.join("\n", written) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(beside, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
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
            missing = ",,,,,,,";
        } else if (header.equals(PREVIOUS_HEADER)) {
            missing = ",,,,,,";
        } else if (header.equals(STARTUP_HEADER)) {
            missing = ",,,,,";
            startupMark = true;
        } else if (header.equals(SEVEN_HEADER)) {
            // A file of 0.81: its rows lack the three columns of the limits, which are empty for them.
            missing = ",,,";
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
            Amount used = number(row.get(1));
            Amount limit = number(row.get(2));
            Instant at = LocalDateTime.parse(row.get(0), TIME).atZone(zone).toInstant();
            String currency = row.get(3).isBlank() ? null : row.get(3);
            Integer percent = used != null && limit != null && limit.signum() > 0 ? (int) used.shareOf(limit, 2) : null;
            return Optional.of(new UsageSnapshot(at, new Spend(used, limit, currency, percent, null)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Amount number(String text) {
        return text.isBlank() ? null : Amount.parse(text.strip());
    }

    private static String amount(Amount value) {
        return value == null ? "" : value.plain();
    }
}
