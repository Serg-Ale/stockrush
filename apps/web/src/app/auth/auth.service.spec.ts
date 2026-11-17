import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { client } from '../api/client.gen';
import { provideHeyApiClient } from '../api/client/client.gen';
import { AuthService } from './auth.service';
import { fakeToken } from './testing';

function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideHeyApiClient(client)],
  });
  return { auth: TestBed.inject(AuthService), http: TestBed.inject(HttpTestingController) };
}

describe('AuthService', () => {
  beforeEach(() => localStorage.clear());

  it('starts signed out', () => {
    expect(setup().auth.isAuthenticated()).toBe(false);
  });

  it('restores a valid stored token and drops an expired one', () => {
    localStorage.setItem('stockrush.token', fakeToken(3600));
    expect(setup().auth.isAuthenticated()).toBe(true);

    TestBed.resetTestingModule();
    localStorage.setItem('stockrush.token', fakeToken(-60));
    expect(setup().auth.isAuthenticated()).toBe(false);
  });

  it('stores the token after a successful login', async () => {
    const { auth, http } = setup();
    const token = fakeToken(3600);

    const login = auth.login('ana@example.com', 'correct-horse');
    const request = await vi.waitFor(() => http.expectOne('/auth/login'));
    expect(request.request.body).toEqual({ email: 'ana@example.com', password: 'correct-horse' });
    request.flush({ token });
    await login;

    expect(auth.isAuthenticated()).toBe(true);
    expect(localStorage.getItem('stockrush.token')).toBe(token);
  });

  it('rejects with the problem details and stays signed out when the credentials are wrong', async () => {
    const { auth, http } = setup();

    const login = auth.login('ana@example.com', 'wrong-password');
    const request = await vi.waitFor(() => http.expectOne('/auth/login'));
    request.flush({ detail: 'Invalid email or password.' }, { status: 401, statusText: 'Unauthorized' });

    await expect(login).rejects.toMatchObject({ detail: 'Invalid email or password.' });
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('signs in right after creating the account', async () => {
    const { auth, http } = setup();

    const registration = auth.register('ana@example.com', 'correct-horse');
    const created = await vi.waitFor(() => http.expectOne('/auth/register'));
    created.flush({ id: 1, email: 'ana@example.com' }, { status: 201, statusText: 'Created' });
    const login = await vi.waitFor(() => http.expectOne('/auth/login'));
    login.flush({ token: fakeToken(3600) });
    await registration;

    expect(auth.isAuthenticated()).toBe(true);
  });

  it('is admin only with a valid token whose role is ADMIN', () => {
    localStorage.setItem('stockrush.token', fakeToken(3600, 'ADMIN'));
    expect(setup().auth.isAdmin()).toBe(true);

    TestBed.resetTestingModule();
    localStorage.setItem('stockrush.token', fakeToken(3600, 'CLIENTE'));
    expect(setup().auth.isAdmin()).toBe(false);

    TestBed.resetTestingModule();
    localStorage.setItem('stockrush.token', fakeToken(-60, 'ADMIN'));
    expect(setup().auth.isAdmin()).toBe(false);
  });

  it('forgets the token on logout', async () => {
    localStorage.setItem('stockrush.token', fakeToken(3600));
    const { auth } = setup();

    auth.logout();

    expect(auth.isAuthenticated()).toBe(false);
    expect(localStorage.getItem('stockrush.token')).toBeNull();
  });
});
