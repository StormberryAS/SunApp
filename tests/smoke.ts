// The browser smoke test: the page as production serves it (tests/serve.py, with the zone's headers
// and the _config.yml exclusions), in headless Chromium. Added 21 October 2026 with the TypeScript
// move, on EclipseTracker's tests/smoke.ts.
//
//   node tests/smoke.ts [--chromium /usr/bin/chromium] [--shots DIR]
//
// Checks: every scenario of tests/scenarios.ts gives, step for step, the snapshot frozen in
// tests/page-vectors.json, which was captured from the SunApp 1.1.3 page (the last hand-written
// app.js), so the TypeScript page behaves as 1.1.3 did; no request leaves the origin, and no
// console error or policy violation under the zone's header; the compiled app.js is what is served,
// under its current stamp; the sources and tooling are not served; the page's day classifier
// agrees with daykind-parity.csv on all 360 rows; no sideways scroll at 320, 390 and 1280 px with
// a result shown or the city list open. Prints one line per check and exits 1 on the first failure.

import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
import { launchChromium, openPage } from './cdp.ts';
import type { Page } from './cdp.ts';
import { clockAt, NOW, pickCity, press, runScenarios } from './scenarios.ts';
import type { Snap, Vector } from './scenarios.ts';
import { startServer } from './server.ts';
import { defined } from './helpers.ts';

const arg = (name: string): string | null => {
  const i = process.argv.indexOf(name);
  return i > 0 ? (process.argv[i + 1] ?? null) : null;
};
const chromium = arg('--chromium') ?? '/usr/bin/chromium';
const shots = arg('--shots');
if (shots) mkdirSync(shots, { recursive: true });
const work = mkdtempSync(join(tmpdir(), 'sunapp-smoke-'));
const repo = new URL('../', import.meta.url);
const read = (p: string): string => readFileSync(new URL(p, repo), 'utf8');

const server = await startServer();
const base = server.base;
const browser = await launchChromium({ chromium, out: work });

async function cleanup(): Promise<void> {
  await browser.close();
  server.stop();
  rmSync(work, { recursive: true, force: true });
}

let passed = 0;
async function check(name: string, fn: () => Promise<void>): Promise<void> {
  try {
    await fn();
    passed += 1;
    console.log(`ok ${passed} ${name}`);
  } catch (err) {
    console.log(`not ok ${name}\n  ${String((err as Error | null)?.stack || err).split('\n').join('\n  ')}`);
    await cleanup();
    process.exit(1);
  }
}

/** Nothing off the origin, no error, no policy refusal. */
function quiet(p: Page, where: string): void {
  assert.deepEqual(p.log.offsite, [], `${where}: requests off the origin`);
  assert.deepEqual(p.log.errors, [], `${where}: console or page errors`);
  assert.deepEqual(p.log.csp, [], `${where}: policy violations`);
}

await check('the page as 1.1.3 left it: every scenario step gives its frozen snapshot', async () => {
  const frozen = JSON.parse(read('tests/page-vectors.json')) as { capturedFrom: string; vectors: Vector[] };
  assert.match(frozen.capturedFrom, /^SunApp 1\.1\.3 /);
  const now = await runScenarios(browser, base, { after: (p, sc) => quiet(p, sc) });
  assert.deepEqual(now.map((v) => v.name), frozen.vectors.map((v) => v.name), 'the same steps, in the same order');
  const diffs: string[] = [];
  now.forEach((v, i) => {
    const want: Snap = defined(frozen.vectors[i], `vector ${i}`).snap;
    for (const k of new Set([...Object.keys(want), ...Object.keys(v.snap)])) {
      if (want[k] !== v.snap[k]) diffs.push(`${v.name}: ${k}\n      1.1.3: ${JSON.stringify(want[k])}\n      now:   ${JSON.stringify(v.snap[k])}`);
    }
  });
  assert.deepEqual(diffs, [], `${diffs.length} differences`);
  console.log(`  (${now.length} snapshots match)`);
});

