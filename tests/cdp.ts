// A small driver for headless Chromium over the DevTools protocol, with nothing but Node 24
// built-ins (its own WebSocket). Used by tests/smoke.ts and tests/capture-vectors.ts. Not a test
// file itself.
//
// Copied on 21 October 2026 from EclipseTracker's tests/cdp.ts (itself from GDPRLetterBuilder's
// tests/cdp.mjs). Changed in one way: EclipseTracker marks a page ready with data-ready on <html>,
// and SunApp has no such marker, so a page counts as ready once init() has filled in the date field
// (it runs on DOMContentLoaded). SunApp has no first-run notice either, so the gate handling finds
// nothing to close. openPage still takes a time zone (Emulation.setTimezoneOverride), which is
// what decides the date field's default.
//
// Every page opens in a fresh browser context. Everything the page requests, logs or is refused by
// its policy is recorded on page.log.
//
// Types: what comes back over the DevTools protocol, and what a page evaluation returns, is JSON
// from the browser, outside anything tsc can know. It is typed FromBrowser (any) in this one place
// on purpose, so the checks read it as they always have; everything of this file's own is typed.

import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** JSON from the browser (a protocol result, an event, an evaluated value): untyped on purpose. */
export type FromBrowser = any;
type Send = (method: string, params?: Record<string, unknown>, sessionId?: string) => Promise<FromBrowser>;
type Listener = (message: FromBrowser) => void;

/** A running Chromium: send a protocol command, listen to every message, close it. */
export interface Browser {
  readonly send: Send;
  readonly listeners: Set<Listener>;
  readonly close: () => Promise<void>;
  readonly profile: string;
}

/** What a page recorded: errors, requests (and those off the origin), policy refusals, the console, downloads. */
export interface PageLog {
  readonly errors: string[];
  readonly requests: Array<{ url: string; type: string; method: string; postData: string; headers: Record<string, string>; t: number }>;
  readonly offsite: string[];
  readonly csp: string[];
  readonly console: string[];
  readonly downloads: FromBrowser[];
}

export interface PageOptions {
  width?: number;
  height?: number;
  init?: string | null;
  gate?: 'close' | 'keep';
  mobile?: boolean;
  downloads?: string | null;
  base?: string | null;
  timezone?: string | null;
}

type Key = 'Tab' | 'Space' | 'Enter' | 'Escape' | 'ArrowDown' | 'ArrowUp' | 'Backspace';

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
const J = JSON.stringify;

export async function launchChromium({ chromium = '/usr/bin/chromium', out, args = [] }: { chromium?: string; out: string; args?: readonly string[] }): Promise<Browser> {
  mkdirSync(out, { recursive: true });
  const profile = mkdtempSync(join(out, 'profile-'));
  const proc = spawn(chromium, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-background-networking', '--disable-sync', '--disable-component-update', '--disable-breakpad', '--disable-crash-reporter',
    '--disable-features=Translate,OptimizationHints,MediaRouter', ...args, 'about:blank'], {
    stdio: ['ignore', 'ignore', 'pipe'],
    // Keeps Chromium's own folders inside the throwaway profile instead of the person's ~/.config/chromium.
    env: { ...process.env, XDG_CONFIG_HOME: join(profile, 'xdg-config'), CHROME_CONFIG_HOME: join(profile, 'xdg-config'), XDG_CACHE_HOME: join(profile, 'xdg-cache') },
  });
  const wsUrl = await new Promise<string>((res, rej) => {
    let buf = '';
    const t = setTimeout(() => rej(new Error('Chromium did not start')), 30000);
    proc.stderr.on('data', (d) => {
      buf += d;
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf);
      if (m && m[1]) { clearTimeout(t); res(m[1]); }
    });
  });
  const ws = new WebSocket(wsUrl);
  await new Promise((r) => ws.addEventListener('open', r, { once: true }));
  let seq = 0;
  const pending = new Map<number, { res: (v: FromBrowser) => void; rej: (e: Error) => void }>();
  const listeners = new Set<Listener>();
  ws.addEventListener('message', (ev) => {
    const m: FromBrowser = JSON.parse(String(ev.data));
    const p = m.id ? pending.get(m.id) : undefined;
    if (p) {
      pending.delete(m.id);
      if (m.error) p.rej(new Error(JSON.stringify(m.error)));
      else p.res(m.result);
    } else for (const fn of listeners) fn(m);
  });
  const send: Send = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const id = ++seq;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  const close = async (): Promise<void> => {
    try { await send('Browser.close'); } catch { /* already gone */ }
    proc.kill('SIGKILL');
  };
  return { send, listeners, close, profile };
}

/**
 * Opens url in a new context. Options: width, height, init (a script run before the page's own),
 * gate ('close' | 'keep'), mobile, downloads (a folder for real downloads), scale.
 */
