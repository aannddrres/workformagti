package ge.magti.portal.repository;

/**
 * Scalar database projection used by the knowledge-score calculation.
 *
 * <p>The endpoint needs only these two counts; exposing the grouped attempt
 * rows would make heap use grow with every passed article version retained
 * for a user.
 */
public interface PassedQuizSummary {

    Number getArticlesPassed();

    Number getFirstTryPasses();
}
