/**
 * SunApp — app.js
 * ================================================================
 * A fully client-side sun-times calculator.
 * Libraries used:
 *   • SunCalc  (bundled locally in suncalc.js) — astronomical math
 *   • Intl API (built-in)                     — timezone-aware formatting
 *
 * Key design decisions:
 *   1. All SunCalc calls return UTC Date objects — we then format
 *      them with Intl.DateTimeFormat using the TARGET timezone, so
 *      the times are always correct for the queried location, not
 *      the browser's local timezone.
 *   2. For cities we already have the IANA timezone ID embedded in
 *      the database. For typed coordinates we resolve the timezone
 *      offline from the nearest known city. Nothing hits the network.
 *      There is no device-location option: the stormberry.as zone
 *      sends Permissions-Policy geolocation=(), which blocks it on
 *      every Labs host, so it was removed on 2026-10-02.
 *   3. Polar Night / Midnight Sun: SunCalc returns NaN Dates when
 *      the sun doesn't cross the horizon — we detect this and show
 *      a user-friendly label instead of crashing.
 * ================================================================
 */

'use strict';

/* Types for the TypeScript checker only (see jsconfig.json); the browser ignores them.
   City, SunCalc and foldQuery come from types/cities.d.ts and types/suncalc.d.ts. */

/**
 * The resolved zone for a calculation.
 * @typedef {object} TzInfo
 * @property {string} ianaId        IANA identifier, for example "Europe/Oslo"
 * @property {string} abbreviation  short name such as "CEST", or "" when Intl has none
 */

/** @typedef {'midnight-sun' | 'polar-night'} PolarCondition */

/**
 * Everything renderResults shows.
 * @typedef {object} SunResults
 * @property {number} lat
 * @property {number} lon
 * @property {string} tz
 * @property {string} tzAbbr
 * @property {string} dateStr
 * @property {string} locationLabel  computed by onCalculate but not shown
 * @property {string | null} sunriseStr
 * @property {string | null} noonStr
 * @property {string | null} sunsetStr
 * @property {PolarCondition | null} polarCondition
 * @property {string | null} dayLengthStr  "14h 10m", or null on a polar day
 * @property {number} dayLengthPct          0 to 100
 */

/* ================================================================
   SECTION 1 — CITY DATABASE
   Format: { name, country, lat, lon, tz }
   Coverage: 25,007 cities worldwide, shared across every Labs app.
   Timezone IDs are IANA strings (used with Intl.DateTimeFormat).
================================================================ */
// The city catalogue lives in the shared cities.js, loaded by index.html
// before this file. Regenerate every app's copy with GitHub/update_cities.py.

/* ================================================================
   SECTION 2 — APP STATE
   A single object that tracks what's currently selected.
================================================================ */
/** @type {{ tab: string, city: City | null }} */
const state = {
  tab: 'city',           // 'city' | 'gps'
  city: null,            // Selected city object from CITIES array
};

/* ================================================================
   SECTION 3 — DOM REFERENCES
   Grab all elements we need once at startup.
================================================================ */
/** @type {(id: string) => HTMLElement | null} */
const $ = id => document.getElementById(id);

/* Every id below is in index.html, which loads this script last, so each cast only drops
   the null that getElementById allows for a missing element. */

