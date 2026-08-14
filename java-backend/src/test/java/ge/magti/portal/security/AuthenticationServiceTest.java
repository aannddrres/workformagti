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
        // The bypass tests below need the dev configuration EXPLICITLY since
        // audit OPUS5 SEC-01: a fresh PortalProperties is now production with
        // the bypass off (proven by DefaultSecurityPostureTest).
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

    @Test
    void productionModeDisablesJitProvisioningAndBypass() {
        properties.setAppEnv("production");
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        Optional<User> result = service.authenticate("admin@magti.ge", "any-password");

        assertTrue(result.isEmpty());
        verify(userRepository, never()).save(any());
    }

    /**
     * The second half of the SEC-01 fix: even in a development app-env, the
     * bypass stays shut unless ALLOW_DEV_LOGIN was asked for explicitly.
     * Before the fix "not production" was the whole gate.
     */
    @Test
    void developmentEnvWithoutTheOptInFlagStillDisablesTheBypass() {
        properties.getSecurity().setAllowDevLogin(false);
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
