#!/usr/bin/env python3
"""
Canonical city-catalogue generator for every Stormberry Labs app.

Single source of truth. Emits ONE packed catalogue and writes it to every app
that uses a city picker, plus a plain .tsv for the SunApp Android build.

Run from anywhere:   python3 update_cities.py [--dry-run] [--target N]

It lives in SunApp/ rather than beside the patch_*.py scripts at the GitHub/
root because that directory is not a repository: a canonical generator kept
there would be untracked, unbacked-up and invisible on GitHub. It writes to
all nine sibling apps regardless.

The previous version had a hardcoded output path missing the /GitHub/
segment, so it raised FileNotFoundError and had not been runnable for some
time. Fixed 2026-08-21.

Output format (packed, ~36 bytes/city against ~97 for the old verbose form):

    line 1  country palette, tab-separated
    line 2  timezone palette, tab-separated
    line 3+ name \t fold \t countryIdx \t latE4 \t lonE4 \t tzIdx

`fold` is the accent-stripped lowercase name, written only when it differs
from name.lowercase(). It exists to fix a real search bug: typing "Herat"
matched nothing because "Herat" was compared against "Herāt" with a plain
substring test.

Coordinates are lossless integers at 1e4 because the source data carries
exactly four decimals.
"""

import argparse
import collections
import gzip
import json
import os
import re
import sys
import unicodedata

import geonamescache

# This file lives in SunApp/ so that it is version-controlled, but it writes
# to every sibling app, so paths are resolved against the parent GitHub/ dir.
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Every app that ships a city picker. cities.js is written to each of these.
APPS = [
    "SunApp", "MoonApp", "PhotoTimer", "TideTracker", "EcoCompass",
    "StarMap", "WeatherWhisper", "PlanetPulse", "SolarFlare",
]

# The Android build reads this asset. Committed rather than generated at build
# time so the APK stays byte-reproducible.
TSV_TARGET = os.path.join(HERE, "SunApp", "data", "cities.tsv")

DEFAULT_TARGET = 25_000

BR_FIPS = {
    '01': 'AC', '02': 'AL', '03': 'AP', '04': 'AM', '05': 'BA', '06': 'CE',
    '07': 'DF', '08': 'ES', '29': 'GO', '13': 'MA', '14': 'MT', '12': 'MS',
    '15': 'MG', '16': 'PA', '17': 'PB', '18': 'PR', '30': 'PE', '20': 'PI',
    '21': 'RJ', '22': 'RN', '23': 'RS', '24': 'RO', '25': 'RR', '26': 'SC',
    '27': 'SP', '28': 'SE', '31': 'TO',
}

BR_CAPITALS = {
    'Rio Branco', 'Maceió', 'Macapá', 'Manaus', 'Salvador', 'Fortaleza',
    'Brasília', 'Vitória', 'Goiânia', 'São Luís', 'Cuiabá', 'Campo Grande',
    'Belo Horizonte', 'Belém', 'João Pessoa', 'Curitiba', 'Recife',
    'Teresina', 'Rio de Janeiro', 'Natal', 'Porto Alegre', 'Porto Velho',
    'Boa Vista', 'Florianópolis', 'São Paulo', 'Aracaju', 'Palmas',
}

US_CAPITALS = {
    ('Montgomery', 'AL'), ('Juneau', 'AK'), ('Phoenix', 'AZ'), ('Little Rock', 'AR'),
    ('Sacramento', 'CA'), ('Denver', 'CO'), ('Hartford', 'CT'), ('Dover', 'DE'),
    ('Tallahassee', 'FL'), ('Atlanta', 'GA'), ('Honolulu', 'HI'), ('Boise', 'ID'),
    ('Springfield', 'IL'), ('Indianapolis', 'IN'), ('Des Moines', 'IA'), ('Topeka', 'KS'),
    ('Frankfort', 'KY'), ('Baton Rouge', 'LA'), ('Augusta', 'ME'), ('Annapolis', 'MD'),
    ('Boston', 'MA'), ('Lansing', 'MI'), ('St. Paul', 'MN'), ('Jackson', 'MS'),
    ('Jefferson City', 'MO'), ('Helena', 'MT'), ('Lincoln', 'NE'), ('Carson City', 'NV'),
    ('Concord', 'NH'), ('Trenton', 'NJ'), ('Santa Fe', 'NM'), ('Albany', 'NY'),
    ('Raleigh', 'NC'), ('Bismarck', 'ND'), ('Columbus', 'OH'), ('Oklahoma City', 'OK'),
    ('Salem', 'OR'), ('Harrisburg', 'PA'), ('Providence', 'RI'), ('Columbia', 'SC'),
    ('Pierre', 'SD'), ('Nashville', 'TN'), ('Austin', 'TX'), ('Salt Lake City', 'UT'),
    ('Montpelier', 'VT'), ('Richmond', 'VA'), ('Olympia', 'WA'), ('Charleston', 'WV'),
    ('Madison', 'WI'), ('Cheyenne', 'WY'),
}

