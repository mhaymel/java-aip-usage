package org.example.settings;

/** A setting that is refused: its value is not one it may have. */
public final class InvalidSettingException extends RuntimeException {

    /** The key of the setting that is refused, or {@code null} when it is none's or not known (since 0.89). */
    private final String field;

    public InvalidSettingException(String message) {
        this(message, null);
    }

    /** A refusal of the setting {@code field}, which the page puts the focus on (since 0.89). */
    public InvalidSettingException(String message, String field) {
        super(message);
        this.field = field;
    }

    /** The key of the setting that is refused, or {@code null}. */
    public String field() {
        return field;
    }
}
