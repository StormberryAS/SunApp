// The page and the publishing set-up: the TypeScript rule (guard S-1), the TypeScript tooling, the
// _config.yml exclusions that keep sources, tooling and tests off the site, the classic script
// tags and their cache stamps, and the house style of the page text. Added 21 October 2026 with the
// TypeScript move, on EclipseTracker's tests/static.test.ts.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { defined } from './helpers.ts';

const read = (p: string): string => readFileSync(new URL(`../${p}`, import.meta.url), 'utf8');
const page = read('index.html');
const visible = page.replace(/<!--[\s\S]*?-->/g, '').replace(/<script[\s\S]*?<\/script>/g, '').replace(/<svg[\s\S]*?<\/svg>/g, '').replace(/<[^>]+>/g, ' ');

/** SunCalc 1.8.0 exactly as vendored in eight Labs repositories (sha256 of suncalc.js). */
const SUNCALC_SHA256 = 'e17fa013a5ab354c22844f3cebecd4a2b866518e5b63d132d537ea6578cb217b';

test('page text: no en or em dashes, no double hyphens, no exclamation marks; lang unchanged', () => {
  assert.doesNotMatch(visible, /[–—]/);
  assert.doesNotMatch(visible, /--/);
  assert.doesNotMatch(visible, /!/);
  assert.match(page, /<html lang="en">/);
});

