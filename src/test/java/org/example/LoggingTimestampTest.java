package org.example;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingTimestampTest {

    private static final long AT = Instant.parse("2026-10-08T14:24:53.987Z").toEpochMilli();

    @Test
    void aLogTimeIsWrittenLikeTheUsageHistoryWritesIt() {
        assertEquals("2026-10-08 14:24:53", Logging.timestamp(AT, ZoneOffset.UTC), "to the second, cut and not rounded");
    }

    @Test
    void itIsTheLocalTimeInTheZoneGiven() {
        assertEquals("2026-10-08 16:24:53", Logging.timestamp(AT, ZoneId.of("Europe/Vienna")));
    }
}