# LOAD-BEARING. These sit below the population cutoff and would otherwise be
# dropped: Kleppestø and Askøy are where Marcos lives. A regeneration that
# loses this list is a regression, not a tidy-up.
#
# geonamescache carries nothing at all inside the Askøy bounding box, because
# it only holds places above roughly 15,000 population. The Askøy coordinates
# below therefore come from Kartverket's Sentralt stedsnavnregister via
#   https://ws.geonorge.no/stedsnavn/v1/navn?sok=<name>
# taking the settlement record (Tettsted / Bygdelag / Tettbebyggelse) rather
# than the "Gard" farm record, since the settlement is the populated place.
# That API is the authoritative source for Norwegian place names and needs no
# key; use it again rather than guessing if more local places are added.
EXTRA_MANUAL_CITIES = [
    {'name': 'Askøy',            'country': 'Norway', 'lat': 60.4430, 'lon':  5.1524, 'tz': 'Europe/Oslo'},
    {'name': 'Kleppestø',        'country': 'Norway', 'lat': 60.4079, 'lon':  5.2341, 'tz': 'Europe/Oslo'},
    # Askøy kommune, Kartverket SSR, retrieved 2026-08-21
    {'name': 'Florvåg',          'country': 'Norway', 'lat': 60.4210, 'lon':  5.2385, 'tz': 'Europe/Oslo'},
    {'name': 'Strusshamn',       'country': 'Norway', 'lat': 60.4044, 'lon':  5.1918, 'tz': 'Europe/Oslo'},
    {'name': 'Erdal',            'country': 'Norway', 'lat': 60.4426, 'lon':  5.2271, 'tz': 'Europe/Oslo'},
    {'name': 'Hetlevik',         'country': 'Norway', 'lat': 60.4260, 'lon':  5.1494, 'tz': 'Europe/Oslo'},
    {'name': 'Follese',          'country': 'Norway', 'lat': 60.4102, 'lon':  5.1544, 'tz': 'Europe/Oslo'},
    {'name': 'Molde',            'country': 'Norway', 'lat': 62.7372, 'lon':  7.1599, 'tz': 'Europe/Oslo'},
    {'name': 'Coventry',         'country': 'UK',     'lat': 52.4068, 'lon': -1.5197, 'tz': 'Europe/London'},
    {'name': 'Nova Friburgo, RJ','country': 'Brazil', 'lat': -22.2858,'lon': -42.5332,'tz': 'America/Sao_Paulo'},
    {'name': 'Teresópolis, RJ',  'country': 'Brazil', 'lat': -22.4121,'lon': -42.9667,'tz': 'America/Sao_Paulo'},
    {'name': 'Petrópolis, RJ',   'country': 'Brazil', 'lat': -22.5050,'lon': -43.1786,'tz': 'America/Sao_Paulo'},
    {'name': 'Macaé, RJ',        'country': 'Brazil', 'lat': -22.3708,'lon': -41.7869,'tz': 'America/Sao_Paulo'},
    {'name': 'Niterói, RJ',      'country': 'Brazil', 'lat': -22.8833,'lon': -43.1036,'tz': 'America/Sao_Paulo'},
    {'name': 'Imperatriz, MA',   'country': 'Brazil', 'lat':  -5.5262,'lon': -47.4682,'tz': 'America/Fortaleza'},
]


# NFD decomposition only splits base+combining pairs. These letters are atomic
# code points with no combining form, so stripping marks leaves them untouched
# and a search for "Tromso" or "kleppesto" finds nothing. Nordic names make this
# a first-class case here, not an exotic one.
ATOMIC_FOLD = str.maketrans({
    'ø': 'o', 'æ': 'ae', 'ð': 'd', 'þ': 'th', 'ł': 'l',
    'đ': 'd', 'ß': 'ss', 'œ': 'oe', 'ŋ': 'n', 'ħ': 'h',
    'ı': 'i', 'ŧ': 't', 'ĳ': 'ij',
})


