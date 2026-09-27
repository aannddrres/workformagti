package ge.magti.portal.compliance;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;

/**
 * Test material an operator can actually open. PO-40 counts, lists and
 * reminds only readings of such material, so a fixture reading of an
 * article that is still a draft, has no audience or does not exist now binds
 * nobody -- which is what the rule is for, and what many older fixtures
 * silently were.
 */
public final class OpenMaterial {

    private OpenMaterial() {
    }

    /** A published article for {@code audience} (at least one department, or "All"). */
    public static Article article(
            ArticleRepository articles, ArticleTargetDepartmentRepository targets,
            String title, String... audience) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setVersion(1);
        article.setStatus("published");
        article.setDraft(false);
        article.setTargetDepartment(audience.length == 1 ? audience[0] : "All");
        Article saved = articles.saveAndFlush(article);
        for (String department : audience) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(saved.getId());
            row.setDepartment(department);
            targets.saveAndFlush(row);
        }
        return saved;
    }
}
