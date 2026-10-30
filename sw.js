"use strict";
/*! SunApp: sw.js is compiled from ts/sw.ts by tools/build-ts.sh, which leaves out every comment
    but this one, and bump_assets.py stamps its ./x.js and ./x.css literals. Edit the .ts, never
    the .js. Offline pilot: the service worker that lets sun.stormberry.as open with no network
    after one visit. Emergency stop: set KILL to true and deploy (see ts/sw.ts). */
const sw = self;
const KILL = false;
const ASSETS = [
    './',
    './suncalc.js?v=e17fa013',
    './cities.js?v=2f08782c',
    './app.js?v=3fb1a2ed',
    './style.css?v=b8c4a26c',
    './fonts/inter/inter.css?v=868d674e',
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
function fnv1a(text) {
    let h = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
        h ^= text.charCodeAt(i);
        h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16).padStart(8, '0');
}
const CACHE = PREFIX + fnv1a(ASSETS.join('\n'));
const LISTED = new Set(ASSETS.filter((a) => !a.includes('?') && !a.endsWith('/') && !a.endsWith('.html')).map((a) => new URL(a, sw.location.href).href));
const PAGES = new Set(ASSETS.filter((a) => a.endsWith('/') || a.endsWith('.html')).map((a) => pageKey(new URL(a, sw.location.href))));
function stampOf(url) {
    if (!/\.(js|css)$/.test(url.pathname))
        return null;
    const v = url.searchParams.get('v');
    return v !== null && /^[0-9a-f]{8}$/.test(v) ? v : null;
}
async function matchesStamp(res, stamp) {
    const digest = await crypto.subtle.digest('SHA-256', await res.clone().arrayBuffer());
    const hex = Array.from(new Uint8Array(digest).slice(0, 4), (b) => b.toString(16).padStart(2, '0')).join('');
    return hex === stamp;
}
const storable = (res) => res.ok && res.type === 'basic' && !res.redirected;
function pageKey(url) {
    const path = url.pathname.endsWith('/index.html') ? url.pathname.slice(0, -'index.html'.length) : url.pathname;
    return url.origin + path;
}
function revalidating(req) {
    try {
        return new Request(req, { cache: 'no-cache' });
    }
    catch {
        return req;
    }
}
async function openExisting() {
    return (await caches.has(CACHE)) ? caches.open(CACHE) : null;
}
const stored = (req) => caches.match(req, { cacheName: CACHE, ignoreVary: true }).catch(() => undefined);
function stampedRefs(html, base) {
    return [...html.matchAll(/(?:src|href)="([^"]+\?v=[0-9a-f]{8})"/g)].map((m) => new URL(m[1], base).href);
}
async function verified(href, stamp) {
    const res = await fetch(new Request(href, { credentials: 'same-origin' }));
    if (storable(res) && (await matchesStamp(res, stamp)))
        return res;
    const again = await fetch(new Request(href, { cache: 'reload', credentials: 'same-origin' }));
    return storable(again) && (await matchesStamp(again, stamp)) ? again : null;
}
async function keepPage(key, res) {
    const html = await res.clone().text();
    for (const href of stampedRefs(html, key)) {
        if (await stored(href))
            continue;
        const stamp = stampOf(new URL(href));
        if (stamp === null)
            return;
        const got = await verified(href, stamp);
        if (got === null)
            return;
        const cache = await openExisting();
        if (cache === null)
            return;
        await cache.put(href, got);
    }
    const cache = await openExisting();
    if (cache !== null)
        await cache.put(key, res);
}
async function precache() {
    const fetched = [];
    for (const rel of ASSETS) {
        const url = new URL(rel, sw.location.href);
        const res = await fetch(new Request(url.href, { cache: 'reload', credentials: 'same-origin' }));
        if (!storable(res))
            throw new Error(`precache: ${url.pathname} answered ${res.status}`);
        const stamp = stampOf(url);
        if (stamp !== null && !(await matchesStamp(res, stamp)))
            throw new Error(`precache: ${url.pathname} does not match its stamp ${stamp}`);
        fetched.push([url.searchParams.has('v') ? url.href : pageKey(url), res]);
    }
    const keys = new Set(fetched.map(([k]) => k));
    for (const [key, res] of fetched) {
        if (key.includes('?') || !(key.endsWith('/') || key.endsWith('.html')))
            continue;
        for (const href of stampedRefs(await res.clone().text(), key)) {
            if (!keys.has(href))
                throw new Error(`precache: ${new URL(key).pathname} loads ${href}, which this worker does not list`);
        }
    }
    const cache = await caches.open(CACHE);
    for (const [key, res] of fetched)
        await cache.put(key, res);
}
async function dropCaches(keep) {
    const names = await caches.keys();
    await Promise.all(names.filter((n) => n.startsWith(PREFIX) && n !== keep).map((n) => caches.delete(n)));
}
async function page(event, url) {
    const key = pageKey(url);
    try {
        const res = await fetch(revalidating(event.request));
        if (storable(res) && PAGES.has(key)) {
            const copy = res.clone();
            event.waitUntil(keepPage(key, copy).catch(() => undefined));
        }
        return res;
    }
    catch (err) {
        const kept = await stored(key);
        if (kept)
            return kept;
        throw err;
    }
}
async function stamped(event, stamp) {
    const kept = await stored(event.request);
    if (kept)
        return kept;
    let res = await fetch(event.request);
    if (storable(res) && !(await matchesStamp(res, stamp))) {
        try {
            const again = await fetch(new Request(event.request.url, { cache: 'reload', credentials: 'same-origin' }));
            if (storable(again))
                res = again;
        }
        catch {
        }
    }
    if (storable(res) && (await matchesStamp(res, stamp))) {
        const copy = res.clone();
        event.waitUntil(openExisting().then((c) => c?.put(event.request, copy)).catch(() => undefined));
    }
    return res;
}
async function staleWhileRevalidate(event) {
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
function isPage(req, url) {
    if (url.pathname.endsWith('/') || url.pathname.endsWith('.html'))
        return true;
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
    if (KILL)
        return;
    const req = event.request;
    if (req.method !== 'GET')
        return;
    const url = new URL(req.url);
    if (url.origin !== sw.location.origin || url.pathname.startsWith('/cdn-cgi/'))
        return;
    if (isPage(req, url)) {
        event.respondWith(page(event, url));
        return;
    }
    const stamp = stampOf(url);
    if (stamp !== null)
        event.respondWith(stamped(event, stamp));
    else if (url.search === '' && LISTED.has(url.href))
        event.respondWith(staleWhileRevalidate(event));
});
