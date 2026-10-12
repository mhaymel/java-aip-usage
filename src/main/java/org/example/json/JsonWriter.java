package org.example.json;

/**
 * Writes a {@link Json} as text, in one of two forms: on one line with no space in it, which is what the API sends,
 * and over several lines, which is what the settings file and a logged response have.
 *
 * <p>In a text only what JSON requires is escaped: the double quote, the backslash and the control characters.
 * Every other character is written as it is, {@code <}, {@code >}, {@code &} and those outside ASCII included.
 */
public final class JsonWriter {

    private static final String INDENT = "  ";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private JsonWriter() {
    }

    /** The value on one line: {@code {"a":1,"b":[true,null]}}. */
    public static String compact(Json value) {
        StringBuilder out = new StringBuilder();
        compact(value, out);
        return out.toString();
    }

    /**
     * The value over several lines. Each member of an object is on a line of its own, two spaces deeper than the
     * object, with a space on both sides of the colon; an array is on one line, with a space inside its brackets and
     * after each comma; an object or an array with nothing in it has one space in it.
     *
     * <pre>
     * {
     *   "usageIntervalSeconds" : 60,
     *   "columns" : [ "time", "used" ],
     *   "last" : { }
     * }
     * </pre>
     *
     * @param lineEnd what ends a line: a line feed, or a carriage return and a line feed
     */
    public static String pretty(Json value, String lineEnd) {
        StringBuilder out = new StringBuilder();
        pretty(value, 0, lineEnd, out);
        return out.toString();
    }

    private static void compact(Json value, StringBuilder out) {
        switch (value.kind()) {
            case OBJECT -> {
                out.append('{');
                boolean first = true;
                for (String key : value.keys()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(key, out);
                    out.append(':');
                    compact(value.get(key), out);
                }
                out.append('}');
            }
            case ARRAY -> {
                out.append('[');
                boolean first = true;
                for (Json item : value.items()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    compact(item, out);
                }
                out.append(']');
            }
            default -> scalar(value, out);
        }
    }

    /** @param depth how many objects this value is inside of; arrays do not count, since they do not break the line */
    private static void pretty(Json value, int depth, String lineEnd, StringBuilder out) {
        switch (value.kind()) {
            case OBJECT -> {
                if (value.size() == 0) {
                    out.append("{ }");
                    return;
                }
                out.append('{');
                boolean first = true;
                for (String key : value.keys()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(lineEnd).append(INDENT.repeat(depth + 1));
                    quote(key, out);
                    out.append(" : ");
                    pretty(value.get(key), depth + 1, lineEnd, out);
                }
                out.append(lineEnd).append(INDENT.repeat(depth)).append('}');
            }
            case ARRAY -> {
                if (value.size() == 0) {
                    out.append("[ ]");
                    return;
                }
                out.append("[ ");
                boolean first = true;
                for (Json item : value.items()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    pretty(item, depth, lineEnd, out);
                }
                out.append(" ]");
            }
            default -> scalar(value, out);
        }
    }

    private static void scalar(Json value, StringBuilder out) {
        switch (value.kind()) {
            case TEXT -> quote(value.text(), out);
            case NUMBER -> out.append(value.numberText());
            case BOOL -> out.append(value.bool() ? "true" : "false");
            default -> out.append("null");
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
