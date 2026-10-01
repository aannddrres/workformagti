import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { RequiredMessage } from '../../shared/required-message';
import { CategoriesService } from '../../core/services/categories.service';
import { Category, CategoryRequest } from '../../core/models/category';
import { ConfirmService } from '../../core/notifications/confirm.service';
import { categoryIconClass } from '../../shared/category-visuals';
import { RowMenu } from '../../shared/row-menu/row-menu';

const PASTEL_COLOR_OPTIONS = [
  { value: 'general', labelKey: 'categories.color_general' },
  { value: 'mobile', labelKey: 'categories.color_mobile' },
  { value: 'fiber', labelKey: 'categories.color_fiber' },
  { value: 'iptv', labelKey: 'categories.color_iptv' },
  { value: 'hosting', labelKey: 'categories.color_hosting' },
  { value: 'digital', labelKey: 'categories.color_digital' },
  { value: 'billing', labelKey: 'categories.color_billing' },
  { value: 'service', labelKey: 'categories.color_service' },
  { value: 'loyalty', labelKey: 'categories.color_loyalty' },
  { value: 'technical', labelKey: 'categories.color_technical' }
];

/**
 * Port of #admin-categories (base-layout.html:2257-2340) + openCategoryCreateForm/
 * editCategory/submitCategoryForm/deleteCategory/renderCategoriesAdminTable
 * (app-core.js:4882-4947, frontend_api.js:2038-2122). Single-level parent/child
 * tree: top-level categories expand to show their children, with a warning row
 * for any "orphan" category whose parent was itself soft-deleted (still
 * possible since delete only reassigns articles, not child categories).
 *
 * <p>Deliberate tightening vs. Python: the parent-category dropdown excludes
 * the category currently being edited, so a top-level category can no longer
 * be set as its own parent (Python's dropdown didn't filter this -- doing so
 * silently vanishes the category from admin view, since it stops being a
 * "top" but was never any other top's child either). One-line guard, no
 * parity cost since self-parenting was never a valid choice.
 */
@Component({
  selector: 'app-admin-categories-page',
  standalone: true,
  imports: [TranslatePipe, RowMenu, RequiredMessage],
  templateUrl: './admin-categories-page.html'
})
export class AdminCategoriesPage {
  private readonly confirmService = inject(ConfirmService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly translate = inject(TranslateService);

  protected readonly pastelColorOptions = PASTEL_COLOR_OPTIONS;

  protected readonly categories = signal<Category[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly expanded = signal<Set<number>>(new Set());

  protected readonly formOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly name = signal('');
  protected readonly slug = signal('');
  protected readonly icon = signal('');
  protected readonly parentId = signal<number | null>(null);
  protected readonly pastelColor = signal('general');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly topLevelCategories = computed(() => this.categories().filter((c) => c.parent_id == null));

  protected readonly childrenByParent = computed(() => {
    const map = new Map<number, Category[]>();
    for (const c of this.categories()) {
      if (c.parent_id != null) {
        const list = map.get(c.parent_id) ?? [];
        list.push(c);
        map.set(c.parent_id, list);
      }
    }
    return map;
  });

  protected readonly orphanCategories = computed(() => {
    const ids = new Set(this.categories().map((c) => c.id));
    return this.categories().filter((c) => c.parent_id != null && !ids.has(c.parent_id));
  });

  protected readonly parentOptions = computed(() =>
    this.topLevelCategories().filter((c) => c.id !== this.editingId())
  );

  // The preview draws the icon operators will actually get, fallback included.
  // An empty field used to preview a layer icon while the tile showed the
  // name-based fallback instead.
  protected readonly iconPreviewClass = computed(
    () => 'fa-solid ' + categoryIconClass({ icon: this.icon() })
  );

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.categoriesService.listAdmin().subscribe({
      next: (data) => {
        this.categories.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      }
    });
  }

  refresh(): void {
    this.load();
  }

  isExpanded(id: number): boolean {
    return this.expanded().has(id);
  }

  toggleExpand(id: number): void {
    const next = new Set(this.expanded());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.expanded.set(next);
  }

  childCount(id: number): number {
    return this.childrenByParent().get(id)?.length ?? 0;
  }

  childrenOf(id: number): Category[] {
    return this.childrenByParent().get(id) ?? [];
  }

  chevronClass(id: number): string {
    const base = 'fa-solid fa-chevron-right text-xs transition-transform';
    return this.isExpanded(id) ? `${base} rotate-90` : base;
  }

  openCreateForm(): void {
    this.editingId.set(null);
    this.name.set('');
    this.slug.set('');
    this.icon.set('');
    this.parentId.set(null);
    this.pastelColor.set('general');
    this.saveError.set(null);
    this.formOpen.set(true);
  }

  openEditForm(category: Category): void {
    this.editingId.set(category.id);
    this.name.set(category.name);
    this.slug.set(category.slug ?? '');
    this.icon.set(category.icon ?? '');
    this.parentId.set(category.parent_id);
    this.pastelColor.set(category.pastel_color_class ?? 'general');
    this.saveError.set(null);
    this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
  }

  onNameInput(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  onSlugInput(event: Event): void {
    this.slug.set((event.target as HTMLInputElement).value);
  }

  onIconInput(event: Event): void {
    this.icon.set((event.target as HTMLInputElement).value);
  }

  onParentChange(event: Event): void {
    const val = (event.target as HTMLSelectElement).value;
    this.parentId.set(val ? Number(val) : null);
  }

  onPastelColorChange(event: Event): void {
    this.pastelColor.set((event.target as HTMLSelectElement).value);
  }

  submit(event: Event): void {
    event.preventDefault();
    const payload: CategoryRequest = {
      name: this.name().trim(),
      parent_id: this.parentId(),
      slug: this.slug().trim() || null,
      icon: this.icon().trim() || null,
      pastel_color_class: this.pastelColor() || 'general',
      is_active: true
    };

    this.saving.set(true);
    this.saveError.set(null);
    const id = this.editingId();
    const request = id != null ? this.categoriesService.update(id, payload) : this.categoriesService.create(payload);

    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.detail ?? this.translate.instant('categories.save_failed'));
      }
    });
  }

  async deleteCategory(category: Category): Promise<void> {
    if (!(await this.confirmService.ask({ message: this.translate.instant('categories.confirm_delete'), confirmLabel: this.translate.instant('categories.delete'), tone: 'danger' }))) {
      return;
    }
    this.categoriesService.remove(category.id).subscribe({
      next: () => this.load(),
      error: () => this.error.set(true)
    });
  }
}
