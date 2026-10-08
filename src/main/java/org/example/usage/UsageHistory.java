package org.example.usage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
 * The usage history: a CSV file with one row per reading, appended as they arrive.
 *
 * <p>Each row is the time of the reading, the amount used, the budget and the currency:
 * {@code 2026-10-08 16:24:53,186.02,1000.00,USD}. The time is the local date and time to the second,
 * written {@code yyyy-MM-dd HH:mm:ss}: the form Excel recognises as a date and time when it
 * imports the file, and it still sorts correctly as text. It is the same clock as the window
 * shows. (Excel has no idea of a time zone, so there is none in the file, and an hour repeats
 * when the clocks go back.) The amounts are
 * plain numbers with two decimals and a dot, whatever the machine's language, with no
 * currency sign and no digit grouping, so every tool reads them the same way. The currency is
 * the code the response named. A missing amount, or currency, is an empty field.
 *
 * <p>The header is written only when the file is new or empty, so successive runs add to the one
 * file. Nothing is ever rotated or removed. A reading with no amounts, such as a plan account's
 * windows, writes nothing. A file from before the currency was a column is upgraded in place by the
 * first row added to it: the header gets the column and the rows already there an empty currency.
 */
public final class UsageHistory {

    static final String HEADER = "datetime,used,limit,currency";

    /** The header of files written before the currency was a column. */
    private static final String OLD_HEADER = "datetime,used,limit";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Path file;

    private final ZoneId zone;

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
     * Adds the reading as a row.
     *
     * @throws IOException if the file cannot be written
     */
    public void append(UsageSnapshot snapshot) throws IOException {
        Spend spend = snapshot.spend();
        if (spend == null) {
            return;
        }
        String row = TIME.format(snapshot.fetchedAt().atZone(zone))
                + "," + amount(spend.used())
                + "," + amount(spend.limit())
                + "," + (spend.currency() == null ? "" : spend.currency().replace(',', ' ').strip())
                + "\n";
        upgradeOldFile();
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        Files.writeString(
                file,
                needsHeader ? HEADER + "\n" + row : row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Adds the currency column to a file that predates it. Written beside it first, so a failure loses nothing. */
    private void upgradeOldFile() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).strip().equals(OLD_HEADER)) {
            return;
        }
        List<String> upgraded = new ArrayList<>();
        upgraded.add(HEADER);
        for (String line : lines.subList(1, lines.size())) {
            upgraded.add(line.isBlank() ? line : line + ",");
        }
        Path beside = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(beside, upgraded, StandardCharsets.UTF_8);
        Files.move(beside, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * The newest reading in the file, for showing before the first refresh has finished. Empty if
     * there is no file, no rows, or the newest row has no amounts, or cannot be read.
     */
    public Optional<UsageSnapshot> latest() {
        try {
            HistoryReader.Table table = HistoryReader.read(file, 1);
            if (table.rows().isEmpty()) {
                return Optional.empty();
            }
            List<String> row = table.rows().get(0);
            Double used = number(row.get(1));
            Double limit = number(row.get(2));
            if (used == null && limit == null) {
                return Optional.empty();
            }
            Instant at = LocalDateTime.parse(row.get(0), TIME).atZone(zone).toInstant();
            String currency = row.get(3).isBlank() ? null : row.get(3);
            Integer percent = used != null && limit != null && limit > 0 ? (int) Math.round(used / limit * 100) : null;
            return Optional.of(new UsageSnapshot(at, new Spend(used, limit, currency, percent, null), List.of()));
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
