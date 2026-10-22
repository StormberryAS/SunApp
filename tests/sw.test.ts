// The offline pilot's service worker, run in Node: the compiled sw.js is loaded into a vm context
// with a stub worker scope, and its fetch handler is driven directly. Added 22 October 2026 (fix
// round 3, verify 2 offline C-1): a browser cache that cannot be read must never break the page
// while the network works. tests/sw-smoke.ts covers the worker in Chromium; a damaged Cache
// Storage cannot be produced there on demand, so this case is tested here.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const SW = readFileSync(new URL('../sw.js', import.meta.url), 'utf8');
const ORIGIN = 'https://sun.stormberry.as';

/** How the page asked: a navigation, or a subresource (Node's Request cannot be built as a navigation). */
type Mode = 'navigate' | 'no-cors';

/** The fetch event the worker is handed, and what it answered with. */
interface FakeFetchEvent {
  readonly request: Request;
  answer: Promise<Response> | null;
  respondWith(p: Promise<Response>): void;
  waitUntil(p: Promise<unknown>): void;
}

/** Loads sw.js into a fresh worker scope whose Cache Storage throws on every call. */
function loadWorker(network: (req: Request) => Promise<Response>): (url: string, mode?: Mode) => Promise<Response> {
  const handlers = new Map<string, (e: unknown) => void>();
  const damaged = (): Promise<never> => Promise.reject(new Error('the cache is damaged'));
  const scope = {
    location: { href: `${ORIGIN}/sw.js`, origin: ORIGIN },
    addEventListener: (type: string, fn: (e: unknown) => void): void => { handlers.set(type, fn); },
    skipWaiting: (): Promise<void> => Promise.resolve(),
  };
  const sandbox: Record<string, unknown> = {
    self: scope,
    caches: { match: damaged, has: damaged, open: damaged, keys: damaged, delete: damaged },
    fetch: network,
    Request,
    Response,
    URL,
    crypto,
    console,
  };
  vm.runInContext(SW, vm.createContext(sandbox), { filename: 'sw.js' });
  const onFetch = handlers.get('fetch');
  assert.ok(onFetch, 'sw.js listens for fetch');
  return (url, mode = 'no-cors') => {
    const event: FakeFetchEvent = {
      request: new Request(url, mode === 'navigate' ? {} : { mode }),
      answer: null,
      respondWith(p) { this.answer = p; },
      waitUntil(p) { p.catch(() => undefined); },
    };
    if (mode === 'navigate') Object.defineProperty(event.request, 'mode', { value: 'navigate' });
    onFetch(event);
    assert.ok(event.answer, `the worker answers ${url}`);
    return event.answer;
  };
}

/** The stamp sw.js lists for one of its scripts or stylesheets. */
function stampOf(file: string): string {
  const m = SW.match(new RegExp(`'\\./${file.replace(/\./g, '\\.')}\\?v=([0-9a-f]{8})'`));
  assert.ok(m?.[1], `sw.js lists ${file} with a stamp`);
  return m[1];
}

test('offline pilot: a cache that cannot be read never breaks the page while online', async () => {
  const asked: string[] = [];
  const worker = loadWorker(async (req) => {
    asked.push(new URL(req.url).pathname);
    return new Response(`network: ${new URL(req.url).pathname}`, { status: 200 });
  });

  // The three strategies: a page (network first), a stamped script (cache first) and an
  // unstamped listed file (stale while revalidate). Each must fall through to the network.
  const cases: Array<[string, Mode?]> = [
    [`${ORIGIN}/`, 'navigate'],
    [`${ORIGIN}/?offline=1`, 'navigate'],
    [`${ORIGIN}/disclaimer.html`, 'navigate'],
    [`${ORIGIN}/app.js?v=${stampOf('app.js')}`],
    [`${ORIGIN}/style.css?v=${stampOf('style.css')}`],
    [`${ORIGIN}/favicon.svg`],
    [`${ORIGIN}/fonts/inter/InterVariable.woff2`],
  ];
  for (const [url, mode] of cases) {
    const res = await worker(url, mode);
    assert.equal(res.status, 200, url);
    assert.equal(await res.text(), `network: ${new URL(url).pathname}`, url);
  }
  assert.ok(asked.length >= cases.length, 'every answer came from the network');
});

test('offline pilot: with no network and no readable cache, the network error is what the page sees', async () => {
  const worker = loadWorker(() => Promise.reject(new TypeError('Failed to fetch')));
  await assert.rejects(worker(`${ORIGIN}/`, 'navigate'), TypeError);
  await assert.rejects(worker(`${ORIGIN}/app.js?v=${stampOf('app.js')}`), TypeError);
});
