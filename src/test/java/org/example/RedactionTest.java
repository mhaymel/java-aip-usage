package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "token sk-ant-oat01-ABCdef_123-xyz",
            "key sk-ant-api03-ABCdef_123-xyz here",
            "Authorization: Bearer ABCdef123",
            "authorization: bearer abc.def.ghi",
            "x-api-key: ABCdef123",
            "X-Api-Key=ABCdef123",
            "{\"access_token\":\"ABCdef123\"}",
            "{\"access_token\": \"ABCdef123\", \"other\": 1}",
            "refresh_token=ABCdef123&x=1",
            "password: hunter2",
            "api_key = ABCdef123",
            "apikey:ABCdef123",
    })
    void removesTheSecretFromEveryShapeOfCredential(String text) {
        String redacted = Redaction.redact(text);

        for (String secret : new String[] {"ABCdef", "abc.def.ghi", "hunter2", "sk-ant-"}) {
            assertFalse(redacted.contains(secret), text + " -> " + redacted);
        }
        assertTrue(redacted.contains("[redacted]"), redacted);
    }

    @Test
    void keepsTheRestOfTheText() {
        assertEquals("GET host/path -> HTTP 200 in 12 ms", Redaction.redact("GET host/path -> HTTP 200 in 12 ms"));
        assertEquals("Usage interval 30 s, poll interval 1 s", Redaction.redact("Usage interval 30 s, poll interval 1 s"));
    }

    @Test
    void leavesStructureAroundASecretIntact() {
        assertEquals("{\"access_token\":\"[redacted]\"}", Redaction.redact("{\"access_token\":\"abc123\"}"));
        assertEquals("a [redacted] b", Redaction.redact("a sk-ant-oat01-xyz b"));
        assertEquals("Authorization: [redacted]", Redaction.redact("Authorization: Bearer abc"));
        assertEquals("sent Bearer [redacted] now", Redaction.redact("sent Bearer abc now"));
    }

    @Test
    void redactsEverySecretInATextNotJustTheFirst() {
        String redacted = Redaction.redact("a sk-ant-one-1 b sk-ant-two-2 c Bearer three d x-api-key: four");

        assertFalse(redacted.contains("one"), redacted);
        assertFalse(redacted.contains("two"), redacted);
        assertFalse(redacted.contains("three"), redacted);
        assertFalse(redacted.contains("four"), redacted);
    }

    @Test
    void redactsAcrossLines() {
        String redacted = Redaction.redact("line one\nAuthorization: Bearer abc\nline three sk-ant-xyz-9\n");

        assertFalse(redacted.contains("abc"), redacted);
        assertFalse(redacted.contains("xyz-9"), redacted);
        assertTrue(redacted.startsWith("line one\n"), redacted);
    }

    @Test
    void anEmptyTextStaysEmpty() {
        assertEquals("", Redaction.redact(""));
    }
}