def fold(name: str) -> str:
    """Accent-stripped, transliterated lowercase form, or '' when it adds nothing."""
    stripped = ''.join(
        ch for ch in unicodedata.normalize('NFD', name)
        if not unicodedata.combining(ch)
    ).lower().translate(ATOMIC_FOLD)
    return '' if stripped == name.lower() else stripped


# GeoNames' own alternate-name dump, the only source that tags names by
# language. geonamescache flattens the tags away, which is why picking an
# English name from it lands on "Augusta Ubiorum" or "CGN" for Koeln rather
# than "Cologne".
#
# ~200 MB, cached outside the repositories and never committed. Re-download
# only when the catalogue is rebuilt against a newer GeoNames release.
ALT_ZIP = os.path.expanduser("~/.cache/geonames/alternateNamesV2.zip")


def english_exonyms(geoname_ids):
    """geonameid -> best English name, for ids we actually ship.

    Preference order follows GeoNames' own flags: an explicitly preferred
    English name beats a short one, which beats any other English entry.
    Historic and colloquial names are rejected outright, otherwise Istanbul
    acquires "Constantinople".
    """
    import zipfile

    if not os.path.exists(ALT_ZIP):
        print(f"  NOTE: {ALT_ZIP} missing, skipping English exonyms")
        return {}

    wanted = set(geoname_ids)
    best = {}   # gid -> (rank, name); lower rank wins
    with zipfile.ZipFile(ALT_ZIP) as zf:
        member = next(n for n in zf.namelist() if n.endswith("alternateNamesV2.txt"))
        with zf.open(member) as fh:
            for raw in fh:
                # alternateNameId, geonameid, isolanguage, alternateName,
                # isPreferredName, isShortName, isColloquial, isHistoric, from, to
                parts = raw.split(b"\t")
                if len(parts) < 4 or parts[2] != b"en":
                    continue
                try:
                    gid = int(parts[1])
                except ValueError:
                    continue
                if gid not in wanted:
                    continue
                colloquial = len(parts) > 6 and parts[6] == b"1"
                historic = len(parts) > 7 and parts[7] == b"1"
                if colloquial or historic:
                    continue
                preferred = len(parts) > 4 and parts[4] == b"1"
                short = len(parts) > 5 and parts[5] == b"1"
                rank = 0 if preferred else (1 if short else 2)
                if gid not in best or rank < best[gid][0]:
                    best[gid] = (rank, parts[3].decode("utf-8", "replace"))
    return {gid: name for gid, (_, name) in best.items()}


