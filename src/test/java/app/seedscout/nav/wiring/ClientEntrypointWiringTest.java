package app.seedscout.nav.wiring;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for the "shipped jar does nothing" defect: {@code SeedscoutNavClient}
 * (the {@code fabric.mod.json} client entrypoint) was compiling and passing all 165 other
 * tests while its {@code onInitializeClient()} body only logged a line and never called
 * {@code SeedscoutNavClientHooks.init()} -- the method that actually registers the keybind,
 * HUD, and teardown hooks. No other test caught this because none of them touch the
 * entrypoint class at all.
 *
 * <p><b>What this test proves:</b> it loads the compiled {@code SeedscoutNavClient.class}
 * bytes (produced by {@code compileJava}, which the {@code test} task depends on) straight
 * from the test classpath and parses the raw JVM constant pool (no ASM/bytecode-analysis
 * library needed -- this is a hand-rolled reader of the format in JVMS 4.4). It asserts the
 * class's constant pool contains a {@code Methodref} entry naming
 * {@code app/seedscout/nav/client/SeedscoutNavClientHooks.init}. A javac-compiled invocation
 * of {@code Hooks.init()} from anywhere in {@code SeedscoutNavClient} necessarily emits
 * exactly such a constant-pool entry, so this is a reliable "the call exists somewhere in
 * this class" signal.
 *
 * <p><b>What this test does NOT prove:</b>
 * <ul>
 *   <li>That the call is reachable from {@code onInitializeClient()} specifically, as
 *       opposed to some other method or a comment-adjacent dead branch. (In practice this
 *       class has only a constructor and {@code onInitializeClient()}, so in this codebase
 *       that distinction collapses -- but the check itself does not verify control flow.)</li>
 *   <li>That Fabric actually calls {@code onInitializeClient()} at runtime -- that depends on
 *       the {@code fabric.mod.json} {@code client} entrypoint declaration and Fabric Loader's
 *       own entrypoint dispatch, neither of which this headless JUnit run exercises.</li>
 *   <li>That {@code SeedscoutNavClientHooks.init()} itself registers correctly against a live
 *       Fabric/Minecraft client (keybind category, HUD element, tick/level/lifecycle events).
 *       That would need a real or simulated client bootstrap, which this project's headless
 *       test task intentionally does not attempt (see build.gradle.kts comment on the
 *       protocol package having zero Minecraft imports by design).</li>
 * </ul>
 * In short: this is a static "the wiring line is present in the compiled class" check, not a
 * runtime "Fabric actually initialized the mod" check. It is intentionally cheap and honest
 * about that limit, per the task brief's guidance to prefer a real, admitted-weak check over
 * an overstated one.
 *
 * <p>A second test applies the same idea one level down, to
 * {@code SeedscoutNavClientHooks} itself, guarding the two Fabric events whose roles were
 * swapped by the nether-portal fix. The same limits apply to it.
 */
class ClientEntrypointWiringTest {

    private static final String ENTRYPOINT_CLASS_RESOURCE =
            "app/seedscout/nav/SeedscoutNavClient.class";
    private static final String HOOKS_CLASS_RESOURCE =
            "app/seedscout/nav/client/SeedscoutNavClientHooks.class";
    private static final String HOOKS_INTERNAL_NAME =
            "app/seedscout/nav/client/SeedscoutNavClientHooks";
    private static final String HOOKS_INIT_METHOD = "init";

    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;

    @Test
    void onInitializeClientEntrypointReferencesHooksInit() throws IOException {
        byte[] classBytes = readClassBytes(ENTRYPOINT_CLASS_RESOURCE);
        List<MemberRef> refs = parseMemberRefs(classBytes, ENTRYPOINT_CLASS_RESOURCE);

        boolean callsHooksInit = refs.stream()
                .anyMatch(ref -> ref.tag != TAG_FIELDREF
                        && ref.ownerInternalName.equals(HOOKS_INTERNAL_NAME)
                        && ref.memberName.equals(HOOKS_INIT_METHOD));

        assertTrue(callsHooksInit,
                "SeedscoutNavClient.class must contain a compiled call to "
                        + HOOKS_INTERNAL_NAME + "." + HOOKS_INIT_METHOD
                        + "() -- found member refs: " + refs
                        + ". Without this call the mod's fabric.mod.json client entrypoint "
                        + "never wires the keybind/HUD/teardown hooks and the shipped jar "
                        + "does nothing at runtime, even though it compiles and other tests "
                        + "pass.");
    }

