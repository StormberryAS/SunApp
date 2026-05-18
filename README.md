# SunApp

Privacy-first solar-position calculator. SunApp computes precise sunrise, solar noon and sunset times for any location on Earth, handles timezone resolution, and addresses edge cases such as the Midnight Sun and Polar Night.

**Live:** [sun.stormberry.as](https://sun.stormberry.as)

## Features
- **City search**: rapid, offline autocomplete for 2,000+ major cities.
- **On-device geolocation**: retrieve your current coordinates with one click.
- **Manual GPS input**: find astronomical data for any arbitrary point on the globe.
- **Time travel**: pick any date, past or future, to calculate historical or upcoming solar events.
- **Polar edge cases**: displays "Midnight Sun" or "Polar Night" when applicable based on latitude and season.
- **Responsive layout**: optimised for mobile and desktop.

## Architecture
- **Vanilla HTML/CSS/JS**, no frameworks, no build step.
- **Privacy first**, no cookies, no tracking. Only one anonymous external call (Open-Meteo) to resolve a raw GPS coordinate to its IANA timezone. City lookups use a bundled, pre-compiled database.
- Stormberry dark-mode glassmorphism design system, Inter typography.
- **Sovereign AI**, built and maintained using high-speed agentic workflows.

## Stack
- [SunCalc](https://github.com/mourner/suncalc) for solar position maths, bundled locally.
- Browser `Intl` API for timezone-aware time formatting.
- [Open-Meteo](https://open-meteo.com) as an anonymous timezone fallback for raw GPS coordinates only.
- [Inter](https://rsms.me/inter/) typeface, locally hosted.

## Local development
```bash
git clone https://github.com/StormberryAS/SunApp.git
cd SunApp
python3 -m http.server 8000
```
Open `http://localhost:8000` in your browser.

### Updating the city database
The city list is pre-compiled offline by `update_cities.py`, which uses `geonamescache` to fetch population data and selects the top 10 cities per country, all US state capitals, the top 5 cities per US state, and all Brazilian capitals (mapped to their state abbreviations). The compiled database is injected into `app.js` as a constant array.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install geonamescache
python update_cities.py
```
The `.venv` folder is excluded via `.gitignore` to keep the repository lightweight.

## Credits
Built by [Stormberry AS](https://stormberry.as). Proudly powered by sovereign AI agents.
