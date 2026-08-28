package ge.magti.portal.content;

import ge.magti.portal.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-closed legal-hold authority boundary.
 *
 * <p>The responsible DPO/Legal authority is intentionally not inferred from
 * an application role. Production owners must supply the exact approved SSO
 * identities through configuration; the default empty allowlist means nobody
 * can set or release a hold. No configured identities are logged here.
 */
@Component
public class LegalHoldAuthority {

    private final Set<String> authorizedEmails;

    public LegalHoldAuthority(
            @Value("${portal.retention.legal-hold-authorized-emails:}") String configuredEmails) {
        this.authorizedEmails = Arrays.stream(configuredEmails.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean canManage(User user) {
        return user != null
                && user.isActive()
                && user.getEmail() != null
                && authorizedEmails.contains(user.getEmail().strip().toLowerCase(Locale.ROOT));
    }
}
