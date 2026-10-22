// Loads the page's three scripts into a Node vm context the way a browser does: as classic
// scripts sharing one global scope, suncalc.js, then cities.js, then the compiled app.js. Used by
// the Node tests (tests/*.test.ts) to call the page's own functions with no change to the page
// code. Not a test file itself. Added 21 October 2026 with the TypeScript move.
//
// The document is a stub, just enough for app.js: getElementById hands out one inert element
// per id, listeners are recorded and fired only when a test asks (dom.fire), and
// requestAnimationFrame runs its callback at once so the day bar's width is set before a test
// reads it. setTimeout callbacks (only the smooth scroll) are dropped.
//
// Two traps, written down so nobody has to find them again:
//  * Everything the page code creates (a Date, an array, a promise) belongs to the context's
//    realm, so `instanceof Date` in this realm is false for a page Date. Tests compare only
//    primitives: strings, numbers, booleans, and JSON.
//  * Top-level const and let of a classic script are not properties of the global object.
//    run('els.resSunrise.textContent') reaches them, because the code runs as a script in that
//    same global scope; ctx.els does not.

import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const REPO = new URL('../', import.meta.url);

/** A stand-in for one DOM element: the properties and methods app.js touches. */
export class FakeElement {
  readonly id: string;
  readonly tagName: string;
  value = '';
  className = '';
  hidden = false;
  readonly dataset: Record<string, string> = {};
  readonly style: Record<string, string> = {};
  readonly attributes = new Map<string, string>();
  readonly listeners = new Map<string, Array<(e: unknown) => unknown>>();
  readonly children: FakeElement[] = [];
  private html = '';
  private readonly dom: FakeDom;

  constructor(dom: FakeDom, id: string, tagName = 'DIV') {
    this.dom = dom;
    this.id = id;
    this.tagName = tagName;
  }

  get innerHTML(): string { return this.html; }
  set innerHTML(v: string) { this.html = String(v); if (this.html === '') this.children.length = 0; }
  /** Text, as a browser would give it back: the markup with its tags removed. */
  get textContent(): string { return this.html.replace(/<[^>]*>/g, ''); }
  set textContent(v: string | null) {
    this.html = String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  setAttribute(name: string, v: string): void {
    this.attributes.set(name, String(v));
    if (name === 'hidden') this.hidden = true;
  }
  removeAttribute(name: string): void {
    this.attributes.delete(name);
    if (name === 'hidden') this.hidden = false;
  }
  getAttribute(name: string): string | null {
    if (name === 'hidden') return this.hidden ? '' : null;
    return this.attributes.get(name) ?? null;
  }
  readonly classList = {
    set: new Set<string>(),
    toggle(name: string, on?: boolean): boolean {
      const want = on ?? !this.set.has(name);
      if (want) this.set.add(name); else this.set.delete(name);
      return want;
    },
    contains(name: string): boolean { return this.set.has(name); },
  };
  addEventListener(type: string, fn: (e: unknown) => unknown): void {
    const list = this.listeners.get(type) ?? [];
    list.push(fn);
    this.listeners.set(type, list);
  }
  appendChild(child: FakeElement): FakeElement { this.children.push(child); return child; }
  querySelectorAll(selector: string): FakeElement[] {
    return selector === 'li' ? this.children.filter((c) => c.tagName === 'LI') : [];
  }
  focus(): void { this.dom.activeElement = this; }
  scrollIntoView(): void { /* nothing to scroll */ }
  closest(): null { return null; }
}

/** The stub document, and what the page did with it. */
export class FakeDom {
  readonly elements = new Map<string, FakeElement>();
  readonly listeners = new Map<string, Array<(e: unknown) => unknown>>();
  activeElement: FakeElement | null = null;

  getElementById(id: string): FakeElement {
    let el = this.elements.get(id);
    if (!el) {
      el = new FakeElement(this, id);
      // The two tab buttons carry data-tab in index.html.
      if (id === 'tab-city') el.dataset.tab = 'city';
      if (id === 'tab-gps') el.dataset.tab = 'gps';
      this.elements.set(id, el);
    }
    return el;
  }
  createElement(tag: string): FakeElement { return new FakeElement(this, '', tag.toUpperCase()); }
  addEventListener(type: string, fn: (e: unknown) => unknown): void {
    const list = this.listeners.get(type) ?? [];
    list.push(fn);
    this.listeners.set(type, list);
  }
}

/** A loaded page: run code in its global scope, and reach its stub document. */
export interface LoadedPage {
  /** Runs code as a script in the page's global scope and returns its value (from the page's realm). */
  run<T = unknown>(code: string): T;
  /** Puts a value on the page's global object under name, for run() to use. */
  set(name: string, value: unknown): void;
  /** Fires every document listener of this type, as the browser would. */
  fire(type: string): void;
  readonly dom: FakeDom;
}

/**
 * Loads suncalc.js, cities.js and app.js from root (the repository unless given) into a fresh
 * context, then fires DOMContentLoaded so init() runs. With init: false it stops before that.
 */
export function loadPage({ root = REPO, init = true }: { root?: URL; init?: boolean } = {}): LoadedPage {
  const dom = new FakeDom();
  const sandbox: Record<string, unknown> = {
    document: dom,
    console,
    requestAnimationFrame: (fn: (t: number) => void): number => { fn(0); return 0; },
    setTimeout: (): number => 0,
  };
  const ctx = vm.createContext(sandbox);
  // window is the global object itself, as in a browser: SunCalc publishes itself on it.
  vm.runInContext('globalThis.window = globalThis;', ctx);
  for (const file of ['suncalc.js', 'cities.js', 'app.js']) {
    vm.runInContext(readFileSync(new URL(file, root), 'utf8'), ctx, { filename: file });
  }
  const page: LoadedPage = {
    run: <T>(code: string): T => vm.runInContext(code, ctx) as T,
    set: (name, value) => { (ctx as Record<string, unknown>)[name] = value; },
    fire: (type) => { for (const fn of dom.listeners.get(type) ?? []) fn({ type }); },
    dom,
  };
  if (init) page.fire('DOMContentLoaded');
  return page;
}
