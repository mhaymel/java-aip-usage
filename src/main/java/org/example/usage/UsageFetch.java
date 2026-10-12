package org.example.usage;

import org.example.Cancel;

import java.util.function.Supplier;

/** Produces one usage reading, and gives up when it is told to. */
@FunctionalInterface
public interface UsageFetch {

    /**
     * @param cancel told when the reading is no longer wanted: the program is stopping, or usage now comes from
     *     somewhere else
     * @throws org.example.token.TokenException if no token can be obtained
     * @throws UsageFetchException if the request fails, is refused or was given up
     * @throws UsageParseException if the response is not a usage document
     */
    UsageSnapshot fetch(Cancel cancel);

    /** A fetch that cannot be told to give up: it ends when it ends. */
    static UsageFetch of(Supplier<UsageSnapshot> supplier) {
        return cancel -> supplier.get();
    }
}
