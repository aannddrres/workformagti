package ge.magti.portal.reminder;

import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.repository.RequiredReadingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** One short transaction and one cross-replica row lock per reading sweep. */
@Service
public class ReminderDeliveryWorker {

    private final RequiredReadingRepository readingRepository;
    private final ReminderService reminderService;

    public ReminderDeliveryWorker(
            RequiredReadingRepository readingRepository, ReminderService reminderService) {
        this.readingRepository = readingRepository;
        this.reminderService = reminderService;
    }

    @Transactional
    public int deliverLocked(long readingId, ReminderType type) {
        RequiredReading reading = readingRepository.findByIdForUpdate(readingId).orElse(null);
        return reading == null ? 0 : reminderService.deliverScheduled(reading, type);
    }

    /** PO-40: readings made mandatory before publication whose assignment has not gone out. */
    @Transactional(readOnly = true)
    public List<Long> awaitingAssignment(int batchSize) {
        return readingRepository.findIdsAwaitingAssignmentDelivery(PageRequest.of(0, batchSize));
    }

    /**
     * Delivers the assignment once the reading is in force, under the same row
     * lock as the other sweeps so two replicas cannot both send it; a no-op
     * until then, and after.
     */
    @Transactional
    public int deliverAssignmentLocked(long readingId) {
        RequiredReading reading = readingRepository.findByIdForUpdate(readingId).orElse(null);
        return reading == null || reading.getAssignmentDeliveredAt() != null
                ? 0 : reminderService.deliverAssignment(reading, null);
    }
}