const els = {
  // Tabs
  tabCity:   /** @type {HTMLButtonElement} */ ($('tab-city')),
  tabGps:    /** @type {HTMLButtonElement} */ ($('tab-gps')),
  // Panels
  panelCity:   /** @type {HTMLElement} */ ($('panel-city')),
  panelGps:    /** @type {HTMLElement} */ ($('panel-gps')),
  // City search
  citySearch:   /** @type {HTMLInputElement} */ ($('city-search')),
  cityDropdown: /** @type {HTMLUListElement} */ ($('city-dropdown')),
  citySelected: /** @type {HTMLElement} */ ($('city-selected')),
  citySelectedText: /** @type {HTMLElement} */ ($('city-selected-text')),
  cityClearBtn: /** @type {HTMLButtonElement} */ ($('city-clear-btn')),
  // GPS inputs
  latInput: /** @type {HTMLInputElement} */ ($('lat-input')),
  lonInput: /** @type {HTMLInputElement} */ ($('lon-input')),
  latError: /** @type {HTMLElement} */ ($('lat-error')),
  lonError: /** @type {HTMLElement} */ ($('lon-error')),
  gpsEcho:  /** @type {HTMLElement} */ ($('gps-echo')),
  // Date
  dateInput: /** @type {HTMLInputElement} */ ($('date-input')),
  // Calculate
  calculateBtn: /** @type {HTMLButtonElement} */ ($('calculate-btn')),
  errorMsg:     /** @type {HTMLElement} */ ($('error-msg')),
  // Results
  resultsCard:    /** @type {HTMLElement} */ ($('results-card')),
  resCoords:  /** @type {HTMLElement} */ ($('res-coords')),
  resDate:    /** @type {HTMLElement} */ ($('res-date')),
  resTz:      /** @type {HTMLElement} */ ($('res-tz')),
  resSunrise: /** @type {HTMLElement} */ ($('res-sunrise')),
  resNoon:    /** @type {HTMLElement} */ ($('res-noon')),
  resSunset:  /** @type {HTMLElement} */ ($('res-sunset')),
  resDayLength: /** @type {HTMLElement} */ ($('res-daylength')),
  dayBarFill: /** @type {HTMLElement} */ ($('day-bar-fill')),
  // Loading
  loadingOverlay: /** @type {HTMLElement} */ ($('loading-overlay')),
};

/* ================================================================
   SECTION 4 — INITIALISE UI
================================================================ */
function init() {
  // Set date picker to today's local date (YYYY-MM-DD format)
  els.dateInput.value = getTodayString();

  // Wire up tab click events
  [els.tabCity, els.tabGps].forEach(btn => {
    /* Both tab buttons carry data-tab ("city" or "gps") in index.html. */
    btn.addEventListener('click', () => switchTab(/** @type {string} */ (btn.dataset.tab)));
  });

  // Wire up city search
  els.citySearch.addEventListener('input', onCityInput);
  els.citySearch.addEventListener('keydown', onCityKeydown);
  els.cityClearBtn.addEventListener('click', clearCity);

  // Close dropdown when clicking outside
  document.addEventListener('click', e => {
    if (!/** @type {Element} */ (e.target).closest('.search-wrapper')) closeDropdown();
  });

  // Editing a coordinate clears its error and the "Using ..." line, which
  // described the previous value, until the next Calculate.
  els.latInput.addEventListener('input', () => { setFieldError(els.latInput, els.latError, null); setEcho(null); });
  els.lonInput.addEventListener('input', () => { setFieldError(els.lonInput, els.lonError, null); setEcho(null); });

  // Calculate button
  els.calculateBtn.addEventListener('click', onCalculate);
}

/* ================================================================
   SECTION 5 — TAB SWITCHING
================================================================ */
/** @param {string} tab  "city" or "gps" */
function switchTab(tab) {
  state.tab = tab;

  // Update aria/visual state for all tabs
  [els.tabCity, els.tabGps].forEach(btn => {
    const isActive = btn.dataset.tab === tab;
    btn.classList.toggle('active', isActive);
    btn.setAttribute('aria-selected', String(isActive));
  });

  // Show / hide panels
  // Using the 'hidden' attribute (which CSS maps to display:none)
  els.panelCity.hidden = (tab !== 'city');
  els.panelGps.hidden  = (tab !== 'gps');
}

/* ================================================================
   SECTION 6 — CITY SEARCH & DROPDOWN
================================================================ */
// Track which dropdown item is keyboard-highlighted
let highlightIndex = -1;

