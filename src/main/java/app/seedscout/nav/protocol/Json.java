package app.seedscout.nav.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, bounded JSON reader for untrusted frames.
 *
 * <p>Hand rolled rather than pulled from a library on purpose. The mod ships to players
 * as a jar, so every dependency is attack surface and download weight, and what this
 * package needs is a parser that says NO to more inputs than a general purpose one does:
 *
 * <ul>
 *   <li>bounded nesting depth ({@link NavProtocol#MAX_JSON_DEPTH})</li>
 *   <li>duplicate object keys are a hard error, never last-write-wins, so a frame cannot
 *       smuggle a second {@code points} past a check that read the first one</li>
 *   <li>integers stay integers: an integral literal decodes to {@link Long}, never to a
 *       {@code double}. This is the same class of bug the protocol's decimal-string seed
 *       rule exists to prevent, and a parser that widens everything to double would
 *       reintroduce it on the inbound side</li>
 *   <li>no NaN, no Infinity, no leading zeros, no trailing commas, no comments, no
 *       trailing content after the top level value</li>
 *   <li>unescaped control characters inside strings are rejected</li>
 * </ul>
 *
 * <p>A JSON null decodes to the {@link #NULL} sentinel rather than a Java null, so a
 * caller can tell "the key was present and null" from "the key was absent". Section 4.1
 * makes that distinction meaningful for {@code seed}.
 */
public final class Json {

    private Json() {
    }

    /** Sentinel for a JSON {@code null}. Never a Java null, so absent and null stay distinct. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Thrown for any input this reader refuses. Carries no attacker controlled text. */
    public static final class SyntaxException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxException(String message) {
            super(message);
        }
    }

    /**
     * Parses {@code text} as a single JSON value.
     *
     * @return a {@link Map}, {@link List}, {@link String}, {@link Long}, {@link Double},
     *         {@link Boolean} or {@link #NULL}. Maps and lists are unmodifiable.
     * @throws SyntaxException on anything this reader refuses, including exceeding
     *         {@link NavProtocol#MAX_JSON_DEPTH}.
     */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        reader.skipWhitespace();
        Object value = reader.readValue(1);
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw new SyntaxException("trailing content after top level value");
        }
        return value;
    }

    private static final class Reader {
        private final String source;
        private int index;

        Reader(String source) {
            this.source = source;
        }

        boolean atEnd() {
            return index >= source.length();
        }

        void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            if (index >= source.length()) {
                throw new SyntaxException("unexpected end of input");
            }
            return source.charAt(index);
        }

        private char next() {
            char c = peek();
            index++;
            return c;
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw new SyntaxException("unexpected character");
            }
        }

        Object readValue(int depth) {
            if (depth > NavProtocol.MAX_JSON_DEPTH) {
                throw new SyntaxException("maximum nesting depth exceeded");
            }
            char c = peek();
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", NULL);
                default -> readNumber();
            };
        }

        private Object readLiteral(String literal, Object value) {
            if (!source.startsWith(literal, index)) {
                throw new SyntaxException("unexpected literal");
            }
            index += literal.length();
            return value;
        }

        private Map<String, Object> readObject(int depth) {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return Collections.unmodifiableMap(result);
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = readValue(depth + 1);
                if (result.put(key, value) != null) {
                    // Last write wins is how a smuggled duplicate key sneaks past a
                    // validator that inspected the first copy. Refuse the frame instead.
                    throw new SyntaxException("duplicate object key");
                }
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return Collections.unmodifiableMap(result);
                }
                if (c != ',') {
                    throw new SyntaxException("expected , or } in object");
                }
            }
        }

        private List<Object> readArray(int depth) {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return Collections.unmodifiableList(result);
            }
            while (true) {
                skipWhitespace();
                result.add(readValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return Collections.unmodifiableList(result);
                }
                if (c != ',') {
                    throw new SyntaxException("expected , or ] in array");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new SyntaxException("unescaped control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(readUnicodeEscape());
                    default -> throw new SyntaxException("invalid escape sequence");
                }
            }
        }

        private char readUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw new SyntaxException("truncated unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(source.charAt(index + i), 16);
                if (digit < 0) {
                    throw new SyntaxException("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            index += 4;
            return (char) value;
        }

        private Object readNumber() {
            int start = index;
            if (index < source.length() && source.charAt(index) == '-') {
                index++;
            }
            int intStart = index;
            while (index < source.length() && isDigit(source.charAt(index))) {
                index++;
            }
            int intDigits = index - intStart;
            if (intDigits == 0) {
                throw new SyntaxException("number has no integer part");
            }
            if (intDigits > 1 && source.charAt(intStart) == '0') {
                throw new SyntaxException("number has a leading zero");
            }
            boolean integral = true;
            if (index < source.length() && source.charAt(index) == '.') {
                integral = false;
                index++;
                int fracStart = index;
                while (index < source.length() && isDigit(source.charAt(index))) {
                    index++;
                }
                if (index == fracStart) {
                    throw new SyntaxException("number has no fraction digits");
                }
            }
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                integral = false;
                index++;
                if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    index++;
                }
                int expStart = index;
                while (index < source.length() && isDigit(source.charAt(index))) {
                    index++;
                }
                if (index == expStart) {
                    throw new SyntaxException("number has no exponent digits");
                }
            }
            String literal = source.substring(start, index);
            if (integral) {
                try {
                    return Long.valueOf(literal);
                } catch (NumberFormatException overflow) {
                    // An integral literal too big for a long is refused rather than
                    // silently widened to a double, which would lose exactly the
                    // precision this protocol cares about.
                    throw new SyntaxException("integer literal out of range");
                }
            }
            double parsed = Double.parseDouble(literal);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new SyntaxException("non finite number");
            }
            return Double.valueOf(parsed);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
