package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportJobWorkerTest {

    @Test
    void buildFailureUsesTheTransactionalLifecycleAndStopsHeartbeat() {
        ExportJobRepository repository = mock(ExportJobRepository.class);
        ExportJobLifecycle lifecycle = mock(ExportJobLifecycle.class);
        ExportJobHeartbeat heartbeat = mock(ExportJobHeartbeat.class);
        when(repository.existsById("job-1")).thenReturn(true);

        ExportJobWorker worker = new ExportJobWorker(repository, lifecycle, heartbeat);
        worker.buildAndStore("job-1", "title", List.of("header"), null, "pdf");

        verify(lifecycle).failBuild("job-1", "pdf");
        verify(heartbeat).finished("job-1");
    }
}
