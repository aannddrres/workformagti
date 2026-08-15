import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { NewsService } from '../../core/services/news.service';
import { UsersService } from '../../core/services/users.service';
import { FavoritesService } from '../../core/services/favorites.service';
import { NewsSummary } from '../../core/models/news';
import { getDepartmentBadge } from '../../shared/department-badge';
import { departmentMatches } from '../../shared/department-matcher';
import { formatKaDate } from '../../shared/ka-date';
import { FavoriteStar } from '../../shared/favorite-star/favorite-star';

const PAGE_SIZE = 10;
type SortOrder = 'newest' | 'oldest' | 'alphabetical';

/**
 * Port of page-news (base-layout.html:1063-1122) + fetchAndRenderNewsPage/
 * runNewsFilter. The original's skip/limit "load more" mechanism
 * (window.newsCurrentSkip/newsHasMore) has no reachable trigger from this
 * page -- its one #news-load-more-btn element is actually created by the
 * unrelated Mandatory-Reading page (a copy-paste/id-collision bug, see
 * migration doc). Real working pagination is built here instead of
 * reproducing the dead button. The favorites-only toggle (#news-fav-toggle
 * in the original) is now wired up too, now that the Favorites slice exists.
 */
@Component({
  selector: 'app-news-page',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar],
  templateUrl: './news-page.html'
})
export class NewsPage {
  private readonly newsService = inject(NewsService);
  private readonly usersService = inject(UsersService);
  protected readonly favoritesService = inject(FavoritesService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly allItems = signal<NewsSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly loadMoreError = signal(false);
  protected readonly hasMore = signal(false);
  protected readonly myDepartment = signal<string | null>(null);

  protected readonly searchQuery = signal('');
  protected readonly deptFilter = signal('');
  protected readonly sortOrder = signal<SortOrder>('newest');
  protected readonly favoritesOnly = signal(false);

  private skip = 0;

  protected readonly filteredItems = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const dept = this.deptFilter();
    const favOnly = this.favoritesOnly();
    const items = this.allItems().filter((item) => {
      // FE-07: this was `item.target_department !== dept`, exact equality
      // against option values -- so choosing "ტექნიკური" hid every item
      // assigned to "ტექნიკური — ჯგუფი 03", i.e. exactly the operators the
      // filter exists for. departmentMatches mirrors the backend's own rule.
      if (dept && !departmentMatches(item.target_department, dept)) {
        return false;
      }
      if (favOnly && !this.favoritesService.isFavorited('news', item.id)) {
        return false;
      }
      return !q || item.title.toLowerCase().includes(q);
    });

    const sort = this.sortOrder();
    return [...items].sort((a, b) => {
      if (sort === 'alphabetical') {
        return a.title.localeCompare(b.title, 'ka-GE');
      }
      const diff = new Date(a.created_at).getTime() - new Date(b.created_at).getTime();
      return sort === 'oldest' ? diff : -diff;
    });
  });

  constructor() {
    this.usersService.me().subscribe((profile) => this.myDepartment.set(profile.department));
    this.fetch(false);
  }

  private fetch(append: boolean): void {
    if (append) {
      this.loadingMore.set(true);
      this.loadMoreError.set(false);
    } else {
      this.loading.set(true);
      this.errorMessage.set(null);
    }

    this.newsService.list({ skip: this.skip, limit: PAGE_SIZE }).subscribe({
      next: (items) => {
        this.allItems.set(append ? [...this.allItems(), ...items] : items);
        this.hasMore.set(items.length === PAGE_SIZE);
        this.skip += PAGE_SIZE;
        this.loading.set(false);
        this.loadingMore.set(false);
      },
      error: () => {
        if (append) {
          this.loadMoreError.set(true);
          this.loadingMore.set(false);
        } else {
          this.errorMessage.set(this.translate.instant('news.page.load_error'));
          this.loading.set(false);
        }
      }
    });
  }

  refresh(): void {
    this.skip = 0;
    this.hasMore.set(false);
    this.fetch(false);
  }

  loadMore(): void {
    this.fetch(true);
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
  }

  onDeptFilterChange(value: string): void {
    this.deptFilter.set(value);
  }

  onSortChange(value: string): void {
    this.sortOrder.set(value as SortOrder);
  }

  toggleFavoritesOnly(): void {
    this.favoritesOnly.set(!this.favoritesOnly());
  }

  badgeFor(item: NewsSummary) {
    return getDepartmentBadge(item.target_department);
  }

  isMyDepartment(item: NewsSummary): boolean {
    return !!this.myDepartment() && item.target_department === this.myDepartment();
  }

  dateLabel(item: NewsSummary): string {
    return formatKaDate(item.created_at);
  }

  open(id: number): void {
    this.router.navigate(['/news', id]);
  }
}
