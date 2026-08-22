package ge.magti.portal.export;

import ge.magti.portal.RequiresOracle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresOracle
@SpringBootTest
@Transactional
class AdminExportQueryServiceIntegrationTest {

    @Autowired
    private AdminExportQueryService service;

    @Test
    void everyInventoryQueryExecutesAgainstOracleWithMatchingRowShape() {
        for (AdminExportFamily family : AdminExportFamily.values()) {
            AdminExportDataset dataset = service.load(family, null, null);
            assertNotNull(dataset.rows());
            dataset.rows().forEach(row -> assertEquals(dataset.headers().size(), row.size(), family.name()));
        }
    }
}
