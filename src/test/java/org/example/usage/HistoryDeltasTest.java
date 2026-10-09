package org.example.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryDeltasTest {

    private static List<String> row(String time, String used, String status) {
        return List.of("2026-10-08 " + time, used, used.isEmpty() ? "" : "1000.00", used.isEmpty() ? "" : "USD", status, "60", "400");
    }

    private static List<HistoryDeltas.Delta> compute(List<String>... rows) {
        return HistoryDeltas.compute(new ArrayList<>(List.of(rows)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theFirstRowOfARunHasNeitherAndTheNextOnesCountFromTheRowBefore() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.25", ""), row("14:03:30", "10.20", ""));

        assertEquals(HistoryDeltas.Delta.NONE, d.get(0));
        assertEquals(new BigDecimal("0.25"), d.get(1).used());
        assertEquals(60L, d.get(1).seconds());
        assertEquals(new BigDecimal("-0.05"), d.get(2).used(), "a drop is negative");
        assertEquals(150L, d.get(2).seconds());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewRunStartsAgainAndIsNotComparedWithTheLastOneOfTheRunBefore() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.25", ""),
                row("16:00:00", "11.00", "start"), row("16:01:00", "11.10", ""));

        assertEquals(HistoryDeltas.Delta.NONE, d.get(2));
        assertEquals(new BigDecimal("0.10"), d.get(3).used());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFailedRowHasATimeButNoChangeAndTheRowAfterItHasATimeFromItButNoChange() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:00:00", "10.00", "start"), row("14:01:00", "", "failed"), row("14:02:00", "10.50", ""));

        assertNull(d.get(1).used(), "a failed row has no amounts");
        assertEquals(60L, d.get(1).seconds());
        assertNull(d.get(2).used(), "the row before it has none");
        assertEquals(60L, d.get(2).seconds(), "counted from the failed row, not the last good one");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFirstRowThatFailedBeginsARunToo() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:00:00", "", "start-failed"), row("14:01:00", "10.00", ""));

        assertEquals(HistoryDeltas.Delta.NONE, d.get(0));
        assertNull(d.get(1).used());
        assertEquals(60L, d.get(1).seconds());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRowWithNoStatusAtAllIsOnlyTheFirstOfTheFileWhenOldRowsHaveNoMarks() {
        List<HistoryDeltas.Delta> d = compute(row("14:00:00", "10.00", ""), row("14:01:00", "10.10", ""));

        assertEquals(HistoryDeltas.Delta.NONE, d.get(0));
        assertEquals(new BigDecimal("0.10"), d.get(1).used());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRowEarlierThanTheOneBeforeOrWithAnUnreadableValueLeavesThatValueEmpty() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:05:00", "10.00", "start"), row("14:01:00", "10.25", ""),
                List.of("yesterday", "abc", "1", "USD", "", "60", "4"));

        assertNull(d.get(1).seconds(), "the clock was set back");
        assertEquals(new BigDecimal("0.25"), d.get(1).used(), "the amount is still worked out");
        assertNull(d.get(2).seconds(), "the time cannot be read");
        assertNull(d.get(2).used(), "the amount cannot be read");
    }

    @Test
    void noRowsGiveNoDeltas() {
        assertEquals(List.of(), HistoryDeltas.compute(List.of()));
    }
}
