/** An unsigned JWT with the given expiry, enough for the code that only reads the payload. */
export function fakeToken(expSecondsFromNow: number): string {
  const exp = Math.floor(Date.now() / 1000) + expSecondsFromNow;
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'none' })}.${encode({ exp })}.sig`;
}
