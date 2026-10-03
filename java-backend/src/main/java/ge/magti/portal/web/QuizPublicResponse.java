package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record QuizPublicResponse(
        @JsonProperty("article_id") Long articleId,
        @JsonProperty("article_version") int articleVersion,
        List<QuizQuestionPublicDto> questions
) {
}
