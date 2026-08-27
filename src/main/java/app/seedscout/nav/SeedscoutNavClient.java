package app.seedscout.nav;

import app.seedscout.nav.client.SeedscoutNavClientHooks;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint declared in {@code fabric.mod.json}. Delegates all registration
 * (keybind, HUD, tick, level change, disconnect and shutdown teardown) to
 * {@link SeedscoutNavClientHooks#init()}.
 *
 * <p>THIS DELEGATION IS THE WHOLE POINT OF THIS CLASS, and it has been broken before: an
 * earlier version's {@code onInitializeClient()} only logged, so the shipped jar did nothing
 * at runtime while the build and every test stayed green. {@code ClientEntrypointWiringTest}
 * exists to stop that recurring; do not remove the {@code init()} call below.
 */
public class SeedscoutNavClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("seedscout-nav");

    @Override
    public void onInitializeClient() {
        SeedscoutNavClientHooks.init();
        LOGGER.info("Seedscout Nav initialized on Minecraft 26.2.");
    }
}
