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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The usage history: a CSV file with one row per query, appended as they are made.
 *
 * <p>The file holds one format, never both, and its header tells which. In the usage-based format a
 * row is the time, the amount used, the budget, the currency, a status, the interval in force and how
 * long the request took: {@code 2026-10-08 16:24:53,186.02,1000.00,USD,start,60,412}. In the
 * seat-based format it is the time, the percentage of the five-hour session limit and when that is
 * set back, the same two of the weekly limit, and the same last three:
 * {@code 2026-10-08 16:24:53,12.34,2026-10-08 18:00:00,80.00,2026-10-10 02:00:00,start,60,412}.
 *
 * <p>Every time is the local date and time to the second, written {@code yyyy-MM-dd HH:mm:ss}: the
 * form Excel recognises as a date and time when it imports the file, and it still sorts correctly as
 * text. It is the same clock as the window shows. (Excel has no idea of a time zone, so there is none
 * in the file, and an hour repeats when the clocks go back.) The numbers are plain, with two decimals
 * and a dot, whatever the machine's language, with no sign of a currency or of a percentage and no
 * digit grouping, so every tool reads them the same way. What is missing is an empty field.
 *
 * <p>The status is {@code start} on the first row this run wrote, {@code failed} on the row of a query
 * that did not succeed (which has no figures), {@code start-failed} when that is the first row, and
 * empty otherwise. The interval is in whole seconds and the duration in whole milliseconds.
 *
 * <p>The header is written only when the file is new or empty, so successive runs add to the one
 * file. A reading that reports nothing writes nothing. A usage-based file with an older header is
 * upgraded in place by the first row added to it. When a reading arrives in a format the file is not
 * in, or the file has a header that is neither format's, the file is set aside under a name with the
 * date and the time and a new one is begun; nothing is ever overwritten or removed.
 */
public final class UsageHistory {

    private static final System.Logger LOG = System.getLogger(UsageHistory.class.getName());

    static final String HEADER = "datetime,used,limit,currency,status,interval,duration_ms";

    /** The header of a file in the seat-based format. */
    static final String SEAT_HEADER =
            "datetime,five_hour,five_hour_resets,seven_day,seven_day_resets,status,interval,duration_ms";

    /** The header of files written before the currency was a column. */
    private static final String OLD_HEADER = "datetime,used,limit";

    /** The header of files written before the startup mark was a column. */
    private static final String PREVIOUS_HEADER = "datetime,used,limit,currency";

    /** The header of files written before the startup mark became the status and the timing was added. */
    private static final String STARTUP_HEADER = "datetime,used,limit,currency,startup";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** The date and the time in the name of a file that was set aside: {@code 2026.10.10-14.24.53}. */
    private static final DateTimeFormatter ASIDE = DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm.ss", Locale.ROOT);

    private final Path file;

    private final ZoneId zone;

    /** Tells the time a file is set aside at, which goes into its name. */
    private final Clock clock;

    /** Whether a row has been written since this object was made, which is since the program started. */
    private boolean written;

    /** The format of the last reading of this run, which a failed refresh with no file to go by is written in. */
    private UsageFormat lastFormat;

    /** Writes times in the machine's own zone, which is the window's. */
    public UsageHistory(Path file) {
        this(file, ZoneId.systemDefault());
    }

    /** @param zone the zone the times are written in; a parameter so a test is not at the mercy of the machine's */
    public UsageHistory(Path file, ZoneId zone) {
        this(file, zone, Clock.systemUTC());
    }

    /** @param clock the time a file is set aside at; a parameter so a test can know the name it gets */
    public UsageHistory(Path file, ZoneId zone, Clock clock) {
        this.file = file;
        this.zone = zone;
        this.clock = clock;
    }

    public Path file() {
        return file;
    }

    /**
     * Adds the reading as a row, in the file of its format: a file of the other format, or of none the
     * program knows, is set aside first. A reading that reports nothing writes nothing and moves nothing.
     *
     * @param intervalSeconds the time between usage requests in force when the request was made
     * @param durationMillis how long the request took
     * @throws IOException if the file cannot be written, or cannot be set aside
     */
    public synchronized void append(UsageSnapshot snapshot, int intervalSeconds, long durationMillis) throws IOException {
        UsageFormat format = snapshot.format();
        if (format == null) {
            return;
        }
        lastFormat = format;
        makeRoomFor(format);
        if (format == UsageFormat.SEAT_BASED) {
            PlanLimits limits = snapshot.limits();
            write(format, snapshot.fetchedAt(), List.of(
                    percentage(limits.fiveHour().utilization()), resets(limits.fiveHour().resetsAt()),
                    percentage(limits.sevenDay().utilization()), resets(limits.sevenDay().resetsAt())),
                    false, intervalSeconds, durationMillis);
        } else {
            Spend spend = snapshot.spend();
            write(format, snapshot.fetchedAt(), List.of(
                    amount(spend.used()), amount(spend.limit()),
                    spend.currency() == null ? "" : spend.currency().replace(',', ' ').strip()),
                    false, intervalSeconds, durationMillis);
        }
    }

