package org.example.settings;

import org.example.fake.Scenario;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The command line: {@code --usage-interval <seconds>}, {@code --poll-interval <seconds>}
 * and {@code --anthropic-url <url>}, each also as {@code --name=<value>}, plus
 * {@code --fake-token} and {@code --fake-backend}, which take none, and
 * {@code --fake-scenario <name>}, which belongs to the fake backend.
 * Parsed before anything starts, so a mistake fails fast and visibly instead
 * of showing up as a window with the wrong behaviour.
 *
 * @param baseUrl where the usage endpoint is, without its path; empty for Anthropic's own
 * @param fakeToken the token flow is left out and a placeholder bearer sent instead
 * @param fakeBackend usage is fetched from the fake backend the application starts itself
 * @param fakeScenario what that backend answers; only ever present with {@code fakeBackend}
 */
public record LaunchOptions(
        OptionalInt usageInterval,
        OptionalInt pollInterval,
        Optional<URI> baseUrl,
        boolean fakeToken,
        boolean fakeBackend,
        Optional<Scenario> fakeScenario,
        boolean help) {

    /** The base URL of the real endpoint, used when the command line names none. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.anthropic.com");

    public static final String USAGE = """
            Usage: java-aip-usage [--usage-interval <seconds>] [--poll-interval <seconds>]
                                  [--anthropic-url <url>] [--fake-token]
                                  [--fake-backend [--fake-scenario <name>]]

              --usage-interval <seconds>  how often usage is fetched from Anthropic (%d-%d, default %d)
              --poll-interval <seconds>   how often the window asks for the latest state (%d-%d, default %d)
              --anthropic-url <url>       where to fetch usage from, without the path (default %s)
              --fake-token                send a placeholder token instead of obtaining a real one
              --fake-backend              fetch from a fake backend inside this program, not from Anthropic
              --fake-scenario <name>      what the fake backend answers (default %s):
                                          %s
              -h, --help                  show this help

            A usage interval chosen in the window is saved to settings.json and replaces --usage-interval
            for the rest of the run. --poll-interval applies to this run only and is never saved, and so
            do --anthropic-url, --fake-token, --fake-backend and --fake-scenario.

            --fake-backend needs nothing of Anthropic: no account, no login, no network. It implies
            --fake-token, because it ignores the token, and cannot be combined with --anthropic-url."""
            .formatted(
                    IntervalRange.USAGE.min(), IntervalRange.USAGE.max(), IntervalRange.USAGE.defaultValue(),
                    IntervalRange.POLL.min(), IntervalRange.POLL.max(), IntervalRange.POLL.defaultValue(),
                    DEFAULT_BASE_URL, Scenario.NORMAL.optionName(), Scenario.names());

    /** The command line was not understood; the message says why. */
    public static final class InvalidOptionsException extends RuntimeException {

        public InvalidOptionsException(String message) {
            super(message);
        }
    }

    public static LaunchOptions none() {
        return new LaunchOptions(
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty(), false, false, Optional.empty(), false);
    }

    /**
     * Whether a placeholder bearer is sent rather than a real token being obtained.
     * Two options ask for it, and either way means the same thing, so the question is
     * answered in one place and the two cannot drift apart.
     */
    public boolean placeholderToken() {
        return fakeToken || fakeBackend;
    }

    /** @throws InvalidOptionsException if an option is unknown, repeated, contradicted, or has a bad value */
    public static LaunchOptions parse(List<String> args) {
        OptionalInt usage = OptionalInt.empty();
        OptionalInt poll = OptionalInt.empty();
        Optional<URI> base = Optional.empty();
        boolean fakeToken = false;
        boolean fakeBackend = false;
        Optional<Scenario> scenario = Optional.empty();
        boolean help = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
                continue;
            }
            String name = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;

            // The two that take no value are settled before a value is looked for.
            if (name.equals("--fake-token") || name.equals("--fake-backend")) {
                if (arg.contains("=")) {
                    throw new InvalidOptionsException(name + " takes no value.");
                }
                if (name.equals("--fake-token")) {
                    fakeToken = once(fakeToken, name);
                } else {
                    fakeBackend = once(fakeBackend, name);
                }
                continue;
            }
            if (!name.equals("--usage-interval") && !name.equals("--poll-interval")
                    && !name.equals("--anthropic-url") && !name.equals("--fake-scenario")) {
                throw new InvalidOptionsException("Unknown option: " + arg);
            }

            String value;
            if (arg.contains("=")) {
                value = arg.substring(arg.indexOf('=') + 1);
            } else if (i + 1 < args.size()) {
                value = args.get(++i);
            } else {
                throw new InvalidOptionsException(needsAValue(name));
            }

            switch (name) {
                case "--usage-interval" -> {
                    if (usage.isPresent()) {
                        throw repeated(name);
                    }
                    usage = OptionalInt.of(seconds(name, value, IntervalRange.USAGE));
                }
                case "--poll-interval" -> {
                    if (poll.isPresent()) {
                        throw repeated(name);
                    }
                    poll = OptionalInt.of(seconds(name, value, IntervalRange.POLL));
                }
                case "--anthropic-url" -> {
                    if (base.isPresent()) {
                        throw repeated(name);
                    }
                    base = Optional.of(baseUrl(value));
                }
                default -> {
                    if (scenario.isPresent()) {
                        throw repeated(name);
                    }
                    scenario = Optional.of(scenario(value));
                }
            }
        }

        // Checked once both sides are known, which is why these are not in the loop.
        if (fakeBackend && base.isPresent()) {
            throw new InvalidOptionsException(
                    "--fake-backend and --anthropic-url cannot both be given: each says where usage comes from.");
        }
        // Refused rather than ignored: a forgotten --fake-backend would otherwise leave the run
        // quietly fetching real usage while the person believed it was fake.
        if (scenario.isPresent() && !fakeBackend) {
            throw new InvalidOptionsException("--fake-scenario needs --fake-backend.");
        }
        return new LaunchOptions(usage, poll, base, fakeToken, fakeBackend, scenario, help);
    }

    private static boolean once(boolean alreadyGiven, String option) {
        if (alreadyGiven) {
            throw repeated(option);
        }
        return true;
    }

    private static InvalidOptionsException repeated(String option) {
        return new InvalidOptionsException(option + " was given more than once.");
    }

    private static String needsAValue(String option) {
        return switch (option) {
            case "--anthropic-url" -> "--anthropic-url needs a URL.";
            case "--fake-scenario" -> "--fake-scenario needs a name.";
            default -> option + " needs a value in seconds.";
        };
    }

    /**
     * The base URL, without the path the application appends. Any host is allowed,
     * this machine or another, and none is warned about: the person naming the URL is
     * the one deciding where the token may go.
     */
    private static URI baseUrl(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            throw new InvalidOptionsException(needsAValue("--anthropic-url"));
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw badUrl(text);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw badUrl(text);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw badUrl(text);
        }
        // One trailing slash is dropped so that appending the path cannot double it.
        return text.endsWith("/") ? URI.create(text.substring(0, text.length() - 1)) : uri;
    }

    private static InvalidOptionsException badUrl(String value) {
        return new InvalidOptionsException(
                "--anthropic-url must be an absolute http or https URL, not \"" + value + "\".");
    }

    private static Scenario scenario(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            throw new InvalidOptionsException(needsAValue("--fake-scenario"));
        }
        return Scenario.ofOptionName(text).orElseThrow(() -> new InvalidOptionsException(
                "--fake-scenario must be one of " + Scenario.names() + ", not \"" + text + "\"."));
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
