package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.UserNote;

public record UserNoteResponse(
        Long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("article_id") Long articleId,
        String content
) {
    public static UserNoteResponse from(UserNote note) {
        return new UserNoteResponse(note.getId(), note.getUserId(), note.getArticleId(), note.getContent());
    }
}
