package org.example.token;

import org.example.Redaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderTokenProviderTest {

    @Test
    void handsOutTheSameTokenAndNeverFails() {
        PlaceholderTokenProvider provider = new PlaceholderTokenProvider();

        assertEquals(PlaceholderTokenProvider.TOKEN, provider.acquire());
        assertEquals(PlaceholderTokenProvider.TOKEN, provider.acquire());
    }

    /**
     * It is not a credential, and must not read like one: a log that mentions it should
     * stay readable, and nobody reading one should have to wonder whether a real token
     * leaked. So the masking has nothing to find in it.
     */
    @Test
    void itIsNotShapedLikeACredential() {
        String line = "A line that mentions " + PlaceholderTokenProvider.TOKEN + " in passing";

        assertEquals(line, Redaction.redact(line));
        assertFalse(PlaceholderTokenProvider.TOKEN.startsWith("sk-ant-"), PlaceholderTokenProvider.TOKEN);
    }

    @Test
    void itSaysWhatItIs() {
        assertTrue(PlaceholderTokenProvider.TOKEN.contains("placeholder"), PlaceholderTokenProvider.TOKEN);
    }
}