function onCityInput() {
  const query = els.citySearch.value.trim().toLowerCase();
  highlightIndex = -1;

  if (query.length < 1) {
    closeDropdown();
    return;
  }

  // Filter cities: match on name OR country, prioritise name-starts-with
  const qf = foldQuery(query);
  // Prefix matches first. With 25,000 cities a bare substring filter
  // buries the obvious answer: "erdal" returned Cloverdale, South
  // Riverdale and Terdal ahead of Erdal, and with only 8 rows shown the
  // city being typed could fall off the list entirely.
  const startsWith = [], contains = [];
  for (const c of CITIES) {
    // c.alt is the folded English exonym where GeoNames stores the local
    // name, so "gothenburg" finds Goteborg and "cologne" finds Koeln.
    if (c.fold.startsWith(qf) || c.alt.startsWith(qf)) startsWith.push(c);
    else if (c.fold.includes(qf) || c.alt.includes(qf) || c.cfold.includes(qf)) contains.push(c);
  }
  const matches = startsWith.concat(contains).slice(0, 8); // Limit to 8 results for usability

  if (matches.length === 0) {
    closeDropdown();
    return;
  }

  // Build the dropdown list HTML
  els.cityDropdown.innerHTML = '';
  matches.forEach((city, i) => {
    const li = document.createElement('li');
    li.setAttribute('role', 'option');
    li.setAttribute('aria-selected', 'false');
    li.dataset.index = String(i);
    li.innerHTML = `
      <span class="city-name">${escapeHtml(city.name)}</span>
      <span class="city-country">${escapeHtml(city.country)}</span>
    `;
    li.addEventListener('click', () => selectCity(city));
    li.addEventListener('mouseenter', () => {
      setHighlight(i);
    });
    els.cityDropdown.appendChild(li);
  });

  // Store matches so keyboard navigation can reference them
  els.cityDropdown._matches = matches;
  els.cityDropdown.removeAttribute('hidden');
  els.citySearch.setAttribute('aria-expanded', 'true');
}

/** @param {KeyboardEvent} e */
function onCityKeydown(e) {
  const items = els.cityDropdown.querySelectorAll('li');

  if (e.key === 'ArrowDown') {
    e.preventDefault();
    setHighlight(Math.min(highlightIndex + 1, items.length - 1));
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    setHighlight(Math.max(highlightIndex - 1, 0));
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (highlightIndex >= 0 && els.cityDropdown._matches) {
      selectCity(els.cityDropdown._matches[highlightIndex]);
    }
  } else if (e.key === 'Escape') {
    closeDropdown();
  }
}

/** @param {number} index  row to highlight, -1 for none */
function setHighlight(index) {
  const items = els.cityDropdown.querySelectorAll('li');
  items.forEach((li, i) => li.classList.toggle('highlighted', i === index));
  highlightIndex = index;
}

/** @param {City} city */
function selectCity(city) {
  state.city = city;
  els.citySearch.value = '';
  closeDropdown();

  // Show the selected-city badge
  els.citySelectedText.textContent = `${city.name}, ${city.country} (${city.tz})`;
  els.citySelected.removeAttribute('hidden');
}

function clearCity() {
  state.city = null;
  els.citySelected.setAttribute('hidden', '');
  els.citySearch.value = '';
  // Also forgets the keyboard highlight and the last match list, so Enter in the
  // emptied field cannot re-select a city from a list that is no longer shown.
  closeDropdown();
  els.citySearch.focus();
}

function closeDropdown() {
  els.cityDropdown.setAttribute('hidden', '');
  els.citySearch.setAttribute('aria-expanded', 'false');
  els.cityDropdown.innerHTML = '';
  // A closed list has nothing to select. Before 2026-10-16 the highlight and the
  // stored matches survived closing, so after a keyboard pick, Clear and Enter (or
  // Escape and Enter, or ArrowUp and Enter) re-selected a city from the old list.
  highlightIndex = -1;
  els.cityDropdown._matches = undefined;
}

/* ================================================================
   SECTION 7: TYPED NUMBERS
================================================================ */
// Shared Labs parser: accepts "60,39" and a Unicode minus, rejects anything else or out of range (null).
/**
 * @param {string | null | undefined} text
 * @param {number} min
 * @param {number} max
 * @returns {number | null}
 */
