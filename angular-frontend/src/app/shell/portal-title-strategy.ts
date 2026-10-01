import { Injectable, Injector, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { Subscription, merge, startWith } from 'rxjs';

const PORTAL = 'shell.portal_title';
/** What the tab says until the dictionary has loaded (index.html says the same). */
const PORTAL_FALLBACK = 'მაგთი პორტალი';

/**
 * The browser tab says which page this is, in Georgian.
 *
 * Every route already names its page as a translation key (`data.title`) but
 * nothing read it, so every tab was the English "Magti Portal" (simulation,
 * 2026-10-01) -- six open tabs, six identical labels.
 *
 * Re-rendered the way the translate pipe is: on a language switch AND when a
 * dictionary finishes loading. `stream()` re-emits on the first only, and the
 * first navigation happens before the dictionary arrives -- the tab showed the
 * raw keys.
 */
@Injectable({ providedIn: 'root' })
export class PortalTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly injector = inject(Injector);
  private current?: Subscription;

  /**
   * Looked up on first use, not injected: the router builds this strategy while
   * TranslateService's own loader is still being wired (HttpClient ->
   * interceptors -> Router), and taking it in the constructor left every
   * translation in the app unloaded -- raw keys on every screen.
   */
  private get translate(): TranslateService {
    return this.injector.get(TranslateService);
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    let key: string | undefined;
    for (let route: ActivatedRouteSnapshot | null = snapshot.root; route; route = route.firstChild) {
      key = (route.data?.['title'] as string | undefined) ?? key;
    }
    this.current?.unsubscribe();
    this.current = merge(this.translate.onLangChange, this.translate.onTranslationChange, this.translate.onFallbackLangChange)
      .pipe(startWith(null))
      .subscribe(() => this.render(key));
  }

  private render(key: string | undefined): void {
    const portal = this.translated(PORTAL) ?? PORTAL_FALLBACK;
    const page = key ? this.translated(key) : null;
    this.title.setTitle(page ? `${page} — ${portal}` : portal);
  }

  /** The translation, or null while the dictionary has not loaded (instant() then echoes the key). */
  private translated(key: string): string | null {
    const value = this.translate.instant(key) as string;
    return value && value !== key ? value : null;
  }
}
