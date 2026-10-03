package ge.magti.portal.web;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserNote;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.UserNoteRepository;
import ge.magti.portal.util.TbilisiTime;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import static ge.magti.portal.web.ArticleEndpointSupport.notFoundMap;
import static ge.magti.portal.web.ArticleEndpointSupport.assertArticleVisible;

/**
 * A reader's private note on an article. Part of the article API split
 * described on {@link ArticleController}.
 */
@RestController
public class ArticleNoteController {

    private final ArticleRepository articleRepository;
    private final UserNoteRepository userNoteRepository;
    private final ArticleEndpointSupport articleSupport;

    public ArticleNoteController(
            ArticleRepository articleRepository,
            UserNoteRepository userNoteRepository,
            ArticleEndpointSupport articleSupport) {
        this.articleRepository = articleRepository;
        this.userNoteRepository = userNoteRepository;
        this.articleSupport = articleSupport;
    }

    @GetMapping("/api/articles/{id}/note")
    public ResponseEntity<?> getUserNote(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        ResponseEntity<Map<String, String>> lookup = requireVisibleArticle(id, user);
        if (lookup != null) {
            return lookup;
        }

        Optional<UserNote> note = userNoteRepository.findByUserIdAndArticleId(user.getId(), id);
        if (note.isEmpty()) {
            // No note is the literal JSON `null`, not an empty body;
            // ResponseEntity.ok(null) would otherwise make Spring write zero
            // bytes, which a client's response.json() would fail to parse.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
        }
        return ResponseEntity.ok(UserNoteResponse.from(note.get()));
    }

    @PutMapping("/api/articles/{id}/note")
    @Transactional
    public ResponseEntity<?> putUserNote(
            @PathVariable Long id, @Valid @RequestBody UserNoteRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        ResponseEntity<Map<String, String>> lookup = requireVisibleArticle(id, user);
        if (lookup != null) {
            return lookup;
        }

        UserNote note = userNoteRepository.findByUserIdAndArticleId(user.getId(), id).orElseGet(UserNote::new);
        boolean isNew = note.getId() == null;
        note.setContent(request.content());
        if (isNew) {
            note.setUserId(user.getId());
            note.setArticleId(id);
            note.setCreatedAt(TbilisiTime.now());
        }
        note.setUpdatedAt(TbilisiTime.now());
        UserNote saved = userNoteRepository.save(note);
        return ResponseEntity.ok(UserNoteResponse.from(saved));
    }

    /** Combines the not-found check and the visibility check every note/quiz-style child route repeats. */
    private ResponseEntity<Map<String, String>> requireVisibleArticle(Long articleId, User user) {
        Optional<Article> found = articleRepository.findById(articleId);
        if (found.isEmpty()) {
            return notFoundMap();
        }
        return assertArticleVisible(found.get(), articleSupport.resolveTargetDepartments(articleId), user);
    }
}
