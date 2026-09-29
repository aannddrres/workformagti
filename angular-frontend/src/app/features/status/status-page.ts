import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

@Component({
  selector: 'app-status-page',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="portal-page flex min-h-[60vh] items-center justify-center">
      <div class="surface-card max-w-xl p-8 text-center md:p-12">
        <p class="text-sm font-bold text-brand-accent">{{ status }}</p>
        <h1 class="mt-3 text-2xl font-bold text-slate-950 dark:text-white">{{ heading }}</h1>
        <p class="mt-3 text-sm leading-6 text-slate-600 dark:text-slate-300">{{ description }}</p>
        <a routerLink="/" class="primary-button mt-7 inline-flex">მთავარ გვერდზე დაბრუნება</a>
      </div>
    </section>
  `
})
export class StatusPage {
  private readonly data = inject(ActivatedRoute).snapshot.data;
  protected readonly status = Number(this.data['status'] ?? 404);
  protected readonly heading = this.status === 403 ? 'წვდომა შეზღუდულია' : 'გვერდი ვერ მოიძებნა';
  protected readonly description = this.status === 403
    ? 'თქვენს როლს ამ სამუშაო სივრცეზე წვდომა არ აქვს. თუ ეს მოულოდნელია, მიმართეთ სისტემურ ადმინისტრატორს.'
    : 'მითითებული მისამართი არ არსებობს ან გვერდი გადატანილია.';
}
