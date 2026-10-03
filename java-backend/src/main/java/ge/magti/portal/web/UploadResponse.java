package ge.magti.portal.web;

/**
 * The upload response, {@code {"url": ..., "filename": ...}}.
 */
public record UploadResponse(String url, String filename) {
}
