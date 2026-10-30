/*! SunApp: sw.js is compiled from ts/sw.ts by tools/build-ts.sh, which leaves out every comment
    but this one, and bump_assets.py stamps its ./x.js and ./x.css literals. Edit the .ts, never
    the .js. Offline pilot: the service worker that lets sun.stormberry.as open with no network
    after one visit. Emergency stop: set KILL to true and deploy (see ts/sw.ts). */

/*
 * SunApp: ts/sw.ts, the offline pilot (21 October 2026)
 * ================================================================
 * Registered by offlinePilot() in ts/app.ts, and only on https://sun.stormberry.as: never on the
 * onion mirror, on file://, on localhost or any other host, and never in the Android app (which is
 * native Compose with no WebView). While the pilot is 'opt-in' a device gets this worker only from
 * a visit to https://sun.stormberry.as/?offline=1; after that it keeps it up to date.
 *
 * What it does:
 *   - install: fetches every file the page needs (ASSETS, bypassing the HTTP cache) into one
 *     cache, all or nothing, then takes over at once (skipWaiting). A stamped file (?v=) is kept
 *     only when its bytes hash to its stamp, so a CDN still serving the old file under a new
 *     stamp can never be pinned offline; the install then fails and the browser retries later,
 *     while the old worker keeps working. It also fails when a stored page loads a stamped file
 *     this worker does not list (fix round 1): a CDN still serving an older sw.js, with the older
 *     files, next to a newer index.html must not store a page whose scripts it lacks.
 *   - activate: deletes every other sunapp- cache, then controls the open pages (clients.claim).
 *   - pages (a path ending in / or .html, or a navigation to a path with no file extension):
 *     network first, revalidating, no timeout. A good answer is returned at once, and stored only
 *     when it is one of the pages in ASSETS and every stamped file it loads is stored as well
 *     (keepPage, fix round 1), so a network that fails partway can never replace the last complete
 *     offline copy with a page whose scripts are missing. Only a network failure falls back to the
 *     stored copy.
 *   - stamped files (./x.js?v=0123abcd, ./x.css?v=...): cache first. A stamped URL never changes
 *     its bytes, so the stored copy is always right. On a miss the answer is stored only when its
 *     bytes match the stamp; when they do not (the HTTP cache holding what a lagging CDN served),
 *     the server is asked once more, past the HTTP cache.
 *   - the other files in ASSETS (fonts, icons, favicon): stale while revalidate.
 *   - anything else (a file not in ASSETS, such as the /favicon.ico a browser asks for by itself,
 *     other origins, non-GET, /cdn-cgi/): not touched, the browser fetches it as if there were no
 *     worker.
 *
 * The cache name is "sunapp-" plus a hash of the stamped ASSETS list. bump_assets.py rewrites the
 * stamps in sw.js whenever a script or stylesheet changes, so every such deploy changes sw.js (the
 * browser then installs the new worker) and rotates the cache (activate deletes the old one).
 *
 * Kill switches, fastest first:
 *   1. OFFLINE_PILOT = 'off' in ts/app.ts: every page load on sun.stormberry.as unregisters the
 *      worker and deletes the sunapp- caches. Pages are network first, so this reaches every
 *      device on its next online visit.
 *   2. KILL = true here: the next update check installs a worker that serves nothing, deletes
 *      every sunapp- cache and unregisters itself. It does not reload the open pages: a page whose
 *      start address carries ?offline=1 would register it again on every load, so a reload would
 *      loop. A page it controlled already goes to the network for everything.
 *   3. A Cloudflare response header Clear-Site-Data: "storage" on sun.stormberry.as (dashboard).
 *
 * Retiring the pilot (fix round 2, verify 1 offline S-1): once this worker has shipped, never delete
 * sw.js and never revert the pilot as a whole. A device keeps its worker while sw.js answers 404,
 * and with offlinePilot() gone from app.js neither switch can reach it; the stranded worker then
 * adds every later release's stamped files to its cache. To end the pilot, deploy OFFLINE_PILOT =
 * 'off' and KILL = true together and keep both served for at least three months (a Home Screen
 * app can go unopened for weeks); only then remove the code. If sw.js was removed by mistake,
 * serving a sw.js with KILL = true again clears the worker at each device's next visit
 * (tests/sw-smoke.ts, check 11).
 * ================================================================
 */

