package ge.magti.portal.web;

import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.CsvExportBuilder;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.export.ExportTooLargeException;
import ge.magti.portal.export.ReadingExportRow;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

/**
 * Mirrors routers/exports.py's 6 endpoints: the synchronous compliance CSV,
 * 3 async xlsx/pdf builds (Excel via Apache POI, PDF via PDFBox -- both
 * Apache-2.0, user-confirmed choice, 2026-08-06), and the job status/
 * download pair.
 *
 * <p><b>Not ported: the "library not installed" 503 checks</b>
 * (routers/exports.py:132-139,253-259,294-297) -- those exist in Python
 * because openpyxl/reportlab are optional runtime pip installs; POI/PDFBox
 * are compile-time Maven dependencies, always present once this module
 * builds, so that whole degradation path has nothing to port. The one PDF
 * failure mode that can still happen -- no Georgian-capable font found --
 * surfaces as the async job's status flipping to {@code failed}, not a
 * request-time 503 (see {@link ExportJobWorker}).
 *
 * <p><b>User-approved fix, 2026-08-06:</b> {@link ExportQueryService}
 * scopes all 3 readings-export formats to eligible users uniformly, closing
 * a gap where xlsx/pdf (unlike the CSV) never filtered out managers/admins/
 * inactive users from an admin-only personal-data export.
 */
@RestController
public class ExportController {

    private final ExportQueryService exportQueryService;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobWorker exportJobWorker;
    private final AuditLogRepository auditLogRepository;
    private final PermissionChecker permissionChecker;

    public ExportController(
            ExportQueryService exportQueryService, ExportJobRepository exportJobRepository,
            ExportJobWorker exportJobWorker, AuditLogRepository auditLogRepository,
            PermissionChecker permissionChecker) {
        this.exportQueryService = exportQueryService;
        this.exportJobRepository = exportJobRepository;
        this.exportJobWorker = exportJobWorker;
        this.auditLogRepository = auditLogRepository;
        this.permissionChecker = permissionChecker;
    }

    /** Port of export_readings (routers/exports.py:65-117). */
    @GetMapping("/api/export/readings")
    public ResponseEntity<?> exportReadingsCsv(@AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        writeAudit(admin.getId(), "EXPORT", "readings");

        List<ReadingExportRow> rows;
        try {
            rows = exportQueryService.eligibleReadingRows();
        } catch (ExportTooLargeException e) {
            return tooLargeResponse(e);
        }

        List<String> headers = List.of("User ID", "User Name", "Item Type", "Item ID", "Status", "Read At");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<Object>> tableRows = rows.stream()
                .map(r -> List.<Object>of(
                        r.userId(), r.userName(), r.itemType(), r.itemId(), r.status(),
                        r.readAt() != null ? r.readAt().format(fmt) : "N/A"))
                .toList();
        String csv = CsvExportBuilder.build(headers, tableRows);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=readings_export.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Port of export_readings_xlsx (routers/exports.py:122-163). */
    @GetMapping("/api/export/readings.xlsx")
    public ResponseEntity<?> exportReadingsXlsx(@AuthenticationPrincipal User admin) {
        ResponseEntity<Map<String, String>> denial = requireSystemAdmin(admin);
        if (denial != null) {
            return denial;
        }
        writeAudit(admin.getId(), "EXPORT_XLSX", "readings");

        List<ReadingExportRow> rows;
        try {
            rows = exportQueryService.eligibleReadingRows();
        } catch (ExportTooLargeException e) {
            return tooLargeResponse(e);
        }

        List<String> headers = List.of(
                "თანამშრომელი", "დეპარტამენტი", "მასალის ტიპი", "მასალის ID", "სტატუსი", "წაკითხვის თარიღი", "ვადა");
        List<List<Object>> tableRows = readingRowsForSpreadsheet(rows);

        String jobId = enqueueJob(tableRows, headers, "Compliance", "xlsx");
        return ResponseEntity.ok(new ExportJobResponse(jobId));
    }

    /** Port of export_readings_pdf (routers/exports.py:246-284). */
    @GetMapping("/api/export/readings.pdf")
    public ResponseEntity<?> exportReadingsPdf(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireReportsExport(user);
        if (denial != null) {
            return denial;
        }
        writeAudit(user.getId(), "EXPORT_PDF", "readings");

        List<ReadingExportRow> rows;
        try {
            rows = exportQueryService.eligibleReadingRows();
        } catch (ExportTooLargeException e) {
            return tooLargeResponse(e);
        }

        List<String> headers = List.of("თანამშრომელი", "დეპარტამენტი", "ტიპი", "ID", "სტატუსი", "წაკითხვა", "ვადა");
        List<List<Object>> tableRows = readingRowsForSpreadsheet(rows);

        String jobId = enqueueJob(tableRows, headers, "სავალდებულოდ გასაცნობი სტატუსი", "pdf");
        return ResponseEntity.ok(new ExportJobResponse(jobId));
    }

    /** Port of export_team_stats_pdf (routers/exports.py:287-317). */
    @GetMapping("/api/export/team-stats.pdf")
    public ResponseEntity<?> exportTeamStatsPdf(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireReportsExport(user);
        if (denial != null) {
            return denial;
        }
        writeAudit(user.getId(), "EXPORT_PDF", "team_stats");

        SortedMap<String, int[]> byDept = exportQueryService.departmentComplianceTotals();
        List<String> headers = List.of("დეპარტამენტი", "სულ მიკუთვნებული", "წაკითხული", "%");
        List<List<Object>> tableRows = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : byDept.entrySet()) {
            int total = entry.getValue()[0];
            int read = entry.getValue()[1];
            tableRows.add(List.of(entry.getKey(), String.valueOf(total), String.valueOf(read), formatPercent(read, total)));
        }

        String jobId = enqueueJob(tableRows, headers, "გუნდის სტატისტიკა — წაკითხვის პროცენტი", "pdf");
        return ResponseEntity.ok(new ExportJobResponse(jobId));
    }

