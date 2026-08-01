package ge.magti.portal.quiz;

/** Mirrors _compute_knowledge_score's return dict (routers/articles.py:877-892). */
public record KnowledgeScoreResult(int score, int articlesPassed, int firstTryPasses) {
}
