package org.example.settings;

import org.example.settings.LaunchOptions.InvalidOptionsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
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
        assertTrue(LaunchOptions.USAGE.contains("default 30"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("1-60"), LaunchOptions.USAGE);
        assertTrue(LaunchOptions.USAGE.contains("default 1)"), LaunchOptions.USAGE);
    }
}
