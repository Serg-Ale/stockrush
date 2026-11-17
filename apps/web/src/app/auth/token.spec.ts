import { fakeToken } from './testing';
import { isExpired, tokenExpiry } from './token';

describe('token', () => {
  it('reads the expiry from the payload', () => {
    const token = fakeToken(3600);
    expect(tokenExpiry(token)).toBeGreaterThan(Date.now() / 1000);
  });

  it('treats malformed tokens as expired', () => {
    expect(tokenExpiry('not-a-jwt')).toBeNull();
    expect(isExpired('not-a-jwt')).toBe(true);
    expect(isExpired('')).toBe(true);
  });

  it('knows a past expiry from a future one', () => {
    expect(isExpired(fakeToken(3600))).toBe(false);
    expect(isExpired(fakeToken(-10))).toBe(true);
  });
});
