package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
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
    void jitOperatorPersonasUseCanonicalGeorgianDepartments() {
        when(userRepository.findByEmailIgnoreCase("tech@magti.ge")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("info@magti.ge")).thenReturn(Optional.empty());

        User tech = service.authenticate("tech@magti.ge", "anything").orElseThrow();
        User info = service.authenticate("info@magti.ge", "anything").orElseThrow();

        assertEquals("ტექნიკური", tech.getDepartment());
        assertEquals("საინფორმაციო", info.getDepartment());
    }

    @Test
    void existingDevPersonaIsMovedFromLegacyEnglishDepartment() {
        User existing = new User();
        existing.setEmail("tech@magti.ge");
        existing.setRole(Role.OPERATOR);
        existing.setDepartment("Support");
        existing.setActive(true);
        when(userRepository.findByEmailIgnoreCase("tech@magti.ge")).thenReturn(Optional.of(existing));

        User result = service.authenticate("tech@magti.ge", "anything").orElseThrow();

        assertEquals("ტექნიკური", result.getDepartment());
        verify(userRepository).save(existing);
    }

    @Test
    void doesNotDowngradeASeededGroupToItsBareParentDepartment() {
        // manager@ / tech@ / info@ are seeded into ჯგუფი 01, but their JIT
        // override department is the bare parent. Signing in must NOT rewrite
        // the specific group away -- that moved them into a phantom
        // bare-named group and skewed the manager rollup.
        User seededGroupMember = new User();
        seededGroupMember.setEmail("tech@magti.ge");
        seededGroupMember.setRole(Role.OPERATOR);
        seededGroupMember.setDepartment("ტექნიკური — ჯგუფი 01");
        seededGroupMember.setActive(true);
        when(userRepository.findByEmailIgnoreCase("tech@magti.ge")).thenReturn(Optional.of(seededGroupMember));

        User result = service.authenticate("tech@magti.ge", "anything").orElseThrow();

        assertEquals("ტექნიკური — ჯგუფი 01", result.getDepartment());
        verify(userRepository, never()).save(seededGroupMember);
    }

    @Test
    void testOperatorPrefixAlsoBypassesPasswordAsGenericOperator() {
        when(userRepository.findByEmailIgnoreCase("test_operator_42@magti.ge")).thenReturn(Optional.empty());

        Optional<User> result = service.authenticate("test_operator_42@magti.ge", "whatever");

        assertTrue(result.isPresent());
        assertEquals(Role.OPERATOR, result.get().getRole());
    }

    @Test
    void presentationPrefixBypassesPasswordForAnExistingDemoAccount() {
        // A real seeded demo operator, hashed with the presentation password.
        // The persona picker signs in with "local-persona", which is NOT that
        // password -- the prefix bypass is what lets it through in the demo.
        User demoOperator = new User();
        demoOperator.setEmail("presentation.tech.g02.op05@magti.ge");
        demoOperator.setRole(Role.OPERATOR);
        demoOperator.setDepartment("ტექნიკური — ჯგუფი 02");
        demoOperator.setActive(true);
        demoOperator.setHashedPassword("$2a$presentation-password-hash");
        when(userRepository.findByEmailIgnoreCase("presentation.tech.g02.op05@magti.ge"))
                .thenReturn(Optional.of(demoOperator));

        User result = service.authenticate("presentation.tech.g02.op05@magti.ge", "local-persona").orElseThrow();

        assertEquals(Role.OPERATOR, result.getRole());
        // Its group department must be left exactly as seeded -- the bypass
        // must not rewrite a presentation account the way it canonicalises the
        // six named personas.
        assertEquals("ტექნიკური — ჯგუფი 02", result.getDepartment());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void presentationPrefixIsInertInProduction() {
        properties.setAppEnv("production");
        User demoOperator = new User();
        demoOperator.setEmail("presentation.tech.g02.op05@magti.ge");
        demoOperator.setRole(Role.OPERATOR);
        demoOperator.setActive(true);
        demoOperator.setHashedPassword("$2a$presentation-password-hash");
        when(userRepository.findByEmailIgnoreCase("presentation.tech.g02.op05@magti.ge"))
                .thenReturn(Optional.of(demoOperator));
        when(passwordEncoder.matches("local-persona", "$2a$presentation-password-hash")).thenReturn(false);

        // In production the prefix earns nothing: it falls through to the real
        // bcrypt check, which the persona password fails.
        assertTrue(service.authenticate("presentation.tech.g02.op05@magti.ge", "local-persona").isEmpty());
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

    /**
     * DEC-P04, from the side that actually hands out tokens.
     *
     * <p>This gate is {@code !properties.isProduction()}, which until the fix
     * was {@code equalsIgnoreCase} with no trimming -- so
     * {@code APP_ENV=production } with one trailing space, a value .env files
     * and docker compose both preserve, was not production and the bypass
     * became eligible. {@link ge.magti.portal.config.ProductionSafetyGuard}
     * would normally refuse to boot that combination, except it reads the
     * same method and skipped its checks for the same reason: both halves of
     * SEC-01 fell to one invisible character.
     *
     * <p>allow-dev-login is left ON here on purpose. That is the dangerous
     * configuration, and the point is that a production APP_ENV refuses the
     * bypass however the value is spaced.
     */
    @Test
    void whitespaceAroundAProductionAppEnvDoesNotReEnableTheBypass() {
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        for (String spelling : List.of(" production", "production ", "  PRODUCTION  ", "\tproduction\n")) {
            properties.setAppEnv(spelling);

            assertTrue(service.authenticate("admin@magti.ge", "any-password").isEmpty(),
                    "APP_ENV=[" + spelling + "] handed out a password-less admin login");
        }
        verify(userRepository, never()).save(any());
    }

    /**
     * DEC-P05, from the side that hands out tokens. A shorthand or a typo in
     * APP_ENV must not be a way to reach the bypass.
     *
     * <p>{@code prod} is the one this repo's own SEC-01 comment has named
     * since the beginning; {@code produciton} is the same mistake with
     * nobody to have thought of it in advance. Both used to be
     * "not production" and therefore eligible, and the boot-time guard that
     * would have refused the combination read the same method and skipped
     * for the same reason.
     *
     * <p>allow-dev-login stays ON here on purpose: that is the dangerous
     * configuration, and the point is that only a named development
     * environment unlocks it.
     */
    @Test
    void anUnrecognisedAppEnvDoesNotEnableTheBypass() {
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        for (String spelling : List.of("prod", "produciton", "staging", "qa", "anything-at-all")) {
            properties.setAppEnv(spelling);

            assertTrue(service.authenticate("admin@magti.ge", "any-password").isEmpty(),
                    "APP_ENV=[" + spelling + "] handed out a password-less admin login");
        }
        verify(userRepository, never()).save(any());
    }

    /** Same gate, for the empty value: absent and blank must both fail safe. */
    @Test
    void aBlankAppEnvDoesNotEnableTheBypass() {
        when(userRepository.findByEmailIgnoreCase("admin@magti.ge")).thenReturn(Optional.empty());

        for (String spelling : List.of("", "   ")) {
            properties.setAppEnv(spelling);

            assertTrue(service.authenticate("admin@magti.ge", "any-password").isEmpty(),
                    "APP_ENV=[" + spelling + "] handed out a password-less admin login");
        }
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
