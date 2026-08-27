package ge.magti.portal.web;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds every HTTP endpoint in the module without booting a context, so the
 * guards built on top of it ({@link EndpointPrincipalCoverageTest},
 * {@link EndpointGuardCoverageTest}) can run in the DB-free CI job.
 *
 * <p>Shared deliberately: guards that disagreed about what counts as an
 * endpoint would each be checking a different subset, and the endpoint that
 * fell between them is exactly the one nobody would notice. They differ in
 * what they assert, never in what they look at.
 *
 * <p>Public rather than package-private only because
 * {@code PermissionEnforcementCoverageTest} lives in
 * {@code ge.magti.portal.domain} -- it asks about permissions, which is a
 * domain question, but has to look at endpoints to answer it.
 */
public final class ControllerEndpoints {

    private ControllerEndpoints() {
    }

    /** Every {@code @RestController} on the classpath, main sources included. */
    public static List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("ge.magti.portal")) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but unloadable: " + definition.getBeanClassName(), e);
            }
        }
        return found;
    }

    /**
     * The merged {@code @RequestMapping} behind {@code @GetMapping} and
     * friends, or null when the method is not a request handler at all.
     */
    public static RequestMapping mappingOf(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }

    /** "GET /api/articles/{id}" -- stable across handler renames. */
    public static String route(RequestMapping mapping) {
        String verb = mapping.method().length == 0 ? "ANY" : mapping.method()[0].name();
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        String path = paths.length == 0 ? "(no path)" : paths[0];
        return verb + " " + path;
    }
}
