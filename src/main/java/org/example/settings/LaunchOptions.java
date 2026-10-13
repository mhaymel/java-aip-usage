package org.example.settings;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The command line: {@code --debug}, {@code --anthropic-url <url>}, and {@code -h} or
 * {@code --help}. The option that takes a value also accepts {@code --name=value}.
 *
 * <p>Parsed before anything starts, so a mistake fails fast and visibly instead
 * of showing up as a window with the wrong behaviour.
 *
 * @param debug the program runs in debug mode: the debug settings are shown and count
 * @param baseUrl where the usage endpoint is, without its path; empty for Anthropic's own
 */
public record LaunchOptions(boolean debug, Optional<URI> baseUrl, boolean help) {

    /** The base URL of the real endpoint, used when the command line names none. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.anthropic.com");

    public static final String USAGE = """
            Usage: java-aip-usage [--debug] [--anthropic-url <url>]

              --debug                     debug mode: a tab Debug in the settings, whose settings say whether
                                          usage comes from a fake backend inside this program, what it answers,
                                          and whether a placeholder token is sent
              --anthropic-url <url>       where to fetch usage from, without the path (default %s)
              -h, --help                  show this help

            The debug settings are kept in settings.json like the others, and count only in debug mode: a
            run without --debug fetches from Anthropic, or from the URL given, with a real token. Everything
            else, the time between usage requests among it, is set in the window."""
            .formatted(DEFAULT_BASE_URL);

    /** The command line was not understood; the message says why. */
    public static final class InvalidOptionsException extends RuntimeException {

        public InvalidOptionsException(String message) {
            super(message);
        }
    }

    public static LaunchOptions none() {
        return new LaunchOptions(false, Optional.empty(), false);
    }

    /** Debug mode and nothing else; for tests. */
    public static LaunchOptions debugMode() {
        return new LaunchOptions(true, Optional.empty(), false);
    }

    /** @throws InvalidOptionsException if an option is unknown, repeated, or has a bad value */
    public static LaunchOptions parse(List<String> args) {
        Optional<URI> base = Optional.empty();
        boolean debug = false;
        boolean help = false;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
                continue;
            }
            String name = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;

            // The one that takes no value is settled before a value is looked for.
            if (name.equals("--debug")) {
                if (arg.contains("=")) {
                    throw new InvalidOptionsException(name + " takes no value.");
                }
                if (debug) {
                    throw repeated(name);
                }
                debug = true;
                continue;
            }
            if (!name.equals("--anthropic-url")) {
                throw new InvalidOptionsException("Unknown option: " + arg);
            }

            String value;
            if (arg.contains("=")) {
                value = arg.substring(arg.indexOf('=') + 1);
            } else if (i + 1 < args.size()) {
                value = args.get(++i);
            } else {
                throw new InvalidOptionsException("--anthropic-url needs a URL.");
            }
            if (base.isPresent()) {
                throw repeated(name);
            }
            base = Optional.of(baseUrl(value));
        }
        return new LaunchOptions(debug, base, help);
    }

    private static InvalidOptionsException repeated(String option) {
        return new InvalidOptionsException(option + " was given more than once.");
    }

    /**
     * The base URL, without the path the application appends. Any host is allowed,
     * this machine or another, and none is warned about: the person naming the URL is
     * the one deciding where the token may go.
     */
    private static URI baseUrl(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            throw new InvalidOptionsException("--anthropic-url needs a URL.");
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
}
