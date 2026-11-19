import gsap from 'gsap';

export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** Tweens `target` to `vars`; with reduced motion, jumps straight to the end state instead. */
export function animateTo(target: gsap.TweenTarget, vars: gsap.TweenVars): void {
  if (prefersReducedMotion()) {
    const { duration, ease, delay, ...end } = vars;
    gsap.set(target, end);
    return;
  }
  gsap.to(target, { duration: 0.6, ease: 'power3.out', ...vars });
}
