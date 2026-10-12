package org.example.money;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An amount of money as a decimal number that is exact: a whole number and the count of its decimals, so that
 * {@code 186.02} is 18602 with 2 and {@code 1.005} is 1005 with 3. Nothing is held as a binary fraction, so no figure
 * is a hair beside the one that was written, and an amount keeps every decimal it was read with; only what is shown
 * or written is rounded, to the cent and upwards at half.
 *
 * <p>Everything the program does with money is here: reading an amount, adding and subtracting, the share of a limit,
 * and the forms it is written in. It holds what a {@code long} holds, with 18 decimals at most; more is refused
 * where it is read.
 */
public final class Amount implements Comparable<Amount> {

    public static final Amount ZERO = new Amount(0, 0);

    /** The most decimals an amount has: as many as a {@code long} has whole digits. */
    static final int MAX_DECIMALS = 18;

    /** A decimal number as it is written: digits with a point among them at most, a sign before, an exponent after. */
    private static final Pattern WRITTEN = Pattern.compile("([+-]?)([0-9]*)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]{1,3}))?");

    private static final long[] POWERS = new long[MAX_DECIMALS + 1];

    static {
        POWERS[0] = 1;
        for (int i = 1; i < POWERS.length; i++) {
            POWERS[i] = POWERS[i - 1] * 10;
        }
    }

    /** The amount without its point: 18602 for 186.02. Never ends in a zero while there are decimals. */
    private final long digits;

    /** How many of the digits are decimals, from 0 to {@link #MAX_DECIMALS}. */
    private final int decimals;

    private Amount(long digits, int decimals) {
        this.digits = digits;
        this.decimals = decimals;
    }

    /**
     * The amount of these digits and decimals, with the zeros at its end taken off: 1.50 is 1.5, which is the same amount.
     *
     * @throws ArithmeticException if the amount is more whole cents than a {@code long} holds, so that every amount
     *     there is can be written in cents
     */
    private static Amount of(long digits, int decimals) {
        while (decimals > 0 && digits % 10 == 0) {
            digits /= 10;
            decimals--;
        }
        if (digits == 0) {
            return ZERO;
        }
        if (decimals < 2) {
            Math.multiplyExact(digits, POWERS[2 - decimals]);
        }
        return new Amount(digits, decimals);
    }

    // ---- making an amount

    /** An amount of whole cents: 18602 is 186.02. */
    public static Amount ofCents(long cents) {
        return of(cents, 2);
    }

    /**
     * An amount in the smallest unit of its currency, with the count of decimals that unit has: 18602 and 2 are 186.02;
     * 500 and 0 are 500.
     *
     * @throws ArithmeticException if the amount is more than this class holds
     */
    public static Amount ofMinor(long minor, int exponent) {
        if (exponent < -MAX_DECIMALS || exponent > MAX_DECIMALS) {
            throw new ArithmeticException("An exponent of " + exponent + " is more than an amount holds");
        }
        return exponent >= 0 ? of(minor, exponent) : of(Math.multiplyExact(minor, POWERS[-exponent]), 0);
    }

    /**
     * An amount as it is written: {@code 186.02}, {@code 1000}, {@code -0.05}, {@code .5}, {@code 1.5e3}. Nothing is
     * rounded.
     *
     * @throws NumberFormatException if the text is not a decimal number, or has more digits than this class holds
     */
    public static Amount parse(String text) {
        Matcher written = text == null ? null : WRITTEN.matcher(text);
        if (written == null || !written.matches()) {
            throw new NumberFormatException("Not an amount: " + text);
        }
        String whole = written.group(2);
        String fraction = written.group(3) == null ? "" : written.group(3);
        if (whole.isEmpty() && fraction.isEmpty()) {
            throw new NumberFormatException("Not an amount: " + text);
        }
        // The zeros at the end of the decimals say nothing; without them 1.500000000000000000000 is the 1.5 it is.
        int end = fraction.length();
        while (end > 0 && fraction.charAt(end - 1) == '0') {
            end--;
        }
        fraction = fraction.substring(0, end);
        int decimals = fraction.length() - (written.group(4) == null ? 0 : Integer.parseInt(written.group(4)));
        try {
            // Too many digits for a long is a NumberFormatException of its own.
            long digits = (whole + fraction).isEmpty() ? 0 : Long.parseLong(written.group(1) + whole + fraction);
            if (digits == 0) {
                return ZERO;
            }
            if (decimals < 0) {
                if (decimals < -MAX_DECIMALS) {
                    throw new NumberFormatException("Too large for an amount: " + text);
                }
                return of(Math.multiplyExact(digits, POWERS[-decimals]), 0);
            }
            Amount amount = of(digits, decimals);
            if (amount.decimals > MAX_DECIMALS) {
                throw new NumberFormatException("Too many decimals for an amount: " + text);
            }
            return amount;
        } catch (ArithmeticException e) {
            throw new NumberFormatException("Too large for an amount: " + text);
        }
    }

    // ---- reckoning

    /** @throws ArithmeticException if the sum is more than this class holds */
    public Amount plus(Amount other) {
        int common = Math.max(decimals, other.decimals);
        return of(Math.addExact(raised(common), other.raised(common)), common);
    }

    /** @throws ArithmeticException if the difference is more than this class holds */
    public Amount minus(Amount other) {
        int common = Math.max(decimals, other.decimals);
        return of(Math.subtractExact(raised(common), other.raised(common)), common);
    }

