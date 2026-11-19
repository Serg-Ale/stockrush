import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { gsap } from 'gsap';
import { Reveal } from './reveal';

@Component({
  imports: [Reveal],
  template: `<div [appReveal]="1"><p>a</p><p>b</p></div>`,
})
class Host {}

function reduceMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', () => ({ matches: reduce }));
}

describe('Reveal', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('asks GSAP to bring the children in from hidden when motion is allowed', () => {
    reduceMotion(false);
    const from = vi.spyOn(gsap, 'from');
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();

    expect(from).toHaveBeenCalledTimes(1);
    const [targets, vars] = from.mock.calls[0];
    expect(Array.from(targets as ArrayLike<Element>)).toEqual(Array.from(fixture.nativeElement.querySelector('div').children));
    expect(vars).toEqual(expect.objectContaining({ opacity: 0 }));
  });

  it('leaves the content untouched with reduced motion', () => {
    reduceMotion(true);
    const from = vi.spyOn(gsap, 'from');
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    expect(from).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('p').getAttribute('style')).toBeNull();
  });
});
