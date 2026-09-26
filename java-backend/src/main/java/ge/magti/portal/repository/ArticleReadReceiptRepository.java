package ge.magti.portal.repository;

import ge.magti.portal.domain.ArticleReadReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ArticleReadReceiptRepository extends JpaRepository<ArticleReadReceipt, Long> {

    /**
     * BL-12: filters on {@code article_id_snapshot}, not the foreign key.
     * Identical results while the article exists; the difference is that a
     * receipt for a deleted article stays findable instead of being
     * retained and unreachable.
     */
    Optional<ArticleReadReceipt> findByArticleIdSnapshotAndArticleVersionAndOperatorId(
            Long articleIdSnapshot, int articleVersion, Long operatorId);

    List<ArticleReadReceipt> findByArticleIdSnapshotAndArticleVersion(
            Long articleIdSnapshot, int articleVersion, Pageable pageable);

}
