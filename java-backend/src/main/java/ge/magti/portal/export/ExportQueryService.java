package ge.magti.portal.export;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * DB-query half of the readings exports (routers/exports.py's
 * export_readings/export_readings_xlsx/export_readings_pdf, lines
 * 93-98,144-150,264-270). Batch-fetches User/RequiredReading rather than a
 * multi-entity JPQL join, same N+1-avoidance-via-map idiom used throughout
 * this port (e.g. MessagingController).
 *
 * <p><b>User-approved fix, 2026-08-06:</b> Python's CSV export scopes to
 * {@code compute_compliance()}'s eligible user ids (active, non-management)
 * but the xlsx/pdf exports never did -- a live gap where the same personal
 * data got less protection depending on file format. This service is the
 * single query path for all 3 formats now, so that gap can't reopen.
 */
@Service
public class ExportQueryService {

    private final ComplianceQueryService complianceQueryService;
    private final ReadStatusRepository readStatusRepository;
    private final UserRepository userRepository;
    private final RequiredReadingRepository requiredReadingRepository;

    public ExportQueryService(
            ComplianceQueryService complianceQueryService, ReadStatusRepository readStatusRepository,
            UserRepository userRepository, RequiredReadingRepository requiredReadingRepository) {
        this.complianceQueryService = complianceQueryService;
        this.readStatusRepository = readStatusRepository;
        this.userRepository = userRepository;
        this.requiredReadingRepository = requiredReadingRepository;
    }

    /**
     * Every ReadStatus row for an eligible (active, non-management) user,
     * flattened with its User/RequiredReading fields. Throws {@link
     * ExportTooLargeException} past {@link ExportSizeGuard#MAX_ROWS} --
     * mirrors Python calling {@code _guard_export_size} synchronously in
     * the request handler, before any background job is enqueued.
     */
    public List<ReadingExportRow> eligibleReadingRows() {
        List<Long> eligibleUserIds = complianceQueryService.computeCompliance().stream()
                .map(record -> record.user().getId())
                .toList();
        if (eligibleUserIds.isEmpty()) {
            return List.of();
        }

        List<ReadStatus> statuses = readStatusRepository.findByUserIdIn(eligibleUserIds);
        ExportSizeGuard.checkSize(statuses.size());

        Map<Long, User> usersById = userRepository.findAllById(eligibleUserIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<Long> readingIds = statuses.stream().map(ReadStatus::getRequiredReadingId).distinct().toList();
        Map<Long, RequiredReading> readingsById = requiredReadingRepository.findAllById(readingIds).stream()
                .collect(Collectors.toMap(RequiredReading::getId, r -> r));

        List<ReadingExportRow> rows = new ArrayList<>();
        for (ReadStatus status : statuses) {
            User user = usersById.get(status.getUserId());
            RequiredReading reading = readingsById.get(status.getRequiredReadingId());
            if (user == null || reading == null) {
                continue;
            }
            rows.add(new ReadingExportRow(
                    status.getUserId(), user.getName(), user.getDepartment(),
                    reading.getItemType(), reading.getItemId(), status.getStatus(),
                    status.getReadAt(), reading.getDueDate()));
        }
        return rows;
    }

    /**
     * Mirrors export_team_stats_pdf's department aggregation
     * (routers/exports.py:305-309): {@code department -> {totalRequired,
     * totalRead}}, alphabetically sorted (Python's {@code sorted(by_dept
     * .items())}) so the caller doesn't have to.
     */
    public SortedMap<String, int[]> departmentComplianceTotals() {
        SortedMap<String, int[]> byDept = new TreeMap<>();
        for (var record : complianceQueryService.computeCompliance()) {
            String department = record.user().getDepartment();
            String key = (department == null || department.isBlank()) ? "—" : department;
            int[] bucket = byDept.computeIfAbsent(key, k -> new int[2]);
            bucket[0] += record.progress().requiredCount();
            bucket[1] += record.progress().readCount();
        }
        return byDept;
    }
}
