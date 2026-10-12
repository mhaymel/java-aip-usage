package org.example.money;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The program's own amounts of money: exact decimals, rounded only where they are written. The figures here are the
 * ones the program has always shown and written, from when {@code BigDecimal} worked them out.
 */
class AmountTest {

    // ---- reading

    @ParameterizedTest
    @CsvSource({
        "186.02, 186.02", "1000, 1000.0", "1000.00, 1000.0", "0, 0.0", "0.00, 0.0", "-0.05, -0.05", "+7.25, 7.25", "007.250, 7.25",
        ".5, 0.5", "5., 5.0", "1.005, 1.005", "1e3, 1000.0", "1.5e3, 1500.0", "1.5E-3, 0.0015", "-0, 0.0", "0.000000000000000001, 0.000000000000000001"})
    void anAmountIsReadAsItIsWrittenAndKeepsEveryDecimal(String written, String exactly) {
        assertEquals(exactly, Amount.parse(written).number());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", ".", "-", "+", "abc", "1,000.00", "1 000", " 1", "1 ", "1.2.3", "--1", "1e", "e3", "0x10", "NaN", "Infinity", "$5",
        "12345678901234567890", "0.0000000000000000001", "1e30", "1e1000"})
    void whatIsNoDecimalNumberOrHasMoreDigitsThanAnAmountHoldsIsRefused(String written) {
        assertThrows(NumberFormatException.class, () -> Amount.parse(written), written);
    }

    @Test
    void noTextIsNoAmount() {
        assertThrows(NumberFormatException.class, () -> Amount.parse(null));
    }

    @Test
    void theSmallestUnitOfACurrencyIsScaledByItsCountOfDecimals() {
        assertEquals("186.02", Amount.ofMinor(18602, 2).number());
        assertEquals("500.0", Amount.ofMinor(500, 0).number(), "a currency with no decimals");
        assertEquals("18.602", Amount.ofMinor(18602, 3).number());
        assertEquals("1860200.0", Amount.ofMinor(18602, -2).number());
        assertEquals("0.01", Amount.ofMinor(1, 2).number());
        assertEquals(Amount.parse("186.02"), Amount.ofCents(18602));
        assertSame(Amount.ZERO, Amount.ofCents(0));
    }

    @Test
    void anAmountBeyondWhatCanBeWrittenInCentsIsRefused() {
        assertThrows(ArithmeticException.class, () -> Amount.ofMinor(Long.MAX_VALUE, 0));
        assertThrows(ArithmeticException.class, () -> Amount.ofMinor(1, 19));
        assertThrows(ArithmeticException.class, () -> Amount.ofMinor(1, -19));
        assertThrows(ArithmeticException.class, () -> Amount.ofMinor(1_000_000_000_000L, -9));
        assertEquals("92233720368547758.07", Amount.ofCents(Long.MAX_VALUE).plain(), "the largest there is");
    }

    // ---- writing

    @Test
    void anAmountIsWrittenWithTwoDecimalsAndACommaForThousands() {
        assertEquals("186.02", Amount.parse("186.02").grouped());
        assertEquals("1,000.00", Amount.parse("1000").grouped());
        assertEquals("1,234,567.89", Amount.parse("1234567.891").grouped());
        assertEquals("999.00", Amount.parse("999").grouped());
        assertEquals("0.00", Amount.ZERO.grouped());
        assertEquals("-1,000.50", Amount.parse("-1000.5").grouped());
        assertEquals("1000.00", Amount.parse("1000").plain(), "and without, for the files");
        assertEquals("0.05", Amount.parse("0.05").plain());
        assertEquals("-0.05", Amount.parse("-0.05").plain());
    }

    @ParameterizedTest
    @CsvSource({
        "0.125, 0.13", "2.675, 2.68", "1.005, 1.01", "186.024, 186.02", "186.025, 186.03", "999.995, 1000.00", "999.994, 999.99",
        "0.005, 0.01", "0.004, 0.00", "0.0049999, 0.00", "0.0050001, 0.01", "-0.005, -0.01", "-0.004, 0.00", "-1.005, -1.01"})
    void aThirdDecimalIsRoundedOnTheDecimalFormAndAwayFromZeroAtHalf(String written, String rounded) {
        assertEquals(rounded, Amount.parse(written).plain());
    }

    @Test
    void aChangeIsWrittenWithItsSignAndNoneForWhatRoundsToNothing() {
        assertEquals("+0.05", Amount.parse("0.05").signed());
        assertEquals("-0.05", Amount.parse("-0.05").signed());
        assertEquals("0.00", Amount.ZERO.signed());
        assertEquals("0.00", Amount.parse("0.004").signed());
        assertEquals("0.00", Amount.parse("-0.004").signed(), "no sign on nothing");
        assertEquals("+0.01", Amount.parse("0.005").signed());
        assertEquals("+1,234.50", Amount.parse("1234.5").signed());
    }

    @Test
    void asANumberOfJsonAnAmountIsExactAndHasOneDecimalAtLeast() {
        assertEquals("186.02", Amount.parse("186.02").number());
        assertEquals("1000.0", Amount.parse("1000.00").number());
        assertEquals("0.0", Amount.ZERO.number());
        assertEquals("-0.05", Amount.parse("-0.05").number());
        assertEquals("1.005", Amount.parse("1.005").number(), "not rounded");
        assertEquals("10000000.0", Amount.parse("10000000").number(), "never with an exponent");
        assertEquals("186.02", Amount.parse("186.02").toString());
    }

    @Test
    void theCentsOfAnAmountAreItsRoundedOnesOrItsExactOnes() {
        assertEquals(18602, Amount.parse("186.02").cents());
        assertEquals(101, Amount.parse("1.005").cents());
        assertEquals(-101, Amount.parse("-1.005").cents());
        assertEquals(18602, Amount.parse("186.02").exactCents());
        assertEquals(25000, Amount.parse("250").exactCents());
        assertEquals(1230, Amount.parse("12.300").exactCents(), "zeros at the end are no decimals");
        assertEquals(100000, Amount.parse("1e3").exactCents());
        assertThrows(ArithmeticException.class, () -> Amount.parse("12.345").exactCents());
    }

    // ---- reckoning

    @Test
    void amountsAreAddedAndSubtractedExactlyWhateverTheirDecimals() {
        assertEquals("0.25", Amount.parse("10.25").minus(Amount.parse("10.00")).number());
        assertEquals("-0.05", Amount.parse("10.20").minus(Amount.parse("10.25")).number());
        assertEquals("0.3", Amount.parse("0.1").plus(Amount.parse("0.2")).number(), "which a binary fraction does not make");
        assertEquals("0.005", Amount.parse("1.005").minus(Amount.parse("1")).number());
        assertEquals("0.001", Amount.parse("1.005").minus(Amount.parse("1.004")).number());
        assertSame(Amount.ZERO, Amount.parse("5.50").minus(Amount.parse("5.5")));
        assertEquals("0.01", Amount.parse("1.005").minus(Amount.parse("1")).plain(), "and rounded only when it is written");
    }

    @Test
    void aSumThatNeedsMoreDigitsThanAnAmountHoldsIsRefusedNotRounded() {
        assertThrows(ArithmeticException.class, () -> Amount.parse("99999999999999.99").minus(Amount.parse("0.000001")));
        assertThrows(ArithmeticException.class, () -> Amount.ofCents(Long.MAX_VALUE).plus(Amount.ofCents(1)));
    }

    @Test
    void amountsAreComparedByWhatTheyAreNotByHowTheyWereWritten() {
        assertEquals(Amount.parse("1.5"), Amount.parse("1.50"));
        assertEquals(Amount.parse("1.5").hashCode(), Amount.parse("1.50").hashCode());
        assertNotEquals(Amount.parse("1.5"), Amount.parse("1.51"));
        assertTrue(Amount.parse("100.01").compareTo(Amount.parse("100")) > 0);
        assertTrue(Amount.parse("-0.01").compareTo(Amount.ZERO) < 0);
        assertEquals(0, Amount.parse("2").compareTo(Amount.parse("2.000")));
        assertTrue(Amount.parse("99999999999999.99").compareTo(Amount.parse("0.000000000000000001")) > 0, "even where they share no form");
        assertEquals(Amount.ZERO, Amount.parse("-3").max(Amount.ZERO));
        assertEquals(Amount.parse("3"), Amount.parse("3").max(Amount.ZERO));
        assertEquals(1, Amount.parse("0.001").signum());
        assertEquals(-1, Amount.parse("-0.001").signum());
        assertEquals(0, Amount.parse("0.000").signum());
    }

    @ParameterizedTest
    @CsvSource({
        "186.02, 1000, 19", "186.66, 1000, 19", "184.99, 1000, 18", "14.50, 100, 15", "14.49, 100, 14", "0, 250, 0", "250, 250, 100",
        "300, 250, 120", "1, 3, 33", "2, 3, 67", "0.005, 1, 1", "0.0049, 1, 0", "-14.50, 100, -15"})
    void theShareOfALimitAsAPercentageIsRoundedToTheNearestAndUpwardsAtHalf(String amount, String whole, long percent) {
        assertEquals(percent, Amount.parse(amount).shareOf(Amount.parse(whole), 2));
    }

    @Test
    void theShareCanBeKeptToMoreDecimals() {
        assertEquals(186_020, Amount.parse("186.02").shareOf(Amount.parse("1000"), 6));
        assertEquals(333_333, Amount.parse("1").shareOf(Amount.parse("3"), 6));
        assertEquals(666_667, Amount.parse("2").shareOf(Amount.parse("3"), 6));
        assertEquals(1_200_000, Amount.parse("300").shareOf(Amount.parse("250"), 6));
        assertEquals(2, Amount.parse("5").shareOf(Amount.parse("3"), 0));
        assertThrows(ArithmeticException.class, () -> Amount.parse("5").shareOf(Amount.ZERO, 2));
    }
}
