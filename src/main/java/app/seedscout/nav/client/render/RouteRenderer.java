package app.seedscout.nav.client.render;

import app.seedscout.nav.client.ClientNavState;
import app.seedscout.nav.client.particle.FullbrightDustTransitionOptions;
import app.seedscout.nav.protocol.RouteFrame;
import app.seedscout.nav.protocol.RoutePoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;

/**
 * Deliverable 5: draws the currently accepted route as a particle trail, sampling each
 * segment every {@link #SAMPLE_SPACING} blocks and snapping each sample to the loaded-chunk
 * surface height, and tracks the distance-to-destination the HUD element reads.
 *
 * <p><b>Scope discipline (deliberate, matching the task brief's Phase 1 exclusions):</b> a
 * waypoint whose chunk is not loaded is simply skipped for that pass, not re-snapped once its
 * chunk loads later and not interpolated from its neighbours. There is no world-space ribbon,
 * no off-screen edge indicator, and no local pathfinding here; all four are explicitly
 * Phase 1b.
 *
 * <p>Runs entirely on the game thread, driven once per client tick by
 * {@link app.seedscout.nav.client.SeedscoutNavClientHooks}. Reads {@link ClientNavState} only
 * through its public read accessors, which are safe to call from the game thread (they read
 * the same volatile fields {@link ClientNavState} itself publishes from this thread).
 */
public final class RouteRenderer {

    public static final RouteRenderer INSTANCE = new RouteRenderer();

    /**
     * Blocks between samples along a segment. Started at 4.0 per the original task brief;
     * tightened to 2.0 on owner request for a denser-looking trail once the fullbright fix (see
     * {@link #spawnAt}) made the points readable in the dark. Halving this doubles the sample
     * (and therefore particle) count for a given visible segment length; see
     * {@link #MAX_SAMPLES_PER_SEGMENT} and {@link #MAX_SAMPLES_PER_PASS} for the ceilings that
     * keep that increase bounded rather than open-ended. Package-private (not {@code private})
     * so {@code RouteRendererTrailColorTest} can pin the exact value, the same way
     * {@link #MAX_SAMPLES_PER_SEGMENT} and {@link #MAX_SAMPLES_PER_PASS} are already exposed to
     * tests below.
     */
    static final double SAMPLE_SPACING = 2.0;

    /** Particles are spawned a few times a second, not every tick: a dense trail does not
     * need 20 Hz refresh, and this keeps the particle count sane on a long route. */
    private static final int PARTICLE_INTERVAL_TICKS = 8;

    /** Do not bother sampling or spawning particles for a segment far outside render range;
     * this is a performance bound, not the excluded "off-screen edge indicator" feature. */
    private static final double MAX_RENDER_DISTANCE = 256.0;

    /**
     * A segment can only be within {@link #MAX_RENDER_DISTANCE} of the player for at most a
     * chord of length {@code 2 * MAX_RENDER_DISTANCE} (the circle's diameter), no matter how
     * far apart its two endpoints actually are. This is the geometric bound behind FINDING
     * H2: {@link #clipToRenderDistance} throws away the out-of-range 99.999% of a segment
     * BEFORE any sampling happens, so the number of samples a segment can ever produce is
     * capped by geometry, not by attacker-supplied coordinates. The explicit constant here is
     * defense in depth on top of that, not the load-bearing bound.
     */
    static final int MAX_SAMPLES_PER_SEGMENT =
            (int) Math.ceil(2 * MAX_RENDER_DISTANCE / SAMPLE_SPACING) + 1;

    /**
     * A second, independent ceiling on top of {@link #MAX_SAMPLES_PER_SEGMENT}: total particle
     * work for one pass over the whole route can never exceed this, however many segments the
     * route has (up to the protocol's own 512 point cap) and however many of them pass near
     * the player. This is what keeps the per-frame cost a genuine constant rather than
     * "bounded but still O(pointCount)".
     */
    static final int MAX_SAMPLES_PER_PASS = 4096;

