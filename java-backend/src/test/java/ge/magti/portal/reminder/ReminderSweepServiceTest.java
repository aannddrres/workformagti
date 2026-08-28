package ge.magti.portal.reminder;

import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.repository.ReminderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReminderSweepServiceTest {

    @Test
    void eachScheduledQueryUsesABoundedWorkBatch() {
        ReminderRepository repository = mock(ReminderRepository.class);
        ReminderDeliveryWorker worker = mock(ReminderDeliveryWorker.class);
        when(repository.findPendingReadingIdsInWindow(
                eq(ReminderType.ASSIGNMENT), eq(ReminderType.DUE_SOON), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(11L, 12L));
        when(repository.findPendingOverdueReadingIds(
                eq(ReminderType.ASSIGNMENT), eq(ReminderType.OVERDUE), any(), any(Pageable.class)))
                .thenReturn(List.of(13L));
        when(worker.deliverLocked(anyLong(), any(ReminderType.class))).thenReturn(1);

        int delivered = new ReminderSweepService(repository, worker).runOnce();

        assertEquals(3, delivered);
        verify(repository).findPendingReadingIdsInWindow(
                eq(ReminderType.ASSIGNMENT), eq(ReminderType.DUE_SOON), any(), any(),
                anyBoundedPage());
        verify(repository).findPendingOverdueReadingIds(
                eq(ReminderType.ASSIGNMENT), eq(ReminderType.OVERDUE), any(),
                anyBoundedPage());
    }

    private static Pageable anyBoundedPage() {
        return org.mockito.ArgumentMatchers.argThat(
                page -> page.getPageNumber() == 0 && page.getPageSize() == 1_000);
    }
}
