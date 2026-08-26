package app.seedscout.nav.protocol;

/**
 * A frame the app sends to the mod. Sealed to exactly {@link RouteFrame} and
 * {@link ClearFrame}, which is section 1 rule 2 ("the message set is an allowlist, not a
 * command channel") expressed as a type.
 *
 * <p>Adding a third permitted subtype is a protocol version bump and a fresh look at the
 * threat model, not a convenience. Making the interface sealed means that decision cannot
 * be taken accidentally in a hurry: a new verb will not compile until this file changes.
 */
public sealed interface InboundFrame permits RouteFrame, ClearFrame {

    /** The {@code type} field this frame carried on the wire. */
    String type();
}
