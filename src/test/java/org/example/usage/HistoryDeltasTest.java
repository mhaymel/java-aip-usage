package org.example.usage;

import org.example.money.Amount;
import org.junit.jupiter.api.Test;

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
        assertEquals(Amount.parse("0.25"), d.get(1).used());
        assertEquals(60L, d.get(1).seconds());
        assertEquals(Amount.parse("-0.05"), d.get(2).used(), "a drop is negative");
        assertEquals(150L, d.get(2).seconds());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewRunHasNoChangeInTheAmountButHasTheTimeSinceTheLastRowOfTheRunBefore() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.25", ""),
                row("16:00:00", "11.00", "start"), row("16:01:00", "11.10", ""));

        assertNull(d.get(2).used(), "the amount is compared inside a run, which decides the zero usage lines");
        assertEquals(7140L, d.get(2).seconds(), "but the time is since the last row of the run before: 14:01:00 to 16:00:00");
        assertEquals(Amount.parse("0.10"), d.get(3).used());
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
        assertEquals(Amount.parse("0.10"), d.get(1).used());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRowEarlierThanTheOneBeforeOrWithAnUnreadableValueLeavesThatValueEmpty() {
        List<HistoryDeltas.Delta> d = compute(
                row("14:05:00", "10.00", "start"), row("14:01:00", "10.25", ""),
                List.of("yesterday", "abc", "1", "USD", "", "60", "4"));

        assertNull(d.get(1).seconds(), "the clock was set back");
        assertEquals(Amount.parse("0.25"), d.get(1).used(), "the amount is still worked out");
        assertNull(d.get(2).seconds(), "the time cannot be read");
        assertNull(d.get(2).used(), "the amount cannot be read");
    }

    @Test
    void noRowsGiveNoDeltas() {
        assertEquals(List.of(), HistoryDeltas.compute(List.of()));
    }

    // ---- on the rows that are shown

    private static List<HistoryDeltas.Delta> visible(boolean[] shown, List<String>... rows) {
        return HistoryDeltas.computeVisible(new ArrayList<>(List.of(rows)), shown);
    }

    @SuppressWarnings("unchecked")
    @Test
    void whenEverythingIsShownTheChangesAreTheSameAsAgainstTheRowBefore() {
        List<List<String>> rows = List.of(row("14:00:00", "10.00", "start"), row("14:01:00", "10.25", ""), row("14:03:30", "10.20", ""));

        assertEquals(HistoryDeltas.compute(rows), HistoryDeltas.computeVisible(rows, new boolean[] {true, true, true}));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aHiddenRowBetweenTwoShownOnesMakesTheChangeAndTheTimeSpanIt() {
        List<HistoryDeltas.Delta> d = visible(new boolean[] {true, false, true},
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.10", ""), row("14:05:00", "10.30", ""));

        assertEquals(HistoryDeltas.Delta.NONE, d.get(1), "a hidden row has none");
        assertEquals(Amount.parse("0.30"), d.get(2).used(), "against the first row, not the hidden one");
        assertEquals(300L, d.get(2).seconds());
    }

    @SuppressWarnings("unchecked")
    @Test
    void aHiddenRowThatBeginsARunIsNoBorderForTheTime() {
        List<HistoryDeltas.Delta> d = visible(new boolean[] {true, false, true},
                row("14:00:00", "10.00", "start"), row("15:00:00", "", "start-failed"), row("15:01:00", "10.30", ""));

        assertEquals(3660L, d.get(2).seconds(), "14:00:00 to 15:01:00, the hidden row between them being passed over");
    }

    @SuppressWarnings("unchecked")
    @Test
    void theFirstShownRowOfARunHasTheTimeSinceTheShownRowBefore() {
        List<HistoryDeltas.Delta> d = visible(new boolean[] {true, true, true},
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.10", ""), row("16:00:00", "10.20", "start"));

        assertEquals(7140L, d.get(2).seconds(), "14:01:00 to 16:00:00");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFailedRowThatIsShownHasATimeAndNoChangeAndTheNextOneHasATimeFromItAndNoChange() {
        List<HistoryDeltas.Delta> d = visible(new boolean[] {true, true, true},
                row("14:00:00", "10.00", "start"), row("14:01:00", "", "failed"), row("14:02:00", "10.50", ""));

        assertEquals(60L, d.get(1).seconds());
        assertEquals(null, d.get(1).used());
        assertEquals(60L, d.get(2).seconds());
        assertEquals(null, d.get(2).used());
    }

    @SuppressWarnings("unchecked")
    private static List<Amount> changes(List<String>... rows) {
        return HistoryDeltas.amountChanges(new ArrayList<>(List.of(rows)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRowChangedByHandToMoreDecimalsIsTakenExactlyAndOnlyTheResultIsRounded() {
        List<Amount> c = changes(row("14:00:00", "1.004", ""), row("14:01:00", "1.009", ""), row("14:02:00", "1.01", ""));

        assertEquals(Amount.parse("0.005"), c.get(1), "not 0.01 less 0.00, which rounding each amount first would give");
        assertEquals("+0.01", c.get(1).signed());
        assertEquals(Amount.parse("0.001"), c.get(2));
        assertEquals("0.00", c.get(2).signed());
    }

    @SuppressWarnings("unchecked")
    @Test
    void anAmountThatIsNoNumberOrTooFarFromTheOneBeforeItHasNoChange() {
        List<Amount> c = changes(row("14:00:00", "99999999999999.99", ""), row("14:01:00", "0.000001", ""), row("14:02:00", "abc", ""));

        assertEquals(null, c.get(1), "their difference has more digits than an amount holds");
        assertEquals(null, c.get(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theChangeInTheAmountIsAgainstTheLastEarlierRowWithAnAmountAndTheFirstIsTheAmountItself() {
        List<Amount> c = changes(row("14:00:00", "10.00", "start"), row("14:01:00", "10.25", ""), row("14:03:30", "10.20", ""));

        assertEquals(Amount.parse("10.00"), c.get(0), "no earlier amount: the amount itself, also for a start line");
        assertEquals(Amount.parse("0.25"), c.get(1));
        assertEquals(Amount.parse("-0.05"), c.get(2), "a fall is a negative change");
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedRowsAreIgnoredAndHaveNoChangeOfTheirOwn() {
        List<Amount> c = changes(
                row("14:00:00", "10.00", "start"), row("14:01:00", "", "failed"), row("14:02:00", "10.50", ""));

        assertNull(c.get(1));
        assertEquals(Amount.parse("0.50"), c.get(2), "against 14:00, the failed row between them being ignored");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewRunIsComparedWithTheLastRowOfTheRunBefore() {
        List<Amount> c = changes(
                row("14:00:00", "10.00", "start"), row("14:01:00", "10.40", ""), row("15:00:00", "10.60", "start"));

        assertEquals(Amount.parse("0.20"), c.get(2), "the border of a run is no border for the amount");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aFailedStartupRowHasNoChangeAndTheFirstRowWithAnAmountAfterItIsTheAmount() {
        List<Amount> c = changes(row("14:00:00", "", "start-failed"), row("14:01:00", "10.40", ""));

        assertNull(c.get(0));
        assertEquals(Amount.parse("10.40"), c.get(1));
    }
}
