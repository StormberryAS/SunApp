// The page scenarios behind tests/page-vectors.json: what a person does on the page (search,
// keyboard, mouse, both tabs, the errors, a city and date matrix) and a snapshot of the page after
// each step. tests/capture-vectors.ts ran them against the SunApp 1.1.3 page and froze the
// snapshots; tests/smoke.ts runs them against the current page and requires the same snapshots,
// string for string. Not a test file itself. Added 21 October 2026 with the TypeScript move.
//
// Determinism. Every page runs on a frozen clock (NOW, and `new Date()` with no argument as well as
// Date.now, because getTodayString() reads the former), in a fixed time zone, and the mouse is
// parked in the top left corner after every press, so Chromium's own hover update after a layout
// change can never highlight a dropdown row by accident.

import { createHash } from 'node:crypto';
import { openPage, sleep } from './cdp.ts';
import type { Browser, FromBrowser, Page } from './cdp.ts';

/** The frozen instant every scenario starts from: 21 October 2026, 10:00 UTC. */
export const NOW = Date.parse('2026-10-21T10:00:00Z');
const J = JSON.stringify;

/**
 * Freezes the page's clock at t (running on from there in real time): Date.now and a Date made
 * with no argument both start at t. Everything else about Date is left as the browser has it.
 */
export const clockAt = (t: number): string => `(() => {
  const R = Date, off = ${t} - R.now();
  function D(...a) { if (!new.target) return R(); return a.length ? new R(...a) : new R(R.now() + off); }
  D.prototype = R.prototype; Object.setPrototypeOf(D, R);
  D.now = () => R.now() + off; D.UTC = R.UTC; D.parse = R.parse;
  globalThis.Date = D;
})();`;

/** Removes every ?v= stamp, so trees whose files differ only in their bytes compare equal. */
export const unstamp = (s: string): string => s.replace(/\?v=[0-9a-f]{8}/g, '?v=STAMP');

/** What one snapshot holds: the parts of the page app.js writes to, and a hash of all of it. */
export type Snap = Record<string, string | boolean | number | null>;

/** The page as app.js left it, after two animation frames (the day bar's width is set in one). */
export async function snapshot(p: Page): Promise<Snap> {
  const raw: FromBrowser = await p.ev(`new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => {
    const o = (id) => { const e = document.getElementById(id); return e ? e.outerHTML : null; };
    const v = (id) => document.getElementById(id).value;
    done({
      tabs: ['tab-city', 'tab-gps'].map((id) => { const b = document.getElementById(id); return id + ' [' + b.className + '] aria-selected=' + b.getAttribute('aria-selected'); }).join(' | '),
      panelCityHidden: document.getElementById('panel-city').hidden, panelGpsHidden: document.getElementById('panel-gps').hidden,
      search: v('city-search'), searchExpanded: document.getElementById('city-search').getAttribute('aria-expanded'),
      dropdown: o('city-dropdown'), selected: o('city-selected'),
      lat: v('lat-input'), latInvalid: document.getElementById('lat-input').getAttribute('aria-invalid'), latError: o('lat-error'),
      lon: v('lon-input'), lonInvalid: document.getElementById('lon-input').getAttribute('aria-invalid'), lonError: o('lon-error'),
      echo: o('gps-echo'), date: v('date-input'), error: o('error-msg'),
      resultsHidden: document.getElementById('results-card').hidden,
      results: ['res-coords', 'res-date', 'res-tz', 'res-sunrise', 'res-noon', 'res-sunset', 'res-daylength']
        .map((id) => { const e = document.getElementById(id); return (e.className ? '[' + e.className + '] ' : '') + e.innerHTML; }).join(' | '),
      resultsHtml: o('results-card'),
      barWidth: document.getElementById('day-bar-fill').style.width,
      loadingHidden: document.getElementById('loading-overlay').hidden,
      focus: document.activeElement ? (document.activeElement.id || document.activeElement.tagName) : null,
      doc: document.documentElement.outerHTML,
    });
  })))`);
  const snap: Snap = {};
  for (const [k, val] of Object.entries(raw as Record<string, string | boolean | number | null>)) {
    // The whole document and the whole results card are kept as hashes: the readable fields above
    // say what changed, the hashes that nothing else did.
    if (k === 'doc') snap.docSha256 = createHash('sha256').update(unstamp(String(val))).digest('hex');
    else if (k === 'resultsHtml') snap.resultsSha256 = createHash('sha256').update(unstamp(String(val))).digest('hex');
    else snap[k] = typeof val === 'string' ? unstamp(val) : val;
  }
  return snap;
}

