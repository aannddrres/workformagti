package ge.magti.portal.web;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what a controller method actually does, for the guards that cannot
 * answer their question from annotations alone.
 *
 * <p>Reflection stops at the method signature, and the two properties worth
 * defending live in the body: whether a handler calls anything that can
 * refuse the caller ({@link EndpointGuardCoverageTest}), and whether a
 * permission is ever consulted anywhere the SYSTEM_ADMIN bypass has not
 * already answered ({@code PermissionEnforcementCoverageTest}). Spring ships
 * a repackaged ASM that is already on the classpath, so reading the bytecode
 * costs no new dependency and no context boot.
 *
 * <p>Calls into other classes are recorded but never followed. A guard
 * reached three services deep is not something a reader of the handler can
 * see either, and following it would turn every failure here into a
 * whole-program puzzle. Calls within the same controller <i>are</i> followed:
 * every {@code require*} guard in this codebase is a private method, and
 * several handlers reach one through another.
 *
 * <p>Lambdas are a blind spot, deliberately accepted: their bodies compile to
 * synthetic methods invoked by {@code invokedynamic}, which is a different
 * instruction than the ones read here. It errs the safe way for both callers
 * -- a guard or a permission check hidden in a lambda reads as absent, so the
 * test fails rather than passes.
 */
public final class ControllerBytecode {

    private ControllerBytecode() {
    }

    /** One call site: who is invoked, by what name, with what descriptor. */
    public record Invocation(String owner, String name, String descriptor) {
    }

    /** Everything one method body does that this analysis can see. */
    public record Body(Set<Invocation> invocations, Set<String> staticFieldsRead) {
    }

    /** name+descriptor of a method -> what its body does. */
    public static Map<String, Body> read(Class<?> type) {
        Map<String, Body> byMethod = new HashMap<>();
        String resource = Type.getInternalName(type) + ".class";
        try (InputStream bytecode = type.getClassLoader().getResourceAsStream(resource)) {
            if (bytecode == null) {
                throw new IllegalStateException("no bytecode on the classpath for " + type.getName()
                        + " -- this guard reads .class files and cannot work without them");
            }
            new ClassReader(bytecode).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Body body = byMethod.computeIfAbsent(name + descriptor,
                            k -> new Body(new LinkedHashSet<>(), new LinkedHashSet<>()));
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String calledDescriptor, boolean isInterface) {
                            body.invocations().add(new Invocation(owner, calledName, calledDescriptor));
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                            // Enum constants are static fields, so this is how
                            // "which Permission does this method name" is read.
                            body.staticFieldsRead().add(owner + "." + fieldName);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (IOException e) {
            throw new IllegalStateException("could not read bytecode for " + type.getName(), e);
        }
        return byMethod;
    }

    /**
     * Everything {@code entryPoint} does, plus everything the methods it
     * calls on {@code owner} itself do, transitively.
     */
    public static Body closureOf(Class<?> owner, Method entryPoint, Map<String, Body> bodies) {
        String internalName = Type.getInternalName(owner);
        Set<Invocation> invocations = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();

        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(entryPoint.getName() + Type.getMethodDescriptor(entryPoint));

        while (!queue.isEmpty()) {
            String key = queue.remove(queue.size() - 1);
            if (!visited.add(key)) {
                continue;
            }
            Body body = bodies.get(key);
            if (body == null) {
                continue;
            }
            fields.addAll(body.staticFieldsRead());
            for (Invocation invocation : body.invocations()) {
                invocations.add(invocation);
                if (internalName.equals(invocation.owner())) {
                    queue.add(invocation.name() + invocation.descriptor());
                }
            }
        }
        return new Body(invocations, fields);
    }
}
