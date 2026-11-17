import { HttpErrorResponse } from '@angular/common/http';
import { errorMessage } from './login';

const failure = (status: number) => new HttpErrorResponse({ status });

describe('errorMessage', () => {
  it('reads the status from the Problem Details body the generated client rejects with', () => {
    expect(errorMessage({ status: 401, detail: 'Invalid email or password.' }, false)).toBe('E-mail ou senha inválidos.');
    expect(errorMessage({ status: 409 }, true)).toBe('Este e-mail já está cadastrado.');
  });

  it('explains the failures the api can return', () => {
    expect(errorMessage(failure(401), false)).toBe('E-mail ou senha inválidos.');
    expect(errorMessage(failure(409), true)).toBe('Este e-mail já está cadastrado.');
    expect(errorMessage(failure(400), true)).toContain('8 a 72');
    expect(errorMessage(failure(429), false)).toContain('Muitas tentativas');
  });

  it('falls back to a generic message that depends on what was being done', () => {
    expect(errorMessage(failure(500), false)).toContain('entrar');
    expect(errorMessage(new Error('boom'), true)).toContain('criar a conta');
  });
});
