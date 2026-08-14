import { Component, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ArticlesService } from '../../../core/services/articles.service';
import { CategoriesService } from '../../../core/services/categories.service';
import { QuizAdminService } from '../../../core/services/quiz-admin.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';
import { UploadService } from '../../../core/services/upload.service';
import { Article, ArticleRequest } from '../../../core/models/article';
import { Category } from '../../../core/models/category';
import { RichTextEditor } from '../../../shared/rich-text-editor/rich-text-editor';
import { QuizBuilder } from '../../../shared/quiz-builder/quiz-builder';

const DEPARTMENT_ORDER: { key: 'info' | 'tech' | 'office'; name: string }[] = [
  { key: 'info', name: 'საინფორმაციო' },
  { key: 'tech', name: 'ტექნიკური' },
  { key: 'office', name: 'ოფისი' }
];

/**
 * Port of the article slide-out drawer -- base-layout.html:2415-2618 (form)
 * + app-core.js's focusCreateForm/editArticle/submitArticleForm/
 * getArticleFormData/syncMandatoryFor/syncQuizQuestions. Full rewrite
 * against signals, not a line translation.
 *
 * <p>Deliberately not ported: autosave-while-typing (Python's initAutosave/
 * performAutosave) and the reader-facing "ვერსიების ისტორია"/"აუდიტი"
 * overlays from the article detail view -- both are separate features from
 * what this drawer needs to do (create/edit a working article). The
 * admin-only history+restore action (Python's "ისტორია" table-row item,
 * once deferred out of this same slice) now lives one level up, in
 * {@link ../admin-content-page.AdminContentPage}'s row menu -> {@link
 * ../article-history-modal/article-history-modal.ArticleHistoryModal}, not
 * inside this drawer. The live preview keeps the Desktop/Mobile toggle only.
 */
@Component({
  selector: 'app-article-edit-drawer',
  standalone: true,
  imports: [TranslatePipe, RichTextEditor, QuizBuilder],
  templateUrl: './article-edit-drawer.html'
})
export class ArticleEditDrawer {
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly quizAdminService = inject(QuizAdminService);
  private readonly requiredReadingService = inject(RequiredReadingService);
  private readonly uploadService = inject(UploadService);
  private readonly translate = inject(TranslateService);

  readonly articleId = input.required<number | null>();
  readonly closed = output<void>();
  readonly saved = output<void>();

  protected readonly richTextEditor = viewChild.required(RichTextEditor);
  protected readonly quizBuilder = viewChild(QuizBuilder);

  protected readonly categories = signal<Category[]>([]);
  protected readonly departmentOrder = DEPARTMENT_ORDER;

  protected readonly title = signal('');
  protected readonly categoryIdValue = signal<number | null>(null);
  protected readonly tags = signal('');
  protected readonly attachmentUrl = signal<string | null>(null);
  protected readonly attachmentFilename = signal<string | null>(null);
  protected readonly status = signal('published');
  protected readonly scheduledAt = signal('');
  protected readonly deptChecked = signal<Record<'info' | 'tech' | 'office', boolean>>({
    info: false,
    tech: false,
    office: false
  });
  protected readonly visibleTechInfo = signal(true);
  protected readonly visibleServiceCenter = signal(false);
  protected readonly isMandatory = signal(false);
  protected readonly dueDate = signal('');
  protected readonly quizEnabled = signal(false);
  protected readonly notifyOperators = signal(false);
  protected readonly audienceProfile = signal('all');

  protected readonly previewDevice = signal<'desktop' | 'mobile'>('desktop');
  protected readonly previewHtml = signal('');

  protected readonly uploading = signal(false);
  protected readonly dropzoneActive = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly departmentError = signal(false);
  protected readonly dueDateError = signal(false);

  constructor() {
    this.categoriesService.list().subscribe({ next: (data) => this.categories.set(data), error: () => {} });

    effect(() => {
      const id = this.articleId();
      if (id == null) {
        this.resetForCreate();
      } else {
        this.loadForEdit(id);
      }
    });
  }

  protected get isEditing(): boolean {
    return this.articleId() != null;
  }

  private resetForCreate(): void {
    this.title.set('');
    this.categoryIdValue.set(null);
    this.tags.set('');
    this.attachmentUrl.set(null);
    this.attachmentFilename.set(null);
    this.status.set('published');
    this.scheduledAt.set('');
    this.deptChecked.set({ info: false, tech: false, office: false });
    this.visibleTechInfo.set(true);
    this.visibleServiceCenter.set(false);
    this.isMandatory.set(false);
    this.dueDate.set('');
    this.quizEnabled.set(false);
    this.notifyOperators.set(false);
    this.audienceProfile.set('all');
    this.saveError.set(null);
    this.departmentError.set(false);
    this.dueDateError.set(false);
    queueMicrotask(() => {
      this.richTextEditor()?.clear();
      this.quizBuilder()?.setQuestions([]);
      this.previewHtml.set('');
    });
  }

