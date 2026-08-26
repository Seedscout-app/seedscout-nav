package app.seedscout.nav.protocol;

/**
 * A frame the mod sends to the app: section 4.1's {@code world}, {@code pos} and
 * {@code unlink}, and nothing else. Sealed so the encoder in {@link NavCodec} is a total
 * switch and adding a fourth outbound message is a compile error until every site that
 * handles frames has been revisited.
 */
public sealed interface OutboundFrame permits WorldSnapshot, PlayerPosition, UnlinkFrame {

    /** The {@code type} field this frame carries on the wire. */
    String type();
}
