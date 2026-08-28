package ge.magti.portal.compliance;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.RequiredReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Transaction boundary for a required-reading delete and its audit proof. */
@Service
public class RequiredReadingMutationService {

    private final RequiredReadingRepository requiredReadingRepository;
    private final MutationAuditService mutationAuditService;

    public RequiredReadingMutationService(
            RequiredReadingRepository requiredReadingRepository,
            MutationAuditService mutationAuditService) {
        this.requiredReadingRepository = requiredReadingRepository;
        this.mutationAuditService = mutationAuditService;
    }

    @Transactional
    public void delete(RequiredReading reading, User actor) {
        Map<String, Object> before = MutationAuditService.requiredReadingSnapshot(reading);
        requiredReadingRepository.delete(reading);
        requiredReadingRepository.flush();
        mutationAuditService.recordSuccess(
                actor, "DELETE_REQUIRED_READING", "required_reading", reading.getId(),
                reading.getItemTitleSnapshot(), before, null);
    }
}
