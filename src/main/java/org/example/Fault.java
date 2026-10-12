package org.example;

import java.io.IOException;

/**
 * Something that went wrong while reading, writing or starting something, said in the program's own words. Where the
 * program itself sees that a thing cannot be done, it throws this, so that what reaches the window and the log is a
 * text the program wrote and not one of the system's.
 */
public final class Fault extends IOException {

    public Fault(String message) {
        super(message);
    }

    public Fault(String message, Throwable cause) {
        super(message, cause);
    }
}
