// The page and the publishing set-up: the TypeScript rule (guard S-1), the TypeScript tooling, the
// _config.yml exclusions that keep sources, tooling and tests off the site, the classic script
// tags and their cache stamps, and the house style of the page text. Added 21 October 2026 with the
// TypeScript move, on EclipseTracker's tests/static.test.ts.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
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
  for (const x of ['ts/', 'tools/', 'tests/', 'node_modules/', 'package.json', 'package-lock.json', 'tsconfig.json', 'tsconfig.sw.json', '.npmrc', 'bump_assets.py', 'update_cities.py', 'README.md', 'DISCLAIMER.md', 'LICENSE', 'NOTICE', 'android/', 'data/', 'fastlane/', 'zapstore.yaml']) {
    assert.ok(excluded.includes(x), `${x} is excluded`);
  }
  for (const x of ['app.js', 'sw.js', 'cities.js', 'suncalc.js', 'index.html', 'style.css', 'disclaimer.html', 'fonts/', 'switcher-icons/']) assert.ok(!excluded.includes(x), `${x} is published`);
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

// The offline pilot (21 October 2026): ts/sw.ts, compiled to sw.js, registered by offlinePilot() in
// ts/app.ts on https://sun.stormberry.as only. tests/sw-smoke.ts runs it in Chromium.
const listed = (src: string): string[] => {
  const m = /const ASSETS(?:: readonly string\[\])? = \[([\s\S]*?)\];/.exec(src);
  return [...defined(m?.[1], 'the ASSETS list').matchAll(/'([^']*)'/g)].map((x) => defined(x[1], 'an entry'));
};
const stampOfFile = (path: string): string => createHash('sha256').update(readFileSync(new URL(`../${path}`, import.meta.url))).digest('hex').slice(0, 8);

