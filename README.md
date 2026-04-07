# SunApp 🌅

SunApp is a beautifully designed, fast, and privacy-first web application that calculates precise sunrise, solar noon, and sunset times for any location on Earth. It intelligently handles timezone resolutions and properly addresses edge cases such as the Midnight Sun and Polar Night.

**Live site:** [sun.stormberry.as](http://sun.stormberry.as/)

## 🎯 Goals
- **Precision:** Deliver astronomically accurate sun times without heavy backend servers.
- **Privacy First:** No cookies, no tracking. Calculations are largely done client-side.
- **Aesthetic Excellence:** Provide a premium, glassmorphism-inspired dark mode UI with fluid micro-animations.
- **Global Support:** Support for over 2,000 major cities, plus exact GPS and device geolocation fallbacks.

## 🚀 Features
- **City Search:** Rapid, offline autocomplete search for major cities.
- **On-Device Geolocation:** Retrieve your current coordinates with one click.
- **Manual GPS Input:** For finding astronomical data for any arbitrary point on the globe.
- **Time-Travel:** Pick any date (past or future) from the calendar to calculate historical or upcoming solar events.
- **Polar Edge Cases:** Displays "Midnight Sun" 🌞 or "Polar Night" 🌑 when applicable based on latitude and season.
- **Responsive Layout:** Optimized for both mobile devices and large desktop monitors.

## 🛠️ Technology Stack
SunApp is built to be extremely lightweight, utilizing a minimal set of dependencies:

### Frontend
- **Languages:** Vanilla HTML5, CSS3, and JavaScript (ES6+). No heavy frameworks.
- **Styling:** Custom CSS implementing modern glassmorphism design, CSS variables, and fluid typography.
- **Fonts:** [Inter](https://rsms.me/inter/) (served locally for speed and privacy).
- **Core Library:** [SunCalc](https://github.com/mourner/suncalc) (bundled locally) for all mathematical solar position calculations.
- **Timezone Resolution:** Uses the browser's native `Intl` API alongside [Open-Meteo](https://open-meteo.com) (used strictly as an anonymous fallback to resolve raw GPS lat/lon to IANA timezones).

### Backend / Data Compilation (Python)
Instead of pinging a database every time a user types, SunApp pre-compiles a robust city list offline. 
- **`update_cities.py`**: A Python script that utilizes `geonamescache` to dynamically fetch population data.
- **Dataset:** It automatically captures the top 10 populated cities per country, all US state capitals, top 5 cities per US state, and all Brazilian capitals (correctly mapped to their State abbreviations).
- The resulting compact database is injected directly as a constant array into `app.js`.

## 📦 Running Locally

Since SunApp serves primarily static files, it is very easy to run locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/StormberryAS/SunApp.git
   cd SunApp
   ```
2. Start a local HTTP server to avoid CORS issues:
   ```bash
   python3 -m http.server 8000
   ```
3. Open your browser and navigate to `http://localhost:8000`.

### Updating the City Database

If you wish to update or modify the offline city database:
1. Ensure you have Python installed.
2. Create a virtual environment and install the required dependencies:
   ```bash
   python3 -m venv .venv
   source .venv/bin/activate
   pip install geonamescache
   ```
3. Run the generator script:
   ```bash
   python update_cities.py
   ```
*Note: The `.venv` folder is excluded via `.gitignore` to keep the repository lightweight.*

## ⚖️ License & Attribution
Designed and built by **[Stormberry A.S.](https://stormberry.as)**
- Powered by [SunCalc](https://github.com/mourner/suncalc).
- GPS fallback powered by [Open-Meteo](https://open-meteo.com).
- Icons and general components inspired by modern web standards.