    /**
     * Trail colour pair, matched to the phone app's own map route (bright cyan-teal core over
     * a near-black casing) so the in-world trail and the map reading agree. FINDING (user
     * report): plain {@code ParticleTypes.END_ROD} is a small whitish spark, and a single pale
     * colour washes out against snow, ice and other bright terrain no matter how it is tuned.
     * {@link net.minecraft.core.particles.DustColorTransitionOptions} sidesteps this: each
     * particle animates from {@link #TRAIL_COLOR_DARK} to {@link #TRAIL_COLOR_BRIGHT} over its
     * lifetime, so the trail always carries both a dark tone (visible on snow/sand/ice) and a
     * saturated bright tone (visible on stone/dirt/foliage) rather than betting on one colour
     * reading against every terrain the route might cross. RGB24, no alpha channel: dust
     * colours are packed straight into {@code 0xRRGGBB} (see
     * {@code net.minecraft.util.ARGB.vector3fFromRGB24}), so the app's ARGB constants have
     * their {@code 0xFF} alpha byte dropped here rather than reused.
     *
     * <p>SECOND FINDING (owner report, after approving this colour pair): the owner tested this
     * in snow ("looked good") but flagged that it "may not look great or at night" in darker
     * settings. Confirmed by decompiling {@code DustColorTransitionParticle}: it inherits
     * {@code Particle.getLightCoords(float)} unchanged, which reads real WORLD light, so the
     * trail dims in caves and at night exactly as reported. The colour pair below is unchanged;
     * only the lighting is fixed, by {@link #spawnAt} constructing a
     * {@link app.seedscout.nav.client.particle.FullbrightDustTransitionOptions} instead of a
     * plain {@code DustColorTransitionOptions}. See that class and
     * {@link app.seedscout.nav.client.particle.FullbrightDustColorTransitionParticle} for why a
     * new registered particle type was required to do this at all.
     */
    static final int TRAIL_COLOR_DARK = 0x07100F;

    /** See {@link #TRAIL_COLOR_DARK}. */
    static final int TRAIL_COLOR_BRIGHT = 0x39E5D5;

    /**
     * Vanilla dust (e.g. redstone) spawns at scale 1.0
     * ({@link net.minecraft.core.particles.DustParticleOptions#REDSTONE}); the user asked for
     * the trail's points to be larger and pop out, so this trail spawns at roughly double that.
     * Comfortably inside the engine's {@code [MIN_SCALE, MAX_SCALE]} range of {@code [0.01,
     * 4.0]} without approaching either extreme, where a particle would either be invisible or
     * balloon into a blob that obscures the ground it is meant to mark.
     */
    static final float TRAIL_PARTICLE_SCALE = 2.0f;

    private int tickCounter;
    private volatile Double distanceToDestination;

    private RouteRenderer() {
    }

    /** Called once per client tick from the game thread. */
    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        RouteFrame route = ClientNavState.INSTANCE.currentRoute();
        ClientNavState.Snapshot snapshot = ClientNavState.INSTANCE.currentSnapshot();
        if (level == null || route == null || snapshot == null
                || !route.matchesDimension(snapshot.dimension())) {
            distanceToDestination = null;
            return;
        }

        List<RoutePoint> points = route.points();
        RoutePoint destination = points.get(points.size() - 1);
        double dx = destination.x() - snapshot.x();
        double dz = destination.z() - snapshot.z();
        distanceToDestination = Math.sqrt(dx * dx + dz * dz);

