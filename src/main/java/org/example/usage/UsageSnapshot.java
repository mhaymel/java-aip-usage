package org.example.usage;

import java.time.Instant;

/**
 * One reading of the usage endpoint: the spend of the account, or none.
 *
 * <p>An account may report no spend at all, so {@link #isEmpty()} is a legitimate
 * answer rather than a failure.
 *
 * <p>{@code fetchedAt} is when this application received the response; the
 * endpoint does not report it.
 *
 * @param otherLimits whether the response reports limits of a session or of a week, which the program does not show: with
 *     no spend, that is the answer of a seat-based account
 */
public record UsageSnapshot(Instant fetchedAt, Spend spend, boolean otherLimits) {

    /** What the row and the log say of an answer with no spend that has the limits of a seat-based account. */
    public static final String NO_SPEND_SEAT_BASED =
            "Received, but this account reports no spend: it looks like a seat-based account, which is not supported yet.";

    /** What they say of an answer with no spend and nothing else the program knows. */
    public static final String NO_SPEND = "Received, but Anthropic reports no spend for this account (spend is not enabled).";

    /** A reading that says nothing of other limits: one from the history, or of a test. */
    public UsageSnapshot(Instant fetchedAt, Spend spend) {
        this(fetchedAt, spend, false);
    }

    public boolean isEmpty() {
        return spend == null;
    }

    /**
     * What is to be said of this reading when it has no spend: the response was received and holds nothing the program
     * can show. {@code null} for a reading with spend.
     */
    public String noSpendMessage() {
        if (spend != null) {
            return null;
        }
        return otherLimits ? NO_SPEND_SEAT_BASED : NO_SPEND;
    }
}
