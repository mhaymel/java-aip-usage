package org.example.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A JSON value: an object, an array, a text, a number, true or false, or null. It is the program's own, so that
 * what is read and what is written depends on no library; {@link JsonReader} makes one from a text and
 * {@link JsonWriter} writes one.
 *
 * <p>An object keeps its keys in the order they were read or put. A number keeps the text it was written with, so
 * {@code 1000.00} stays {@code 1000.00} and {@code 45.0} is not taken for the whole number 45. Nothing is turned into
 * something else unless one of the {@code as…} questions asks for it.
 */
public final class Json {

    /** What a value is. */
    public enum Kind {
        OBJECT, ARRAY, TEXT, NUMBER, BOOL, NULL
    }

    public static final Json NULL = new Json(Kind.NULL, null);

    public static final Json TRUE = new Json(Kind.BOOL, "true");

    public static final Json FALSE = new Json(Kind.BOOL, "false");

    /** A number as JSON allows it: no leading zero or plus sign, digits on both sides of a point. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final Kind kind;

    /** The value of a text, the text of a number as written, {@code true} or {@code false}; null for the rest. */
    private final String text;

    private final Map<String, Json> members;

    private final List<Json> items;

    private Json(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
        this.members = kind == Kind.OBJECT ? new LinkedHashMap<>() : null;
        this.items = kind == Kind.ARRAY ? new ArrayList<>() : null;
    }

    // ---- making a value

    /** A new object with nothing in it. */
    public static Json object() {
        return new Json(Kind.OBJECT, null);
    }

    /** A new array with nothing in it. */
    public static Json array() {
        return new Json(Kind.ARRAY, null);
    }

    /** A text; no text is {@link #NULL}. */
    public static Json text(String value) {
        return value == null ? NULL : new Json(Kind.TEXT, value);
    }

    public static Json number(long value) {
        return new Json(Kind.NUMBER, Long.toString(value));
    }

