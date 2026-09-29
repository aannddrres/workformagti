import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../core/services/articles.service';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticleBulkResponse, ArticleBulkStatus, ArticleSummary } from '../../core/models/article';
import { Category } from '../../core/models/category';
import { DEPARTMENTS } from '../../shared/user-roles';
import { ArticleEditDrawer } from './article-edit-drawer/article-edit-drawer';
import { ArticleHistoryModal } from './article-history-modal/article-history-modal';
import { NewsEditDrawer } from './news-edit-drawer/news-edit-drawer';
import { VideoEditDrawer } from './video-edit-drawer/video-edit-drawer';
import { NewsService } from '../../core/services/news.service';
import { VideosService } from '../../core/services/videos.service';
import { NewsSummary } from '../../core/models/news';
import { VideoInstruction } from '../../core/models/video';
import { ToastService } from '../../core/notifications/toast.service';
import { Observable, firstValueFrom } from 'rxjs';
import { ActivatedRoute, Router } from '@angular/router';
import { ConfirmRequest, ConfirmService } from '../../core/notifications/confirm.service';
import { RequiredReadingService } from '../../core/services/required-reading.service';
import { lossLines, mandatoryLoss } from '../../shared/mandatory-reach';
import { createTableSort } from '../../shared/table-sort';
import { KaDatePipe } from '../../shared/ka-date.pipe';

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
  published: 'bg-emerald-50 text-emerald-700 border-emerald-100 dark:bg-emerald-950/40 dark:text-emerald-300 dark:border-emerald-800/60',
  draft: 'bg-slate-50 text-slate-700 border-slate-100 dark:bg-slate-800/60 dark:text-slate-300 dark:border-slate-700',
  scheduled: 'bg-blue-50 text-blue-700 border-blue-100 dark:bg-blue-950/40 dark:text-blue-300 dark:border-blue-800/60',
  archived: 'bg-slate-100 text-slate-600 border-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:border-slate-700'
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
  imports: [TranslatePipe, KaDatePipe, ArticleEditDrawer, ArticleHistoryModal, NewsEditDrawer, VideoEditDrawer],
  templateUrl: './admin-content-page.html'
})
export class AdminContentPage {
  private readonly confirmService = inject(ConfirmService);
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly newsService = inject(NewsService);
  private readonly videosService = inject(VideosService);
  private readonly requiredReadingService = inject(RequiredReadingService);
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
  // A failed request used to end in the empty-list message ("nothing matches
  // your filters"), so an outage looked like an empty CMS.
  protected readonly articlesFailed = signal(false);
  protected readonly newsFailed = signal(false);
  protected readonly videosFailed = signal(false);
  protected readonly queueFailed = computed(() => this.articlesFailed() || this.newsFailed() || this.videosFailed());

  protected readonly searchQuery = signal('');
  protected readonly categoryFilter = signal<number | null>(null);
  protected readonly statusFilter = signal('');

  protected readonly currentPage = signal(1);

  /**
   * Selected ARTICLE ids. Not news or video ids, and the queue reflects that
   * by giving only article rows a checkbox.
   *
   * The bulk endpoints are article endpoints. Letting a news row into a
   * selection would produce a request that silently did nothing for part of
   * it -- the ids would simply not be found -- and the user would be told
   * "3 updated" for a batch of five.
   */
  protected readonly selection = signal<Set<number>>(new Set());
  protected readonly selectionCount = computed(() => this.selection().size);

  /** The article rows on the current page -- the only selectable ones. */
  protected readonly selectableRows = computed(() =>
    this.pageRows().filter((row) => row.type === 'article')
  );

  protected readonly allSelectableSelected = computed(() => {
    const rows = this.selectableRows();
    return rows.length > 0 && rows.every((row) => this.selection().has(row.id));
  });

  /** Result of the last bulk call, shown instead of a silent refresh. */
  protected readonly bulkNotice = signal<string | null>(null);
  protected readonly bulkBusy = signal(false);

