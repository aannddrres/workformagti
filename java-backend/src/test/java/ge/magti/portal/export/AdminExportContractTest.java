package ge.magti.portal.export;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminExportContractTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdminExportQueryService service = new AdminExportQueryService(jdbc);

    AdminExportContractTest() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    @Test
    void allSixFamiliesHaveExplicitGeorgianHeaderAllowlistsWithoutCredentials() {
        assertEquals(6, AdminExportFamily.values().length);
        List<String> forbidden = List.of(
                "password", "hashedpassword", "token", "session", "credential",
                "privatekey", "secret", "cookie", "authorization");

        for (AdminExportFamily family : AdminExportFamily.values()) {
            AdminExportDataset dataset = service.load(family, null, null);
            assertFalse(dataset.headers().isEmpty(), family.name());
            for (String header : dataset.headers()) {
                String normalized = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                for (String denied : forbidden) {
                    assertFalse(normalized.contains(denied), family + " exports forbidden field " + header);
                }
            }
        }
    }
}
