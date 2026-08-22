#!/usr/bin/env python3
"""Assert the web catalogue and the Android asset are the same data.

Both are written by update_cities.py in a single run, so they can only diverge
if somebody edits one by hand or reruns the generator against only one target.
That is exactly the failure this guard exists to catch, because the symptom
otherwise is a phone and a website quietly disagreeing about where a city is.

Exits non-zero on mismatch. Run in CI before the Gradle build.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CITIES_JS = os.path.join(HERE, "cities.js")
CITIES_TSV = os.path.join(HERE, "data", "cities.tsv")


def packed_from_js(path):
    """The generated cities.js embeds the packed table as one JSON string literal."""
    src = open(path, encoding="utf-8").read()
    m = re.search(r'var D = ("(?:[^"\\]|\\.)*");', src, re.S)
    if not m:
        sys.exit(f"{path}: could not find the packed data literal")
    return json.loads(m.group(1))


def main():
    for p in (CITIES_JS, CITIES_TSV):
        if not os.path.exists(p):
            sys.exit(f"missing: {p}")

    js = packed_from_js(CITIES_JS)
    tsv = open(CITIES_TSV, encoding="utf-8").read()

    if js == tsv:
        rows = len(tsv.split("\n")) - 2
        print(f"city parity OK: {rows} rows, byte-identical in cities.js and data/cities.tsv")
        return 0

    jl, tl = js.split("\n"), tsv.split("\n")
    print(f"MISMATCH: cities.js has {len(jl)} lines, cities.tsv has {len(tl)}", file=sys.stderr)
    for i, (a, b) in enumerate(zip(jl, tl)):
        if a != b:
            print(f"  first difference at line {i + 1}", file=sys.stderr)
            print(f"    cities.js : {a[:160]}", file=sys.stderr)
            print(f"    cities.tsv: {b[:160]}", file=sys.stderr)
            break
    print("Re-run SunApp/update_cities.py to regenerate both from one source.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