  /**
   * The audiences a batch can be aimed at. The same list the single-article
   * drawer offers, so a bulk change cannot produce a department the editor
   * could not have typed by hand.
   */
  protected readonly bulkDepartmentOptions = DEPARTMENTS;

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
  protected readonly sort = createTableSort<QueueRow>({
    title: (row) => row.title,
    type: (row) => row.type,
    status: (row) => row.status,
    context: (row) => row.context,
    createdAt: (row) => row.createdAt
  });
  /** Sorted before paging, so a column orders the whole queue rather than
   *  the twenty rows that happen to be on this page. With no column chosen
   *  it keeps the newest-first order queueRows already applies. */
  protected readonly sortedQueueRows = computed(() => this.sort.sort(this.queueRows()));
  protected readonly queueTotalPages = computed(() => Math.max(1, Math.ceil(this.queueRows().length / PAGE_SIZE)));
  protected readonly pageRows = computed(() => {
    const page = Math.min(this.currentPage(), this.queueTotalPages());
    return this.sortedQueueRows().slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
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
      next: (items) => { this.news.set(items); this.newsFailed.set(false); this.newsLoading.set(false); },
      error: () => { this.newsFailed.set(true); this.newsLoading.set(false); this.toast.error('სიახლეების რიგი ვერ ჩაიტვირთა'); }
    });
  }

  private loadVideos(): void {
    this.videosLoading.set(true);
    this.videosService.list().subscribe({
      next: (items) => { this.videos.set(items); this.videosFailed.set(false); this.videosLoading.set(false); },
      error: () => { this.videosFailed.set(true); this.videosLoading.set(false); this.toast.error('ვიდეოების რიგი ვერ ჩაიტვირთა'); }
    });
  }

  /** Reloads only the sources that failed. */
  protected retryQueue(): void {
    if (this.articlesFailed()) this.loadArticles();
    if (this.newsFailed()) this.loadNews();
    if (this.videosFailed()) this.loadVideos();
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
        this.articlesFailed.set(false);
        this.loading.set(false);
        this.selection.set(new Set());
      },
      error: () => {
        this.articlesFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  protected switchTab(tab: ContentType): void {
    this.activeTab.set(tab);
    this.currentPage.set(1);
    this.updateUrl();
  }

  protected pageButtonClass(page: number): string {
    return page === this.currentPage()
      ? 'rounded-md px-3 py-1.5 text-sm font-normal bg-brand dark:bg-brand-700 text-white shadow-e1'
      : 'rounded-md px-3 py-1.5 text-sm font-normal text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800';
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
      for (const row of this.selectableRows()) {
        if (checked) next.add(row.id);
        else next.delete(row.id);
      }
      return next;
    });
  }

  protected clearSelection(): void {
    this.selection.set(new Set());
    this.bulkNotice.set(null);
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

  protected async removeRow(row: QueueRow): Promise<void> {
    if (row.type === 'article') { await this.deleteArticle(row.original as ArticleSummary); return; }
    if (!this.rowArchived(row)) { this.toast.error('კონტენტი ჯერ უნდა დაარქივოთ.'); return; }
    if (!(await this.confirmService.ask('გადავიტანოთ ჩანაწერი სანაგვეში? ისტორიული მტკიცებულებები შენარჩუნდება.'))) return;
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

  protected async toggleArchive(article: ArticleSummary): Promise<void> {
    this.openMenuFor.set(null);
    const shouldArchive = article.status !== 'archived';
    const message = shouldArchive
      ? this.translate.instant('content.articles.confirm_archive_one')
      : this.translate.instant('content.articles.confirm_unarchive_one');
    const mandatoryWarning = shouldArchive ? await this.archiveMandatoryWarning(article.id) : null;
    if (!(await this.confirmService.ask(mandatoryWarning ?? message))) return;
    this.actionError.set(null);
    const request = shouldArchive ? this.articlesService.archive(article.id) : this.articlesService.unarchive(article.id);
    request.subscribe({
      next: () => this.loadArticles(),
      error: (err) => this.actionError.set(err?.error?.detail ?? this.translate.instant('content.articles.archive_failed'))
    });
  }

  /**
   * PO-40: archiving mandatory material pauses the obligation for everyone it
   * binds. Asked before, naming them where the caller may see names and
   * counting them otherwise; null -- the plain question -- when the article
   * binds nobody or who it binds cannot be read.
   */
  private async archiveMandatoryWarning(articleId: number): Promise<ConfirmRequest | null> {
    try {
      const audience = await firstValueFrom(this.requiredReadingService.addressees('article', articleId));
      const loss = mandatoryLoss(audience, { reach: 'never', departments: [] });
      if (loss.total === 0) return null;
      let message = this.translate.instant('content.articles.mandatory_loss_archive', { count: loss.total });
      if (loss.confirmed > 0) {
        message += ' ' + this.translate.instant('content.articles.mandatory_loss_confirmed', { count: loss.confirmed });
      }
      return {
        title: this.translate.instant('content.articles.mandatory_loss_title'),
        message,
        details: lossLines(loss, (count) => this.translate.instant('content.articles.mandatory_loss_more', { count })),
        confirmLabel: this.translate.instant('content.articles.mandatory_loss_archive_confirm'),
        tone: 'danger'
      };
    } catch {
      return null;
    }
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

  protected async deleteArticle(article: ArticleSummary): Promise<void> {
    this.openMenuFor.set(null);
    if (article.status !== 'archived') {
      this.actionError.set('სტატია ჯერ უნდა დაარქივოთ და მხოლოდ შემდეგ გადაიტანოთ სანაგვეში.');
      return;
    }
    if (!(await this.confirmService.ask({ message: this.translate.instant('content.articles.confirm_delete'), tone: 'danger' }))) return;
    this.actionError.set(null);
    this.articlesService.remove(article.id).subscribe({
      next: () => this.loadArticles(),
      error: (err) => this.actionError.set(err?.error?.detail ?? this.translate.instant('content.articles.delete_failed'))
    });
  }

  /**
   * Move every selected article to one status.
   *
   * Confirmation is asked for publishing and only for publishing. Sending
   * something back to draft or the archive takes it away from readers and is
   * undone by the opposite button; publishing puts it in front of six hundred
   * people, and that is the direction worth a second look.
   */
  protected async bulkStatus(status: ArticleBulkStatus): Promise<void> {
    const ids = [...this.selection()];
    if (ids.length === 0) return;
    if (status === 'published' && !(await this.confirmService.ask(`გამოქვეყნდეს ${ids.length} მასალა. გავაგრძელოთ?`))) {
      return;
    }
    this.runBulk(this.articlesService.bulkStatus(ids, status));
  }

  /** Re-file the selection into another category. */
  protected bulkCategory(rawValue: string): void {
    const ids = [...this.selection()];
    const categoryId = Number(rawValue);
    if (ids.length === 0 || !rawValue || Number.isNaN(categoryId)) return;
    this.runBulk(this.articlesService.bulkRetarget(ids, { categoryId }));
  }

  /** Re-aim the selection at another department. */
  protected bulkDepartment(rawValue: string): void {
    const ids = [...this.selection()];
    if (ids.length === 0 || !rawValue) return;
    this.runBulk(this.articlesService.bulkRetarget(ids, { targetDepartments: [rawValue] }));
  }

  /**
   * One place for what every bulk call does afterwards.
   *
   * The response is reported rather than swallowed: a batch is partial by
   * nature -- an article already in the requested state is skipped -- and
   * refreshing the list without saying so leaves the user counting rows to
   * work out what happened.
   */
  private runBulk(request: Observable<ArticleBulkResponse>): void {
    this.actionError.set(null);
    this.bulkNotice.set(null);
    this.bulkBusy.set(true);
    request.subscribe({
      next: (result) => {
        this.bulkBusy.set(false);
        const skipped = result.skipped_ids?.length ?? 0;
        this.bulkNotice.set(
          skipped === 0
            ? `განახლდა ${result.updated} მასალა.`
            : `განახლდა ${result.updated} მასალა, ${skipped} გამოტოვებული (უკვე ამ მდგომარეობაში იყო).`
        );
        this.selection.set(new Set());
        this.loadArticles();
      },
      error: (err) => {
        this.bulkBusy.set(false);
        this.actionError.set(err?.error?.detail ?? 'მასობრივი ოპერაცია ვერ შესრულდა');
      }
    });
  }

  protected dismissBulkNotice(): void {
    this.bulkNotice.set(null);
  }

  protected dismissActionError(): void {
    this.actionError.set(null);
  }
}
