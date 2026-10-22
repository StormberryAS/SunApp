// The parity contracts with the Android port: the solar golden corpus is what the bundled SunCalc
// gives (tools/gen-solar-golden.ts --check), the web catalogue and the Android asset are the same
// data (tools/check-city-parity.py), and the page's day classifier (onCalculate, section 9.4) gives
// the label in daykind-parity.csv for every one of its 360 rows. Added 21 October 2026 with the
// TypeScript move.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { loadPage } from './load.ts';
import { defined } from './helpers.ts';

const root = new URL('../', import.meta.url).pathname;

test('suncalc-golden.csv is exactly what tools/gen-solar-golden.ts writes from suncalc.js', () => {
  const out = execFileSync(process.execPath, [`${root}tools/gen-solar-golden.ts`, '--check'], { encoding: 'utf8' });
  assert.match(out, /golden check OK: .* 350 data rows/);
});

test('cities.js and data/cities.tsv hold the same catalogue', () => {
  const out = execFileSync('python3', ['-I', `${root}tools/check-city-parity.py`], { encoding: 'utf8' });
  assert.match(out, /^city parity OK: 25007 rows/);
});

test("the page's day classifier agrees with daykind-parity.csv on all 360 rows", async () => {
  const csv = readFileSync(new URL('../android/app/src/test/resources/daykind-parity.csv', import.meta.url), 'utf8').trim().split('\n');
  assert.equal(csv[0], 'place,lat,lon,date,day_kind');
  const rows = csv.slice(1).map((line) => line.split(','));
  assert.equal(rows.length, 360);
  const page = loadPage();
  // The coordinates tab, as a person would use it: type the numbers and the date, press Calculate.
  page.run("switchTab('gps')");
  const bad: string[] = [];
  for (const row of rows) {
    const [place, lat, lon, date, want] = row.map((x, i) => defined(x, `column ${i}`));
    page.set('__row', JSON.stringify([lat, lon, date]));
    await page.run<Promise<void>>('(() => { const [la, lo, d] = JSON.parse(__row); els.latInput.value = la; els.lonInput.value = lo; els.dateInput.value = d; return onCalculate(); })()');
    const html = page.run<string>('els.resSunrise.innerHTML + "|" + els.resSunset.innerHTML');
    const got = /Midnight Sun/.test(html) ? 'MIDNIGHT_SUN' : /Polar Night/.test(html) ? 'POLAR_NIGHT' : 'NORMAL';
    // A normal day shows two times; a polar day the same label in both cards.
    if (got === 'NORMAL') assert.match(html, /^\d{2}:\d{2}:\d{2}\|\d{2}:\d{2}:\d{2}$/, `${place} ${date}`);
    if (got !== want) bad.push(`${place} ${date}: page ${got}, csv ${want}`);
    assert.equal(page.run('els.resultsCard.hidden'), false, `${place} ${date}: the result is shown`);
  }
  assert.deepEqual(bad, []);
});
