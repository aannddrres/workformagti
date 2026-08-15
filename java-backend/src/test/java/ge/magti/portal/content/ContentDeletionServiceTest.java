package ge.magti.portal.content;

import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.video.TagSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-free proof of the ordering and calls behind the BL-02/BL-10 fix. The
 * real-Oracle version -- does the FK actually let this happen -- lives in
 * {@code ArticleControllerIntegrationTest.deletingAnArticleRemovesItsRequiredReadingAndReadStatuses}
 * and its siblings; this one runs on every push, including with no database.
 */
class ContentDeletionServiceTest {

    private final RequiredReadingRepository requiredReadingRepository = mock(RequiredReadingRepository.class);
    private final ReadStatusRepository readStatusRepository = mock(ReadStatusRepository.class);
    private final FavoriteRepository favoriteRepository = mock(FavoriteRepository.class);
    private final TagSyncService tagSyncService = mock(TagSyncService.class);
    private final ContentDeletionService service = new ContentDeletionService(
            requiredReadingRepository, readStatusRepository, favoriteRepository, tagSyncService);

    private static RequiredReading readingWithId(long id) {
        RequiredReading reading = new RequiredReading();
        reading.setId(id);
        return reading;
    }

    /**
     * BL-02's own ordering note: read_statuses.required_reading_id FKs to
     * required_readings, so the child must go first or the delete would
     * throw on real Oracle.
     */
    @Test
    void readStatusesAreDeletedBeforeTheRequiredReadingsTheyReference() {
        List<RequiredReading> readings = List.of(readingWithId(10L), readingWithId(11L));
        when(requiredReadingRepository.findByItemTypeAndItemId("article", 5L)).thenReturn(readings);

        service.deletePolymorphicReferences("article", 5L);

        InOrder order = inOrder(readStatusRepository, requiredReadingRepository);
        order.verify(readStatusRepository).deleteByRequiredReadingIdIn(List.of(10L, 11L));
        order.verify(requiredReadingRepository).deleteAllInBatch(readings);
    }

    @Test
    void tagsAndFavoritesAreAlwaysCleared() {
        when(requiredReadingRepository.findByItemTypeAndItemId("video", 7L)).thenReturn(List.of());

        service.deletePolymorphicReferences("video", 7L);

        verify(tagSyncService).sync(eq("video"), eq(7L), eq(""));
        verify(favoriteRepository).deleteByItemTypeAndItemId("video", 7L);
    }

    /** No required readings for this item -- must not touch read_statuses at all. */
    @Test
    void noRequiredReadingsMeansNoReadStatusOrRequiredReadingCalls() {
        when(requiredReadingRepository.findByItemTypeAndItemId("video", 7L)).thenReturn(List.of());

        service.deletePolymorphicReferences("video", 7L);

        verify(readStatusRepository, never()).deleteByRequiredReadingIdIn(org.mockito.ArgumentMatchers.anyList());
        verify(requiredReadingRepository, never()).deleteAllInBatch(org.mockito.ArgumentMatchers.anyList());
    }
}
