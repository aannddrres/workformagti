package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signs an employee in through the company directory and keeps their portal
 * account in step with what the directory says about them.
 *
 * <p>What the directory decides, on <b>every</b> sign-in (owner decision,
 * 2026-09-21, closing PO-28 and the role half of PO-29): the role. A role
 * changed or withdrawn there takes effect at the next sign-in, and -- because
 * {@code JwtAuthenticationFilter} re-reads the role on every request -- on
 * every request after it.
 *
 * <p>What stays the portal's: per-user permission overrides (the directory
 * has never heard of them, so a sign-in never touches them), and whether the
 * account is active. An administrator's deactivation (PO-24) outranks a
 * correct password; otherwise switching a leaver off would last only until
 * they next typed it.
 *
 * <p>Department and name are taken from the directory only when it sends
 * them, and never used to blank a value -- the rule PO-29 wrote down so that
 * a department an administrator typed in by hand (PO-23) survives the next
 * sign-in. The live directory sends neither today (2026-09-21).
 */
@Service
public class CorporateLoginService {

    private static final Logger logger = LoggerFactory.getLogger(CorporateLoginService.class);

    public sealed interface Result permits SignedIn, Rejected, Unavailable {
    }

    /**
     * @param previousRole the role the account had before this sign-in
     *                     changed it, or null when it did not change
     * @param created      whether this sign-in created the account
     */
    public record SignedIn(User user, Role previousRole, boolean created) implements Result {
    }

    /** @param deactivated true when the password was right but the portal account is switched off */
    public record Rejected(boolean deactivated) implements Result {
    }

    public record Unavailable() implements Result {
    }

    private final CorporateAuthClient client;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PortalProperties.Corporate settings;

    public CorporateLoginService(
            CorporateAuthClient client,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PortalProperties properties) {
        this.client = client;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.settings = properties.getSecurity().getCorporate();
    }

    public Result login(String typedEmail, String password) {
        String email = typedEmail.trim().toLowerCase(Locale.ROOT);
        String domain = settings.getDomain() == null ? "" : settings.getDomain().trim().toLowerCase(Locale.ROOT);
        if (!domain.isEmpty() && !email.endsWith(domain)) {
            // Not an address the directory can know. Refused here rather than
            // sent on: no reason to hand an outsider's password to it at all.
            return new Rejected(false);
        }

        CorporateAuthClient.Outcome outcome = client.authenticate(email, password);
        if (outcome instanceof CorporateAuthClient.Rejected) {
            return new Rejected(false);
        }
        if (outcome instanceof CorporateAuthClient.Unavailable) {
            return new Unavailable();
        }
        return provision(((CorporateAuthClient.Authenticated) outcome).identity());
    }

    private Result provision(CorporateIdentity identity) {
        DirectoryRoleMapper mapper = new DirectoryRoleMapper(settings.getRoleMap());
        Set<Role> mapped = mapper.mappedRoles(identity.authorities());
        if (mapped.size() > 1) {
            logger.warn("Directory grants {} more than one portal role {}; using the highest",
                    identity.email(), mapped);
        }
        Role directoryRole = mapper.roleFor(identity.authorities());

        User user = userRepository.findByEmailIgnoreCase(identity.email()).orElse(null);
        if (user == null) {
            return new SignedIn(userRepository.save(newAccount(identity, directoryRole)), null, true);
        }
        if (!user.isActive()) {
            return new Rejected(true);
        }

        Role previous = user.getRole();
        boolean changed = false;
        if (previous != directoryRole) {
            user.setRole(directoryRole);
            changed = true;
        }
        if (identity.name() != null && !identity.name().equals(user.getName())) {
            user.setName(identity.name());
            changed = true;
        }
        if (identity.department() != null && !identity.department().equals(user.getDepartment())) {
            user.setDepartment(identity.department());
            changed = true;
        }
        if (changed) {
            user = userRepository.save(user);
        }
        return new SignedIn(user, previous != directoryRole ? previous : null, false);
    }

    private User newAccount(CorporateIdentity identity, Role role) {
        User user = new User();
        user.setEmail(identity.email());
        user.setName(identity.name() != null ? identity.name()
                : identity.login() != null ? identity.login()
                : identity.email().substring(0, identity.email().indexOf('@')));
        user.setRole(role);
        // Null, not a placeholder: an unknown department sees only content
        // addressed to everyone until an administrator assigns one (PO-23).
        // The development JIT's "Support" matches no real audience and would
        // look like an assignment.
        user.setDepartment(identity.department());
        user.setActive(true);
        // The column is required, and the portal never checks a password for
        // these accounts. A random value nobody knows keeps the local login
        // path -- off in production anyway (PO-25) -- unable to match it.
        user.setHashedPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return user;
    }
}
