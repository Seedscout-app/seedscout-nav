package app.seedscout.nav.protocol;

/**
 * A route label that has already been reduced to inert literal text.
 *
 * <p>This type exists because {@code label} is the highest risk field in the whole
 * protocol: it is the only attacker controlled string that reaches a render surface.
 * Section 4.2 says it is "display text only, never parsed, never used to look anything
 * up", and that rule is enforced HERE, by the type system, rather than by a comment
 * somebody has to remember:
 *
 * <ul>
 *   <li>{@link RouteFrame} carries a {@code SafeLabel}, not a {@code String}, so there is
 *       no raw wire string in scope at the render call site to pass by accident.</li>
 *   <li>The only accessor is {@link #literalText()}. Its name is the contract: the value
 *       MUST be rendered as literal text. It MUST NOT be used as a translation key, MUST
 *       NOT be parsed as a text component (JSON or otherwise), MUST NOT be fed to any
 *       formatting, selector, NBT or command parser, and MUST NOT be used to look
 *       anything up.</li>
 *   <li>{@link #toString()} deliberately does NOT return the label, so a stray string
 *       concatenation cannot quietly turn this back into unlabelled text.</li>
 * </ul>
 *
 * <p>What {@link #of} removes, and why:
 * <ul>
 *   <li>Section sign formatting codes (the section sign U+00A7 plus its code character). Left in,
 *       they let a remote peer paint arbitrary colours, obfuscation (the k code) or
 *       bold text onto the player's HUD.</li>
 *   <li>All Unicode control characters (category Cc) including newline and tab, which
 *       would otherwise break out of a single line HUD element.</li>
 *   <li>All Unicode format characters (category Cf). This is the bidirectional override
 *       family (U+202A..U+202E, U+2066..U+2069) plus zero width joiners: they let text
 *       render in an order that does not match its code points, which is a spoofing
 *       vector on any label a player is asked to trust.</li>
 * </ul>
 *
 * <p>Truncation to {@link NavProtocol#MAX_LABEL_CODE_POINTS} happens AFTER stripping, so
 * padding a label with removable characters cannot smuggle a longer visible string
 * through, and it counts code points rather than {@code char}s so a truncation can never
 * split a surrogate pair into an unpaired half.
 */
public final class SafeLabel {

    private static final char SECTION_SIGN = '\u00A7';

    private final String literal;

    private SafeLabel(String literal) {
        this.literal = literal;
    }

    /** The empty label, used when a {@code route} frame carries no usable label. */
    public static final SafeLabel EMPTY = new SafeLabel("");

    /**
     * Reduces an untrusted wire string to a literal label. Never throws, never returns
     * null: any input, including one made entirely of formatting codes, produces a valid
     * (possibly empty) label.
     */
    public static SafeLabel of(String raw) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        StringBuilder stripped = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int codePoint = raw.codePointAt(i);
            int width = Character.charCount(codePoint);
            i += width;
            if (codePoint == SECTION_SIGN) {
                // Drop the code character that follows too, so a label of the section sign, then "a", then "Town", reads
                // "Town" rather than "aTown". Only a real code character is eaten: a
                // trailing lone section sign eats nothing else.
                if (i < raw.length() && isFormattingCode(raw.charAt(i))) {
                    i++;
                }
                continue;
            }
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL || type == Character.FORMAT) {
                continue;
            }
            stripped.appendCodePoint(codePoint);
        }
        String result = stripped.toString();
        int codePointCount = result.codePointCount(0, result.length());
        if (codePointCount > NavProtocol.MAX_LABEL_CODE_POINTS) {
            int end = result.offsetByCodePoints(0, NavProtocol.MAX_LABEL_CODE_POINTS);
            result = result.substring(0, end);
        }
        return result.isEmpty() ? EMPTY : new SafeLabel(result);
    }

    private static boolean isFormattingCode(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O')
                || c == 'r' || c == 'R';
    }

    /**
     * The label, as LITERAL TEXT. See the class documentation: a caller renders this
     * string verbatim and never treats it as a key, a component, or anything parseable.
     */
    public String literalText() {
        return literal;
    }

    public boolean isEmpty() {
        return literal.isEmpty();
    }

    @Override
    public String toString() {
        // Not the label. See the class documentation.
        return "SafeLabel(" + literal.length() + " chars)";
    }
}
