import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticleSummary } from '../../core/models/article';
import { Category } from '../../core/models/category';
import { ArticleEditDrawer } from './article-edit-drawer/article-edit-drawer';
import { ArticleHistoryModal } from './article-history-modal/article-history-modal';
import { NewsEditDrawer } from './news-edit-drawer/news-edit-drawer';
import { VideoEditDrawer } from './video-edit-drawer/video-edit-drawer';
import { NewsService } from '../../core/services/news.service';
import { VideosService } from '../../core/services/videos.service';
import { NewsSummary } from '../../core/models/news';
import { VideoInstruction } from '../../core/models/video';
import { ToastService } from '../../core/notifications/toast.service';
import { Observable } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';

type ContentType = 'all' | 'article' | 'news' | 'video';
type QueueRow = {
  key: string;
  id: number;
  type: Exclude<ContentType, 'all'>;
  title: string;
  status: string;
  createdAt: string;
  context: string;
  original: ArticleSummary | NewsSummary | VideoInstruction;
};

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
  imports: [TranslatePipe, DatePipe, ArticleEditDrawer, ArticleHistoryModal, NewsEditDrawer, VideoEditDrawer],
  templateUrl: './admin-content-page.html'
})
export class AdminContentPage {
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly newsService = inject(NewsService);
  private readonly videosService = inject(VideosService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly activeTab = signal<ContentType>('all');
  protected readonly createMenuOpen = signal(false);

  protected readonly articles = signal<ArticleSummary[]>([]);
  protected readonly news = signal<NewsSummary[]>([]);
  protected readonly videos = signal<VideoInstruction[]>([]);
  protected readonly categories = signal<Category[]>([]);
  protected readonly loading = signal(true);
  protected readonly newsLoading = signal(true);
  protected readonly videosLoading = signal(true);
  protected readonly queueLoading = computed(() => this.loading() || this.newsLoading() || this.videosLoading());

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

  protected readonly queueRows = computed<QueueRow[]>(() => {
    const articleRows: QueueRow[] = this.articles().map((item) => ({
      key: `article-${item.id}`, id: item.id, type: 'article', title: item.title,
      status: item.status, createdAt: item.created_at, context: this.categoryName(item), original: item
    }));
    const newsRows: QueueRow[] = this.news().map((item) => ({
      key: `news-${item.id}`, id: item.id, type: 'news', title: item.title,
      status: item.is_archived ? 'archived' : item.is_draft ? 'draft' : 'published',
      createdAt: item.created_at, context: item.target_department === 'All' ? 'ყველა დეპარტამენტი' : item.target_department,
      original: item
    }));
    const videoRows: QueueRow[] = this.videos().map((item) => ({
      key: `video-${item.id}`, id: item.id, type: 'video', title: item.title,
      status: item.is_archived ? 'archived' : 'published', createdAt: item.created_at,
      context: item.category || 'კატეგორიის გარეშე', original: item
    }));
    const type = this.activeTab();
    const q = this.searchQuery().trim().toLowerCase();
    const status = this.statusFilter();
    return [...articleRows, ...newsRows, ...videoRows]
      .filter((row) => type === 'all' || row.type === type)
      .filter((row) => !q || row.title.toLowerCase().includes(q) || row.context.toLowerCase().includes(q))
      .filter((row) => !status || row.status === status)
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  });
  protected readonly queueTotalPages = computed(() => Math.max(1, Math.ceil(this.queueRows().length / PAGE_SIZE)));
  protected readonly pageRows = computed(() => {
    const page = Math.min(this.currentPage(), this.queueTotalPages());
    return this.queueRows().slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  });
  protected readonly queueStart = computed(() => this.queueRows().length === 0 ? 0 : (Math.min(this.currentPage(), this.queueTotalPages()) - 1) * PAGE_SIZE + 1);
  protected readonly queueEnd = computed(() => Math.min(this.queueStart() + PAGE_SIZE - 1, this.queueRows().length));

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
  protected readonly editingNews = signal<NewsSummary | { id: null } | null>(null);
  protected readonly editingVideo = signal<VideoInstruction | { id: null } | null>(null);
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
    const query = this.route.snapshot.queryParamMap;
    const type = query.get('type');
    if (type === 'article' || type === 'news' || type === 'video' || type === 'all') {
      this.activeTab.set(type);
    }
    this.searchQuery.set(query.get('q') ?? '');
    this.statusFilter.set(query.get('status') ?? '');
    this.currentPage.set(Math.max(1, Number(query.get('page')) || 1));
    this.loadArticles();
    this.loadNews();
    this.loadVideos();
    this.loadCategories();
  }

