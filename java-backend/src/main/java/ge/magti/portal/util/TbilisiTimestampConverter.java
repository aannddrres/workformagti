package ge.magti.portal.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Maps {@link OffsetDateTime} (always {@link TbilisiTime#OFFSET}, by
 * construction) to a plain Oracle {@code TIMESTAMP(6)} column with no zone
 * stored (naive Tbilisi wall-clock, no offset persisted). Without this
 * converter, Hibernate 6's
 * default mapping for {@code OffsetDateTime} is {@code TIMESTAMP WITH TIME
 * ZONE}, which is a different physical column type than what
 * db/migration creates and would fail schema validation at startup.
 *
 * <p>{@code autoApply = true}: every {@code OffsetDateTime} entity field
 * gets this automatically, since the migration doc counts 25 such columns
 * across the schema (docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1b) -- all
 * of them need the exact same treatment, not a one-off {@code @Convert}.
 */
@Converter(autoApply = true)
public class TbilisiTimestampConverter implements AttributeConverter<OffsetDateTime, LocalDateTime> {

    @Override
    public LocalDateTime convertToDatabaseColumn(OffsetDateTime attribute) {
        return attribute == null ? null : attribute.withOffsetSameInstant(TbilisiTime.OFFSET).toLocalDateTime();
    }

    @Override
    public OffsetDateTime convertToEntityAttribute(LocalDateTime dbData) {
        return dbData == null ? null : dbData.atOffset(TbilisiTime.OFFSET);
    }
}
