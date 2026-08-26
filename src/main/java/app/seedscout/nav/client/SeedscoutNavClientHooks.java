package app.seedscout.nav.client;

import app.seedscout.nav.client.gui.PairScreen;
import app.seedscout.nav.client.render.RouteHud;
import app.seedscout.nav.client.render.RouteRenderer;
import app.seedscout.nav.protocol.UnlinkFrame;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

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

    private SeedscoutNavClientHooks() {
    }

    public static void init() {
        KeyMappingHelper.registerKeyMapping(OPEN_PAIR_SCREEN);

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("seedscout-nav", "route_distance"), RouteHud.INSTANCE);

        ClientTickEvents.END_CLIENT_TICK.register(SeedscoutNavClientHooks::onEndClientTick);

        // World change (dimension change counts as a level change too): the task brief's
        // "hook client tick and world-change events so the session ends and the listener
        // closes on world change" trigger.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(
                (minecraft, newLevel) -> NavSession.INSTANCE.endSession(UnlinkFrame.PLAYER_LEFT_WORLD));

        // Client shutdown: the fourth teardown trigger.
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                minecraft -> NavSession.INSTANCE.endSession(UnlinkFrame.CLIENT_SHUTDOWN));
    }

    private static void onEndClientTick(net.minecraft.client.Minecraft minecraft) {
        ClientNavState.INSTANCE.updateFromGameThread();
        RouteRenderer.INSTANCE.onClientTick();

        while (OPEN_PAIR_SCREEN.consumeClick()) {
            openPairScreen(minecraft);
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
