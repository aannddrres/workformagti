package ge.magti.portal.repository;

import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.ReminderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    Page<Reminder> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    List<Reminder> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId);

    Optional<Reminder> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    boolean existsByRequiredReadingIdAndRecipientUserIdAndType(
            Long requiredReadingId, Long recipientUserId, ReminderType type);

    List<Reminder> findByRequiredReadingIdAndTypeOrderByIdAsc(
            Long requiredReadingId, ReminderType type);

    Optional<Reminder> findFirstByRecipientUserIdAndTypeOrderByCreatedAtDesc(
            Long recipientUserId, ReminderType type);

    /**
     * Only readings with an original assignment recipient who is still unread
     * and has not received this delivery type are swept. That makes completed
     * readings disappear from every later scan without a separate sweep-state
     * table and without re-evaluating historical audience membership.
     */
    @Query("SELECT DISTINCT rr.id FROM RequiredReading rr, Reminder seed "
            + "WHERE seed.requiredReadingId = rr.id AND seed.type = :seedType "
            + "AND rr.dueDate > :after AND rr.dueDate <= :through "
            + "AND NOT EXISTS (SELECT delivered.id FROM Reminder delivered "
            + "WHERE delivered.requiredReadingId = rr.id "
            + "AND delivered.recipientUserId = seed.recipientUserId "
            + "AND delivered.type = :deliveryType) "
            + "AND NOT EXISTS (SELECT status.id FROM ReadStatus status "
            + "WHERE status.requiredReadingId = rr.id "
            + "AND status.userId = seed.recipientUserId AND status.status = 'read')")
    List<Long> findPendingReadingIdsInWindow(
            @Param("seedType") ReminderType seedType,
            @Param("deliveryType") ReminderType deliveryType,
            @Param("after") OffsetDateTime after,
            @Param("through") OffsetDateTime through);

    @Query("SELECT DISTINCT rr.id FROM RequiredReading rr, Reminder seed "
            + "WHERE seed.requiredReadingId = rr.id AND seed.type = :seedType "
            + "AND rr.dueDate <= :through "
            + "AND NOT EXISTS (SELECT delivered.id FROM Reminder delivered "
            + "WHERE delivered.requiredReadingId = rr.id "
            + "AND delivered.recipientUserId = seed.recipientUserId "
            + "AND delivered.type = :deliveryType) "
            + "AND NOT EXISTS (SELECT status.id FROM ReadStatus status "
            + "WHERE status.requiredReadingId = rr.id "
            + "AND status.userId = seed.recipientUserId AND status.status = 'read')")
    List<Long> findPendingOverdueReadingIds(
            @Param("seedType") ReminderType seedType,
            @Param("deliveryType") ReminderType deliveryType,
            @Param("through") OffsetDateTime through);
}
