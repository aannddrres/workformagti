import { Component, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { VideosService } from '../../../core/services/videos.service';
import { CategoriesService } from '../../../core/services/categories.service';
import { UploadService } from '../../../core/services/upload.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';
import { VideoInstruction, VideoInstructionRequest } from '../../../core/models/video';
import { Category } from '../../../core/models/category';
import { DEPARTMENTS } from '../../../shared/user-roles';

/**
 * Port of the video slide-out drawer -- base-layout.html:2015-2088
 * (#admin-video-panel) + app-core.js's focusVideoForm/editVideo/
 * submitVideoForm/handleVideoUpload. Video URL is either typed directly
 * (e.g. a YouTube link) or replaced by an uploaded file's /uploads/ URL --
 * same single text field either way, exactly like Python's #video-url
 * input. Category is a free-text *name* here (video_category select is
 * populated from category *names*, not ids -- schema difference from
 * Article's category_id FK, confirmed in frontend_api.js:2011-2014).
 */
@Component({
  selector: 'app-video-edit-drawer',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './video-edit-drawer.html'
})
export class VideoEditDrawer {
  private readonly videosService = inject(VideosService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly uploadService = inject(UploadService);
  private readonly requiredReadingService = inject(RequiredReadingService);
  private readonly translate = inject(TranslateService);

  readonly videoId = input.required<number | null>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly departments = DEPARTMENTS;
  protected readonly categories = signal<Category[]>([]);

  protected readonly title = signal('');
  protected readonly videoUrl = signal('');
  protected readonly category = signal('');
  protected readonly targetDepartment = signal('All');
  protected readonly isMandatory = signal(false);
  protected readonly dueDate = signal('');

  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    this.categoriesService.list().subscribe({ next: (data) => this.categories.set(data), error: () => {} });

    effect(() => {
      const id = this.videoId();
      if (id == null) {
        this.resetForCreate();
      } else {
        this.loadForEdit(id);
      }
    });
  }

  protected get isEditing(): boolean {
    return this.videoId() != null;
  }

  private resetForCreate(): void {
    this.title.set('');
    this.videoUrl.set('');
    this.category.set('');
    this.targetDepartment.set('All');
    this.isMandatory.set(false);
    this.dueDate.set('');
    this.saveError.set(null);
  }

  private loadForEdit(id: number): void {
    this.saveError.set(null);
    this.videosService.list().subscribe({
      next: (videos) => {
        const video = videos.find((v) => v.id === id);
        if (!video) {
          this.saveError.set(this.translate.instant('content.videos.load_failed'));
          return;
        }
        this.applyVideo(video);
        this.requiredReadingService.byItem('video', id).subscribe({
          next: (rr) => {
            this.isMandatory.set(!!rr);
            this.dueDate.set(rr?.due_date ? rr.due_date.slice(0, 10) : '');
          },
          error: () => {}
        });
      },
      error: () => this.saveError.set(this.translate.instant('content.videos.load_failed'))
    });
  }

  private applyVideo(video: VideoInstruction): void {
    this.title.set(video.title);
    this.videoUrl.set(video.video_url);
    this.category.set(video.category ?? '');
    this.targetDepartment.set(video.target_department);
  }

  protected onFileInputChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.uploading.set(true);
      this.uploadService.upload(file).subscribe({
        next: (result) => {
          this.videoUrl.set(result.url);
          this.uploading.set(false);
        },
        error: () => {
          this.uploading.set(false);
          this.saveError.set(this.translate.instant('content.articles.upload_failed'));
        }
      });
    }
    (event.target as HTMLInputElement).value = '';
  }

  protected close(): void {
    this.closed.emit();
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const payload: VideoInstructionRequest = {
      title: this.title(),
      video_url: this.videoUrl(),
      category: this.category() || null,
      target_department: this.targetDepartment(),
      tags: null
    };

    this.saving.set(true);
    this.saveError.set(null);
    const id = this.videoId();
    const request = id != null ? this.videosService.update(id, payload) : this.videosService.create(payload);

    request.subscribe({
      next: async (video) => {
        const dueIso = this.isMandatory() && this.dueDate() ? new Date(this.dueDate()).toISOString() : null;
        try {
          await this.requiredReadingService.sync('video', video.id, this.targetDepartment(), this.isMandatory(), dueIso);
        } catch {
          /* non-fatal -- video itself saved successfully */
        }
        this.saving.set(false);
        this.saved.emit();
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.detail ?? this.translate.instant('content.videos.save_failed'));
      }
    });
  }
}
