package ge.magti.portal.export;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
@Transactional
class AdminExportQueryServiceIntegrationTest {

    @Autowired
    private AdminExportQueryService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyInventoryQueryExecutesAgainstOracleWithMatchingRowShape() {
        for (AdminExportFamily family : AdminExportFamily.values()) {
            AdminExportDataset dataset = service.load(family, null, null);
            assertNotNull(dataset.rows());
            dataset.rows().forEach(row -> assertEquals(dataset.headers().size(), row.size(), family.name()));
        }
    }

    @Test
    void readEvidencePresentsLabelsWithoutChangingStoredCodes() {
        String title = "სინთეზური გაცნობა " + System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO article_read_receipts (article_id_snapshot, article_title_snapshot,
                    article_version, operator_name_snapshot, operator_email_snapshot,
                    operator_department_snapshot, read_at)
                VALUES (990000001, ?, 1, 'სინთეზური თანამშრომელი', 'fixture@magti.ge',
                    'სინთეზური ჯგუფი', CURRENT_TIMESTAMP)
                """, title);

        AdminExportDataset dataset = service.load(AdminExportFamily.READ_EVIDENCE, null, null);
        List<Object> row = dataset.rows().stream()
                .filter(candidate -> title.equals(candidate.get(8)))
                .findFirst().orElseThrow();
        assertEquals("სტატიის გაცნობის ჩანაწერი", row.get(0));
        assertEquals("სტატია", row.get(6));
        assertEquals("წაკითხულია", row.get(10));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM article_read_receipts WHERE article_title_snapshot = ?",
                Long.class, title) == 1);
    }
}