await check('the compiled app.js is what is served, under its current stamp', async () => {
  const res = await fetch(`${base}app.js`);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), read('app.js'));
  const stamp = createHash('sha256').update(readFileSync(new URL('app.js', repo))).digest('hex').slice(0, 8);
  const html = await (await fetch(base)).text();
  assert.ok(html.includes(`<script src="app.js?v=${stamp}"></script>`));
});

await check('sources, tooling and tests are not served; the site is', async () => {
  const hidden = ['ts/app.ts', 'ts/app-dom.d.ts', 'ts/cities.d.ts', 'tests/smoke.ts', 'tests/page-vectors.json', 'tests/serve.py', 'tools/build-ts.sh',
    'tools/gen-solar-golden.ts', 'tools/check-city-parity.py', 'package.json', 'package-lock.json', 'tsconfig.json', '.npmrc', '.gitattributes',
    'node_modules/typescript/package.json', 'bump_assets.py', 'update_cities.py', 'README.md', 'data/cities.tsv', 'android/README.md', 'zapstore.yaml',
    'jsconfig.json', 'types/app.d.ts'];
  for (const path of hidden) assert.equal((await fetch(`${base}${path}`)).status, 404, `${path} is not served`);
  for (const path of ['', 'index.html', 'app.js', 'cities.js', 'suncalc.js', 'style.css', 'disclaimer.html', 'favicon.svg', 'fonts/inter/inter.css']) {
    assert.equal((await fetch(`${base}${path}`)).status, 200, `${path || '/'} is served`);
  }
});

await check("the page's day classifier agrees with daykind-parity.csv on all 360 rows, in the browser", async () => {
  const rows = read('android/app/src/test/resources/daykind-parity.csv').trim().split('\n').slice(1).map((l) => l.split(','));
  assert.equal(rows.length, 360);
  const p = await openPage(browser, base, { timezone: 'Europe/Oslo', init: clockAt(NOW) });
  const got: string[] = await p.ev(`(async () => {
    switchTab('gps');
    const out = [];
    for (const [, lat, lon, date] of ${JSON.stringify(rows)}) {
      els.latInput.value = lat; els.lonInput.value = lon; els.dateInput.value = date;
      await onCalculate();
      const h = els.resSunrise.innerHTML + els.resSunset.innerHTML;
      out.push(/Midnight Sun/.test(h) ? 'MIDNIGHT_SUN' : /Polar Night/.test(h) ? 'POLAR_NIGHT' : 'NORMAL');
    }
    return out;
  })()`);
  const bad = rows.map((r, i) => (got[i] === r[4] ? null : `${r[0]} ${r[3]}: page ${got[i]}, csv ${r[4]}`)).filter(Boolean);
  assert.deepEqual(bad, []);
  quiet(p, 'day classifier');
  await p.close();
});

await check('no sideways scroll at 320, 390 and 1280 px, with a result shown and with the city list open', async () => {
  for (const width of [320, 390, 1280]) {
    const p = await openPage(browser, base, { width, timezone: 'Europe/Oslo', init: clockAt(NOW) });
    const wide = (): Promise<boolean> => p.ev('document.documentElement.scrollWidth > document.documentElement.clientWidth');
    assert.equal(await wide(), false, `${width}px as loaded`);
    await p.type('#city-search', 'santiago');
    assert.equal(await wide(), false, `${width}px with the list open`);
    await pickCity(p, 'longyearbyen', 'Longyearbyen', 'Svalbard and Jan Mayen');
    await p.setDate('#date-input', '2026-12-21');
    await press(p, '#calculate-btn');
    assert.equal(await wide(), false, `${width}px with Polar Night shown`);
    await pickCity(p, 'anchorage', 'Anchorage, AK', 'USA');
    await p.setDate('#date-input', '2026-06-21');
    await press(p, '#calculate-btn');
    assert.equal(await wide(), false, `${width}px with a long day shown`);
    if (shots) await p.shot(join(shots, `anchorage-${width}.png`));
    quiet(p, `${width}px`);
    await p.close();
  }
});

console.log(`smoke: all ${passed} checks passed`);
await cleanup();