    /**
     * The same "is the wiring line actually there" check, one level down, for the two events
     * whose roles were swapped when the portal bug was fixed. A nether portal used to end the
     * nav session because {@code AFTER_CLIENT_LEVEL_CHANGE} was the teardown trigger; the
     * teardown trigger is now {@code ClientPlayConnectionEvents.DISCONNECT}. Both fields must be
     * referenced by the hooks class, because dropping either one is a silent regression: drop
     * DISCONNECT and the link outlives the world it belongs to, drop AFTER_CLIENT_LEVEL_CHANGE
     * and a real save swap never resends the world frame.
     *
     * <p>Same limits as the test above: this proves the constant-pool reference exists in this
     * class, not that Fabric dispatches the event or that the registered lambda does the right
     * thing at runtime.
     */
    @Test
    void hooksReferenceBothTheDisconnectAndLevelChangeEvents() throws IOException {
        byte[] classBytes = readClassBytes(HOOKS_CLASS_RESOURCE);
        List<MemberRef> refs = parseMemberRefs(classBytes, HOOKS_CLASS_RESOURCE);

        assertTrue(referencesField(refs,
                        "net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents",
                        "DISCONNECT"),
                "SeedscoutNavClientHooks must register ClientPlayConnectionEvents.DISCONNECT: it "
                        + "is the event that actually means the player left the world, and it is "
                        + "what ends the nav session now that a level change no longer does. "
                        + "Found member refs: " + refs);

        assertTrue(referencesField(refs,
                        "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLevelEvents",
                        "AFTER_CLIENT_LEVEL_CHANGE"),
                "SeedscoutNavClientHooks must still register "
                        + "ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE, now as the trigger that "
                        + "resends the world frame when the loaded save changed. Found member "
                        + "refs: " + refs);
    }

    /**
     * The three references the whole in-game route render path hangs off, guarded the same
     * way the two above are.
     *
     * <p>HONESTLY LABELLED: this is regression coverage, not evidence of a defect. It passes
     * against the source as it stands, and it was added because the 2026-08-27 device pass
     * reported no route drawing anywhere and nothing in the test suite could rule the in-game
     * half in or out. Losing any one of these three would take the whole render path out
     * silently, exactly the way the entrypoint defect this class was born for did: drop
     * {@code END_CLIENT_TICK} and the renderer is never ticked, drop
     * {@code RouteRenderer.onClientTick} and the tick reaches nothing, drop
     * {@code HudElementRegistry.addLast} and the distance readout never exists.
     *
     * <p>Same limits as every check here: a constant-pool reference proves the call site was
     * compiled into this class, not that Fabric dispatches it or that what it registers draws
     * anything. Only a device can decide that.
     */
    @Test
    void hooksReferenceTheWholeRouteRenderPath() throws IOException {
        byte[] classBytes = readClassBytes(HOOKS_CLASS_RESOURCE);
        List<MemberRef> refs = parseMemberRefs(classBytes, HOOKS_CLASS_RESOURCE);

        assertTrue(referencesField(refs,
                        "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents",
                        "END_CLIENT_TICK"),
                "SeedscoutNavClientHooks must register ClientTickEvents.END_CLIENT_TICK: it is "
                        + "the only thing that drives ClientNavState's snapshot and the route "
                        + "renderer. Found member refs: " + refs);

        assertTrue(referencesMethod(refs,
                        "app/seedscout/nav/client/render/RouteRenderer",
                        "onClientTick"),
                "SeedscoutNavClientHooks must call RouteRenderer.onClientTick() from its tick "
                        + "handler: without it the client ticks and the route is never drawn. "
                        + "Found member refs: " + refs);

        assertTrue(referencesMethod(refs,
                        "net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry",
                        "addLast"),
                "SeedscoutNavClientHooks must register the route HUD element with "
                        + "HudElementRegistry.addLast: without it there is no distance readout "
                        + "even when a route is accepted. Found member refs: " + refs);
    }

