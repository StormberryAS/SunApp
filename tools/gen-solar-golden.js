#!/usr/bin/env node
/**
 * gen-solar-golden.js: regenerate the solar golden corpus for the Android port.
 * ================================================================
 * Emits android/app/src/test/resources/suncalc-golden.csv: 25 cases (24 real
 * places and dates, plus one synthetic coordinate) x all 14 SunCalc event
 * keys, as UTC instants.
 *
 * WHY THIS EXISTS, AND WHY IT READS THE BUNDLED suncalc.js RATHER THAN AN ALMANAC
 *
 * The Kotlin SunCalc in the APK is a literal transcription of SunCalc 1.8.0,
 * not an independent solar model. Its job is to agree with sun.stormberry.as to
 * the millisecond, so the golden values have to come from the very library the
 * web app runs. SunCalc's own approximations (a fixed obliquity of 23.4397 deg,
 * no nutation, no epoch correction) put it up to roughly 0.2 degrees of altitude
 * away from a rigorous ephemeris at its own reported crossings. An almanac would
 * therefore fail every row here while being astronomically more correct, and
 * "fixing" the port to match it would break parity with the web app. The corpus
 * documents SunCalc 1.8.0's behaviour deliberately; it is a parity contract, not
 * a statement about the sky.
 *
 * THE ANCHORING RULE
 *
 * app.js section 9.2 builds its calculation date as
 *   new Date(Date.UTC(year, month - 1, day, 12, 0, 0))
 * That noon-UTC anchor is not cosmetic. SunCalc's julianCycle() picks which
 * solar day to solve for from the anchor instant, so anchoring at local midnight
 * would select the neighbouring day for anything far enough east or west. This
 * script uses the identical construction so the Kotlin port is compared against
 * what the web app actually does, not against a tidier convention nobody ships.
 *
 * COLUMNS
 *
 *   case_id       G01..G24, stable; the test suite and the plan refer to these
 *   description   free text, comma-free by construction (see assertNoCommas)
 *   lat, lon      degrees, 4 decimal places, the resolution of the city table
 *   date          ISO calendar date handed to the anchoring rule above
 *   event         the Kotlin SolarEvent enum NAME, not SunCalc's camelCase key
 *   expected_utc  ISO-8601 instant with milliseconds, or the literal NONE
 *
 * NONE is the whole reason this corpus is exhaustive rather than sunrise-only.
 * SunCalc returns a Date wrapping NaN when acos() has no solution, which is how
 * it says "this event does not happen today". Nullability is per key, not per
 * day: G12 has no sunrise but does have nautical dusk, G21 has a sunrise but no
 * golden hour. A port that collapses "any NaN" into "polar day" passes a
 * sunrise-only corpus and fails users at both ends of the year, so every key of
 * every case is written out and asserted.
 *
 * Deliberately NOT emitted: timezone abbreviations (Intl and DateTimeFormatter
 * disagree, and Android's CLDR differs again by API level) and any local-time
 * rendering (the zone is the port's business, and a golden file that carries one
 * would freeze a tzdb version into the test suite).
 *
 * Usage, from anywhere:
 *   node tools/gen-solar-golden.js
 * Then commit the CSV. It is checked in rather than generated at build time so
 * a change to the goldens shows up as a reviewable diff.
 * ================================================================
 */

'use strict';

const fs = require('fs');
const path = require('path');

// Resolve everything against this script's own location. The CSV lands deep in
// the Gradle test tree and the library sits at the repo root; neither should
// depend on which directory the operator happened to be standing in.
const REPO_ROOT = path.resolve(__dirname, '..');
const SunCalc = require(path.join(REPO_ROOT, 'suncalc.js'));
const OUT_PATH = path.join(
  REPO_ROOT, 'android', 'app', 'src', 'test', 'resources', 'suncalc-golden.csv',
);