    /**
     * A number with decimals, written in the shortest form that is this number and no other: {@code 186.02},
     * {@code 1000.0}.
     *
     * @throws IllegalArgumentException for a value JSON has no way to write: not a number, or infinite
     */
    public static Json number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("JSON has no number for " + value);
        }
        return new Json(Kind.NUMBER, Double.toString(value));
    }

    /**
     * A number by the text it is to be written with, such as {@code 1000.00}.
     *
     * @throws IllegalArgumentException if the text is not a number as JSON allows it
     */
    public static Json number(String written) {
        if (written == null || !NUMBER.matcher(written).matches()) {
            throw new IllegalArgumentException("Not a JSON number: " + written);
        }
        return new Json(Kind.NUMBER, written);
    }

    public static Json bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** A whole number, or {@link #NULL} for none. */
    public static Json of(Integer value) {
        return value == null ? NULL : number(value.longValue());
    }

    /** A whole number, or {@link #NULL} for none. */
    public static Json of(Long value) {
        return value == null ? NULL : number(value.longValue());
    }

    /** A number with decimals, or {@link #NULL} for none. */
    public static Json of(Double value) {
        return value == null ? NULL : number(value.doubleValue());
    }

    /** An array of texts. */
    public static Json texts(List<String> values) {
        Json array = array();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    // ---- filling an object or an array

    /**
     * Sets a member of this object; no value is {@link #NULL}. A key that is there keeps its place and has the new value.
     *
     * @return this object, so that an object can be written member after member
     * @throws IllegalStateException if this is not an object
     */
    public Json put(String key, Json value) {
        if (kind != Kind.OBJECT) {
            throw new IllegalStateException("Not an object: " + kind);
        }
        members.put(key, value == null ? NULL : value);
        return this;
    }

    public Json put(String key, String value) {
        return put(key, text(value));
    }

    public Json put(String key, long value) {
        return put(key, number(value));
    }

    public Json put(String key, double value) {
        return put(key, number(value));
    }

    public Json put(String key, boolean value) {
        return put(key, bool(value));
    }

    public Json put(String key, Integer value) {
        return put(key, of(value));
    }

    public Json put(String key, Long value) {
        return put(key, of(value));
    }

    public Json put(String key, Double value) {
        return put(key, of(value));
    }

    /**
     * Adds an item to this array; no value is {@link #NULL}.
     *
     * @return this array
     * @throws IllegalStateException if this is not an array
     */
    public Json add(Json value) {
        if (kind != Kind.ARRAY) {
            throw new IllegalStateException("Not an array: " + kind);
        }
        items.add(value == null ? NULL : value);
        return this;
    }

    public Json add(String value) {
        return add(text(value));
    }

    // ---- what a value is

    public Kind kind() {
        return kind;
    }

    public boolean isObject() {
        return kind == Kind.OBJECT;
    }

    public boolean isArray() {
        return kind == Kind.ARRAY;
    }

    public boolean isText() {
        return kind == Kind.TEXT;
    }

    public boolean isNumber() {
        return kind == Kind.NUMBER;
    }

    public boolean isBool() {
        return kind == Kind.BOOL;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    /** Whether this is a number written with no point and no exponent: {@code 45}, but neither {@code 45.0} nor {@code 4e1}. */
    public boolean isWhole() {
        return kind == Kind.NUMBER && text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
    }

    /** Whether this is a whole number that a {@code long} holds. */
    public boolean fitsLong() {
        if (!isWhole()) {
            return false;
        }
        try {
            Long.parseLong(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Whether this is a whole number that an {@code int} holds. */
    public boolean fitsInt() {
        if (!fitsLong()) {
            return false;
        }
        long value = Long.parseLong(text);
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    // ---- what a value holds, as it is

    /** The value of a text; null for anything that is not a text. */
    public String text() {
        return kind == Kind.TEXT ? text : null;
    }

    /** A number as it was written; null for anything that is not a number. */
    public String numberText() {
        return kind == Kind.NUMBER ? text : null;
    }

    /** Whether this is {@code true}; false for {@code false} and for anything that is not one of the two. */
    public boolean bool() {
        return this == TRUE;
    }

    /** A number as an {@code int}, its decimals cut off; 0 for anything that is not a number. Ask {@link #fitsInt} first where it matters. */
    public int intValue() {
        return fitsLong() ? (int) Long.parseLong(text) : (int) doubleValue();
    }

    /** A number as a {@code long}, its decimals cut off; 0 for anything that is not a number. */
    public long longValue() {
        return fitsLong() ? Long.parseLong(text) : (long) doubleValue();
    }

    /** A number as a {@code double}; 0 for anything that is not a number. */
    public double doubleValue() {
        if (kind != Kind.NUMBER) {
            return 0.0;
        }
        // A whole number by way of the whole number, so that -0 is the 0 it is as one.
        return fitsLong() ? (double) Long.parseLong(text) : Double.parseDouble(text);
    }

    // ---- members and items

    /** The member of this object with that key; null if there is none, or this is not an object. A member that is null in the JSON is {@link #NULL}. */
    public Json get(String key) {
        return kind == Kind.OBJECT ? members.get(key) : null;
    }

    /** The item of this array at that place, counted from 0; null if there is none, or this is not an array. */
    public Json get(int index) {
        return kind == Kind.ARRAY && index >= 0 && index < items.size() ? items.get(index) : null;
    }

    /** Whether this object has that key, whatever its value, null included. */
    public boolean has(String key) {
        return kind == Kind.OBJECT && members.containsKey(key);
    }

    /** The keys of this object in their order; none for anything else. */
    public Set<String> keys() {
        return kind == Kind.OBJECT ? Collections.unmodifiableSet(members.keySet()) : Set.of();
    }

    /** The items of this array in their order; none for anything else. */
    public List<Json> items() {
        return kind == Kind.ARRAY ? Collections.unmodifiableList(items) : List.of();
    }

    /** How many members this object or items this array has; 0 for anything else. */
    public int size() {
        return kind == Kind.OBJECT ? members.size() : kind == Kind.ARRAY ? items.size() : 0;
    }

    /**
     * The value at a path of keys and places, each after a slash: {@code /display/show/time}, {@code /lines/0/cells}.
     * The empty path is this value.
     *
     * @return the value, or null if the path leads nowhere
     */
    public Json at(String path) {
        Json value = this;
        if (path.isEmpty()) {
            return value;
        }
        for (String step : path.substring(1).split("/", -1)) {
            if (value == null) {
                return null;
            }
            if (value.kind == Kind.ARRAY) {
                try {
                    value = value.get(Integer.parseInt(step));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                value = value.get(step);
            }
        }
        return value;
    }

    // ---- what a value can be taken for

    /**
     * This value as a text: a text as it is, a number as it was written, {@code true}, {@code false} and {@code null}
     * by their names, and nothing for an object or an array.
     */
    public String asText() {
        return switch (kind) {
            case TEXT, NUMBER, BOOL -> text;
            case NULL -> "null";
            case OBJECT, ARRAY -> "";
        };
    }

    /** As {@link #asInt(int)}, with 0 for what is no number. */
    public int asInt() {
        return asInt(0);
    }

    /**
     * This value as a whole number: a number with its decimals cut off, a text that is the text of a number, 1 for
     * {@code true} and 0 for {@code false}.
     *
     * @param otherwise what anything else is
     */
    public int asInt(int otherwise) {
        return (int) asLong(otherwise);
    }

    /** As {@link #asLong(long)}, with 0 for what is no number. */
    public long asLong() {
        return asLong(0);
    }

    /** As {@link #asInt(int)}, for a {@code long}. */
    public long asLong(long otherwise) {
        switch (kind) {
            case NUMBER:
                return longValue();
            case BOOL:
                return bool() ? 1 : 0;
            case TEXT:
                String trimmed = text.trim();
                try {
                    return Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    try {
                        return (long) Double.parseDouble(trimmed);
                    } catch (NumberFormatException e2) {
                        return otherwise;
                    }
                }
            default:
                return otherwise;
        }
    }

    /** As {@link #asDouble(double)}, with 0 for what is no number. */
    public double asDouble() {
        return asDouble(0.0);
    }

    /**
     * This value as a number with decimals: a number, a text that is the text of one, 1 and 0 for {@code true} and
     * {@code false}.
     *
     * @param otherwise what anything else is
     */
    public double asDouble(double otherwise) {
        switch (kind) {
            case NUMBER:
                return doubleValue();
            case BOOL:
                return bool() ? 1.0 : 0.0;
            case TEXT:
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    return otherwise;
                }
            default:
                return otherwise;
        }
    }

    /** As {@link #asBoolean(boolean)}, with false for what says neither. */
    public boolean asBoolean() {
        return asBoolean(false);
    }

    /**
     * This value as yes or no: {@code true} and {@code false}, a whole number that is not 0 or is, and the texts
     * {@code true} and {@code false}.
     *
     * @param otherwise what anything else is
     */
    public boolean asBoolean(boolean otherwise) {
        switch (kind) {
            case BOOL:
                return bool();
            case NUMBER:
                return fitsLong() ? Long.parseLong(text) != 0 : otherwise;
            case TEXT:
                String trimmed = text.trim();
                return trimmed.equals("true") || (!trimmed.equals("false") && otherwise);
            default:
                return otherwise;
        }
    }

    // ---- as an object of Java

    /** This value as JSON on one line, as {@link JsonWriter#compact} writes it. */
    @Override
    public String toString() {
        return JsonWriter.compact(this);
    }

    /** Two values are equal when they are of one kind and hold the same; a number by the text it was written with. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Json that) || kind != that.kind) {
            return false;
        }
        return switch (kind) {
            case OBJECT -> members.equals(that.members);
            case ARRAY -> items.equals(that.items);
            case TEXT, NUMBER, BOOL -> text.equals(that.text);
            case NULL -> true;
        };
    }

    @Override
    public int hashCode() {
        return switch (kind) {
            case OBJECT -> members.hashCode();
            case ARRAY -> items.hashCode();
            case TEXT, NUMBER, BOOL -> text.hashCode();
            case NULL -> 0;
        };
    }
}
