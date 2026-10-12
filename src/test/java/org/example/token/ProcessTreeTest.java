package org.example.token;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of ending a process tree that can be judged without a process: the commands, and the order. That a real
 * tree is ended is {@code ClaudeTokenProviderTest}'s to show, on the system the tests run on.
 */
class ProcessTreeTest {

    @Test
    void onWindowsOneCommandEndsTheProcessAndItsTree() {
        assertEquals(List.of("taskkill", "/PID", "4711", "/T", "/F"), ProcessTree.windowsCommand(4711));
        assertTrue(ProcessTree.isWindows("Windows 11"));
        assertFalse(ProcessTree.isWindows("Mac OS X"));
        assertFalse(ProcessTree.isWindows("Linux"));
    }

    @Test
    void elsewhereTheChildrenAreListedAndEachProcessIsEndedAtOnce() {
        assertEquals(List.of("pgrep", "-P", "4711"), ProcessTree.childrenCommand(4711));
        assertEquals(List.of("kill", "-9", "4711"), ProcessTree.killCommand(4711));
    }

    @Test
    void whatAProcessStartedIsFoundLevelByLevelAndTheDeepestComesFirst() {
        Map<Long, List<Long>> tree = Map.of(
                1L, List.of(10L, 11L),
                10L, List.of(100L),
                100L, List.of(1000L, 1001L),
                11L, List.of());

        List<Long> order = ProcessTree.descendants(1, pid -> tree.getOrDefault(pid, List.of()));

        assertEquals(List.of(1001L, 1000L, 100L, 11L, 10L), order);
        assertFalse(order.contains(1L), "the process itself is the caller's to end, last");
    }

    @Test
    void aProcessThatStartedNothingHasNothingToEndBeforeIt() {
        assertEquals(List.of(), ProcessTree.descendants(1, pid -> List.of()));
    }

    @Test
    void aNumberThatComesAgainIsNotFollowedASecondTime() {
        // No tree has a circle, but a number can be given to a new process while the tree is being read.
        Map<Long, List<Long>> circle = Map.of(1L, List.of(2L), 2L, List.of(3L), 3L, List.of(1L, 2L));

        assertEquals(List.of(3L, 2L), ProcessTree.descendants(1, pid -> circle.getOrDefault(pid, List.of())));
    }

    @Test
    void theListOfChildrenIsOneNumberToALineAndAnythingElseIsPassedOver() {
        assertEquals(List.of(4712L, 4713L), ProcessTree.parseChildren("4712\n4713\n"));
        assertEquals(List.of(4712L, 4713L), ProcessTree.parseChildren("  4712 \r\n\r\nno such process\n4713"));
        assertEquals(List.of(), ProcessTree.parseChildren(""));
    }
}
