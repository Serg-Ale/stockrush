import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Product } from '../../catalog/catalog.service';
import { ProductCard } from './product-card';

function render(product: Product) {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(ProductCard);
  fixture.componentRef.setInput('product', product);
  fixture.detectChanges();
  return { fixture, img: () => (fixture.nativeElement as HTMLElement).querySelector('img')! };
}

const keyboard: Product = { id: 1, name: 'Mechanical Keyboard', availableQuantity: 5, imageUrl: '/img/products/mechanical-keyboard.webp' };

describe('ProductCard picture', () => {
  it('shows the product picture with its name as alt, lazy loading and a reserved size', () => {
    const { img } = render(keyboard);

    expect(img().getAttribute('src')).toBe('/img/products/mechanical-keyboard.webp');
    expect(img().getAttribute('alt')).toBe('Mechanical Keyboard');
    expect(img().getAttribute('loading')).toBe('lazy');
    expect(img().getAttribute('width')).toBe('600');
    expect(img().getAttribute('height')).toBe('450');
  });

  it('only ever loads from inside the app', () => {
    const { img } = render(keyboard);

    expect(img().getAttribute('src')).toMatch(/^\/[^/]/);
  });

  it('shows the SVG placeholder, as decoration, when the product has no picture', () => {
    const { img } = render({ ...keyboard, imageUrl: null });

    expect(img().getAttribute('src')).toBe('/img/placeholder-product.svg');
    expect(img().getAttribute('alt')).toBe('');
  });

  it('falls back to the placeholder when the picture fails to load', () => {
    const { fixture, img } = render(keyboard);

    img().dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(img().getAttribute('src')).toBe('/img/placeholder-product.svg');
    expect(img().getAttribute('alt')).toBe('');
  });
});
