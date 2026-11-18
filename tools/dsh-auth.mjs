/**
 * Shared DSH browser-session helper for the local tooling.
 *
 * DSH's web server admits a browser only through (a) the per-process launch
 * token in `/?token=…`, which mints a signed cookie, or (b) that cookie. The
 * cookie is `dsh-auth-<sha256(authority)>` whose value is
 * `v1.<b64url(json)>.<b64url(hmac-sha256(secret, b64url(json)))>` with the
 * signing secret persisted in `$DSH_HOME/.credentials.yaml` under
 * `client-connection/browser-session`. This module forges that cookie locally so
 * preview tooling can render the real UI without touching the launch process.
 */
import { createHash, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const b64url = (input) =>
  Buffer.from(input).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');

/** Read the browser-session signing secret (32 raw bytes, base64url) from the credentials file. */
export function readSigningSecret(credentialsPath) {
  const text = readFileSync(credentialsPath, 'utf8');
  const match = /client-connection\/browser-session:[\s\S]*?secret:\s*([A-Za-z0-9_-]{40,64})/u.exec(text);
  if (!match) throw new Error(`no client-connection/browser-session secret in ${credentialsPath}`);
  return match[1];
}

/** Build the cookie DSH would have issued for `authority` (host[:port] as it appears in the Host header). */
export function forgeCookie(secretB64, authority, lifetimeMs = 86_400_000) {
  const secret = Buffer.from(secretB64.replaceAll('-', '+').replaceAll('_', '/'), 'base64');
  if (secret.length !== 32) throw new Error(`signing secret must be 32 bytes, got ${secret.length}`);
  const now = Date.now();
  const payload = { version: 1, authority, issuedAt: now, expiresAt: now + lifetimeMs };
  const body = b64url(JSON.stringify(payload));
  const signature = b64url(createHmac('sha256', secret).update(body).digest());
  return {
    name: `dsh-auth-${b64url(createHash('sha256').update(authority).digest())}`,
    value: `v1.${body}.${signature}`,
    expiresAt: payload.expiresAt,
  };
}