    /**
     * Adds the row of a query that did not succeed: the time it failed, no figures. A failed query has
     * no format, so the row goes into the file as it is, in that file's format, and never moves it. With
     * no file yet it is written in the format of the last reading of this run, and in the usage-based
     * one if there has been none.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void appendFailure(Instant at, int intervalSeconds, long durationMillis) throws IOException {
        Content content = content();
        UsageFormat format;
        if (content.empty()) {
            format = lastFormat != null ? lastFormat : UsageFormat.USAGE_BASED;
        } else {
            // A file with a header the program does not know keeps getting the rows it always got.
            format = content.format() != null ? content.format() : UsageFormat.USAGE_BASED;
        }
        if (format == UsageFormat.USAGE_BASED) {
            upgradeOldFile();
        }
        write(format, at, format == UsageFormat.SEAT_BASED ? List.of("", "", "", "") : List.of("", "", ""),
                true, intervalSeconds, durationMillis);
    }

    private void write(UsageFormat format, Instant at, List<String> figures, boolean failed,
                       int intervalSeconds, long durationMillis) throws IOException {
        String status = (written ? "" : "start") + (failed ? (written ? "failed" : "-failed") : "");
        String row = TIME.format(at.atZone(zone))
                + "," + String.join(",", figures)
                + "," + status
                + "," + intervalSeconds
                + "," + durationMillis
                + "\n";
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        Files.writeString(
                file,
                needsHeader ? (format == UsageFormat.SEAT_BASED ? SEAT_HEADER : HEADER) + "\n" + row : row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        written = true;
    }

    /**
     * What the file holds, as far as its first line and the presence of further ones tell.
     *
     * @param empty there is no file, or nothing in it but blank lines
     * @param format the format its header is of, or {@code null} for a header that is neither format's
     * @param rows whether there is anything after the header; a file whose first line is no known header has
     */
    private record Content(boolean empty, UsageFormat format, boolean rows) {
    }

    private Content content() throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Content(true, null, false);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return new Content(true, null, false);
        }
        String header = lines.get(0).strip();
        UsageFormat format = header.equals(SEAT_HEADER) ? UsageFormat.SEAT_BASED
                : header.equals(HEADER) || header.equals(OLD_HEADER) || header.equals(PREVIOUS_HEADER) || header.equals(STARTUP_HEADER)
                        ? UsageFormat.USAGE_BASED : null;
        return new Content(false, format, format == null || lines.size() > 1);
    }

    /**
     * Sees to it that the file can take a row of this format: left as it is if it is in that format (an older
     * usage-based header upgraded), emptied if it has a header and nothing under it, and set aside if it holds
     * rows of the other format or under a header the program does not know.
     */
    private void makeRoomFor(UsageFormat format) throws IOException {
        Content content = content();
        if (content.empty()) {
            if (Files.exists(file) && Files.size(file) > 0) {
                // Blank lines only: begun again, so that the header is the first line.
                Files.writeString(file, "", StandardCharsets.UTF_8);
            }
            return;
        }
        if (content.format() == format) {
            if (format == UsageFormat.USAGE_BASED) {
                upgradeOldFile();
            }
            return;
        }
        if (!content.rows()) {
            // A header of the other format and nothing under it: there is nothing to keep.
            Files.writeString(file, "", StandardCharsets.UTF_8);
            return;
        }
        setAside(content.format(), format);
    }

    /** Moves the file to a name with the date and the time, so that a new one is begun; never onto a file that is there. */
    private void setAside(UsageFormat from, UsageFormat to) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stamp = ASIDE.format(clock.instant().atZone(zone));
        Path aside = file.resolveSibling(dot < 0 ? name + "." + stamp : name.substring(0, dot) + "." + stamp + name.substring(dot));
        if (Files.exists(aside)) {
            throw new IOException("the usage history cannot be set aside, since " + aside.getFileName() + " is already there");
        }
        try {
            Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(file, aside);
        }
        LOG.log(System.Logger.Level.INFO,
                "The usage format changed: " + (from == null ? "unknown" : from.text()) + " ==> " + to.text());
        LOG.log(System.Logger.Level.INFO, "The usage history so far was moved to " + aside.toAbsolutePath());
    }

    /** Brings a usage-based file with an older header up to date. Written beside it first, so a failure loses nothing. */
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
     * that has figures, so a failed row is passed over, in whichever format the file holds. Empty if there
     * is no file, no such row, or it cannot be read.
     */
    public Optional<UsageSnapshot> latest() {
        try {
            HistoryReader.Table table = HistoryReader.read(file, Integer.MAX_VALUE);
            boolean seat = table.format() == UsageFormat.SEAT_BASED;
            int second = seat ? HistoryReader.SEVEN_DAY_INDEX : 2;
            List<String> row = table.rows().stream()
                    .filter(r -> !r.get(1).isBlank() || !r.get(second).isBlank())
                    .findFirst()
                    .orElse(null);
            if (row == null) {
                return Optional.empty();
            }
            Instant at = LocalDateTime.parse(row.get(0), TIME).atZone(zone).toInstant();
            if (seat) {
                return Optional.of(UsageSnapshot.seatBased(at, new PlanLimits(
                        new PlanLimits.Limit(Double.parseDouble(row.get(1)), instant(row.get(2))),
                        new PlanLimits.Limit(Double.parseDouble(row.get(3)), instant(row.get(4))))));
            }
            Double used = number(row.get(1));
            Double limit = number(row.get(2));
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

    private static String percentage(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** When a limit is set back, as the local date and time the file writes; empty for none, or one that cannot be read. */
    private String resets(String resetsAt) {
        if (resetsAt == null || resetsAt.isBlank()) {
            return "";
        }
        try {
            return TIME.format(OffsetDateTime.parse(resetsAt).atZoneSameInstant(zone));
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    /** A reset time of the file, as the endpoint would have sent it; {@code null} for none. */
    private String instant(String local) {
        return local.isBlank() ? null : LocalDateTime.parse(local, TIME).atZone(zone).toInstant().toString();
    }
}
