/** Seconds-since-epoch expiry of a JWT, or null when the token is not a readable JWT. */
export function tokenExpiry(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const exp = JSON.parse(json).exp;
    return typeof exp === 'number' ? exp : null;
  } catch {
    return null;
  }
}

export function isExpired(token: string, nowMs: number = Date.now()): boolean {
  const exp = tokenExpiry(token);
  return exp === null || exp * 1000 <= nowMs;
}