export async function openPage(browser: Browser, url: string, { width = 1280, height = 900, init = null, gate = 'close', mobile = width < 600, downloads = null, base = null, timezone = null }: PageOptions = {}) {
  const { send, listeners } = browser;
  const { browserContextId } = await send('Target.createBrowserContext');
  const { targetId } = await send('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  const s = (m: string, p?: Record<string, unknown>): Promise<FromBrowser> => send(m, p, sessionId);
  const origin = new URL(base || url).origin;
  const log: PageLog = { errors: [], requests: [], offsite: [], csp: [], console: [], downloads: [] };
  const onMsg: Listener = (m) => {
    if (m.method === 'Browser.downloadProgress' && m.params.state === 'completed') log.downloads.push(m.params);
    if (m.method === 'Browser.downloadWillBegin') log.downloads.push({ begin: m.params.suggestedFilename, url: m.params.url });
    if (m.sessionId !== sessionId) return;
    if (m.method === 'Runtime.exceptionThrown') log.errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    if (m.method === 'Runtime.consoleAPICalled') {
      const text = m.params.args.map((a: FromBrowser) => a.value ?? a.description).join(' ');
      log.console.push(`${m.params.type}: ${text}`);
      if (m.params.type === 'error') log.errors.push(text);
    }
    if (m.method === 'Log.entryAdded') {
      if (/Content Security Policy|Refused to/i.test(m.params.entry.text)) log.csp.push(m.params.entry.text);
      else if (m.params.entry.level === 'error') log.errors.push(`${m.params.entry.source}: ${m.params.entry.text}`);
    }
    if (m.method === 'Network.requestWillBeSent') {
      const r = m.params.request;
      log.requests.push({ url: r.url, type: m.params.type, method: r.method, postData: r.postData || '', headers: r.headers, t: Date.now() });
      if (!r.url.startsWith(origin) && !r.url.startsWith('data:') && !r.url.startsWith('blob:')) log.offsite.push(r.url);
    }
  };
  listeners.add(onMsg);
  await s('Runtime.enable');
  await s('Network.enable');
  await s('Page.enable');
  await s('Log.enable');
  await s('DOM.enable');
  await s('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
  if (timezone) await s('Emulation.setTimezoneOverride', { timezoneId: timezone });
  if (downloads) await send('Browser.setDownloadBehavior', { behavior: 'allowAndName', downloadPath: downloads, browserContextId, eventsEnabled: true });
  await s('Page.addScriptToEvaluateOnNewDocument', { source: "window.__csp=[];document.addEventListener('securitypolicyviolation',e=>window.__csp.push(e.violatedDirective+' '+e.blockedURI),true);" });
  if (init) await s('Page.addScriptToEvaluateOnNewDocument', { source: init });
  const ev = async (expression: string): Promise<FromBrowser> => {
    const r = await s('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
  };
  const waitFor = async (expression: string, ms = 10000): Promise<boolean> => {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      try { if (await ev(expression)) return true; } catch { /* the page is still loading */ }
      await sleep(50);
    }
    return false;
  };
  const load = async (target = url): Promise<void> => {
    await s('Page.navigate', { url: target });
    await waitFor("document.readyState === 'complete'", 20000);
    await waitFor("(document.getElementById('date-input')?.value ?? '') !== ''", 20000);
    await sleep(150);
  };
  await load();
  const gateShown = await ev("!!document.querySelector('.sb-gate-overlay')");
  if (gate === 'close' && gateShown) {
    await ev("document.querySelector('.sb-gate-btn').click()");
    if (!(await waitFor("!document.querySelector('.sb-gate-overlay') && !document.querySelector('main').inert", 5000))) throw new Error('the first-run notice did not close');
  }
  const settle = async (): Promise<void> => {
    let last = await ev('scrollY');
    for (let i = 0; i < 40; i++) {
      await sleep(50);
      const now = await ev('scrollY');
      if (now === last) return;
      last = now;
    }
  };
  // Presses an element in its centre with real mouse events, after scrolling it into view and
  // checking nothing covers it there.
  const click = async (selector: string): Promise<void> => {
    await ev(`document.querySelector(${J(selector)})?.scrollIntoView({ block: 'center', behavior: 'instant' })`);
    await settle();
    const r = await ev(`(() => {
      const el = document.querySelector(${J(selector)});
      if (!el) return { err: 'not found' };
      const b = el.getBoundingClientRect();
      if (!b.width || !b.height) return { err: 'not visible' };
      const x = b.left + b.width / 2, y = b.top + b.height / 2;
      const hit = document.elementFromPoint(x, y);
      return { x, y, ok: !!hit && (hit === el || el.contains(hit) || (el.labels && [...el.labels].some((l) => l.contains(hit)))) };
    })()`);
    if (r.err || !r.ok) throw new Error(`cannot press ${selector}: ${r.err || 'covered'}`);
    await s('Input.dispatchMouseEvent', { type: 'mouseMoved', x: r.x, y: r.y });
    await s('Input.dispatchMouseEvent', { type: 'mousePressed', x: r.x, y: r.y, button: 'left', clickCount: 1 });
    await s('Input.dispatchMouseEvent', { type: 'mouseReleased', x: r.x, y: r.y, button: 'left', clickCount: 1 });
    await sleep(80);
  };
  const KEYS: Record<Key, { key: string; code: string; windowsVirtualKeyCode: number; text?: string }> = {
    Tab: { key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9 },
    Space: { key: ' ', code: 'Space', windowsVirtualKeyCode: 32, text: ' ' },
    Enter: { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, text: '\r' },
    Escape: { key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 },
    ArrowDown: { key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 },
    ArrowUp: { key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 },
    Backspace: { key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 },
  };
  const key = async (k: Key, { shift = false }: { shift?: boolean } = {}): Promise<void> => {
    const d = KEYS[k];
    const modifiers = shift ? 8 : 0;
    await s('Input.dispatchKeyEvent', { type: 'keyDown', modifiers, ...d });
    await s('Input.dispatchKeyEvent', { type: 'keyUp', modifiers, key: d.key, code: d.code, windowsVirtualKeyCode: d.windowsVirtualKeyCode });
    await sleep(40);
  };
  // Types into a field as a person would: focus, select what is there, insert the text.
  const type = async (selector: string, text: string, { replace = true }: { replace?: boolean } = {}): Promise<void> => {
    await ev(`(() => { const t = document.querySelector(${J(selector)}); t.scrollIntoView({ block: 'center', behavior: 'instant' }); t.focus(); ${replace ? 't.select && t.select();' : 't.selectionStart = t.selectionEnd = t.value.length;'} })()`);
    if (replace) {
      await s('Input.dispatchKeyEvent', { type: 'keyDown', ...KEYS.Backspace });
      await s('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    }
    if (text) await s('Input.insertText', { text });
    await sleep(60);
  };
  // A date field: set as the browser's own picker would (focus, the value, then input and change
  // events). A person always focuses the field first; the page undoes input events that come with no
  // press, key or focus in its first seconds, because only a browser's session restore sends those.
  const setDate = async (selector: string, iso: string): Promise<void> => {
    await ev(`(() => { const t = document.querySelector(${J(selector)}); t.focus(); t.value = ${J(iso)}; t.dispatchEvent(new Event('input', { bubbles: true })); t.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await sleep(60);
  };
  const shot = async (path: string): Promise<void> => {
    const h = await ev('Math.min(document.documentElement.scrollHeight, 12000)');
    const r = await s('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width, height: h, scale: 1 } });
    writeFileSync(path, Buffer.from(r.data, 'base64'));
  };
  const viewShot = async (path: string): Promise<void> => {
    const r = await s('Page.captureScreenshot', { format: 'png' });
    writeFileSync(path, Buffer.from(r.data, 'base64'));
  };
  const close = async (): Promise<void> => {
    listeners.delete(onMsg);
    await send('Target.closeTarget', { targetId }).catch(() => {});
    await send('Target.disposeBrowserContext', { browserContextId }).catch(() => {});
  };
  return { s, ev, waitFor, click, key, type, setDate, shot, viewShot, close, load, log, gateShown, browserContextId, settle };
}

/**
 * Every visible select whose options do not all fit the room Chromium leaves for the chosen
 * option's text (its user-agent inner box, read over the DevTools protocol), or whose text is
 * smaller than 16px (verifier item K-2, 2026-10-16: at 320 px with 200% text the chosen option was
 * cut off). Empty when every select shows every option whole.
 */
export type Page = Awaited<ReturnType<typeof openPage>>;

export async function selectsCut(m: Page): Promise<string[]> {
  const { root } = await m.s('DOM.getDocument', { depth: -1, pierce: true });
  const selects: FromBrowser[] = [];
  const walk = (n: FromBrowser): void => { if (n.nodeName === 'SELECT') selects.push(n); (n.children || []).forEach(walk); (n.shadowRoots || []).forEach(walk); };
  walk(root);
  const texts = await m.ev(`[...document.querySelectorAll('select')].map((s) => {
    if (!s.getClientRects().length) return null;
    const cs = getComputedStyle(s);
    const span = document.createElement('span');
    span.style.cssText = 'position:absolute;visibility:hidden;white-space:pre';
    span.style.font = cs.font;
    document.body.append(span);
    const widest = Math.max(...[...s.options].map((o) => { span.textContent = o.text; return span.getBoundingClientRect().width; }));
    span.remove();
    return { name: s.id || s.className, widest, font: parseFloat(cs.fontSize) };
  })`);
  if (texts.length !== selects.length) return ['the selects in the page and in its tree differ'];
  const bad: string[] = [];
  for (let i = 0; i < selects.length; i++) {
    const t = texts[i];
    if (!t) continue;
    const inner: FromBrowser[] = [];
    const find = (n: FromBrowser): void => { if (n.nodeName === 'DIV' && (n.attributes || []).includes('-internal-select-inner-element')) inner.push(n); (n.children || []).forEach(find); };
    (selects[i].shadowRoots || []).forEach(find);
    if (inner.length !== 1) { bad.push(`${t.name}: no inner text box`); continue; }
    const box = await m.s('DOM.getBoxModel', { nodeId: inner[0].nodeId });
    const room = box.model.content[2] - box.model.content[0];
    if (t.widest > room + 0.5) bad.push(`${t.name}: ${Math.round(t.widest)}px of text in ${Math.round(room)}px`);
    if (t.font < 16) bad.push(`${t.name}: text ${t.font}px`);
  }
  return bad;
}
