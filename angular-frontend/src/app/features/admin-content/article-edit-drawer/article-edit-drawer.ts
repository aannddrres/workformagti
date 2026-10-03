import { Component, DestroyRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { ArticleDraft, clearDraft, draftKey, readDraft, writeDraft } from './article-draft-store';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { RequiredMessage } from '../../../shared/required-message';
import { ArticlesService } from '../../../core/services/articles.service';
import { CategoriesService } from '../../../core/services/categories.service';
import { QuizAdminService } from '../../../core/services/quiz-admin.service';
import { RequiredReadingService } from '../../../core/services/required-reading.service';
import { UploadService } from '../../../core/services/upload.service';
import { ArticleCommandRequest, ArticleRequest } from '../../../core/models/article';
import { MandatoryAddressees, MandatoryRefusal } from '../../../core/models/required-reading';
import { Category } from '../../../core/models/category';
import { RichTextEditor } from '../../../shared/rich-text-editor/rich-text-editor';
import { QuizBuilder } from '../../../shared/quiz-builder/quiz-builder';
import { ToastService } from '../../../core/notifications/toast.service';
import { PortalDialog } from '../../../shared/portal-dialog/portal-dialog';
import { DateField } from '../../../shared/date-field/date-field';
import { formatKaDateTime, isoToTbilisiLocal, tbilisiEndOfDay, tbilisiLocalToIso, tbilisiToday } from '../../../shared/ka-date';
import { ConfirmService } from '../../../core/notifications/confirm.service';
import { articleReach, lossLines, mandatoryLoss } from '../../../shared/mandatory-reach';

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
  imports: [TranslatePipe, RichTextEditor, QuizBuilder, PortalDialog, DateField, RequiredMessage],
  templateUrl: './article-edit-drawer.html'
})
export class ArticleEditDrawer {
  private readonly confirmService = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  /** True when the mandatory-reading flag could not be read; the UI must not present the unchecked box as fact. */
  protected readonly mandatoryUnknown = signal(false);
  private readonly articlesService = inject(ArticlesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly quizAdminService = inject(QuizAdminService);
  private readonly requiredReadingService = inject(RequiredReadingService);
  private readonly uploadService = inject(UploadService);
  private readonly translate = inject(TranslateService);
  private readonly auth = inject(AuthService);

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
  /** A new article starts as a draft (კ20): publishing is a choice, not a default. Editing loads the article's own. */
  protected readonly status = signal('draft');
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
  /** The earliest deadline the picker offers; the server refuses an earlier one. */
  protected readonly today = tbilisiToday();
  /** The article was mandatory when opened: its obligation may be paused, not only created. */
  protected readonly wasMandatory = signal(false);
  /** Who the obligation binds now (PO-40), for the warning before a change takes it away; null if unknown. */
  private readonly mandatoryAudience = signal<MandatoryAddressees | null>(null);
  /** When readers can open the article as the form stands: now, at its schedule, or not at all. */
  protected readonly mandatoryReach = computed(() => articleReach(this.status(), this.scheduledAt() ? tbilisiLocalToIso(this.scheduledAt()) : null));
  /**
   * PO-40: nobody may be bound to what they cannot open, so a draft cannot
   * become mandatory. One already mandatory keeps the box, to be unticked or
   * left paused until it is published again.
   */
  protected readonly mandatoryAllowed = computed(() => this.mandatoryReach() !== 'never' || this.wasMandatory());
  protected readonly dueBeforePublication = signal(false);
  protected readonly quizEnabled = signal(false);
  protected readonly audienceProfile = signal('all');

  protected readonly previewDevice = signal<'desktop' | 'mobile'>('desktop');
  protected readonly previewHtml = signal('');

  protected readonly uploading = signal(false);
  protected readonly dropzoneActive = signal(false);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  /** Text this browser kept from an earlier, unsaved session in this drawer (article-draft-store.ts). */
  protected readonly pendingDraft = signal<ArticleDraft | null>(null);
  private draftTimer: ReturnType<typeof setTimeout> | null = null;
  /** The lock_version this form was loaded at, sent back so a newer edit is not overwritten. */
  private readonly loadedLockVersion = signal<number | null>(null);
  protected readonly saveError = signal<string | null>(null);
  /** The departments a refused assignment would have missed, under the error (PO-40). */
  protected readonly saveErrorDetails = signal<string[]>([]);
  protected readonly departmentError = signal(false);
  protected readonly dueDateError = signal(false);
  /**
   * FE-04: an empty category dropdown used to be indistinguishable from a
   * failed load. Category is a required field, so silently offering none
   * blocks the save with no explanation of why.
   */
  protected readonly categoriesFailed = signal(false);
  /** Same distinction for the quiz: "this article has no questions" vs "we could not fetch them". */
  protected readonly quizLoadFailed = signal(false);

  constructor() {
    this.loadCategories();
    inject(DestroyRef).onDestroy(() => this.flushDraft());

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

  /** Retried from the template, so a transient failure is one click to recover from rather than a reopened drawer. */
  protected loadCategories(): void {
    this.categoriesService.list().subscribe({
      next: (data) => {
        this.categories.set(data);
        this.categoriesFailed.set(false);
      },
      error: () => this.categoriesFailed.set(true)
    });
  }

  private resetForCreate(): void {
    this.dirty.set(false);
    this.loadedLockVersion.set(null);
    this.title.set('');
    this.categoryIdValue.set(null);
    this.tags.set('');
    this.attachmentUrl.set(null);
    this.attachmentFilename.set(null);
    this.status.set('draft');
    this.scheduledAt.set('');
    this.deptChecked.set({ info: false, tech: false, office: false });
    this.visibleTechInfo.set(true);
    this.visibleServiceCenter.set(false);
    this.isMandatory.set(false);
    this.dueDate.set('');
    this.wasMandatory.set(false);
    this.mandatoryAudience.set(null);
    this.dueBeforePublication.set(false);
    this.saveErrorDetails.set([]);
    this.quizEnabled.set(false);
    this.audienceProfile.set('all');
    this.saveError.set(null);
    this.departmentError.set(false);
    this.dueDateError.set(false);
    queueMicrotask(() => {
      this.richTextEditor()?.clear();
      this.quizBuilder()?.setQuestions([]);
      this.previewHtml.set('');
      this.offerKeptDraft('', '');
    });
  }

  private loadForEdit(id: number): void {
    this.saveError.set(null);
    this.saveErrorDetails.set([]);
    this.wasMandatory.set(false);
    this.mandatoryAudience.set(null);
    this.dueBeforePublication.set(false);
    this.departmentError.set(false);
    this.dueDateError.set(false);
    this.articlesService.get(id).subscribe({
      next: (article) => {
        this.loadedLockVersion.set(article.lock_version);
        this.title.set(article.title);
        this.categoryIdValue.set(article.category_id);
        this.tags.set(article.tags ?? '');
        this.attachmentUrl.set(article.attachment_url);
        this.attachmentFilename.set(article.attachment_url ? article.attachment_url.split('/').pop() ?? null : null);
        this.status.set(article.status);
        this.scheduledAt.set(article.published_at ? isoToTbilisiLocal(article.published_at) : '');
        this.deptChecked.set({
          info: article.target_departments.includes('საინფორმაციო'),
          tech: article.target_departments.includes('ტექნიკური'),
          office: article.target_departments.includes('ოფისი')
        });
        this.visibleTechInfo.set(article.visible_to_tech_info);
        this.visibleServiceCenter.set(article.visible_to_service_center);
        this.quizEnabled.set(article.quiz_enabled);
        this.audienceProfile.set(article.audience_profile ?? 'all');

        queueMicrotask(() => {
          this.richTextEditor()?.setHtml(article.content);
          this.previewHtml.set(article.content);
          this.dirty.set(false);
          this.offerKeptDraft(article.title, article.content);
        });

        // If this lookup fails silently the checkbox renders UNCHECKED, which
        // is indistinguishable from "not a mandatory reading" -- so saving the
        // article would clear a real compliance obligation that the editor
        // never knowingly touched. Surfaced, and the flag is left untouched
        // rather than defaulted to false.
        this.requiredReadingService.byItem('article', id).subscribe({
          next: (rr) => {
            this.isMandatory.set(!!rr);
            this.wasMandatory.set(!!rr);
            this.dueDate.set(rr?.due_date ? rr.due_date.slice(0, 10) : '');
            this.mandatoryUnknown.set(false);
            if (rr) {
              // Without it the save still works; it only cannot say who a change would release.
              this.requiredReadingService.addressees('article', id).subscribe({
                next: (audience) => this.mandatoryAudience.set(audience),
                error: () => this.mandatoryAudience.set(null)
              });
            }
          },
          error: () => {
            this.mandatoryUnknown.set(true);
            this.toast.error(this.translate.instant('content.articles.mandatory_load_error'));
          }
        });

        if (article.quiz_enabled) {
          this.quizAdminService.get(id).subscribe({
            next: (view) => {
              this.quizLoadFailed.set(false);
              queueMicrotask(() => this.quizBuilder()?.setQuestions(view.questions));
            },
            // An empty builder for an article that HAS a quiz reads as "no
            // questions yet". An admin who then adds one replaces the real
            // set -- afterSaved only skips the update when the builder is
            // empty, so the moment they type anything the old questions are
            // gone.
            error: () => {
              this.quizLoadFailed.set(true);
              this.toast.error(this.translate.instant('content.articles.quiz_load_error'));
            }
          });
        } else {
          this.quizLoadFailed.set(false);
          queueMicrotask(() => this.quizBuilder()?.setQuestions([]));
        }
      },
      error: () => this.saveError.set(this.translate.instant('content.articles.load_failed'))
    });
  }

  protected onEditorContentChange(html: string): void {
    this.previewHtml.set(html);
    this.dirty.set(true);
    this.scheduleDraft();
  }

  protected markDirty(): void {
    this.dirty.set(true);
    this.scheduleDraft();
  }

  protected restoreDraft(): void {
    const draft = this.pendingDraft();
    if (!draft) return;
    this.title.set(draft.title);
    this.richTextEditor().setHtml(draft.content);
    this.previewHtml.set(draft.content);
    this.pendingDraft.set(null);
    this.dirty.set(true);
  }

  protected discardDraft(): void {
    this.pendingDraft.set(null);
    this.forgetDraft();
  }

  protected draftTime(draft: ArticleDraft): string {
    return formatKaDateTime(new Date(draft.savedAt).toISOString());
  }

  private draftStorageKey(): string | null {
    const email = this.auth.currentUser()?.email;
    return email ? draftKey(email, this.articleId()) : null;
  }

  private offerKeptDraft(loadedTitle: string, loadedContent: string): void {
    const key = this.draftStorageKey();
    const draft = key ? readDraft(key) : null;
    const differs = draft != null && (draft.content !== loadedContent || draft.title !== loadedTitle)
      && (draft.content.replace(/<[^>]*>/g, '').trim() !== '' || draft.title.trim() !== '');
    this.pendingDraft.set(differs ? draft : null);
  }

  /** A second after the last keystroke, not on every one. */
  private scheduleDraft(): void {
    if (this.draftTimer) clearTimeout(this.draftTimer);
    this.draftTimer = setTimeout(() => this.flushDraft(), 1000);
  }

  private flushDraft(): void {
    if (this.draftTimer) clearTimeout(this.draftTimer);
    this.draftTimer = null;
    // Not while an earlier draft is still on offer: writing now would replace it before it was answered.
    const key = this.draftStorageKey();
    if (!key || !this.dirty() || this.pendingDraft()) return;
    writeDraft(key, { title: this.title(), content: this.previewHtml(), savedAt: Date.now() });
  }

  private forgetDraft(): void {
    if (this.draftTimer) clearTimeout(this.draftTimer);
    this.draftTimer = null;
    const key = this.draftStorageKey();
    if (key) clearDraft(key);
  }

  protected toggleDepartment(key: 'info' | 'tech' | 'office', checked: boolean): void {
    this.deptChecked.update((current) => ({ ...current, [key]: checked }));
    this.dirty.set(true);
  }

  protected dropzoneClass(): string {
    const base = 'rounded-lg border-2 border-dashed p-3 transition-colors';
    return this.dropzoneActive() ? `${base} border-brand-accent bg-red-50 dark:bg-red-950/20` : `${base} border-slate-200 dark:border-slate-700`;
  }

  protected previewFrameClass(): string {
    const base = 'bg-white dark:bg-slate-900 rounded-lg shadow-e1 border border-slate-200 dark:border-slate-800 overflow-hidden transition';
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
        this.dirty.set(true);
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
    this.dirty.set(true);
  }

  protected async close(): Promise<void> {
    if (this.dirty() && !(await this.confirmService.ask({ message: 'შეუნახავი ცვლილებები დაიკარგება. გსურთ დახურვა?', confirmLabel: 'დახურვა შენახვის გარეშე', tone: 'danger' }))) {
      return;
    }
    // Closed on purpose, so the kept copy goes too -- unless it was never answered.
    if (!this.pendingDraft()) {
      this.forgetDraft();
    }
    this.dirty.set(false);
    this.closed.emit();
  }

  protected async submit(event: Event): Promise<void> {
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

    // PO-40: made mandatory in advance, it comes into force at publication;
    // a deadline before then would be born overdue. The server refuses it too.
    const dueBeforePublication = this.isMandatory() && this.mandatoryReach() === 'later'
      && new Date(tbilisiEndOfDay(this.dueDate())) <= new Date(tbilisiLocalToIso(this.scheduledAt()));
    this.dueBeforePublication.set(dueBeforePublication);
    if (dueBeforePublication) {
      return;
    }
    if (!(await this.confirmMandatoryLoss(departments))) {
      return;
    }

    let publishedAt: string | null = null;
    if (this.status() === 'scheduled' && this.scheduledAt()) {
      // PO-58: the time typed is Tbilisi time, like every deadline -- not
      // whatever zone this computer's clock happens to be set to.
      publishedAt = tbilisiLocalToIso(this.scheduledAt());
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
      lock_version: this.loadedLockVersion()
    };

    this.saving.set(true);
    this.saveError.set(null);
    this.saveErrorDetails.set([]);
    const questions = this.quizEnabled() ? this.quizBuilder()?.getQuestions() ?? [] : [];
    const command: ArticleCommandRequest = {
      article: payload,
      mandatory: this.isMandatory(),
      due_date: this.isMandatory() && this.dueDate() ? tbilisiEndOfDay(this.dueDate()) : null,
      target_department: departments[0],
      quiz: this.quizEnabled() ? { questions } : null
    };
    const id = this.articleId();
    const request = id != null
      ? this.articlesService.updateCommand(id, command)
      : this.articlesService.createCommand(command);

    request.subscribe({
      next: () => this.finishSave(),
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(err?.error?.detail ?? this.translate.instant('content.articles.save_failed'));
        const refusal = err?.error as MandatoryRefusal | undefined;
        this.saveErrorDetails.set((refusal?.blocked_departments ?? []).map(
          ({ department, count }) => `${department} — ${count}`));
      }
    });
  }

  /**
   * PO-40: before a change takes a mandatory article out of someone's reach
   * -- unpublishing it, scheduling it ahead, dropping their department -- say
   * who, and let the editor stop. Nothing to ask when nothing is lost, when the
   * obligation is being removed outright, or when who it binds is unknown.
   */
  private async confirmMandatoryLoss(departments: string[]): Promise<boolean> {
    const audience = this.mandatoryAudience();
    if (!this.wasMandatory() || !this.isMandatory() || !audience) {
      return true;
    }
    const reach = this.mandatoryReach();
    const loss = mandatoryLoss(audience, { reach, departments });
    if (loss.total === 0) {
      return true;
    }
    const key = reach === 'never' ? 'content.articles.mandatory_loss_unpublished'
      : reach === 'later' ? 'content.articles.mandatory_loss_scheduled'
      : 'content.articles.mandatory_loss_departments';
    let message = this.translate.instant(key, { count: loss.total });
    if (loss.confirmed > 0) {
      message += ' ' + this.translate.instant('content.articles.mandatory_loss_confirmed', { count: loss.confirmed });
    }
    return this.confirmService.ask({
      title: this.translate.instant('content.articles.mandatory_loss_title'),
      message,
      details: lossLines(loss, (count) => this.translate.instant('content.articles.mandatory_loss_more', { count })),
      confirmLabel: this.translate.instant('content.articles.mandatory_loss_confirm'),
      tone: 'danger'
    });
  }

  private finishSave(): void {
    this.saving.set(false);
    this.forgetDraft();
    this.dirty.set(false);
    this.saved.emit();
  }
}
