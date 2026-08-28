package ge.magti.portal.reminder;

import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/** Database-locked sweep operations, safe when more than one app replica runs. */
@Service
public class ReminderSweepService {
    private static final int SWEEP_BATCH_SIZE = 1_000;

    private final ReminderRepository reminderRepository;
    private final ReminderDeliveryWorker deliveryWorker;

    public ReminderSweepService(
            ReminderRepository reminderRepository,
            ReminderDeliveryWorker deliveryWorker) {
        this.reminderRepository = reminderRepository;
        this.deliveryWorker = deliveryWorker;
    }

    public int runOnce() {
        OffsetDateTime now = TbilisiTime.now();
        int delivered = 0;
        for (Long id : reminderRepository.findPendingReadingIdsInWindow(
                ReminderType.ASSIGNMENT, ReminderType.DUE_SOON, now, now.plusHours(24),
                PageRequest.of(0, SWEEP_BATCH_SIZE))) {
            delivered += deliveryWorker.deliverLocked(id, ReminderType.DUE_SOON);
        }
        for (Long id : reminderRepository.findPendingOverdueReadingIds(
                ReminderType.ASSIGNMENT, ReminderType.OVERDUE, now,
                PageRequest.of(0, SWEEP_BATCH_SIZE))) {
            delivered += deliveryWorker.deliverLocked(id, ReminderType.OVERDUE);
        }
        return delivered;
    }
}
