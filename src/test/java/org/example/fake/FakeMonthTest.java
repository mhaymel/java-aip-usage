package org.example.fake;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeMonthTest {

    /** Wednesday, 14 October 2026: the 1st to the 13th have nine working days, and the fifth of them, Oct 7, is left out. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void readingsEveryThreeHoursOnTheWorkingDaysBeforeTodayEveryFifthLeftOut() {
        List<FakeMonth.Reading> readings = FakeMonth.readings(18_602, TODAY);

        assertEquals(8 * 4, readings.size(), "eight working days of four readings");
        assertTrue(readings.stream().allMatch(r -> r.at().toLocalDate().isBefore(TODAY)), "none today or after");
        assertTrue(readings.stream().noneMatch(r -> r.at().getDayOfWeek() == DayOfWeek.SATURDAY || r.at().getDayOfWeek() == DayOfWeek.SUNDAY));
        assertTrue(readings.stream().noneMatch(r -> r.at().toLocalDate().equals(LocalDate.of(2026, 10, 7))), "a day bridged");
        assertEquals(List.of(LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(14, 0), LocalTime.of(17, 0)),
                readings.subList(0, 4).stream().map(r -> r.at().toLocalTime()).toList());
    }

    @Test
    void theAmountRisesToALittleUnderWhatTheFakeBackendBeginsWith() {
        List<FakeMonth.Reading> readings = FakeMonth.readings(18_602, TODAY);

        for (int i = 1; i < readings.size(); i++) {
            assertTrue(readings.get(i).usedCents() > readings.get(i - 1).usedCents(), "it rises at every reading");
        }
        assertEquals(18_602 * 98 / 100, readings.get(readings.size() - 1).usedCents());
        assertEquals(readings, FakeMonth.readings(18_602, TODAY), "the same month gives the same readings");
    }

    @Test
    void noneOnTheFirstOrWithNothingToSpend() {
        assertEquals(List.of(), FakeMonth.readings(18_602, LocalDate.of(2026, 10, 1)));
        assertEquals(List.of(), FakeMonth.readings(0, TODAY));
    }
}
