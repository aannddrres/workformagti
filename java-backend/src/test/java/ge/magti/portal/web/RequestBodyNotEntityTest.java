package ge.magti.portal.web;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Fails the build when a handler binds a request body straight onto a JPA
 * entity -- mass assignment, where a caller sets {@code role} or
 * {@code author_id} by adding it to the JSON.
 *
 * <p>FindSecBugs reports ENTITY_MASS_ASSIGNMENT on 149 handlers, every one
 * of them because the handler takes {@code @AuthenticationPrincipal User},
 * which is the signed-in caller, not request input. Its rule cannot tell the
 * two apart, so {@code spotbugs-exclude.xml} drops the pattern -- and this
 * test is what makes dropping it safe: the case the rule exists for is
 * checked here instead, precisely.
 */
class RequestBodyNotEntityTest {

    @Test
    void noRequestBodyIsAnEntity() {
        int bodies = 0;
        List<String> entityBodies = new ArrayList<>();

        for (Class<?> controller : ControllerEndpoints.restControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = ControllerEndpoints.mappingOf(method);
                if (mapping == null) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) {
                        continue;
                    }
                    bodies++;
                    if (parameter.getType().isAnnotationPresent(Entity.class)) {
                        entityBodies.add(ControllerEndpoints.route(mapping) + "  ("
                                + controller.getSimpleName() + "#" + method.getName() + " takes "
                                + parameter.getType().getSimpleName() + ")");
                    }
                }
            }
        }

        assertFalse(bodies == 0, "scanner found no @RequestBody parameters at all -- the guard would pass vacuously");
        assertEquals(List.of(), entityBodies.stream().sorted().toList(),
                "these handlers bind JSON directly onto a JPA entity, so a caller can set any column by naming "
                        + "it. Accept a *Request record with only the fields the endpoint means to change");
    }
}
