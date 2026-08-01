package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.quiz.KnowledgeScoreResult;

/** Mirrors schemas.py's KnowledgeScoreResponse. */
public record KnowledgeScoreResponse(
        @JsonProperty("user_id") Long userId,
        int score,
        @JsonProperty("articles_passed") int articlesPassed,
        @JsonProperty("first_try_passes") int firstTryPasses
) {
    public static KnowledgeScoreResponse from(Long userId, KnowledgeScoreResult result) {
        return new KnowledgeScoreResponse(userId, result.score(), result.articlesPassed(), result.firstTryPasses());
    }
}
