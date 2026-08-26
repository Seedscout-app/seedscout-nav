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
 */
class ClientEntrypointWiringTest {

    private static final String ENTRYPOINT_CLASS_RESOURCE =
            "app/seedscout/nav/SeedscoutNavClient.class";
    private static final String HOOKS_INTERNAL_NAME =
            "app/seedscout/nav/client/SeedscoutNavClientHooks";
    private static final String HOOKS_INIT_METHOD = "init";

    @Test
    void onInitializeClientEntrypointReferencesHooksInit() throws IOException {
        byte[] classBytes = readEntrypointClassBytes();
        List<MethodRef> methodRefs = parseMethodRefs(classBytes);

        boolean callsHooksInit = methodRefs.stream()
                .anyMatch(ref -> ref.ownerInternalName.equals(HOOKS_INTERNAL_NAME)
                        && ref.methodName.equals(HOOKS_INIT_METHOD));

        assertTrue(callsHooksInit,
                "SeedscoutNavClient.class must contain a compiled call to "
                        + HOOKS_INTERNAL_NAME + "." + HOOKS_INIT_METHOD
                        + "() -- found method refs: " + methodRefs
                        + ". Without this call the mod's fabric.mod.json client entrypoint "
                        + "never wires the keybind/HUD/teardown hooks and the shipped jar "
                        + "does nothing at runtime, even though it compiles and other tests "
                        + "pass.");
    }

    private static byte[] readEntrypointClassBytes() throws IOException {
        try (InputStream in = ClientEntrypointWiringTest.class.getClassLoader()
                .getResourceAsStream(ENTRYPOINT_CLASS_RESOURCE)) {
            if (in == null) {
                fail("Could not find " + ENTRYPOINT_CLASS_RESOURCE + " on the test classpath; "
                        + "expected compileJava (a dependency of the test task) to have "
                        + "produced it.");
            }
            return in.readAllBytes();
        }
    }

    /** One JVM constant-pool Methodref/InterfaceMethodref entry, resolved to plain names. */
    private record MethodRef(String ownerInternalName, String methodName) {
    }

    /**
     * Minimal JVM class-file constant-pool parser (JVMS section 4.4), just enough to resolve
     * every Methodref/InterfaceMethodref entry to an (owner internal name, method name) pair.
     * Deliberately does not parse method bodies/bytecode instructions -- see the class doc for
     * exactly what that limits this test to proving.
     */
    private static List<MethodRef> parseMethodRefs(byte[] classBytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(classBytes))) {
            int magic = in.readInt();
            if (magic != 0xCAFEBABE) {
                fail("Not a valid .class file (bad magic): " + ENTRYPOINT_CLASS_RESOURCE);
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
                                + ENTRYPOINT_CLASS_RESOURCE + " at index " + i);
                }
                // remember the tag alongside the raw data for the resolve pass below
                pool[i] = new Object[] {tag, pool[i]};
            }

            List<MethodRef> refs = new ArrayList<>();
            for (int i = 1; i < constantPoolCount; i++) {
                Object entry = pool[i];
                if (entry == null) {
                    continue;
                }
                Object[] tagged = (Object[]) entry;
                int tag = (int) tagged[0];
                if (tag != 10 && tag != 11) { // Methodref / InterfaceMethodref only
                    continue;
                }
                int[] classAndNameType = (int[]) tagged[1];
                int classIndex = classAndNameType[0];
                int nameAndTypeIndex = classAndNameType[1];

                int classNameUtf8Index = ((int[]) ((Object[]) pool[classIndex])[1])[0];
                String ownerInternalName = (String) ((Object[]) pool[classNameUtf8Index])[1];

                int[] nameAndType = (int[]) ((Object[]) pool[nameAndTypeIndex])[1];
                int nameUtf8Index = nameAndType[0];
                String methodName = (String) ((Object[]) pool[nameUtf8Index])[1];

                refs.add(new MethodRef(ownerInternalName, methodName));
            }
            return refs;
        }
    }
}
