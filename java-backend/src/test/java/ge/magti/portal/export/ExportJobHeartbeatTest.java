package ge.magti.portal.export;

import ge.magti.portal.repository.ExportJobRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportJobHeartbeatTest {

    @Test
    void lostLeaseLeavesTheLocalRegistryInsteadOfBeingRenewedForever() {
        ExportJobRepository jobs = mock(ExportJobRepository.class);
        ExportJobLeaseOwner owner = new ExportJobLeaseOwner();
        ExportJobHeartbeat heartbeat = new ExportJobHeartbeat(jobs, owner);
        when(jobs.renewLease("lost", owner.id())).thenReturn(0);

        heartbeat.track("lost");
        heartbeat.renew();
        heartbeat.renew();

        verify(jobs, times(1)).renewLease("lost", owner.id());
    }
}