function parseDecimal(text, min, max) {
  if (text == null) return null;
  let s = String(text).trim().replace(/[\u2212\u2012\u2013\u2014\uFE63\uFF0D]/g, '-').replace(/\s+/g, '');
  if (/^[+-]?\d+,\d+$/.test(s)) s = s.replace(',', '.');
  if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(s)) return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= min && n <= max ? n : null;
}

/**
 * Show (msg) or clear (null) the inline message under one coordinate field.
 * @param {HTMLInputElement} input
 * @param {HTMLElement} errorEl
 * @param {string | null} msg
 */
function setFieldError(input, errorEl, msg) {
  if (msg) {
    errorEl.textContent = msg;
    errorEl.hidden = false;
    input.setAttribute('aria-invalid', 'true');
  } else {
    errorEl.textContent = '';
    errorEl.hidden = true;
    input.removeAttribute('aria-invalid');
  }
}

/** Show (text) or clear (null) the "Using ..." line. Cleared text, not just
 *  hidden: aria-describedby still reads a hidden element it points at.
 *  @param {string | null} text */
function setEcho(text) {
  els.gpsEcho.textContent = text || '';
  els.gpsEcho.hidden = !text;
}

/* ================================================================
   SECTION 8 — TIMEZONE RESOLUTION (FULLY OFFLINE)
   No network calls. City zones come straight from the bundled city
   database, and typed coordinates resolve to the nearest known
   city's zone. Nothing hits the network.
================================================================ */
/**
 * @param {number} lat
 * @param {number} lon
 * @returns {string} IANA zone of the nearest catalogue city
 */
