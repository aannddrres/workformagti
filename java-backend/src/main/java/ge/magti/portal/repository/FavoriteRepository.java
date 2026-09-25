package ge.magti.portal.repository;

import ge.magti.portal.domain.Favorite;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserId(Long userId, Pageable pageable);

    Optional<Favorite> findByIdAndUserId(Long id, Long userId);

    Optional<Favorite> findByUserIdAndItemTypeAndItemId(Long userId, String itemType, Long itemId);

    /**
     * BL-10: no delete path ever cleared favorites for a deleted item
     * (favorites.item_id has no FK, V9:7). Derived delete, same shape as
     * {@link TagMappingRepository#deleteByItemTypeAndItemId} -- nothing
     * re-inserts a favorite for this item in the same transaction, so there
     * is no flush-order trap to guard against with {@code @Modifying} here.
     */
    void deleteByItemTypeAndItemId(String itemType, Long itemId);

    /**
     * Port of add_favorite's race guard (routers/favorites.py:64-80). No
     * UPDATE case exists here (favoriting twice is a no-op, nothing to
     * overwrite), so this is INSERT-if-missing only, not a full MERGE.
     *
     * <p>The NOT EXISTS alone was documented as atomic and is not: under
     * read committed it cannot see another transaction's uncommitted insert
     * of the same bookmark, so the second insert waited on V9's
     * {@code uq_favorite_user_item} (see {@link Favorite}) and, once the
     * first committed, failed with ORA-00001 -- a 500 on a double click
     * (ConcurrentUpsertIntegrationTest). {@code IGNORE_ROW_ON_DUPKEY_INDEX}
     * is Oracle's semantic hint for exactly this: the waiting insert skips
     * the row instead of raising, and the caller's read-back finds the
     * committed one. NOT EXISTS stays so the common path never takes the
     * hint's slower duplicate branch.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(favorites (user_id, item_type, item_id)) */
            INTO favorites (user_id, item_type, item_id)
            SELECT :userId, :itemType, :itemId FROM dual
            WHERE NOT EXISTS (
                SELECT 1 FROM favorites WHERE user_id = :userId AND item_type = :itemType AND item_id = :itemId
            )
            """, nativeQuery = true)
    void insertIfMissing(@Param("userId") Long userId, @Param("itemType") String itemType, @Param("itemId") Long itemId);
}
