package org.example.fake;

import java.util.Arrays;
import java.util.Optional;

/**
 * How the ordinary answer of the fake backend is shaped, which the debug settings say: what kind of account it stands
 * for, what the account has spent and may spend, by how much the spending grows, when a request is refused for coming
 * too soon, and how long an answer takes. They are for the scenario {@code normal}; the other scenarios are fixed.
 *
 * @param account the kind of account the answer is of
 * @param increaseCents the most the amount used grows by at one answer, in cents; it grows by a whole number of cents
 *     drawn evenly from 0 to this
 * @param budgetCents the spend limit the account reports, in cents
 * @param startCents the amount used the account begins with, in cents
 * @param refuseSeconds a request that comes less than this long after the answered one before it gets an HTTP 429; 0
 *     never refuses
 * @param delayMillis the longest an answer is held back, in milliseconds; each is held for a time drawn evenly from 0
 *     to this
 */
public record FakeOptions(Account account, int increaseCents, long budgetCents, long startCents, int refuseSeconds, int delayMillis) {

    /** The kind of account the fake backend answers for. */
    public enum Account {

        /** Billed by usage: the answer has spend, an amount used that grows and a budget. */
        USAGE("usage"),

        /** Billed by seat: the answer has no spend, and the limits of a session and of a week. */
        SEAT("seat");

        private final String json;

        Account(String json) {
            this.json = json;
        }

        /** The form it has in the settings file and the API. */
        public String json() {
            return json;
        }

        public static Optional<Account> fromJson(String text) {
            return Arrays.stream(values()).filter(account -> account.json.equals(text)).findFirst();
        }

        public static String names() {
            return String.join(", ", Arrays.stream(values()).map(Account::json).toList());
        }
    }

    public static final int MAX_INCREASE_CENTS = 100_000;

    public static final long MAX_BUDGET_CENTS = 100_000_000L;

    public static final int MAX_REFUSE_SECONDS = 3_600;

    public static final int MAX_DELAY_MILLIS = 60_000;

    /**
     * What the fake backend did before it could be shaped: an account billed by usage that begins at 186.02 of 1,000.00
     * and grows by at most 1.37, never refuses and answers at once.
     */
    public static final FakeOptions DEFAULTS = new FakeOptions(Account.USAGE, 137, 100_000, 18_602, 0, 0);

    /**
     * Why these cannot be the options, in words for the person who set them, or nothing when they can.
     */
    public Optional<String> fault() {
        if (increaseCents < 0 || increaseCents > MAX_INCREASE_CENTS) {
            return Optional.of("The largest increase must be a whole number of cents from 0 to " + MAX_INCREASE_CENTS + ".");
        }
        if (budgetCents < 1 || budgetCents > MAX_BUDGET_CENTS) {
            return Optional.of("The budget must be an amount from 0.01 to 1,000,000.");
        }
        if (startCents < 0 || startCents > budgetCents) {
            return Optional.of("The starting amount must be an amount from 0 to the budget.");
        }
        if (refuseSeconds < 0 || refuseSeconds > MAX_REFUSE_SECONDS) {
            return Optional.of("The time within which a request is refused must be a whole number of seconds from 0 to " + MAX_REFUSE_SECONDS + ".");
        }
        if (delayMillis < 0 || delayMillis > MAX_DELAY_MILLIS) {
            return Optional.of("The longest request must be a whole number of milliseconds from 0 to " + MAX_DELAY_MILLIS + ".");
        }
        return Optional.empty();
    }
}