def collect(target: int):
    gc = geonamescache.GeonamesCache()
    cities = gc.get_cities()
    countries = gc.get_countries()
    by_pop = sorted(cities.values(), key=lambda c: c.get('population', 0), reverse=True)

    out, seen = [], set()

    def add(city):
        key = (round(city['latitude'], 3), round(city['longitude'], 3))
        if key in seen:
            return False
        seen.add(key)
        cc = city['countrycode']
        country = countries.get(cc, {}).get('name', cc)
        if cc == 'US':
            country = 'USA'
        elif cc == 'GB':
            country = 'UK'
        name = city['name']
        admin1 = city.get('admin1code', '')
        if cc == 'US' and admin1:
            name = f"{name}, {admin1}"
        elif cc == 'BR' and BR_FIPS.get(admin1):
            name = f"{name}, {BR_FIPS[admin1]}"
        out.append({
            'name': name, 'country': country,
            'lat': city['latitude'], 'lon': city['longitude'],
            'tz': city['timezone'],
            'gid': int(city['geonameid']),
        })
        return True

    # The curated core, preserved verbatim from the original generator so that
    # nothing currently in the catalogue can disappear when the target changes.
    per_country = collections.Counter()
    for c in by_pop:
        if per_country[c['countrycode']] < 10 and add(c):
            per_country[c['countrycode']] += 1

    for c in by_pop:
        if c['countrycode'] == 'BR' and c['name'] in BR_CAPITALS:
            add(c)

    for c in by_pop:
        if c['countrycode'] == 'US' and (c['name'], c.get('admin1code', '')) in US_CAPITALS:
            add(c)

    per_state = collections.Counter()
    for c in by_pop:
        if c['countrycode'] == 'US':
            st = c.get('admin1code', '')
            if st and per_state[st] < 5 and add(c):
                per_state[st] += 1

    core = len(out)

    # Then fill to target by descending population.
    for c in by_pop:
        if len(out) >= target:
            break
        add(c)
    filled = len(out)

    # Manual extras last, and always, even past the target -- but skip any that
    # the population fill has already supplied. Coordinate-key dedupe is not
    # enough: a manual entry 40 m from the GeoNames one rounds to a different
    # key and both survive, which is how Molde, Coventry and four Brazilian
    # cities ended up listed twice once the target rose to 25,000. Match on
    # name plus proximity instead.
    def _km(a_lat, a_lon, b_lat, b_lon):
        import math
        mean = math.radians((a_lat + b_lat) / 2)
        dx = (a_lon - b_lon) * math.cos(mean)
        dy = a_lat - b_lat
        return math.hypot(dx, dy) * 111.32

    added_manual = 0
    for extra in EXTRA_MANUAL_CITIES:
        if any(c['name'] == extra['name']
               and _km(c['lat'], c['lon'], extra['lat'], extra['lon']) < 5
               for c in out):
            continue
        key = (round(extra['lat'], 3), round(extra['lon'], 3))
        if key not in seen:
            seen.add(key)
            out.append(dict(extra))
            added_manual += 1

    out.sort(key=lambda c: (c['country'], c['name']))
    return out, core, filled, added_manual


def pack(rows, exonyms=None):
    exonyms = exonyms or {}
    countries = sorted({c['country'] for c in rows})
    zones = sorted({c['tz'] for c in rows})
    ci = {v: i for i, v in enumerate(countries)}
    zi = {v: i for i, v in enumerate(zones)}

    def alt_of(c):
        """Folded English exonym, blank unless it adds a searchable form."""
        en = exonyms.get(c.get('gid'))
        if not en:
            return ''
        ef = fold(en) or en.lower()
        primary = fold(c['name']) or c['name'].lower()
        # Skip when it collides with the primary fold, and when the primary
        # already contains it: "York" adds nothing to "York, PA".
        if ef == primary or ef in primary:
            return ''
        return ef

    lines = [
        f"{c['name']}\t{fold(c['name'])}\t{ci[c['country']]}"
        f"\t{round(c['lat'] * 1e4)}\t{round(c['lon'] * 1e4)}\t{zi[c['tz']]}"
        f"\t{alt_of(c)}"
        for c in rows
    ]
    return "\t".join(countries) + "\n" + "\t".join(zones) + "\n" + "\n".join(lines)