/** This file runs as a classic worker script: self is the service worker's global scope. */
const sw = self as unknown as ServiceWorkerGlobalScope;

/** Emergency stop: true ships a worker that removes itself and everything it stored. */
const KILL = false as boolean;

/**
 * Everything the page needs offline, relative to this file (the site root). The stamps on the
 * scripts and stylesheets are written by bump_assets.py, from the same hashes as index.html;
 * tests/static.test.ts holds this list to the page's own references.
 */
const ASSETS: readonly string[] = [
  './',
  './suncalc.js',
  './cities.js',
  './app.js',
  './style.css',
  './fonts/inter/inter.css',
  './fonts/inter/InterVariable.woff2',
  './fonts/inter/InterVariable-Italic.woff2',
  './favicon.svg',
  './disclaimer.html',
  './switcher-icons/astro.svg',
  './switcher-icons/beer.svg',
  './switcher-icons/check.svg',
  './switcher-icons/eclipse.svg',
  './switcher-icons/eco.svg',
  './switcher-icons/flare.svg',
  './switcher-icons/garden.svg',
  './switcher-icons/gdpr.svg',
  './switcher-icons/labs.svg',
  './switcher-icons/metadata.svg',
  './switcher-icons/meteor.svg',
  './switcher-icons/moon.svg',
  './switcher-icons/nightmode.svg',
  './switcher-icons/photo.svg',
  './switcher-icons/planet.svg',
  './switcher-icons/scheduler.svg',
  './switcher-icons/star.svg',
  './switcher-icons/sun.svg',
  './switcher-icons/tide.svg',
  './switcher-icons/username.svg',
  './switcher-icons/weather.svg',
];

const PREFIX = 'sunapp-';

/** FNV-1a, 32 bits, as 8 hex digits: enough to tell two stamp lists apart. */
function fnv1a(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, '0');
}

const CACHE = PREFIX + fnv1a(ASSETS.join('\n'));

/** The unstamped files of ASSETS other than pages, by address: the only ones kept fresh in the background. */
const LISTED = new Set(ASSETS.filter((a) => !a.includes('?') && !a.endsWith('/') && !a.endsWith('.html')).map((a) => new URL(a, sw.location.href).href));

/** The pages in ASSETS, by their stored key: the only pages ever stored (fix round 1). */
const PAGES = new Set(ASSETS.filter((a) => a.endsWith('/') || a.endsWith('.html')).map((a) => pageKey(new URL(a, sw.location.href))));

/** The stamp of a stamped script or stylesheet URL, or null. */
function stampOf(url: URL): string | null {
  if (!/\.(js|css)$/.test(url.pathname)) return null;
  const v = url.searchParams.get('v');
  return v !== null && /^[0-9a-f]{8}$/.test(v) ? v : null;
}

/** Whether a response's bytes hash (SHA-256, first 8 hex digits) to the stamp in its URL. */
async function matchesStamp(res: Response, stamp: string): Promise<boolean> {
  const digest = await crypto.subtle.digest('SHA-256', await res.clone().arrayBuffer());
  const hex = Array.from(new Uint8Array(digest).slice(0, 4), (b) => b.toString(16).padStart(2, '0')).join('');
  return hex === stamp;
}

/** Only a complete, same-origin answer is ever stored. */
const storable = (res: Response): boolean => res.ok && res.type === 'basic' && !res.redirected;

/** Where a page is stored: its path without the query, so / and /?offline=1 share one entry. */
function pageKey(url: URL): string {
  const path = url.pathname.endsWith('/index.html') ? url.pathname.slice(0, -'index.html'.length) : url.pathname;
  return url.origin + path;
}

