package app.seedscout.nav.client;

import app.seedscout.nav.link.RenderSink;
import app.seedscout.nav.link.WorldSource;
import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.WorldSnapshot;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The single bridge between live Minecraft state and the {@code link} package's two narrow
 * interfaces ({@link WorldSource}, {@link RenderSink}), per the integrator's brief: implement
 * the interfaces the link worker defined rather than reaching around them.
 *
 * <p>Threading, per both interfaces' documentation: {@link #updateFromGameThread()} runs once
 * per client tick, on the game thread, and publishes a single immutable {@link Snapshot}
 * through a volatile field. Every {@link WorldSource} and {@link RenderSink} method here is
 * called from {@code link}'s own threads (the session's scheduled sampler, or a connection's
 * virtual read thread) and only ever reads that published snapshot, never touches a live
 * Minecraft object directly. This is the "reading a volatile snapshot the game thread
 * publishes each tick" pattern {@link WorldSource}'s own documentation asks for.
 *
 * <p>{@link #currentRoute()} is written here (by {@link #showRoute}, off the game thread) and
 * read by {@link app.seedscout.nav.client.render.RouteRenderer} on the game thread every tick;
 * a plain volatile field is enough for that single-writer-many-reader publication, the same
 * way {@link #snapshot} is.
 */
public final class ClientNavState implements WorldSource, RenderSink {

    public static final ClientNavState INSTANCE = new ClientNavState();

    private volatile Snapshot snapshot;
    private volatile RouteFrame currentRoute;

    private ClientNavState() {
    }

    /** Everything the link layer can ask for about the player and world, as of one tick. */
    public record Snapshot(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            String seed,
            String mcVersion,
            int spawnX,
            int spawnZ) {
    }

    /** Called once per client tick, from the game thread only. See the class documentation. */
    void updateFromGameThread() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            snapshot = null;
            return;
        }
        String dimension = level.dimension().identifier().getPath();
        String seed = readSingleplayerSeed(mc, level);
        BlockPos spawn = level.getRespawnData().pos();
        snapshot = new Snapshot(
                dimension,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                seed,
                SharedConstants.getCurrentVersion().name(),
                spawn.getX(),
                spawn.getZ());
    }

    /**
     * The world seed is only ever knowable client-side when this client is also running the
     * integrated (singleplayer/LAN-hosted) server; every other case, including every real
     * multiplayer server, correctly reports {@code null} per section 4.1.
     */
    private static String readSingleplayerSeed(Minecraft mc, ClientLevel level) {
        if (!mc.hasSingleplayerServer()) {
            return null;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null) {
            serverLevel = server.getLevel(Level.OVERWORLD);
        }
        return serverLevel == null ? null : WorldSnapshot.seedString(serverLevel.getSeed());
    }

    // -----------------------------------------------------------------
    // WorldSource
    // -----------------------------------------------------------------

    @Override
    public WorldSnapshot worldSnapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            // confirm() only ever runs while a session is live in a loaded world, so this is
            // a defensive fallback rather than an expected path: report "no map" rather than
            // fail the confirm outright.
            return new WorldSnapshot(WorldSnapshot.EDITION_JAVA, "unknown", "unknown", null, 0, 0);
        }
        return new WorldSnapshot(
                WorldSnapshot.EDITION_JAVA, s.mcVersion(), s.dimension(), s.seed(), s.spawnX(), s.spawnZ());
    }

    @Override
    public PlayerPosition playerPosition() {
        Snapshot s = snapshot;
        if (s == null) {
            return null;
        }
        return new PlayerPosition(s.x(), s.y(), s.z(), s.yaw(), s.dimension());
    }

    // -----------------------------------------------------------------
    // RenderSink
    // -----------------------------------------------------------------

    @Override
    public void showRoute(RouteFrame route) {
        Snapshot s = snapshot;
        String currentDimension = s == null ? null : s.dimension();
        if (!route.matchesDimension(currentDimension)) {
            return;
        }
        if (!route.supersedes(currentRoute)) {
            return;
        }
        currentRoute = route;
    }

    @Override
    public void clearRoute() {
        currentRoute = null;
    }

    /** Read by {@link app.seedscout.nav.client.render.RouteRenderer} every client tick. */
    public RouteFrame currentRoute() {
        return currentRoute;
    }

    /** Read by {@link app.seedscout.nav.client.render.RouteRenderer} every client tick. */
    public Snapshot currentSnapshot() {
        return snapshot;
    }
}
