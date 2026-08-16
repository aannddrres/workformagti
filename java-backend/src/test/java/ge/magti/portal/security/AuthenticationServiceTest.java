package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private PortalProperties properties;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        properties = new PortalProperties();
        // Defaults are now production with the bypass off (SEC-01), so the
        // dev-login cases below have to ask for the insecure posture by name.
        // devDefaultsAreSafeWithNoConfigurationAtAll asserts the untouched
        // defaults instead.
        properties.setAppEnv("development");
        properties.getSecurity().setAllowDevLogin(true);
        service = new AuthenticationService(userRepository, passwordEncoder, properties);

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$encoded-dummy-hash");
    }

    @Test
    void jitProvisionsKnownTestEmailAndBypassesPassword() {
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        Optional<User> result = service.authenticate("admin@magti.ge", "any-password-at-all");

        assertTrue(result.isPresent());
        assertEquals(Role.SYSTEM_ADMIN, result.get().getRole());
        assertEquals("Administration", result.get().getDepartment());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void testOperatorPrefixAlsoBypassesPasswordAsGenericOperator() {
        when(userRepository.findByEmailIgnoreCase("test_operator_42@magti.ge")).thenReturn(Optional.empty());

        Optional<User> result = service.authenticate("test_operator_42@magti.ge", "whatever");

        assertTrue(result.isPresent());
        assertEquals(Role.OPERATOR, result.get().getRole());
    }

    /**
     * The acceptance criterion for SEC-01: a PortalProperties nobody has
     * configured must not accept a password-less login. Before the fix this
     * object defaulted to appEnv="development" and the bypass was live.
     */
    @Test
    void devLoginIsOffWithNoConfigurationAtAll() {
        PortalProperties untouched = new PortalProperties();
        AuthenticationService freshService =
                new AuthenticationService(userRepository, passwordEncoder, untouched);
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        assertTrue(untouched.isProduction(), "default app-env must be production");
        assertTrue(freshService.authenticate("admin@magti.ge", "any-password").isEmpty());
        verify(userRepository, never()).save(any());
    }

    /**
     * A non-production environment is no longer sufficient on its own: the
     * bypass also needs allow-dev-login, so a deployment that merely omits
     * APP_ENV cannot hand out admin tokens.
     */
    @Test
    void devEnvironmentAloneDoesNotEnableTheBypass() {
        properties.getSecurity().setAllowDevLogin(false);
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        assertTrue(service.authenticate("admin@magti.ge", "any-password").isEmpty());
        verify(userRepository, never()).save(any());
    }

    @Test
    void productionModeDisablesJitProvisioningAndBypass() {
        properties.setAppEnv("production");
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        Optional<User> result = service.authenticate("admin@magti.ge", "any-password");

        assertTrue(result.isEmpty());
        verify(userRepository, never()).save(any());
    }

    @Test
    void realUserNeedsTheCorrectBcryptPassword() {
        User realUser = new User();
        realUser.setEmail("operator@magti.ge");
        realUser.setRole(Role.OPERATOR);
        realUser.setActive(true);
        realUser.setHashedPassword("$2a$stored-hash");
        when(userRepository.findByEmailIgnoreCase("operator@magti.ge")).thenReturn(Optional.of(realUser));
        when(passwordEncoder.matches("correct-password", "$2a$stored-hash")).thenReturn(true);
        when(passwordEncoder.matches("wrong-password", "$2a$stored-hash")).thenReturn(false);

        assertTrue(service.authenticate("operator@magti.ge", "correct-password").isPresent());
        assertTrue(service.authenticate("operator@magti.ge", "wrong-password").isEmpty());
    }

    @Test
    void inactiveAccountIsRejectedEvenForATestEmail() {
        User disabled = new User();
        disabled.setEmail("admin@magti.ge");
        disabled.setRole(Role.SYSTEM_ADMIN);
        disabled.setActive(false);
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.of(disabled));

        assertTrue(service.authenticate("admin@magti.ge", "any-password").isEmpty());
    }
}
