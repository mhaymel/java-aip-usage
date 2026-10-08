package org.example;

import java.util.regex.Pattern;

/**
 * Removes anything shaped like a credential from text on its way to a log.
 *
 * <p>This is the last line of defence, not the first: the code is written so
 * that tokens and response bodies never reach a log message at all. It exists
 * because a message built from an exception or a subprocess's output is not
 * under this code's control.
 */
public final class Redaction {

    static final String MASK = "[redacted]";

    private static final Pattern ANTHROPIC_KEY = Pattern.compile("sk-ant-[A-Za-z0-9_-]+");

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    /** {@code x-api-key: value}, {@code "access_token": "value"}, {@code password=value} and the like. */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(x-api-key|authorization|api[-_]?key|access[-_]?token|refresh[-_]?token|password|secret)"
                    + "([\"']?\\s*[:=]\\s*[\"']?)(?:bearer\\s+)?[^\\s\"',}]+");

    private Redaction() {
    }

    public static String redact(String text) {
        String redacted = ANTHROPIC_KEY.matcher(text).replaceAll(MASK);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
        return NAMED_SECRET.matcher(redacted).replaceAll("$1$2" + MASK);
    }
}
