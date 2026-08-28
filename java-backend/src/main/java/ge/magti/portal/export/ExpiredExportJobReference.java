package ge.magti.portal.export;

/** BLOB-free cleanup row for an expired export job. */
public record ExpiredExportJobReference(String id, String path) {
}