        tickCounter++;
        if (tickCounter % PARTICLE_INTERVAL_TICKS != 0) {
            return;
        }
        spawnParticles(level, points, snapshot);
    }

    private void spawnParticles(ClientLevel level, List<RoutePoint> points, ClientNavState.Snapshot player) {
        int samplesSpent = 0;
        for (int i = 0; i + 1 < points.size() && samplesSpent < MAX_SAMPLES_PER_PASS; i++) {
            RoutePoint from = points.get(i);
            RoutePoint to = points.get(i + 1);
            double[] range = clipToRenderDistance(from, to, player.x(), player.z());
            if (range == null) {
                // FINDING H2: this is the fix. A segment that never comes within render
                // distance of the player is rejected here, by a single O(1) closed-form
                // solve, WITHOUT ever computing a sample count from the segment's raw
                // length. Two in-bounds points at opposite world-border extremes used to
                // reach the sampling loop below with a length of ~8.5e7 blocks; now they
                // are rejected right here.
                continue;
            }
            double tMin = range[0];
            double tMax = range[1];
            double segmentLength = Math.hypot(to.x() - from.x(), to.z() - from.z());
            double visibleLength = (tMax - tMin) * segmentLength;
            int samples = Math.min(MAX_SAMPLES_PER_SEGMENT,
                    Math.max(1, (int) Math.ceil(visibleLength / SAMPLE_SPACING)));
            for (int s = 0; s <= samples && samplesSpent < MAX_SAMPLES_PER_PASS; s++, samplesSpent++) {
                double t = tMin + (tMax - tMin) * s / samples;
                double x = from.x() + (to.x() - from.x()) * t;
                double z = from.z() + (to.z() - from.z()) * t;
                spawnAt(level, x, z);
            }
        }
    }

    /**
     * Returns the {@code [tMin, tMax]} sub-range (in the segment's own 0..1 parameterisation)
     * that lies within {@link #MAX_RENDER_DISTANCE} of the player, or {@code null} if no part
     * of the segment does. Closed-form circle/segment intersection: solves for where
     * {@code |from + t*(to-from) - player| == MAX_RENDER_DISTANCE} and clips the resulting
     * interval to {@code [0, 1]}. Cost is O(1) regardless of how far apart {@code from} and
     * {@code to} are, which is exactly what keeps a route whose points sit at opposite corners
     * of the world border from ever reaching the sampling loop. Package-private so a test can
     * exercise the geometry directly without touching {@link ClientLevel}.
     */
    static double[] clipToRenderDistance(RoutePoint from, RoutePoint to, double playerX, double playerZ) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double fx = from.x() - playerX;
        double fz = from.z() - playerZ;
        double radius = MAX_RENDER_DISTANCE;

        double a = dx * dx + dz * dz;
        if (a == 0.0) {
            // Degenerate segment: both points identical. A single point, either in or out.
            return Math.hypot(fx, fz) <= radius ? new double[] {0.0, 0.0} : null;
        }
        double b = 2 * (fx * dx + fz * dz);
        double c = fx * fx + fz * fz - radius * radius;

        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0) {
            // The infinite line never enters the circle, so neither does the finite segment.
            return null;
        }
        double sqrtDiscriminant = Math.sqrt(discriminant);
        double t1 = (-b - sqrtDiscriminant) / (2 * a);
        double t2 = (-b + sqrtDiscriminant) / (2 * a);

        double tMin = Math.max(0.0, t1);
        double tMax = Math.min(1.0, t2);
        return tMin <= tMax ? new double[] {tMin, tMax} : null;
    }

    /**
     * The number of particle samples {@link #spawnParticles} would spend on one segment, with
     * no per-pass budget applied. Exists so a test can assert this stays bounded by geometry
     * alone (see {@link #MAX_SAMPLES_PER_SEGMENT}) for a hostile route, independent of the
     * {@link #MAX_SAMPLES_PER_PASS} safety net that would otherwise mask a regression here.
     */
    static int sampleCountForSegment(RoutePoint from, RoutePoint to, double playerX, double playerZ) {
        double[] range = clipToRenderDistance(from, to, playerX, playerZ);
        if (range == null) {
            return 0;
        }
        double segmentLength = Math.hypot(to.x() - from.x(), to.z() - from.z());
        double visibleLength = (range[1] - range[0]) * segmentLength;
        int samples = Math.min(MAX_SAMPLES_PER_SEGMENT, Math.max(1, (int) Math.ceil(visibleLength / SAMPLE_SPACING)));
        // spawnParticles' inner loop is "for (s = 0; s <= samples; s++)", so it makes
        // samples + 1 spawnAt calls; this mirrors that exactly rather than reporting the
        // "samples" variable itself, so a caller comparing against real work is comparing
        // like for like.
        return samples + 1;
    }

    private void spawnAt(ClientLevel level, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) {
            // Not loaded: skip rather than guess a height. See the class documentation.
            return;
        }
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        // Spawn at the CENTRE of the block whose height was just looked up, not the raw
        // sample coordinate. Block (blockX, blockZ) spans [blockX, blockX+1) x [blockZ,
        // blockZ+1); the raw x/z used here previously lands on the corner shared by four
        // blocks, so a player digging down at the drawn point risks opening a 2x2 column
        // instead of the single intended block.
        DustColorTransitionOptions trailDust =
                new FullbrightDustTransitionOptions(TRAIL_COLOR_DARK, TRAIL_COLOR_BRIGHT, TRAIL_PARTICLE_SCALE);
        level.addParticle(trailDust, blockCenter(blockX), surfaceY + 0.2, blockCenter(blockZ), 0.0, 0.02, 0.0);
    }

    /**
     * The centre coordinate of the block at {@code blockCoordinate} along one axis. Package
     * private and pure so a test can assert the block-centre snap directly, the same way
     * {@link #clipToRenderDistance} exposes geometry a test can drive without touching
     * {@link ClientLevel}.
     */
    static double blockCenter(int blockCoordinate) {
        return blockCoordinate + 0.5;
    }

    /** Distance in blocks to the current route's destination, or null when nothing is drawn. */
    public Double distanceToDestination() {
        return distanceToDestination;
    }
}
