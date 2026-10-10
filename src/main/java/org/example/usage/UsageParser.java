package org.example.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * @param body the response body
     * @param fetchedAt when the body was received
     * @throws UsageParseException if the body is not a usage document
     */
    public UsageSnapshot parse(String body, Instant fetchedAt) {
        if (body == null) {
            throw new UsageParseException("The usage response was empty.");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new UsageParseException(
                    "The usage response is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new UsageParseException("The usage response is not a JSON object.");
        }

        // A real answer always carries a `spend` object, even for an account
        // with nothing to report. Without it this is some other document, for
        // instance an error body.
        if (!root.path("spend").isObject()) {
            throw new UsageParseException(
                    "The usage response has no \"spend\" object; its format may have changed.");
        }
        return new UsageSnapshot(fetchedAt, spend(root.get("spend")));
    }

    /**
     * {@code spend} reports {@code enabled: false} on an account with no credit
     * ceiling, which is an absent balance rather than a balance of zero.
     */
    private static Spend spend(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("enabled").asBoolean(false)) {
            return null;
        }
        Double used = amount(node.get("used"));
        Double limit = amount(node.get("limit"));
        if (used == null && limit == null) {
            throw new UsageParseException(
                    "The usage response enables spend but reports neither \"used\" nor \"limit\";"
                            + " its format may have changed.");
        }
        JsonNode percent = node.get("percent");
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
    private static Double amount(JsonNode money) {
        if (money == null || !money.isObject() || !money.path("amount_minor").isNumber()) {
            return null;
        }
        int exponent = money.path("exponent").asInt(0);
        return BigDecimal.valueOf(money.get("amount_minor").longValue())
                .movePointLeft(exponent)
                .doubleValue();
    }

    /** The currency is stated on each amount, and identically on both. */
    private static String currency(JsonNode spend) {
        String used = text(spend.path("used").get("currency"));
        return used != null ? used : text(spend.path("limit").get("currency"));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
