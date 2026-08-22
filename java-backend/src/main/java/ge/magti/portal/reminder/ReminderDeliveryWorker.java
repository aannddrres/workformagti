package ge.magti.portal.reminder;

import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.repository.RequiredReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
