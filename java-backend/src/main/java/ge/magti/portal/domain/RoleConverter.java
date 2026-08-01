package ge.magti.portal.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link Role#value()} ("operator", "admin", ...), not
 * {@link Role#name()} ("OPERATOR", "SYSTEM_ADMIN"). Getting this backwards
 * would silently write "SYSTEM_ADMIN" into the role column instead of
 * "admin" -- see {@link Role}'s own javadoc on why that string in particular
 * must never change.
 */
@Converter(autoApply = true)
public class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Role convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Role.fromValue(dbData);
    }
}
