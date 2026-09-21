package ge.magti.portal.security;

import java.util.Set;

/**
 * Who the company directory says just signed in -- and nothing else.
 *
 * <p>The directory's answer also carries an access token and a refresh token.
 * They are deliberately not fields here: the portal needs to know who the
 * person is, not to act as them anywhere, and a token that is never kept can
 * never leak from a log, a heap dump or a database backup (docs/QUESTIONS_FOR_IT.md
 * No.13 records how much those tokens can do).
 *
 * @param login           the directory login, e.g. {@code name.surname}
 * @param email           the address the portal account is keyed by
 * @param directoryUserId the directory's own stable id, kept for audit
 * @param authorities     every authority the token grants; only the mapped ones matter
 * @param department      the department claim when the directory sends one, else null
 * @param name            the full-name claim when the directory sends one, else null
 */
public record CorporateIdentity(
        String login,
        String email,
        String directoryUserId,
        Set<String> authorities,
        String department,
        String name) {
}
