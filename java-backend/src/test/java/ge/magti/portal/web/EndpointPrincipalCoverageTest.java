package ge.magti.portal.web;

import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Fails the build when a new endpoint is added that cannot authorize anyone.
 *
 * <h2>Why a coverage test and not {@code @PreAuthorize}</h2>
 *
 * {@code SecurityConfig:45} is {@code anyRequest().permitAll()}, and there is
 * not one {@code @PreAuthorize} in the module -- every one of the 112
 * endpoints authorizes inside its own handler, through a {@code require*}
 * helper, {@link ge.magti.portal.security.PermissionChecker} or
 * {@link ge.magti.portal.security.ManagerScope}. That is a working design,
 * but it fails open: a handler that simply forgets to call its guard is
 * reachable by anyone, and nothing in the framework notices.
 *
 * <p>Method bodies are not visible to reflection, so this cannot assert that
 * a guard is actually <i>called</i>. It asserts the one precondition that is
 * visible and that no guard can do without: the handler must be handed the
 * caller. A handler with no {@code @AuthenticationPrincipal User} parameter
 * has nothing to check a role, a permission or an owner id against -- it is
 * structurally incapable of authorizing, whatever its body says.
 *
 * <p>So this is a floor, not a ceiling. It cannot catch a handler that takes
 * the principal and ignores it; that is what the {@code *IntegrationTest}
 * negative cases are for. It does catch the failure that has no other
 * backstop at all.
 *
 * <p>Deliberately DB-free (classpath scanning only, no context boot), like
 * {@link ge.magti.portal.OracleTagCoverageTest}, so it runs in the same CI
 * job it protects rather than in the Oracle job.
 *
 * <h2>The allowlist is a record of decisions, not a convenience</h2>
 *
 * Three endpoints are public on purpose and are named below with the reason.
 * {@code GET /uploads/{filename}} is the open file-entitlement question
 * (QUESTIONS_FOR_IT.md &sect;9) -- it is pinned here so that closing that
 * question shows up as a deliberate edit to this list. The list is also
 * checked for staleness in both directions: an entry that stops being public
 * fails just as loudly as an endpoint that quietly becomes public, so the
 * allowlist cannot outlive the decision it records.
 */
class EndpointPrincipalCoverageTest {

    /**
     * Route -&gt; why it may run without a caller. Keyed by "METHOD path" so a
     * rename of the handler method does not silently widen the exemption.
     */
    private static final Set<String> PUBLIC_BY_DESIGN = Set.of(
            // Issues the token; there is no principal yet by definition.
            // Abuse is bounded by LoginRateLimiter, not by authentication.
            "POST /api/auth/login",
            // Liveness/readiness probe. Reports only "ok"/"redis
            // not_configured" -- no tenant data, and a probe that needed a
            // token could not be used by a load balancer.
            "GET /api/health",
            // DEC-P01 / QUESTIONS_FOR_IT.md #9, deliberately unresolved.
            // Article bodies contain inline <img src="/uploads/...">, which
            // stops rendering the moment this needs a token, so the fix is a
            // frontend change too. Filenames are UUIDs (obscurity, not
            // authorization). REMOVE THIS ENTRY when the decision lands.
            "GET /uploads/{filename}"
    );

    @Test
    void everyEndpointReceivesTheAuthenticatedPrincipal() {
        List<String> routes = new ArrayList<>();
        List<String> unguardable = new ArrayList<>();

        for (Class<?> controller : ControllerEndpoints.restControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                String route = ControllerEndpoints.route(mapping);
                routes.add(route);
                if (PUBLIC_BY_DESIGN.contains(route)) {
                    continue;
                }
                if (!receivesPrincipal(method)) {
                    unguardable.add(route + "  (" + controller.getSimpleName() + "#" + method.getName() + ")");
                }
            }
        }

        assertFalse(routes.isEmpty(), "scanner found no controller mappings at all -- the guard would pass vacuously");
        assertEquals(List.of(), unguardable.stream().sorted().toList(),
                "these endpoints take no @AuthenticationPrincipal User, so they cannot check a role, a permission "
                        + "or an owner id no matter what the handler body does. Add the parameter and the matching "
                        + "require*/PermissionChecker/ManagerScope guard, or -- if the endpoint is public on "
                        + "purpose -- add it to PUBLIC_BY_DESIGN with the reason");
    }

    /**
     * The other direction: an allowlist entry that no longer names a public
     * endpoint. Without this, closing DEC-P01 would leave a stale exemption
     * sitting in the list, ready to re-open the hole for whatever route later
     * reuses that path.
     */
    @Test
    void allowlistHasNoStaleEntries() {
        Set<String> stillPublic = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>();

        for (Class<?> controller : ControllerEndpoints.restControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                String route = ControllerEndpoints.route(mapping);
                known.add(route);
                if (!receivesPrincipal(method)) {
                    stillPublic.add(route);
                }
            }
        }

        List<String> gone = PUBLIC_BY_DESIGN.stream().filter(r -> !known.contains(r)).sorted().toList();
        assertEquals(List.of(), gone,
                "PUBLIC_BY_DESIGN names routes that no longer exist -- delete them so the list keeps meaning "
                        + "what it says");

        List<String> nowGuarded = PUBLIC_BY_DESIGN.stream().filter(r -> !stillPublic.contains(r)).sorted().toList();
        assertEquals(List.of(), nowGuarded,
                "these routes now take a principal, so their exemption is obsolete -- remove them from "
                        + "PUBLIC_BY_DESIGN (if this is GET /uploads/{filename}, DEC-P01 has been decided and "
                        + "the comment above it should go too)");
    }

    /**
     * True when the handler is handed the caller. The type is checked, not
     * just the annotation: {@code @AuthenticationPrincipal String username}
     * would satisfy the annotation while giving the guards nothing to read a
     * role or a department off.
     */
    private static boolean receivesPrincipal(Method method) {
        for (Parameter parameter : method.getParameters()) {
            for (Annotation annotation : parameter.getAnnotations()) {
                if (annotation.annotationType() == AuthenticationPrincipal.class
                        && parameter.getType() == User.class) {
                    return true;
                }
            }
        }
        return false;
    }
}
