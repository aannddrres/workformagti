package ge.magti.portal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collection;
import java.util.Map;

/**
 * Refuses a request that gives a single-valued parameter more than once
 * (ASVS V15.3.7, HTTP parameter pollution).
 *
 * <p>Left to itself Spring decides quietly: a number takes the first value,
 * text joins them with a comma, and a form body's fields merge with the
 * query string's (RepeatedParameterGuardTest records each). That is
 * consistent inside this backend, but anything in front of it that reads the
 * other copy -- a proxy, a WAF rule, a log -- then disagrees with what the
 * handler used. No handler here takes a list parameter today, so the refusal
 * costs a correct client nothing; one that ever does may still repeat that
 * name, since that is how a list is sent.
 *
 * <p>Answered by GlobalExceptionHandler with the same 400 as an unbindable
 * parameter, and like that one it does not echo the value.
 */
public class RepeatedParameterGuard implements HandlerInterceptor {

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        String[] declaredNames = NAMES.getParameterNames(method.getMethod());
        for (MethodParameter parameter : method.getMethodParameters()) {
            RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
            if (annotation == null || takesSeveral(parameter)) {
                continue;
            }
            String name = parameterName(annotation, parameter, declaredNames);
            String[] values = name == null ? null : request.getParameterValues(name);
            if (values != null && values.length > 1) {
                throw new RepeatedParameterException(name);
            }
        }
        return true;
    }

    /** {@code name} and {@code value} are aliases; the raw annotation resolves neither into the other. */
    private static String parameterName(RequestParam annotation, MethodParameter parameter, String[] declaredNames) {
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }
        if (!annotation.value().isEmpty()) {
            return annotation.value();
        }
        return declaredNames == null ? null : declaredNames[parameter.getParameterIndex()];
    }

    private static boolean takesSeveral(MethodParameter parameter) {
        Class<?> type = parameter.nestedIfOptional().getNestedParameterType();
        return type.isArray() || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                || MultipartFile.class.isAssignableFrom(type);
    }

    /** A single-valued request parameter arrived more than once. */
    public static class RepeatedParameterException extends RuntimeException {
        public RepeatedParameterException(String parameterName) {
            super("request parameter given more than once: " + parameterName);
        }
    }
}
