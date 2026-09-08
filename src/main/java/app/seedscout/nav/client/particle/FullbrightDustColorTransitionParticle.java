package app.seedscout.nav.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.DustColorTransitionParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;

/**
 * The route trail's fullbright dust particle: identical to vanilla's
 * {@link DustColorTransitionParticle} (same dark-to-bright colour transition, same scale) except
 * for how it is lit.
 *
 * <p><b>The problem this fixes (confirmed by decompiling this project's own Minecraft 26.2 jar
 * with javap):</b> {@code Particle.getLightCoords(float)} reads REAL WORLD LIGHT by default: it
 * resolves the particle's block position and, if the chunk is loaded, defers to
 * {@code LightCoordsUtil.getLightCoords(level, pos)}. Neither {@code DustParticleBase} nor
 * {@code DustColorTransitionParticle} overrides it, so the plain dust trail dims in caves and at
 * night, exactly as the owner reported after approving the colour in daylight/snow ("looked good
 * in snow. in a darker setting it may not look great or at night"). This is the same reason the
 * OLD {@code END_ROD}-based trail glowed everywhere: {@code SimpleAnimatedParticle} (its
 * superclass) hardcodes {@code getLightCoords} to {@code LightCoordsUtil.FULL_BRIGHT}. This class
 * does the same override, scoped to the dust trail specifically, so the owner keeps the two-tone
 * colour just approved instead of reverting to END_ROD's washed-out white spark.
 *
 * <p>Package-private constructor: the only intended entry point is {@link Provider}, which
 * {@link SeedscoutNavParticles} registers for {@link SeedscoutNavParticles#FULLBRIGHT_TRAIL}.
 */
public final class FullbrightDustColorTransitionParticle extends DustColorTransitionParticle {

    /**
     * The exact value {@link #getLightCoords} returns, pulled out as its own constant so
     * {@code RouteRendererTrailColorTest} can pin it directly: constructing a real instance of
     * this class needs a live {@link ClientLevel} the test harness does not have (same
     * constraint {@code RouteRendererDosTest} and {@code RouteRendererBlockCenterTest} already
     * document for {@code RouteRenderer} itself), but the constant this override unconditionally
     * returns is a pure value a test can check with no game running. {@code public} (rather than
     * package-private, unlike {@code RouteRenderer}'s own test-only constants) because the test
     * that pins it, {@code RouteRendererTrailColorTest}, lives in the sibling {@code
     * app.seedscout.nav.client.render} package, not this one.
     */
    public static final int LIGHT_COORDS = LightCoordsUtil.FULL_BRIGHT;

    private FullbrightDustColorTransitionParticle(ClientLevel level, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed, DustColorTransitionOptions options, SpriteSet sprites) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed, options, sprites);
    }

    /**
     * {@code protected int getLightCoords(float)} on {@link Particle} widened to {@code public}
     * here, matching the exact widening {@code SimpleAnimatedParticle} itself uses for the same
     * override (confirmed via javap): unconditional full brightness, ignoring the world light
     * {@link DustColorTransitionParticle} would otherwise inherit.
     */
    @Override
    public int getLightCoords(float partialTick) {
        return LIGHT_COORDS;
    }

    /**
     * Fabric's {@code ParticleProviderRegistry.PendingParticleProvider} callback supplies the
     * {@code SpriteSet} once particle resources for
     * {@code assets/seedscout-nav/particles/fullbright_trail.json} are loaded; see
     * {@link SeedscoutNavParticles#init()}.
     */
    public static final class Provider implements ParticleProvider<DustColorTransitionOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(DustColorTransitionOptions options, ClientLevel level,
                double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, RandomSource random) {
            return new FullbrightDustColorTransitionParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, options, sprites);
        }
    }
}
