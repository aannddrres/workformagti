import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Location } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { TranslatePipe } from '@ngx-translate/core';
import { CategoriesService } from '../../core/services/categories.service';
import { ArticlesService } from '../../core/services/articles.service';
import { Category } from '../../core/models/category';
import { ArticleCard, ArticleCardViewModel } from '../../shared/article-card/article-card';
import { getCategoryIcon } from '../../shared/category-visuals';

type AudienceProfile = 'all' | 'info' | 'tech';

interface CategoryArticleRow extends ArticleCardViewModel {
  audienceProfile: string | null;
}

@Component({
  selector: 'app-category-view-page',
  standalone: true,
  imports: [ArticleCard, TranslatePipe],
  templateUrl: './category-view-page.html'
})
export class CategoryViewPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly categoriesService = inject(CategoriesService);
  private readonly articlesService = inject(ArticlesService);

  private readonly slug = toSignal(this.route.paramMap.pipe(map((params) => params.get('slug') ?? '')), { initialValue: '' });

  protected readonly category = signal<Category | null>(null);
  protected readonly rows = signal<CategoryArticleRow[]>([]);
  protected readonly loading = signal(true);
  protected readonly profile = signal<AudienceProfile>('all');

  protected readonly icon = computed(() => {
    const cat = this.category();
    return cat?.icon || getCategoryIcon(cat?.name, '');
  });

  protected readonly filteredRows = computed(() => {
    const profile = this.profile();
    const rows = this.rows();
    const filtered = profile === 'all' ? rows : rows.filter((r) => r.audienceProfile === profile || r.audienceProfile === 'all');
    return [...filtered].sort((a, b) => {
      const timeA = new Date(a.publishedAt || a.createdAt).getTime();
      const timeB = new Date(b.publishedAt || b.createdAt).getTime();
      return timeB - timeA;
    });
  });

  constructor() {
    this.categoriesService.list().subscribe((categories) => {
      const slug = this.slug();
      const found = categories.find((c) => c.slug === slug || String(c.id) === slug) ?? null;
      this.category.set(found);
      if (found) {
        this.loadArticles(found.id, found.name);
      } else {
        this.loading.set(false);
      }
    });
  }

  private loadArticles(categoryId: number, categoryName: string): void {
    this.loading.set(true);
    this.articlesService.list({ categoryId, limit: 200 }).subscribe({
      next: (articles) => {
        this.rows.set(
          articles.map((a) => ({
            id: a.id,
            title: a.title,
            categoryName,
            createdAt: a.created_at,
            publishedAt: a.published_at,
            readTime: a.read_time,
            audienceProfile: a.audience_profile
          }))
        );
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  setProfile(profile: AudienceProfile): void {
    this.profile.set(profile);
  }

  openArticle(id: number): void {
    this.articlesService.logView(id);
    this.router.navigate(['/article', id]);
  }

  goBack(): void {
    this.location.back();
  }
}
