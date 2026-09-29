import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { RowMenu } from './row-menu';

@Component({
  standalone: true,
  imports: [RowMenu],
  template: `
    <app-row-menu label="სტატიის მოქმედებები">
      <button type="button" class="row-menu-item" (click)="archived.set(true)">დაარქივება</button>
    </app-row-menu>
    <p id="outside">outside</p>
  `
})
class Host {
  readonly archived = signal(false);
}

async function setup() {
  const fixture = TestBed.createComponent(Host);
  await fixture.whenStable();
  const root = fixture.nativeElement as HTMLElement;
  document.body.appendChild(root);
  const trigger = root.querySelector<HTMLButtonElement>('app-row-menu > button')!;
  const panel = () => root.querySelector<HTMLElement>('.row-menu-panel');
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
  };
  return { fixture, root, trigger, panel, settle };
}

describe('RowMenu', () => {
  it('opens under its button, runs the chosen item and hands focus back', async () => {
    const { fixture, trigger, panel, settle } = await setup();
    expect(trigger.getAttribute('aria-label')).toBe('სტატიის მოქმედებები');
    expect(panel()).toBeNull();

    trigger.click();
    await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    panel()!.querySelector<HTMLButtonElement>('button')!.click();
    await settle();

    expect(fixture.componentInstance.archived()).toBe(true);
    expect(panel()).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('closes on a click elsewhere and on Escape', async () => {
    const { root, trigger, panel, settle } = await setup();
    trigger.click();
    await settle();
    root.querySelector<HTMLElement>('#outside')!.click();
    await settle();
    expect(panel()).toBeNull();

    trigger.click();
    await settle();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    expect(panel()).toBeNull();
  });
});
