package app.seedscout.nav.protocol;

/**
 * Which loaded save the client is in, as far as the client can tell, plus the rule for when a
 * level change obliges the mod to resend {@link WorldSnapshot}.
 *
 * <p>THE PROBLEM THIS EXISTS TO SOLVE. Fabric fires one client-level-change event for two very
 * different things: stepping through a nether portal (same save, new dimension) and loading a
 * genuinely different save. An earlier version of this mod ended the link on that event, so a
 * nether portal killed the session. Telling the two apart needs a value that is stable across
 * the dimensions of one save and different between saves, which is exactly what this record
 * is.
 *
 * <p><b>Note what is deliberately absent: there is no dimension component.</b> That is the
 * whole point. A portal produces an EQUAL identity and therefore no resend, and the app keeps
 * its live dimension from the {@code pos} cadence (section 4.1's {@code pos} frame carries
 * {@code dimension}), never from {@code world}.
 *
 * <p>THE ASYMMETRY THAT SHAPES THE RULE. Resending when the app did not need it and failing to
 * resend when it did are not equally bad, so the rule is not symmetric:
 *
 * <ul>
 *   <li>A REDUNDANT resend is close to free. {@code app/lib/data/nav_link_service.dart}
 *       documents (its "ambiguity 3") that a second {@code world} frame while linked
 *       overwrites the stored one rather than being ignored, because "the newer frame is
 *       definitionally more correct than the one already stored". It bumps a monotonic world
 *       epoch, and it raises the app's "You changed world" prompt only when the SEED differs.
 *       A redundant resend therefore carries the same seed and shows the user nothing.</li>
 *   <li>A MISSED resend is unrecoverable. The app goes on drawing the previous save's map
 *       indefinitely, with no signal that anything moved.</li>
 * </ul>
 *
 * <p>So this class is built to never miss a real save change and to accept a rare redundant
 * one. That is why {@link #UNKNOWN} on either side of a comparison means "resend" rather than
 * "assume unchanged": {@link #requiresWorldResend} answers "no" only when it can positively
 * prove both sides name the same save.
 *
 * <p>Lives in this package for the same reason the rest of it does: zero Minecraft imports, so
 * the whole decision is exercised headlessly by {@code gradlew test}. The Minecraft-facing
 * layer ({@code ClientNavState}) reads the game objects and calls the factories below; it is
 * the only thing here that could not be unit tested without a running client.
 *
 * @param kind {@link #KIND_SINGLEPLAYER} or {@link #KIND_MULTIPLAYER}, null when unknown
 * @param id the save directory (singleplayer) or the server address (multiplayer), null when
 *     unknown. Process local: never logged, never sent on the wire, never rendered.
 * @param seed the decimal seed string, or null. Always null for {@link #KIND_MULTIPLAYER},
 *     where the client genuinely cannot know it (section 4.1).
 */
public record SaveIdentity(String kind, String id, String seed) {

    /** This client is running the integrated server, so the save is a directory on disk. */
    public static final String KIND_SINGLEPLAYER = "singleplayer";

    /** This client is a guest on someone else's server, so the "save" is that server. */
    public static final String KIND_MULTIPLAYER = "multiplayer";

    /**
     * The value to use whenever the client cannot positively determine which save is loaded.
     * Never equal to a known identity, and {@link #requiresWorldResend} treats it as "resend",
     * so an unreadable identity fails in the safe direction rather than pinning the app to a
     * stale world.
     */
    public static final SaveIdentity UNKNOWN = new SaveIdentity(null, null, null);

    /**
     * A save on this machine's disk. {@code saveDirectory} is whatever path uniquely names the
     * save (the integrated server's world root); a missing or empty one yields {@link #UNKNOWN}
     * rather than a half-identity that could compare equal to another half-identity.
     *
     * <p>{@code seed} participates in equality as a second discriminator, which is sound
     * because it is a per-save value: {@code ServerLevel.getSeed()} reads the save's
     * world-generation options, so it is identical in the overworld, the nether and the end of
     * one save and cannot make a portal look like a new world.
     */
    public static SaveIdentity singleplayer(String saveDirectory, String seed) {
        if (saveDirectory == null || saveDirectory.isEmpty()) {
            return UNKNOWN;
        }
        return new SaveIdentity(KIND_SINGLEPLAYER, saveDirectory, seed);
    }

    /**
     * A remote server, named by the address the client connected to.
     *
     * <p>KNOWN LIMIT, stated rather than hidden: a proxy that moves a player between backend
     * servers keeps the same client-visible address, so this cannot see that transfer. Nothing
     * client side can. The blast radius is small because a multiplayer link reports a null seed
     * either way, so the app is already in its "linked but no map" mode and only the
     * {@code spawnX}/{@code spawnZ} of the {@code world} frame can go stale.
     */
    public static SaveIdentity multiplayer(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) {
            return UNKNOWN;
        }
        return new SaveIdentity(KIND_MULTIPLAYER, serverAddress, null);
    }

    /** False for {@link #UNKNOWN} and for any partially filled value. */
    public boolean isKnown() {
        return kind != null && !kind.isEmpty() && id != null && !id.isEmpty();
    }

    /**
     * Whether a {@code world} frame must be resent now that the client level has changed.
     *
     * <p>Answers false ONLY when both sides are known and identical. Every other case, an
     * unknown on either side or a nothing-sent-yet null, answers true, per the asymmetry in the
     * class documentation: a spurious extra resend is harmless, a missed one is not.
     *
     * @param lastSent the identity of the save the last {@code world} frame described, or null
     *     if no frame has been sent on this link yet
     * @param current the identity of the save the client is in now
     */
    public static boolean requiresWorldResend(SaveIdentity lastSent, SaveIdentity current) {
        if (lastSent == null || current == null) {
            return true;
        }
        if (!lastSent.isKnown() || !current.isKnown()) {
            return true;
        }
        return !lastSent.equals(current);
    }
}
