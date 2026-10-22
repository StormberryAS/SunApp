// Starts tests/serve.py (the local stand-in for sun.stormberry.as) and hands back its address.
// Used by tests/smoke.ts, tests/capture-vectors.ts and tests/sw-smoke.ts. Not a test file itself.
// Added 21 October 2026 with the TypeScript move; tls (HTTPS under the real host name, for the
// offline pilot's service worker) added the same day.

import { spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';

export interface Server { readonly base: string; readonly spki: string | null; readonly proc: ChildProcess; stop(): void }

/**
 * Serves root (this checkout unless given) on a free port, as production would. With tls it serves
 * HTTPS with a throwaway certificate for sun.stormberry.as, whose SPKI hash comes back as spki.
 * With overrides, serve.py reads that JSON file on every request to drop or replace single
 * addresses (tests/sw-smoke.ts, fix round 1).
 */
export async function startServer({ root = null, tls = false, overrides = null }: { root?: string | null; tls?: boolean; overrides?: string | null } = {}): Promise<Server> {
  const args = ['-I', new URL('./serve.py', import.meta.url).pathname, '--quiet', ...(root ? ['--root', root] : []), ...(tls ? ['--tls'] : []), ...(overrides ? ['--overrides', overrides] : [])];
  const proc = spawn('python3', args, { stdio: ['ignore', 'pipe', 'inherit'] });
  const [base, spki] = await new Promise<[string, string | null]>((res, rej) => {
    const t = setTimeout(() => rej(new Error('serve.py did not start')), 15000);
    let buf = '';
    proc.stdout.on('data', (d) => {
      buf += d;
      const m = /READY (\S+)(?: spki=(\S+))?\n/.exec(buf);
      if (m && m[1]) { clearTimeout(t); res([m[1], m[2] ?? null]); }
    });
    proc.on('exit', (code) => rej(new Error(`serve.py exited with ${code}`)));
  });
  return { base, spki, proc, stop: () => { proc.kill('SIGTERM'); } };
}