/**
 * SunCalc's returned key -> the Kotlin SolarEvent constant it maps to.
 *
 * The order is the enum's declaration order, so a reader diffing a regenerated
 * CSV sees rows move only when a value actually changed. solarNoon and nadir sit
 * last because they are the two that can never be NONE: they are computed
 * directly from the solar transit and never route through acos().
 */
const EVENT_KEYS = [
  ['sunrise', 'SUNRISE'],
  ['sunset', 'SUNSET'],
  ['sunriseEnd', 'SUNRISE_END'],
  ['sunsetStart', 'SUNSET_START'],
  ['dawn', 'DAWN'],
  ['dusk', 'DUSK'],
  ['nauticalDawn', 'NAUTICAL_DAWN'],
  ['nauticalDusk', 'NAUTICAL_DUSK'],
  ['nightEnd', 'NIGHT_END'],
  ['night', 'NIGHT'],
  ['goldenHourEnd', 'GOLDEN_HOUR_END'],
  ['goldenHour', 'GOLDEN_HOUR'],
  ['solarNoon', 'SOLAR_NOON'],
  ['nadir', 'NADIR'],
];

/**
 * The cases. Each earns its slot: either it pins a behaviour the port could
 * plausibly get wrong, or it is the boring baseline that proves a fix for one of
 * the others did not break the ordinary case.
 *
 * Coordinates are given to 4 decimal places because that is exactly the
 * resolution of data/cities.tsv, so a test can feed a city row in unchanged.
 */
