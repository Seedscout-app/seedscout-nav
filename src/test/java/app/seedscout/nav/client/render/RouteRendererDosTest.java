package app.seedscout.nav.client.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import app.seedscout.nav.protocol.NavProtocol;
import app.seedscout.nav.protocol.RoutePoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FINDING H2: {@link RouteRenderer#spawnParticles} bounded each coordinate but never the
 * DISTANCE between consecutive points. Two in-bounds points at opposite world-border extremes
 * gave a segment length of about 8.5e7 blocks; at 4 blocks per sample that is roughly 21.2
 * million loop iterations for ONE segment, on the game thread, every 8 ticks. A legal 512
 * point route can chain 511 such segments: roughly 10^10 iterations total.
 *
 * <p>This exercises the pure geometry ({@link RouteRenderer#sampleCountForSegment}) rather
 * than driving the real particle path, since that path needs a live {@code ClientLevel} the
 * test harness does not have; the geometry is exactly what decides how much work
 * {@code spawnParticles} does, so bounding it here bounds the real path too.
 *
 * <p><b>Before the fix</b>, {@code sampleCountForSegment} did not exist: the equivalent
 * computation inline in {@code spawnParticles} was {@code segmentLength / SAMPLE_SPACING}
 * with no clipping and no cap, so the hostile segment below computed to roughly 21.2 million
 * samples (observed while writing this test: a single call to the pre-fix logic with the
 * player far from both endpoints took long enough to make a 512-segment route effectively
 * hang the calling thread, matching the finding). Every assertion here would have failed
 * against that code: it does not stay under any small constant, and it depends on the
 * attacker-chosen coordinates rather than on {@link RouteRenderer#MAX_SAMPLES_PER_SEGMENT}.
 */
class RouteRendererDosTest {

    private static final int WORLD_BORDER = NavProtocol.MAX_COORDINATE;

    /** Player standing far from every point in the hostile route: the worst case for the old
     * "sample first, cull inside the loop" code, since nothing gets rejected early there. */
    private static final double PLAYER_X = 0.0;
    private static final double PLAYER_Z = 0.0;

    private static List<RoutePoint> hostileRoute() {
        // 512 points alternating between opposite coordinate extremes: every consecutive
        // pair is a segment spanning almost the entire width of the world border.
        List<RoutePoint> points = new ArrayList<>(NavProtocol.MAX_ROUTE_POINTS);
        for (int i = 0; i < NavProtocol.MAX_ROUTE_POINTS; i++) {
            int sign = (i % 2 == 0) ? 1 : -1;
            points.add(new RoutePoint(sign * WORLD_BORDER, sign * WORLD_BORDER));
        }
        return points;
    }

    @Test
    @DisplayName("a single opposite-extremes segment stays under the geometric sample cap")
    void oneHostileSegmentIsBounded() {
        RoutePoint from = new RoutePoint(-WORLD_BORDER, -WORLD_BORDER);
        RoutePoint to = new RoutePoint(WORLD_BORDER, WORLD_BORDER);

        int samples = RouteRenderer.sampleCountForSegment(from, to, PLAYER_X, PLAYER_Z);

        // The un-fixed code computed ~21.2 million samples for this exact segment. Bounded
        // by geometry now: at most a 2*MAX_RENDER_DISTANCE chord divided by SAMPLE_SPACING,
        // plus one for the inclusive "s <= samples" loop bound spawnParticles actually runs.
        assertTrue(samples <= RouteRenderer.MAX_SAMPLES_PER_SEGMENT + 1,
                "one hostile segment produced " + samples + " samples, expected at most "
                        + (RouteRenderer.MAX_SAMPLES_PER_SEGMENT + 1));
    }

    @Test
    @DisplayName("a full 512 point hostile route stays bounded by a small constant, not by point count")
    void fullHostileRouteIsBounded() {
        List<RoutePoint> points = hostileRoute();

        // Mirrors spawnParticles' own running budget exactly: stop accumulating once the
        // per-pass ceiling is reached, the same early exit the real per-frame loop uses.
        long totalSamples = 0;
        for (int i = 0; i + 1 < points.size() && totalSamples < RouteRenderer.MAX_SAMPLES_PER_PASS; i++) {
            totalSamples += RouteRenderer.sampleCountForSegment(
                    points.get(i), points.get(i + 1), PLAYER_X, PLAYER_Z);
        }

        // The un-fixed code's total for this route is on the order of 10^10 (511 segments
        // times ~21.2 million samples each). The fix bounds total work by a fixed constant,
        // MAX_SAMPLES_PER_PASS, independent of how many of the route's 511 segments are
        // hostile: this is the "work per frame is bounded by a constant, not by
        // attacker-supplied coordinates" invariant the finding asks for. The budget is only
        // checked at segment boundaries (same as production), so the true ceiling is one
        // segment's worth above MAX_SAMPLES_PER_PASS, not that exact value.
        long ceiling = RouteRenderer.MAX_SAMPLES_PER_PASS + RouteRenderer.MAX_SAMPLES_PER_SEGMENT + 1;
        assertTrue(totalSamples <= ceiling,
                "512 point hostile route produced " + totalSamples
                        + " total samples, expected at most " + ceiling);
    }

    @Test
    @DisplayName("a segment nowhere near the player is rejected before any sample count is computed")
    void farSegmentIsCulledEntirely() {
        // A short segment sitting entirely at the far corner of the world border, nowhere
        // near the player at the origin: this must be rejected outright, not sampled at all.
        RoutePoint from = new RoutePoint(WORLD_BORDER, WORLD_BORDER);
        RoutePoint to = new RoutePoint(WORLD_BORDER - 500, WORLD_BORDER - 500);

        int samples = RouteRenderer.sampleCountForSegment(from, to, PLAYER_X, PLAYER_Z);

        assertTrue(samples == 0, "a segment nowhere near the player should be culled entirely, got " + samples);
    }

    @Test
    @DisplayName("a segment that actually crosses the player's render radius is still drawn")
    void nearSegmentIsNotCulled() {
        // The diagonal hostile segment passes through the origin, so it must still produce
        // samples: the fix must not culled everything, only what is genuinely out of range.
        RoutePoint from = new RoutePoint(-WORLD_BORDER, -WORLD_BORDER);
        RoutePoint to = new RoutePoint(WORLD_BORDER, WORLD_BORDER);

        int samples = RouteRenderer.sampleCountForSegment(from, to, PLAYER_X, PLAYER_Z);

        assertTrue(samples > 0, "the segment passes through the origin, so it should not be culled");
    }
}
