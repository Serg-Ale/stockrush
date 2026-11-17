import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { fakeToken } from './testing';

function setup(storedToken?: string) {
  localStorage.clear();
  if (storedToken) localStorage.setItem('stockrush.token', storedToken);
  TestBed.configureTestingModule({
    providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
  });
  return { http: TestBed.inject(HttpClient), mock: TestBed.inject(HttpTestingController) };
}

describe('authInterceptor', () => {
  it('adds the bearer token when signed in', () => {
    const token = fakeToken(3600);
    const { http, mock } = setup(token);

    http.get('/orders').subscribe();

    expect(mock.expectOne('/orders').request.headers.get('Authorization')).toBe(`Bearer ${token}`);
  });

  it('sends no Authorization header when signed out', () => {
    const { http, mock } = setup();

    http.get('/products').subscribe();

    expect(mock.expectOne('/products').request.headers.has('Authorization')).toBe(false);
  });

  it('signs out and goes to the login when an authenticated call gets 401', () => {
    const { http, mock } = setup(fakeToken(3600));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    http.get('/orders').subscribe({ error: () => undefined });
    mock.expectOne('/orders').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });
});