const CASES = [
  {
    id: 'G01', lat: 0.0, lon: 0.0, date: '2026-03-20',
    // The origin on the March equinox. Nothing is polar, nothing is near a date
    // line and the day is close to twelve hours: if this row moves the port has
    // a plain arithmetic error rather than an edge-case bug.
    desc: 'Equator at the prime meridian on the March equinox - baseline',
  },
  {
    id: 'G02', lat: 0.0, lon: 0.0, date: '2026-06-21',
    // Same point at maximum declination. Day length barely moves at the equator
    // and that near-invariance is itself worth pinning.
    desc: 'Equator at the prime meridian on the June solstice',
  },
  {
    id: 'G03', lat: 51.5074, lon: -0.1278, date: '2026-03-20',
    // A mid-latitude city just west of the meridian. Small negative longitude
    // catches a sign slip in lw = rad * -lng that the equator cases cannot.
    desc: 'London mid-latitude on the March equinox',
  },
  {
    id: 'G04', lat: 60.3913, lon: 5.3221, date: '2026-06-21',
    // Bergen at midsummer: sunrise and sunset are real but the nautical and
    // astronomical keys are NONE because the sun never drops below -12 degrees.
    // This is the case that kills "any NaN means polar".
    desc: 'Bergen at midsummer - civil twilight all night',
  },
  {
    id: 'G05', lat: 60.3913, lon: 5.3221, date: '2026-12-21',
    // Bergen at midwinter. Still a normal day at 60 N; every key resolves.
    desc: 'Bergen at midwinter - short but complete day',
  },
  {
    id: 'G06', lat: 60.3913, lon: 5.3221, date: '2026-03-20',
    desc: 'Bergen on the March equinox',
  },
  {
    id: 'G07', lat: 60.3913, lon: 5.3221, date: '2026-09-23',
    desc: 'Bergen on the September equinox',
  },
  {
    id: 'G08', lat: 60.3913, lon: 5.3221, date: '2026-03-29',
    // Europe/Oslo springs forward on this date. SunCalc knows nothing about
    // civil time, so these instants must be perfectly smooth against 28 and 30
    // March. The row exists so that a port which accidentally routes through a
    // wall clock is caught here rather than by a user an hour late for work.
    desc: 'Bergen on the Europe/Oslo spring-forward date',
  },
  {
    id: 'G09', lat: 60.3913, lon: 5.3221, date: '2026-10-25',
    desc: 'Bergen on the Europe/Oslo fall-back date',
  },
  {
    id: 'G10', lat: 69.6492, lon: 18.9553, date: '2026-01-15',
    // Tromso on the day the sun returns: it clears -0.833 for about 24 minutes
    // but never reaches -0.3, so SUNRISE and SUNSET resolve while SUNRISE_END
    // and SUNSET_START are NONE. A port that treats sunrise as implying
    // sunrise-end produces a negative interval here.
    desc: 'Tromso as the sun returns - rises but never finishes rising',
  },
  {
    id: 'G11', lat: 1.8721, lon: -157.4278, date: '2026-06-21',
    // Kiritimati is at UTC+14 on a NEGATIVE longitude: the civil date line runs
    // east of it. Every event instant lands on a different UTC date from the
    // local one, which is the trap the occurrence engine's anchor-day selection
    // exists to handle.
    desc: 'Kiritimati - UTC+14 on a western longitude',
  },
  {
    id: 'G12', lat: 78.2233, lon: 15.6469, date: '2026-12-21',
    // Longyearbyen in deep polar night. The civil and golden-hour keys are NONE
    // but nautical and astronomical twilight are perfectly real: the sun does
    // get below -12 and -18, it simply never comes back up. Together with G04
    // this brackets the nullability rule from both directions.
    desc: 'Longyearbyen polar night - no sunrise but real astronomical twilight',
  },
  {
    id: 'G13', lat: 78.2233, lon: 15.6469, date: '2026-06-21',
    // Longyearbyen midnight sun: all twelve angle keys are NONE and only the two
    // transit keys survive. This is the row that catches NaN collapsing to 0L,
    // which renders polar day as 1970-01-01 rather than as an absence.
    desc: 'Longyearbyen midnight sun - all twelve angle events absent',
  },
  {
    id: 'G14', lat: 89.99, lon: 0.0, date: '2026-03-19',
    // Effectively the North Pole a day before the equinox. All twelve keys are
    // NONE, yet the sun sits ABOVE the -0.833 sunrise altitude at nadir and
    // BELOW zero at noon. A classifier that samples noon altitude against zero
    // calls this polar night; the geometry says the sun never sets. The corpus
    // records the instants only - the disagreement is asserted in DayKind.
    desc: 'North Pole a day before the March equinox - the classifier trap',
  },
  {
    id: 'G15', lat: 78.2233, lon: 15.6469, date: '2026-04-19',
    // The one day in 2026 where Longyearbyen sits on the knife edge: the sun
    // dips about 0.007 degrees below the sunrise altitude at nadir, too shallow
    // for SunCalc's solver to locate the crossing, so SUNRISE and SUNSET are
    // NONE while SUNRISE_END and SUNSET_START resolve. app.js section 9.4 names
    // this exact date as the reason its polar classifier has a third branch.
    desc: 'Longyearbyen on the 2026 midnight-sun boundary day',
  },
  {
    id: 'G16', lat: 78.2233, lon: 15.6469, date: '2026-09-23',
    // High Arctic on an equinox: an ordinary rise and set, but the nautical and
    // astronomical keys are still NONE. Proves the polar keys are absent for a
    // reason other than "it is a polar day".
    desc: 'Longyearbyen on the September equinox - ordinary day with no deep night',
  },
  {
    id: 'G17', lat: -54.8019, lon: -68.303, date: '2026-06-21',
    // Southern hemisphere at its shortest day, with both coordinates negative.
    desc: 'Ushuaia on the southern winter solstice',
  },
  {
    id: 'G18', lat: -18.1416, lon: 178.4419, date: '2026-06-21',
    // Fiji: longitude past 178 E, so the whole solar day straddles the
    // antimeridian in UTC. Sunrise lands on 20 June UTC while being 21 June
    // local, which is the drift an alarm scheduler has to correct for.
    // This is also the closest ORDINARY case to the julianCycle rounding
    // boundary - its argument sits 0.0052 of a day from a tie - but it does not
    // by itself discriminate between rounding modes. G25 does; see there.
    desc: 'Suva Fiji east of the antimeridian - near the julianCycle boundary',
  },
  {
    id: 'G19', lat: -13.8333, lon: -171.7667, date: '2026-06-21',
    // Samoa is UTC+13 on a western longitude, the mirror image of G18. SunCalc's
    // answer for "21 June" renders as 22 June in Pacific/Apia, which is exactly
    // the drift the occurrence engine has to correct before arming an alarm.
    desc: 'Apia Samoa - UTC+13 west of the antimeridian',
  },
  {
    id: 'G20', lat: -33.8688, lon: 151.2093, date: '2026-12-21',
    // Southern summer solstice with a large positive longitude: solar noon is
    // near 02:00 UTC and sunrise falls on the previous UTC day.
    desc: 'Sydney on the southern summer solstice',
  },
  {
    id: 'G21', lat: 66.0, lon: 25.0, date: '2026-12-21',
    // Just inside the Arctic Circle at midwinter. Maximum altitude is about
    // +0.56 degrees, so everything at or below -0.3 resolves and only the two
    // positive-angle golden-hour keys are NONE. The complement of G12: here the
    // absent keys are the bright ones.
    desc: 'Arctic Circle at midwinter - only the golden-hour events absent',
  },
  {
    id: 'G22', lat: 1.3521, lon: 103.8198, date: '2026-12-21',
    desc: 'Singapore on the December solstice - near-equatorial baseline',
  },
  {
    id: 'G23', lat: -33.4489, lon: -70.6693, date: '2026-09-06',
    // America/Santiago starts DST on this date, in the southern hemisphere and
    // in the opposite direction from G08. Same argument: the instants must not
    // notice.
    desc: 'Santiago on the America/Santiago DST start date',
  },
  {
    id: 'G24', lat: 64.1466, lon: -21.9426, date: '2026-06-21',
    // Reykjavik at midsummer. Atlantic/Reykjavik is UTC+0 all year, so there is
    // no offset to hide behind: sunset is at 00:05 on 22 June, a full local day
    // after the anchor date. Any UI or scheduler that assumes an event shares
    // the anchor's calendar date is wrong here in the most visible way possible.
    desc: 'Reykjavik at midsummer - sunset crosses local midnight',
  },
  {
    id: 'G25', lat: -18.1416, lon: -179.676, date: '2026-06-22',
    // SYNTHETIC, and the only row in the corpus that is. Everything above is a
    // real place; this is a coordinate chosen so that julianCycle's argument
    // lands on EXACTLY 9668.5, a perfect tie.
    //
    // It exists because the obvious transcription hazard turned out not to be
    // caught by any real case. suncalc.js writes julianCycle as
    // Math.round(days - J0 - lw / (2 * PI)); JS Math.round is half-up, and
    // java.lang.Math.round matches it, but kotlin.math.round is ties-to-even and
    // would resolve this argument DOWN to 9668 because 9668 is even. That is a
    // whole solar day: solar noon moves from 2026-06-23T00:02Z to the 22nd.
    //
    // A sweep of every 4-decimal longitude on both parities of the day counter
    // found precisely one value where the two modes disagree, and this is it, so
    // without this row a kotlin.math.round substitution passes the entire suite.
    // The lesson generalises: transcribe floor(x + 0.5) literally.
    desc: 'Synthetic tie longitude - julianCycle rounding-mode discriminator',
  },
];

