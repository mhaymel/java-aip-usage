package org.example.json;

import java.nio.charset.StandardCharsets;

/**
 * Reads a JSON text into a {@link Json}, strictly as RFC 8259 says: no comment, no single quote, no comma before a
 * closing bracket, no number with a leading zero or plus sign, no control character in a text that is not escaped,
 * and nothing but white space after the value.
 *
 * <p>A key that comes twice in an object has its last value, at the place of the first.
 */
public final class JsonReader {

    /** How deep objects and arrays may be nested; a text that goes deeper is refused rather than followed. */
    static final int MAX_DEPTH = 1000;

    private final String text;

    private int at;

    private JsonReader(String text) {
        this.text = text;
    }

    /**
     * @return the value, or null if the text is empty or white space only: there is no value in it, which is not the
     *     same as a text that is wrong
     * @throws JsonException if the text is not JSON
     */
    public static Json read(String text) throws JsonException {
        JsonReader reader = new JsonReader(text);
        reader.skipSpace();
        if (reader.at == text.length()) {
            return null;
        }
        Json value = reader.value(0);
        reader.skipSpace();
        if (reader.at < text.length()) {
            throw reader.fault("there is text after the value");
        }
        return value;
    }

    /** As {@link #read(String)}, for bytes that are UTF-8; a byte order mark at the start is passed over. */
    public static Json read(byte[] bytes) throws JsonException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return read(text.startsWith("﻿") ? text.substring(1) : text);
    }

    private Json value(int depth) throws JsonException {
        if (at == text.length()) {
            throw fault("a value was expected");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return Json.text(string());
            case 't':
                return word("true", Json.TRUE);
            case 'f':
                return word("false", Json.FALSE);
            case 'n':
                return word("null", Json.NULL);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw fault("a value was expected");
        }
    }

    private Json object(int depth) throws JsonException {
        if (depth == MAX_DEPTH) {
            throw fault("it is nested more than " + MAX_DEPTH + " deep");
        }
        Json object = Json.object();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            if (peek() != '"') {
                throw fault("a key in double quotes was expected");
            }
            String key = string();
            skipSpace();
            if (peek() != ':') {
                throw fault("':' was expected after a key");
            }
            at++;
            skipSpace();
            object.put(key, value(depth + 1));
            skipSpace();
            char next = peek();
            at++;
            if (next == '}') {
                return object;
            }
            if (next != ',') {
                at--;
                throw fault("',' or '}' was expected");
            }
            skipSpace();
        }
    }

    private Json array(int depth) throws JsonException {
        if (depth == MAX_DEPTH) {
            throw fault("it is nested more than " + MAX_DEPTH + " deep");
        }
        Json array = Json.array();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value(depth + 1));
            skipSpace();
            char next = peek();
            at++;
            if (next == ']') {
                return array;
            }
            if (next != ',') {
                at--;
                throw fault("',' or ']' was expected");
            }
            skipSpace();
        }
    }

    /** The text between the double quote at this place and the one that closes it, its escapes resolved. */
    private String string() throws JsonException {
        StringBuilder value = new StringBuilder();
        at++;
        while (true) {
            if (at == text.length()) {
                throw fault("the text is not closed");
            }
            char c = text.charAt(at);
            if (c == '"') {
                at++;
                return value.toString();
            }
            if (c < 0x20) {
                throw fault("a control character in a text must be escaped");
            }
            at++;
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (at == text.length()) {
                throw fault("the text is not closed");
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(fourHex());
                default -> {
                    at--;
                    throw fault("an escape that JSON does not have");
                }
            }
        }
    }

    private char fourHex() throws JsonException {
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = at < text.length() ? Character.digit(text.charAt(at), 16) : -1;
            if (digit < 0) {
                throw fault("four hexadecimal digits were expected after \\u");
            }
            code = code * 16 + digit;
            at++;
        }
        return (char) code;
    }

    private Json number() throws JsonException {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        if (peek() == '0') {
            at++;
        } else if (!digits()) {
            throw faultAt(start, "a number was expected");
        }
        if (peek() == '.') {
            at++;
            if (!digits()) {
                throw fault("a digit was expected after the point");
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            at++;
            if (peek() == '+' || peek() == '-') {
                at++;
            }
            if (!digits()) {
                throw fault("a digit was expected in the exponent");
            }
        }
        char after = peek();
        if (after >= '0' && after <= '9') {
            throw faultAt(start, "a number may not begin with a zero that other digits follow");
        }
        return Json.number(text.substring(start, at));
    }

    /** Passes over the digits at this place; whether there was one. */
    private boolean digits() {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            at++;
        }
        return at > start;
    }

    private Json word(String word, Json value) throws JsonException {
        if (!text.startsWith(word, at)) {
            throw fault("a value was expected");
        }
        at += word.length();
        return value;
    }

    /** The character at this place, or the character 0 at the end of the text, which is no character of JSON. */
    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            at++;
        }
    }

    private JsonException fault(String what) {
        return faultAt(at, what);
    }

    /** A fault at a place of the text, which is said as a line and a column, both counted from 1. */
    private JsonException faultAt(int place, String what) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < place && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(line, column, what);
    }
}
