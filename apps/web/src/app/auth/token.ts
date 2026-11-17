/** The payload of a JWT (not verified: only the api can do that), or null when it is not readable. */
export function tokenClaims(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null;
  }
}

/** Seconds-since-epoch expiry of a JWT, or null when the token is not a readable JWT. */
export function tokenExpiry(token: string): number | null {
  const exp = tokenClaims(token)?.['exp'];
  return typeof exp === 'number' ? exp : null;
}

export function isExpired(token: string, nowMs: number = Date.now()): boolean {
  const exp = tokenExpiry(token);
  return exp === null || exp * 1000 <= nowMs;
}