test('the page loads the three classic scripts in the 1.1.3 order, each stamped', () => {
  const scripts = [...page.matchAll(/<script\b[^>]*>/g)].map((m) => m[0]).filter((s) => !/type="application\/ld\+json"/.test(s));
  assert.deepEqual(scripts.map((s) => s.replace(/\?v=[0-9a-f]{8}/, '?v=STAMP')), [
    '<script src="suncalc.js?v=STAMP">',
    '<script src="cities.js?v=STAMP">',
    '<script src="app.js?v=STAMP">',
  ]);
  // suncalc.js in the head, cities.js and app.js at the end of the body.
  assert.ok(page.indexOf('src="suncalc.js') < page.indexOf('</head>'));
  assert.ok(page.indexOf('src="cities.js') > page.indexOf('<body'));
  assert.ok(page.indexOf('src="app.js') > page.indexOf('src="cities.js'));
  assert.ok(page.indexOf('src="app.js') < page.indexOf('</body>'));
  assert.doesNotMatch(page, /type="module"/);
  assert.doesNotMatch(page, /src="ts\//);
});

test('every local script and stylesheet carries the ?v= stamp of its current bytes', () => {
  const refs = [...page.matchAll(/(?:src|href)="((?!https?:|\/\/|data:|#|mailto:)[^"?#]+\.(?:js|css))(\?v=[^"]*)?"/g)];
  assert.ok(refs.length >= 5);
  for (const m of refs) {
    const path = defined(m[1], 'the path');
    if (path.startsWith('switcher-icons/')) continue;
    const want = createHash('sha256').update(readFileSync(new URL(`../${path}`, import.meta.url))).digest('hex').slice(0, 8);
    assert.equal(m[2], `?v=${want}`, `${path} is stamped with its own hash`);
  }
});

test('_config.yml keeps sources, tooling and tests off the site, and publishes the app', () => {
  const cfg = read('_config.yml');
  const excluded = [...cfg.matchAll(/^ {2}- (\S+)$/gm)].map((m) => m[1]);
  for (const x of ['ts/', 'tools/', 'tests/', 'node_modules/', 'package.json', 'package-lock.json', 'tsconfig.json', '.npmrc', 'bump_assets.py', 'update_cities.py', 'README.md', 'DISCLAIMER.md', 'LICENSE', 'NOTICE', 'android/', 'data/', 'fastlane/', 'zapstore.yaml']) {
    assert.ok(excluded.includes(x), `${x} is excluded`);
  }
  for (const x of ['app.js', 'cities.js', 'suncalc.js', 'index.html', 'style.css', 'disclaimer.html', 'fonts/', 'switcher-icons/']) assert.ok(!excluded.includes(x), `${x} is published`);
  // Gone with the move: nothing may still point at them.
  for (const x of ['jsconfig.json', 'types/']) assert.ok(!excluded.includes(x), `${x} no longer exists`);
});

test('the TypeScript set-up follows EclipseTracker: TypeScript 6.0.3 pinned, strict, a classic script', () => {
  const pkg = JSON.parse(read('package.json'));
  assert.deepEqual(pkg.devDependencies, { '@types/node': '24.19.2', typescript: '6.0.3' });
  assert.equal(pkg.dependencies, undefined, 'no runtime dependency');
  assert.deepEqual(pkg.engines, { node: '>=24' });
  assert.equal(pkg.type, 'module');
  const lock = JSON.parse(read('package-lock.json'));
  assert.equal(lock.packages['node_modules/typescript'].version, '6.0.3');
  assert.equal(lock.packages['node_modules/@types/node'].version, '24.19.2');
  assert.deepEqual(Object.keys(lock.packages).filter((k) => k !== '').sort(), ['node_modules/@types/node', 'node_modules/typescript', 'node_modules/undici-types']);
  for (const [k, v] of Object.entries(lock.packages as Record<string, { integrity?: string; hasInstallScript?: boolean }>)) {
    if (k === '') continue;
    assert.match(v.integrity ?? '', /^sha512-/, `${k} is pinned by its integrity hash`);
    assert.notEqual(v.hasInstallScript, true, `${k} has no install script`);
  }
  assert.match(read('.npmrc'), /^ignore-scripts=true$/m);
  assert.match(read('.npmrc'), /^save-exact=true$/m);
  const ts = JSON.parse(read('tsconfig.json'));
  for (const k of ['strict', 'noUnusedLocals', 'noUnusedParameters', 'noImplicitReturns', 'noFallthroughCasesInSwitch', 'noUncheckedIndexedAccess', 'verbatimModuleSyntax', 'removeComments', 'noEmitOnError']) {
    assert.equal(ts.compilerOptions[k], true, `tsconfig.json ${k}`);
  }
  assert.equal(ts.compilerOptions.rootDir, 'ts');
  assert.equal(ts.compilerOptions.outDir, '.');
  assert.equal(ts.compilerOptions.module, 'ES2020');
  assert.equal(ts.compilerOptions.target, 'ES2020');
  assert.deepEqual(ts.include, ['ts/**/*.ts']);
  assert.match(read('.gitignore'), /^node_modules\/$/m);
  // app.ts is a classic script: no import or export, so tsc emits a script and index.html keeps
  // loading app.js without type="module" (decision D-1).
  const app = read('ts/app.ts');
  assert.doesNotMatch(app, /^\s*(import|export)\b/m);
  const js = read('app.js');
  assert.match(js, /^"use strict";\n\/\*! SunApp: app\.js is compiled from ts\/app\.ts/);
  assert.doesNotMatch(js, /^\s*(import|export)\b/m);
  // The tests and the Node tools are TypeScript, run by Node 24 directly and type-checked strictly.
  const tests = JSON.parse(read('tests/tsconfig.json'));
  for (const k of ['strict', 'noUncheckedIndexedAccess', 'erasableSyntaxOnly', 'verbatimModuleSyntax', 'noEmit']) assert.equal(tests.compilerOptions[k], true, `tests/tsconfig.json ${k}`);
  assert.deepEqual(tests.compilerOptions.types, ['node'], 'Node is typed by @types/node');
  assert.deepEqual(tests.compilerOptions.lib, ['ES2023'], 'no DOM library: the tests run in Node, not a browser');
  assert.deepEqual(tests.include, ['*.ts', '../tools/*.ts']);
  assert.match(read('tools/build-ts.sh'), /tests\/tsconfig\.json/);
  assert.deepEqual(readdirSync(new URL('./', import.meta.url)).filter((n) => /\.(m?js|cjs)$/.test(n)), [], 'no JavaScript left in tests/');
  assert.deepEqual(readdirSync(new URL('../tools/', import.meta.url)).filter((n) => /\.(m?js|cjs)$/.test(n)), [], 'no JavaScript left in tools/');
});

test('the vendored SunCalc and the generated catalogue are untouched', () => {
  const suncalc = readFileSync(new URL('../suncalc.js', import.meta.url));
  assert.equal(createHash('sha256').update(suncalc).digest('hex'), SUNCALC_SHA256);
  assert.match(read('cities.js'), /^\/\/ Stormberry Labs shared city catalogue\.\n\/\/ GENERATED FILE, DO NOT EDIT BY HAND\.\n/);
});

// S-1 (21 October 2026, as EclipseTracker's of 17 October): the only JavaScript in the repository
// is compiled output, plus the generated city catalogue and the vendored SunCalc. Every .js, .mjs
// and .cjs that git tracks, or would pick up (untracked and not ignored), is one of those. Each
// compiled file and cities.js is marked linguist-generated and suncalc.js linguist-vendored, so
// GitHub's language bar counts none of them. A hand-written script anywhere else fails here.
test('the only JavaScript is compiled, generated or vendored, and marked so (S-1)', (t) => {
  const root = new URL('../', import.meta.url);
  const git = (...args: string[]): string => execFileSync('git', ['-C', root.pathname, ...args], { encoding: 'utf8' });
  let inGit = false;
  try { inGit = git('rev-parse', '--is-inside-work-tree').trim() === 'true'; } catch { inGit = false; }
  if (!inGit) { t.skip('not a git checkout'); return; }
  const walk = (dir: string): string[] => readdirSync(new URL(dir, root)).flatMap((n) => (n.endsWith('.ts') ? [`${dir}${n}`] : /\./.test(n) ? [] : walk(`${dir}${n}/`)));
  const compiled = walk('ts/').filter((f) => !f.endsWith('.d.ts')).map((f) => f.replace(/^ts\//, '').replace(/\.ts$/, '.js')).sort();
  assert.ok(compiled.includes('app.js'), 'ts/ compiles to app.js');
  const allowed = [...compiled, 'cities.js', 'suncalc.js'].sort();
  const js = git('ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', '*.js', '*.mjs', '*.cjs').split('\0').filter(Boolean);
  assert.deepEqual([...new Set(js)].sort(), allowed, 'JavaScript in the repository other than the compiled, generated and vendored files');
  const attr = (name: string, files: string[]): Map<string, string> => {
    const out = git('check-attr', '-z', name, '--', ...files).split('\0');
    const m = new Map<string, string>();
    for (let i = 0; i + 2 < out.length; i += 3) m.set(defined(out[i], 'path'), defined(out[i + 2], 'value'));
    return m;
  };
  const generated = attr('linguist-generated', [...compiled, 'cities.js']);
  for (const f of [...compiled, 'cities.js']) assert.equal(generated.get(f), 'true', `${f} is linguist-generated`);
  assert.equal(attr('linguist-vendored', ['suncalc.js']).get('suncalc.js'), 'true', 'suncalc.js is linguist-vendored');
});
