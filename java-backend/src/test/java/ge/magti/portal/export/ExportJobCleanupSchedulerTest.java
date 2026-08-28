package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportJobCleanupSchedulerTest {

    @Test
    void expiredJobsAreDrainedInBoundedBatches() {
        ExportJobRepository repository = mock(ExportJobRepository.class);
        ExpiredExportJobReference first = new ExpiredExportJobReference("first", null);
        ExpiredExportJobReference second = new ExpiredExportJobReference("second", null);
        when(repository.findExpiredReferences(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(first), List.of(second), List.of());

        new ExportJobCleanupScheduler(repository).sweepExpiredJobs();

        verify(repository).deleteExpiredByIds(List.of("first"));
        verify(repository).deleteExpiredByIds(List.of("second"));
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(3)).findExpiredReferences(anyDouble(), pages.capture());
        assertEquals(3, pages.getAllValues().size());
        assertTrue(pages.getAllValues().stream()
                .allMatch(page -> page.getPageNumber() == 0 && page.getPageSize() == 500));
    }
}
