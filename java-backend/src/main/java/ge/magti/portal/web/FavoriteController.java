package ge.magti.portal.web;

import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Favorite;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.FavoriteRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/favorites.py -- all 3 endpoints (list, add, remove).
 * Any authenticated user (any active role) manages only their own
 * bookmarks; there is no admin/content-admin gate anywhere in this
 * domain, unlike every other Content controller so far.
 */
@RestController
public class FavoriteController {

    private final FavoriteRepository favoriteRepository;
    private final ItemTitleResolver itemTitleResolver;

    public FavoriteController(FavoriteRepository favoriteRepository, ItemTitleResolver itemTitleResolver) {
        this.favoriteRepository = favoriteRepository;
        this.itemTitleResolver = itemTitleResolver;
    }

    /** Port of get_favorites (routers/favorites.py:14-42). */
    @GetMapping("/api/favorites")
    public ResponseEntity<?> getFavorites(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        List<FavoriteResponse> favorites = CompleteResultGuard.enforce(
                        favoriteRepository.findByUserId(user.getId(), CompleteResultGuard.sentinelPage())).stream()
                .map(f -> FavoriteResponse.from(f, itemTitleResolver.resolve(f.getItemType(), f.getItemId()).orElse(null)))
                .toList();
        return ResponseEntity.ok(favorites);
    }

    /**
     * Port of add_favorite (routers/favorites.py:44-90). Idempotent: an
     * existing bookmark for the same (user, item) is returned as-is,
     * matching Python exactly.
     */
    @PostMapping("/api/favorites")
    @Transactional
    public ResponseEntity<?> addFavorite(@Valid @RequestBody FavoriteRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        favoriteRepository.insertIfMissing(user.getId(), request.itemType(), request.itemId());
        Favorite favorite = favoriteRepository
                .findByUserIdAndItemTypeAndItemId(user.getId(), request.itemType(), request.itemId())
                .orElseThrow();
        String title = itemTitleResolver.resolve(favorite.getItemType(), favorite.getItemId()).orElse(null);
        return ResponseEntity.ok(FavoriteResponse.from(favorite, title));
    }

    /** Port of remove_favorite (routers/favorites.py:92-122). */
    @DeleteMapping("/api/favorites/{id}")
    public ResponseEntity<?> removeFavorite(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<Favorite> found = favoriteRepository.findByIdAndUserId(id, user.getId());
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "რჩეული ვერ მოიძებნა"));
        }
        favoriteRepository.delete(found.get());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

}
