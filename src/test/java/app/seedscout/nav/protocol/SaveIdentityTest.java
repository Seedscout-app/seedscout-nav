package app.seedscout.nav.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discrimination that keeps a nether portal from killing the nav link.
 *
 * <p>What this CAN prove: given two save identities, {@link SaveIdentity#requiresWorldResend}
 * makes the right call, and the factories fail towards {@link SaveIdentity#UNKNOWN} rather than
 * towards a half-identity. That is the whole decision, and it needs no game to exercise.
 *
 * <p>What this CANNOT prove, stated so nobody reads a green bar as more than it is: that
 * {@code ClientNavState.readSaveIdentity} reads the right Minecraft objects, that Fabric fires
 * {@code AFTER_CLIENT_LEVEL_CHANGE} where expected, or that a real portal keeps a real link
 * alive. Those need a running client, which this project's headless test task does not have.
 */
class SaveIdentityTest {

    private static final String SAVE_A = "/saves/New World";
    private static final String SAVE_B = "/saves/New World (1)";
    private static final String SEED_A = "-4172144997902289642";
    private static final String SEED_B = "42";

    @Nested
    @DisplayName("the portal case: the bug this class exists to fix")
    class PortalCase {

        @Test
        @DisplayName("a dimension change within one save is not a save change")
        void dimensionIsNotPartOfIdentity() {
            // Nothing in a SaveIdentity varies by dimension, so the overworld and the nether of
            // one save produce the identical value and no resend is triggered.
            SaveIdentity overworld = SaveIdentity.singleplayer(SAVE_A, SEED_A);
            SaveIdentity nether = SaveIdentity.singleplayer(SAVE_A, SEED_A);

            assertFalse(SaveIdentity.requiresWorldResend(overworld, nether),
                    "walking through a nether portal must not resend the world frame, and above "
                            + "all must not be mistaken for a different save");
        }

        @Test
        @DisplayName("a multiplayer dimension change is not a save change either")
        void multiplayerDimensionIsNotPartOfIdentity() {
            SaveIdentity before = SaveIdentity.multiplayer("mc.example.net");
            SaveIdentity after = SaveIdentity.multiplayer("mc.example.net");

            assertFalse(SaveIdentity.requiresWorldResend(before, after));
        }
    }

    @Nested
    @DisplayName("a genuinely different save must always resend")
    class RealSaveChange {

        @Test
        @DisplayName("a different save directory resends")
        void differentDirectoryResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(SAVE_A, SEED_A),
                    SaveIdentity.singleplayer(SAVE_B, SEED_A)));
        }

        @Test
        @DisplayName("the same directory with a different seed resends")
        void differentSeedResends() {
            // Recreating a save in place keeps the directory and changes the seed. The seed is a
            // per-save value (ServerLevel.getSeed() reads the world-gen options), so it can
            // never differ between the dimensions of one save and is safe to compare here.
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(SAVE_A, SEED_A),
                    SaveIdentity.singleplayer(SAVE_A, SEED_B)));
        }

        @Test
        @DisplayName("a seed going from unread to read resends")
        void seedAppearingResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(SAVE_A, null),
                    SaveIdentity.singleplayer(SAVE_A, SEED_A)));
        }

        @Test
        @DisplayName("a different server resends")
        void differentServerResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.multiplayer("mc.example.net"),
                    SaveIdentity.multiplayer("other.example.net")));
        }

        @Test
        @DisplayName("singleplayer and multiplayer never compare equal on a coincident id")
        void kindDiscriminates() {
            String sameId = "example";
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(sameId, null),
                    SaveIdentity.multiplayer(sameId)));
            assertNotEquals(
                    SaveIdentity.singleplayer(sameId, null), SaveIdentity.multiplayer(sameId));
        }

        @Test
        @DisplayName("leaving singleplayer for a server resends")
        void singleplayerToMultiplayerResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(SAVE_A, SEED_A),
                    SaveIdentity.multiplayer("mc.example.net")));
        }
    }

    @Nested
    @DisplayName("the asymmetry: an unprovable identity resends rather than assuming unchanged")
    class FailsTowardsResending {

        @Test
        @DisplayName("UNKNOWN as the current identity resends")
        void unknownCurrentResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.singleplayer(SAVE_A, SEED_A), SaveIdentity.UNKNOWN));
        }

        @Test
        @DisplayName("UNKNOWN as the last sent identity resends")
        void unknownLastSentResends() {
            assertTrue(SaveIdentity.requiresWorldResend(
                    SaveIdentity.UNKNOWN, SaveIdentity.singleplayer(SAVE_A, SEED_A)));
        }

        @Test
        @DisplayName("two UNKNOWNs resend rather than compare equal")
        void unknownIsNeverEqualForThisPurpose() {
            // UNKNOWN equals UNKNOWN as a record, which is exactly why requiresWorldResend
            // cannot be a bare equality check: two unreadable identities say nothing about
            // whether the save changed, so the answer must be "resend".
            assertTrue(SaveIdentity.requiresWorldResend(SaveIdentity.UNKNOWN, SaveIdentity.UNKNOWN));
        }

        @Test
        @DisplayName("a null last sent identity, meaning nothing sent yet, resends")
        void nullLastSentResends() {
            assertTrue(SaveIdentity.requiresWorldResend(null, SaveIdentity.singleplayer(SAVE_A, SEED_A)));
        }

        @Test
        @DisplayName("a null current identity resends")
        void nullCurrentResends() {
            assertTrue(SaveIdentity.requiresWorldResend(SaveIdentity.singleplayer(SAVE_A, SEED_A), null));
        }
    }

    @Nested
    @DisplayName("factories collapse an unusable part to UNKNOWN instead of a half identity")
    class Factories {

        @Test
        void nullSaveDirectoryIsUnknown() {
            assertSame(SaveIdentity.UNKNOWN, SaveIdentity.singleplayer(null, SEED_A));
        }

        @Test
        void emptySaveDirectoryIsUnknown() {
            assertSame(SaveIdentity.UNKNOWN, SaveIdentity.singleplayer("", SEED_A));
        }

        @Test
        void nullServerAddressIsUnknown() {
            assertSame(SaveIdentity.UNKNOWN, SaveIdentity.multiplayer(null));
        }

        @Test
        void emptyServerAddressIsUnknown() {
            assertSame(SaveIdentity.UNKNOWN, SaveIdentity.multiplayer(""));
        }

        @Test
        @DisplayName("a multiplayer identity never carries a seed")
        void multiplayerSeedIsAlwaysNull() {
            assertSame(null, SaveIdentity.multiplayer("mc.example.net").seed());
        }

        @Test
        void unknownIsNotKnownAndAKnownOneIs() {
            assertFalse(SaveIdentity.UNKNOWN.isKnown());
            assertFalse(new SaveIdentity(SaveIdentity.KIND_SINGLEPLAYER, "", null).isKnown());
            assertFalse(new SaveIdentity(null, SAVE_A, null).isKnown());
            assertTrue(SaveIdentity.singleplayer(SAVE_A, SEED_A).isKnown());
            assertTrue(SaveIdentity.multiplayer("mc.example.net").isKnown());
        }
    }
}
