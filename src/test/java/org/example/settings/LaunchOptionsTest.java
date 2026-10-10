package org.example.settings;

import org.example.fake.Scenario;
import org.example.settings.LaunchOptions.InvalidOptionsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {

    private static LaunchOptions parse(String... args) {
        return LaunchOptions.parse(List.of(args));
    }

    @Test
    void noArgumentsLeaveEverythingToTheSettings() {
        assertEquals(LaunchOptions.none(), parse());
    }

    @Test
    void readsBothIntervalsInEitherForm() {
        LaunchOptions spaced = parse("--usage-interval", "10", "--poll-interval", "2");
        LaunchOptions joined = parse("--usage-interval=10", "--poll-interval=2");

        assertEquals(OptionalInt.of(10), spaced.usageInterval());
        assertEquals(OptionalInt.of(2), spaced.pollInterval());
        assertEquals(spaced, joined);
    }

    @Test
    void eitherOptionMayBeGivenAlone() {
        assertEquals(OptionalInt.of(10), parse("--usage-interval", "10").usageInterval());
        assertEquals(OptionalInt.empty(), parse("--usage-interval", "10").pollInterval());
        assertEquals(OptionalInt.of(2), parse("--poll-interval=2").pollInterval());
        assertEquals(OptionalInt.empty(), parse("--poll-interval=2").usageInterval());
    }

    @Test
    void acceptsTheBoundaries() {
        assertEquals(OptionalInt.of(5), parse("--usage-interval=5").usageInterval());
        assertEquals(OptionalInt.of(3600), parse("--usage-interval=3600").usageInterval());
        assertEquals(OptionalInt.of(1), parse("--poll-interval=1").pollInterval());
        assertEquals(OptionalInt.of(60), parse("--poll-interval=60").pollInterval());
    }

    @Test
    void helpIsRecognised() {
        assertTrue(parse("--help").help());
        assertTrue(parse("-h").help());
        assertFalse(parse().help());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "3601", "0", "-5", "abc", "", "1.5", "1e3", "99999999999999999999"})
    void rejectsABadUsageInterval(String value) {
        InvalidOptionsException e = assertThrows(
                InvalidOptionsException.class, () -> parse("--usage-interval", value));

        assertTrue(e.getMessage().startsWith("--usage-interval must be"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "61", "-1", "x", ""})
    void rejectsABadPollInterval(String value) {
        InvalidOptionsException e = assertThrows(
                InvalidOptionsException.class, () -> parse("--poll-interval=" + value));

        assertTrue(e.getMessage().startsWith("--poll-interval must be"), e.getMessage());
    }

    @Test
    void rejectsAMissingValue() {
        assertTrue(assertThrows(InvalidOptionsException.class, () -> parse("--usage-interval"))
                .getMessage().contains("needs a value"));
    }

    @Test
    void rejectsUnknownOptionsAndStrayArguments() {
        assertThrows(InvalidOptionsException.class, () -> parse("--interval", "10"));
        assertThrows(InvalidOptionsException.class, () -> parse("10"));
        assertThrows(InvalidOptionsException.class, () -> parse("--usage-intervals=10"));
    }

    @Test
    void rejectsARepeatedOption() {
        assertThrows(InvalidOptionsException.class, () -> parse("--usage-interval=10", "--usage-interval=20"));
        assertThrows(InvalidOptionsException.class, () -> parse("--poll-interval=1", "--poll-interval", "2"));
    }

    @Test
    void theUsageTextStatesTheRangesAndDefaults() {
        assertTrue(LaunchOptions.USAGE.contains("5-3600"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("default 60"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("1-60"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("default 1)"), LaunchOptions.USAGE);
    }

    @Test
    void theUsageTextSaysWhichIntervalIsSavedAndWhichIsNot() {
        assertTrue(LaunchOptions.USAGE.contains("saved to settings.json"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("never saved"), LaunchOptions.USAGE);
    }

    // ---- --anthropic-url

    @Test
    void readsTheBaseUrlInEitherForm() {
        URI expected = URI.create("http://127.0.0.1:8080");

        assertEquals(Optional.of(expected), parse("--anthropic-url", "http://127.0.0.1:8080").baseUrl());
        assertEquals(Optional.of(expected), parse("--anthropic-url=http://127.0.0.1:8080").baseUrl());
        assertEquals(Optional.empty(), parse().baseUrl());
    }

    @Test
    void dropsOneTrailingSlashSoThePathCannotBeDoubled() {
        assertEquals(
                Optional.of(URI.create("http://127.0.0.1:8080")),
                parse("--anthropic-url=http://127.0.0.1:8080/").baseUrl());
    }

    /** A proxy is often reached under a path, and that path is a prefix of the endpoint's. */
    @Test
    void keepsAPathAsAPrefix() {
        assertEquals(
                Optional.of(URI.create("https://proxy.example/anthropic")),
                parse("--anthropic-url=https://proxy.example/anthropic").baseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8080", "https://api.anthropic.com", "HTTP://127.0.0.1:8080",
            "http://localhost", "https://somewhere.far.away:8443/under/a/path"})
    void acceptsAnyHostAndEitherScheme(String url) {
        assertEquals(Optional.of(url.endsWith("/") ? url.substring(0, url.length() - 1) : url),
                parse("--anthropic-url=" + url).baseUrl().map(URI::toString));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1:8080", "/api/oauth/usage", "ftp://host", "file:///tmp/x", "http://",
            "http://host?query=1", "http://host#fragment", "not a url at all", "://host"})
    void rejectsAUrlItCannotFetchFrom(String url) {
        InvalidOptionsException e = assertThrows(
                InvalidOptionsException.class, () -> parse("--anthropic-url=" + url));

        assertTrue(e.getMessage().startsWith("--anthropic-url must be an absolute http or https URL"), e.getMessage());
    }

    @Test
    void rejectsABaseUrlThatIsMissingOrEmptyOrRepeated() {
        assertEquals("--anthropic-url needs a URL.",
                assertThrows(InvalidOptionsException.class, () -> parse("--anthropic-url")).getMessage());
        assertEquals("--anthropic-url needs a URL.",
                assertThrows(InvalidOptionsException.class, () -> parse("--anthropic-url=")).getMessage());
        assertThrows(InvalidOptionsException.class,
                () -> parse("--anthropic-url=http://a", "--anthropic-url=http://b"));
    }

    // ---- --fake-token and --fake-backend

    @Test
    void readsTheTwoFlagsThatTakeNoValue() {
        assertTrue(parse("--fake-token").fakeToken());
        assertTrue(parse("--fake-backend").fakeBackend());
        assertFalse(parse().fakeToken());
        assertFalse(parse().fakeBackend());
    }

    @Test
    void eitherFlagMeansNoRealTokenIsObtained() {
        assertTrue(parse("--fake-token").placeholderToken());
        assertTrue(parse("--fake-backend").placeholderToken(), "the fake backend ignores the token");
        assertTrue(parse("--fake-backend", "--fake-token").placeholderToken(), "both say the same thing");
        assertFalse(parse().placeholderToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--fake-token", "--fake-backend"})
    void rejectsAValueGivenToAFlag(String option) {
        assertEquals(option + " takes no value.",
                assertThrows(InvalidOptionsException.class, () -> parse(option + "=1")).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--fake-token", "--fake-backend"})
    void rejectsARepeatedFlag(String option) {
        assertThrows(InvalidOptionsException.class, () -> parse(option, option));
    }

    /** Each says where usage comes from, so accepting both would mean silently picking one. */
    @Test
    void refusesTheTwoOptionsThatBothSayWhereUsageComesFrom() {
        String expected = "--fake-backend and --anthropic-url cannot both be given: each says where usage comes from.";

        assertEquals(expected, assertThrows(InvalidOptionsException.class,
                () -> parse("--fake-backend", "--anthropic-url=http://127.0.0.1:1")).getMessage());
        assertEquals(expected, assertThrows(InvalidOptionsException.class,
                () -> parse("--anthropic-url=http://127.0.0.1:1", "--fake-backend")).getMessage());
    }

    // ---- --fake-scenario

    @Test
    void readsAScenarioByName() {
        assertEquals(
                Optional.of(Scenario.HTTP_429),
                parse("--fake-backend", "--fake-scenario", "http-429").fakeScenario());
        assertEquals(
                Optional.of(Scenario.NOT_JSON),
                parse("--fake-backend", "--fake-scenario=NOT-JSON").fakeScenario());
        assertEquals(Optional.empty(), parse("--fake-backend").fakeScenario());
    }

    /**
     * Refused rather than ignored: a forgotten --fake-backend would otherwise leave the run
     * quietly fetching real usage while the person believed it was fake.
     */
    @Test
    void refusesAScenarioWithNoBackendToServeIt() {
        assertEquals("--fake-scenario needs --fake-backend.",
                assertThrows(InvalidOptionsException.class, () -> parse("--fake-scenario=normal")).getMessage());
    }

    @Test
    void refusesAScenarioItDoesNotKnowAndNamesTheOnesItDoes() {
        InvalidOptionsException e = assertThrows(InvalidOptionsException.class,
                () -> parse("--fake-backend", "--fake-scenario=explode"));

        assertTrue(e.getMessage().startsWith("--fake-scenario must be one of "), e.getMessage());
        for (Scenario scenario : Scenario.values()) {
            assertTrue(e.getMessage().contains(scenario.optionName()), scenario + " missing from: " + e.getMessage());
        }
        assertTrue(e.getMessage().endsWith("not \"explode\"."), e.getMessage());
    }

    @Test
    void rejectsAScenarioThatIsMissingOrEmptyOrRepeated() {
        assertEquals("--fake-scenario needs a name.",
                assertThrows(InvalidOptionsException.class, () -> parse("--fake-backend", "--fake-scenario"))
                        .getMessage());
        assertEquals("--fake-scenario needs a name.",
                assertThrows(InvalidOptionsException.class, () -> parse("--fake-backend", "--fake-scenario="))
                        .getMessage());
        assertThrows(InvalidOptionsException.class,
                () -> parse("--fake-backend", "--fake-scenario=normal", "--fake-scenario=empty"));
    }

    // ---- the usage text

    @Test
    void theUsageTextNamesTheNewOptionsAndTheDefaultUrl() {
        assertTrue(LaunchOptions.USAGE.contains("--anthropic-url"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("--fake-token"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("--fake-backend"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("--fake-scenario"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("https://api.anthropic.com"), LaunchOptions.USAGE);
    }

    @Test
    void theUsageTextListsEveryScenario() {
        for (Scenario scenario : Scenario.values()) {
            assertTrue(LaunchOptions.USAGE.contains(scenario.optionName()), scenario + " missing from the usage text");
        }
    }

    // ---- --fake-format

    private static String refusal(String... args) {
        return assertThrows(LaunchOptions.InvalidOptionsException.class, () -> parse(args)).getMessage();
    }

    @Test
    void theFakeBackendCanBeToldWhichFormatToAnswerIn() {
        assertEquals(java.util.Optional.empty(), parse("--fake-backend").fakeFormat(), "none named: the usage-based one is the default");
        assertEquals(java.util.Optional.of(org.example.usage.UsageFormat.SEAT_BASED), parse("--fake-backend", "--fake-format", "seat-based").fakeFormat());
        assertEquals(java.util.Optional.of(org.example.usage.UsageFormat.USAGE_BASED), parse("--fake-format= Usage-Based ", "--fake-backend").fakeFormat());
    }

    @Test
    void aFakeFormatIsRefusedWithoutTheBackendWithAnUnknownNameWithNoneOrTwice() {
        assertEquals("--fake-format needs --fake-backend.", refusal("--fake-format", "seat-based"));
        assertEquals("--fake-format must be one of usage-based, seat-based, not \"pro\".", refusal("--fake-backend", "--fake-format", "pro"));
        assertEquals("--fake-format needs a name.", refusal("--fake-backend", "--fake-format"));
        assertEquals("--fake-format needs a name.", refusal("--fake-backend", "--fake-format="));
        assertEquals("--fake-format was given more than once.",
                refusal("--fake-backend", "--fake-format", "seat-based", "--fake-format", "seat-based"));
    }

    @Test
    void theHelpTextNamesTheFakeFormatAndItsTwoNames() {
        assertTrue(LaunchOptions.USAGE.contains("--fake-format <name>"));
        assertTrue(LaunchOptions.USAGE.contains("usage-based, seat-based"));
    }
}
