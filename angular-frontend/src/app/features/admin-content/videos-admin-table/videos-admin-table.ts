import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { VideosService } from '../../../core/services/videos.service';
import { VideoInstruction } from '../../../core/models/video';
import { VideoEditDrawer } from '../video-edit-drawer/video-edit-drawer';
import { ToastService } from '../../../core/notifications/toast.service';

/**
 * Port of #admin-videos-table-container (base-layout.html:1872-1889) +
 * fetchAndRenderAdminVideos. R5 adds the previously missing archive/trash
 * controls so the backend lifecycle is actually usable from the product.
 */
@Component({
  selector: 'app-videos-admin-table',
  standalone: true,
  imports: [TranslatePipe, VideoEditDrawer],
  templateUrl: './videos-admin-table.html'
})
export class VideosAdminTable {
  private readonly toast = inject(ToastService);
  private readonly videosService = inject(VideosService);
  private readonly translate = inject(TranslateService);

  protected readonly items = signal<VideoInstruction[]>([]);
  protected readonly loading = signal(true);
  protected readonly editingVideo = signal<VideoInstruction | { id: null } | null>(null);

  constructor() {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.videosService.list().subscribe({
      next: (data) => {
        this.items.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  protected departmentLabel(dept: string): string {
    return dept === 'All' ? this.translate.instant('content.all_departments') : dept;
  }

  protected openCreate(): void {
    this.editingVideo.set({ id: null });
  }

  protected openEdit(item: VideoInstruction): void {
    this.editingVideo.set(item);
  }

  protected closeDrawer(): void {
    this.editingVideo.set(null);
  }

  protected onSaved(): void {
    this.closeDrawer();
    this.load();
  }

  protected remove(item: VideoInstruction): void {
    if (!item.is_archived) {
      this.toast.error('ვიდეო ჯერ უნდა დაარქივოთ და მხოლოდ შემდეგ გადაიტანოთ სანაგვეში.');
      return;
    }
    if (!window.confirm(this.translate.instant('content.videos.confirm_delete'))) return;
    this.videosService.remove(item.id).subscribe({
      next: () => this.load(),
      error: (err: HttpErrorResponse) =>
        this.toast.error(err.error?.detail ?? this.translate.instant('content.videos.delete_error'))
    });
  }

  protected toggleArchive(item: VideoInstruction): void {
    const request = item.is_archived ? this.videosService.unarchive(item.id) : this.videosService.archive(item.id);
    request.subscribe({
      next: () => this.load(),
      error: (err: HttpErrorResponse) => this.toast.error(err.error?.detail ?? 'არქივის მოქმედება ვერ შესრულდა')
    });
  }
}
