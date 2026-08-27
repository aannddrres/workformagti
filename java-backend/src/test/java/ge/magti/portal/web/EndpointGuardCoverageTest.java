package ge.magti.portal.web;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.web.bind.annotation.RequestMapping;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Asserts that every endpoint actually calls something that can refuse the
 * caller -- the half {@link EndpointPrincipalCoverageTest} explicitly cannot
 * reach.
 *
 * <h2>Why this exists on top of the principal check</h2>
 *
 * That test asserts the handler is <i>handed</i> the caller, which is the
 * floor: without the parameter, no authorization is possible at all. It says
 * so in its own javadoc and names the gap it leaves -- a handler that takes
 * {@code @AuthenticationPrincipal User} and then never looks at it. Under
 * {@code SecurityConfig:45}'s {@code anyRequest().permitAll()} that handler
 * is reachable by anyone, and the parameter makes it look guarded.
 *
 * <p>Method bodies are invisible to reflection but not to bytecode, and
 * Spring ships a repackaged ASM ({@code org.springframework.asm}) that is
 * already on the classpath, so no new dependency is needed. This reads each
 * controller's own {@code .class} file, collects what each handler invokes,
 * follows calls into private helpers in the same class -- the repo's
 * {@code require*} guards are all private, and several handlers reach them
 * through one -- and requires that the resulting call closure contains at
 * least one recognised guard.
 *
 * <h2>What this can and cannot prove</h2>
 *
 * It proves a guard is <b>called</b>. It cannot prove the result is
 * <b>honoured</b>: a handler that calls {@code requireSystemAdmin(user)} and
 * throws the denial away still passes here. Nor can it check that the guard
 * chosen is the right one for that endpoint -- {@code requireAuthenticated}
 * on something that should be admin-only satisfies this test. Those remain
 * the job of the {@code *IntegrationTest} negative cases, which is why this
 * is a second floor and not a replacement for them.
 *
 * <p>Together the two coverage tests pin the shape of the design: an
 * endpoint receives the caller, and does something with them. Neither can be
 * satisfied by accident, and both fail at build time rather than in a
 * penetration test.
 *
 * <p>DB-free (classpath scanning plus bytecode reading, no context boot), so
 * it runs in the same CI job it protects.
 */
class EndpointGuardCoverageTest {

    /**
     * Every method this codebase uses to refuse a caller. Recognised by
     * name, on any owner, because the alternative -- pinning owners too --
     * breaks on a rename without catching anything a rename would break.
     *
     * <p>This list is short on purpose. Adding to it widens what counts as
     * "guarded" for all 112 endpoints at once, so a new entry should be a
     * genuine new guard, not a way to quiet a failure.
     */
    private static final Set<String> GUARD_METHODS = Set.of(
            // PermissionChecker: the permission catalog.
            "hasPermission",
            // ManagerScope: whose personal data this caller may read (SEC-13).
            "isDepartmentScoped", "visibleActiveUsers",
            // DepartmentMatcher: content/compliance visibility by department.
            "matches",
            // DirectMessagePermission: who this caller may message.
            "canSend",
            // QuizGateChecker: the quiz gate in front of a required reading.
            "denialFor"
    );

    /**
     * The controllers' own denial helpers are private and named by
     * convention -- requireAuthenticated, requireSystemAdmin,
     * requireContentAdmin, requireManagerOrAdmin, requireReportsExport,
     * requireComplianceAssign, requireSystemAuditNonManager. Matching the
     * prefix rather than listing them keeps a new one from being invisible
     * here on the day it is written.
     */
    private static final String GUARD_PREFIX = "require";

    /**
     * A repository finder that takes the caller's own id is the whole
     * authorization for the endpoints that use it: favourites and messages
     * are never fetched by id alone, only by (id, userId), so another user's
     * row comes back empty and the handler answers 404. Counting it as a
     * guard is the point -- it is one, and a stricter list would have forced
     * those handlers onto the allowlist and hidden them.
     */
    private static boolean isOwnerScopedLookup(String methodName) {
        return methodName.startsWith("findBy") && methodName.contains("UserId");
    }

    /**
     * Endpoints that deliberately refuse nobody, each with the reason and,
     * where one exists, the open decision it belongs to.
     */
    private static final Set<String> NO_GUARD_BY_DESIGN = Set.of(
            // Issues the token. There is no caller to refuse yet; abuse is
            // bounded by LoginRateLimiter instead.
            "POST /api/auth/login",
            // DEC-P02, the logout contract. Null-tolerant on purpose
            // (AuthController:136-141): logging out when already logged out
            // is not an error, and a 401 here would strand the frontend's
            // own logout path when a token expires in an open tab. It does
            // take the principal, so the sibling test passes -- this is the
            // one endpoint where the two guards legitimately disagree.
            // REVISIT with DEC-P02, not before.
            "POST /api/auth/logout",
            // Liveness/readiness probe. Returns only status strings, and a
            // probe that needed a token could not be used by a load balancer.
            "GET /api/health",
            // DEC-P01 / QUESTIONS_FOR_IT.md #9, deliberately unresolved.
            // Same entry as in EndpointPrincipalCoverageTest; both go when
            // the decision lands.
            "GET /uploads/{filename}"
    );

