import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { VideosService } from '../../core/services/videos.service';
import { VideoInstruction } from '../../core/models/video';
import { toYoutubeThumbnailUrl } from '../../shared/youtube';
import { FavoriteStar } from '../../shared/favorite-star/favorite-star';

/**
 * Port of page-video (base-layout.html:1044-1061) + fetchAndRenderVideos.
 * Flat list/grid, title-only search (filterVideos, app-core.js:3797-3802)
 * -- no category grouping/filter exists on the reader-facing page in the
 * original. Admin inline-edit overlay is out of scope (no admin CRUD in
 * Angular yet).
 */
@Component({
  selector: 'app-videos-page',
  standalone: true,
  imports: [TranslatePipe, FavoriteStar],
  templateUrl: './videos-page.html'
})
export class VideosPage {
  private readonly videosService = inject(VideosService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);

  protected readonly videos = signal<VideoInstruction[] | null>(null);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly searchQuery = signal('');

  protected readonly filtered = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    const items = this.videos() ?? [];
    return q ? items.filter((v) => v.title.toLowerCase().includes(q)) : items;
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.videos.set(null);
    this.errorMessage.set(null);
    this.videosService.list().subscribe({
      next: (videos) => this.videos.set(videos),
      error: () => this.errorMessage.set(this.translate.instant('videos.page.load_error'))
    });
  }

  onSearchInput(value: string): void {
    this.searchQuery.set(value);
  }

  thumbnailFor(video: VideoInstruction): string {
    return toYoutubeThumbnailUrl(video.video_url) || '';
  }

  open(id: number): void {
    this.router.navigate(['/videos', id]);
  }
}