/**
 * A request for a page, but one that asks the server (If-None-Match) instead of the HTTP cache, so
 * kill switch 1 reaches a device at once rather than after the ten minutes index.html may sit in
 * the HTTP cache. The trade-off (fix round 2, verify 1 offline C-1): the copy is no longer a
 * navigation, so the server sees Sec-Fetch-Mode: same-origin and Sec-Fetch-Dest: empty and no
 * Sec-Fetch-User. GitHub Pages and the zone ignore these today, but a Cloudflare challenge (Bot
 * Fight Mode, Under Attack mode, a managed challenge) would answer pilot devices with a bare 403
 * instead of its page. Keep every such rule off sun.stormberry.as while the pilot runs.
 */
function revalidating(req: Request): Request {
  try {
    return new Request(req, { cache: 'no-cache' });
  } catch {
    return req;
  }
}

/** The cache, only if it exists: a write never brings back a cache the page or activate deleted. */
async function openExisting(): Promise<Cache | null> {
  return (await caches.has(CACHE)) ? caches.open(CACHE) : null;
}

/**
 * A stored answer, read without creating the cache (caches.open would create it). A cache that
 * cannot be read (damaged or evicted storage) counts as a miss, so the network still answers and
 * the page works online as if there were no worker (fix round 3, verify 2 offline C-1).
 */
const stored = (req: Request | string): Promise<Response | undefined> =>
  caches.match(req, { cacheName: CACHE, ignoreVary: true }).catch(() => undefined);

/** The stamped scripts and stylesheets a page loads, as absolute addresses. */
function stampedRefs(html: string, base: string): string[] {
  return [...html.matchAll(/(?:src|href)="([^"]+\?v=[0-9a-f]{8})"/g)].map((m) => new URL(m[1] as string, base).href);
}

/** A stamped file whose bytes match its stamp (asking past the HTTP cache once on a mismatch), or null. */
async function verified(href: string, stamp: string): Promise<Response | null> {
  const res = await fetch(new Request(href, { credentials: 'same-origin' }));
  if (storable(res) && (await matchesStamp(res, stamp))) return res;
  const again = await fetch(new Request(href, { cache: 'reload', credentials: 'same-origin' }));
  return storable(again) && (await matchesStamp(again, stamp)) ? again : null;
}

/**
 * Stores a page only once every stamped file it loads is stored: a page whose scripts are missing
 * must never replace the last complete one (a network that dies after the page, or an old sw.js).
 */
async function keepPage(key: string, res: Response): Promise<void> {
  const html = await res.clone().text();
  for (const href of stampedRefs(html, key)) {
    if (await stored(href)) continue;
    const stamp = stampOf(new URL(href));
    if (stamp === null) return;
    const got = await verified(href, stamp);
    if (got === null) return;
    const cache = await openExisting();
    if (cache === null) return;
    await cache.put(href, got);
  }
  const cache = await openExisting();
  if (cache !== null) await cache.put(key, res);
}

async function precache(): Promise<void> {
  const fetched: Array<[string, Response]> = [];
  for (const rel of ASSETS) {
    const url = new URL(rel, sw.location.href);
    const res = await fetch(new Request(url.href, { cache: 'reload', credentials: 'same-origin' }));
    if (!storable(res)) throw new Error(`precache: ${url.pathname} answered ${res.status}`);
    const stamp = stampOf(url);
    if (stamp !== null && !(await matchesStamp(res, stamp))) throw new Error(`precache: ${url.pathname} does not match its stamp ${stamp}`);
    fetched.push([url.searchParams.has('v') ? url.href : pageKey(url), res]);
  }
  // Every stamped file the stored pages load must be in this list, under the same stamp: an sw.js
  // older than index.html (a lagging edge) must not install a page whose scripts it lacks.
  const keys = new Set(fetched.map(([k]) => k));
  for (const [key, res] of fetched) {
    if (key.includes('?') || !(key.endsWith('/') || key.endsWith('.html'))) continue;
    for (const href of stampedRefs(await res.clone().text(), key)) {
      if (!keys.has(href)) throw new Error(`precache: ${new URL(key).pathname} loads ${href}, which this worker does not list`);
    }
  }
  // All or nothing: the cache is written only once every file has arrived whole.
  const cache = await caches.open(CACHE);
  for (const [key, res] of fetched) await cache.put(key, res);
}

