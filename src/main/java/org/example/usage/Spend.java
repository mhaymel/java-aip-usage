package org.example.usage;

import org.example.money.Amount;

/**
 * The credit balance of an account: what it has spent against the
 * ceiling it was given.
 *
 * <p>The endpoint sends amounts as minor units with an exponent ({@code 18602}
 * and {@code 2}). They are held here already scaled, so only {@link
 * UsageParser} has to know about the wire form. Every member may be absent.
 */
public record Spend(Amount used, Amount limit, String currency, Integer percent, String severity) {
}
