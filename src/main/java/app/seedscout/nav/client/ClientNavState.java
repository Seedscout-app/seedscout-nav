package app.seedscout.nav.client;

import app.seedscout.nav.link.RenderSink;
import app.seedscout.nav.link.WorldSource;
import app.seedscout.nav.protocol.PlayerPosition;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.SafeLabel;
import app.seedscout.nav.protocol.SaveIdentity;
import app.seedscout.nav.protocol.WorldSnapshot;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("seedscout-nav");

    public static final ClientNavState INSTANCE = new ClientNavState();

    private volatile Snapshot snapshot;
    private volatile RouteFrame currentRoute;
    private volatile SaveIdentity lastSentSaveIdentity;

    private ClientNavState() {
    }

    /**
     * Everything the link layer can ask for about the player and world, as of one tick.
     *
     * <p>{@code saveIdentity} is captured here, on the game thread, in the same pass that reads
     * the seed, so the identity and the {@link WorldSnapshot} built from this snapshot can never
     * describe two different saves.
     */
    public record Snapshot(
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            String seed,
            String mcVersion,
            int spawnX,
            int spawnZ,
            SaveIdentity saveIdentity) {
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
                spawn.getZ(),
                readSaveIdentity(mc, seed));
    }

    /**
     * Which save is loaded, for {@link SaveIdentity#requiresWorldResend}. Every failure to read
     * one of the parts answers {@link SaveIdentity#UNKNOWN}, which that method treats as
     * "resend": see its documentation for why an unreadable identity must fail towards a
     * redundant frame rather than towards a stale map.
     *
     * <p>Deliberately reads nothing dimension-dependent, so a nether portal produces an
     * identical value and the link survives it.
     */
    private static SaveIdentity readSaveIdentity(Minecraft mc, String seed) {
        if (mc.hasSingleplayerServer()) {
            IntegratedServer server = mc.getSingleplayerServer();
            if (server == null) {
                return SaveIdentity.UNKNOWN;
            }
            Path root;
            try {
                root = server.getWorldPath(LevelResource.ROOT);
            } catch (RuntimeException e) {
                return SaveIdentity.UNKNOWN;
            }
            // The save directory, which stays in this process: it is never logged, never put on
            // the wire, and never rendered, so the absolute form is used because it is the more
            // exact discriminator, not the prettier one.
            return root == null ? SaveIdentity.UNKNOWN : SaveIdentity.singleplayer(root.toString(), seed);
        }
        ServerData current = mc.getCurrentServer();
        return current == null ? SaveIdentity.UNKNOWN : SaveIdentity.multiplayer(current.ip);
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

    /**
     * {@inheritDoc}
     *
     * <p>Also records which save the returned frame describes. This method is called at exactly
     * the two moments a {@code world} frame goes out (the player confirming pairing, and a
     * resend after the loaded save changed), so it is the one honest place to stamp "this is
     * what the app was last told", which {@link #lastSentSaveIdentity()} then compares against.
     */
    @Override
    public WorldSnapshot worldSnapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            // confirm() only ever runs while a session is live in a loaded world, so this is
            // a defensive fallback rather than an expected path: report "no map" rather than
            // fail the confirm outright.
            lastSentSaveIdentity = SaveIdentity.UNKNOWN;
            return new WorldSnapshot(WorldSnapshot.EDITION_JAVA, "unknown", "unknown", null, 0, 0);
        }
        lastSentSaveIdentity = s.saveIdentity();
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

    /**
     * Section 4.2's two acceptance gates, and the ONLY place either is decided.
     *
     * <p>Every outcome is logged at debug, because both gates fail silently by design: a
     * route that is rejected here draws nothing, sends nothing back, and is
     * indistinguishable from a route that drew correctly and simply was not looked at.
     * Without these lines the only way to tell a dimension mismatch from a stale id from a
     * working renderer is a physical device and guesswork, which is exactly what the
     * 2026-08-27 device pass cost.
     *
     * <p>The route's own dimension string is untrusted wire text (bounded to
     * {@code NavProtocol.MAX_DIMENSION_CHARS}, but not otherwise inert), so it goes through
     * {@link SafeLabel} before it reaches the log: that is this repo's existing reducer for
     * exactly this, and it strips the control and format characters that would otherwise
     * let a peer forge log lines. The label itself is never logged at all.
     */
    @Override
    public void showRoute(RouteFrame route) {
        Snapshot s = snapshot;
        String currentDimension = s == null ? null : s.dimension();
        if (!route.matchesDimension(currentDimension)) {
            LOGGER.debug(
                    "Route {} rejected: dimension \"{}\" is not the player's \"{}\"",
                    route.id(),
                    SafeLabel.of(route.dimension()).literalText(),
                    currentDimension);
            return;
        }
        RouteFrame drawn = currentRoute;
        if (!route.supersedes(drawn)) {
            LOGGER.debug(
                    "Route {} rejected: superseded by the route already drawn (id {})",
                    route.id(),
                    drawn == null ? null : drawn.id());
            return;
        }
        currentRoute = route;
        LOGGER.debug("Route {} accepted and drawn", route.id());
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

    /**
     * The save described by the most recent {@link #worldSnapshot()}, or null if no
     * {@code world} frame has ever been built. Written on a link thread and read on the game
     * thread by {@link SeedscoutNavClientHooks}, hence volatile, the same publication the two
     * fields above use.
     */
    public SaveIdentity lastSentSaveIdentity() {
        return lastSentSaveIdentity;
    }
}