function nearestCityTimezone(lat, lon) {
  // Timezones are large political regions and the bundled city list is dense
  // near populated areas, so the nearest city's zone is the correct one in
  // practice. Equirectangular distance is plenty for a nearest-neighbour pick.
  /** @type {City | null} */
  let best = null, bestDist = Infinity;
  for (const c of CITIES) {
    let dLon = Math.abs(c.lon - lon);
    if (dLon > 180) dLon = 360 - dLon;
    const dLat = c.lat - lat;
    const x = dLon * Math.cos(((lat + c.lat) / 2) * Math.PI / 180);
    const dist = x * x + dLat * dLat;
    if (dist < bestDist) { bestDist = dist; best = c; }
  }
  return best ? best.tz : (Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
}

/**
 * @param {number} lat
 * @param {number} lon
 * @returns {TzInfo}
 */
function resolveTimezone(lat, lon) {
  const ianaId = nearestCityTimezone(lat, lon);
  return { ianaId, abbreviation: getTimezoneAbbreviation(ianaId, els.dateInput.value) };
}

/* ================================================================
   SECTION 9 — MAIN CALCULATE HANDLER
================================================================ */
async function onCalculate() {
  clearError();

  // --- 9.1: Gather lat/lon and timezone based on active tab ---
  /* Both tabs below assign lat, lon and tzInfo or return early, so the NaN start values are
     never read; they exist so the checker can see lat and lon are always numbers. */
  let lat = NaN, lon = NaN;
  /** @type {TzInfo | undefined} */ let tzInfo;

  if (state.tab === 'city') {
    if (!state.city) {
      showError('Please select a city from the search list first.');
      return;
    }
    lat = state.city.lat;
    lon = state.city.lon;
    // We already know the timezone for cities — no API call needed
    tzInfo = { ianaId: state.city.tz, abbreviation: getTimezoneAbbreviation(state.city.tz, els.dateInput.value) };

  } else if (state.tab === 'gps') {
    const latVal = parseDecimal(els.latInput.value, -90, 90);
    const lonVal = parseDecimal(els.lonInput.value, -180, 180);

    setFieldError(els.latInput, els.latError,
      latVal === null ? 'Enter a latitude between -90 and 90, such as 60.39 or 60,39.' : null);
    setFieldError(els.lonInput, els.lonError,
      lonVal === null ? 'Enter a longitude between -180 and 180, such as 5.32 or 5,32.' : null);

    if (latVal === null || lonVal === null) {
      // Do not compute, and do not leave an older result showing under the error.
      setEcho(null);
      els.resultsCard.setAttribute('hidden', '');
      (latVal === null ? els.latInput : els.lonInput).focus();
      return;
    }

    lat = latVal;
    lon = lonVal;
    // Echo the numbers actually used, so "6,5" visibly became 6.5.
    setEcho(`Using ${lat}, ${lon}`);

    showLoading(true);
    try {
      tzInfo = await resolveTimezone(lat, lon);
    } catch (err) {
      showLoading(false);
      showError(`Could not resolve timezone for these coordinates: ${/** @type {Error} */ (err).message}`);
      return;
    }
    showLoading(false);
  }

  // --- 9.2: Parse the selected date ---
  // The date input gives "YYYY-MM-DD". We construct a date at UTC
  // noon so SunCalc uses the correct calendar day regardless of timezone.
  const [year, month, day] = els.dateInput.value.split('-').map(Number);
  if (!year || !month || !day) {
    showError('Please select a valid date.');
    return;
  }

  // Pass a UTC date at noon — SunCalc just needs the correct day
  const dateForCalc = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));

  // --- 9.3: Call SunCalc ---
  const times = SunCalc.getTimes(dateForCalc, lat, lon);

  // SunCalc returns local JS Date objects for each event.
  // `times.sunrise` and `times.sunset` are NaN-valued Dates when the
  // sun doesn't rise or set (polar conditions).
  const sunriseValid = isValidDate(times.sunrise);
  const sunsetValid  = isValidDate(times.sunset);
  const noonValid    = isValidDate(times.solarNoon);

  // --- 9.4: Determine polar condition ---
  // Compare the day's altitude EXTREMES against the sunrise/sunset altitude
  // itself, not against zero. Sunrise and sunset are defined at -0.833 deg
  // (atmospheric refraction plus the sun's apparent radius), so zero is the
  // wrong threshold: a sun that never climbs above -0.5 deg has still never
  // "risen" by that definition, and one that never falls below -0.5 deg has
  // never set.
  //
  // Keep this identical to DayKind in the SunApp Kotlin port. The two surfaces
  // are required to agree, so a change here is a change there.
  //
  // Measured impact is deliberately small: across 2026 this differs from the
  // old noon-against-zero test on ONE day at Longyearbyen and on none at
  // Tromso or Bergen. It is here for correctness and cross-surface parity, not
  // because users were seeing wrong labels every week.
  /** @type {PolarCondition | null} */
  let polarCondition = null;
  if (!sunriseValid || !sunsetValid) {
    const HORIZON = -0.833 * Math.PI / 180;
    const maxAltitude = SunCalc.getPosition(times.solarNoon, lat, lon).altitude;
    const minAltitude = SunCalc.getPosition(times.nadir, lat, lon).altitude;
    if (minAltitude > HORIZON) {
      polarCondition = 'midnight-sun';   // never drops to the sunset altitude
    } else if (maxAltitude < HORIZON) {
      polarCondition = 'polar-night';    // never climbs to the sunrise altitude
    } else {
      // Boundary case: the sun does cross the horizon altitude, but so
      // shallowly that SunCalc's solver could not locate the crossing.
      // Observed once, 2026-04-19 at Longyearbyen, where the sun dips 0.007 deg
      // below the threshold at nadir. Fall back to the old test, which gives
      // the sensible answer for a sun that is up essentially the whole day.
      polarCondition = maxAltitude > 0 ? 'midnight-sun' : 'polar-night';
    }
  }

  // --- 9.5: Format the times in the TARGET timezone (not browser's!) ---
  /* Both tabs set tzInfo or return above; state.tab is only ever 'city' or 'gps'. */
  const info = /** @type {TzInfo} */ (tzInfo);
  const tz = info.ianaId;

  const sunriseStr = sunriseValid ? formatTime(times.sunrise, tz) : null;
  const noonStr    = noonValid    ? formatTime(times.solarNoon, tz) : null;
  const sunsetStr  = sunsetValid  ? formatTime(times.sunset, tz)  : null;

  // --- 9.6: Calculate day length ---
  /** @type {string | null} */
  let dayLengthStr  = null;
  let dayLengthPct  = 0;

  if (sunriseValid && sunsetValid) {
    const ms = times.sunset.getTime() - times.sunrise.getTime();
    const totalMinutes = Math.round(ms / 60000);
    const h = Math.floor(totalMinutes / 60);
    const m = totalMinutes % 60;
    dayLengthStr = `${h}h ${m}m`;
    // Percentage of maximum possible day length (24h)
    dayLengthPct = Math.min(100, (ms / (24 * 3600 * 1000)) * 100);
  }

  // --- 9.7: Build location label ---
  let locationLabel;
  if (state.tab === 'city') {
    /* The city tab returned above unless a city is selected, and nothing in between awaits. */
    const city = /** @type {City} */ (state.city);
    locationLabel = `${city.name}, ${city.country}`;
  } else {
    locationLabel = `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
  }

  // --- 9.8: Render results ---
  renderResults({
    lat, lon, tz, tzAbbr: info.abbreviation,
    dateStr: formatDate(dateForCalc),
    locationLabel,
    sunriseStr, noonStr, sunsetStr,
    polarCondition,
    dayLengthStr, dayLengthPct,
  });
}

/* ================================================================
   SECTION 10 — RESULTS RENDERING
================================================================ */
/** @param {SunResults} results */
function renderResults({
  lat, lon, tz, tzAbbr,
  dateStr, locationLabel,
  sunriseStr, noonStr, sunsetStr,
  polarCondition,
  dayLengthStr, dayLengthPct,
}) {
  // Populate the meta pills
  els.resCoords.textContent = `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
  els.resDate.textContent   = dateStr;
  els.resTz.textContent     = tzAbbr ? `${tzAbbr} / ${tz}` : tz;

  // Populate the three sun-time cards
  if (polarCondition === 'midnight-sun') {
    // Sun never sets
    els.resSunrise.innerHTML = '<span class="polar-sun">🌞 Midnight Sun</span>';
    els.resSunset.innerHTML  = '<span class="polar-sun">🌞 Midnight Sun</span>';
    els.resSunrise.className = '';
    els.resSunset.className  = '';
  } else if (polarCondition === 'polar-night') {
    // Sun never rises
    els.resSunrise.innerHTML = '<span class="polar-midnight">🌑 Polar Night</span>';
    els.resSunset.innerHTML  = '<span class="polar-midnight">🌑 Polar Night</span>';
    els.resSunrise.className = '';
    els.resSunset.className  = '';
  } else {
    // Normal day — show formatted times
    els.resSunrise.textContent = sunriseStr;
    els.resSunset.textContent  = sunsetStr;
    els.resSunrise.className = 'sun-time-value mono';
    els.resSunset.className  = 'sun-time-value mono';
  }

  // Solar noon is always available (even in polar conditions)
  els.resNoon.textContent = noonStr || '—';
  els.resNoon.className = 'sun-time-value mono';

  // Day length bar
  if (dayLengthStr) {
    els.resDayLength.textContent = dayLengthStr;
    // Trigger the CSS transition after a small delay so it animates
    requestAnimationFrame(() => {
      els.dayBarFill.style.width = `${dayLengthPct.toFixed(1)}%`;
    });
  } else {
    els.resDayLength.textContent = polarCondition === 'midnight-sun' ? '24h 0m' : '0h 0m';
    els.dayBarFill.style.width   = polarCondition === 'midnight-sun' ? '100%' : '0%';
  }


  // Show results (remove hidden attribute)
  els.resultsCard.removeAttribute('hidden');

  // Smooth scroll to results
  setTimeout(() => els.resultsCard.scrollIntoView({ behavior: 'smooth', block: 'start' }), 100);
}

