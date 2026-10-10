package org.example.usage;

/**
 * What a reading in the seat-based format holds: the five-hour session limit and the
 * weekly limit. A response carries further plan windows; only these two are used.
 *
 * @param fiveHour the session limit, the {@code five_hour} window
 * @param sevenDay the weekly limit, the {@code seven_day} window
 */
public record PlanLimits(Limit fiveHour, Limit sevenDay) {

    /**
     * One limit.
     *
     * @param utilization how much of it is used, in percent; it can be over 100
     * @param resetsAt when it is set back, as the endpoint sent it, or {@code null} if it sent none
     */
    public record Limit(double utilization, String resetsAt) {
    }
}
