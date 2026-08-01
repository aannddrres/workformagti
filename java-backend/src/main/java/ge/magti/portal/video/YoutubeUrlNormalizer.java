package ge.magti.portal.video;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors routers/videos.py's normalize_youtube_url (:25-50) exactly --
 * same pattern list, same priority order (first match wins), same
 * fallback for a bare 11-character video ID, same pass-through for
 * anything that matches none of them.
 */
public final class YoutubeUrlNormalizer {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/embed/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/watch\\?(?:[^&]*&)*v=([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/v/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/vi/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtube(?:-nocookie)?\\.com/e/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern BARE_ID = Pattern.compile("^[a-zA-Z0-9_-]{11}$");

    private YoutubeUrlNormalizer() {
    }

    public static String normalize(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String trimmed = url.strip();

        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                return "https://www.youtube.com/embed/" + matcher.group(1) + "?rel=0";
            }
        }

        if (trimmed.length() == 11 && BARE_ID.matcher(trimmed).matches()) {
            return "https://www.youtube.com/embed/" + trimmed + "?rel=0";
        }

        return trimmed;
    }
}
