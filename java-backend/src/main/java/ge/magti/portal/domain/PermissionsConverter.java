package ge.magti.portal.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The nullable {@code users.permissions} column -- a JSON array of strings, e.g.
 * {@code ["articles.publish", "users.manage"]}. Not {@code autoApply}: only
 * {@link User#getPermissions()} means "JSON-encoded string set" in this
 * codebase, and auto-applying to every future {@code Set<String>} field
 * would be wrong by default rather than right by default.
 *
 * <p>Null-handling is deliberately asymmetric: the DB column stays
 * nullable (matching the source column), but {@link User#getPermissions()}
 * has never returned null (it defaults to an empty set) and {@link
 * User#hasPermission} relies on that -- so a NULL column reads back as an
 * empty set here rather than null, preserving that existing invariant.
 */
@Converter
public class PermissionsConverter implements AttributeConverter<Set<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashSet<String>> SET_TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Set<String> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize permissions", e);
        }
    }

    @Override
    public Set<String> convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return new LinkedHashSet<>();
        }
        try {
            return MAPPER.readValue(dbData, SET_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse permissions", e);
        }
    }
}
