package org.example.token;

import org.example.Failure;
import org.example.log.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

/**
 * Ends a process and everything it started. {@code claude} is a wrapper round further processes, and on Windows it
 * is started through {@code cmd.exe}: ending the one process that was started would leave the rest running.
 *
 * <p>It is done with the commands every system has for it, so that it needs nothing of the Java runtime that another
 * language lacks: {@code taskkill} with its tree switch on Windows, and elsewhere {@code pgrep} to find the children
 * of a process, level by level, and {@code kill} to end them, the deepest first.
 */
final class ProcessTree {

    private static final Log LOG = Log.of(ProcessTree.class);

    /** How long one of the commands may take; they answer at once, and a hanging one must not hold the program up. */
    private static final long COMMAND_SECONDS = 5;

    private ProcessTree() {
    }

    /**
     * Ends the process of this number and what it started, at once and without asking them. The process should still
     * be running, since what it started is found through it. Never fails: a command that is missing or does not work
     * is noted in the log at debug level, and the caller ends the process itself in any case.
     */
    static void kill(long pid) {
        if (isWindows(System.getProperty("os.name", ""))) {
            run(windowsCommand(pid));
            return;
        }
        for (long descendant : descendants(pid, ProcessTree::childrenOf)) {
            run(killCommand(descendant));
        }
        run(killCommand(pid));
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** The command that ends a process and its tree on Windows. */
    static List<String> windowsCommand(long pid) {
        return List.of("taskkill", "/PID", Long.toString(pid), "/T", "/F");
    }

    /** The command that ends one process at once where there is no command for a tree. */
    static List<String> killCommand(long pid) {
        return List.of("kill", "-9", Long.toString(pid));
    }

    /** The command that lists the processes a process started, one number to a line. */
    static List<String> childrenCommand(long pid) {
        return List.of("pgrep", "-P", Long.toString(pid));
    }

    /**
     * Everything a process started, and what those started, and so on: the deepest first, so that ending them in this
     * order never leaves a process whose parent is gone before it. The process itself is not among them.
     *
     * @param childrenOf the processes one process started
     */
    static List<Long> descendants(long pid, LongFunction<List<Long>> childrenOf) {
        List<Long> found = new ArrayList<>();
        List<Long> level = List.of(pid);
        while (!level.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (long parent : level) {
                for (long child : childrenOf.apply(parent)) {
                    // A number seen before would mean a circle, which a tree has none of; it is not followed again.
                    if (child != pid && !found.contains(child) && !next.contains(child)) {
                        next.add(child);
                    }
                }
            }
            found.addAll(next);
            level = next;
        }
        List<Long> deepestFirst = new ArrayList<>(found);
        java.util.Collections.reverse(deepestFirst);
        return deepestFirst;
    }

    /** The numbers in what the command that lists children wrote: one to a line, anything else passed over. */
    static List<Long> parseChildren(String output) {
        List<Long> children = new ArrayList<>();
        for (String line : output.split("\\R")) {
            try {
                children.add(Long.parseLong(line.strip()));
            } catch (NumberFormatException e) {
                // An empty line, or a line that is no number: not a child.
            }
        }
        return children;
    }

    private static List<Long> childrenOf(long pid) {
        try {
            Process listing = new ProcessBuilder(childrenCommand(pid)).redirectErrorStream(true).start();
            String output = new String(listing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            listing.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS);
            return parseChildren(output);
        } catch (IOException e) {
            LOG.debug("Could not list what process " + pid + " started: " + Failure.ofStart(e));
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.debug(command.get(0) + " did not end in time");
            }
        } catch (IOException e) {
            LOG.debug(command.get(0) + " could not be started: " + Failure.ofStart(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
