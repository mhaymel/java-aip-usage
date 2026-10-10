package org.example.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

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
     * No plan window, although it holds a {@code utilization}: it restates the credit
     * figures that {@code spend} already reports, and a response of the usage-based
     * format carries it.
     */
    private static final String RESTATES_SPEND = "extra_usage";

    /** The two plan windows a reading in the seat-based format is made of; every other window is passed over. */
    private static final String FIVE_HOUR = "five_hour";

    private static final String SEVEN_DAY = "seven_day";

    /** What a seat-based response without both of them is refused with: half a reading is never shown. */
    static final String SEAT_BASED_INCOMPLETE =
            "The usage response is in the seat-based format but lacks \"five_hour\" or \"seven_day\";"
                    + " its format may have changed.";

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

        boolean planWindows = hasPlanWindows(root);
        // A real answer always carries a `spend` object, even for an account
        // with nothing to report. With neither it, nor a window, this is some
        // other document, for instance an error body.
        if (!root.path("spend").isObject() && !planWindows) {
            throw new UsageParseException(
                    "The usage response has neither a \"spend\" object nor any usage windows;"
                            + " its format may have changed.");
        }
        // One plan window makes it the seat-based format, with spend beside it or without,
        // and then the spend is ignored: it is not read, so nothing in it can fail the reading.
        if (planWindows) {
            PlanLimits.Limit fiveHour = limit(root.get(FIVE_HOUR));
            PlanLimits.Limit sevenDay = limit(root.get(SEVEN_DAY));
            if (fiveHour == null || sevenDay == null) {
                throw new UsageParseException(SEAT_BASED_INCOMPLETE);
            }
            return UsageSnapshot.seatBased(fetchedAt, new PlanLimits(fiveHour, sevenDay));
        }
        return new UsageSnapshot(fetchedAt, spend(root.get("spend")));
    }

    /** One of the two limits, or {@code null} if the document has no such window. A missing reset time is no fault. */
    private static PlanLimits.Limit limit(JsonNode window) {
        if (window == null || !window.isObject() || !window.path("utilization").isNumber()) {
            return null;
        }
        return new PlanLimits.Limit(window.get("utilization").doubleValue(), text(window.get("resets_at")));
    }

    /**
     * Whether the document carries a plan window, which is what tells the seat-based
     * format from the usage-based one. A window is known by its shape, not by its name:
     * any member holding an object with a numeric {@code utilization}. The document
     * carries a long tail of further keys, several evidently placeholders, and naming
     * the known ones would miss any window Anthropic adds. A key that is {@code null},
     * as the usage-based format has them, is none.
     */
    private static boolean hasPlanWindows(JsonNode root) {
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (RESTATES_SPEND.equals(field.getKey())) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value.isObject() && value.path("utilization").isNumber()) {
                return true;
            }
        }
        return false;
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
