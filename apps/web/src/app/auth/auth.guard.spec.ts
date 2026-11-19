import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { adminGuard, authGuard } from './auth.guard';
import { fakeToken } from './testing';

function run(url: string) {
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  return TestBed.runInInjectionContext(() => authGuard({} as never, { url } as RouterStateSnapshot));
}

describe('authGuard', () => {
  beforeEach(() => localStorage.clear());

  it('sends a signed-out visitor to the login, remembering where they were going', () => {
    const result = run('/catalogo');

    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/entrar?retorno=%2Fcatalogo');
  });

  it('lets a signed-in user through', () => {
    localStorage.setItem('stockrush.token', fakeToken(3600));

    expect(run('/catalogo')).toBe(true);
  });
});

describe('adminGuard', () => {
  beforeEach(() => localStorage.clear());

  function runAdmin() {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    return TestBed.runInInjectionContext(() => adminGuard({} as never, {} as RouterStateSnapshot));
  }

  it('lets an admin in', () => {
    localStorage.setItem('stockrush.token', fakeToken(3600, 'ADMIN'));

    expect(runAdmin()).toBe(true);
  });

  it('sends a regular user back to the catalog', () => {
    localStorage.setItem('stockrush.token', fakeToken(3600, 'CLIENTE'));

    const result = runAdmin();

    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/catalogo');
  });
});
