package org.example.usage;

import org.example.json.Json;
import org.example.json.JsonException;
import org.example.json.JsonReader;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reads the document served by {@code https://api.anthropic.com/api/oauth/usage}.
 *
 * <p>The endpoint is undocumented and unversioned, so this survives a document
 * that grows fields and fails loudly on one whose shape has actually changed,
 * rather than reporting an account as having no usage. Adapted from the
 * {@code java-aip} parser of the same purpose.
 */
public final class UsageParser {

    /**
     * @param body the response body
     * @param fetchedAt when the body was received
     * @throws UsageParseException if the body is not a usage document
     */
    public UsageSnapshot parse(String body, Instant fetchedAt) {
        if (body == null) {
            throw new UsageParseException("The usage response was empty.");
        }
        Json root;
        try {
            root = JsonReader.read(body);
        } catch (JsonException e) {
            throw new UsageParseException(
                    "The usage response is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new UsageParseException("The usage response is not a JSON object.");
        }

        // A real answer always carries a `spend` object, even for an account
        // with nothing to report. Without it this is some other document, for
        // instance an error body.
        if (!isObject(root.get("spend"))) {
            throw new UsageParseException(
                    "The usage response has no \"spend\" object; its format may have changed.");
        }
        return new UsageSnapshot(fetchedAt, spend(root.get("spend")), otherLimits(root));
    }

    /**
     * Whether the response reports limits the program does not show, those of a session or of a week: a list
     * {@code limits} with something in it, or one of the windows {@code five_hour} and {@code seven_day}. With no spend
     * that is the answer of a seat-based account.
     */
    private static boolean otherLimits(Json root) {
        Json limits = root.get("limits");
        return (limits != null && limits.isArray() && limits.size() > 0)
                || isObject(root.get("five_hour")) || isObject(root.get("seven_day"));
    }

    /**
     * {@code spend} reports {@code enabled: false} on an account with no credit
     * ceiling, which is an absent balance rather than a balance of zero.
     */
    private static Spend spend(Json node) {
        Json enabled = node.get("enabled");
        if (enabled == null || !enabled.asBoolean(false)) {
            return null;
        }
        Double used = amount(node.get("used"));
        Double limit = amount(node.get("limit"));
        if (used == null && limit == null) {
            throw new UsageParseException(
                    "The usage response enables spend but reports neither \"used\" nor \"limit\";"
                            + " its format may have changed.");
        }
        Json percent = node.get("percent");
        return new Spend(
                used,
                limit,
                currency(node),
                percent != null && percent.isNumber() ? percent.intValue() : null,
                text(node.get("severity")));
    }

    /**
     * Minor units scaled by their exponent: {@code 18602} at exponent 2 is
     * 186.02. Scaled through {@link BigDecimal} rather than by dividing
     * doubles, so the figure is the one the endpoint meant.
     */
    private static Double amount(Json money) {
        if (!isObject(money) || money.get("amount_minor") == null || !money.get("amount_minor").isNumber()) {
            return null;
        }
        Json exponent = money.get("exponent");
        return BigDecimal.valueOf(money.get("amount_minor").longValue())
                .movePointLeft(exponent == null ? 0 : exponent.asInt(0))
                .doubleValue();
    }

    /** The currency is stated on each amount, and identically on both. */
    private static String currency(Json spend) {
        String used = text(member(spend.get("used"), "currency"));
        return used != null ? used : text(member(spend.get("limit"), "currency"));
    }

    private static boolean isObject(Json node) {
        return node != null && node.isObject();
    }

    /** A member of what may be no object, or not there at all. */
    private static Json member(Json node, String key) {
        return node == null ? null : node.get(key);
    }

    private static String text(Json node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
