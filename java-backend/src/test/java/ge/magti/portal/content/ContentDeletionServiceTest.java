package ge.magti.portal.content;

import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.video.TagSyncService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** DB-free guard that purge removes presentation references only. */
class ContentDeletionServiceTest {

    private final FavoriteRepository favoriteRepository = mock(FavoriteRepository.class);
    private final TagSyncService tagSyncService = mock(TagSyncService.class);
    private final ContentDeletionService service = new ContentDeletionService(favoriteRepository, tagSyncService);

    @Test
    void purgeClearsTagsAndFavorites() {
        service.purgeNonEvidenceReferences("article", 5L);

        verify(tagSyncService).sync("article", 5L, "");
        verify(favoriteRepository).deleteByItemTypeAndItemId("article", 5L);
    }
}
