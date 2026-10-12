package org.example.json;

import java.io.IOException;

/**
 * A text that is not JSON. The message says where and what, in the program's own words:
 * {@code line 3, column 14: a value was expected}.
 *
 * <p>It is an {@link IOException} so that what reads a file and its JSON in one step has one failure to deal with.
 */
public final class JsonException extends IOException {

    private final String what;

    JsonException(int line, int column, String what) {
        super("line " + line + ", column " + column + ": " + what);
        this.what = what;
    }

    /** What is wrong, without the place. */
    public String what() {
        return what;
    }
}