test('offline pilot: sw.js stores exactly what the pages load, under the stamps the pages use', () => {
  const assets = listed(read('sw.js'));
  // ts/sw.ts holds the same list without stamps; bump_assets.py writes them into sw.js.
  assert.deepEqual(listed(read('ts/sw.ts')), assets.map((a) => a.replace(/\?v=[0-9a-f]{8}$/, '')));
  assert.equal(new Set(assets).size, assets.length, 'no entry twice');
  // Every script and stylesheet the two pages load, with the stamp they load it under.
  const pageRefs = new Set<string>();
  for (const html of ['index.html', 'disclaimer.html']) {
    for (const m of read(html).matchAll(/(?:src|href)="((?!https?:|\/\/|data:|#|mailto:)[^"?#]+\.(?:js|css)\?v=[0-9a-f]{8})"/g)) pageRefs.add(`./${m[1]}`);
  }
  assert.deepEqual(assets.filter((a) => /\?v=/.test(a)).sort(), [...pageRefs].sort());
  for (const a of assets) {
    const m = /^\.\/([^?]*)(?:\?v=([0-9a-f]{8}))?$/.exec(a);
    assert.ok(m, `${a} is a relative path`);
    const path = defined(m[1], 'path') || 'index.html';
    assert.ok(existsSync(new URL(`../${path}`, import.meta.url)), `${a} exists`);
    if (m[2]) assert.equal(m[2], stampOfFile(path), `${a} carries the hash of ${path}`);
    if (/\.(js|css)$/.test(path)) assert.ok(m[2], `${a} is stamped`);
  }
  // The rest: the page itself, the disclaimer, the font the page preloads and its italic (the
  // disclaimer's last paragraph), the favicon and every carousel icon.
  const icons = readdirSync(new URL('../switcher-icons/', import.meta.url)).filter((n) => n.endsWith('.svg')).sort().map((n) => `./switcher-icons/${n}`);
  assert.deepEqual(assets.filter((a) => !/\?v=/.test(a)), ['./', './fonts/inter/InterVariable.woff2', './fonts/inter/InterVariable-Italic.woff2', './favicon.svg', './disclaimer.html', ...icons]);
  assert.match(page, /<link rel="preload" href="fonts\/inter\/InterVariable\.woff2" as="font"/);
  // The Home Screen icon (e1b6591) is linked by both pages but deliberately not stored: iOS fetches
  // it online when the page is added, and precaching is all or nothing, so a listed file that went
  // missing would fail every install.
  for (const html of ['index.html', 'disclaimer.html']) {
    assert.match(read(html), /<link rel="apple-touch-icon" href="apple-touch-icon\.png" \/>/, `${html} links the Home Screen icon`);
  }
  assert.ok(!assets.some((a) => a.includes('apple-touch-icon')), 'the Home Screen icon is not precached');
});

// The worker's precache and keepPage() treat every ?v= reference in a stored page as a same-origin
// script or stylesheet it must hold (fix round 2, verify 1 offline C-4): an absolute or other kind
// of ?v= reference would make every install fail without a word, so it fails here instead.
test('offline pilot: every ?v= reference in the stored pages is a relative .js or .css path', () => {
  for (const html of ['index.html', 'disclaimer.html']) {
    const refs = [...read(html).matchAll(/(?:src|href)="([^"]*\?v=[^"]*)"/g)].map((m) => defined(m[1], 'a reference'));
    assert.ok(refs.length > 0, `${html} has stamped references`);
    for (const r of refs) assert.match(r, /^(?![a-z][a-z0-9+.-]*:|\/)[^"?#]+\.(?:js|css)\?v=[0-9a-f]{8}$/, `${html}: ${r}`);
  }
});

test('offline pilot: registered only on https://sun.stormberry.as; no manifest, no theme colour', () => {
  const app = read('ts/app.ts');
  assert.match(app, /if \(location\.protocol !== 'https:' \|\| location\.hostname !== 'sun\.stormberry\.as'\) return;/);
  // A bare 'sw.js', never './sw.js': the worker's address has to stay the same for the browser to
  // update it in place, and the './x.js' form is the one bump_assets.py stamps in sw.js, so keeping
  // the register call to the bare form keeps that convention unambiguous should app.js ever be
  // stamped the same way. Today bump_assets.py stamps literals in sw.js only.
  assert.match(app, /sw\.register\('sw\.js', \{ scope: '\.\/', updateViaCache: 'none' \}\)/);
  assert.match(app, /^const OFFLINE_PILOT = '(off|opt-in|on)' as 'off' \| 'opt-in' \| 'on';$/m);
  assert.match(app, /^  try \{ offlinePilot\(\); \} catch \{/m);
  assert.match(read('ts/sw.ts'), /^const KILL = (true|false) as boolean;$/m);
  for (const html of ['index.html', 'disclaimer.html']) {
    const text = read(html);
    assert.doesNotMatch(text, /rel="manifest"/, `${html} has no manifest (it would let Chrome on Android offer an install that competes with Zapstore)`);
    assert.doesNotMatch(text, /name="theme-color"/, `${html} has no theme colour`);
    assert.doesNotMatch(text, /sw\.js/, `${html} does not load the worker as a script`);
  }
  // The worker is checked as a worker: no DOM, the WebWorker library, and kept out of the page program.
  const swc = JSON.parse(read('tsconfig.sw.json'));
  assert.equal(swc.extends, './tsconfig.json');
  assert.deepEqual(swc.compilerOptions.lib, ['ES2020', 'WebWorker']);
  assert.deepEqual(swc.include, ['ts/sw.ts']);
  assert.deepEqual(JSON.parse(read('tsconfig.json')).exclude, ['ts/sw.ts']);
  assert.match(read('tools/build-ts.sh'), /tsconfig\.sw\.json/);
});

// The footer of the page and the footer of the Android app carry the same sentences, word for word
// (Marcos, 24 October 2026: "keep web versions and APKs consistent"), except two lines the page words
// for itself (Marcos, 25 October 2026, option B), which are pinned below. The app's wording is the
// approved one, so the page is compared with android/.../ui/components/Footer.kt. Only the layout may
// differ. The addresses are checked as well: each credit link on the page goes where the app's goes.
test('the footer says what the Android app footer says, word for word, bar two approved web lines', () => {
  const kt = read('android/app/src/main/kotlin/no/stormberry/sunapp/ui/components/Footer.kt');
  const unescape = (s: string): string => s.replace(/\\u([0-9A-Fa-f]{4})/g, (_, h: string) => String.fromCharCode(parseInt(h, 16)));
  const consts = new Map<string, string>();
  for (const m of kt.matchAll(/const val (\w+) = "([^"]*)"/g)) consts.set(defined(m[1], 'name'), unescape(defined(m[2], 'value')));
  const text = (html: string): string => html
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ').replace(/&#8209;/g, '‑').replace(/&copy;/g, '©').replace(/&middot;/g, '·')
    .replace(/[ \t\r\n]+/g, ' ').trim();
  const body = (name: string): string => {
    const start = kt.indexOf(name);
    assert.ok(start >= 0, `${name} is in Footer.kt`);
    return kt.slice(start);
  };
  const literal = (s: string): string => unescape(s).replace(/\$(\w+)/g, (_, n: string) => defined(consts.get(n), `const ${n}`));

  // The pieces of a built sentence, in order: append("...") and url(label, address).
  const sentence = (fn: string): { text: string; links: string[] } => {
    const src = body(fn);
    const end = src.indexOf('\n}\n');
    const parts = [...src.slice(0, end).matchAll(/append\("((?:[^"\\]|\\.)*)"\)|url\((?:"((?:[^"\\]|\\.)*)"|(\w+)), (?:FooterTargets\.(\w+))\)/g)];
    let out = '';
    const links: string[] = [];
    for (const m of parts) {
      if (m[1] !== undefined) out += literal(m[1]);
      else {
        out += m[2] !== undefined ? literal(m[2]) : defined(consts.get(defined(m[3], 'label const')), 'label const');
        links.push(defined(m[4], 'target'));
      }
    }
    return { text: out, links };
  };

  const para = (re: RegExp, what: string): { text: string; html: string } => {
    const m = re.exec(page);
    assert.ok(m, `${what} is in index.html`);
    return { text: text(defined(m[1], what)), html: defined(m[1], what) };
  };

  // Credits: lead-in included, and nothing about icons, languages, libraries or fonts.
  const credit = sentence('private fun creditLine');
  assert.equal(credit.text, 'Runs entirely on your device: our own code, SunCalc\'s formulae (BSD 2‑Clause) and city data from GeoNames and Kartverket (CC BY 4.0). Nothing is sent.');
  const web = para(/<div class="footer-powered">\s*<p>([\s\S]*?)<\/p>/, 'the credits sentence');
  assert.equal(web.text, credit.text);
  const targets = new Map<string, string>();
  for (const m of kt.matchAll(/const val (\w+) = "(https?:[^"]*)"/g)) targets.set(defined(m[1], 'name'), defined(m[2], 'url'));
  const hrefs = [...web.html.matchAll(/href="([^"]+)"/g)].map((m) => m[1]);
  assert.deepEqual(hrefs, credit.links.map((l) => defined(targets.get(l), `FooterTargets.${l}`)));
  assert.doesNotMatch(web.text, /Lucide|Kotlin|Jetpack|Material|Inter|Intl|backport/);

  // The legal line.
  const legal = sentence('private fun legalLine');
  // The app holds the organisation number together with no-break spaces; the page does it with
  // white-space: nowrap on its links. Same words, so the spaces are compared as spaces.
  const plain = (s: string): string => s.replace(/\u00A0/g, ' ');
  assert.equal(plain(para(/<p>(&copy; 2026 Stormberry AS[\s\S]*?)<\/p>/, 'the legal line').text), plain(legal.text));

  const line = (s: string): string => literal(s);
  // Two lines are the website's own, approved as written (Marcos, 25 October 2026, option B): the app
  // says "Your places and alarms never leave this device. Disconnect and try it." and "Alarm
  // permissions only. No internet. No tracking."; the page says what is true of a page. The app's
  // lines stay pinned, so neither side can change without this test noticing.
  const WEB_PRIVACY_LINE = 'Your places never leave this browser.';
  const WEB_PERMISSION_LINE = 'No cookies. No tracking. Every calculation runs in your browser.';
  assert.equal(line(defined(consts.get('PRIVACY_ALARMS'), 'app privacy line')), 'Your places and alarms never leave this device. Disconnect and try it.');
  assert.equal(line(defined(consts.get('PERMISSIONS_ALARMS'), 'app permission line')), 'Alarm permissions only. No internet. No tracking.');

  // The single lines: tagline, privacy, permissions, Privacy First, city list date, the closing line.
  const lines = [
    line(defined(/text = "(Smarter sales[^"]*)"/.exec(kt)?.[1], 'tagline')),
    line(WEB_PRIVACY_LINE),
    line(WEB_PERMISSION_LINE),
    line(defined(/FooterNote\("(Privacy First[^"]*)"\)/.exec(kt)?.[1], 'Privacy First line')),
    line(defined(/FooterNote\("(City list dated[^"]*)"\)/.exec(kt)?.[1], 'city list line')),
    line(defined(/FooterNote\("(Proudly powered[^"]*)"\)/.exec(kt)?.[1], 'closing line')),
  ];
  const footer = defined(/<footer class="site-footer">([\s\S]*?)<\/footer>/.exec(page)?.[1], 'the footer');
  const paragraphs = [...footer.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/g)].map((m) => text(defined(m[1], 'paragraph')));
  for (const l of lines) assert.ok(paragraphs.includes(l), `the page footer has the app's line: ${l}`);
  // And the page has no sentence of its own beyond the app's, bar the two approved web lines above.
  const known = new Set([...lines, credit.text, legal.text].map(plain));
  for (const p of paragraphs) assert.ok(known.has(plain(p)), `a page footer sentence the app does not have: ${p}`);
});
