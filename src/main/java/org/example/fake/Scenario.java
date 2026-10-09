package org.example.fake;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What the fake backend answers. The names here are the ones {@code --fake-scenario}
 * takes, so this enum is the only list of them: the command line validates against it
 * and names its members in the message it prints for a name it does not know, and the
 * server reads it to decide what to send. A scenario added here is usable at once and
 * cannot be missing from the message.
 */
public enum Scenario {

    /** A plausible usage document whose figures move a little on each request. */
    NORMAL("normal"),

    HTTP_401("http-401"),

    HTTP_403("http-403"),

    HTTP_429("http-429"),

    /** An HTTP 429 that also says how long to wait, which the back-off must honour. */
    HTTP_429_RETRY_AFTER("http-429-retry-after"),

    HTTP_500("http-500"),

    NOT_JSON("not-json"),

    EMPTY("empty"),

    /** A JSON object that is some other document: no {@code spend}, no window. */
    NO_SPEND_NO_WINDOWS("no-spend-no-windows"),

    /** A usage document with text after it, which a strict reader must refuse. */
    TRAILING_TEXT("trailing-text"),

    /** Answers later than the request timeout allows. */
    SLOW("slow"),

    /** Never answers at all. */
    HANG("hang");

    private final String optionName;

    Scenario(String optionName) {
        this.optionName = optionName;
    }

    /** The name on the command line, lower-case with hyphens. */
    public String optionName() {
        return optionName;
    }

    /** Every name, in order, for a message that has to list them. */
    public static String names() {
        return Arrays.stream(values()).map(Scenario::optionName).collect(Collectors.joining(", "));
    }

    /** The scenario of that name, whatever its case, or empty if there is none. */
    public static Optional<Scenario> ofOptionName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.optionName.equals(wanted)).findFirst();
    }

    @Override
    public String toString() {
        return optionName;
    }
}
