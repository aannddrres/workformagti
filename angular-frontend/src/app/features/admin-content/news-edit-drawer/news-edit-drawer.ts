import { Component, effect, inject, input, output, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { NewsService } from '../../../core/services/news.service';
import { UploadService } from '../../../core/services/upload.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';
import { News, NewsRequest } from '../../../core/models/news';
import { DEPARTMENTS } from '../../../shared/user-roles';
import { ToastService } from '../../../core/notifications/toast.service';

/**
 * Port of the news slide-out drawer -- base-layout.html:1912-2013
 * (#admin-news-panel) + app-core.js's focusNewsForm/editNews/
 * submitNewsForm/syncMandatoryFor. Full rewrite against signals.
 * Simpler than the article drawer: plain textarea (no rich-text editor
 * in the real Python form either), single-select department (incl. "All"),
 * one attachment, mandatory-reading toggle. No quiz, no live preview, no
 * status/scheduling -- none of those exist on the real News form.
 */
@Component({
  selector: 'app-news-edit-drawer',
  standalone: true,
  imports: [TranslatePipe],
  templateUrl: './news-edit-drawer.html'
})
export class NewsEditDrawer {
  private readonly toast = inject(ToastService);
  /** True when the mandatory-reading flag could not be read; the unchecked box is not a fact. */
  protected readonly mandatoryUnknown = signal(false);
  private readonly newsService = inject(NewsService);
  private readonly uploadService = inject(UploadService);
  private readonly requiredReadingService = inject(RequiredReadingService);
  private readonly translate = inject(TranslateService);

  readonly newsId = input.required<number | null>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly departments = DEPARTMENTS;

  protected readonly title = signal('');
  protected readonly content = signal('');
  protected readonly targetDepartment = signal('All');
  protected readonly attachmentUrl = signal<string | null>(null);
  protected readonly attachmentFilename = signal<string | null>(null);
  protected readonly visibleTechInfo = signal(true);
  protected readonly visibleServiceCenter = signal(false);
  protected readonly isMandatory = signal(false);
  protected readonly dueDate = signal('');

  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly dueDateError = signal(false);

  constructor() {
    effect(() => {
      const id = this.newsId();
      if (id == null) {
        this.resetForCreate();
      } else {
        this.loadForEdit(id);
      }
    });
  }

  protected get isEditing(): boolean {
    return this.newsId() != null;
  }

  private resetForCreate(): void {
    this.title.set('');
    this.content.set('');
    this.targetDepartment.set('All');
    this.attachmentUrl.set(null);
    this.attachmentFilename.set(null);
    this.visibleTechInfo.set(true);
    this.visibleServiceCenter.set(false);
    this.isMandatory.set(false);
    this.dueDate.set('');
    this.saveError.set(null);
    this.dueDateError.set(false);
  }

  private loadForEdit(id: number): void {
    this.saveError.set(null);
    this.dueDateError.set(false);
    this.newsService.get(id).subscribe({
      next: (news: News) => {
        this.title.set(news.title);
        this.content.set(news.content);
        this.targetDepartment.set(news.target_department);
        this.attachmentUrl.set(news.attachment_url);
        this.attachmentFilename.set(news.attachment_url ? news.attachment_url.split('/').pop() ?? null : null);
        this.visibleTechInfo.set(news.visible_to_tech_info);
        this.visibleServiceCenter.set(news.visible_to_service_center);

        // Same trap as the article drawer: a silent failure here renders the
        // checkbox UNCHECKED, which the editor cannot tell from "not mandatory",
        // so saving would clear a real compliance obligation.
        this.requiredReadingService.byItem('news', id).subscribe({
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
      error: () => this.saveError.set(this.translate.instant('content.news.load_failed'))
    });
  }

  protected onFileInputChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.uploading.set(true);
      this.uploadService.upload(file).subscribe({
        next: (result) => {
          this.attachmentUrl.set(result.url);
          this.attachmentFilename.set(result.filename);
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

  protected removeAttachment(): void {
    this.attachmentUrl.set(null);
    this.attachmentFilename.set(null);
  }

  protected close(): void {
    this.closed.emit();
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (this.isMandatory() && !this.dueDate()) {
      this.dueDateError.set(true);
      return;
    }
    this.dueDateError.set(false);

    const payload: NewsRequest = {
      title: this.title(),
      content: this.content(),
      target_department: this.targetDepartment(),
      attachment_url: this.attachmentUrl(),
      visible_to_tech_info: this.visibleTechInfo(),
      visible_to_service_center: this.visibleServiceCenter()
    };

    this.saving.set(true);
    this.saveError.set(null);
    const id = this.newsId();
    const request = id != null ? this.newsService.update(id, payload) : this.newsService.create(payload);

    request.subscribe({
      next: async (news) => {
        const dueIso = this.isMandatory() && this.dueDate() ? new Date(this.dueDate()).toISOString() : null;
        try {
          await this.requiredReadingService.sync('news', news.id, this.targetDepartment(), this.isMandatory(), dueIso);
        } catch {
          /* non-fatal -- news item itself saved successfully */
        }
        this.saving.set(false);
        this.saved.emit();
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.detail ?? this.translate.instant('content.news.save_failed'));
      }
    });
  }
}
