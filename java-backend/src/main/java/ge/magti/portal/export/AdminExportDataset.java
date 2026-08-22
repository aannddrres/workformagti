package ge.magti.portal.export;

import java.util.List;

public record AdminExportDataset(
        AdminExportFamily family,
        List<String> headers,
        List<List<Object>> rows) {
}
