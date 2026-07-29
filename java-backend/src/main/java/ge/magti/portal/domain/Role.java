package ge.magti.portal.domain;

import java.util.Arrays;

/**
 * Mirrors security.py's ROLE_* string constants (security.py:30-34) and the
 * users.role column (models.py:36).
 *
 * <p>Trap carried over deliberately: {@link #SYSTEM_ADMIN}'s wire value is
 * the bare string "admin", not "system_admin" -- that's what's actually
 * stored in the column and signed into the JWT "role" claim today
 * (routers/auth.py:67). Renaming the value to match the enum constant name
 * would silently break every existing row and token.
 */
public enum Role {
    OPERATOR("operator"),
    MANAGER("manager"),
    CONTENT_ADMIN("content_admin"),
    SYSTEM_ADMIN("admin");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Role fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + value));
    }
}
