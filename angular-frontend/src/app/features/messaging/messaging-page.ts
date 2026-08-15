import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { MessagingService } from '../../core/services/messaging.service';
import { MessageEntry } from '../../core/models/message';
import { formatKaDateTime } from '../../shared/ka-date';
import { ComposeMessageModal } from './compose-message-modal/compose-message-modal';
import { BroadcastModal } from './broadcast-modal/broadcast-modal';
import { ToastService } from '../../core/notifications/toast.service';

type Tab = 'inbox' | 'sent';

const MANAGER_ROLES = ['admin', 'manager'];
const ADMIN_OR_CONTENT_ADMIN = ['admin', 'content_admin'];

/**
 * Port of routers/messaging.py's 6 durable endpoints -- one page, Inbox/Sent
 * tabs (Sent only meaningful for manager/admin, who are the only roles that
 * can send). Mark-as-read is automatic on row click rather than a separate
 * button: message bodies are short and already fully visible in the list,
 * unlike MyReadingsPage's items which route to a separate detail page.
 *
 * DELETE /api/messages/{id} only succeeds when the caller is the message's
 * recipient (MessagingController.deleteMessage's findByIdAndUserId), so the
 * delete action only appears on Inbox rows, never Sent rows.
 */
@Component({
  selector: 'app-messaging-page',
  standalone: true,
  imports: [TranslatePipe, ComposeMessageModal, BroadcastModal],
  templateUrl: './messaging-page.html'
})
export class MessagingPage {
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly messagingService = inject(MessagingService);
  private readonly translate = inject(TranslateService);

  protected readonly tab = signal<Tab>('inbox');
  protected readonly inboxItems = signal<MessageEntry[]>([]);
  protected readonly sentItems = signal<MessageEntry[]>([]);
  protected readonly sentLoaded = signal(false);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly markingId = signal<number | null>(null);
  protected readonly deletingId = signal<number | null>(null);
  protected readonly composeOpen = signal(false);
  protected readonly broadcastOpen = signal(false);

  protected readonly canCompose = computed(() => MANAGER_ROLES.includes(this.auth.currentUser()?.role ?? ''));
  protected readonly canBroadcast = computed(() => ADMIN_OR_CONTENT_ADMIN.includes(this.auth.currentUser()?.role ?? ''));

  constructor() {
    this.loadInbox();
  }

  private loadInbox(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.messagingService.inbox().subscribe({
      next: (items) => {
        this.inboxItems.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set(this.translate.instant('messaging.inbox.load_error'));
        this.loading.set(false);
      }
    });
  }

  private loadSent(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.messagingService.sent().subscribe({
      next: (items) => {
        this.sentItems.set(items);
        this.sentLoaded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set(this.translate.instant('messaging.inbox.load_error'));
        this.loading.set(false);
      }
    });
  }

  setTab(tab: Tab): void {
    this.tab.set(tab);
    if (tab === 'sent' && !this.sentLoaded()) {
      this.loadSent();
    }
  }

  senderLabel(item: MessageEntry): string {
    return item.sender_name ?? this.translate.instant('messaging.inbox.unknown_user');
  }

  recipientLabel(item: MessageEntry): string {
    return item.recipient_name ?? this.translate.instant('messaging.inbox.unknown_user');
  }

  dateLabel(item: MessageEntry): string {
    return formatKaDateTime(item.created_at);
  }

  openInboxItem(item: MessageEntry): void {
    if (item.is_read || this.markingId() === item.id) {
      return;
    }
    this.markingId.set(item.id);
    this.messagingService.markRead(item.id).subscribe({
      next: (updated) => {
        this.markingId.set(null);
        this.inboxItems.set(this.inboxItems().map((m) => (m.id === updated.id ? updated : m)));
      },
      error: () => this.markingId.set(null)
    });
  }

  /**
   * Deleting a message is irreversible and was the one destructive action in
   * the app with NO confirmation at all — every other delete path
   * (categories, articles, news, videos) already asks first. It also failed
   * silently: the row simply stayed put with the spinner cleared, which reads
   * as "nothing happened" rather than "this did not work".
   */
  deleteMessage(item: MessageEntry, event: Event): void {
    event.stopPropagation();
    if (!window.confirm(this.translate.instant('shared.confirm_delete_message'))) {
      return;
    }
    this.deletingId.set(item.id);
    this.messagingService.remove(item.id).subscribe({
      next: () => {
        this.deletingId.set(null);
        this.inboxItems.set(this.inboxItems().filter((m) => m.id !== item.id));
      },
      error: (err: HttpErrorResponse) => {
        this.deletingId.set(null);
        this.toast.error(err.error?.detail ?? this.translate.instant('shared.delete_failed'));
      }
    });
  }

  openCompose(): void {
    this.composeOpen.set(true);
  }

  closeCompose(): void {
    this.composeOpen.set(false);
  }

  onMessageSent(): void {
    this.composeOpen.set(false);
    this.sentLoaded.set(false);
    if (this.tab() === 'sent') {
      this.loadSent();
    }
  }

  openBroadcast(): void {
    this.broadcastOpen.set(true);
  }

  closeBroadcast(): void {
    this.broadcastOpen.set(false);
  }
}
