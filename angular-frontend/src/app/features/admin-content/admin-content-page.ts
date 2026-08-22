import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticleSummary } from '../../core/models/article';
import { Category } from '../../core/models/category';
import { ArticleEditDrawer } from './article-edit-drawer/article-edit-drawer';
import { ArticleHistoryModal } from './article-history-modal/article-history-modal';
import { NewsAdminTable } from './news-admin-table/news-admin-table';
import { VideosAdminTable } from './videos-admin-table/videos-admin-table';

type ContentTab = 'articles' | 'news' | 'videos';

const PAGE_SIZE = 20;

const STATUS_BADGE: Record<string, string> = {
  published: 'bg-emerald-50 text-emerald-700 border-emerald-100',
  draft: 'bg-gray-50 text-gray-700 border-gray-100',
  scheduled: 'bg-blue-50 text-blue-700 border-blue-100',
  archived: 'bg-gray-100 text-gray-500 border-gray-200'
};

/**
 * Port of #admin-content (base-layout.html:1758-2089) -- the largest of the
 * admin screens. The old Python screen had a Feedback tab; feedback is not a
 * product feature in the Angular/Java portal and neither UI nor API is exposed.
 *
 * <p>Articles table: fetch-everything-then-paginate-client-side (mirrors
 * fetchAndRenderAdminContent's `limit=1000` + renderArticlePage), search +
 * category + status filters, per-row action menu (history/edit/
 * archive-toggle/delete), select-all + bulk archive/unarchive. The
 * "ისტორია" action -- deferred out of the initial Content management slice
 * with explicit user sign-off -- opens {@link ArticleHistoryModal}.
 */
@Component({
  selector: 'app-admin-content-page',
  standalone: true,
  imports: [TranslatePipe, DatePipe, ArticleEditDrawer, ArticleHistoryModal, NewsAdminTable, VideosAdminTable],
  templateUrl: './admin-content-page.html'
})
export class AdminContentPage {
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly translate = inject(TranslateService);

  protected readonly activeTab = signal<ContentTab>('articles');

  protected readonly articles = signal<ArticleSummary[]>([]);
  protected readonly categories = signal<Category[]>([]);
  protected readonly loading = signal(true);

  protected readonly searchQuery = signal('');
  protected readonly categoryFilter = signal<number | null>(null);
  protected readonly statusFilter = signal('');

  protected readonly currentPage = signal(1);
  protected readonly selection = signal<Set<number>>(new Set());
  /**
   * Drives the bulk buttons' enabled state and their labels.
   *
   * bulkArchive() has always returned early on an empty selection, so the
   * buttons looked live, did nothing when pressed, and said nothing about
   * why — the user is left to guess that a selection was required.
   */
  protected readonly selectionCount = computed(() => this.selection().size);

