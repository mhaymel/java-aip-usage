package org.example.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogTest {

    private static final Instant AT = Instant.parse("2026-10-09T10:00:00Z");

    @Test
    void itStartsEmpty() {
        assertEquals(List.of(), new ErrorLog().newestFirst());
    }

    @Test
    void entriesComeBackNewestFirst() {
        ErrorLog log = new ErrorLog();
        log.add(AT, "first");
        log.add(AT.plusSeconds(1), "second");
        log.add(AT.plusSeconds(2), "third");

        assertEquals(List.of("third", "second", "first"), log.newestFirst().stream().map(ErrorLog.Entry::message).toList());
        assertEquals(AT.plusSeconds(2), log.newestFirst().get(0).at());
    }

    @Test
    void itKeepsOnlyTheNewestThousand() {
        ErrorLog log = new ErrorLog();
        for (int i = 0; i < ErrorLog.CAPACITY + 50; i++) {
            log.add(AT.plusSeconds(i), "error " + i);
        }

        List<ErrorLog.Entry> entries = log.newestFirst();

        assertEquals(ErrorLog.CAPACITY, entries.size());
        assertEquals("error " + (ErrorLog.CAPACITY + 49), entries.get(0).message(), "the newest is first");
        assertEquals("error 50", entries.get(ErrorLog.CAPACITY - 1).message(), "the 50 oldest are gone");
    }

    @Test
    void theListHandedOutIsACopy() {
        ErrorLog log = new ErrorLog();
        log.add(AT, "one");
        List<ErrorLog.Entry> before = log.newestFirst();

        log.add(AT, "two");

        assertEquals(1, before.size());
    }

    @Test
    void itCopesWithSeveralThreadsAddingAtOnce() throws Exception {
        ErrorLog log = new ErrorLog();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    log.add(AT, "x");
                    log.newestFirst();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(400, log.newestFirst().size());
    }
}
