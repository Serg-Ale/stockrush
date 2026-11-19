import { Directive, effect, ElementRef, inject, input } from '@angular/core';
import { gsap } from 'gsap';

export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Brings the host's children in one after another each time the bound value changes. With reduced motion requested
 * nothing is animated and the content is simply there.
 */
@Directive({ selector: '[appReveal]' })
export class Reveal {
  /** Any value; a new one replays the reveal. */
  readonly appReveal = input<unknown>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  constructor() {
    effect((onCleanup) => {
      this.appReveal();
      if (prefersReducedMotion()) return;
      const tween = gsap.from(this.host.nativeElement.children, {
        opacity: 0,
        y: 14,
        duration: 0.5,
        stagger: 0.09,
        ease: 'power3.out',
        clearProps: 'opacity,transform',
      });
      onCleanup(() => tween.kill());
    });
  }
}
