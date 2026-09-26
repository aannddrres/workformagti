package ge.magti.portal.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.ColorConverter;
import org.springframework.boot.logging.logback.EnclosedInSquareBracketsConverter;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASVS V16.4.1. Logged values come from requests -- an address typed at
 * sign-in, a path, an exception message quoting input -- and a line break in
 * one of them used to start a new, forged log line. The console pattern in
 * application.yml now folds line breaks in the message into a space. This
 * renders an event through that exact pattern, the way Spring Boot's logback
 * set-up does, and checks the forged line cannot exist.
 */
class LogInjectionTest {

    @SuppressWarnings("unchecked")
    private static String consolePattern() throws Exception {
        try (InputStream yaml = LogInjectionTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(yaml);
            Map<String, Object> root = new Yaml().load(yaml);
            Map<String, Object> logging = (Map<String, Object>) root.get("logging");
            assertNotNull(logging, "application.yml has no logging section");
            Map<String, Object> pattern = (Map<String, Object>) logging.get("pattern");
            return (String) pattern.get("console");
        }
    }

    @Test
    void aLineBreakInALoggedValueCannotStartANewLogLine() throws Exception {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        Map<String, String> rules = new HashMap<>();
        rules.put("clr", ColorConverter.class.getName());
        rules.put("esb", EnclosedInSquareBracketsConverter.class.getName());
        rules.put("wEx", ExtendedWhitespaceThrowableProxyConverter.class.getName());
        context.putObject(ch.qos.logback.core.CoreConstants.PATTERN_RULE_REGISTRY, rules);

        String configured = consolePattern();
        String forged = "x\r\n2026-09-26T12:00:00.000+04:00  INFO --- [main] forged : ADMIN LOGIN OK";

        String rendered = render(context, configured, forged);
        assertEquals(1, lines(rendered), "exactly one line: " + rendered);
        assertTrue(rendered.contains("SIGN_IN_FAILED account=x 2026-09-26"), rendered);

        // Control: the same pattern with a bare %m is what Spring Boot ships,
        // and there the forged line does appear.
        String unprotected = configured.replace(PROTECTED_MESSAGE, "%m");
        assertTrue(configured.contains(PROTECTED_MESSAGE), configured);
        assertEquals(2, lines(render(context, unprotected, forged)));
    }

    private static final String PROTECTED_MESSAGE = "%replace(%m){'[\\r\\n]+', ' '}";

    private static String render(LoggerContext context, String pattern, String value) {
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();
        return layout.doLayout(new LoggingEvent("test", context.getLogger("ge.magti.portal.web.AuthController"),
                Level.WARN, "SIGN_IN_FAILED account={}", null, new Object[] {value}));
    }

    private static int lines(String rendered) {
        return rendered.split("\n", -1).length - 1;
    }
}
