import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
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
  afterEach(() => vi.unstubAllGlobals());

  it('hides the children to animate them in when motion is allowed', () => {
    reduceMotion(false);
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('p').style.opacity).toBe('0');
  });

  it('leaves the content untouched with reduced motion', () => {
    reduceMotion(true);
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('p').getAttribute('style')).toBeNull();
  });
});
