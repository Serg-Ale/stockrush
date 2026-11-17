import { computed, Injectable, signal } from '@angular/core';
import { login, register } from '../api';
import { isExpired } from './token';

const STORAGE_KEY = 'stockrush.token';

/** Holds the session token. The signal is the source of truth; storage only survives page reloads. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _token = signal<string | null>(this.restore());

  readonly token = this._token.asReadonly();
  readonly isAuthenticated = computed(() => {
    const token = this._token();
    return token !== null && !isExpired(token);
  });

  /** Rejects with the HTTP error when the credentials are wrong. */
  async login(email: string, password: string): Promise<void> {
    const { data } = await login({ body: { email, password }, throwOnError: true });
    if (!data.token) {
      throw new Error('A resposta de login não trouxe um token.');
    }
    this.store(data.token);
  }

  /** Creates the account and signs in with it. */
  async register(email: string, password: string): Promise<void> {
    await register({ body: { email, password }, throwOnError: true });
    await this.login(email, password);
  }

  logout(): void {
    this.store(null);
  }

  private store(token: string | null): void {
    this._token.set(token);
    try {
      if (token) {
        localStorage.setItem(STORAGE_KEY, token);
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // Storage can be blocked; the session then lasts until the page is closed.
    }
  }

  private restore(): string | null {
    try {
      const token = localStorage.getItem(STORAGE_KEY);
      return token && !isExpired(token) ? token : null;
    } catch {
      return null;
    }
  }
}
