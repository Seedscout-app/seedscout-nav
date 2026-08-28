package app.seedscout.nav.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ledger row nav-route-line-block-center-snap: {@code spawnAt} floored the sample x/z to a
 * block coordinate for the HEIGHT LOOKUP, but then spawned the particle at the raw,
 * unfloored x/z. A block at integer coordinate (bx, bz) spans the half-open interval
 * [bx, bx+1) on both axes, so its true centre is (bx+0.5, bz+0.5). Spawning at the raw
 * sample instead lands the particle on the corner shared by four blocks, so a player digging
 * straight down at the drawn point risks opening a 2x2 column rather than the single intended
 * block. The owner hit this on a real device.
 *
 * <p>This exercises {@link RouteRenderer#blockCenter}, the pure per-axis helper the fix
 * introduced, rather than driving {@code spawnAt} itself, since {@code spawnAt} needs a live
 * {@link net.minecraft.client.multiplayer.ClientLevel} the test harness does not have. This
 * mirrors the convention {@link RouteRendererDosTest} already uses for
 * {@code clipToRenderDistance}: the pure geometry is exactly what decides the spawned
 * coordinate, so bounding it here bounds the real path too.
 */
class RouteRendererBlockCenterTest {

    @Test
    @DisplayName("a positive block coordinate snaps to its centre, not its corner")
    void positiveCoordinateSnapsToCenter() {
        // A sample at x = 12.9 floors to block 12; the pre-fix code spawned at the raw 12.9
        // (a different corner of the same block), not the block's centre 12.5.
        int blockX = (int) Math.floor(12.9);
        assertEquals(12, blockX);
        assertEquals(12.5, RouteRenderer.blockCenter(blockX));
    }

    @Test
    @DisplayName("a coordinate that is already an exact integer still snaps to the block's centre")
    void exactIntegerCoordinateSnapsToCenter() {
        // x = 5.0 is the worst case for the pre-fix bug: floor(5.0) == 5, so the raw
        // coordinate IS the block corner exactly, with no fractional part to hide it.
        int blockX = (int) Math.floor(5.0);
        assertEquals(5, blockX);
        assertEquals(5.5, RouteRenderer.blockCenter(blockX));
    }

    @Test
    @DisplayName("a negative coordinate floors down, not truncates toward zero, before snapping to centre")
    void negativeCoordinateFloorsBeforeSnapping() {
        // x = -3.7: floor is -4 (floor rounds toward negative infinity), NOT -3 (truncation
        // toward zero). This is the case where floor and (int) cast truncation diverge, and
        // exactly the case Math.floor is used to get right in spawnAt.
        int blockX = (int) Math.floor(-3.7);
        assertEquals(-4, blockX);
        assertEquals(-3.5, RouteRenderer.blockCenter(blockX));
    }

    @Test
    @DisplayName("a negative exact-integer coordinate still snaps to its own block's centre")
    void negativeExactIntegerCoordinateSnapsToCenter() {
        int blockZ = (int) Math.floor(-10.0);
        assertEquals(-10, blockZ);
        assertEquals(-9.5, RouteRenderer.blockCenter(blockZ));
    }

    @Test
    @DisplayName("x and z axes snap independently to the same block's centre")
    void bothAxesSnapTogetherForOneSample() {
        // A single sample at (x=-3.7, z=8.2): both axes must independently floor-then-center,
        // landing on (-3.5, 8.5), the centre of block (-4, 8), not on any raw sample value.
        double x = -3.7;
        double z = 8.2;
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        assertEquals(-3.5, RouteRenderer.blockCenter(blockX));
        assertEquals(8.5, RouteRenderer.blockCenter(blockZ));
    }
}