    private static boolean referencesMethod(
            List<MemberRef> refs, String ownerInternalName, String methodName) {
        return refs.stream().anyMatch(ref -> ref.tag != TAG_FIELDREF
                && ref.ownerInternalName.equals(ownerInternalName)
                && ref.memberName.equals(methodName));
    }

    private static boolean referencesField(
            List<MemberRef> refs, String ownerInternalName, String fieldName) {
        return refs.stream().anyMatch(ref -> ref.tag == TAG_FIELDREF
                && ref.ownerInternalName.equals(ownerInternalName)
                && ref.memberName.equals(fieldName));
    }

    private static byte[] readClassBytes(String resource) throws IOException {
        try (InputStream in = ClientEntrypointWiringTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                fail("Could not find " + resource + " on the test classpath; "
                        + "expected compileJava (a dependency of the test task) to have "
                        + "produced it.");
            }
            return in.readAllBytes();
        }
    }

    /**
     * One JVM constant-pool Fieldref/Methodref/InterfaceMethodref entry, resolved to plain
     * names. {@code tag} is kept so a "calls this method" claim is never satisfied by a field
     * reference that happens to share a name.
     */
    private record MemberRef(int tag, String ownerInternalName, String memberName) {
    }

    /**
     * Minimal JVM class-file constant-pool parser (JVMS section 4.4), just enough to resolve
     * every Fieldref/Methodref/InterfaceMethodref entry to an (owner internal name, member name)
     * pair. Deliberately does not parse method bodies/bytecode instructions -- see the class doc
     * for exactly what that limits this test to proving.
     */
    private static List<MemberRef> parseMemberRefs(byte[] classBytes, String resource)
            throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                fail("Not a valid .class file (bad magic): " + resource);
            }
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version

            int constantPoolCount = in.readUnsignedShort();
            // Index 0 is unused; entries are 1..constantPoolCount-1.
            Object[] pool = new Object[constantPoolCount];

            for (int i = 1; i < constantPoolCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: { // Utf8
                        pool[i] = in.readUTF();
                        break;
                    }
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        pool[i] = new int[] {in.readUnsignedShort()};
                        break;
                    case 15: { // MethodHandle
                        in.readUnsignedByte(); // reference_kind
                        pool[i] = new int[] {in.readUnsignedShort()};
                        break;
                    }
                    case 9:  // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                        break;
                    case 3: // Integer
                    case 4: // Float
                        in.readInt();
                        break;
                    case 5: // Long
                    case 6: // Double
                        in.readLong();
                        i++; // longs/doubles take two constant-pool slots
                        break;
                    default:
                        fail("Unrecognized constant pool tag " + tag + " while parsing "
                                + resource + " at index " + i);
                }
                // remember the tag alongside the raw data for the resolve pass below
                pool[i] = new Object[] {tag, pool[i]};
            }

            List<MemberRef> refs = new ArrayList<>();
            for (int i = 1; i < constantPoolCount; i++) {
                Object entry = pool[i];
                if (entry == null) {
                    continue;
                }
                Object[] tagged = (Object[]) entry;
                int tag = (int) tagged[0];
                if (tag != TAG_FIELDREF && tag != TAG_METHODREF && tag != TAG_INTERFACE_METHODREF) {
                    continue;
                }
                int[] classAndNameType = (int[]) tagged[1];
                int classIndex = classAndNameType[0];
                int nameAndTypeIndex = classAndNameType[1];

                int classNameUtf8Index = ((int[]) ((Object[]) pool[classIndex])[1])[0];
                String ownerInternalName = (String) ((Object[]) pool[classNameUtf8Index])[1];

                int[] nameAndType = (int[]) ((Object[]) pool[nameAndTypeIndex])[1];
                int nameUtf8Index = nameAndType[0];
                String memberName = (String) ((Object[]) pool[nameUtf8Index])[1];

                refs.add(new MemberRef(tag, ownerInternalName, memberName));
            }
            return refs;
        }
    }
}
