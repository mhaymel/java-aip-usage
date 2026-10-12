package org.example.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The program's own JSON: what is read, what is refused and how that is said, what a value answers, and the two forms
 * it is written in. The forms are the ones the API, the settings file and the log have had since their first version,
 * so a change here changes those bytes.
 */
class JsonTest {

    // ---- reading

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "[]", "null", "true", "false", "0", "-1", "42", "1.5", "1.50", "1e2", "1E+2", "1.5e-3", "-0", "-0.0",
        "\"\"", "\"a\"", "{\"a\":1}", "[[1,2],[3]]", " \t\r\n{ \"a\" : [ 1 , 2 ] } \r\n", "12345678901234567890",
        "\"\\u0000\\u001f\\u20ac\\ud83d\\ude00\"", "\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\""})
    void thisIsJson(String text) throws JsonException {
        JsonReader.read(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "{,}", "{a:1}", "{'a':1}",
        "'a'", "\"a", "\"a\\\"", "\"\\x\"", "\"\\u12\"", "\"\\u12g4\"", "\"a\nb\"", "\"a\tb\"",
        "01", "-", "+1", ".5", "1.", "1.e1", "1e", "1e+", "--1", "0x10", "NaN", "Infinity", "-Infinity",
        "tru", "nul", "True", "nulll", "truefalse", "{} {}", "{} x", "1 2", "[1] ,", "// c\n1", "/* c */ 1",
        "{\"a\":1 \"b\":2}", "[1 2]", "{\"a\":1,,\"b\":2}", "\u00a0 1", "{\"a\" 1}"})
    void thisIsNotJson(String text) {
        assertThrows(JsonException.class, () -> JsonReader.read(text));
    }

    @Test
    void anEmptyTextHasNoValueAndIsNoFault() throws JsonException {
        assertNull(JsonReader.read(""));
        assertNull(JsonReader.read(" \r\n\t"));
        assertNull(JsonReader.read(new byte[0]));
    }

    @Test
    void aFaultSaysWhereAndWhatInTheProgramsOwnWords() {
        assertEquals("line 1, column 1: a value was expected", fault("x"));
        assertEquals("line 1, column 6: a value was expected", fault("{\"a\":}"));
        assertEquals("line 3, column 3: a key in double quotes was expected", fault("{\n  \"a\": 1,\n  b: 2\n}"));
        assertEquals("line 1, column 6: ':' was expected after a key", fault("{\"a\" 1}"));
        assertEquals("line 1, column 8: ',' or '}' was expected", fault("{\"a\":1 \"b\":2}"));
        assertEquals("line 1, column 4: ',' or ']' was expected", fault("[1 2]"));
        assertEquals("line 1, column 3: the text is not closed", fault("\"a"));
        assertEquals("line 1, column 3: a control character in a text must be escaped", fault("\"a\tb\""));
        assertEquals("line 1, column 3: an escape that JSON does not have", fault("\"\\x\""));
        assertEquals("line 1, column 6: four hexadecimal digits were expected after \\u", fault("\"\\u12g4\""));
        assertEquals("line 1, column 1: a number may not begin with a zero that other digits follow", fault("01"));
        assertEquals("line 1, column 3: a digit was expected after the point", fault("1."));
        assertEquals("line 1, column 3: a digit was expected in the exponent", fault("1e"));
        assertEquals("line 1, column 1: a number was expected", fault("-"));
        assertEquals("line 1, column 4: there is text after the value", fault("{} x"));
        assertEquals("line 2, column 1: there is text after the value", fault("{}\n{}"));
    }

    @Test
    void whatIsWrongIsAlsoSaidWithoutThePlace() {
        JsonException e = assertThrows(JsonException.class, () -> JsonReader.read("[1 2]"));
        assertEquals("',' or ']' was expected", e.what());
    }

    private static String fault(String text) {
        return assertThrows(JsonException.class, () -> JsonReader.read(text)).getMessage();
    }

    @Test
    void nestingIsFollowedToAThousandLevelsAndRefusedBeyond() throws JsonException {
        JsonReader.read("[".repeat(JsonReader.MAX_DEPTH) + "]".repeat(JsonReader.MAX_DEPTH));
        String deeper = "[".repeat(JsonReader.MAX_DEPTH + 1) + "]".repeat(JsonReader.MAX_DEPTH + 1);
        assertTrue(fault(deeper).endsWith("it is nested more than 1000 deep"), fault(deeper));
    }

    @Test
    void aKeyThatComesTwiceHasItsLastValueAtItsFirstPlace() throws JsonException {
        assertEquals("{\"a\":3,\"b\":2}", JsonReader.read("{\"a\":1,\"b\":2,\"a\":3}").toString());
    }

    @Test
    void bytesAreReadAsUtf8AndAByteOrderMarkIsPassedOver() throws JsonException {
        assertEquals("\u20ac", JsonReader.read("\"\u20ac\"".getBytes(StandardCharsets.UTF_8)).text());
        assertEquals(1, JsonReader.read("\uFEFF{\"a\":1}".getBytes(StandardCharsets.UTF_8)).get("a").intValue());
        assertThrows(JsonException.class, () -> JsonReader.read("\uFEFF{\"a\":1}"), "a text has none");
    }

    @Test
    void anEscapeIsResolvedAndASlashNeedsNone() throws JsonException {
        assertEquals("\" \\ / \b \f \n \r \t \u20ac", JsonReader.read("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u20AC\"").text());
        assertEquals("a/b", JsonReader.read("\"a/b\"").text());
    }

    // ---- what a value answers

    @Test
    void aNumberKeepsTheTextItWasWrittenWith() throws JsonException {
        assertEquals("1000.00", JsonReader.read("1000.00").numberText());
        assertEquals("[1000.00,1e2,-0]", JsonReader.read("[1000.00, 1e2, -0]").toString());
    }

    @Test
    void onlyANumberWithNoPointAndNoExponentIsWhole() throws JsonException {
        assertTrue(JsonReader.read("45").isWhole());
        assertTrue(JsonReader.read("-0").isWhole());
        assertFalse(JsonReader.read("45.0").isWhole());
        assertFalse(JsonReader.read("4e1").isWhole());
        assertFalse(JsonReader.read("\"45\"").isWhole(), "a text is no number");
        assertFalse(JsonReader.read("true").isWhole());
    }

    @Test
    void whetherAWholeNumberFitsIsSaidAtTheEdges() throws JsonException {
        assertTrue(JsonReader.read("2147483647").fitsInt());
        assertFalse(JsonReader.read("2147483648").fitsInt());
        assertTrue(JsonReader.read("-2147483648").fitsInt());
        assertFalse(JsonReader.read("-2147483649").fitsInt());
        assertTrue(JsonReader.read("9223372036854775807").fitsLong());
        assertFalse(JsonReader.read("9223372036854775808").fitsLong());
        assertFalse(JsonReader.read("45.0").fitsInt());
        assertFalse(JsonReader.read("45.0").fitsLong());
    }

    @Test
    void aNumberAsAWholeOneHasItsDecimalsCutOff() throws JsonException {
        assertEquals(18, JsonReader.read("18.9").intValue());
        assertEquals(-18, JsonReader.read("-18.9").intValue());
        assertEquals(100L, JsonReader.read("1e2").longValue());
        assertEquals(186.02, JsonReader.read("186.02").doubleValue());
        assertEquals(0.0, JsonReader.read("-0").doubleValue(), "the whole number, which has no sign at zero");
        assertEquals(0, JsonReader.read("\"12\"").intValue(), "a text is no number unless it is asked to be taken for one");
    }

    @Test
    void aValueIsTakenForATextANumberOrYesAndNoOnlyWhenAsked() throws JsonException {
        assertEquals("USD", JsonReader.read("\"USD\"").asText());
        assertEquals("1.50", JsonReader.read("1.50").asText());
        assertEquals("true", JsonReader.read("true").asText());
        assertEquals("null", JsonReader.read("null").asText());
        assertEquals("", JsonReader.read("{\"a\":1}").asText());
        assertEquals("", JsonReader.read("[1]").asText());

        assertEquals(2, JsonReader.read("2").asInt(0));
        assertEquals(2, JsonReader.read("\"2\"").asInt(0));
        assertEquals(7, JsonReader.read("\" 7 \"").asInt(0));
        assertEquals(1, JsonReader.read("\"1.9\"").asInt(0));
        assertEquals(1, JsonReader.read("true").asInt(0));
        assertEquals(-7, JsonReader.read("\"x\"").asInt(-7));
        assertEquals(-7, JsonReader.read("null").asInt(-7));
        assertEquals(-7, JsonReader.read("{}").asInt(-7));

        assertTrue(JsonReader.read("true").asBoolean(false));
        assertFalse(JsonReader.read("false").asBoolean(true));
        assertTrue(JsonReader.read("1").asBoolean(false));
        assertFalse(JsonReader.read("0").asBoolean(true));
        assertTrue(JsonReader.read("\"true\"").asBoolean(false));
        assertFalse(JsonReader.read("\"false\"").asBoolean(true));
        assertTrue(JsonReader.read("\"yes\"").asBoolean(true), "neither: what was given for it");
        assertFalse(JsonReader.read("null").asBoolean(false));
        assertTrue(JsonReader.read("1.5").asBoolean(true), "a number with decimals says neither");
    }

    @Test
    void aTextIsATextOnlyWhenItIsOne() throws JsonException {
        assertEquals("a", JsonReader.read("\"a\"").text());
        assertNull(JsonReader.read("1").text());
        assertNull(JsonReader.read("null").text());
        assertNull(JsonReader.read("1").get("a"), "nothing has members but an object");
        assertFalse(JsonReader.read("\"true\"").bool());
    }

    @Test
    void aMemberThatIsNullIsThereAndOneThatIsMissingIsNot() throws JsonException {
        Json object = JsonReader.read("{\"a\":null}");
        assertTrue(object.has("a"));
        assertSame(Json.NULL, object.get("a"));
        assertFalse(object.has("b"));
        assertNull(object.get("b"));
    }

    @Test
    void aPathLeadsThroughObjectsAndArraysOrNowhere() throws JsonException {
        Json value = JsonReader.read("{\"lines\":[{\"cells\":[\"a\",\"b\"]}],\"none\":null}");
        assertEquals("b", value.at("/lines/0/cells/1").text());
        assertSame(value, value.at(""));
        assertSame(Json.NULL, value.at("/none"));
        assertNull(value.at("/lines/1"));
        assertNull(value.at("/lines/x"));
        assertNull(value.at("/none/deeper"));
        assertNull(value.at("/missing/deeper"));
        assertEquals(List.of("lines", "none"), List.copyOf(value.keys()));
        assertEquals(2, value.size());
        assertEquals(2, value.at("/lines/0/cells").items().size());
        assertEquals(0, value.at("/none").size());
    }

    @Test
    void twoValuesAreEqualWhenTheyHoldTheSame() throws JsonException {
        assertEquals(JsonReader.read("{\"a\":[1,true,null,\"x\"]}"), JsonReader.read(" { \"a\" : [ 1, true, null, \"x\" ] } "));
        assertFalse(JsonReader.read("1").equals(JsonReader.read("\"1\"")));
        assertFalse(JsonReader.read("1.0").equals(JsonReader.read("1")), "a number is the text it was written with");
    }

    // ---- making and writing

    @Test
    void anObjectIsWrittenMemberAfterMemberInTheOrderTheyWerePut() {
        Json object = Json.object()
                .put("text", "a")
                .put("none", (String) null)
                .put("whole", 60)
                .put("long", 10_000_000_000L)
                .put("decimal", 186.02)
                .put("yes", true)
                .put("boxed", (Integer) null)
                .put("nested", Json.object().put("list", Json.texts(List.of("x", "y"))))
                .put("missing", (Json) null);
        assertEquals("{\"text\":\"a\",\"none\":null,\"whole\":60,\"long\":10000000000,\"decimal\":186.02,\"yes\":true,\"boxed\":null,"
                + "\"nested\":{\"list\":[\"x\",\"y\"]},\"missing\":null}", JsonWriter.compact(object));
    }

    @Test
    void aKeyThatIsPutAgainKeepsItsPlace() {
        assertEquals("{\"a\":3,\"b\":2}", Json.object().put("a", 1).put("b", 2).put("a", 3).toString());
    }

    @Test
    void aNumberWithDecimalsIsWrittenInItsShortestForm() {
        assertEquals("186.02", Json.number(186.02).toString());
        assertEquals("1000.0", Json.number(1000.0).toString(), "always with a decimal, so that it is seen to be one");
        assertEquals("0.30000000000000004", Json.number(0.1 + 0.2).toString());
        assertEquals("1.0E10", Json.number(1.0E10).toString());
        assertEquals("1.0E-5", Json.number(1.0E-5).toString());
        assertEquals("1000.00", Json.number("1000.00").toString(), "and by its text, exactly that");
        assertThrows(IllegalArgumentException.class, () -> Json.number(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.number(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Json.number("01"));
        assertThrows(IllegalArgumentException.class, () -> Json.number("1,000.00"));
    }

    @Test
    void onlyAnObjectTakesMembersAndOnlyAnArrayItems() {
        assertThrows(IllegalStateException.class, () -> Json.array().put("a", 1));
        assertThrows(IllegalStateException.class, () -> Json.object().add("a"));
        assertThrows(IllegalStateException.class, () -> Json.NULL.put("a", 1));
    }

    @Test
    void inATextOnlyWhatJsonRequiresIsEscaped() {
        assertEquals("\"\\\" \\\\ / \\b \\f \\n \\r \\t\"", Json.text("\" \\ / \b \f \n \r \t").toString());
        assertEquals("\"\\u0000\\u0001\\u001F\"", Json.text("\u0000\u0001\u001f").toString(), "the other control characters, by their number");
        assertEquals("\"<b>&amp;</b> \u0394 \u20ac \u007f \u2028\"", Json.text("<b>&amp;</b> \u0394 \u20ac \u007f \u2028").toString(),
                "everything else as it is");
    }

    @Test
    void overSeveralLinesAnObjectBreaksAndAnArrayDoesNot() throws JsonException {
        Json value = JsonReader.read(
                "{\"usageIntervalSeconds\":60,\"timeFormat\":\"hh:mm\",\"fakeBudget\":1000.00,\"costMonthlyLimit\":null,"
                        + "\"columns\":[\"time\",\"used\"],\"none\":[],\"empty\":{},\"spend\":{\"used\":{\"amount_minor\":18602}},"
                        + "\"limits\":[{\"a\":1},{\"b\":[{\"c\":{}}]}]}");
        assertEquals(String.join("\n",
                "{",
                "  \"usageIntervalSeconds\" : 60,",
                "  \"timeFormat\" : \"hh:mm\",",
                "  \"fakeBudget\" : 1000.00,",
                "  \"costMonthlyLimit\" : null,",
                "  \"columns\" : [ \"time\", \"used\" ],",
                "  \"none\" : [ ],",
                "  \"empty\" : { },",
                "  \"spend\" : {",
                "    \"used\" : {",
                "      \"amount_minor\" : 18602",
                "    }",
                "  },",
                "  \"limits\" : [ {",
                "    \"a\" : 1",
                "  }, {",
                "    \"b\" : [ {",
                "      \"c\" : { }",
                "    } ]",
                "  } ]",
                "}"), JsonWriter.pretty(value, "\n"));
    }

    @Test
    void theEndOfALineIsWhatIsAskedFor() {
        assertEquals("{\r\n  \"a\" : 1\r\n}", JsonWriter.pretty(Json.object().put("a", 1), "\r\n"));
        assertEquals("[ 1, 2 ]", JsonWriter.pretty(Json.array().add(Json.number(1)).add(Json.number(2)), "\r\n"));
        assertEquals("{ }", JsonWriter.pretty(Json.object(), "\n"));
        assertEquals("\"a\"", JsonWriter.pretty(Json.text("a"), "\n"));
    }

    @Test
    void whatIsWrittenIsReadBackAsTheSame() throws JsonException {
        Json value = Json.object()
                .put("text", "say \"hello\"\n\u0001 \u20ac")
                .put("numbers", Json.array().add(Json.number(-5)).add(Json.number(0.5)).add(Json.number("1000.00")))
                .put("flags", Json.array().add(Json.TRUE).add(Json.FALSE).add(Json.NULL))
                .put("nested", Json.object().put("empty", Json.object()).put("none", Json.array()));
        assertEquals(value, JsonReader.read(JsonWriter.compact(value)));
        assertEquals(value, JsonReader.read(JsonWriter.pretty(value, "\r\n")));
    }
}
