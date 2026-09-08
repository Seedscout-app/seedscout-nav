package app.seedscout.nav.client;

import app.seedscout.nav.client.gui.PairScreen;
import app.seedscout.nav.client.particle.SeedscoutNavParticles;
import app.seedscout.nav.client.render.RouteHud;
import app.seedscout.nav.client.render.RouteRenderer;
import app.seedscout.nav.protocol.SaveIdentity;
import app.seedscout.nav.protocol.UnlinkFrame;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything this worker's owned {@code client/} package needs wired into Fabric's lifecycle,
 * behind a single {@link #init()} entry point.
 *
 * <p>Called from {@code SeedscoutNavClient.onInitializeClient()}, the mod's
 * {@code fabric.mod.json} client entrypoint.
 */
public final class SeedscoutNavClientHooks {

    private static final KeyMapping OPEN_PAIR_SCREEN = new KeyMapping(
            "key.seedscout-nav.pair",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_N,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("seedscout-nav", "pair")));

    /**
     * Set when the client level changed, cleared once the change has been evaluated. See
     * {@link #resendWorldIfSaveChanged()} for why the evaluation cannot happen inside the level
     * change event itself. Both writers run on the game thread; atomic anyway because it costs
     * nothing and removes the question.
     */
    private static final AtomicBoolean LEVEL_CHANGE_PENDING = new AtomicBoolean();

    private SeedscoutNavClientHooks() {
    }

    public static void init() {
        // Must run before any particle can be spawned: this both registers the FULLBRIGHT_TRAIL
        // ParticleType (as a side effect of first touching SeedscoutNavParticles) and its
        // client-side rendering provider. See SeedscoutNavParticles for why this is this mod's
        // first registered content and the exact API this relies on.
        SeedscoutNavParticles.init();

        KeyMappingHelper.registerKeyMapping(OPEN_PAIR_SCREEN);

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("seedscout-nav", "route_distance"), RouteHud.INSTANCE);

        ClientTickEvents.END_CLIENT_TICK.register(SeedscoutNavClientHooks::onEndClientTick);

        // THE PLAYER ACTUALLY LEFT. This event, not a level change, is what "left the world"
        // means: quitting to the title screen, disconnecting, or being kicked all tear down the
        // play network handler, and none of them are survivable by a link that is bound to one
        // world. PLAYER_LEFT_WORLD is the existing reason for exactly this and needs no new
        // enum constant; section 4.1's own example of an unlink uses it.
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, minecraft) -> NavSession.INSTANCE.endSession(UnlinkFrame.PLAYER_LEFT_WORLD));

        // A LEVEL CHANGE IS NOT THAT. This event fires for a nether portal as readily as for a
        // different save, and it used to end the session, so walking through a portal killed
        // the link. It now only arms a check; see resendWorldIfSaveChanged().
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(
                (minecraft, newLevel) -> LEVEL_CHANGE_PENDING.set(true));

        // Client shutdown: the fourth teardown trigger.
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                minecraft -> NavSession.INSTANCE.endSession(UnlinkFrame.CLIENT_SHUTDOWN));
    }

    private static void onEndClientTick(net.minecraft.client.Minecraft minecraft) {
        ClientNavState.INSTANCE.updateFromGameThread();
        resendWorldIfSaveChanged();
        RouteRenderer.INSTANCE.onClientTick();

        while (OPEN_PAIR_SCREEN.consumeClick()) {
            openPairScreen(minecraft);
        }
    }

    /**
     * Answers the one question a level change leaves open: was that a portal, or a different
     * save? {@link SaveIdentity} holds the discrimination itself and the reasoning behind it;
     * this method supplies the two things that only the client layer knows, namely WHEN it is
     * safe to ask and what to do with the answer.
     *
     * <p>WHY THIS RUNS ON THE TICK RATHER THAN INSIDE THE EVENT. {@link ClientNavState}
     * republishes its snapshot once per client tick, so at the instant
     * {@code AFTER_CLIENT_LEVEL_CHANGE} fires the snapshot still describes the level the player
     * just left. Resending from inside the event would send the OLD world's seed and spawn as
     * if it were the new one, which is worse than not resending at all. Deferring by one tick
     * costs 50 ms and guarantees the frame describes the level it is announcing.
     *
     * <p>The flag stays armed while the snapshot is null, which is the case for as many ticks as
     * the new level takes to become usable. That is deliberate and is the same
     * never-miss-a-real-change bias {@link SaveIdentity#requiresWorldResend} is built on: the
     * check is postponed until it can be answered, never dropped.
     */
    private static void resendWorldIfSaveChanged() {
        if (!LEVEL_CHANGE_PENDING.get()) {
            return;
        }
        ClientNavState.Snapshot snapshot = ClientNavState.INSTANCE.currentSnapshot();
        if (snapshot == null) {
            // The new level has not produced a snapshot yet. Stay armed and ask again next tick.
            return;
        }
        LEVEL_CHANGE_PENDING.set(false);
        if (SaveIdentity.requiresWorldResend(
                ClientNavState.INSTANCE.lastSentSaveIdentity(), snapshot.saveIdentity())) {
            // A no-op unless a session is actually linked, so this is safe to call regardless.
            NavSession.INSTANCE.resendWorld();
        }
    }

    private static void openPairScreen(net.minecraft.client.Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            // No world loaded (e.g. still on the title screen): nothing to pair.
            return;
        }
        NavSession.INSTANCE.openPairing().ifPresent(server -> {
            minecraft.setScreenAndShow(new PairScreen(server));
        });
    }
}
