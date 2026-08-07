import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Location } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { map, switchMap } from 'rxjs';
import { TranslatePipe } from '@ngx-translate/core';
import { VideosService } from '../../core/services/videos.service';
import { VideoInstruction } from '../../core/models/video';
import { toYoutubeEmbedUrl } from '../../shared/youtube';

/**
 * Port of the video detail modal (#video-detail-modal, app-core.js:3804-3907)
 * as a routed page instead of a modal, matching this port's established
 * detail-route pattern. GET /api/videos/{id} doesn't exist on the backend
 * -- the original resolves a clicked video from its own full-list cache
 * (Store.videos), falling back to a fresh GET /api/videos if not cached;
 * this page does the same (fetch the list, find by id) since that's the
 * real available API shape, not a compromise specific to this port.
 * View-count POST fires once the video resolves, mirroring viewVideo's
 * "log after opening, don't block on it" timing.
 */
@Component({
  selector: 'app-video-detail-page',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './video-detail-page.html'
})
export class VideoDetailPage {
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly videosService = inject(VideosService);
  private readonly sanitizer = inject(DomSanitizer);

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly video = signal<VideoInstruction | null>(null);

  protected readonly embedUrl = computed<SafeResourceUrl | null>(() => {
    const v = this.video();
    const embed = v ? toYoutubeEmbedUrl(v.video_url) : null;
    return embed ? this.sanitizer.bypassSecurityTrustResourceUrl(embed) : null;
  });

  constructor() {
    this.route.paramMap
      .pipe(
        map((params) => Number(params.get('id'))),
        switchMap((id) => this.videosService.list().pipe(map((videos) => ({ id, videos }))))
      )
      .subscribe({
        next: ({ id, videos }) => {
          const found = videos.find((v) => v.id === id) ?? null;
          this.video.set(found);
          this.notFound.set(!found);
          this.loading.set(false);
          if (found) {
            this.videosService.logView(id);
          }
        },
        error: () => {
          this.notFound.set(true);
          this.loading.set(false);
        }
      });
  }

  goBack(): void {
    this.location.back();
  }
}
