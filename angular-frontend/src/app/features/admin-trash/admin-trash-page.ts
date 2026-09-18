import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { ContentTrashItem, TrashItemType } from '../../core/models/content-trash';
import { UserProfileService } from '../../core/auth/user-profile.service';
import { ContentTrashService } from '../../core/services/content-trash.service';
import { createTableSort } from '../../shared/table-sort';
import { ConfirmService } from '../../core/notifications/confirm.service';

@Component({
  selector: 'app-admin-trash-page',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './admin-trash-page.html'
})
export class AdminTrashPage {
  private readonly confirmService = inject(ConfirmService);
  private readonly trashService = inject(ContentTrashService);
  private readonly profiles = inject(UserProfileService);

  protected readonly items = signal<ContentTrashItem[]>([]);
  protected readonly sort = createTableSort<ContentTrashItem>({
    title: (item) => item.title,
    trashed: (item) => item.trashed_at,
    purge: (item) => item.purge_after
  });
  protected readonly sortedItems = computed(() => this.sort.sort(this.items()));
  protected readonly loading = signal(true);
  protected readonly busyKey = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly isSystemAdmin = computed(() => this.profiles.access()?.role === 'admin');

  constructor() {
    this.profiles.ensureAccessLoaded().subscribe();
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.trashService.list().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(err.error?.detail ?? 'სანაგვის ჩატვირთვა ვერ მოხერხდა');
        this.loading.set(false);
      }
    });
  }

  protected async restore(item: ContentTrashItem): Promise<void> {
    if (!(await this.confirmService.ask(`აღვადგინოთ „${item.title}“? მასალა არქივში დაბრუნდება.`))) return;
    this.run(item, this.trashService.restore(item.item_type, item.item_id));
  }

  protected async purge(item: ContentTrashItem): Promise<void> {
    if (!this.canPurge(item)) return;
    if (!(await this.confirmService.ask({ message: `საბოლოოდ წავშალოთ „${item.title}“? კონტენტის payload ვეღარ აღდგება.`, tone: 'danger' }))) return;
    this.run(item, this.trashService.purge(item.item_type, item.item_id));
  }

  protected canPurge(item: ContentTrashItem): boolean {
    return this.isSystemAdmin() && !item.legal_hold && new Date(item.purge_after).getTime() <= Date.now();
  }

  protected itemTypeLabel(type: TrashItemType): string {
    return { article: 'სტატია', news: 'სიახლე', video: 'ვიდეო' }[type];
  }

  protected busy(item: ContentTrashItem): boolean {
    return this.busyKey() === `${item.item_type}:${item.item_id}`;
  }

  protected dismissError(): void {
    this.error.set(null);
  }

  private run(item: ContentTrashItem, request: ReturnType<ContentTrashService['restore']>): void {
    this.busyKey.set(`${item.item_type}:${item.item_id}`);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.busyKey.set(null);
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.busyKey.set(null);
        this.error.set(err.error?.detail ?? 'მოქმედება ვერ შესრულდა');
      }
    });
  }
}
