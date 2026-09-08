package app.seedscout.nav.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.seedscout.nav.client.particle.FullbrightDustColorTransitionParticle;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ScalableParticleOptionsBase;
import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User report: the route trail used {@code ParticleTypes.END_ROD}, a small whitish spark that
 * disappears against snow and ice. The fix swaps it for a {@link DustColorTransitionOptions}
 * pair (dark casing to bright core, matching the phone app's own map route colours) at a larger
 * scale. This can't exercise real particle spawning ({@code spawnAt} needs a live
 * {@code ClientLevel} the test harness does not have, matching the convention
 * {@link RouteRendererBlockCenterTest} and {@link RouteRendererDosTest} already use) but the
 * colour and scale choice IS a pure value the fix could get wrong in several ways this pins
 * down: the two tones being distinct, both being derived from the app's actual route colours
 * (not just any two colours), and the scale sitting inside the engine's legal range and above
 * vanilla's own default rather than being a no-op.
 *
 * <p>SECOND FINDING (owner report, after approving this colour pair): the trail read well in
 * snow but the owner flagged it would likely be unreadable in caves/at night, and separately
 * asked for the sample points to sit closer together. {@link FullbrightDustColorTransitionParticle}
 * and the {@code SAMPLE_SPACING} tightening below cover those two follow-ups; the same
 * no-live-{@code ClientLevel} constraint applies, so these pin the pure constants the fix could
 * get wrong rather than driving a real particle spawn.
 */
class RouteRendererTrailColorTest {

    @Test
    @DisplayName("the dark and bright trail tones are distinct, so the transition can't collapse to a flat colour")
    void trailTonesAreDistinct() {
        assertNotEquals(RouteRenderer.TRAIL_COLOR_DARK, RouteRenderer.TRAIL_COLOR_BRIGHT);
    }

    @Test
    @DisplayName("the trail tones are the app's map route colours (0xFF39E5D5 over 0xFF07100F) with the alpha byte dropped")
    void trailTonesMatchAppRouteColors() {
        int appRouteCore = 0xFF39E5D5;
        int appRouteCasing = 0xFF07100F;

        assertEquals(appRouteCore & 0xFFFFFF, RouteRenderer.TRAIL_COLOR_BRIGHT);
        assertEquals(appRouteCasing & 0xFFFFFF, RouteRenderer.TRAIL_COLOR_DARK);
    }

    @Test
    @DisplayName("the trail scale is larger than vanilla dust's default (1.0) but within the engine's legal [MIN_SCALE, MAX_SCALE] range")
    void trailScaleIsLargerButLegal() {
        assertTrue(RouteRenderer.TRAIL_PARTICLE_SCALE > 1.0f,
                "trail scale should read as larger than vanilla dust, per the user's request to make points pop out");
        assertTrue(RouteRenderer.TRAIL_PARTICLE_SCALE >= ScalableParticleOptionsBase.MIN_SCALE
                        && RouteRenderer.TRAIL_PARTICLE_SCALE <= ScalableParticleOptionsBase.MAX_SCALE,
                "trail scale must stay inside the engine's legal scale range");
    }

    @Test
    @DisplayName("the chosen colours and scale actually construct a valid DustColorTransitionOptions")
    void trailColorsConstructValidParticleOptions() {
        DustColorTransitionOptions trailDust = new DustColorTransitionOptions(
                RouteRenderer.TRAIL_COLOR_DARK, RouteRenderer.TRAIL_COLOR_BRIGHT, RouteRenderer.TRAIL_PARTICLE_SCALE);

        assertEquals(RouteRenderer.TRAIL_PARTICLE_SCALE, trailDust.getScale());
    }

    @Test
    @DisplayName("the fullbright trail particle reports LightCoordsUtil.FULL_BRIGHT, not real world light")
    void trailParticleReportsFullBrightLightCoords() {
        assertEquals(LightCoordsUtil.FULL_BRIGHT, FullbrightDustColorTransitionParticle.LIGHT_COORDS,
                "the whole point of FullbrightDustColorTransitionParticle is to stop the trail "
                        + "dimming in caves and at night; it must report full brightness unconditionally");
    }

    @Test
    @DisplayName("sample spacing was tightened to 2.0 blocks, per the owner's request for a denser trail")
    void sampleSpacingIsTwoBlocks() {
        assertEquals(2.0, RouteRenderer.SAMPLE_SPACING);
    }
}
