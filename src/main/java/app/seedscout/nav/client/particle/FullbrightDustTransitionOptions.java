package app.seedscout.nav.client.particle;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ScalableParticleOptionsBase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;

/**
 * A {@link DustColorTransitionOptions}-shaped options object, but bound to
 * {@link SeedscoutNavParticles#FULLBRIGHT_TRAIL} instead of vanilla's own
 * {@code ParticleTypes.DUST_COLOR_TRANSITION}.
 *
 * <p><b>Why this class has to exist at all (confirmed by decompiling this project's own
 * Minecraft 26.2 jar with javap):</b> {@code DustColorTransitionOptions.getType()} is hardcoded
 * to {@code return ParticleTypes.DUST_COLOR_TRANSITION;} and {@code ParticleEngine.makeParticle}
 * looks up the spawning provider strictly by {@code options.getType()} (via
 * {@code BuiltInRegistries.PARTICLE_TYPE.getId(options.getType())}), never by whatever
 * {@link ParticleType} the caller thinks it is spawning. So constructing a plain
 * {@code DustColorTransitionOptions} and registering a fullbright provider under a NEW
 * {@link ParticleType} would silently do nothing: every such options object still routes to
 * vanilla's own (dim, real-world-lit) provider. The only way to make a dust-transition-shaped
 * particle route to a different provider is a subclass that overrides {@code getType()}.
 *
 * <p><b>Why this subclasses {@link DustColorTransitionOptions} rather than replacing it
 * entirely:</b> {@code DustColorTransitionParticle}'s constructor (and its
 * {@code Provider.createParticle}) is typed to take a {@code DustColorTransitionOptions}
 * specifically, not a generic {@code ScalableParticleOptionsBase}. Subclassing keeps this a
 * genuine {@code DustColorTransitionOptions} so {@link FullbrightDustColorTransitionParticle} can
 * still extend vanilla's {@code DustColorTransitionParticle} and inherit its colour-transition
 * behaviour unchanged, overriding only light coordinates.
 *
 * <p><b>Why {@code getType()} below returns {@code ParticleType<DustColorTransitionOptions>}
 * rather than {@code ParticleType<FullbrightDustTransitionOptions>}:</b> Java generics are
 * invariant, so a {@code ParticleType<FullbrightDustTransitionOptions>} return type would NOT be
 * override-compatible with the parent method's {@code ParticleType<DustColorTransitionOptions>}
 * signature (javac rejects it as an unrelated return type, not a covariant one). Keeping the
 * exact same generic signature and returning a different runtime instance is the only legal
 * override; it is also why {@link #CODEC} and {@link #STREAM_CODEC} below are typed as
 * {@code MapCodec<DustColorTransitionOptions>} / {@code StreamCodec<..., DustColorTransitionOptions>}
 * rather than parameterised on this subclass, even though their {@code apply}/factory reference
 * ({@link #FullbrightDustTransitionOptions(int, int, float)}) always constructs THIS subclass
 * specifically (a constructor reference to a subtype satisfies a supertype-returning functional
 * interface just fine; only method-override return types are invariant here, not lambda targets).
 *
 * <p>{@link #fromColor} and {@link #toColor} duplicate (rather than reuse) the private,
 * same-named fields already stored by the {@link DustColorTransitionOptions} superclass: those
 * are {@code private}, not {@code protected}, so a subclass cannot read them back for its own
 * codec's field getters. The superclass constructor is still given the same values via
 * {@code super(fromColor, toColor, scale)}, so the inherited {@code getFromColor()} /
 * {@code getToColor()} that {@code DustColorTransitionParticle}'s constructor actually calls stay
 * correct; the copies here exist solely so {@link #CODEC} and {@link #STREAM_CODEC} have
 * something to read.
 */
public final class FullbrightDustTransitionOptions extends DustColorTransitionOptions {

    /**
     * Field names ({@code from_color}, {@code to_color}, {@code scale}) and codec shape
     * (RGB24 via {@link ExtraCodecs#RGB_COLOR_CODEC}, scale via the inherited
     * {@link ScalableParticleOptionsBase#SCALE}) mirror {@code DustColorTransitionOptions.CODEC}
     * exactly; only the {@code apply} factory reference differs, constructing this subclass so a
     * data-driven decode (e.g. a future {@code /particle} command) also routes to the fullbright
     * provider instead of silently falling back to vanilla's dim one.
     */
    public static final MapCodec<DustColorTransitionOptions> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    ExtraCodecs.RGB_COLOR_CODEC.fieldOf("from_color")
                            .forGetter(options -> ((FullbrightDustTransitionOptions) options).fromColor),
                    ExtraCodecs.RGB_COLOR_CODEC.fieldOf("to_color")
                            .forGetter(options -> ((FullbrightDustTransitionOptions) options).toColor),
                    SCALE.fieldOf("scale").forGetter(ScalableParticleOptionsBase::getScale)
            ).apply(instance, FullbrightDustTransitionOptions::new));

    /** See {@link #CODEC}; the network counterpart, same field order. */
    public static final StreamCodec<RegistryFriendlyByteBuf, DustColorTransitionOptions> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, options -> ((FullbrightDustTransitionOptions) options).fromColor,
            ByteBufCodecs.INT, options -> ((FullbrightDustTransitionOptions) options).toColor,
            ByteBufCodecs.FLOAT, ScalableParticleOptionsBase::getScale,
            FullbrightDustTransitionOptions::new);

    private final int fromColor;
    private final int toColor;

    public FullbrightDustTransitionOptions(int fromColor, int toColor, float scale) {
        super(fromColor, toColor, scale);
        this.fromColor = fromColor;
        this.toColor = toColor;
    }

    @Override
    public ParticleType<DustColorTransitionOptions> getType() {
        return SeedscoutNavParticles.FULLBRIGHT_TRAIL;
    }
}
