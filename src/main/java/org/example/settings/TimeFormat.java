package org.example.settings;

import java.util.Arrays;
import java.util.Optional;

/** How the time of day is cut in the row and its messages: the form it has in the settings file and the API. */
public enum TimeFormat {

    HOURS_MINUTES("hh:mm"),
    HOURS_MINUTES_SECONDS("hh:mm:ss");

    private final String json;

    TimeFormat(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    public static Optional<TimeFormat> fromJson(String text) {
        return Arrays.stream(values()).filter(format -> format.json.equals(text)).findFirst();
    }
}