/**
 * The CSV is read by a hand-rolled Kotlin splitter, not a CSV library, because
 * pulling a parser into the unit-test classpath to read one static file is not a
 * trade worth making. That only stays safe if no field can contain a comma, so
 * enforce it here rather than discovering it as a mis-parsed golden.
 */
function assertNoCommas(value, where) {
  if (String(value).includes(',')) {
    throw new Error(`${where} contains a comma, which the Kotlin CSV reader cannot survive: ${value}`);
  }
}

/**
 * SunCalc signals "this event has no solution today" by returning a Date built
 * from NaN, because acos() of an out-of-range argument is NaN and the value is
 * carried all the way through. Calling toISOString() on one throws a RangeError,
 * so the emptiness has to be tested before the formatting, never caught after.
 */
function formatInstant(date) {
  if (!(date instanceof Date) || Number.isNaN(date.getTime())) return 'NONE';
  return date.toISOString();
}

function main() {
  const rows = [['case_id', 'description', 'lat', 'lon', 'date', 'event', 'expected_utc']];
  const seenIds = new Set();
  const summary = [];

  for (const c of CASES) {
    if (seenIds.has(c.id)) throw new Error(`duplicate case id ${c.id}`);
    seenIds.add(c.id);
    assertNoCommas(c.desc, `${c.id} description`);

    const [year, month, day] = c.date.split('-').map(Number);
    // The app.js section 9.2 anchoring rule, transcribed rather than paraphrased.
    const anchor = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
    const times = SunCalc.getTimes(anchor, c.lat, c.lon);

    const absent = [];
    for (const [jsKey, enumName] of EVENT_KEYS) {
      const value = formatInstant(times[jsKey]);
      if (value === 'NONE') absent.push(enumName);
      rows.push([
        c.id, c.desc, c.lat.toFixed(4), c.lon.toFixed(4), c.date, enumName, value,
      ]);
    }
    summary.push({ id: c.id, desc: c.desc, absent });
  }

  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, rows.map((r) => r.join(',')).join('\n') + '\n', 'utf8');

  // Self-check. These are the three properties the corpus would be worthless
  // without, and they are cheap enough to verify on every regeneration rather
  // than trusting that a future edit to CASES kept them true.
  const dataRows = rows.length - 1;
  const expected = CASES.length * EVENT_KEYS.length;
  if (dataRows !== expected) throw new Error(`emitted ${dataRows} rows, expected ${expected}`);

  const g13 = summary.find((s) => s.id === 'G13');
  if (g13.absent.length !== 12 || g13.absent.includes('SOLAR_NOON') || g13.absent.includes('NADIR')) {
    throw new Error('G13 must have exactly the twelve angle events absent and both transit events present');
  }
  const g12 = summary.find((s) => s.id === 'G12');
  if (!g12.absent.includes('SUNRISE') || !g12.absent.includes('SUNSET')
      || g12.absent.includes('SOLAR_NOON') || g12.absent.includes('NIGHT')) {
    throw new Error('G12 must have no sunrise or sunset, but a real solar noon and astronomical dusk');
  }
  // G25 is only worth its row while its julianCycle argument is still an exact
  // tie. Recompute it here rather than trusting the comment: a future edit to
  // the date or the longitude would quietly turn the discriminator back into an
  // ordinary Fiji row, and nothing else in the suite would notice.
  const g25 = CASES.find((c) => c.id === 'G25');
  const [gy, gm, gd] = g25.date.split('-').map(Number);
  const g25Days = Date.UTC(gy, gm - 1, gd, 12, 0, 0) / 864e5 - 0.5 + 2440588 - 2451545;
  const g25Arg = g25Days - 9e-4 - (Math.PI / 180 * -g25.lon) / (2 * Math.PI);
  if (g25Arg - Math.floor(g25Arg) !== 0.5 || Math.floor(g25Arg) % 2 !== 0) {
    throw new Error(`G25 no longer sits on an even-valued julianCycle tie (argument ${g25Arg}); it discriminates nothing`);
  }

  process.stdout.write(`wrote ${OUT_PATH}\n`);
  process.stdout.write(`${CASES.length} cases x ${EVENT_KEYS.length} events = ${dataRows} data rows\n\n`);
  for (const s of summary) {
    const absent = s.absent.length ? `${s.absent.length} absent: ${s.absent.join(' ')}` : 'all 14 present';
    process.stdout.write(`${s.id}  ${s.desc}\n      ${absent}\n`);
  }
}

main();
