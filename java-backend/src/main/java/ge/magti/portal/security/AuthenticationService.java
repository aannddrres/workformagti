package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates a login, including its dev-only conveniences -- see
 * docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md and the
 * auth-bypass-intentional-pending-ad memory: the password-less bypass for
 * known test emails is deliberate, not a bug, kept until real Active
 * Directory integration replaces this whole login step.
 */
@Service
public class AuthenticationService {

    /** The development personas' addresses. */
    private static final Set<String> DEV_TEST_EMAILS = Set.of(
            "admin@magti.ge", "content@magti.ge", "manager@magti.ge",
            "nino@magti.ge", "tech@magti.ge", "info@magti.ge");

    private record JitOverride(Role role, String department, String name) {
    }

    /** Role, department and name a development persona is created with. */
    private static final Map<String, JitOverride> JIT_PROVISION_OVERRIDES = Map.of(
            "admin@magti.ge", new JitOverride(Role.SYSTEM_ADMIN, "Administration", "სისტემური ადმინი"),
            "content@magti.ge", new JitOverride(Role.CONTENT_ADMIN, "Content Creation", "კონტენტის ადმინისტრატორი"),
            "manager@magti.ge", new JitOverride(Role.MANAGER, "ტექნიკური", "ჯგუფის მენეჯერი"),
            "nino@magti.ge", new JitOverride(null, null, "ნინო ჩიტიშვილი"),
            "tech@magti.ge", new JitOverride(null, "ტექნიკური", "ტექნიკური ოპერატორი"),
            "info@magti.ge", new JitOverride(null, "საინფორმაციო", "საინფორმაციო ოპერატორი"));

    private static final String JIT_DUMMY_PASSWORD = "dummy_password_for_jit_user";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PortalProperties properties;

    public AuthenticationService(
            UserRepository userRepository, PasswordEncoder passwordEncoder, PortalProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * Looks up an account by email without authenticating -- used only to
     * decide whether a failed login attempt should get a LOGIN_FAILED
     * audit row (an audit row needs a real user id for its NOT NULL admin_id FK, so a
     * login attempt against a genuinely unknown email logs nothing).
     */
    public Optional<User> findExistingAccount(String email) {
        return userRepository.findByEmailIgnoreCase(email.toLowerCase());
    }

    /**
     * Whether this address signs in through the password-less development
     * bypass rather than a real credential check.
     *
     * <p>SEC-01: requires BOTH a non-production environment and the explicit
     * allow-dev-login opt-in. !isProduction() alone was satisfied by any
     * environment that was not exactly "production" -- including a
     * deployment whose manifest simply omitted APP_ENV.
     *
     * <p>The demo org's "presentation." prefix rode these same guards until
     * the demo stack left the repository (2026-09-29); an address with it is
     * an ordinary one now, and needs its real password.
     *
     * <p>Public so the login endpoint can keep these personas on the bypass
     * while every other address goes to the company directory.
     */
    public boolean isDevLoginAccount(String email) {
        String lowerEmail = email.toLowerCase();
        return !properties.isProduction()
                && properties.getSecurity().isAllowDevLogin()
                && (lowerEmail.startsWith("test_operator_")
                        || DEV_TEST_EMAILS.contains(lowerEmail));
    }

    public Optional<User> authenticate(String email, String password) {
        String lowerEmail = email.toLowerCase();
        boolean isTestAccount = isDevLoginAccount(lowerEmail);

        User user = userRepository.findByEmailIgnoreCase(lowerEmail)
                .orElseGet(() -> isTestAccount ? jitProvision(lowerEmail) : null);

        if (user == null || !user.isActive()) {
            return Optional.empty();
        }
        if (isTestAccount) {
            synchronizeDevDepartment(lowerEmail, user);
            return Optional.of(user);
        }
        if (user.getHashedPassword() == null || !passwordEncoder.matches(password, user.getHashedPassword())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    private User jitProvision(String lowerEmail) {
        JitOverride override = JIT_PROVISION_OVERRIDES.get(lowerEmail);
        Role role = (override != null && override.role() != null) ? override.role() : Role.OPERATOR;
        String department = (override != null && override.department() != null) ? override.department() : "Support";
        String name = (override != null && override.name() != null)
                ? override.name()
                : "Test User " + lowerEmail.substring(0, lowerEmail.indexOf('@'));

        User user = new User();
        user.setEmail(lowerEmail);
        user.setName(name);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode(JIT_DUMMY_PASSWORD));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

        return userRepository.save(user);
    }

    /**
     * Keeps already-provisioned local personas aligned with the canonical
     * department values used by article targeting. Older databases contain
     * {@code Support}/{@code Informational}; those values make every
     * Georgian-targeted article disappear for the corresponding operator.
     * This path is reachable only when the explicit development-login bypass
     * is enabled, so production user profiles are never rewritten here.
     */
    private void synchronizeDevDepartment(String lowerEmail, User user) {
        JitOverride override = JIT_PROVISION_OVERRIDES.get(lowerEmail);
        if (override == null || override.department() == null
                || override.department().equals(user.getDepartment())) {
            return;
        }
        // Do not downgrade a seeded group ("ტექნიკური — ჯგუფი 01") to its bare
        // parent ("ტექნიკური"). This override exists to migrate a legacy
        // English department to Georgian, not to erase the specific group the
        // demo seeder placed the persona in -- doing so moved manager@/tech@
        // out of ჯგუფი 01 into a phantom bare-named "group" and skewed the
        // department rollup a manager sees.
        String current = user.getDepartment();
        if (current != null && current.startsWith(override.department() + " ")) {
            return;
        }
        user.setDepartment(override.department());
        userRepository.save(user);
    }
}
