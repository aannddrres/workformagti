import { Injectable, signal } from '@angular/core';

export interface FontScaleOption {
  label: string;
  value: number;
}

const STORAGE_KEY = 'magti_font_scale';

@Injectable({ providedIn: 'root' })
export class FontScaleService {
  readonly options: FontScaleOption[] = [
    { label: '100%', value: 1 },
    { label: '115%', value: 1.15 },
    { label: '130%', value: 1.3 },
    { label: '150%', value: 1.5 },
    { label: '175%', value: 1.75 },
    { label: '200%', value: 2 }
  ];

  private readonly _scale = signal(this.readStoredScale());
  readonly scale = this._scale.asReadonly();

  constructor() {
    this.apply(this._scale());
  }

  set(value: number): void {
    const supported = this.options.some((option) => option.value === value) ? value : 1;
    this._scale.set(supported);
    localStorage.setItem(STORAGE_KEY, String(supported));
    this.apply(supported);
  }

  private readStoredScale(): number {
    const stored = Number(localStorage.getItem(STORAGE_KEY));
    return this.options.some((option) => option.value === stored) ? stored : 1;
  }

  private apply(value: number): void {
    document.documentElement.style.setProperty('--ui-font-scale', String(value));
    document.documentElement.dataset['fontScale'] = String(Math.round(value * 100));
  }
}
