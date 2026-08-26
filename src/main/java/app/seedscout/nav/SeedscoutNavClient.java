package app.seedscout.nav;

import app.seedscout.nav.client.SeedscoutNavClientHooks;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint declared in {@code fabric.mod.json}. Delegates all registration
 * (keybind, HUD, world-change and shutdown teardown) to {@link SeedscoutNavClientHooks#init()}.
 */
public class SeedscoutNavClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("seedscout-nav");

    @Override
    public void onInitializeClient() {
        SeedscoutNavClientHooks.init();
        LOGGER.info("Seedscout Nav initialized on Minecraft 26.2.");
    }
}
