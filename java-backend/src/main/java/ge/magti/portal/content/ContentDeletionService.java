package ge.magti.portal.content;

import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.video.TagSyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The one place that clears the polymorphic references a deleted article,
 * news item or video leaves behind (audit BL-02, BL-10).
 *
 * <p>{@code required_readings}, {@code tags_mapping} and {@code favorites}
 * all address content by {@code (item_type, item_id)} with no foreign key --
 * a deliberate choice recorded in each table's own migration, since one
 * {@code item_id} column has to mean "article, news or video" depending on
 * {@code item_type}. No FK also means Oracle's cascade cannot clean any of
 * them; before this class, nothing did. A deleted article's mandatory
 * reading kept counting in the compliance denominator as an unreadable
 * "Item #482 / Content not available." forever (BL-02), and its tags and
 * favourites simply never went away (BL-10).
 *
 * <p>Previously this list was about to be repeated three times, once per
 * controller, and the audit found the copies were already drifting (only
 * articles and videos called {@code tagSyncService.sync}; nothing cleared
 * favorites anywhere). One method, called from all three delete endpoints,
 * so there is one list to keep correct.
 *
 * <p>{@code itemType} is a free string end to end (see {@link
 * ge.magti.portal.web.RequiredReadingRequest}, {@link
 * ge.magti.portal.web.FavoriteController}) -- calling this for a video,
 * which nothing today points a required reading at, just finds zero rows
 * and is a no-op. Uniform is simpler than three call sites each guessing
 * which of the three tables their own item type can appear in.
 */
@Service
public class ContentDeletionService {

    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final FavoriteRepository favoriteRepository;
    private final TagSyncService tagSyncService;

    public ContentDeletionService(
            RequiredReadingRepository requiredReadingRepository, ReadStatusRepository readStatusRepository,
            FavoriteRepository favoriteRepository, TagSyncService tagSyncService) {
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.favoriteRepository = favoriteRepository;
        this.tagSyncService = tagSyncService;
    }

    /**
     * Call this BEFORE deleting the item row itself -- it does not touch the
     * item, only what points at it, so ordering relative to the item's own
     * delete does not matter for THIS method, but the caller still owns one
     * transaction across both (each delete endpoint is {@code @Transactional}).
     */
    @Transactional
    public void deletePolymorphicReferences(String itemType, Long itemId) {
        List<RequiredReading> readings = requiredReadingRepository.findByItemTypeAndItemId(itemType, itemId);
        if (!readings.isEmpty()) {
            // Order matters: read_statuses.required_reading_id FKs to
            // required_readings, so it must be empty first (BL-02's own
            // ordering note).
            List<Long> readingIds = readings.stream().map(RequiredReading::getId).toList();
            readStatusRepository.deleteByRequiredReadingIdIn(readingIds);
            requiredReadingRepository.deleteAllInBatch(readings);
        }

        // Empty tags string is TagSyncService's existing "remove every
        // mapping for this item" call -- the same one create/update already
        // use, just never on delete.
        tagSyncService.sync(itemType, itemId, "");

        favoriteRepository.deleteByItemTypeAndItemId(itemType, itemId);
    }
}
