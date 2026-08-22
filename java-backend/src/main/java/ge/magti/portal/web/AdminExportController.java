package ge.magti.portal.web;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.AdminExportDataset;
import ge.magti.portal.export.AdminExportFamily;
import ge.magti.portal.export.AdminExportJobService;
import ge.magti.portal.export.AdminExportQueryService;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportTooLargeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** PO-14: separate, audited SYSTEM_ADMIN-only exports for each data family. */
@RestController
public class AdminExportController {

    private final AdminExportQueryService queryService;
    private final AdminExportJobService jobService;
    private final ExportJobWorker jobWorker;

    public AdminExportController(
            AdminExportQueryService queryService,
            AdminExportJobService jobService,
            ExportJobWorker jobWorker) {
        this.queryService = queryService;
        this.jobService = jobService;
        this.jobWorker = jobWorker;
    }

    @PostMapping("/api/admin/exports/audit-ledger")
    public ResponseEntity<?> auditLedger(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.AUDIT_LEDGER, from, through, user, requireSystemAdmin(user));
    }

    @PostMapping("/api/admin/exports/read-evidence")
    public ResponseEntity<?> readEvidence(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.READ_EVIDENCE, from, through, user, requireSystemAdmin(user));
    }

    @PostMapping("/api/admin/exports/article-views")
    public ResponseEntity<?> articleViews(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.ARTICLE_VIEWS, from, through, user, requireSystemAdmin(user));
    }

    @PostMapping("/api/admin/exports/search-history")
    public ResponseEntity<?> searchHistory(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.SEARCH_HISTORY, from, through, user, requireSystemAdmin(user));
    }

    @PostMapping("/api/admin/exports/quiz-attempts")
    public ResponseEntity<?> quizAttempts(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.QUIZ_ATTEMPTS, from, through, user, requireSystemAdmin(user));
    }

    @PostMapping("/api/admin/exports/change-events")
    public ResponseEntity<?> changeEvents(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate through,
            @AuthenticationPrincipal User user) {
        return submit(AdminExportFamily.CHANGE_EVENTS, from, through, user, requireSystemAdmin(user));
    }

    private ResponseEntity<?> submit(
            AdminExportFamily family, LocalDate from, LocalDate through, User user,
            ResponseEntity<Map<String, String>> denial) {
        if (denial != null) return denial;
        if (from != null && through != null && from.isAfter(through)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "detail", "საწყისი თარიღი საბოლოო თარიღზე გვიან ვერ იქნება"));
        }
        try {
            AdminExportDataset dataset = queryService.load(family, from, through);
            String jobId = jobService.register(user, family, from, through, dataset.rows().size());
            jobWorker.buildAdminAndStore(
                    jobId, family.title(), dataset.headers(), dataset.rows(), family.filenamePrefix());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ExportJobResponse(jobId));
        } catch (ExportTooLargeException e) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "detail", String.format(
                            "ექსპორტი ძალიან დიდია (%d ჩანაწერი, ზღვარი %d). გამოიყენეთ თარიღის ფილტრი.",
                            e.getRowCount(), e.getMaxRows())));
        }
    }

    private static ResponseEntity<Map<String, String>> requireSystemAdmin(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (!user.isActive() || user.getRole() != Role.SYSTEM_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: საჭიროა სისტემური ადმინისტრატორი"));
        }
        return null;
    }
}
