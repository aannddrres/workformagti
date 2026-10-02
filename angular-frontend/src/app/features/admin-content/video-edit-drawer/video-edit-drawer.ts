import { Component, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { RequiredMessage } from '../../../shared/required-message';
import { VideosService } from '../../../core/services/videos.service';
import { CategoriesService } from '../../../core/services/categories.service';
import { UploadService } from '../../../core/services/upload.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';
import { VideoInstruction, VideoInstructionRequest } from '../../../core/models/video';
import { Category } from '../../../core/models/category';
import { DEPARTMENTS } from '../../../shared/user-roles';
import { ToastService } from '../../../core/notifications/toast.service';
import { PortalDialog } from '../../../shared/portal-dialog/portal-dialog';
import { tbilisiEndOfDay, tbilisiToday } from '../../../shared/ka-date';
import { DateField } from '../../../shared/date-field/date-field';
import { ConfirmService } from '../../../core/notifications/confirm.service';

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
  imports: [TranslatePipe, PortalDialog, DateField, RequiredMessage],
  templateUrl: './video-edit-drawer.html'
})
export class VideoEditDrawer {
  private readonly confirmService = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  /** True when the mandatory-reading flag could not be read; the unchecked box is not a fact. */
  protected readonly mandatoryUnknown = signal(false);
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
  /** The earliest deadline the picker offers; the server refuses an earlier one. */
  protected readonly today = tbilisiToday();

  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly dueDateError = signal(false);
  /** FE-04: an empty dropdown must not be indistinguishable from a failed load. */
  protected readonly categoriesFailed = signal(false);

  constructor() {
    this.loadCategories();

    effect(() => {
      const id = this.videoId();
      if (id == null) {
        this.resetForCreate();
      } else {
        this.loadForEdit(id);
      }
    });
  }

  /** Retried from the template, so a transient failure costs one click, not a reopened drawer. */
  protected loadCategories(): void {
    this.categoriesService.list().subscribe({
      next: (data) => {
        this.categories.set(data);
        this.categoriesFailed.set(false);
      },
      error: () => this.categoriesFailed.set(true)
    });
  }

  protected get isEditing(): boolean {
    return this.videoId() != null;
  }

  private resetForCreate(): void {
    this.dirty.set(false);
    this.title.set('');
    this.videoUrl.set('');
    this.category.set('');
    this.targetDepartment.set('All');
    this.isMandatory.set(false);
    this.dueDate.set('');
    this.saveError.set(null);
    this.dueDateError.set(false);
  }

  private loadForEdit(id: number): void {
    this.saveError.set(null);
    this.dueDateError.set(false);
    this.videosService.list().subscribe({
      next: (videos) => {
        const video = videos.find((v) => v.id === id);
        if (!video) {
          this.saveError.set(this.translate.instant('content.videos.load_failed'));
          return;
        }
        this.applyVideo(video);
        // Same trap as the article drawer: a silent failure here renders the
        // checkbox UNCHECKED, which the editor cannot tell from "not mandatory",
        // so saving would clear a real compliance obligation.
        this.requiredReadingService.byItem('video', id).subscribe({
          next: (rr) => {
            this.isMandatory.set(!!rr);
            this.dueDate.set(rr?.due_date ? rr.due_date.slice(0, 10) : '');
            this.mandatoryUnknown.set(false);
          },
          error: () => {
            this.mandatoryUnknown.set(true);
            this.toast.error(this.translate.instant('content.articles.mandatory_load_error'));
          }
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
    this.dirty.set(false);
  }

  protected onFileInputChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.uploading.set(true);
      this.uploadService.upload(file).subscribe({
        next: (result) => {
          this.videoUrl.set(result.url);
          this.uploading.set(false);
          this.dirty.set(true);
        },
        error: () => {
          this.uploading.set(false);
          this.saveError.set(this.translate.instant('content.articles.upload_failed'));
        }
      });
    }
    (event.target as HTMLInputElement).value = '';
  }

  protected async close(): Promise<void> {
    if (this.dirty() && !(await this.confirmService.ask({ message: 'შეუნახავი ცვლილებები დაიკარგება. გსურთ დახურვა?', confirmLabel: 'დახურვა შენახვის გარეშე', tone: 'danger' }))) {
      return;
    }
    this.closed.emit();
  }

  protected markDirty(): void {
    this.dirty.set(true);
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.isMandatory() && !this.dueDate()) {
      this.dueDateError.set(true);
      return;
    }
    this.dueDateError.set(false);

    const payload: VideoInstructionRequest = {
      title: this.title(),
      video_url: this.videoUrl(),
      category: this.category() || null,
      target_department: this.targetDepartment(),
      tags: null
    };

    this.saving.set(true);
    this.saveError.set(null);
    const dueIso = this.isMandatory() && this.dueDate() ? tbilisiEndOfDay(this.dueDate()) : null;
    const command = {
      video: payload,
      mandatory: this.isMandatory(),
      due_date: dueIso,
      target_department: this.targetDepartment()
    };
    const id = this.videoId();
    const request = id != null
      ? this.videosService.updateCommand(id, command)
      : this.videosService.createCommand(command);

    request.subscribe({
      next: () => {
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
