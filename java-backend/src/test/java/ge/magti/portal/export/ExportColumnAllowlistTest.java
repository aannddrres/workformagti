package ge.magti.portal.export;

import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.web.ExportController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export allowlist from {@code docs/ACCESS_CONTRACT_MATRIX_KA.md}, asserted.
 *
 * <p>Plan §8 requires a server-side whitelist that admits identity and
 * statistical fields and refuses raw audit / view / search / session /
 * security data; §9.1 requires a test that proves it for all three formats.
 * The risk this defends is not a developer deciding to export the audit log
 * -- it is a column arriving by accident, because someone added a field to
 * {@link ReadingExportRow} for one screen and the export picked it up. A
 * manager's export is personal data that leaves the building, so its shape
 * has to be a decision, never a side effect.
 *
 * <p>Two assertions, because either alone is escapable:
 *
 * <ol>
 *   <li><b>The headers are pinned</b>, per format. A new column fails here
 *       even if its source field is innocent.
 *   <li><b>The source row is pinned.</b> {@link ReadingExportRow} is the only
 *       shape all three readings exports read from, so a field that cannot
 *       exist there cannot reach any of them -- which covers the formats'
 *       row-building code without having to re-assert it three times.
 * </ol>
 */
class ExportColumnAllowlistTest {

    /**
     * PO-13's columns, identical in all three formats (owner, 2026-08-22;
     * implemented 2026-10-02). No employee or material ID.
     */
    private static final List<String> PO_13 = List.of(
            "თანამშრომელი", "დეპარტამენტი", "ჯგუფი", "მასალის სათაური", "მასალის ტიპი",
            "სტატუსი", "წაკითხვის დრო", "ვადა");

    /** Substrings that may never appear in an export column or in its source row (§8). */
    private static final List<String> FORBIDDEN = List.of(
            "audit", "viewlog", "view_log", "searchlog", "search_log", "session",
            "token", "password", "hash", "ipaddress", "ip_address", "useragent", "user_agent");

    private final ExportQueryService exportQueryService = mock(ExportQueryService.class);
    private final ExportJobWorker exportJobWorker = mock(ExportJobWorker.class);
    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final ExportController controller = new ExportController(
            exportQueryService,
            exportJobRepository,
            exportJobWorker,
            mock(AuditLogRepository.class),
            new PermissionChecker());

    ExportColumnAllowlistTest() {
        when(exportQueryService.eligibleReadingRows(any(), any(), any())).thenReturn(List.of());
        when(exportQueryService.departmentComplianceTotals(any())).thenReturn(new TreeMap<>());
        when(exportJobRepository.startLease(anyString(), anyString())).thenReturn(1);
    }

    private static User exporter() {
        User user = new User();
        user.setId(1L);
        user.setRole(Role.SYSTEM_ADMIN);
        user.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        return user;
    }

    /** The headers the controller handed to the job worker for the one call it just made. */
    private List<String> capturedHeaders() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> headers = ArgumentCaptor.forClass(List.class);
        verify(exportJobWorker).buildAndStore(anyString(), anyString(), headers.capture(), anyList(), anyString());
        return headers.getValue();
    }

    private static List<String> csvHeaderOf(ResponseEntity<?> response) {
        byte[] body = (byte[]) response.getBody();
        String csv = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        return Arrays.stream(csv.split("\r?\n", 2)[0].split(",")).map(c -> c.replace("\"", "").strip()).toList();
    }

    @Test
    void theCsvReadingsExportHasExactlyTheAllowedColumns() {
        assertEquals(PO_13, csvHeaderOf(controller.exportReadingsCsv(exporter(), null, null)));
    }

    @Test
    void theXlsxReadingsExportHasExactlyTheAllowedColumns() {
        controller.exportReadingsXlsx(exporter(), null, null);

        assertEquals(PO_13, capturedHeaders());
    }

    @Test
    void thePdfReadingsExportHasExactlyTheAllowedColumns() {
        controller.exportReadingsPdf(exporter(), null, null);

        assertEquals(PO_13, capturedHeaders());
    }

    @Test
    void theTeamStatsPdfHasExactlyTheAllowedColumns() {
        controller.exportTeamStatsPdf(exporter());

        assertEquals(
                List.of("დეპარტამენტი", "სულ მიკუთვნებული", "წაკითხული", "%"),
                capturedHeaders());
    }

    /**
     * The structural half: all three readings exports read one row shape, so
     * pinning it is what actually stops a forbidden value from reaching any
     * of them. A field added here for an unrelated screen fails the build
     * with the allowlist in the message, rather than appearing in the next
     * export a manager downloads.
     */
    @Test
    void theSourceRowCarriesExactlyTheAllowedFields() {
        List<String> fields = Arrays.stream(ReadingExportRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(
                List.of("userId", "userName", "department", "group", "itemType", "itemId", "itemTitle", "status",
                        "readAt", "dueDate"),
                fields,
                "ReadingExportRow feeds every readings export. Adding a field here widens all three files at "
                        + "once -- update docs/ACCESS_CONTRACT_MATRIX_KA.md's allowlist in the same commit, and "
                        + "check the field against the forbidden categories in plan §8.");
    }

    @Test
    void noExportColumnOrSourceFieldComesFromAForbiddenCategory() {
        controller.exportReadingsXlsx(exporter(), null, null);

        Function<String, String> normalise = s -> s.toLowerCase(Locale.ROOT).replace(" ", "");
        List<String> everything = new java.util.ArrayList<>(capturedHeaders().stream().map(normalise).toList());
        everything.addAll(csvHeaderOf(controller.exportReadingsCsv(exporter(), null, null)).stream().map(normalise).toList());
        everything.addAll(Arrays.stream(ReadingExportRow.class.getRecordComponents())
                .map(RecordComponent::getName).map(normalise).toList());

        for (String name : everything) {
            for (String forbidden : FORBIDDEN) {
                assertTrue(!name.contains(forbidden),
                        "\"" + name + "\" looks like raw " + forbidden + " data, which plan §8 forbids in any export");
            }
        }
    }
}