    /** Port of get_export_status (routers/exports.py:421-429). */
    @GetMapping("/api/export/status/{jobId}")
    public ResponseEntity<?> getExportStatus(@PathVariable("jobId") String jobId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireReportsExport(user);
        if (denial != null) {
            return denial;
        }
        Optional<ExportJob> job = exportJobRepository.findById(jobId);
        if (job.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "საექსპორტო დავალება ვერ მოიძებნა"));
        }
        return ResponseEntity.ok(new ExportStatusResponse(jobId, job.get().getStatus()));
    }

    /** Port of download_export (routers/exports.py:432-447). */
    @GetMapping("/api/export/download/{jobId}")
    public ResponseEntity<?> downloadExport(@PathVariable("jobId") String jobId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireReportsExport(user);
        if (denial != null) {
            return denial;
        }
        Optional<ExportJob> jobOpt = exportJobRepository.findById(jobId);
        if (jobOpt.isEmpty() || !"completed".equals(jobOpt.get().getStatus()) || jobOpt.get().getPath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ექსპორტი ჯერ არ არის მზად"));
        }
        Path path = Path.of(jobOpt.get().getPath());
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ექსპორტი ჯერ არ არის მზად"));
        }
        cleanupExport(jobId, path);

        String filename = path.getFileName().toString();
        MediaType mediaType = filename.endsWith(".xlsx")
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.APPLICATION_PDF;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(mediaType)
                .body(data);
    }

    private List<List<Object>> readingRowsForSpreadsheet(List<ReadingExportRow> rows) {
        DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        DateTimeFormatter dFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        return rows.stream()
                .map(r -> List.<Object>of(
                        r.userName(),
                        r.department() == null ? "" : r.department(),
                        r.itemType() == null ? "" : r.itemType(),
                        String.valueOf(r.itemId()),
                        r.status(),
                        r.readAt() != null ? r.readAt().format(dtFmt) : "",
                        r.dueDate() != null ? r.dueDate().format(dFmt) : ""))
                .toList();
    }

    private String enqueueJob(List<List<Object>> rows, List<String> headers, String title, String exportType) {
        String jobId = UUID.randomUUID().toString();
        ExportJob job = new ExportJob();
        job.setId(jobId);
        job.setStatus("processing");
        job.setPath(null);
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        exportJobRepository.saveAndFlush(job);
        exportJobWorker.buildAndStore(jobId, title, headers, rows, exportType);
        return jobId;
    }

    private void cleanupExport(String jobId, Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort, matches Python's _cleanup_export (routers/exports.py:406-412).
        }
        exportJobRepository.deleteById(jobId);
    }

    private void writeAudit(Long adminId, String action, String itemType) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(adminId);
        audit.setAction(action);
        audit.setItemType(itemType);
        audit.setItemId(0L);
        audit.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(audit);
    }

    private static String formatPercent(int read, int total) {
        if (total == 0) {
            return "0.0%";
        }
        BigDecimal pct = BigDecimal.valueOf(100.0 * read / total).setScale(1, RoundingMode.HALF_EVEN);
        return pct.toPlainString() + "%";
    }

    private static ResponseEntity<Map<String, String>> tooLargeResponse(ExportTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", String.format(
                        "ექსპორტი ძალიან დიდია (%d ჩანაწერი, ზღვარი %d). დააზუსტეთ ფილტრი და სცადეთ თავიდან.",
                        e.getRowCount(), e.getMaxRows())));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireReportsExport(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.REPORTS_EXPORT)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
