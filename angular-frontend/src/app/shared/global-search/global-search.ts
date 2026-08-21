import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Subject, debounceTime, filter, of, switchMap } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { SearchService } from '../../core/services/search.service';
import { SearchHit } from '../../core/models/search';
import { detailRouteFor, iconForContentType } from '../content-type-visuals';
import { CategoriesService } from '../../core/services/categories.service';
import { Category } from '../../core/models/category';
import { categoryPath } from '../category-tree';

/**
 * Command-palette search, reachable from anywhere with Ctrl/⌘+K.
 *
 * The portal had no search UI at all: an operator with a customer waiting
 * could only browse the category grid by hand, even though the backend has
 * had a full trigram-indexed global search the whole time. This is the
 * shortest path between "customer asks a question" and "operator has the
 * article", which is the job this product exists to do.
 *
 * Design notes:
 * - A palette rather than a results page. The operator is mid-call and needs
 *   the answer over the top of whatever they were doing, not a navigation
 *   that loses their place.
 * - switchMap, so a slow response for "შე" can never overwrite the results
 *   for "შეთავაზება". The naive mergeMap version of this shows the wrong
 *   list at exactly the moment the user is typing fastest.
 * - Failures are shown. 25 call sites in this app swallow errors silently
 *   (audit FE-01/FE-02/FE-04); a search box that answers a failure with an
 *   empty list teaches the operator the knowledge base has nothing, which is
 *   worse than an error.
 * - takeUntilDestroyed on the stream. `takeUntilDestroyed` appears nowhere
 *   else in this app (audit FE-03), so this is also the convention going in.
 */
@Component({
  selector: 'app-global-search',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './global-search.html',
  host: {
    '(document:keydown)': 'onDocumentKeydown($event)',
  },
})
export class GlobalSearch {
  private readonly searchService = inject(SearchService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly categoriesService = inject(CategoriesService);

  private readonly input = viewChild<ElementRef<HTMLInputElement>>('searchInput');
  private readonly queries = new Subject<string>();

  protected readonly open = signal(false);
  protected readonly query = signal('');
  protected readonly hits = signal<SearchHit[]>([]);
  protected readonly loading = signal(false);
  protected readonly failed = signal(false);
  /** Index into hits() for keyboard selection; -1 = nothing highlighted. */
  protected readonly activeIndex = signal(-1);
  protected readonly categories = signal<Category[]>([]);

  protected readonly minLength = SearchService.MIN_QUERY_LENGTH;
  protected readonly tooShort = computed(
    () => this.query().trim().length > 0 && this.query().trim().length < this.minLength,
  );
  protected readonly empty = computed(
    () =>
      !this.loading() &&
      !this.failed() &&
      this.hits().length === 0 &&
      this.query().trim().length >= this.minLength,
  );

  constructor() {
    this.categoriesService
      .list()
      .subscribe({ next: (categories) => this.categories.set(categories) });
    /**
     * Put the caret in the palette's input when it opens.
     *
     * This used to be a queueMicrotask inside openPalette(), and it never
     * fired: the overlay is behind `@if (open())`, so the input element does
     * not exist until Angular renders, and a microtask runs before that --
     * `input()` was still undefined and focus() was called on nothing. The
     * palette opened with the caret nowhere, so Ctrl+K followed by typing
     * produced no text, which is the entire point of a command palette.
     * Caught by the E2E spec, not by anything that reads.
     *
     * An effect is the right tool because `input()` is itself a signal: this
     * runs once when open() flips, again when the viewChild resolves, and
     * that second run is the one that lands.
     */
    effect(() => {
      if (this.open()) {
        this.input()?.nativeElement.focus();
      }
    });

    this.queries
      .pipe(
        debounceTime(250),
        // Deliberately no distinctUntilChanged: it would swallow the retry
        // button, whose whole job is to re-issue the query that just failed.
        // debounceTime already collapses fast typing, and a genuinely repeated
        // query is answered from the backend's own 60s cache.
        tap(() => this.failed.set(false)),
        filter((q) => {
          const longEnough = q.trim().length >= this.minLength;
          if (!longEnough) {
            this.hits.set([]);
            this.loading.set(false);
          }
          return longEnough;
        }),
        tap(() => this.loading.set(true)),
        switchMap((q) =>
          this.searchService.searchHits(q.trim()).pipe(
            catchError(() => {
              this.failed.set(true);
              return of([] as SearchHit[]);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((hits) => {
        this.loading.set(false);
        this.hits.set(hits);
        this.activeIndex.set(hits.length > 0 ? 0 : -1);
      });
  }

  /** Ctrl/⌘+K toggles from anywhere; Escape closes. */
  onDocumentKeydown(event: KeyboardEvent): void {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.open() ? this.close() : this.openPalette();
      return;
    }
    if (event.key === 'Escape' && this.open()) {
      this.close();
    }
  }

  openPalette(): void {
    this.open.set(true);
    // Focus is handled by the effect in the constructor, not from here --
    // see it for why doing it inline never worked.
  }

  close(): void {
    this.open.set(false);
    this.query.set('');
    this.hits.set([]);
    this.loading.set(false);
    this.failed.set(false);
    this.activeIndex.set(-1);
  }

  onQueryInput(value: string): void {
    this.query.set(value);
    this.queries.next(value);
  }

  /** Arrow keys move the highlight; Enter opens it. Handled on the input so typing never loses focus. */
  onInputKeydown(event: KeyboardEvent): void {
    const total = this.hits().length;
    if (event.key === 'ArrowDown' && total > 0) {
      event.preventDefault();
      this.activeIndex.set((this.activeIndex() + 1) % total);
    } else if (event.key === 'ArrowUp' && total > 0) {
      event.preventDefault();
      this.activeIndex.set((this.activeIndex() - 1 + total) % total);
    } else if (event.key === 'Enter') {
      const hit = this.hits()[this.activeIndex()];
      if (hit) {
        event.preventDefault();
        this.openHit(hit);
      }
    }
  }

  openHit(hit: SearchHit): void {
    const route = detailRouteFor(hit.itemType, hit.id);
    if (route) {
      this.router.navigate(route);
    }
    this.close();
  }

  protected openAllResults(): void {
    const query = this.query().trim();
    this.close();
    this.router.navigate(['/info'], { queryParams: { q: query } });
  }

  protected contextFor(hit: SearchHit): string | null {
    if (hit.itemType !== 'article') {
      return hit.context;
    }
    const path = categoryPath(this.categories(), hit.categoryId ?? null)
      .map((category) => category.name)
      .join(' › ');
    const departments = (hit.targetDepartments ?? [])
      .filter((department) => department !== 'All')
      .join(', ');
    return [path || hit.context, departments].filter(Boolean).join(' · ') || null;
  }

  protected iconFor(hit: SearchHit): string {
    return iconForContentType(hit.itemType);
  }

  protected optionId(index: number): string {
    return `global-search-option-${index}`;
  }

  protected retry(): void {
    this.queries.next(this.query());
  }
}
