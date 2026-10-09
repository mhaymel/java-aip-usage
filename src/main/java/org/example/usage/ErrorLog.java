package org.example.usage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The errors of this run, newest last in the order they happened and handed out newest first: one for each
 * failed refresh, an HTTP 429 and a problem with the token included. It is kept in memory only. Nothing is
 * written to a file, so it is empty at every start, and it holds the newest {@value #CAPACITY}.
 */
public final class ErrorLog {

    static final int CAPACITY = 1000;

    /** One error: when it happened and the message the row would show for it. */
    public record Entry(Instant at, String message) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(Instant at, String message) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(at, message));
    }

    /** A copy, newest first. */
    public synchronized List<Entry> newestFirst() {
        List<Entry> copy = new ArrayList<>(entries);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