def render_js(packed: str, count: int) -> str:
    """A classic script defining a global CITIES.

    Deliberately NOT an ES module. Six of the nine apps load app.js as a plain
    script, so a module export would break them. `var` puts the binding on
    globalThis, which a `type="module"` script can also read, so StarMap works
    from the same file.
    """
    return (
        "// Stormberry Labs shared city catalogue.\n"
        "// GENERATED FILE, DO NOT EDIT BY HAND.\n"
        "// Regenerate with GitHub/update_cities.py, which writes this same file\n"
        "// into every app. Editing one copy silently desynchronises the suite.\n"
        f"// {count} cities. Packed as palettes plus tab-separated rows, decoded\n"
        "// into the same { name, country, lat, lon, tz } objects the apps have\n"
        "// always consumed, so no application code changes.\n"
        "//\n"
        "// The decode is LAZY, behind a getter, and this is deliberate. Building\n"
        f"// {count} objects costs ~70 ms on a desktop and several hundred on a\n"
        "// phone. Doing that at page load would block first paint for a list the\n"
        "// user has usually not asked for yet. The file still downloads eagerly;\n"
        "// only the object construction is deferred to first access, and the\n"
        "// result is cached, so `CITIES.filter(...)` behaves exactly as before.\n"
        "(function () {\n"
        "  // Atomic letters NFD cannot split. Kept in sync with ATOMIC_FOLD in\n"
        "  // GitHub/update_cities.py; if one side changes the other must too, or\n"
        "  // the query and the stored fold stop agreeing.\n"
        "  var ATOMIC = {'\\u00f8':'o','\\u00e6':'ae','\\u00f0':'d','\\u00fe':'th','\\u0142':'l',\n"
        "                '\\u0111':'d','\\u00df':'ss','\\u0153':'oe','\\u014b':'n','\\u0127':'h',\n"
        "                '\\u0131':'i','\\u0167':'t','\\u0133':'ij'};\n"
        "  var D = " + json.dumps(packed) + ";\n"
        "  var cache = null;\n"
        "  function decode() {\n"
        "    var i = D.indexOf('\\n'), j = D.indexOf('\\n', i + 1);\n"
        "    var C = D.slice(0, i).split('\\t');\n"
        "    var Z = D.slice(i + 1, j).split('\\t');\n"
        "    // Country names carry accents too (Cote d'Ivoire, Curacao). Fold the\n"
        "    // palette once rather than per city: 244 entries against 25008.\n"
        "    var CF = C.map(function (x) { return globalThis.foldQuery(x); });\n"
        "    return D.slice(j + 1).split('\\n').map(function (line) {\n"
        "      var p = line.split('\\t');\n"
        "      return {\n"
        "        name: p[0],\n"
        "        fold: p[1] || p[0].toLowerCase(),\n"
        "        country: C[+p[2]],\n"
        "        cfold: CF[+p[2]],\n"
        "        lat: +p[3] / 1e4,\n"
        "        lon: +p[4] / 1e4,\n"
        "        tz: Z[+p[5]],\n"
        "        alt: p[6] || ''\n"
        "      };\n"
        "    });\n"
        "  }\n"
        "  Object.defineProperty(globalThis, 'CITIES', {\n"
        "    configurable: true,\n"
        "    get: function () { return cache || (cache = decode()); }\n"
        "  });\n"
        "  // Fold a user query the same way city names are folded, so typing\n"
        "  // 'Herat' matches 'Herat' and typing 'Herat' with the macron also matches.\n"
        "  // Both halves must fold or the comparison is asymmetric and still misses.\n"
        "  globalThis.foldQuery = function (s) {\n"
        "    return String(s).normalize('NFD').replace(/[\\u0300-\\u036f]/g, '').toLowerCase()\n      .replace(/[\\u00f8\\u00e6\\u00f0\\u00fe\\u0142\\u0111\\u00df\\u0153\\u014b\\u0127\\u0131\\u0167\\u0133]/g, function (c) {\n        return ATOMIC[c];\n      });\n"
        "  };\n"
        "})();\n"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--target', type=int, default=DEFAULT_TARGET)
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    rows, core, filled, manual = collect(args.target)
    exonyms = english_exonyms(c['gid'] for c in rows if 'gid' in c)
    packed = pack(rows, exonyms)
    js = render_js(packed, len(rows))

    raw = len(js.encode())
    gz = len(gzip.compress(js.encode(), 9))
    print(f"curated core         : {core}")
    print(f"after population fill: {filled}")
    print(f"manual extras added  : {manual}")
    print(f"TOTAL                : {len(rows)}")
    print(f"cities.js            : {raw/1024:.1f} KiB raw, {gz/1024:.1f} KiB gzipped")
    print(f"cities.tsv           : {len(packed.encode())/1024:.1f} KiB")
    searchable = sum(1 for line in packed.split("\n")[2:] if line.rsplit("\t", 1)[-1])
    print(f"English exonyms      : {len(exonyms)} found, {searchable} add a new searchable form")

    for city in ('Kleppestø', 'Askøy', 'Florvåg', 'Strusshamn', 'Erdal',
                 'Hetlevik', 'Follese', 'Molde', 'Coventry'):
        assert any(c['name'] == city for c in rows), f"MISSING manual city: {city}"
    print("manual-extras check  : all present")

    if args.dry_run:
        print("\n--dry-run: nothing written")
        return 0

    for app in APPS:
        path = os.path.join(HERE, app, 'cities.js')
        if not os.path.isdir(os.path.dirname(path)):
            print(f"  SKIP {app}: no such directory")
            continue
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(js)
        print(f"  wrote {app}/cities.js")

    os.makedirs(os.path.dirname(TSV_TARGET), exist_ok=True)
    with open(TSV_TARGET, 'w', encoding='utf-8') as fh:
        fh.write(packed)
    print(f"  wrote SunApp/data/cities.tsv")
    return 0


if __name__ == '__main__':
    sys.exit(main())
