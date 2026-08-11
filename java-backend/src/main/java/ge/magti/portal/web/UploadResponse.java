package ge.magti.portal.web;

/**
 * Mirrors routers/platform.py's {@code upload_file} return dict
 * {@code {"url": ..., "filename": ...}}.
 */
public record UploadResponse(String url, String filename) {
}
