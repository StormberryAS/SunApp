// Small helpers for the tests, added on 17 October 2026 when the tests became TypeScript: with
// noUncheckedIndexedAccess a regular expression's group or an array's element may be missing, and
// these say so by name instead of failing later with a TypeError. Not a test file itself.

/** Group n (1 unless given) of the first match of re in text; throws when there is none. */
export function group(re: RegExp, text: string, n = 1): string {
  const m = re.exec(text);
  const g = m ? m[n] : undefined;
  if (g === undefined) throw new Error(`no match for ${re} (group ${n})`);
  return g;
}

/** The value, or an error naming what is missing. */
export function defined<T>(value: T | null | undefined, what: string): T {
  if (value === null || value === undefined) throw new Error(`missing: ${what}`);
  return value;
}
