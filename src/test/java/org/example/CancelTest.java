package org.example;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling something under way to give up, which takes the place of interrupting a thread. */
class CancelTest {

    @Test
    void whatRegisteredIsToldOnceInTheOrderItRegistered() {
        Cancel cancel = new Cancel();
        List<String> told = new ArrayList<>();
        cancel.onCancel(() -> told.add("first"));
        cancel.onCancel(() -> told.add("second"));
        assertFalse(cancel.isCancelled());
        assertEquals(List.of(), told, "nothing before it is cancelled");

        cancel.cancel();
        cancel.cancel();

        assertTrue(cancel.isCancelled());
        assertEquals(List.of("first", "second"), told, "once each, however often it is cancelled");
    }

    @Test
    void whatRegistersAfterTheCancelIsToldAtOnceSoThatACancelThatCameFirstIsNotMissed() {
        Cancel cancel = new Cancel();
        cancel.cancel();
        List<String> told = new ArrayList<>();

        cancel.onCancel(() -> told.add("late"));

        assertEquals(List.of("late"), told);
    }

    @Test
    void oneThatCannotBeToldDoesNotKeepTheRestFromBeingTold() {
        Cancel cancel = new Cancel();
        List<String> told = new ArrayList<>();
        cancel.onCancel(() -> {
            throw new IllegalStateException("cannot be told");
        });
        cancel.onCancel(() -> told.add("told all the same"));

        cancel.cancel();

        assertEquals(List.of("told all the same"), told);
    }

    @Test
    void aCancelThatIsNeverCancelledTellsNobody() {
        Cancel cancel = new Cancel();
        List<String> told = new ArrayList<>();
        cancel.onCancel(() -> told.add("never"));

        assertFalse(cancel.isCancelled());
        assertEquals(List.of(), told);
    }

    @Test
    void nothingInTheProgramInterruptsAThreadOrWaitsOnALock() throws IOException {
        // A rule about what is absent, so it is checked on the sources. A thread may put back its own interrupt flag
        // where Java makes it catch an interruption, which is the one thing left of it; nothing interrupts another
        // thread, asks whether it was interrupted, or holds a lock with a condition to wait on.
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = source.getFileName().toString();
                String code = Files.readString(source).replace("Thread.currentThread().interrupt()", "");
                assertFalse(code.contains(".interrupt()"), name + " interrupts a thread");
                assertFalse(code.contains("isInterrupted()"), name + " asks whether a thread was interrupted");
                assertFalse(code.contains("Thread.interrupted()"), name + " asks whether a thread was interrupted");
                assertFalse(code.contains("ReentrantLock"), name + " holds a lock");
                assertFalse(code.contains("Condition "), name + " waits on a condition");
                assertFalse(code.contains(".descendants()"), name + " asks Java for the processes a process started");
            }
        }
    }
}