/* ================================================================
   SECTION 11 — HELPER FUNCTIONS
================================================================ */

/**
 * Returns today's date as a YYYY-MM-DD string based on the
 * browser's local date (for the date-picker default).
 *
 * @returns {string} e.g. "2026-10-16"
 */
function getTodayString() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Returns true if 'd' is a Date object with a valid numeric value.
 * SunCalc returns Dates with NaN time values for polar conditions.
 *
 * @param {unknown} d
 * @returns {d is Date}
 */
function isValidDate(d) {
  return d instanceof Date && !isNaN(d.getTime());
}

/**
 * Formats a UTC Date object into "HH:MM:SS" in the specified IANA timezone.
 * This is the core of the timezone-correct output requirement.
 *
 * @param {Date}   date   - A UTC Date (e.g. from SunCalc)
 * @param {string} tzId   - IANA timezone string, e.g. "Europe/Oslo"
 * @returns {string}      - e.g. "07:05:43"
 */
function formatTime(date, tzId) {
  return new Intl.DateTimeFormat('en-GB', {
    hour:   '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZone: tzId,
  }).format(date);
}

/**
 * Formats a Date as "DD/MonthName/YYYY" — matches the example in the brief.
 * Uses the UTC date parts so the day isn't shifted by local timezone.
 *
 * @param {Date} date
 * @returns {string} e.g. "01/April/2026"
 */
