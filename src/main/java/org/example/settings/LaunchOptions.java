package org.example.settings;

import java.util.List;
import java.util.OptionalInt;

/**
 * The command line: {@code --usage-interval <seconds>} and
 * {@code --poll-interval <seconds>}, each also as {@code --name=<seconds>}.
 * Parsed before anything starts, so a mistake fails fast and visibly instead
 * of showing up as a window with the wrong behaviour.
 */
public record LaunchOptions(OptionalInt usageInterval, OptionalInt pollInterval, boolean help) {

    public static final String USAGE = """
            Usage: java-aip-usage [--usage-interval <seconds>] [--poll-interval <seconds>]

              --usage-interval <seconds>  how often usage is fetched from Anthropic (%d-%d, default %d)
              --poll-interval <seconds>   how often the window asks for the latest state (%d-%d, default %d)
              -h, --help                  show this help

            Values chosen in the window are saved to settings.json and replace these for the rest of the run."""
            .formatted(
                    IntervalRange.USAGE.min(), IntervalRange.USAGE.max(), IntervalRange.USAGE.defaultValue(),
                    IntervalRange.POLL.min(), IntervalRange.POLL.max(), IntervalRange.POLL.defaultValue());

    /** The command line was not understood; the message says why. */
    public static final class InvalidOptionsException extends RuntimeException {

        public InvalidOptionsException(String message) {
            super(message);
        }
    }

    public static LaunchOptions none() {
        return new LaunchOptions(OptionalInt.empty(), OptionalInt.empty(), false);
    }

    /** @throws InvalidOptionsException if an option is unknown, repeated, or has a bad value */
    public static LaunchOptions parse(List<String> args) {
        OptionalInt usage = OptionalInt.empty();
        OptionalInt poll = OptionalInt.empty();
        boolean help = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
                continue;
            }
            String name = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (!name.equals("--usage-interval") && !name.equals("--poll-interval")) {
                throw new InvalidOptionsException("Unknown option: " + arg);
            }

            String value;
            if (arg.contains("=")) {
                value = arg.substring(arg.indexOf('=') + 1);
            } else if (i + 1 < args.size()) {
                value = args.get(++i);
            } else {
                throw new InvalidOptionsException(name + " needs a value in seconds.");
            }

            if (name.equals("--usage-interval")) {
                if (usage.isPresent()) {
                    throw new InvalidOptionsException(name + " was given more than once.");
                }
                usage = OptionalInt.of(seconds(name, value, IntervalRange.USAGE));
            } else {
                if (poll.isPresent()) {
                    throw new InvalidOptionsException(name + " was given more than once.");
                }
                poll = OptionalInt.of(seconds(name, value, IntervalRange.POLL));
            }
        }
        return new LaunchOptions(usage, poll, help);
    }

    private static int seconds(String option, String value, IntervalRange range) {
        long seconds;
        try {
            seconds = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new InvalidOptionsException(option + " must be a whole number of seconds, not \"" + value + "\".");
        }
        if (!range.contains(seconds)) {
            throw new InvalidOptionsException(option + " must be from " + range.min() + " to " + range.max() + " seconds.");
        }
        return (int) seconds;
    }
}
