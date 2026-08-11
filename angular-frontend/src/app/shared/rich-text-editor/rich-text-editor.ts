import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  inject,
  output,
  signal,
  viewChild
} from '@angular/core';
import Quill from 'quill';
import DOMPurify from 'dompurify';
import { UploadService } from '../../core/services/upload.service';

interface SlashCommand {
  label: string;
  format: string;
  value: string | number | boolean;
}

const SLASH_COMMANDS: SlashCommand[] = [
  { label: 'H1 სათაური', format: 'header', value: 1 },
  { label: 'H2 ქვესათაური', format: 'header', value: 2 },
  { label: 'ციტატის ბლოკი', format: 'blockquote', value: true }
];

interface MarkdownLineRule {
  pattern: RegExp;
  format: string;
  value: string | number | boolean;
}

const MARKDOWN_LINE_RULES: MarkdownLineRule[] = [
  { pattern: /^## $/, format: 'header', value: 2 },
  { pattern: /^# $/, format: 'header', value: 1 },
  { pattern: /^> $/, format: 'blockquote', value: true }
];

/**
 * Wraps raw Quill 1.3.7 (snow theme) -- same version the Python app loads
 * from CDN in base-layout.html, and same toolbar config/image handler as
 * app-core.js's DOMContentLoaded block (lines 123-139, 452-491), plus the
 * additive authoring extras from admin-cms-enhancements.js: "#"/"##"/">"
 * line shortcuts, a "/" slash-command menu, and sanitized inline-image
 * upload for HTML pasted with embedded `data:` image sources.
 *
 * <p>Deliberately NOT a controlled/two-way-bound `[value]` input: Quill
 * itself is the source of truth for its content, exactly like the Python
 * original. {@link setHtml} is only ever called at specific moments (open
 * create form, open edit form, cancel) -- never on every keystroke -- or a
 * reactive value binding would fight the user's cursor position on every
 * character typed. Callers read the live HTML via {@link getHtml} (e.g. on
 * form submit, or from a `contentChange` listener for a debounced live
 * preview) rather than the editor pushing a value back into a bound signal.
 */
@Component({
  selector: 'app-rich-text-editor',
  standalone: true,
  templateUrl: './rich-text-editor.html'
})
export class RichTextEditor implements AfterViewInit, OnDestroy {
  private readonly uploadService = inject(UploadService);

  private readonly editorHost = viewChild.required<ElementRef<HTMLDivElement>>('editorHost');

  /** Emits the current root innerHTML on every Quill text-change. */
  readonly contentChange = output<string>();

  /** Mirrors admin-cms-enhancements.js's updateCharCount (trailing
   *  newline stripped, same as Quill's own char-count convention). */
  readonly charCount = signal(0);

  protected readonly slashCommands = SLASH_COMMANDS;
  protected readonly slashMenuOpen = signal(false);
  protected readonly slashMenuSelectedIndex = signal(0);
  protected readonly slashMenuTop = signal(0);
  protected readonly slashMenuLeft = signal(0);
  private slashMenuLineStart = 0;

  private quill: any = null;

  ngAfterViewInit(): void {
    const quill = new Quill(this.editorHost().nativeElement, {
      theme: 'snow',
      modules: {
        toolbar: {
          container: [
            [{ header: [1, 2, 3, false] }],
            ['bold', 'italic', 'underline', 'strike'],
            [{ list: 'ordered' }, { list: 'bullet' }],
            ['link', 'image'],
            ['clean']
          ],
          handlers: {
            image: () => this.openImagePicker()
          }
        }
      }
    });
    this.quill = quill;

    quill.on('text-change', (delta: unknown, oldDelta: unknown, source: string) => {
      this.charCount.set(quill.getText().replace(/\n$/, '').length);
      this.contentChange.emit(quill.root.innerHTML);
      this.handleMarkdownShortcuts(delta, source);
      this.handleSlashMenuTrigger(delta, source);
    });

    quill.root.addEventListener('drop', (e: DragEvent) => {
      const files = e.dataTransfer?.files;
      if (files && files.length > 0) {
        e.preventDefault();
        for (const file of Array.from(files)) {
          if (file.type.startsWith('image/')) {
            this.uploadAndInsert(file);
          }
        }
      }
    });

    quill.root.addEventListener('paste', (e: ClipboardEvent) => this.onPaste(e));
    quill.root.addEventListener('keydown', (e: KeyboardEvent) => this.handleSlashMenuKeydown(e), true);
    quill.root.addEventListener('blur', () => this.closeSlashMenu());
  }

  ngOnDestroy(): void {
    this.quill = null;
  }

  getHtml(): string {
    return this.quill?.root.innerHTML ?? '';
  }

  /** Silent (no text-change event) so callers driving this from an effect
   *  don't trigger their own contentChange handler re-entrantly. */
  setHtml(html: string): void {
    const quill = this.quill;
    if (!quill) {
      return;
    }
    quill.setText('', 'silent');
    if (html) {
      quill.clipboard.dangerouslyPasteHTML(0, html, 'silent');
    }
    this.charCount.set(quill.getText().replace(/\n$/, '').length);
  }

  clear(): void {
    this.setHtml('');
  }

  insertImageAtCursor(url: string): void {
    const quill = this.quill;
    if (!quill) {
      return;
    }
    const range = quill.getSelection() ?? { index: quill.getLength(), length: 0 };
    quill.insertEmbed(range.index, 'image', url);
    quill.setSelection(range.index + 1, 0);
  }

  protected executeSlashCommand(index: number): void {
    const quill = this.quill;
    const cmd = this.slashCommands[index];
    if (!quill || !cmd) {
      return;
    }
    const lineStart = this.slashMenuLineStart;
    this.closeSlashMenu();
    quill.formatLine(lineStart, 1, cmd.format, cmd.value, 'user');
    quill.deleteText(lineStart, 1, 'user');
    quill.setSelection(lineStart, 0, 'user');
  }

  private openImagePicker(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.onchange = () => {
      const file = input.files?.[0];
      if (file) {
        this.uploadAndInsert(file);
      }
    };
    input.click();
  }

  private uploadAndInsert(file: File): void {
    this.uploadService.upload(file).subscribe({
      next: (result) => this.insertImageAtCursor(result.url),
      error: (err) => console.error('Inline image upload failed:', err)
    });
  }

  /** Native "#", "##", ">" line shortcuts -- Quill's structured API only
   *  (formatLine + deleteText), never innerHTML, so undo/redo stays intact. */
  private handleMarkdownShortcuts(delta: any, source: string): void {
    if (source !== 'user') {
      return;
    }
    const ops = delta?.ops ?? [];
    const lastOp = ops[ops.length - 1];
    if (!lastOp || lastOp.insert !== ' ') {
      return;
    }

    const quill = this.quill;
    const sel = quill.getSelection();
    if (!sel) {
      return;
    }

    const line = quill.getLine(sel.index);
    if (!line || !line[0]) {
      return;
    }
    const lineStart = sel.index - line[1];
    const prefix = quill.getText(lineStart, sel.index - lineStart);

    const rule = MARKDOWN_LINE_RULES.find((r) => r.pattern.test(prefix));
    if (!rule) {
      return;
    }

    quill.formatLine(lineStart, prefix.length, rule.format, rule.value, 'user');
    quill.deleteText(lineStart, prefix.length, 'user');
  }

  /** "/" alone on a line opens a small floating picker; Enter applies it
   *  via the same formatLine + deleteText pattern as the markdown shortcuts. */
  private handleSlashMenuTrigger(delta: any, source: string): void {
    if (source !== 'user') {
      return;
    }
    const quill = this.quill;
    const sel = quill.getSelection();
    if (!sel) {
      this.closeSlashMenu();
      return;
    }

    const line = quill.getLine(sel.index);
    if (!line || !line[0]) {
      this.closeSlashMenu();
      return;
    }
    const lineStart = sel.index - line[1];
    const prefix = quill.getText(lineStart, sel.index - lineStart);

    if (prefix === '/') {
      this.openSlashMenu(lineStart);
    } else if (this.slashMenuOpen()) {
      this.closeSlashMenu();
    }
  }

  private openSlashMenu(lineStart: number): void {
    const quill = this.quill;
    const bounds = quill.getBounds(lineStart + 1);
    this.slashMenuLeft.set(bounds.left);
    this.slashMenuTop.set(bounds.top + bounds.height + 4);
    this.slashMenuLineStart = lineStart;
    this.slashMenuSelectedIndex.set(0);
    this.slashMenuOpen.set(true);
  }

  private closeSlashMenu(): void {
    this.slashMenuOpen.set(false);
  }

  /** Capture phase so this runs BEFORE Quill's own keyboard module (bubble
   *  phase) -- otherwise Quill would already have inserted a newline / moved
   *  the cursor by the time this handler saw the event. */
  private handleSlashMenuKeydown(e: KeyboardEvent): void {
    if (!this.slashMenuOpen()) {
      return;
    }
    const count = this.slashCommands.length;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      e.stopPropagation();
      this.slashMenuSelectedIndex.update((i) => (i + 1) % count);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      e.stopPropagation();
      this.slashMenuSelectedIndex.update((i) => (i - 1 + count) % count);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      this.executeSlashCommand(this.slashMenuSelectedIndex());
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      this.closeSlashMenu();
    }
  }

  /** app-core.js's own paste listener only inspects clipboardData.items
   *  (real image files / clipboard image bytes) -- handled by the plain
   *  image/* branch below. Some sites inline thumbnails as
   *  `<img src="data:...">` directly in their DOM, which arrives as
   *  text/html and slips past that check; this branch (ported from
   *  admin-cms-enhancements.js's handleDataUriPaste) catches that case,
   *  sanitizing with DOMPurify before insertion since raw pasted HTML is
   *  otherwise an XSS vector. */
  private onPaste(e: ClipboardEvent): void {
    const items = e.clipboardData?.items;
    if (items) {
      for (const item of Array.from(items)) {
        if (item.type.startsWith('image/')) {
          const file = item.getAsFile();
          if (file) {
            e.preventDefault();
            this.uploadAndInsert(file);
            return;
          }
        }
      }
    }

    const types = e.clipboardData?.types;
    if (!types || Array.prototype.indexOf.call(types, 'text/html') === -1) {
      return;
    }
    const html = e.clipboardData!.getData('text/html');
    if (!html || !/<img[^>]+src=["']data:/i.test(html)) {
      return;
    }

    e.preventDefault();
    const clean = DOMPurify.sanitize(html);

    const template = document.createElement('template');
    template.innerHTML = clean;
    const pending: { id: string; dataUri: string; originalAlt: string }[] = [];
    let counter = 0;
    template.content.querySelectorAll('img[src^="data:"]').forEach((img) => {
      // Quill's Image format strips unrecognized attributes (data-*, class,
      // title) on insert, keeping only src/alt/width/height -- so the
      // pending-upload marker has to ride in `alt`, the one free-text
      // attribute that survives the round-trip.
      const id = `pending-upload:${Date.now()}-${counter++}`;
      pending.push({ id, dataUri: img.getAttribute('src') ?? '', originalAlt: img.getAttribute('alt') ?? '' });
      img.setAttribute('alt', id);
      // Quill's clipboard module also drops <img> tags with no src at all,
      // so swap the multi-MB data URI for a 1x1 transparent placeholder
      // rather than removing it outright.
      img.setAttribute('src', 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==');
    });

    const quill = this.quill;
    const range = quill.getSelection() ?? { index: quill.getLength(), length: 0 };
    quill.clipboard.dangerouslyPasteHTML(range.index, template.innerHTML, 'user');

    for (const item of pending) {
      this.uploadDataUriImage(item.dataUri)
        .then((result) => {
          const placeholder = quill.root.querySelector(`img[alt="${item.id}"]`);
          if (placeholder) {
            placeholder.setAttribute('src', result.url);
            placeholder.setAttribute('alt', item.originalAlt);
          }
        })
        .catch((err) => {
          console.error('Pasted image upload failed:', err);
          const placeholder = quill.root.querySelector(`img[alt="${item.id}"]`);
          if (placeholder) {
            placeholder.setAttribute('alt', 'სურათის ატვირთვა ვერ მოხერხდა');
          }
        });
    }
  }

  private uploadDataUriImage(dataUri: string): Promise<{ url: string; filename: string }> {
    const match = /^data:([^;]+);base64,(.*)$/.exec(dataUri);
    if (!match) {
      return Promise.reject(new Error('invalid data URI'));
    }
    const mime = match[1];
    const binary = atob(match[2]);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    const ext = (mime.split('/')[1] ?? 'png').split('+')[0];
    const file = new File([bytes], `pasted-image.${ext}`, { type: mime });
    return new Promise((resolve, reject) => {
      this.uploadService.upload(file).subscribe({ next: resolve, error: reject });
    });
  }
}
