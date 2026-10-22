// The page's own helpers, called in Node through tests/load.ts: the number parser, the date and
// time formatters, the zone abbreviation (its error path included), the HTML escape and the
// offline nearest-city zone lookup. Added 21 October 2026 with the TypeScript move. Every expected
// value below was also produced by the 1.1.3 app.js (the hand-written JavaScript the TypeScript
// replaced), so these vectors pin the behaviour across the move.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadPage } from './load.ts';

const page = loadPage();
/** Calls a page function by name with JSON-able arguments; the result comes back through JSON. */
const call = <T>(fn: string, ...args: unknown[]): T => {
  page.set('__args', JSON.stringify(args));
  return JSON.parse(page.run<string>(`JSON.stringify(${fn}(...JSON.parse(__args)) ?? null)`)) as T;
};
const ch = (...codes: number[]): string => String.fromCharCode(...codes);
const MINUS = ch(0x2212);

test('parseDecimal: the shared Labs parser', () => {
  const cases: Array<[string | null, number, number, number | null]> = [
    ['60,39', -90, 90, 60.39],
    ['60.39', -90, 90, 60.39],
    [' 60,39 ', -90, 90, 60.39],
    [`${MINUS}33.87`, -90, 90, -33.87],
    [`${ch(0xFF0D)}33,87`, -90, 90, -33.87],
    ['+5', -90, 90, 5],
    ['.5', -90, 90, 0.5],
    ['5.', -90, 90, 5],
    // Whitespace inside is dropped before parsing: "6 0.39" reads as 60.39, as in 1.1.3.
    ['6 0.39', -90, 90, 60.39],
    ['90', -90, 90, 90],
    ['-90', -90, 90, -90],
    ['90.0001', -90, 90, null],
    ['-180.5', -180, 180, null],
    ['1e2', -180, 180, null],
    ['0x10', -180, 180, null],
    ['1,5,3', -90, 90, null],
    ['60,', -90, 90, null],
    [',5', -90, 90, null],
    ['N60', -90, 90, null],
    ['abc', -90, 90, null],
    ['', -90, 90, null],
    ['   ', -90, 90, null],
    // Arabic-Indic digits are not ASCII digits: refused, as in 1.1.3.
    [ch(0x0666, 0x0660), -90, 90, null],
    [null, -90, 90, null],
  ];
  for (const [text, min, max, want] of cases) assert.equal(call('parseDecimal', text, min, max), want, JSON.stringify(text));
  assert.equal(page.run('parseDecimal(undefined, -90, 90)'), null);
});

test('formatDate: DD/Month/YYYY from the UTC date parts', () => {
  const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
  months.forEach((name, i) => {
    assert.equal(page.run(`formatDate(new Date(Date.UTC(2026, ${i}, 5, 12)))`), `05/${name}/2026`);
  });
  assert.equal(page.run('formatDate(new Date(Date.UTC(1900, 0, 1, 12)))'), '01/January/1900');
  assert.equal(page.run('formatDate(new Date(Date.UTC(2100, 11, 31, 12)))'), '31/December/2100');
});

test('formatTime: HH:MM:SS in the target zone, not the machine zone', () => {
  assert.equal(page.run("formatTime(new Date(Date.UTC(2026, 5, 21, 22, 5, 3)), 'Europe/Oslo')"), '00:05:03');
  assert.equal(page.run("formatTime(new Date(Date.UTC(2026, 5, 21, 22, 5, 3)), 'UTC')"), '22:05:03');
  assert.equal(page.run("formatTime(new Date(Date.UTC(2026, 0, 15, 10, 0, 0)), 'Pacific/Apia')"), '23:00:00');
});

test('getTimezoneAbbreviation: Intl short names, and "" on every error path', () => {
  assert.equal(call('getTimezoneAbbreviation', 'America/New_York', '2026-07-01'), 'EDT');
  assert.equal(call('getTimezoneAbbreviation', 'America/New_York', '2026-01-15'), 'EST');
  assert.equal(call('getTimezoneAbbreviation', 'Europe/Oslo', '2026-01-15'), 'GMT+1');
  assert.equal(call('getTimezoneAbbreviation', 'Europe/Oslo', '2026-07-01'), 'GMT+2');
  // A zone Intl does not know throws a RangeError inside; the catch gives "".
  assert.equal(call('getTimezoneAbbreviation', 'Not/AZone', '2026-01-15'), '');
  // An empty date field: Date.UTC of undefined parts is NaN, formatToParts throws, "" again.
  assert.equal(call('getTimezoneAbbreviation', 'Europe/Oslo', ''), '');
  assert.equal(call('getTimezoneAbbreviation', 'Europe/Oslo', '2026'), '');
});

test('escapeHtml: the four characters a city name could break markup with', () => {
  assert.equal(call('escapeHtml', '& < > "'), '&amp; &lt; &gt; &quot;');
  assert.equal(call('escapeHtml', "Côte d'Ivoire"), "Côte d'Ivoire");
  assert.equal(call('escapeHtml', '<b>&amp;</b>'), '&lt;b&gt;&amp;amp;&lt;/b&gt;');
});

test('nearestCityTimezone: offline, at the poles and across the antimeridian', () => {
  const cases: Array<[number, number, string]> = [
    [60.39, 5.32, 'Europe/Oslo'],
    [78.2233, 15.6469, 'Arctic/Longyearbyen'],
    [90, 0, 'Arctic/Longyearbyen'],
    [-90, 0, 'Atlantic/South_Georgia'],
    [0, 180, 'Pacific/Tarawa'],
    [0, -180, 'Pacific/Tarawa'],
    [-18.14, 179.99, 'Pacific/Fiji'],
    [-13.83, -171.77, 'Pacific/Apia'],
    [1.8721, -157.4278, 'Pacific/Honolulu'],
  ];
  for (const [lat, lon, want] of cases) assert.equal(call('nearestCityTimezone', lat, lon), want, `${lat}, ${lon}`);
});

test('init: the date field defaults to today, YYYY-MM-DD, and the city tab is active', () => {
  assert.match(page.run<string>('els.dateInput.value'), /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(page.run('els.dateInput.value === getTodayString()'), true);
  assert.equal(page.run('state.tab'), 'city');
});