function formatDate(date) {
  const months = ['January','February','March','April','May','June',
                  'July','August','September','October','November','December'];
  const d = String(date.getUTCDate()).padStart(2, '0');
  const m = months[date.getUTCMonth()];
  const y = date.getUTCFullYear();
  return `${d}/${m}/${y}`;
}

/**
 * Derives the short timezone abbreviation (e.g. "CEST") for a given
 * IANA timezone and date string, using the Intl API.
 *
 * @param {string} ianaId   - e.g. "Europe/Oslo"
 * @param {string} dateStr  - "YYYY-MM-DD"
 * @returns {string}        - e.g. "CEST"
 */
function getTimezoneAbbreviation(ianaId, dateStr) {
  try {
    const [y, mo, d] = dateStr.split('-').map(Number);
    const date = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0));

    // 'short' timeZoneName gives us the abbreviation like "CEST", "PST" etc.
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: ianaId,
      timeZoneName: 'short',
    }).formatToParts(date);

    const tzPart = parts.find(p => p.type === 'timeZoneName');
    return tzPart ? tzPart.value : '';
  } catch {
    return '';
  }
}


/**
 * Escapes HTML special characters, so a city name is shown as text and never parsed
 * as markup. Same function as MoonApp's. Added 2026-10-16: the catalogue is ours,
 * but its one "&" entry worked only because the parser forgave a bare ampersand.
 *
 * @param {string} str
 * @returns {string}
 */
function escapeHtml(str) {
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

/* ================================================================
   SECTION 12 — UI STATE HELPERS
================================================================ */

/** Display an error message below the calculate button, and hide any
 *  earlier result so it cannot be read as the answer to the new input.
 *  @param {string} msg */
function showError(msg) {
  els.errorMsg.textContent = msg;
  els.errorMsg.removeAttribute('hidden');
  els.resultsCard.setAttribute('hidden', '');
}

/** Clear any visible error message */
function clearError() {
  els.errorMsg.setAttribute('hidden', '');
  els.errorMsg.textContent = '';
}

/** Show or hide the full-screen loading overlay
 *  @param {boolean} show */
function showLoading(show) {
  els.loadingOverlay.hidden = !show;
}

/* ================================================================
   SECTION 13 — BOOTSTRAP
================================================================ */
// Run init once the DOM is fully parsed (script is at end of body,
// so this is essentially immediate, but we guard with DOMContentLoaded).
document.addEventListener('DOMContentLoaded', init);