async function dropCaches(keep: string | null): Promise<void> {
  const names = await caches.keys();
  await Promise.all(names.filter((n) => n.startsWith(PREFIX) && n !== keep).map((n) => caches.delete(n)));
}

async function page(event: FetchEvent, url: URL): Promise<Response> {
  const key = pageKey(url);
  try {
    const res = await fetch(revalidating(event.request));
    if (storable(res) && PAGES.has(key)) {
      const copy = res.clone();
      event.waitUntil(keepPage(key, copy).catch(() => undefined));
    }
    return res;
  } catch (err) {
    const kept = await stored(key);
    if (kept) return kept;
    throw err;
  }
}

async function stamped(event: FetchEvent, stamp: string): Promise<Response> {
  const kept = await stored(event.request);
  if (kept) return kept;
  let res = await fetch(event.request);
  if (storable(res) && !(await matchesStamp(res, stamp))) {
    // Other bytes than the stamp names: the HTTP cache kept what a lagging CDN served under this
    // stamp. Ask the server once more, past the HTTP cache.
    try {
      const again = await fetch(new Request(event.request.url, { cache: 'reload', credentials: 'same-origin' }));
      if (storable(again)) res = again;
    } catch {
      // Offline now: the first answer is still better than none.
    }
  }
  if (storable(res) && (await matchesStamp(res, stamp))) {
    const copy = res.clone();
    event.waitUntil(openExisting().then((c) => c?.put(event.request, copy)).catch(() => undefined));
  }
  return res;
}

async function staleWhileRevalidate(event: FetchEvent): Promise<Response> {
  const kept = await stored(event.request);
  const network = fetch(event.request).then((res) => {
    if (storable(res)) {
      const copy = res.clone();
      event.waitUntil(openExisting().then((c) => c?.put(event.request, copy)).catch(() => undefined));
    }
    return res;
  });
  if (kept) {
    event.waitUntil(network.then(() => undefined, () => undefined));
    return kept;
  }
  return network;
}

/**
 * Whether a request is for a page: a path ending in / or .html, or a document request for a path
 * whose last segment has no file extension. A file opened in a tab (/robots.txt, /sw.js) is not
 * one (fix round 1): the worker leaves it to the browser.
 */
function isPage(req: Request, url: URL): boolean {
  if (url.pathname.endsWith('/') || url.pathname.endsWith('.html')) return true;
  const last = url.pathname.slice(url.pathname.lastIndexOf('/') + 1);
  return (req.mode === 'navigate' || req.destination === 'document') && !last.includes('.');
}

sw.addEventListener('install', (event) => {
  event.waitUntil(KILL ? sw.skipWaiting() : precache().then(() => sw.skipWaiting()));
});

sw.addEventListener('activate', (event) => {
  event.waitUntil(KILL
    ? dropCaches(null).then(() => sw.registration.unregister()).then(() => undefined)
    : dropCaches(CACHE).then(() => sw.clients.claim()));
});

sw.addEventListener('fetch', (event) => {
  if (KILL) return;
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== sw.location.origin || url.pathname.startsWith('/cdn-cgi/')) return;
  if (isPage(req, url)) {
    event.respondWith(page(event, url));
    return;
  }
  const stamp = stampOf(url);
  if (stamp !== null) event.respondWith(stamped(event, stamp));
  else if (url.search === '' && LISTED.has(url.href)) event.respondWith(staleWhileRevalidate(event));
});
