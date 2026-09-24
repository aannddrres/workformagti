package ge.magti.portal.compliance;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleReadReceipt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the first acknowledgement across both HTTP routes and JVM instances. */
@Service
public class ReadingAcknowledgementService {

    private final UserRepository users;
    private final ArticleReadReceiptRepository receipts;
    private final ReadStatusRepository statuses;
    private final MutationAuditService audit;

    public ReadingAcknowledgementService(UserRepository users, ArticleReadReceiptRepository receipts,
            ReadStatusRepository statuses, MutationAuditService audit) {
        this.users = users;
        this.receipts = receipts;
        this.statuses = statuses;
        this.audit = audit;
    }

    @Transactional
    public ArticleReadReceipt acknowledgeArticle(Article article, User actor, List<RequiredReading> covering) {
        lockActor(actor);
        OffsetDateTime now = TbilisiTime.now();
        boolean receiptWasPresent = receipts.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                article.getId(), article.getVersion(), actor.getId()).isPresent();
        ArticleReadReceipt receipt = receiptFor(article, actor, now, "ACKNOWLEDGE_ARTICLE_READ");
        boolean changed = !receiptWasPresent;
        for (RequiredReading reading : covering) {
            changed |= markStatus(reading, actor, now, "ACKNOWLEDGE_ARTICLE_READ");
        }
        if (!changed) {
            audit.recordResult(actor, "ACKNOWLEDGE_ARTICLE_READ", "article_read_receipt",
                    receipt.getId(), receipt.getArticleTitleSnapshot(), "ALREADY_ACKNOWLEDGED", null,
                    receiptSnapshot(receipt), receiptSnapshot(receipt), null, null);
        }
        return receipt;
    }

    @Transactional
    public ReadStatus acknowledgeRequiredReading(RequiredReading reading, Article article, User actor) {
        lockActor(actor);
        OffsetDateTime now = TbilisiTime.now();
        ReadStatus status = statuses.findByUserIdAndRequiredReadingId(actor.getId(), reading.getId())
                .orElse(null);
        boolean statusWasRead = status != null && "read".equals(status.getStatus());
        if (!statusWasRead) {
            markStatus(reading, actor, now, "MARK_REQUIRED_READING_READ");
        }
        boolean receiptWasPresent = article == null || receipts
                .findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                        article.getId(), article.getVersion(), actor.getId()).isPresent();
        if (article != null && !receiptWasPresent) {
            receiptFor(article, actor, now, "MARK_REQUIRED_READING_READ");
        }
        status = statuses.findByUserIdAndRequiredReadingId(actor.getId(), reading.getId()).orElseThrow();
        if (statusWasRead && receiptWasPresent) {
            audit.recordResult(actor, "MARK_REQUIRED_READING_READ", "read_status", status.getId(),
                    reading.getItemTitleSnapshot(), "ALREADY_ACKNOWLEDGED", null,
                    MutationAuditService.readStatusSnapshot(status),
                    MutationAuditService.readStatusSnapshot(status), null, null);
        }
        return status;
    }

    private void lockActor(User actor) {
        users.findByIdForUpdate(actor.getId()).orElseThrow();
    }

    private ArticleReadReceipt receiptFor(Article article, User actor, OffsetDateTime now, String action) {
        ArticleReadReceipt existing = receipts.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                article.getId(), article.getVersion(), actor.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        ArticleReadReceipt receipt = new ArticleReadReceipt();
        receipt.setArticleId(article.getId());
        receipt.setArticleIdSnapshot(article.getId());
        receipt.setArticleTitleSnapshot(article.getTitle());
        receipt.setArticleVersion(article.getVersion());
        receipt.setOperatorId(actor.getId());
        receipt.setOperatorNameSnapshot(actor.getName());
        receipt.setOperatorEmailSnapshot(actor.getEmail());
        receipt.setOperatorDepartmentSnapshot(actor.getDepartment());
        receipt.setReadAt(now);
        receipt = receipts.saveAndFlush(receipt);
        audit.recordSuccess(actor, action, "article_read_receipt", receipt.getId(),
                receipt.getArticleTitleSnapshot(), null, receiptSnapshot(receipt));
        return receipt;
    }

    private boolean markStatus(RequiredReading reading, User actor, OffsetDateTime now, String action) {
        ReadStatus status = statuses.findByUserIdAndRequiredReadingId(actor.getId(), reading.getId())
                .orElseGet(() -> {
                    ReadStatus fresh = new ReadStatus();
                    fresh.setUserId(actor.getId());
                    fresh.setRequiredReadingId(reading.getId());
                    return fresh;
                });
        if ("read".equals(status.getStatus())) {
            return false;
        }
        Map<String, Object> before = status.getId() == null
                ? null : MutationAuditService.readStatusSnapshot(status);
        status.setStatus("read");
        status.setReadAt(now);
        status.setOperatorDepartmentSnapshot(actor.getDepartment());
        status = statuses.saveAndFlush(status);
        audit.recordSuccess(actor, action, "read_status", status.getId(), reading.getItemTitleSnapshot(),
                before, MutationAuditService.readStatusSnapshot(status));
        return true;
    }

    private static Map<String, Object> receiptSnapshot(ArticleReadReceipt receipt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("article_id", receipt.getArticleIdSnapshot());
        snapshot.put("article_version", receipt.getArticleVersion());
        snapshot.put("article_title", receipt.getArticleTitleSnapshot());
        snapshot.put("operator_id", receipt.getOperatorId());
        snapshot.put("operator_name", receipt.getOperatorNameSnapshot());
        snapshot.put("operator_email", receipt.getOperatorEmailSnapshot());
        snapshot.put("operator_department", receipt.getOperatorDepartmentSnapshot());
        snapshot.put("read_at", receipt.getReadAt().toString());
        return snapshot;
    }
}