  protected readonly filteredArticles = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const categoryId = this.categoryFilter();
    const status = this.statusFilter();
    return this.articles().filter((a) => {
      if (q && !a.title.toLowerCase().includes(q)) return false;
      if (categoryId != null && a.category_id !== categoryId) return false;
      if (status && a.status !== status) return false;
      return true;
    });
  });

  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.filteredArticles().length / PAGE_SIZE)));

  protected readonly pageArticles = computed(() => {
    const page = Math.min(this.currentPage(), this.totalPages());
    const start = (page - 1) * PAGE_SIZE;
    return this.filteredArticles().slice(start, start + PAGE_SIZE);
  });

  protected readonly pageStart = computed(() =>
    this.filteredArticles().length === 0 ? 0 : (Math.min(this.currentPage(), this.totalPages()) - 1) * PAGE_SIZE + 1
  );
  protected readonly pageEnd = computed(() =>
    Math.min(this.pageStart() + PAGE_SIZE - 1, this.filteredArticles().length)
  );

  protected readonly pageNumbers = computed(() => {
    const total = this.totalPages();
    const page = Math.min(this.currentPage(), total);
    const start = Math.max(1, Math.min(page - 2, total - 4));
    const end = Math.min(total, start + 4);
    const numbers: number[] = [];
    for (let p = Math.max(1, start); p <= end; p++) numbers.push(p);
    return numbers;
  });

  protected readonly allOnPageSelected = computed(() => {
    const page = this.pageArticles();
    return page.length > 0 && page.every((a) => this.selection().has(a.id));
  });

  protected readonly editingArticle = signal<ArticleSummary | { id: null } | null>(null);
  protected readonly openMenuFor = signal<number | null>(null);
  protected readonly historyForArticleId = signal<number | null>(null);
  protected readonly actionError = signal<string | null>(null);
  /**
   * FE-04: this dropdown is a FILTER, so a failed load is less dangerous
   * than in the drawer -- but silently showing only "All categories" makes
   * the admin think the portal has none, and quietly removes their ability
   * to narrow a long list.
   */
  protected readonly categoriesFailed = signal(false);

  constructor() {
    this.loadArticles();
    this.loadCategories();
  }

  protected loadCategories(): void {
    this.categoriesService.list().subscribe({
      next: (data) => {
        this.categories.set(data);
        this.categoriesFailed.set(false);
      },
      error: () => this.categoriesFailed.set(true)
    });
  }

  private loadArticles(): void {
    this.loading.set(true);
    this.articlesService.listAdmin().subscribe({
      next: (data) => {
        this.articles.set(data);
        this.loading.set(false);
        this.selection.set(new Set());
      },
      error: () => this.loading.set(false)
    });
  }

  protected switchTab(tab: ContentTab): void {
    this.activeTab.set(tab);
  }

  protected tabClass(tab: ContentTab): string {
    return this.activeTab() === tab
      ? 'border-b-2 border-brand px-4 pb-3 text-sm font-semibold text-brand'
      : 'border-b-2 border-transparent px-4 pb-3 text-sm font-medium text-gray-500 hover:text-gray-800 dark:text-zinc-400 dark:hover:text-zinc-100';
  }

  protected pageButtonClass(page: number): string {
    return page === this.currentPage()
      ? 'rounded-lg px-3 py-1.5 text-sm font-medium bg-brand text-white shadow-sm'
      : 'rounded-lg px-3 py-1.5 text-sm font-medium text-gray-600 dark:text-zinc-300 hover:bg-gray-100 dark:hover:bg-zinc-800';
  }

  protected categoryName(article: ArticleSummary): string {
    return article.category_name ?? this.categories().find((c) => c.id === article.category_id)?.name ?? `ID: ${article.category_id}`;
  }

  protected statusBadgeClass(status: string): string {
    return STATUS_BADGE[status] ?? STATUS_BADGE['draft'];
  }

  protected onFilterChange(): void {
    this.currentPage.set(1);
  }

  protected goToPage(page: number): void {
    this.currentPage.set(Math.max(1, Math.min(page, this.totalPages())));
  }

  protected toggleMenu(id: number): void {
    this.openMenuFor.update((current) => (current === id ? null : id));
  }

  protected toggleSelectAllOnPage(checked: boolean): void {
    this.selection.update((current) => {
      const next = new Set(current);
      for (const a of this.pageArticles()) {
        if (checked) next.add(a.id);
        else next.delete(a.id);
      }
      return next;
    });
  }

  protected toggleSelected(id: number, checked: boolean): void {
    this.selection.update((current) => {
      const next = new Set(current);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected openCreateArticle(): void {
    this.editingArticle.set({ id: null });
  }

  protected openEditArticle(article: ArticleSummary): void {
    this.openMenuFor.set(null);
    this.editingArticle.set(article);
  }

  protected closeArticleDrawer(): void {
    this.editingArticle.set(null);
  }

  protected onArticleSaved(): void {
    this.closeArticleDrawer();
    this.loadArticles();
  }

  protected toggleArchive(article: ArticleSummary): void {
    this.openMenuFor.set(null);
    const shouldArchive = article.status !== 'archived';
    const message = shouldArchive
      ? this.translate.instant('content.articles.confirm_archive_one')
      : this.translate.instant('content.articles.confirm_unarchive_one');
    if (!window.confirm(message)) return;
    this.actionError.set(null);
    const request = shouldArchive ? this.articlesService.archive(article.id) : this.articlesService.unarchive(article.id);
    request.subscribe({
      next: () => this.loadArticles(),
      error: (err) => this.actionError.set(err?.error?.detail ?? this.translate.instant('content.articles.archive_failed'))
    });
  }

  protected openHistory(article: ArticleSummary): void {
    this.openMenuFor.set(null);
    this.historyForArticleId.set(article.id);
  }

  protected closeHistory(): void {
    this.historyForArticleId.set(null);
  }

  protected onVersionRestored(): void {
    this.historyForArticleId.set(null);
    this.loadArticles();
  }

  protected deleteArticle(article: ArticleSummary): void {
    this.openMenuFor.set(null);
    if (!window.confirm(this.translate.instant('content.articles.confirm_delete'))) return;
    this.actionError.set(null);
    this.articlesService.remove(article.id).subscribe({
      next: () => this.loadArticles(),
      error: (err) => this.actionError.set(err?.error?.detail ?? this.translate.instant('content.articles.delete_failed'))
    });
  }

  protected bulkArchive(archive: boolean): void {
    const ids = [...this.selection()];
    if (ids.length === 0) return;
    const message = this.translate.instant(
      archive ? 'content.articles.confirm_bulk_archive' : 'content.articles.confirm_bulk_unarchive',
      { count: ids.length }
    );
    if (!window.confirm(message)) return;
    this.actionError.set(null);
    this.articlesService.bulkArchive(ids, archive).subscribe({
      next: () => this.loadArticles(),
      error: (err) => this.actionError.set(err?.error?.detail ?? this.translate.instant('content.articles.bulk_archive_failed'))
    });
  }

  protected dismissActionError(): void {
    this.actionError.set(null);
  }
}
