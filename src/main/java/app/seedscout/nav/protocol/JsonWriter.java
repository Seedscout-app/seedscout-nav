package app.seedscout.nav.protocol;

/**
 * A tiny append-only JSON object writer, the encode half of {@link Json}.
 *
 * <p>Deliberately has no "write an arbitrary Object" entry point. Every field goes in
 * through a method that names its wire type, which is how the decimal-string seed rule
 * (section 4.1) is enforced by the API instead of by reviewer vigilance: there is simply
 * no way to hand this writer a {@code long} seed and have it come out as a JSON number,
 * because {@link #seed} only accepts the already-formatted decimal string.
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder(128);
    private boolean empty = true;

    JsonWriter() {
        out.append('{');
    }

    JsonWriter string(String key, String value) {
        separator();
        writeString(key);
        out.append(':');
        writeString(value);
        return this;
    }

    /**
     * The one and only way to write {@code world.seed}. It takes the DECIMAL STRING the
     * wire carries, never a numeric type, at any layer: Minecraft seeds exceed 2^53 and a
     * JSON number would be silently mangled by any parser that reads numbers as doubles,
     * which is every JavaScript and Dart parser. A null value writes JSON null, which
     * section 4.1 defines as "linked but no map".
     */
    JsonWriter seed(String key, String decimalStringOrNull) {
        separator();
        writeString(key);
        out.append(':');
        if (decimalStringOrNull == null) {
            out.append("null");
        } else {
            writeString(decimalStringOrNull);
        }
        return this;
    }

    JsonWriter integer(String key, long value) {
        separator();
        writeString(key);
        out.append(':').append(value);
        return this;
    }

    JsonWriter number(String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            // JSON has no encoding for these, and a player position can only be one of
            // them if something upstream is already broken. Fail here rather than emit a
            // frame the app will refuse as a protocol violation.
            throw new IllegalArgumentException("non finite number for key " + key);
        }
        separator();
        writeString(key);
        out.append(':').append(value);
        return this;
    }

    /** Writes {@code key: [[x,z],[x,z],...]}, the {@code route.points} shape. */
    JsonWriter points(String key, Iterable<RoutePoint> values) {
        separator();
        writeString(key);
        out.append(":[");
        boolean first = true;
        for (RoutePoint point : values) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('[').append(point.x()).append(',').append(point.z()).append(']');
        }
        out.append(']');
        return this;
    }

    String end() {
        return out.append('}').toString();
    }

    private void separator() {
        if (!empty) {
            out.append(',');
        }
        empty = false;
    }

    private void writeString(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