/** Moves the mouse to the top left corner, away from every control. */
export const parkMouse = async (p: Page): Promise<void> => {
  await p.s('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 1, y: 1 });
  await sleep(30);
};

/** Presses an element with real mouse events, then parks the mouse. */
export const press = async (p: Page, selector: string): Promise<void> => {
  await p.click(selector);
  await parkMouse(p);
};

/** Types a city query, then presses the row of that name and country. */
export async function pickCity(p: Page, query: string, name: string, country: string): Promise<void> {
  await p.type('#city-search', query);
  const i: number = await p.ev(`(els.cityDropdown._matches || []).findIndex((c) => c.name === ${J(name)} && c.country === ${J(country)})`);
  if (i < 0) throw new Error(`"${query}" does not list ${name}, ${country}`);
  await press(p, `#city-dropdown li:nth-child(${i + 1})`);
}

/** One recorded step: its name and the page after it. */
export interface Vector { name: string; snap: Snap }

type Step = (p: Page, rec: (name: string) => Promise<void>) => Promise<void>;
interface Scenario { name: string; timezone: string; width: number; at?: number; run: Step }

/** The catalogue entries the matrix uses: query, name, country. */
export const MATRIX_CITIES: ReadonlyArray<readonly [string, string, string]> = [
  ['bergen', 'Bergen', 'Norway'],
  ['tromso', 'Tromsø', 'Norway'],
  ['longyearbyen', 'Longyearbyen', 'Svalbard and Jan Mayen'],
  ['reykjavik', 'Reykjavík', 'Iceland'],
  ['murmansk', 'Murmansk', 'Russia'],
  ['nuuk', 'Nuuk', 'Greenland'],
  ['anchorage', 'Anchorage, AK', 'USA'],
  ['singapore', 'Singapore', 'Singapore'],
  ['ushuaia', 'Ushuaia', 'Argentina'],
  ['sydney', 'Sydney', 'Australia'],
  ['apia', 'Apia', 'Samoa'],
  ['suva', 'Suva', 'Fiji'],
  ['santiago', 'Santiago', 'Chile'],
  ['london', 'London', 'UK'],
  ['new york', 'New York City, NY', 'USA'],
  ['honolulu', 'Honolulu, HI', 'USA'],
];

/**
 * The matrix dates: polar night and midnight sun, the 2026 boundary day at Longyearbyen, the sun's
 * return to Tromsø, both equinoxes, both solstices, both European DST changes, the southern DST
 * start, and the ends of the date picker's useful range.
 */
export const MATRIX_DATES = ['2026-01-15', '2026-03-29', '2026-04-19', '2026-06-21', '2026-09-06', '2026-10-25', '2026-12-21', '1900-01-01'] as const;

const SEARCHES = ['tromso', 'Tromsø', 'erdal', 'gothenburg', 'cologne', 'herat', 'kleppesto', 'sao paulo', 'new york', '&', '<b>', 'a', 'zz', '  bergen  ', 'BERGEN'];

/** The scenarios, in order. The vectors are their recorded steps, named "scenario / step". */
export const SCENARIOS: Scenario[] = [
  {
    name: 'first load 1280', timezone: 'Europe/Oslo', width: 1280,
    run: async (_p, rec) => { await rec('page as loaded'); },
  },
  {
    name: 'first load 390', timezone: 'America/Los_Angeles', width: 390,
    run: async (_p, rec) => { await rec('page as loaded'); },
  },
  {
    // 23:59:30 on 21 October in Los Angeles is already 22 October in UTC: the date field must say
    // the local date, the 21st.
    name: 'first load just before midnight', timezone: 'America/Los_Angeles', width: 1280, at: Date.parse('2026-10-22T06:59:30Z'),
    run: async (_p, rec) => { await rec('date field'); },
  },
  {
    // 00:00:30 on 22 October in Kiritimati (UTC+14) is still 21 October in UTC: the field says the 22nd.
    name: 'first load just after midnight', timezone: 'Pacific/Kiritimati', width: 1280, at: Date.parse('2026-10-21T10:00:30Z'),
    run: async (_p, rec) => { await rec('date field'); },
  },
  {
    name: 'city search', timezone: 'Europe/Oslo', width: 1280,
    run: async (p, rec) => {
      for (const q of SEARCHES) {
        await p.type('#city-search', q);
        await rec(`query ${J(q)}`);
      }
    },
  },
  {
    name: 'keyboard', timezone: 'Europe/Oslo', width: 1280,
    run: async (p, rec) => {
      await p.type('#city-search', 'bergen');
      await p.key('ArrowDown'); await p.key('ArrowDown');
      await rec('two ArrowDown');
      await p.key('Enter');
      await rec('Enter picks the second row');
      // The 002980e regressions: a closed list has nothing for Enter to pick.
      await press(p, '#city-clear-btn');
      await rec('Clear');
      await p.key('Enter');
      await rec('Enter after Clear picks nothing');
      await p.type('#city-search', 'oslo');
      await p.key('ArrowDown');
      await p.key('Escape');
      await rec('Escape closes the list');
      await p.key('Enter');
      await rec('Enter after Escape picks nothing');
      await p.type('#city-search', 'tromso');
      await p.key('ArrowUp');
      await rec('ArrowUp at the top');
      await p.key('Enter');
      await rec('Enter after ArrowUp picks the first row');
      await p.type('#city-search', 'kleppesto');
      await p.key('Escape');
      await p.key('ArrowUp');
      await p.key('Enter');
      await rec('ArrowUp then Enter on a closed list picks nothing');
    },
  },
  {
    name: 'mouse', timezone: 'Europe/Oslo', width: 1280,
    run: async (p, rec) => {
      await p.type('#city-search', 'santiago');
      const box = await p.ev("(() => { const b = document.querySelector('#city-dropdown li:nth-child(3)').getBoundingClientRect(); return { x: b.left + b.width / 2, y: b.top + b.height / 2 }; })()");
      await p.s('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y });
      await sleep(60);
      await rec('hover highlights the third row');
      await press(p, 'h1');
      await rec('a click outside closes the list');
    },
  },
  {
    name: 'errors', timezone: 'Europe/Oslo', width: 1280,
    run: async (p, rec) => {
      await press(p, '#calculate-btn');
      await rec('Calculate with no city');
      await pickCity(p, 'bergen', 'Bergen', 'Norway');
      await press(p, '#calculate-btn');
      await rec('Bergen today');
      await p.setDate('#date-input', '');
      await press(p, '#calculate-btn');
      await rec('an empty date');
      await press(p, '#tab-gps');
      await rec('coordinates tab after the error');
      await press(p, '#tab-city');
      await p.setDate('#date-input', '2026-06-21');
      await press(p, '#calculate-btn');
      await rec('fixed: Bergen at midsummer');
    },
  },
  {
    name: 'coordinates', timezone: 'Europe/Oslo', width: 390,
    run: async (p, rec) => {
      await press(p, '#tab-gps');
      const pairs: Array<[string, string, string, string]> = [
        ['valid', '60.39', '5.32', '2026-10-21'],
        ['decimal comma', '60,39', '5,32', '2026-10-21'],
        ['typographic minus', `${String.fromCharCode(0x2212)}33,87`, '151.21', '2026-12-21'],
        ['latitude out of range', '91', '5.32', '2026-10-21'],
        ['longitude out of range', '60.39', '181', '2026-10-21'],
        ['letters', 'abc', 'def', '2026-10-21'],
        ['empty', '', '', '2026-10-21'],
        ['North Pole, the classifier trap', '89.99', '0', '2026-03-19'],
        ['South Pole', '-89.99', '0', '2026-09-22'],
        ['Kiritimati by coordinates', '1.8721', '-157.4278', '2026-06-21'],
        ['Longyearbyen boundary day', '78.2233', '15.6469', '2026-04-19'],
      ];
      for (const [name, lat, lon, date] of pairs) {
        await p.type('#lat-input', lat);
        await p.type('#lon-input', lon);
        await p.setDate('#date-input', date);
        await press(p, '#calculate-btn');
        await rec(name);
      }
      // Editing a field clears its error and the "Using ..." line.
      await p.type('#lat-input', '60.39');
      await p.type('#lon-input', 'x');
      await press(p, '#calculate-btn');
      await rec('longitude alone in error');
      await p.type('#lon-input', '5');
      await rec('editing the longitude clears its error');
      await press(p, '#calculate-btn');
      await rec('calculated again');
      await p.type('#lat-input', '6');
      await rec('editing the latitude clears the echo');
    },
  },
  {
    name: 'matrix', timezone: 'Europe/Oslo', width: 1280,
    run: async (p, rec) => {
      for (const [query, name, country] of MATRIX_CITIES) {
        await pickCity(p, query, name, country);
        for (const date of MATRIX_DATES) {
          await p.setDate('#date-input', date);
          await press(p, '#calculate-btn');
          await rec(`${name} ${date}`);
        }
      }
    },
  },
];

/** Runs every scenario in a fresh page of base and returns the recorded vectors, in order. */
export async function runScenarios(browser: Browser, base: string, { only = null, after = null }: {
  only?: RegExp | null;
  /** Called with each scenario's page before it closes, to check what it logged. */
  after?: ((p: Page, scenario: string) => void) | null;
} = {}): Promise<Vector[]> {
  const out: Vector[] = [];
  for (const sc of SCENARIOS) {
    if (only && !only.test(sc.name)) continue;
    const p = await openPage(browser, base, { width: sc.width, timezone: sc.timezone, init: clockAt(sc.at ?? NOW) });
    try {
      await parkMouse(p);
      await sc.run(p, async (step) => { out.push({ name: `${sc.name} / ${step}`, snap: await snapshot(p) }); });
      if (after) after(p, sc.name);
    } finally {
      await p.close();
    }
  }
  return out;
}
