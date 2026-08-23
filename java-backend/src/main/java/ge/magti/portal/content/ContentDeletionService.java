package ge.magti.portal.content;

import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.video.TagSyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears only non-evidence polymorphic references during verified payload
 * purge.
 *
 * <p>Tags and favourites are presentation data and may disappear with the
 * payload. Required readings, read statuses, quiz attempts, read receipts,
 * view logs and audit rows are deliberately absent: R5 classifies them as
 * evidence that must outlive the content row. V42 adds the missing title/id
 * snapshots that keep those surviving rows intelligible.
 */
@Service
public class ContentDeletionService {

    private final FavoriteRepository favoriteRepository;
    private final TagSyncService tagSyncService;

    public ContentDeletionService(FavoriteRepository favoriteRepository, TagSyncService tagSyncService) {
        this.favoriteRepository = favoriteRepository;
        this.tagSyncService = tagSyncService;
    }

    @Transactional
    public void purgeNonEvidenceReferences(String itemType, Long itemId) {
        tagSyncService.sync(itemType, itemId, "");

        favoriteRepository.deleteByItemTypeAndItemId(itemType, itemId);
    }
}
