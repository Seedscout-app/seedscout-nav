package app.seedscout.nav.protocol;

import java.util.Objects;

/**
 * The result of trying to read one inbound text frame: either a fully checked
 * {@link InboundFrame}, or a drop.
 *
 * <p>Modelled as a sealed result rather than an exception because section 4 makes dropping
 * the NORMAL case, not the exceptional one: "unknown types MUST be ignored rather than
 * treated as an error". A decoder that threw would push every caller into a try/catch
 * whose empty catch block is indistinguishable from a bug, and the one thing this seam
 * must never do is let a hostile frame escape as a stack unwind on a network thread.
 * {@link NavCodec#decode} therefore never throws, for any input at all.
 *
 * <p>An {@link Accepted} instance is a promise: every bound in
 * {@code shared/nav_protocol.md} section 4.2 has already been checked, so nothing
 * downstream needs to re-validate and nothing downstream is holding a raw wire value.
 */
public sealed interface Inbound permits Inbound.Accepted, Inbound.Dropped {

    /** A frame that passed every check. */
    record Accepted(InboundFrame frame) implements Inbound {
        public Accepted {
            Objects.requireNonNull(frame, "frame");
        }
    }

    /**
     * A frame that was thrown away. Carries no fragment of the offending input, only the
     * {@link DropReason}, so a caller cannot accidentally log attacker controlled text.
     */
    record Dropped(DropReason reason) implements Inbound {
        public Dropped {
            Objects.requireNonNull(reason, "reason");
        }

        /**
         * Always null. Present so a caller that pattern matches loosely still cannot end
         * up holding a half-validated frame from a drop.
         */
        public InboundFrame frameOrNull() {
            return null;
        }
    }

    static Inbound accept(InboundFrame frame) {
        return new Accepted(frame);
    }

    static Inbound drop(DropReason reason) {
        return new Dropped(reason);
    }
}