    /** The larger of the two. */
    public Amount max(Amount other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /** -1, 0 or 1 as the amount is below zero, zero or above. */
    public int signum() {
        return Long.signum(digits);
    }

    @Override
    public int compareTo(Amount other) {
        int common = Math.max(decimals, other.decimals);
        try {
            return Long.compare(raised(common), other.raised(common));
        } catch (ArithmeticException e) {
            // Too far apart to be written with the same decimals, so far enough apart for this.
            return Double.compare(approximately(), other.approximately());
        }
    }

    /**
     * This amount as a share of another, as a whole number: with 2 decimals the percentage, 19 for 186.02 of 1000; with
     * 6 the millionths. Rounded to the nearest, upwards at half.
     *
     * @param decimals how many decimals of the share are kept, from 0 to 9
     * @throws ArithmeticException if the other amount is zero
     */
    public long shareOf(Amount whole, int decimals) {
        if (whole.digits == 0) {
            throw new ArithmeticException("A share of nothing");
        }
        boolean negative = (digits < 0) != (whole.digits < 0);
        try {
            // Both as whole numbers of the same unit, then divided by hand, digit after digit.
            int common = Math.max(this.decimals, whole.decimals);
            long dividend = Math.absExact(raised(common));
            long divisor = Math.absExact(whole.raised(common));
            long share = dividend / divisor;
            long rest = dividend % divisor;
            for (int i = 0; i < decimals; i++) {
                rest = Math.multiplyExact(rest, 10L);
                share = Math.addExact(Math.multiplyExact(share, 10L), rest / divisor);
                rest %= divisor;
            }
            if (rest >= divisor - rest) {
                share = Math.addExact(share, 1);
            }
            return negative ? -share : share;
        } catch (ArithmeticException e) {
            // Amounts beyond any budget: near enough is all there is to say of their share.
            return Math.round(approximately() / whole.approximately() * POWERS[decimals]);
        }
    }

    /** The amount in whole cents, rounded to the nearest, away from zero at half: 1.005 is 101, -1.005 is -101. */
    public long cents() {
        if (decimals <= 2) {
            return digits * POWERS[2 - decimals];
        }
        long divisor = POWERS[decimals - 2];
        long cents = digits / divisor;
        long rest = Math.abs(digits % divisor);
        if (rest >= divisor - rest) {
            cents += digits < 0 ? -1 : 1;
        }
        return cents;
    }

    /**
     * The amount in whole cents, for an amount that is one.
     *
     * @throws ArithmeticException if it has more than two decimals, or is more cents than a {@code long} holds
     */
    public long exactCents() {
        if (decimals > 2) {
            throw new ArithmeticException("More than two decimals: " + number());
        }
        return Math.multiplyExact(digits, POWERS[2 - decimals]);
    }

    // ---- writing an amount

    /** With two decimals and nothing else, rounded to the cent: {@code 186.02}, {@code 1000.00}, {@code -0.05}. */
    public String plain() {
        return written(cents(), false);
    }

    /** As {@link #plain()}, with a comma after every three digits of the whole part: {@code 1,000.00}. */
    public String grouped() {
        return written(cents(), true);
    }

    /** As {@link #grouped()}, with the sign of a change: {@code +0.05}, {@code -0.05}, and {@code 0.00} for what rounds to nothing. */
    public String signed() {
        long cents = cents();
        String text = written(Math.abs(cents), true);
        return cents > 0 ? "+" + text : cents < 0 ? "-" + text : text;
    }

    /**
     * The amount as a number of JSON, exactly and never rounded, with one decimal at least so that it is seen to be a
     * number with decimals: {@code 186.02}, {@code 1000.0}, {@code -0.05}, {@code 0.0}.
     */
    public String number() {
        String all = Long.toString(Math.abs(digits));
        if (all.length() <= decimals) {
            all = "0".repeat(decimals - all.length() + 1) + all;
        }
        String whole = all.substring(0, all.length() - decimals);
        String fraction = decimals == 0 ? "0" : all.substring(all.length() - decimals);
        return (digits < 0 ? "-" : "") + whole + "." + fraction;
    }

    private static String written(long cents, boolean grouped) {
        long absolute = Math.abs(cents);
        String whole = Long.toString(absolute / 100);
        if (grouped) {
            StringBuilder with = new StringBuilder(whole);
            for (int at = whole.length() - 3; at > 0; at -= 3) {
                with.insert(at, ',');
            }
            whole = with.toString();
        }
        long fraction = absolute % 100;
        return (cents < 0 ? "-" : "") + whole + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    /** The digits of this amount written with more decimals. */
    private long raised(int toDecimals) {
        return Math.multiplyExact(digits, POWERS[toDecimals - decimals]);
    }

    /** The amount as a binary fraction, which is near it and seldom it; for the cases that are beyond exact reckoning only. */
    private double approximately() {
        return digits / (double) POWERS[decimals];
    }

    // ---- as an object of Java

    /** Two amounts are equal when they are the same amount, however they were written: 1.5 and 1.50 are. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Amount that && digits == that.digits && decimals == that.decimals;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(digits) * 31 + decimals;
    }

    /** As {@link #number()}. */
    @Override
    public String toString() {
        return number();
    }
}
