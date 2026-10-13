package org.example.settings;

import org.example.settings.LaunchOptions.InvalidOptionsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {

    private static LaunchOptions parse(String... args) {
        return LaunchOptions.parse(List.of(args));
    }

    private static String refusal(String... args) {
        return assertThrows(InvalidOptionsException.class, () -> parse(args)).getMessage();
    }

    @Test
    void noArgumentsLeaveEverythingToTheSettings() {
        assertEquals(LaunchOptions.none(), parse());
        assertFalse(parse().debug(), "not in debug mode unless asked");
        assertEquals(Optional.empty(), parse().baseUrl());
        assertFalse(parse().help());
    }

    // ---- --debug

    @Test
    void debugTurnsDebugModeOnAndTakesNoValue() {
        assertTrue(parse("--debug").debug());
        assertEquals(LaunchOptions.debugMode(), parse("--debug"));
        assertEquals("--debug takes no value.", refusal("--debug=true"));
        assertEquals("--debug was given more than once.", refusal("--debug", "--debug"));
    }

    @Test
    void debugAndTheUrlGoTogetherInEitherOrder() {
        LaunchOptions one = parse("--debug", "--anthropic-url", "http://127.0.0.1:8080");
        LaunchOptions other = parse("--anthropic-url=http://127.0.0.1:8080", "--debug");

        assertEquals(one, other);
        assertTrue(one.debug());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080")), one.baseUrl());
    }

    // ---- the options that are gone

    @ParameterizedTest
    @ValueSource(strings = {"--fake-backend", "--fake-token", "--fake-scenario", "--usage-interval", "--poll-interval"})
    void theOptionsThatWereRemovedAreUnknownNow(String option) {
        assertEquals("Unknown option: " + option, refusal(option));
        assertEquals("Unknown option: " + option + "=1", refusal(option + "=1"));
    }

    @Test
    void anUnknownOptionIsRefusedAsItWasWritten() {
        assertEquals("Unknown option: --nope", refusal("--nope"));
        assertEquals("Unknown option: --nope=1", refusal("--nope=1"));
        assertEquals("Unknown option: 60", refusal("60"));
    }

    // ---- --anthropic-url

    @Test
    void readsTheBaseUrlInEitherForm() {
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080")), parse("--anthropic-url", "http://127.0.0.1:8080").baseUrl());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080")), parse("--anthropic-url=http://127.0.0.1:8080").baseUrl());
        assertEquals(Optional.of(URI.create("https://example.test")), parse("--anthropic-url", "  https://example.test  ").baseUrl(), "trimmed");
    }

    @Test
    void aTrailingSlashIsDroppedAndAPathPrefixKept() {
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080")), parse("--anthropic-url", "http://127.0.0.1:8080/").baseUrl());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080/proxy")), parse("--anthropic-url", "http://127.0.0.1:8080/proxy").baseUrl());
        assertEquals(Optional.of(URI.create("http://127.0.0.1:8080/proxy")), parse("--anthropic-url", "http://127.0.0.1:8080/proxy/").baseUrl());
    }

    @Test
    void theSchemeMayBeInAnyCaseAndAnyHostIsAllowed() {
        assertEquals(Optional.of(URI.create("HTTP://127.0.0.1:8080")), parse("--anthropic-url", "HTTP://127.0.0.1:8080").baseUrl());
        assertEquals(Optional.of(URI.create("https://usage.example.org")), parse("--anthropic-url", "https://usage.example.org").baseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:8080", "/just/a/path", "ftp://example.test", "http://", "http://example.test?x=1", "http://example.test#top", "not a url"})
    void aUrlThatIsNotAnAbsoluteHttpOneIsRefused(String url) {
        assertEquals("--anthropic-url must be an absolute http or https URL, not \"" + url + "\".", refusal("--anthropic-url", url));
    }

    @Test
    void theUrlNeedsAValueAndIsGivenOnce() {
        assertEquals("--anthropic-url needs a URL.", refusal("--anthropic-url"));
        assertEquals("--anthropic-url needs a URL.", refusal("--anthropic-url="));
        assertEquals("--anthropic-url needs a URL.", refusal("--anthropic-url", "   "));
        assertEquals("--anthropic-url was given more than once.", refusal("--anthropic-url", "http://a.test", "--anthropic-url", "http://b.test"));
    }

    // ---- help

    @Test
    void helpIsAskedForInEitherForm() {
        assertTrue(parse("-h").help());
        assertTrue(parse("--help").help());
        assertTrue(parse("--debug", "--help").help(), "beside other options too");
    }

    @Test
    void theUsageTextNamesTheOptionsThereAreAndNoneThatIsGone() {
        for (String option : List.of("--debug", "--anthropic-url <url>", "-h, --help", "https://api.anthropic.com")) {
            assertTrue(LaunchOptions.USAGE.contains(option), option);
        }
        for (String gone : List.of("--fake-backend", "--fake-token", "--fake-scenario", "--usage-interval", "--poll-interval")) {
            assertFalse(LaunchOptions.USAGE.contains(gone), gone);
        }
        assertTrue(LaunchOptions.USAGE.contains("--debug                     debug mode: a tab Debug in the settings, whose settings say whether"),
                "the debug settings are a tab of the settings, and the text says so");
        assertFalse(LaunchOptions.USAGE.contains("debug button"), "there is no debug button");
    }
}