  private loadNews(): void {
    this.newsLoading.set(true);
    this.newsService.listAdmin().subscribe({
      next: (items) => { this.news.set(items); this.newsLoading.set(false); },
      error: () => { this.newsLoading.set(false); this.toast.error('სიახლეების რიგი ვერ ჩაიტვირთა'); }
    });
  }

  private loadVideos(): void {
    this.videosLoading.set(true);
    this.videosService.list().subscribe({
      next: (items) => { this.videos.set(items); this.videosLoading.set(false); },
      error: () => { this.videosLoading.set(false); this.toast.error('ვიდეოების რიგი ვერ ჩაიტვირთა'); }
    });
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

  protected switchTab(tab: ContentType): void {
    this.activeTab.set(tab);
    this.currentPage.set(1);
    this.updateUrl();
  }

  protected tabClass(tab: ContentType): string {
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
    this.updateUrl();
  }

  protected goToPage(page: number): void {
    this.currentPage.set(Math.max(1, Math.min(page, this.queueTotalPages())));
    this.updateUrl();
  }

  private updateUrl(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        type: this.activeTab() === 'all' ? null : this.activeTab(),
        q: this.searchQuery().trim() || null,
        status: this.statusFilter() || null,
        page: this.currentPage() > 1 ? this.currentPage() : null
      },
      queryParamsHandling: 'merge'
    });
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
    this.createMenuOpen.set(false);
    this.editingArticle.set({ id: null });
  }

  protected openCreateNews(): void { this.createMenuOpen.set(false); this.editingNews.set({ id: null }); }
  protected openCreateVideo(): void { this.createMenuOpen.set(false); this.editingVideo.set({ id: null }); }
  protected createLabel(): string {
    return ({ all: '+ სტატია', article: '+ სტატია', news: '+ სიახლე', video: '+ ვიდეო' })[this.activeTab()];
  }
  protected openCreateCurrent(): void {
    if (this.activeTab() === 'news') this.openCreateNews();
    else if (this.activeTab() === 'video') this.openCreateVideo();
    else this.openCreateArticle();
  }
  protected openEditRow(row: QueueRow): void {
    if (row.type === 'article') this.openEditArticle(row.original as ArticleSummary);
    else if (row.type === 'news') this.editingNews.set(row.original as NewsSummary);
    else this.editingVideo.set(row.original as VideoInstruction);
  }
  protected closeNewsDrawer(): void { this.editingNews.set(null); }
  protected closeVideoDrawer(): void { this.editingVideo.set(null); }
  protected onNewsSaved(): void { this.closeNewsDrawer(); this.loadNews(); }
  protected onVideoSaved(): void { this.closeVideoDrawer(); this.loadVideos(); }

  protected typeLabel(type: QueueRow['type']): string {
    return ({ article: 'სტატია', news: 'სიახლე', video: 'ვიდეო' })[type];
  }

  protected rowArchived(row: QueueRow): boolean { return row.status === 'archived'; }

  protected toggleArchiveRow(row: QueueRow): void {
    if (row.type === 'article') {
      this.toggleArchive(row.original as ArticleSummary);
      return;
    }
    const archived = row.status === 'archived';
    const request: Observable<unknown> = row.type === 'news'
      ? (archived ? this.newsService.unarchive(row.id) : this.newsService.archive(row.id))
      : (archived ? this.videosService.unarchive(row.id) : this.videosService.archive(row.id));
    request.subscribe({
      next: () => row.type === 'news' ? this.loadNews() : this.loadVideos(),
      error: () => this.toast.error('არქივის მოქმედება ვერ შესრულდა')
    });
  }

  protected removeRow(row: QueueRow): void {
    if (row.type === 'article') { this.deleteArticle(row.original as ArticleSummary); return; }
    if (!this.rowArchived(row)) { this.toast.error('კონტენტი ჯერ უნდა დაარქივოთ.'); return; }
    if (!window.confirm('გადავიტანოთ ჩანაწერი სანაგვეში? ისტორიული მტკიცებულებები შენარჩუნდება.')) return;
    const request: Observable<unknown> = row.type === 'news' ? this.newsService.remove(row.id) : this.videosService.remove(row.id);
    request.subscribe({
      next: () => row.type === 'news' ? this.loadNews() : this.loadVideos(),
      error: () => this.toast.error('სანაგვეში გადატანა ვერ შესრულდა')
    });
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
    if (article.status !== 'archived') {
      this.actionError.set('სტატია ჯერ უნდა დაარქივოთ და მხოლოდ შემდეგ გადაიტანოთ სანაგვეში.');
      return;
    }
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