    @Test
    void everyEndpointCallsSomethingThatCanRefuseTheCaller() {
        List<String> ungoverned = new ArrayList<>();
        int checked = 0;

        for (Class<?> controller : ControllerEndpoints.restControllers()) {
            Map<String, Set<Invocation>> invocations = readInvocations(controller);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                checked++;
                String route = ControllerEndpoints.route(mapping);
                if (NO_GUARD_BY_DESIGN.contains(route)) {
                    continue;
                }
                if (!reachesAGuard(controller, method, invocations)) {
                    ungoverned.add(route + "  (" + controller.getSimpleName() + "#" + method.getName() + ")");
                }
            }
        }

        assertFalse(checked == 0, "no controller mappings found -- the guard would pass vacuously");
        assertEquals(List.of(), ungoverned.stream().sorted().toList(),
                "these endpoints call no require*/PermissionChecker/ManagerScope/DepartmentMatcher/"
                        + "DirectMessagePermission guard and no owner-scoped repository finder, directly or "
                        + "through a helper in the same controller. Under SecurityConfig's permitAll that makes "
                        + "them reachable by anyone. Add the guard the endpoint needs, or -- if it is public on "
                        + "purpose -- add it to NO_GUARD_BY_DESIGN with the reason");
    }

    /**
     * Keeps the exemptions honest in the other direction, the same way
     * {@link EndpointPrincipalCoverageTest#allowlistHasNoStaleEntries} does:
     * an entry naming a route that no longer exists, or one that has since
     * grown a guard, is deleted rather than left to cover whatever later
     * takes that path.
     */
    @Test
    void allowlistHasNoStaleEntries() {
        Set<String> known = new LinkedHashSet<>();
        Set<String> stillUngoverned = new LinkedHashSet<>();

        for (Class<?> controller : ControllerEndpoints.restControllers()) {
            Map<String, Set<Invocation>> invocations = readInvocations(controller);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                String route = ControllerEndpoints.route(mapping);
                known.add(route);
                if (!reachesAGuard(controller, method, invocations)) {
                    stillUngoverned.add(route);
                }
            }
        }

        assertEquals(List.of(), NO_GUARD_BY_DESIGN.stream().filter(r -> !known.contains(r)).sorted().toList(),
                "NO_GUARD_BY_DESIGN names routes that no longer exist -- delete them");
        assertEquals(List.of(), NO_GUARD_BY_DESIGN.stream().filter(r -> !stillUngoverned.contains(r)).sorted().toList(),
                "these routes now call a guard, so their exemption is obsolete -- remove them from "
                        + "NO_GUARD_BY_DESIGN (if this is POST /api/auth/logout, DEC-P02 has been decided; if it "
                        + "is GET /uploads/{filename}, DEC-P01 has)");
    }

    /** One call site: who is invoked, by what name, with what descriptor. */
    private record Invocation(String owner, String name, String descriptor) {
    }

    /**
     * Walks the handler's calls, following any call that stays inside the
     * same controller so a guard invoked from a private helper still counts.
     * Cross-class calls are recorded but not followed: a guard reached three
     * services deep is not something a reader of the handler can see either,
     * and following it would turn this into a whole-program analysis whose
     * failures nobody could act on.
     *
     * <p>A guard invoked from inside a lambda is not seen either: lambdas
     * compile to {@code invokedynamic}, which is a different instruction.
     * That errs the safe way -- such a handler fails this test rather than
     * passing it -- and every guard in the codebase today is a plain call at
     * the top of the handler, which is also where it belongs.
     */
    private static boolean reachesAGuard(Class<?> controller, Method handler, Map<String, Set<Invocation>> invocations) {
        String internalName = Type.getInternalName(controller);
        Set<String> visited = new HashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(handler.getName() + Type.getMethodDescriptor(handler));

        while (!queue.isEmpty()) {
            String key = queue.remove(queue.size() - 1);
            if (!visited.add(key)) {
                continue;
            }
            for (Invocation invocation : invocations.getOrDefault(key, Set.of())) {
                if (invocation.name().startsWith(GUARD_PREFIX)
                        || GUARD_METHODS.contains(invocation.name())
                        || isOwnerScopedLookup(invocation.name())) {
                    return true;
                }
                if (internalName.equals(invocation.owner())) {
                    queue.add(invocation.name() + invocation.descriptor());
                }
            }
        }
        return false;
    }

    /** name+descriptor of a method -> everything its body invokes. */
    private static Map<String, Set<Invocation>> readInvocations(Class<?> controller) {
        Map<String, Set<Invocation>> byMethod = new HashMap<>();
        String resource = Type.getInternalName(controller) + ".class";
        try (InputStream bytecode = controller.getClassLoader().getResourceAsStream(resource)) {
            if (bytecode == null) {
                throw new IllegalStateException("no bytecode on the classpath for " + controller.getName()
                        + " -- this guard reads .class files and cannot work without them");
            }
            new ClassReader(bytecode).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    Set<Invocation> calls = byMethod.computeIfAbsent(name + descriptor, k -> new LinkedHashSet<>());
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String calledDescriptor, boolean isInterface) {
                            calls.add(new Invocation(owner, calledName, calledDescriptor));
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (IOException e) {
            throw new IllegalStateException("could not read bytecode for " + controller.getName(), e);
        }
        return byMethod;
    }
}
