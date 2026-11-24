/**
 * Portal trust tooling: diagnose and clear "UI metadata tampered" (signed portal delivery).
 * Cross-platform (Linux / macOS / Windows): uses only Bun + node:crypto, no bash/openssl/python.
 * Runbook: docs/ui-metadata-tampered.md
 *
 *   bun run portal:check                      — compare SPA pin vs this machine's signing key
 *   bun run portal:pin                        — rewrite trust-store.json from this machine's public key
 *   bun run portal:publish   [--portal=admin] — publish a signed bundle if the portal has none
 *   bun run portal:republish [--portal=admin] — re-sign even if a bundle exists (FSP_PUBLISH_FORCE=1)
 *
 * PUBLIC key material only is read/printed. The private key is read solely to confirm the
 * public PEM belongs to it; it is never printed or written anywhere.
 */
import { createPrivateKey, createPublicKey } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = dirname(dirname(fileURLToPath(import.meta.url)));
const REPO = dirname(FRONTEND);
const PKI = join(REPO, 'infrastructure', 'pki', 'dev');
const PRIV = join(PKI, 'ui-builder-signing.pem');
const PUB = join(PKI, 'ui-builder-signing-pub.pem');
const TRUST = join(FRONTEND, 'src', 'portals', 'signed', 'trust-store.json');
const KID = 'ui-builder-dev-1';

type Jwk = { x: string; y: string };

function localPublicJwk(): Jwk {
  if (!existsSync(PUB) || !existsSync(PRIV)) {
    fail(`signing keys missing in infrastructure/pki/dev — run 'make pki' (Linux/macOS) or `
      + `'powershell -File scripts\\dev-windows.ps1 pki' (Windows), then restart core`);
  }
  const pub = createPublicKey(readFileSync(PUB)).export({ format: 'jwk' }) as Jwk;
  const fromPriv = createPublicKey(createPrivateKey(readFileSync(PRIV))).export({ format: 'jwk' }) as Jwk;
  if (pub.x !== fromPriv.x || pub.y !== fromPriv.y) {
    fail('ui-builder-signing-pub.pem does not belong to ui-builder-signing.pem — delete BOTH, '
      + 're-run the pki step, restart core, then `bun run portal:pin` and `bun run portal:republish`');
  }
  return pub;
}

function pinnedJwk(): (Jwk & { kid: string }) | undefined {
  const store = JSON.parse(readFileSync(TRUST, 'utf8')) as { keys: (Jwk & { kid: string })[] };
  return store.keys.find((k) => k.kid === KID);
}

function fail(msg: string): never {
  console.error(`✗ ${msg}`);
  process.exit(1);
}

function check(): void {
  const local = localPublicJwk();
  const pinned = pinnedJwk();
  if (!pinned) fail(`trust-store.json has no "${KID}" key — run \`bun run portal:pin\``);
  if (pinned.x !== local.x || pinned.y !== local.y) {
    fail('trust-store.json pins a DIFFERENT key than this machine signs with '
      + '(typically a teammate\'s committed pin). Run `bun run portal:pin`, then `bun run portal:republish`.');
  }
  console.log('✓ trust-store.json matches this machine\'s signing key.');
  console.log('  If the portal still shows "UI metadata tampered", the stored bundle was signed by an older');
  console.log('  key: make sure core was restarted after the last pki run, then `bun run portal:republish`.');
}

function pin(): void {
  const { x, y } = localPublicJwk();
  // Same layout dev-pki.sh writes, so either tool produces an identical file.
  const body = `{
  "_comment": "Build-pinned JWKS for signed portal delivery (plans/signed-portal-delivery-plan.md §2.3). This file IS the browser trust anchor: the running portal accepts only kids listed here, discovered at RUNTIME keys are never trusted. Rotate by adding a new kid here, shipping an overlap build, then removing the old kid. Never put private keys in this file.",
  "pinnedAt": "${new Date().toISOString().slice(0, 10)}",
  "keys": [
    { "kid": "${KID}", "alg": "ES256", "kty": "EC", "crv": "P-256",
      "x": "${x}", "y": "${y}" }
  ]
}
`;
  writeFileSync(TRUST, body);
  console.log('✓ trust-store.json re-pinned to this machine\'s public key (Vite reloads it; rebuild for dist).');
}

function publish(force: boolean, args: string[]): void {
  const portal = args.find((a) => a.startsWith('--portal='))?.slice('--portal='.length) ?? process.env.PORTAL ?? 'admin';
  const env = { ...process.env, PORTAL: portal, ...(force ? { FSP_PUBLISH_FORCE: '1' } : {}) };
  // process.execPath is the bun binary on every OS — no reliance on bunx/.cmd shims or a POSIX shell.
  // only the portal-publish flow — tests/setup also holds plugin-upgrade / widget-register one-shots
  const r = spawnSync(process.execPath, ['x', 'playwright', 'test', '-c', 'playwright.setup.config.ts', 'portal-publish'],
    { cwd: FRONTEND, env, stdio: 'inherit' });
  process.exit(r.status ?? 1);
}

const [cmd = 'check', ...rest] = process.argv.slice(2);
switch (cmd) {
  case 'check': check(); break;
  case 'pin': pin(); break;
  case 'publish': publish(false, rest); break;
  case 'republish': publish(true, rest); break;
  default: fail(`unknown command "${cmd}" — use check | pin | publish | republish`);
}
