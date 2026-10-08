package org.example.usage;

/**
 * One rolling plan window, such as {@code five_hour} or {@code seven_day_opus}.
 *
 * <p>The key is kept exactly as the endpoint spells it: the set of windows is
 * not ours to control, so a display name invented here would be a guess about a
 * field Anthropic may rename or replace. {@code resetsAt} is likewise kept as
 * received, and is {@code null} when the endpoint sends no reset time.
 */
public record UsageWindow(String key, double utilization, String resetsAt) {
}
