package org.example.usage;

import java.util.function.BooleanSupplier;

/**
 * Whether the JSON of each response is written to the log, which the settings switch while the program runs.
 * Off until told otherwise.
 */
public final class ResponseLog implements BooleanSupplier {

    private volatile boolean on;

    public void set(boolean on) {
        this.on = on;
    }

    @Override
    public boolean getAsBoolean() {
        return on;
    }
}
