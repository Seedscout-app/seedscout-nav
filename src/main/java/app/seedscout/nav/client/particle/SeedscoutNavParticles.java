package app.seedscout.nav.client.particle;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * This mod's only registered content, behind a single {@link #init()} entry point, matching the
 * pattern {@code SeedscoutNavClientHooks#init()} already uses for the rest of this package's
 * Fabric lifecycle wiring.
 *
 * <p>Confirmed by inspecting the actual Fabric API 0.158.0+26.2 jar with javap (the registration
 * shape is NOT assumed): the vanilla registry field is
 * {@code BuiltInRegistries.PARTICLE_TYPE: Registry<ParticleType<?>>}, populated with
 * {@code Registry.register(Registry<V>, Identifier, T)}; the ParticleType VALUE itself (its
 * codec/stream-codec pair, with no id baked in) comes from
 * {@code FabricParticleTypes.complex(MapCodec<T>, StreamCodec<? super RegistryFriendlyByteBuf, T>)}
 * in {@code fabric-particles-v1}; and the client-side rendering provider is wired separately via
 * {@code net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry}, whose
 * {@code PendingParticleProvider<T>} overload (used below, not the plain-{@code ParticleProvider}
 * one) is what supplies the {@code SpriteSet} loaded from this mod's own
 * {@code assets/seedscout-nav/particles/fullbright_trail.json} rather than requiring one to be
 * built by hand.
 */
public final class SeedscoutNavParticles {

    /**
     * The route trail's fullbright dust particle type (see
     * {@link FullbrightDustColorTransitionParticle} for why it needs to exist and
     * {@link FullbrightDustTransitionOptions} for why its options object cannot just be a plain
     * {@code DustColorTransitionOptions}). {@code overrideLimiter} is {@code false}, matching
     * vanilla's own {@code ParticleTypes.DUST_COLOR_TRANSITION} registration (confirmed via
     * javap): this trail should still respect the player's Minecraft particle-count setting like
     * any other dust particle, not bypass it.
     */
    public static final ParticleType<DustColorTransitionOptions> FULLBRIGHT_TRAIL = Registry.register(
            BuiltInRegistries.PARTICLE_TYPE,
            Identifier.fromNamespaceAndPath("seedscout-nav", "fullbright_trail"),
            FabricParticleTypes.complex(FullbrightDustTransitionOptions.CODEC, FullbrightDustTransitionOptions.STREAM_CODEC));

    private SeedscoutNavParticles() {
    }

    /**
     * Registers the client-side rendering provider for {@link #FULLBRIGHT_TRAIL}. Must run
     * during client mod init (before any world loads and starts spawning particles), same
     * lifecycle stage as the rest of {@code SeedscoutNavClientHooks#init()}. The {@code FULLBRIGHT_TRAIL}
     * field above is registered into {@code BuiltInRegistries.PARTICLE_TYPE} as a side effect of
     * this class's static initialiser, which runs the first time anything references this class
     * (i.e. the moment {@code SeedscoutNavClientHooks#init()} calls this method), so a single
     * call here is enough to complete both registrations.
     */
    public static void init() {
        ParticleProviderRegistry.getInstance().register(
                FULLBRIGHT_TRAIL, sprites -> new FullbrightDustColorTransitionParticle.Provider(sprites));
    }
}
