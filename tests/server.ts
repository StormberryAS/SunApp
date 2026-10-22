// Starts tests/serve.py (the local stand-in for sun.stormberry.as) and hands back its address.
// Used by tests/smoke.ts and tests/capture-vectors.ts. Not a test file itself. Added 21 October
// 2026 with the TypeScript move.

import { spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';

export interface Server { readonly base: string; readonly proc: ChildProcess; stop(): void }

/** Serves root (this checkout unless given) on a free port, as production would. */
export async function startServer({ root = null }: { root?: string | null } = {}): Promise<Server> {
  const args = ['-I', new URL('./serve.py', import.meta.url).pathname, '--quiet', ...(root ? ['--root', root] : [])];
  const proc = spawn('python3', args, { stdio: ['ignore', 'pipe', 'inherit'] });
  const base = await new Promise<string>((res, rej) => {
    const t = setTimeout(() => rej(new Error('serve.py did not start')), 15000);
    let buf = '';
    proc.stdout.on('data', (d) => {
      buf += d;
      const m = /READY (\S+)/.exec(buf);
      if (m && m[1]) { clearTimeout(t); res(m[1]); }
    });
    proc.on('exit', (code) => rej(new Error(`serve.py exited with ${code}`)));
  });
  return { base, proc, stop: () => { proc.kill('SIGTERM'); } };
}
