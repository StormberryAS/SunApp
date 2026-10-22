"use strict";
/*! SunApp: app.js is compiled from ts/app.ts by tools/build-ts.sh, which leaves out every comment
    but this one: the explanations are in the source. Edit the .ts, never the .js: the build refuses
    to overwrite a hand edit, and its check mode fails on one. */
const state = {
    tab: 'city',
    city: null,
};
const $ = (id) => document.getElementById(id);
const els = {
    tabCity: $('tab-city'),
    tabGps: $('tab-gps'),
    panelCity: $('panel-city'),
    panelGps: $('panel-gps'),
    citySearch: $('city-search'),
    cityDropdown: $('city-dropdown'),
    citySelected: $('city-selected'),
    citySelectedText: $('city-selected-text'),
    cityClearBtn: $('city-clear-btn'),
    latInput: $('lat-input'),
    lonInput: $('lon-input'),
    latError: $('lat-error'),
    lonError: $('lon-error'),
    gpsEcho: $('gps-echo'),
    dateInput: $('date-input'),
    calculateBtn: $('calculate-btn'),
    errorMsg: $('error-msg'),
    resultsCard: $('results-card'),
    resCoords: $('res-coords'),
    resDate: $('res-date'),
    resTz: $('res-tz'),
    resSunrise: $('res-sunrise'),
    resNoon: $('res-noon'),
    resSunset: $('res-sunset'),
    resDayLength: $('res-daylength'),
    dayBarFill: $('day-bar-fill'),
    loadingOverlay: $('loading-overlay'),
};
function init() {
    els.dateInput.value = getTodayString();
    [els.tabCity, els.tabGps].forEach(btn => {
        btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });
    els.citySearch.addEventListener('input', onCityInput);
    els.citySearch.addEventListener('keydown', onCityKeydown);
    els.cityClearBtn.addEventListener('click', clearCity);
    document.addEventListener('click', e => {
        if (!e.target.closest('.search-wrapper'))
            closeDropdown();
    });
    els.latInput.addEventListener('input', () => { setFieldError(els.latInput, els.latError, null); setEcho(null); });
    els.lonInput.addEventListener('input', () => { setFieldError(els.lonInput, els.lonError, null); setEcho(null); });
    els.calculateBtn.addEventListener('click', onCalculate);
}
function switchTab(tab) {
    state.tab = tab;
    [els.tabCity, els.tabGps].forEach(btn => {
        const isActive = btn.dataset.tab === tab;
        btn.classList.toggle('active', isActive);
        btn.setAttribute('aria-selected', String(isActive));
    });
    els.panelCity.hidden = (tab !== 'city');
    els.panelGps.hidden = (tab !== 'gps');
}
let highlightIndex = -1;
function onCityInput() {
    const query = els.citySearch.value.trim().toLowerCase();
    highlightIndex = -1;
    if (query.length < 1) {
        closeDropdown();
        return;
    }
    const qf = foldQuery(query);
    const startsWith = [], contains = [];
    for (const c of CITIES) {
        if (c.fold.startsWith(qf) || c.alt.startsWith(qf))
            startsWith.push(c);
        else if (c.fold.includes(qf) || c.alt.includes(qf) || c.cfold.includes(qf))
            contains.push(c);
    }
    const matches = startsWith.concat(contains).slice(0, 8);
    if (matches.length === 0) {
        closeDropdown();
        return;
    }
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
    els.cityDropdown._matches = matches;
    els.cityDropdown.removeAttribute('hidden');
    els.citySearch.setAttribute('aria-expanded', 'true');
}
function onCityKeydown(e) {
    const items = els.cityDropdown.querySelectorAll('li');
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        setHighlight(Math.min(highlightIndex + 1, items.length - 1));
    }
    else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setHighlight(Math.max(highlightIndex - 1, 0));
    }
    else if (e.key === 'Enter') {
        e.preventDefault();
        if (highlightIndex >= 0 && els.cityDropdown._matches) {
            selectCity(els.cityDropdown._matches[highlightIndex]);
        }
    }
    else if (e.key === 'Escape') {
        closeDropdown();
    }
}
function setHighlight(index) {
    const items = els.cityDropdown.querySelectorAll('li');
    items.forEach((li, i) => li.classList.toggle('highlighted', i === index));
    highlightIndex = index;
}
function selectCity(city) {
    state.city = city;
    els.citySearch.value = '';
    closeDropdown();
    els.citySelectedText.textContent = `${city.name}, ${city.country} (${city.tz})`;
    els.citySelected.removeAttribute('hidden');
}
function clearCity() {
    state.city = null;
    els.citySelected.setAttribute('hidden', '');
    els.citySearch.value = '';
    closeDropdown();
    els.citySearch.focus();
}
function closeDropdown() {
    els.cityDropdown.setAttribute('hidden', '');
    els.citySearch.setAttribute('aria-expanded', 'false');
    els.cityDropdown.innerHTML = '';
    highlightIndex = -1;
    els.cityDropdown._matches = undefined;
}
function parseDecimal(text, min, max) {
    if (text == null)
        return null;
    let s = String(text).trim().replace(/[\u2212\u2012\u2013\u2014\uFE63\uFF0D]/g, '-').replace(/\s+/g, '');
    if (/^[+-]?\d+,\d+$/.test(s))
        s = s.replace(',', '.');
    if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(s))
        return null;
    const n = Number(s);
    return Number.isFinite(n) && n >= min && n <= max ? n : null;
}
function setFieldError(input, errorEl, msg) {
    if (msg) {
        errorEl.textContent = msg;
        errorEl.hidden = false;
        input.setAttribute('aria-invalid', 'true');
    }
    else {
        errorEl.textContent = '';
        errorEl.hidden = true;
        input.removeAttribute('aria-invalid');
    }
}
function setEcho(text) {
    els.gpsEcho.textContent = text || '';
    els.gpsEcho.hidden = !text;
}
function nearestCityTimezone(lat, lon) {
    let best = null, bestDist = Infinity;
    for (const c of CITIES) {
        let dLon = Math.abs(c.lon - lon);
        if (dLon > 180)
            dLon = 360 - dLon;
        const dLat = c.lat - lat;
        const x = dLon * Math.cos(((lat + c.lat) / 2) * Math.PI / 180);
        const dist = x * x + dLat * dLat;
        if (dist < bestDist) {
            bestDist = dist;
            best = c;
        }
    }
    return best ? best.tz : (Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC');
}
function resolveTimezone(lat, lon) {
    const ianaId = nearestCityTimezone(lat, lon);
    return { ianaId, abbreviation: getTimezoneAbbreviation(ianaId, els.dateInput.value) };
}
async function onCalculate() {
    clearError();
    let lat = NaN, lon = NaN;
    let tzInfo;
    if (state.tab === 'city') {
        if (!state.city) {
            showError('Please select a city from the search list first.');
            return;
        }
        lat = state.city.lat;
        lon = state.city.lon;
        tzInfo = { ianaId: state.city.tz, abbreviation: getTimezoneAbbreviation(state.city.tz, els.dateInput.value) };
    }
    else if (state.tab === 'gps') {
        const latVal = parseDecimal(els.latInput.value, -90, 90);
        const lonVal = parseDecimal(els.lonInput.value, -180, 180);
        setFieldError(els.latInput, els.latError, latVal === null ? 'Enter a latitude between -90 and 90, such as 60.39 or 60,39.' : null);
        setFieldError(els.lonInput, els.lonError, lonVal === null ? 'Enter a longitude between -180 and 180, such as 5.32 or 5,32.' : null);
        if (latVal === null || lonVal === null) {
            setEcho(null);
            els.resultsCard.setAttribute('hidden', '');
            (latVal === null ? els.latInput : els.lonInput).focus();
            return;
        }
        lat = latVal;
        lon = lonVal;
        setEcho(`Using ${lat}, ${lon}`);
        showLoading(true);
        try {
            tzInfo = await resolveTimezone(lat, lon);
        }
        catch (err) {
            showLoading(false);
            showError(`Could not resolve timezone for these coordinates: ${err.message}`);
            return;
        }
        showLoading(false);
    }
    const [year, month, day] = els.dateInput.value.split('-').map(Number);
    if (!year || !month || !day) {
        showError('Please select a valid date.');
        return;
    }
    const dateForCalc = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
    const times = SunCalc.getTimes(dateForCalc, lat, lon);
    const sunriseValid = isValidDate(times.sunrise);
    const sunsetValid = isValidDate(times.sunset);
    const noonValid = isValidDate(times.solarNoon);
    let polarCondition = null;
    if (!sunriseValid || !sunsetValid) {
        const HORIZON = -0.833 * Math.PI / 180;
        const maxAltitude = SunCalc.getPosition(times.solarNoon, lat, lon).altitude;
        const minAltitude = SunCalc.getPosition(times.nadir, lat, lon).altitude;
        if (minAltitude > HORIZON) {
            polarCondition = 'midnight-sun';
        }
        else if (maxAltitude < HORIZON) {
            polarCondition = 'polar-night';
        }
        else {
            polarCondition = maxAltitude > 0 ? 'midnight-sun' : 'polar-night';
        }
    }
    const info = tzInfo;
    const tz = info.ianaId;
    const sunriseStr = sunriseValid ? formatTime(times.sunrise, tz) : null;
    const noonStr = noonValid ? formatTime(times.solarNoon, tz) : null;
    const sunsetStr = sunsetValid ? formatTime(times.sunset, tz) : null;
    let dayLengthStr = null;
    let dayLengthPct = 0;
    if (sunriseValid && sunsetValid) {
        const ms = times.sunset.getTime() - times.sunrise.getTime();
        const totalMinutes = Math.round(ms / 60000);
        const h = Math.floor(totalMinutes / 60);
        const m = totalMinutes % 60;
        dayLengthStr = `${h}h ${m}m`;
        dayLengthPct = Math.min(100, (ms / (24 * 3600 * 1000)) * 100);
    }
    let locationLabel;
    if (state.tab === 'city') {
        const city = state.city;
        locationLabel = `${city.name}, ${city.country}`;
    }
    else {
        locationLabel = `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
    }
    renderResults({
        lat, lon, tz, tzAbbr: info.abbreviation,
        dateStr: formatDate(dateForCalc),
        locationLabel,
        sunriseStr, noonStr, sunsetStr,
        polarCondition,
        dayLengthStr, dayLengthPct,
    });
}
function renderResults({ lat, lon, tz, tzAbbr, dateStr, sunriseStr, noonStr, sunsetStr, polarCondition, dayLengthStr, dayLengthPct, }) {
    els.resCoords.textContent = `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
    els.resDate.textContent = dateStr;
    els.resTz.textContent = tzAbbr ? `${tzAbbr} / ${tz}` : tz;
    if (polarCondition === 'midnight-sun') {
        els.resSunrise.innerHTML = '<span class="polar-sun">🌞 Midnight Sun</span>';
        els.resSunset.innerHTML = '<span class="polar-sun">🌞 Midnight Sun</span>';
        els.resSunrise.className = '';
        els.resSunset.className = '';
    }
    else if (polarCondition === 'polar-night') {
        els.resSunrise.innerHTML = '<span class="polar-midnight">🌑 Polar Night</span>';
        els.resSunset.innerHTML = '<span class="polar-midnight">🌑 Polar Night</span>';
        els.resSunrise.className = '';
        els.resSunset.className = '';
    }
    else {
        els.resSunrise.textContent = sunriseStr;
        els.resSunset.textContent = sunsetStr;
        els.resSunrise.className = 'sun-time-value mono';
        els.resSunset.className = 'sun-time-value mono';
    }
    els.resNoon.textContent = noonStr || '—';
    els.resNoon.className = 'sun-time-value mono';
    if (dayLengthStr) {
        els.resDayLength.textContent = dayLengthStr;
        requestAnimationFrame(() => {
            els.dayBarFill.style.width = `${dayLengthPct.toFixed(1)}%`;
        });
    }
    else {
        els.resDayLength.textContent = polarCondition === 'midnight-sun' ? '24h 0m' : '0h 0m';
        els.dayBarFill.style.width = polarCondition === 'midnight-sun' ? '100%' : '0%';
    }
    els.resultsCard.removeAttribute('hidden');
    setTimeout(() => els.resultsCard.scrollIntoView({ behavior: 'smooth', block: 'start' }), 100);
}
function getTodayString() {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
}
function isValidDate(d) {
    return d instanceof Date && !isNaN(d.getTime());
}
function formatTime(date, tzId) {
    return new Intl.DateTimeFormat('en-GB', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
        timeZone: tzId,
    }).format(date);
}
function formatDate(date) {
    const months = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];
    const d = String(date.getUTCDate()).padStart(2, '0');
    const m = months[date.getUTCMonth()];
    const y = date.getUTCFullYear();
    return `${d}/${m}/${y}`;
}
function getTimezoneAbbreviation(ianaId, dateStr) {
    try {
        const [y, mo, d] = dateStr.split('-').map(Number);
        const date = new Date(Date.UTC(y, mo - 1, d, 12, 0, 0));
        const parts = new Intl.DateTimeFormat('en-US', {
            timeZone: ianaId,
            timeZoneName: 'short',
        }).formatToParts(date);
        const tzPart = parts.find(p => p.type === 'timeZoneName');
        return tzPart ? tzPart.value : '';
    }
    catch {
        return '';
    }
}
function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function showError(msg) {
    els.errorMsg.textContent = msg;
    els.errorMsg.removeAttribute('hidden');
    els.resultsCard.setAttribute('hidden', '');
}
function clearError() {
    els.errorMsg.setAttribute('hidden', '');
    els.errorMsg.textContent = '';
}
function showLoading(show) {
    els.loadingOverlay.hidden = !show;
}
document.addEventListener('DOMContentLoaded', init);