  private loadForEdit(id: number): void {
    this.saveError.set(null);
    this.departmentError.set(false);
    this.dueDateError.set(false);
    this.articlesService.get(id).subscribe({
      next: (article) => {
        this.title.set(article.title);
        this.categoryIdValue.set(article.category_id);
        this.tags.set(article.tags ?? '');
        this.attachmentUrl.set(article.attachment_url);
        this.attachmentFilename.set(article.attachment_url ? article.attachment_url.split('/').pop() ?? null : null);
        this.status.set(article.status);
        this.scheduledAt.set(article.published_at ? toDatetimeLocal(article.published_at) : '');
        this.deptChecked.set({
          info: article.target_departments.includes('საინფორმაციო'),
          tech: article.target_departments.includes('ტექნიკური'),
          office: article.target_departments.includes('ოფისი')
        });
        this.visibleTechInfo.set(article.visible_to_tech_info);
        this.visibleServiceCenter.set(article.visible_to_service_center);
        this.quizEnabled.set(article.quiz_enabled);
        this.audienceProfile.set(article.audience_profile ?? 'all');
        this.notifyOperators.set(false);

        queueMicrotask(() => {
          this.richTextEditor()?.setHtml(article.content);
          this.previewHtml.set(article.content);
        });

        this.requiredReadingService.byItem('article', id).subscribe({
          next: (rr) => {
            this.isMandatory.set(!!rr);
            this.dueDate.set(rr?.due_date ? rr.due_date.slice(0, 10) : '');
          },
          error: () => {}
        });

        if (article.quiz_enabled) {
          this.quizAdminService.get(id).subscribe({
            next: (view) => queueMicrotask(() => this.quizBuilder()?.setQuestions(view.questions)),
            error: () => {}
          });
        } else {
          queueMicrotask(() => this.quizBuilder()?.setQuestions([]));
        }
      },
      error: () => this.saveError.set(this.translate.instant('content.articles.load_failed'))
    });
  }

  protected onEditorContentChange(html: string): void {
    this.previewHtml.set(html);
  }

  protected toggleDepartment(key: 'info' | 'tech' | 'office', checked: boolean): void {
    this.deptChecked.update((current) => ({ ...current, [key]: checked }));
  }

  protected dropzoneClass(): string {
    const base = 'rounded-xl border-2 border-dashed p-3 transition-colors';
    return this.dropzoneActive() ? `${base} border-brand bg-red-50 dark:bg-red-950/20` : `${base} border-gray-200 dark:border-zinc-700`;
  }

  protected previewFrameClass(): string {
    const base = 'bg-white dark:bg-zinc-900 rounded-xl shadow-sm border border-gray-200 dark:border-zinc-800 overflow-hidden transition-all';
    return this.previewDevice() === 'mobile' ? `${base} w-[360px]` : `${base} w-full`;
  }

  protected onDropzoneDragOver(e: DragEvent): void {
    e.preventDefault();
    this.dropzoneActive.set(true);
  }

  protected onDropzoneDragLeave(e: DragEvent): void {
    e.preventDefault();
    this.dropzoneActive.set(false);
  }

  protected onDropzoneDrop(e: DragEvent): void {
    e.preventDefault();
    this.dropzoneActive.set(false);
    const file = e.dataTransfer?.files?.[0];
    if (file) {
      this.uploadAttachment(file);
    }
  }

  protected onFileInputChange(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) {
      this.uploadAttachment(file);
    }
    (event.target as HTMLInputElement).value = '';
  }

  private uploadAttachment(file: File): void {
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

  protected removeAttachment(): void {
    this.attachmentUrl.set(null);
    this.attachmentFilename.set(null);
  }

  protected close(): void {
    this.closed.emit();
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const departments = this.departmentOrder.filter((d) => this.deptChecked()[d.key]).map((d) => d.name);
    if (departments.length === 0) {
      this.departmentError.set(true);
      return;
    }
    this.departmentError.set(false);

    if (this.isMandatory() && !this.dueDate()) {
      this.dueDateError.set(true);
      return;
    }
    this.dueDateError.set(false);

    let publishedAt: string | null = null;
    if (this.status() === 'scheduled' && this.scheduledAt()) {
      publishedAt = new Date(this.scheduledAt()).toISOString();
    }

    const payload: ArticleRequest = {
      title: this.title(),
      content: this.richTextEditor().getHtml(),
      category_id: this.categoryIdValue() ?? this.categories()[0]?.id ?? 1,
      tags: this.tags().trim() || null,
      target_departments: departments,
      status: this.status(),
      published_at: publishedAt,
      attachment_url: this.attachmentUrl(),
      audience_profile: this.audienceProfile(),
      visible_to_tech_info: this.visibleTechInfo(),
      visible_to_service_center: this.visibleServiceCenter(),
      is_draft: false,
      quiz_enabled: this.quizEnabled(),
      notify_operators: this.notifyOperators()
    };

    this.saving.set(true);
    this.saveError.set(null);
    const id = this.articleId();
    const request = id != null ? this.articlesService.update(id, payload) : this.articlesService.create(payload);

    request.subscribe({
      next: (article: Article) => this.afterSaved(article, departments[0]),
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.detail ?? this.translate.instant('content.articles.save_failed'));
      }
    });
  }

  private async afterSaved(article: Article, targetDepartment: string): Promise<void> {
    const dueIso = this.isMandatory() && this.dueDate() ? new Date(this.dueDate()).toISOString() : null;
    try {
      await this.requiredReadingService.sync('article', article.id, targetDepartment, this.isMandatory(), dueIso);
    } catch {
      /* non-fatal -- article itself saved successfully */
    }

    if (this.quizEnabled()) {
      const questions = this.quizBuilder()!.getQuestions();
      if (questions.length > 0) {
        this.quizAdminService.update(article.id, questions).subscribe({
          next: () => this.finishSave(),
          error: () => this.finishSave()
        });
        return;
      }
    }
    this.finishSave();
  }

  private finishSave(): void {
    this.saving.set(false);
    this.saved.emit();
  }
}

function toDatetimeLocal(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
