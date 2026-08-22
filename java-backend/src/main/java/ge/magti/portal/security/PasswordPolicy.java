package ge.magti.portal.security;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors security.py's validate_password_policy (security.py:105-124).
 * Returns the list of Georgian-language failure fragments (empty = valid)
 * rather than throwing, matching this port's established pattern of
 * controllers building their own {@code ResponseEntity} 400s instead of a
 * global exception handler.
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static List<String> validate(String password) {
        List<String> errors = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            errors.add("მინიმუმ " + MIN_LENGTH + " სიმბოლო");
        }
        if (password == null || password.chars().noneMatch(Character::isDigit)) {
            errors.add("მინიმუმ ერთი ციფრი");
        }
        if (password == null || password.chars().noneMatch(Character::isUpperCase)) {
            errors.add("მინიმუმ ერთი დიდი ასო");
        }
        if (password == null || password.chars().noneMatch(Character::isLowerCase)) {
            errors.add("მინიმუმ ერთი პატარა ასო");
        }
        return errors;
    }
}
