/**
 * SunApp — app.js
 * ================================================================
 * A fully client-side sun-times calculator.
 * Libraries used:
 *   • SunCalc  (loaded via CDN in index.html) — astronomical math
 *   • Intl API (built-in)                     — timezone-aware formatting
 *   • Open-Meteo API (fetch)                  — timezone lookup for raw GPS coords
 *
 * Key design decisions:
 *   1. All SunCalc calls return UTC Date objects — we then format
 *      them with Intl.DateTimeFormat using the TARGET timezone, so
 *      the times are always correct for the queried location, not
 *      the browser's local timezone.
 *   2. For cities we already have the IANA timezone ID embedded in
 *      the database. For raw GPS / device coords we call Open-Meteo
 *      to resolve the timezone.
 *   3. Polar Night / Midnight Sun: SunCalc returns NaN Dates when
 *      the sun doesn't cross the horizon — we detect this and show
 *      a user-friendly label instead of crashing.
 * ================================================================
 */

'use strict';

/* ================================================================
   SECTION 1 — CITY DATABASE
   Format: { name, country, lat, lon, tz }
   Coverage: capital + up to 3 largest cities per major country.
   Timezone IDs are IANA strings (used with Intl.DateTimeFormat).
================================================================ */
const CITIES = [
  { name: "Baghl\u0101n", country: "Afghanistan", lat: 36.1307, lon: 68.7083, tz: "Asia/Kabul" },
  { name: "Balkh", country: "Afghanistan", lat: 36.7563, lon: 66.8972, tz: "Asia/Kabul" },
  { name: "Gardez", country: "Afghanistan", lat: 33.5974, lon: 69.2259, tz: "Asia/Kabul" },
  { name: "Ghazni", country: "Afghanistan", lat: 33.5539, lon: 68.4210, tz: "Asia/Kabul" },
  { name: "Her\u0101t", country: "Afghanistan", lat: 34.3482, lon: 62.1997, tz: "Asia/Kabul" },
  { name: "Jal\u0101l\u0101b\u0101d", country: "Afghanistan", lat: 34.4265, lon: 70.4515, tz: "Asia/Kabul" },
  { name: "Kabul", country: "Afghanistan", lat: 34.5281, lon: 69.1723, tz: "Asia/Kabul" },
  { name: "Kandah\u0101r", country: "Afghanistan", lat: 31.6133, lon: 65.7101, tz: "Asia/Kabul" },
  { name: "Kunduz", country: "Afghanistan", lat: 36.7289, lon: 68.8570, tz: "Asia/Kabul" },
  { name: "Maz\u0101r-e Shar\u012bf", country: "Afghanistan", lat: 36.7090, lon: 67.1109, tz: "Asia/Kabul" },
  { name: "Mariehamn", country: "Aland Islands", lat: 60.0973, lon: 19.9348, tz: "Europe/Mariehamn" },
  { name: "Berat", country: "Albania", lat: 40.7058, lon: 19.9522, tz: "Europe/Tirane" },
  { name: "Durr\u00ebs", country: "Albania", lat: 41.3235, lon: 19.4547, tz: "Europe/Tirane" },
  { name: "Elbasan", country: "Albania", lat: 41.1125, lon: 20.0822, tz: "Europe/Tirane" },
  { name: "Fier", country: "Albania", lat: 40.7239, lon: 19.5561, tz: "Europe/Tirane" },
  { name: "Fier-\u00c7if\u00e7i", country: "Albania", lat: 40.7167, lon: 19.5667, tz: "Europe/Tirane" },
  { name: "Kor\u00e7\u00eb", country: "Albania", lat: 40.6186, lon: 20.7808, tz: "Europe/Tirane" },
  { name: "Lushnj\u00eb", country: "Albania", lat: 40.9419, lon: 19.7050, tz: "Europe/Tirane" },
  { name: "Shkod\u00ebr", country: "Albania", lat: 42.0683, lon: 19.5126, tz: "Europe/Tirane" },
  { name: "Tirana", country: "Albania", lat: 41.3274, lon: 19.8187, tz: "Europe/Tirane" },
  { name: "Vlor\u00eb", country: "Albania", lat: 40.4686, lon: 19.4832, tz: "Europe/Tirane" },
  { name: "Algiers", country: "Algeria", lat: 36.7323, lon: 3.0875, tz: "Africa/Algiers" },
  { name: "Annaba", country: "Algeria", lat: 36.9000, lon: 7.7667, tz: "Africa/Algiers" },
  { name: "Bab Ezzouar", country: "Algeria", lat: 36.7261, lon: 3.1829, tz: "Africa/Algiers" },
  { name: "Batna City", country: "Algeria", lat: 35.5560, lon: 6.1741, tz: "Africa/Algiers" },
  { name: "Blida", country: "Algeria", lat: 36.4700, lon: 2.8277, tz: "Africa/Algiers" },
  { name: "Constantine", country: "Algeria", lat: 36.3650, lon: 6.6147, tz: "Africa/Algiers" },
  { name: "Djelfa", country: "Algeria", lat: 34.6728, lon: 3.2630, tz: "Africa/Algiers" },
  { name: "Oran", country: "Algeria", lat: 35.6991, lon: -0.6359, tz: "Africa/Algiers" },
  { name: "Sidi Bel Abbes", country: "Algeria", lat: 35.1899, lon: -0.6309, tz: "Africa/Algiers" },
  { name: "S\u00e9tif", country: "Algeria", lat: 36.1911, lon: 5.4137, tz: "Africa/Algiers" },
  { name: "Pago Pago", country: "American Samoa", lat: -14.2781, lon: -170.7025, tz: "Pacific/Pago_Pago" },
  { name: "Andorra la Vella", country: "Andorra", lat: 42.5078, lon: 1.5211, tz: "Europe/Andorra" },
  { name: "les Escaldes", country: "Andorra", lat: 42.5073, lon: 1.5341, tz: "Europe/Andorra" },
  { name: "Benguela", country: "Angola", lat: -12.5767, lon: 13.4027, tz: "Africa/Luanda" },
  { name: "Cabinda", country: "Angola", lat: -5.5620, lon: 12.1948, tz: "Africa/Luanda" },
  { name: "Cu\u00edto", country: "Angola", lat: -12.3833, lon: 16.9333, tz: "Africa/Luanda" },
  { name: "Huambo", country: "Angola", lat: -12.7761, lon: 15.7392, tz: "Africa/Luanda" },
  { name: "Lobito", country: "Angola", lat: -12.3644, lon: 13.5360, tz: "Africa/Luanda" },
  { name: "Luanda", country: "Angola", lat: -8.8368, lon: 13.2343, tz: "Africa/Luanda" },
  { name: "Lubango", country: "Angola", lat: -14.9172, lon: 13.4925, tz: "Africa/Luanda" },
  { name: "Malanje", country: "Angola", lat: -9.5402, lon: 16.3410, tz: "Africa/Luanda" },
  { name: "Saurimo", country: "Angola", lat: -9.6608, lon: 20.3915, tz: "Africa/Luanda" },
  { name: "U\u00edge", country: "Angola", lat: -7.6087, lon: 15.0613, tz: "Africa/Luanda" },
  { name: "The Valley", country: "Anguilla", lat: 18.2170, lon: -63.0578, tz: "America/Anguilla" },
  { name: "Saint John\u2019s", country: "Antigua and Barbuda", lat: 17.1210, lon: -61.8433, tz: "America/Antigua" },
  { name: "Bah\u00eda Blanca", country: "Argentina", lat: -38.7176, lon: -62.2655, tz: "America/Argentina/Buenos_Aires" },
  { name: "Buenos Aires", country: "Argentina", lat: -34.6131, lon: -58.3772, tz: "America/Argentina/Buenos_Aires" },
  { name: "Corrientes", country: "Argentina", lat: -27.4678, lon: -58.8344, tz: "America/Argentina/Cordoba" },
  { name: "C\u00f3rdoba", country: "Argentina", lat: -31.4065, lon: -64.1885, tz: "America/Argentina/Cordoba" },
  { name: "Mar del Plata", country: "Argentina", lat: -38.0004, lon: -57.5562, tz: "America/Argentina/Buenos_Aires" },
  { name: "Posadas", country: "Argentina", lat: -27.3918, lon: -55.9238, tz: "America/Argentina/Cordoba" },
  { name: "Rosario", country: "Argentina", lat: -32.9468, lon: -60.6393, tz: "America/Argentina/Cordoba" },
  { name: "Salta", country: "Argentina", lat: -24.8065, lon: -65.4200, tz: "America/Argentina/Salta" },
  { name: "San Miguel de Tucum\u00e1n", country: "Argentina", lat: -26.8160, lon: -65.2105, tz: "America/Argentina/Tucuman" },
  { name: "Santa Fe", country: "Argentina", lat: -31.6488, lon: -60.7087, tz: "America/Argentina/Cordoba" },
  { name: "Ajapnyak", country: "Armenia", lat: 40.1992, lon: 44.4706, tz: "Asia/Yerevan" },
  { name: "Arabkir", country: "Armenia", lat: 40.2062, lon: 44.5037, tz: "Asia/Yerevan" },
  { name: "Erebuni", country: "Armenia", lat: 40.1331, lon: 44.5301, tz: "Asia/Yerevan" },
  { name: "Gyumri", country: "Armenia", lat: 40.7931, lon: 43.8464, tz: "Asia/Yerevan" },
  { name: "Malatia-Sebastia", country: "Armenia", lat: 40.1740, lon: 44.4457, tz: "Asia/Yerevan" },
  { name: "Nor Nork", country: "Armenia", lat: 40.1966, lon: 44.5669, tz: "Asia/Yerevan" },
  { name: "Shengavit", country: "Armenia", lat: 40.1557, lon: 44.4779, tz: "Asia/Yerevan" },
  { name: "Vanadzor", country: "Armenia", lat: 40.8074, lon: 44.4970, tz: "Asia/Yerevan" },
  { name: "Yerevan", country: "Armenia", lat: 40.1776, lon: 44.5126, tz: "Asia/Yerevan" },
  { name: "Noord", country: "Aruba", lat: 12.5660, lon: -70.0320, tz: "America/Aruba" },
  { name: "Oranjestad", country: "Aruba", lat: 12.5240, lon: -70.0270, tz: "America/Aruba" },
  { name: "San Nicolas", country: "Aruba", lat: 12.4362, lon: -69.9071, tz: "America/Aruba" },
  { name: "Tanki Leendert", country: "Aruba", lat: 12.5391, lon: -70.0200, tz: "America/Aruba" },
  { name: "Adelaide", country: "Australia", lat: -34.9287, lon: 138.5986, tz: "Australia/Adelaide" },
  { name: "Brisbane", country: "Australia", lat: -27.4679, lon: 153.0281, tz: "Australia/Brisbane" },
  { name: "Canberra", country: "Australia", lat: -35.2835, lon: 149.1281, tz: "Australia/Sydney" },
  { name: "Gold Coast", country: "Australia", lat: -28.0003, lon: 153.4309, tz: "Australia/Brisbane" },
  { name: "Logan City", country: "Australia", lat: -27.6392, lon: 153.1094, tz: "Australia/Brisbane" },
  { name: "Melbourne", country: "Australia", lat: -37.8140, lon: 144.9633, tz: "Australia/Melbourne" },
  { name: "Newcastle", country: "Australia", lat: -32.9295, lon: 151.7801, tz: "Australia/Sydney" },
  { name: "Perth", country: "Australia", lat: -31.9522, lon: 115.8614, tz: "Australia/Perth" },
  { name: "Sunshine Coast", country: "Australia", lat: -26.6568, lon: 153.0796, tz: "Australia/Brisbane" },
  { name: "Sydney", country: "Australia", lat: -33.8678, lon: 151.2073, tz: "Australia/Sydney" },
  { name: "Donaustadt", country: "Austria", lat: 48.2333, lon: 16.4600, tz: "Europe/Vienna" },
  { name: "Favoriten", country: "Austria", lat: 48.1612, lon: 16.3823, tz: "Europe/Vienna" },
  { name: "Floridsdorf", country: "Austria", lat: 48.2500, lon: 16.4000, tz: "Europe/Vienna" },
  { name: "Graz", country: "Austria", lat: 47.0667, lon: 15.4500, tz: "Europe/Vienna" },
  { name: "Innsbruck", country: "Austria", lat: 47.2627, lon: 11.3945, tz: "Europe/Vienna" },
  { name: "Linz", country: "Austria", lat: 48.3064, lon: 14.2861, tz: "Europe/Vienna" },
  { name: "Ottakring", country: "Austria", lat: 48.2167, lon: 16.3000, tz: "Europe/Vienna" },
  { name: "Salzburg", country: "Austria", lat: 47.7994, lon: 13.0440, tz: "Europe/Vienna" },
  { name: "Simmering", country: "Austria", lat: 48.1833, lon: 16.4333, tz: "Europe/Vienna" },
  { name: "Vienna", country: "Austria", lat: 48.2085, lon: 16.3721, tz: "Europe/Vienna" },
  { name: "Baku", country: "Azerbaijan", lat: 40.3777, lon: 49.8920, tz: "Asia/Baku" },
  { name: "Ganja", country: "Azerbaijan", lat: 40.6828, lon: 46.3606, tz: "Asia/Baku" },
  { name: "Lankaran", country: "Azerbaijan", lat: 38.7543, lon: 48.8506, tz: "Asia/Baku" },
  { name: "Lerik", country: "Azerbaijan", lat: 38.7739, lon: 48.4150, tz: "Asia/Baku" },
  { name: "Mingelchaur", country: "Azerbaijan", lat: 40.7640, lon: 47.0595, tz: "Asia/Baku" },
  { name: "Nax\u00e7\u0131van", country: "Azerbaijan", lat: 39.2089, lon: 45.4122, tz: "Asia/Baku" },
  { name: "Saatl\u0131", country: "Azerbaijan", lat: 39.9321, lon: 48.3689, tz: "Asia/Baku" },
  { name: "Sumqay\u0131t", country: "Azerbaijan", lat: 40.5897, lon: 49.6686, tz: "Asia/Baku" },
  { name: "Tovuz", country: "Azerbaijan", lat: 40.9925, lon: 45.6284, tz: "Asia/Baku" },
  { name: "Yevlakh", country: "Azerbaijan", lat: 40.6183, lon: 47.1501, tz: "Asia/Baku" },
  { name: "Freeport", country: "Bahamas", lat: 26.5333, lon: -78.7000, tz: "America/Nassau" },
  { name: "Killarney", country: "Bahamas", lat: 25.0500, lon: -77.4167, tz: "America/Nassau" },
  { name: "Lucaya", country: "Bahamas", lat: 26.5333, lon: -78.6667, tz: "America/Nassau" },
  { name: "Nassau", country: "Bahamas", lat: 25.0582, lon: -77.3431, tz: "America/Nassau" },
  { name: "Al Muharraq", country: "Bahrain", lat: 26.2572, lon: 50.6119, tz: "Asia/Bahrain" },
  { name: "Ar Rif\u0101\u2018", country: "Bahrain", lat: 26.1300, lon: 50.5550, tz: "Asia/Bahrain" },
  { name: "D\u0101r Kulayb", country: "Bahrain", lat: 26.0686, lon: 50.5039, tz: "Asia/Bahrain" },
  { name: "Jidd \u1e28af\u015f", country: "Bahrain", lat: 26.2186, lon: 50.5478, tz: "Asia/Bahrain" },
  { name: "Mad\u012bnat \u1e28amad", country: "Bahrain", lat: 26.1153, lon: 50.5069, tz: "Asia/Bahrain" },
  { name: "Mad\u012bnat \u2018\u012as\u00e1", country: "Bahrain", lat: 26.1736, lon: 50.5478, tz: "Asia/Bahrain" },
  { name: "Manama", country: "Bahrain", lat: 26.2279, lon: 50.5857, tz: "Asia/Bahrain" },
  { name: "Sitrah", country: "Bahrain", lat: 26.1547, lon: 50.6206, tz: "Asia/Bahrain" },
  { name: "Chattogram", country: "Bangladesh", lat: 22.3384, lon: 91.8317, tz: "Asia/Dhaka" },
  { name: "Comilla", country: "Bangladesh", lat: 23.4619, lon: 91.1850, tz: "Asia/Dhaka" },
  { name: "Dhaka", country: "Bangladesh", lat: 23.7104, lon: 90.4074, tz: "Asia/Dhaka" },
  { name: "Gazipur", country: "Bangladesh", lat: 23.9984, lon: 90.4223, tz: "Asia/Dhaka" },
  { name: "Khulna", country: "Bangladesh", lat: 22.8098, lon: 89.5644, tz: "Asia/Dhaka" },
  { name: "Mirpur Model Thana", country: "Bangladesh", lat: 23.8093, lon: 90.3609, tz: "Asia/Dhaka" },
  { name: "Moh\u0101mmadpur", country: "Bangladesh", lat: 24.8984, lon: 88.5284, tz: "Asia/Dhaka" },
  { name: "Pallabi", country: "Bangladesh", lat: 23.8250, lon: 90.3700, tz: "Asia/Dhaka" },
  { name: "Rajshahi", country: "Bangladesh", lat: 24.3740, lon: 88.6011, tz: "Asia/Dhaka" },
  { name: "Rangpur", country: "Bangladesh", lat: 25.7466, lon: 89.2517, tz: "Asia/Dhaka" },
  { name: "Bridgetown", country: "Barbados", lat: 13.1073, lon: -59.6202, tz: "America/Barbados" },
  { name: "Baranovichi", country: "Belarus", lat: 53.1325, lon: 26.0077, tz: "Europe/Minsk" },
  { name: "Barysaw", country: "Belarus", lat: 54.2279, lon: 28.5050, tz: "Europe/Minsk" },
  { name: "Bobruysk", country: "Belarus", lat: 53.1464, lon: 29.2055, tz: "Europe/Minsk" },
  { name: "Brest", country: "Belarus", lat: 52.1089, lon: 23.7175, tz: "Europe/Minsk" },
  { name: "Homyel'", country: "Belarus", lat: 52.4345, lon: 30.9754, tz: "Europe/Minsk" },
  { name: "Hrodna", country: "Belarus", lat: 53.6758, lon: 23.8289, tz: "Europe/Minsk" },
  { name: "Mahilyow", country: "Belarus", lat: 53.9088, lon: 30.3404, tz: "Europe/Minsk" },
  { name: "Minsk", country: "Belarus", lat: 53.9002, lon: 27.5665, tz: "Europe/Minsk" },
  { name: "Pinsk", country: "Belarus", lat: 52.1215, lon: 26.0673, tz: "Europe/Minsk" },
  { name: "Vitebsk", country: "Belarus", lat: 55.1904, lon: 30.2049, tz: "Europe/Minsk" },
  { name: "Anderlecht", country: "Belgium", lat: 50.8362, lon: 4.3145, tz: "Europe/Brussels" },
  { name: "Antwerpen", country: "Belgium", lat: 51.2205, lon: 4.4003, tz: "Europe/Brussels" },
  { name: "Brugge", country: "Belgium", lat: 51.2089, lon: 3.2242, tz: "Europe/Brussels" },
  { name: "Brussels", country: "Belgium", lat: 50.8505, lon: 4.3488, tz: "Europe/Brussels" },
  { name: "Charleroi", country: "Belgium", lat: 50.4114, lon: 4.4445, tz: "Europe/Brussels" },
  { name: "Gent", country: "Belgium", lat: 51.0500, lon: 3.7167, tz: "Europe/Brussels" },
  { name: "Leuven", country: "Belgium", lat: 50.8796, lon: 4.7009, tz: "Europe/Brussels" },
  { name: "Li\u00e8ge", country: "Belgium", lat: 50.6337, lon: 5.5675, tz: "Europe/Brussels" },
  { name: "Namur", country: "Belgium", lat: 50.4669, lon: 4.8675, tz: "Europe/Brussels" },
  { name: "Schaerbeek", country: "Belgium", lat: 50.8693, lon: 4.3774, tz: "Europe/Brussels" },
  { name: "Belize City", country: "Belize", lat: 17.4995, lon: -88.1976, tz: "America/Belize" },
  { name: "Belmopan", country: "Belize", lat: 17.2538, lon: -88.7640, tz: "America/Belize" },
  { name: "Orange Walk", country: "Belize", lat: 18.0812, lon: -88.5633, tz: "America/Belize" },
  { name: "San Pedro", country: "Belize", lat: 17.9160, lon: -87.9659, tz: "America/Belize" },
  { name: "Abomey", country: "Benin", lat: 7.1829, lon: 1.9912, tz: "Africa/Porto-Novo" },
  { name: "Abomey-Calavi", country: "Benin", lat: 6.4485, lon: 2.3557, tz: "Africa/Porto-Novo" },
  { name: "Bohicon", country: "Benin", lat: 7.1783, lon: 2.0667, tz: "Africa/Porto-Novo" },
  { name: "Cotonou", country: "Benin", lat: 6.3654, lon: 2.4183, tz: "Africa/Porto-Novo" },
  { name: "Djougou", country: "Benin", lat: 9.7085, lon: 1.6660, tz: "Africa/Porto-Novo" },
  { name: "Ekp\u00e9", country: "Benin", lat: 6.3921, lon: 2.5372, tz: "Africa/Porto-Novo" },
  { name: "Godom\u00e8", country: "Benin", lat: 6.3895, lon: 2.3458, tz: "Africa/Porto-Novo" },
  { name: "Nikki", country: "Benin", lat: 9.9401, lon: 3.2107, tz: "Africa/Porto-Novo" },
  { name: "Parakou", country: "Benin", lat: 9.3372, lon: 2.6303, tz: "Africa/Porto-Novo" },
  { name: "Porto-Novo", country: "Benin", lat: 6.4965, lon: 2.6036, tz: "Africa/Porto-Novo" },
  { name: "Hamilton", country: "Bermuda", lat: 32.2949, lon: -64.7830, tz: "Atlantic/Bermuda" },
  { name: "Phuntsholing", country: "Bhutan", lat: 26.8516, lon: 89.3884, tz: "Asia/Thimphu" },
  { name: "Pun\u0101kha", country: "Bhutan", lat: 27.5914, lon: 89.8774, tz: "Asia/Thimphu" },
  { name: "Thimphu", country: "Bhutan", lat: 27.4661, lon: 89.6419, tz: "Asia/Thimphu" },
  { name: "Tsirang", country: "Bhutan", lat: 27.0219, lon: 90.1229, tz: "Asia/Thimphu" },
  { name: "Cochabamba", country: "Bolivia", lat: -17.3819, lon: -66.1599, tz: "America/La_Paz" },
  { name: "La Paz", country: "Bolivia", lat: -16.5000, lon: -68.1500, tz: "America/La_Paz" },
  { name: "Oruro", country: "Bolivia", lat: -17.9715, lon: -67.0932, tz: "America/La_Paz" },
  { name: "Potos\u00ed", country: "Bolivia", lat: -19.5836, lon: -65.7531, tz: "America/La_Paz" },
  { name: "Quillacollo", country: "Bolivia", lat: -17.3923, lon: -66.2784, tz: "America/La_Paz" },
  { name: "Riberalta", country: "Bolivia", lat: -11.0103, lon: -66.0526, tz: "America/La_Paz" },
  { name: "Sacaba", country: "Bolivia", lat: -17.3980, lon: -66.0383, tz: "America/La_Paz" },
  { name: "Santa Cruz de la Sierra", country: "Bolivia", lat: -17.7863, lon: -63.1812, tz: "America/La_Paz" },
  { name: "Sucre", country: "Bolivia", lat: -19.0333, lon: -65.2627, tz: "America/La_Paz" },
  { name: "Tarija", country: "Bolivia", lat: -21.5355, lon: -64.7296, tz: "America/La_Paz" },
  { name: "Kralendijk", country: "Bonaire, Saint Eustatius and Saba ", lat: 12.1500, lon: -68.2667, tz: "America/Kralendijk" },
  { name: "Banja Luka", country: "Bosnia and Herzegovina", lat: 44.7784, lon: 17.1939, tz: "Europe/Sarajevo" },
  { name: "Biha\u0107", country: "Bosnia and Herzegovina", lat: 44.8169, lon: 15.8708, tz: "Europe/Sarajevo" },
  { name: "Br\u010dko", country: "Bosnia and Herzegovina", lat: 44.8700, lon: 18.8101, tz: "Europe/Sarajevo" },
  { name: "Bugojno", country: "Bosnia and Herzegovina", lat: 44.0572, lon: 17.4508, tz: "Europe/Sarajevo" },
  { name: "Ilid\u017ea", country: "Bosnia and Herzegovina", lat: 43.8315, lon: 18.3070, tz: "Europe/Sarajevo" },
  { name: "Lukavac", country: "Bosnia and Herzegovina", lat: 44.5425, lon: 18.5262, tz: "Europe/Sarajevo" },
  { name: "Mostar", country: "Bosnia and Herzegovina", lat: 43.3433, lon: 17.8081, tz: "Europe/Sarajevo" },
  { name: "Sarajevo", country: "Bosnia and Herzegovina", lat: 43.8486, lon: 18.3564, tz: "Europe/Sarajevo" },
  { name: "Tuzla", country: "Bosnia and Herzegovina", lat: 44.5384, lon: 18.6671, tz: "Europe/Sarajevo" },
  { name: "Zenica", country: "Bosnia and Herzegovina", lat: 44.2017, lon: 17.9040, tz: "Europe/Sarajevo" },
  { name: "Francistown", country: "Botswana", lat: -21.1700, lon: 27.5078, tz: "Africa/Gaborone" },
  { name: "Gaborone", country: "Botswana", lat: -24.6545, lon: 25.9086, tz: "Africa/Gaborone" },
  { name: "Mahalapye", country: "Botswana", lat: -23.1041, lon: 26.8142, tz: "Africa/Gaborone" },
  { name: "Maun", country: "Botswana", lat: -19.9833, lon: 23.4167, tz: "Africa/Gaborone" },
  { name: "Mochudi", country: "Botswana", lat: -24.4167, lon: 26.1500, tz: "Africa/Gaborone" },
  { name: "Mogoditshane", country: "Botswana", lat: -24.6269, lon: 25.8656, tz: "Africa/Gaborone" },
  { name: "Molepolole", country: "Botswana", lat: -24.4066, lon: 25.4951, tz: "Africa/Gaborone" },
  { name: "Palapye", country: "Botswana", lat: -22.5461, lon: 27.1251, tz: "Africa/Gaborone" },
  { name: "Serowe", country: "Botswana", lat: -22.3875, lon: 26.7108, tz: "Africa/Gaborone" },
  { name: "Tlokweng", country: "Botswana", lat: -24.6686, lon: 25.9711, tz: "Africa/Gaborone" },
  { name: "Aracaju, SE", country: "Brazil", lat: -10.9111, lon: -37.0717, tz: "America/Maceio" },
  { name: "Belo Horizonte, MG", country: "Brazil", lat: -19.9208, lon: -43.9378, tz: "America/Sao_Paulo" },
  { name: "Bel\u00e9m, PA", country: "Brazil", lat: -1.4558, lon: -48.5044, tz: "America/Belem" },
  { name: "Bel\u00e9m, PB", country: "Brazil", lat: -6.6917, lon: -35.5333, tz: "America/Fortaleza" },
  { name: "Boa Vista, RR", country: "Brazil", lat: 2.8197, lon: -60.6733, tz: "America/Boa_Vista" },
  { name: "Bras\u00edlia, DF", country: "Brazil", lat: -15.7797, lon: -47.9297, tz: "America/Sao_Paulo" },
  { name: "Campo Grande", country: "Brazil", lat: -20.4428, lon: -54.6464, tz: "America/Campo_Grande" },
  { name: "Campo Grande, SP", country: "Brazil", lat: -23.6746, lon: -46.6875, tz: "America/Sao_Paulo" },
  { name: "Cuiab\u00e1, MT", country: "Brazil", lat: -15.5961, lon: -56.0967, tz: "America/Cuiaba" },
  { name: "Curitiba, PR", country: "Brazil", lat: -25.4278, lon: -49.2731, tz: "America/Sao_Paulo" },
  { name: "Florian\u00f3polis, SC", country: "Brazil", lat: -27.5967, lon: -48.5492, tz: "America/Sao_Paulo" },
  { name: "Fortaleza, CE", country: "Brazil", lat: -3.7172, lon: -38.5431, tz: "America/Fortaleza" },
  { name: "Goi\u00e2nia, GO", country: "Brazil", lat: -16.6786, lon: -49.2539, tz: "America/Sao_Paulo" },
  { name: "Imperatriz, MA", country: "Brazil", lat: -5.5262, lon: -47.4682, tz: "America/Fortaleza" },
  { name: "Jo\u00e3o Pessoa, PB", country: "Brazil", lat: -7.1150, lon: -34.8631, tz: "America/Fortaleza" },
  { name: "Macap\u00e1, AP", country: "Brazil", lat: 0.0389, lon: -51.0664, tz: "America/Belem" },
  { name: "Maca\u00e9, RJ", country: "Brazil", lat: -22.3708, lon: -41.7869, tz: "America/Sao_Paulo" },
  { name: "Macei\u00f3, AL", country: "Brazil", lat: -9.6658, lon: -35.7353, tz: "America/Maceio" },
  { name: "Manaus, AM", country: "Brazil", lat: -3.1019, lon: -60.0250, tz: "America/Manaus" },
  { name: "Natal, RN", country: "Brazil", lat: -5.7950, lon: -35.2094, tz: "America/Fortaleza" },
  { name: "Niter\u00f3i, RJ", country: "Brazil", lat: -22.8833, lon: -43.1036, tz: "America/Sao_Paulo" },
  { name: "Nova Friburgo, RJ", country: "Brazil", lat: -22.2858, lon: -42.5332, tz: "America/Sao_Paulo" },
  { name: "Palmas, PR", country: "Brazil", lat: -26.4842, lon: -51.9906, tz: "America/Sao_Paulo" },
  { name: "Palmas, TO", country: "Brazil", lat: -10.1675, lon: -48.3277, tz: "America/Araguaina" },
  { name: "Petr\u00f3polis, RJ", country: "Brazil", lat: -22.5050, lon: -43.1786, tz: "America/Sao_Paulo" },
  { name: "Porto Alegre, RS", country: "Brazil", lat: -30.0328, lon: -51.2302, tz: "America/Sao_Paulo" },
  { name: "Porto Velho, RO", country: "Brazil", lat: -8.7619, lon: -63.9039, tz: "America/Porto_Velho" },
  { name: "Recife, PE", country: "Brazil", lat: -8.0539, lon: -34.8811, tz: "America/Recife" },
  { name: "Rio Branco, AC", country: "Brazil", lat: -9.9747, lon: -67.8100, tz: "America/Rio_Branco" },
  { name: "Rio de Janeiro, RJ", country: "Brazil", lat: -22.9064, lon: -43.1822, tz: "America/Sao_Paulo" },
  { name: "Salvador, BA", country: "Brazil", lat: -12.9756, lon: -38.4910, tz: "America/Bahia" },
  { name: "S\u00e3o Lu\u00eds, MA", country: "Brazil", lat: -2.5297, lon: -44.3028, tz: "America/Fortaleza" },
  { name: "S\u00e3o Paulo, SP", country: "Brazil", lat: -23.5475, lon: -46.6361, tz: "America/Sao_Paulo" },
  { name: "Teresina, PI", country: "Brazil", lat: -5.0892, lon: -42.8019, tz: "America/Fortaleza" },
  { name: "Teres\u00f3polis, RJ", country: "Brazil", lat: -22.4121, lon: -42.9667, tz: "America/Sao_Paulo" },
  { name: "Vit\u00f3ria, ES", country: "Brazil", lat: -20.3194, lon: -40.3378, tz: "America/Sao_Paulo" },
  { name: "Road Town", country: "British Virgin Islands", lat: 18.4269, lon: -64.6208, tz: "America/Tortola" },
  { name: "Bandar Seri Begawan", country: "Brunei", lat: 4.8903, lon: 114.9401, tz: "Asia/Brunei" },
  { name: "Kuala Belait", country: "Brunei", lat: 4.5836, lon: 114.2312, tz: "Asia/Brunei" },
  { name: "Liang", country: "Brunei", lat: 4.6775, lon: 114.4959, tz: "Asia/Brunei" },
  { name: "Mentiri", country: "Brunei", lat: 4.9706, lon: 115.0208, tz: "Asia/Brunei" },
  { name: "Sengkurong", country: "Brunei", lat: 4.8833, lon: 114.8333, tz: "Asia/Brunei" },
  { name: "Serasa", country: "Brunei", lat: 5.0176, lon: 115.0580, tz: "Asia/Brunei" },
  { name: "Seria", country: "Brunei", lat: 4.6064, lon: 114.3248, tz: "Asia/Brunei" },
  { name: "Tutong", country: "Brunei", lat: 4.8028, lon: 114.6492, tz: "Asia/Brunei" },
  { name: "Burgas", country: "Bulgaria", lat: 42.5061, lon: 27.4678, tz: "Europe/Sofia" },
  { name: "Dobrich", country: "Bulgaria", lat: 43.5667, lon: 27.8333, tz: "Europe/Sofia" },
  { name: "Pernik", country: "Bulgaria", lat: 42.6000, lon: 23.0333, tz: "Europe/Sofia" },
  { name: "Pleven", country: "Bulgaria", lat: 43.4167, lon: 24.6167, tz: "Europe/Sofia" },
  { name: "Plovdiv", country: "Bulgaria", lat: 42.1500, lon: 24.7500, tz: "Europe/Sofia" },
  { name: "Ruse", country: "Bulgaria", lat: 43.8487, lon: 25.9534, tz: "Europe/Sofia" },
  { name: "Sliven", country: "Bulgaria", lat: 42.6858, lon: 26.3292, tz: "Europe/Sofia" },
  { name: "Sofia", country: "Bulgaria", lat: 42.6975, lon: 23.3241, tz: "Europe/Sofia" },
  { name: "Stara Zagora", country: "Bulgaria", lat: 42.4328, lon: 25.6419, tz: "Europe/Sofia" },
  { name: "Varna", country: "Bulgaria", lat: 43.2167, lon: 27.9167, tz: "Europe/Sofia" },
  { name: "Banfora", country: "Burkina Faso", lat: 10.6333, lon: -4.7667, tz: "Africa/Ouagadougou" },
  { name: "Bobo-Dioulasso", country: "Burkina Faso", lat: 11.1771, lon: -4.2979, tz: "Africa/Ouagadougou" },
  { name: "Fada N'gourma", country: "Burkina Faso", lat: 12.0616, lon: 0.3584, tz: "Africa/Ouagadougou" },
  { name: "Hound\u00e9", country: "Burkina Faso", lat: 11.5000, lon: -3.5167, tz: "Africa/Ouagadougou" },
  { name: "Kaya", country: "Burkina Faso", lat: 13.0917, lon: -1.0844, tz: "Africa/Ouagadougou" },
  { name: "Koudougou", country: "Burkina Faso", lat: 12.2526, lon: -2.3627, tz: "Africa/Ouagadougou" },
  { name: "Ouagadougou", country: "Burkina Faso", lat: 12.3657, lon: -1.5339, tz: "Africa/Ouagadougou" },
  { name: "Ouahigouya", country: "Burkina Faso", lat: 13.5828, lon: -2.4216, tz: "Africa/Ouagadougou" },
  { name: "Pouytenga", country: "Burkina Faso", lat: 12.2516, lon: -0.4256, tz: "Africa/Ouagadougou" },
  { name: "Saaba", country: "Burkina Faso", lat: 12.3714, lon: -1.4147, tz: "Africa/Ouagadougou" },
  { name: "Bubanza", country: "Burundi", lat: -3.0804, lon: 29.3910, tz: "Africa/Bujumbura" },
  { name: "Bujumbura", country: "Burundi", lat: -3.3819, lon: 29.3614, tz: "Africa/Bujumbura" },
  { name: "Cibitoke", country: "Burundi", lat: -2.8869, lon: 29.1248, tz: "Africa/Bujumbura" },
  { name: "Gatumba", country: "Burundi", lat: -3.3333, lon: 29.2500, tz: "Africa/Bujumbura" },
  { name: "Gitega", country: "Burundi", lat: -3.4271, lon: 29.9246, tz: "Africa/Bujumbura" },
  { name: "Karuzi", country: "Burundi", lat: -3.1014, lon: 30.1628, tz: "Africa/Bujumbura" },
  { name: "Kayanza", country: "Burundi", lat: -2.9221, lon: 29.6293, tz: "Africa/Bujumbura" },
  { name: "Kirundo", country: "Burundi", lat: -2.5845, lon: 30.0959, tz: "Africa/Bujumbura" },
  { name: "Ngozi", country: "Burundi", lat: -2.9075, lon: 29.8306, tz: "Africa/Bujumbura" },
  { name: "Rumonge", country: "Burundi", lat: -3.9736, lon: 29.4386, tz: "Africa/Bujumbura" },
  { name: "Assomada", country: "Cabo Verde", lat: 15.1000, lon: -23.6833, tz: "Atlantic/Cape_Verde" },
  { name: "Espargos", country: "Cabo Verde", lat: 16.7552, lon: -22.9446, tz: "Atlantic/Cape_Verde" },
  { name: "Mindelo", country: "Cabo Verde", lat: 16.8901, lon: -24.9804, tz: "Atlantic/Cape_Verde" },
  { name: "Praia", country: "Cabo Verde", lat: 14.9315, lon: -23.5125, tz: "Atlantic/Cape_Verde" },
  { name: "Battambang", country: "Cambodia", lat: 13.1027, lon: 103.1982, tz: "Asia/Phnom_Penh" },
  { name: "Kampong Cham", country: "Cambodia", lat: 11.9934, lon: 105.4635, tz: "Asia/Phnom_Penh" },
  { name: "Kampong Chhnang", country: "Cambodia", lat: 12.2500, lon: 104.6667, tz: "Asia/Phnom_Penh" },
  { name: "Paoy Paet", country: "Cambodia", lat: 13.6580, lon: 102.5636, tz: "Asia/Phnom_Penh" },
  { name: "Phnom Penh", country: "Cambodia", lat: 11.5625, lon: 104.9160, tz: "Asia/Phnom_Penh" },
  { name: "Pursat", country: "Cambodia", lat: 12.5388, lon: 103.9192, tz: "Asia/Phnom_Penh" },
  { name: "Siem Reap", country: "Cambodia", lat: 13.3618, lon: 103.8606, tz: "Asia/Phnom_Penh" },
  { name: "Sihanoukville", country: "Cambodia", lat: 10.6093, lon: 103.5296, tz: "Asia/Phnom_Penh" },
  { name: "Ta Khmau", country: "Cambodia", lat: 11.4833, lon: 104.9500, tz: "Asia/Phnom_Penh" },
  { name: "Takeo", country: "Cambodia", lat: 10.9908, lon: 104.7850, tz: "Asia/Phnom_Penh" },
  { name: "Bafoussam", country: "Cameroon", lat: 5.4778, lon: 10.4176, tz: "Africa/Douala" },
  { name: "Bamenda", country: "Cameroon", lat: 5.9597, lon: 10.1460, tz: "Africa/Douala" },
  { name: "Buea", country: "Cameroon", lat: 4.1534, lon: 9.2423, tz: "Africa/Douala" },
  { name: "Douala", country: "Cameroon", lat: 4.0483, lon: 9.7043, tz: "Africa/Douala" },
  { name: "Kouss\u00e9ri", country: "Cameroon", lat: 12.0769, lon: 15.0306, tz: "Africa/Douala" },
  { name: "Kumba", country: "Cameroon", lat: 4.6363, lon: 9.4469, tz: "Africa/Douala" },
  { name: "Maroua", country: "Cameroon", lat: 10.5909, lon: 14.3159, tz: "Africa/Douala" },
  { name: "Ngaound\u00e9r\u00e9", country: "Cameroon", lat: 7.3277, lon: 13.5847, tz: "Africa/Douala" },
  { name: "Nkongsamba", country: "Cameroon", lat: 4.9547, lon: 9.9404, tz: "Africa/Douala" },
  { name: "Yaound\u00e9", country: "Cameroon", lat: 3.8667, lon: 11.5167, tz: "Africa/Douala" },
  { name: "Brampton", country: "Canada", lat: 43.6834, lon: -79.7663, tz: "America/Toronto" },
  { name: "Calgary", country: "Canada", lat: 51.0501, lon: -114.0853, tz: "America/Edmonton" },
  { name: "Edmonton", country: "Canada", lat: 53.5501, lon: -113.4687, tz: "America/Edmonton" },
  { name: "Hamilton", country: "Canada", lat: 43.2501, lon: -79.8496, tz: "America/Toronto" },
  { name: "Mississauga", country: "Canada", lat: 43.5789, lon: -79.6583, tz: "America/Toronto" },
  { name: "Montr\u00e9al", country: "Canada", lat: 45.5088, lon: -73.5878, tz: "America/Toronto" },
  { name: "Ottawa", country: "Canada", lat: 45.4112, lon: -75.6981, tz: "America/Toronto" },
  { name: "Toronto", country: "Canada", lat: 43.7064, lon: -79.3986, tz: "America/Toronto" },
  { name: "Vancouver", country: "Canada", lat: 49.2497, lon: -123.1193, tz: "America/Vancouver" },
  { name: "Winnipeg", country: "Canada", lat: 49.8844, lon: -97.1470, tz: "America/Winnipeg" },
  { name: "George Town", country: "Cayman Islands", lat: 19.2866, lon: -81.3744, tz: "America/Cayman" },
  { name: "West Bay", country: "Cayman Islands", lat: 19.3820, lon: -81.3928, tz: "America/Cayman" },
  { name: "Bambari", country: "Central African Republic", lat: 5.7679, lon: 20.6757, tz: "Africa/Bangui" },
  { name: "Bangassou", country: "Central African Republic", lat: 4.7413, lon: 22.8184, tz: "Africa/Bangui" },
  { name: "Bangui", country: "Central African Republic", lat: 4.3612, lon: 18.5550, tz: "Africa/Bangui" },
  { name: "Berb\u00e9rati", country: "Central African Republic", lat: 4.2612, lon: 15.7922, tz: "Africa/Bangui" },
  { name: "Bimbo", country: "Central African Republic", lat: 4.2567, lon: 18.4158, tz: "Africa/Bangui" },
  { name: "Bossangoa", country: "Central African Republic", lat: 6.4926, lon: 17.4552, tz: "Africa/Bangui" },
  { name: "Bouar", country: "Central African Republic", lat: 5.9340, lon: 15.5960, tz: "Africa/Bangui" },
  { name: "B\u00e9goua", country: "Central African Republic", lat: 4.4544, lon: 18.5347, tz: "Africa/Bangui" },
  { name: "Carnot", country: "Central African Republic", lat: 4.9427, lon: 15.8773, tz: "Africa/Bangui" },
  { name: "Kaga-Bandoro", country: "Central African Republic", lat: 6.9896, lon: 19.1874, tz: "Africa/Bangui" },
  { name: "Ab\u00e9ch\u00e9", country: "Chad", lat: 13.8292, lon: 20.8324, tz: "Africa/Ndjamena" },
  { name: "Am Timan", country: "Chad", lat: 11.0433, lon: 20.2834, tz: "Africa/Ndjamena" },
  { name: "Bongor", country: "Chad", lat: 10.2809, lon: 15.3742, tz: "Africa/Ndjamena" },
  { name: "Doba", country: "Chad", lat: 8.6639, lon: 16.8532, tz: "Africa/Ndjamena" },
  { name: "Goz Beida", country: "Chad", lat: 12.2264, lon: 21.4098, tz: "Africa/Ndjamena" },
  { name: "Kelo", country: "Chad", lat: 9.3086, lon: 15.8066, tz: "Africa/Ndjamena" },
  { name: "Moundou", country: "Chad", lat: 8.5744, lon: 16.0772, tz: "Africa/Ndjamena" },
  { name: "N'Djamena", country: "Chad", lat: 12.1067, lon: 15.0444, tz: "Africa/Ndjamena" },
  { name: "Pala", country: "Chad", lat: 9.3646, lon: 14.9072, tz: "Africa/Ndjamena" },
  { name: "Sarh", country: "Chad", lat: 9.1456, lon: 18.3833, tz: "Africa/Ndjamena" },
  { name: "Antofagasta", country: "Chile", lat: -23.6509, lon: -70.3975, tz: "America/Santiago" },
  { name: "Arica", country: "Chile", lat: -18.4755, lon: -70.3006, tz: "America/Santiago" },
  { name: "Puente Alto", country: "Chile", lat: -33.6117, lon: -70.5758, tz: "America/Santiago" },
  { name: "Puerto Montt", country: "Chile", lat: -41.4693, lon: -72.9424, tz: "America/Santiago" },
  { name: "San Bernardo", country: "Chile", lat: -33.5922, lon: -70.6996, tz: "America/Santiago" },
  { name: "Santiago", country: "Chile", lat: -33.4569, lon: -70.6483, tz: "America/Santiago" },
  { name: "Talcahuano", country: "Chile", lat: -36.7249, lon: -73.1168, tz: "America/Santiago" },
  { name: "Temuco", country: "Chile", lat: -38.7363, lon: -72.5974, tz: "America/Santiago" },
  { name: "Valpara\u00edso", country: "Chile", lat: -33.0360, lon: -71.6296, tz: "America/Santiago" },
  { name: "Vi\u00f1a del Mar", country: "Chile", lat: -33.0246, lon: -71.5518, tz: "America/Santiago" },
  { name: "Beijing", country: "China", lat: 39.9075, lon: 116.3972, tz: "Asia/Shanghai" },
  { name: "Chengdu", country: "China", lat: 30.6667, lon: 104.0667, tz: "Asia/Shanghai" },
  { name: "Dongguan", country: "China", lat: 23.0180, lon: 113.7487, tz: "Asia/Shanghai" },
  { name: "Guangzhou", country: "China", lat: 23.1167, lon: 113.2500, tz: "Asia/Shanghai" },
  { name: "Nanjing", country: "China", lat: 32.0617, lon: 118.7778, tz: "Asia/Shanghai" },
  { name: "Shanghai", country: "China", lat: 31.2222, lon: 121.4581, tz: "Asia/Shanghai" },
  { name: "Shenzhen", country: "China", lat: 22.5455, lon: 114.0683, tz: "Asia/Shanghai" },
  { name: "Tianjin", country: "China", lat: 39.1422, lon: 117.1767, tz: "Asia/Shanghai" },
  { name: "Wuhan", country: "China", lat: 30.5833, lon: 114.2667, tz: "Asia/Shanghai" },
  { name: "Xi\u2019an", country: "China", lat: 34.2583, lon: 108.9286, tz: "Asia/Shanghai" },
  { name: "Flying Fish Cove", country: "Christmas Island", lat: -10.4217, lon: 105.6791, tz: "Indian/Christmas" },
  { name: "West Island", country: "Cocos Islands", lat: -12.1568, lon: 96.8225, tz: "Indian/Cocos" },
  { name: "Barranquilla", country: "Colombia", lat: 10.9685, lon: -74.7813, tz: "America/Bogota" },
  { name: "Bogot\u00e1", country: "Colombia", lat: 4.6097, lon: -74.0817, tz: "America/Bogota" },
  { name: "Bucaramanga", country: "Colombia", lat: 7.1250, lon: -73.1189, tz: "America/Bogota" },
  { name: "Cali", country: "Colombia", lat: 3.4305, lon: -76.5199, tz: "America/Bogota" },
  { name: "Cartagena", country: "Colombia", lat: 10.3982, lon: -75.4933, tz: "America/Bogota" },
  { name: "C\u00facuta", country: "Colombia", lat: 7.9074, lon: -72.5049, tz: "America/Bogota" },
  { name: "Ibagu\u00e9", country: "Colombia", lat: 4.4357, lon: -75.2029, tz: "America/Bogota" },
  { name: "Medell\u00edn", country: "Colombia", lat: 6.2450, lon: -75.5715, tz: "America/Bogota" },
  { name: "Monter\u00eda", country: "Colombia", lat: 8.7508, lon: -75.8782, tz: "America/Bogota" },
  { name: "Santa Marta", country: "Colombia", lat: 11.2386, lon: -74.1943, tz: "America/Bogota" },
  { name: "Fomboni", country: "Comoros", lat: -12.2876, lon: 43.7434, tz: "Indian/Comoro" },
  { name: "Moroni", country: "Comoros", lat: -11.7022, lon: 43.2551, tz: "Indian/Comoro" },
  { name: "Moutsamoudou", country: "Comoros", lat: -12.1667, lon: 44.3994, tz: "Indian/Comoro" },
  { name: "Avarua", country: "Cook Islands", lat: -21.2075, lon: -159.7755, tz: "Pacific/Rarotonga" },
  { name: "Alajuela", country: "Costa Rica", lat: 10.0172, lon: -84.2127, tz: "America/Costa_Rica" },
  { name: "Curridabat", country: "Costa Rica", lat: 9.9139, lon: -84.0359, tz: "America/Costa_Rica" },
  { name: "Liberia", country: "Costa Rica", lat: 10.6352, lon: -85.4378, tz: "America/Costa_Rica" },
  { name: "Lim\u00f3n", country: "Costa Rica", lat: 9.9907, lon: -83.0360, tz: "America/Costa_Rica" },
  { name: "Para\u00edso", country: "Costa Rica", lat: 9.8385, lon: -83.8666, tz: "America/Costa_Rica" },
  { name: "Puntarenas", country: "Costa Rica", lat: 9.9769, lon: -84.8379, tz: "America/Costa_Rica" },
  { name: "San Francisco", country: "Costa Rica", lat: 9.9938, lon: -84.1274, tz: "America/Costa_Rica" },
  { name: "San Isidro", country: "Costa Rica", lat: 9.3674, lon: -83.6971, tz: "America/Costa_Rica" },
  { name: "San Jos\u00e9", country: "Costa Rica", lat: 9.9339, lon: -84.0849, tz: "America/Costa_Rica" },
  { name: "San Vicente", country: "Costa Rica", lat: 9.9619, lon: -84.0494, tz: "America/Costa_Rica" },
  { name: "Centar", country: "Croatia", lat: 45.8131, lon: 15.9775, tz: "Europe/Zagreb" },
  { name: "Karlovac", country: "Croatia", lat: 45.4917, lon: 15.5500, tz: "Europe/Zagreb" },
  { name: "Osijek", country: "Croatia", lat: 45.5511, lon: 18.6939, tz: "Europe/Zagreb" },
  { name: "Pula", country: "Croatia", lat: 44.8683, lon: 13.8481, tz: "Europe/Zagreb" },
  { name: "Rijeka", country: "Croatia", lat: 45.3267, lon: 14.4424, tz: "Europe/Zagreb" },
  { name: "Sesvete", country: "Croatia", lat: 45.8311, lon: 16.1164, tz: "Europe/Zagreb" },
  { name: "Slavonski Brod", country: "Croatia", lat: 45.1603, lon: 18.0156, tz: "Europe/Zagreb" },
  { name: "Split", country: "Croatia", lat: 43.5089, lon: 16.4392, tz: "Europe/Zagreb" },
  { name: "Zadar", country: "Croatia", lat: 44.1158, lon: 15.2251, tz: "Europe/Zagreb" },
  { name: "Zagreb", country: "Croatia", lat: 45.8144, lon: 15.9780, tz: "Europe/Zagreb" },
  { name: "Almendares", country: "Cuba", lat: 23.1101, lon: -82.4232, tz: "America/Havana" },
  { name: "Arroyo Naranjo", country: "Cuba", lat: 23.0381, lon: -82.3770, tz: "America/Havana" },
  { name: "Camag\u00fcey", country: "Cuba", lat: 21.3808, lon: -77.9169, tz: "America/Havana" },
  { name: "Diez de Octubre", country: "Cuba", lat: 23.0881, lon: -82.3597, tz: "America/Havana" },
  { name: "Guant\u00e1namo", country: "Cuba", lat: 20.1444, lon: -75.2092, tz: "America/Havana" },
  { name: "Havana", country: "Cuba", lat: 23.1330, lon: -82.3830, tz: "America/Havana" },
  { name: "Holgu\u00edn", country: "Cuba", lat: 20.8872, lon: -76.2631, tz: "America/Havana" },
  { name: "Mantilla", country: "Cuba", lat: 23.0679, lon: -82.3414, tz: "America/Havana" },
  { name: "Santa Clara", country: "Cuba", lat: 22.4071, lon: -79.9658, tz: "America/Havana" },
  { name: "Santiago de Cuba", country: "Cuba", lat: 20.0229, lon: -75.8217, tz: "America/Havana" },
  { name: "Bandariba", country: "Curacao", lat: 12.0926, lon: -68.8309, tz: "America/Curacao" },
  { name: "Willemstad", country: "Curacao", lat: 12.1225, lon: -68.8864, tz: "America/Curacao" },
  { name: "Famagusta", country: "Cyprus", lat: 35.1249, lon: 33.9413, tz: "Asia/Famagusta" },
  { name: "Kyrenia", country: "Cyprus", lat: 35.3363, lon: 33.3173, tz: "Asia/Famagusta" },
  { name: "K\u00e1to Lakat\u00e1meia", country: "Cyprus", lat: 35.1176, lon: 33.3254, tz: "Asia/Nicosia" },
  { name: "K\u00e1to Polem\u00eddia", country: "Cyprus", lat: 34.6972, lon: 32.9932, tz: "Asia/Nicosia" },
  { name: "Larnaca", country: "Cyprus", lat: 34.9221, lon: 33.6279, tz: "Asia/Nicosia" },
  { name: "Limassol", country: "Cyprus", lat: 34.6841, lon: 33.0379, tz: "Asia/Nicosia" },
  { name: "M\u00f3rfou", country: "Cyprus", lat: 35.1987, lon: 32.9934, tz: "Asia/Nicosia" },
  { name: "Nicosia", country: "Cyprus", lat: 35.1728, lon: 33.3540, tz: "Asia/Nicosia" },
  { name: "Paphos", country: "Cyprus", lat: 34.7768, lon: 32.4245, tz: "Asia/Nicosia" },
  { name: "Str\u00f3volos", country: "Cyprus", lat: 35.1487, lon: 33.3338, tz: "Asia/Nicosia" },
  { name: "Brno", country: "Czechia", lat: 49.1952, lon: 16.6080, tz: "Europe/Prague" },
  { name: "Hradec Kr\u00e1lov\u00e9", country: "Czechia", lat: 50.2092, lon: 15.8328, tz: "Europe/Prague" },
  { name: "Liberec", country: "Czechia", lat: 50.7671, lon: 15.0562, tz: "Europe/Prague" },
  { name: "Olomouc", country: "Czechia", lat: 49.5955, lon: 17.2518, tz: "Europe/Prague" },
  { name: "Ostrava", country: "Czechia", lat: 49.8347, lon: 18.2820, tz: "Europe/Prague" },
  { name: "Pardubice", country: "Czechia", lat: 50.0408, lon: 15.7766, tz: "Europe/Prague" },
  { name: "Pilsen", country: "Czechia", lat: 49.7475, lon: 13.3776, tz: "Europe/Prague" },
  { name: "Prague", country: "Czechia", lat: 50.0880, lon: 14.4208, tz: "Europe/Prague" },
  { name: "\u00dast\u00ed nad Labem", country: "Czechia", lat: 50.6607, lon: 14.0323, tz: "Europe/Prague" },
  { name: "\u010cesk\u00e9 Bud\u011bjovice", country: "Czechia", lat: 48.9745, lon: 14.4743, tz: "Europe/Prague" },
  { name: "Bukavu", country: "Democratic Republic of the Congo", lat: -2.4908, lon: 28.8428, tz: "Africa/Lubumbashi" },
  { name: "Kananga", country: "Democratic Republic of the Congo", lat: -5.8962, lon: 22.4166, tz: "Africa/Lubumbashi" },
  { name: "Kikwit", country: "Democratic Republic of the Congo", lat: -5.0410, lon: 18.8162, tz: "Africa/Kinshasa" },
  { name: "Kinshasa", country: "Democratic Republic of the Congo", lat: -4.3276, lon: 15.3136, tz: "Africa/Kinshasa" },
  { name: "Kisangani", country: "Democratic Republic of the Congo", lat: 0.5153, lon: 25.1910, tz: "Africa/Lubumbashi" },
  { name: "Kolwezi", country: "Democratic Republic of the Congo", lat: -10.7148, lon: 25.4667, tz: "Africa/Lubumbashi" },
  { name: "Likasi", country: "Democratic Republic of the Congo", lat: -10.9830, lon: 26.7384, tz: "Africa/Lubumbashi" },
  { name: "Lubumbashi", country: "Democratic Republic of the Congo", lat: -11.6609, lon: 27.4794, tz: "Africa/Lubumbashi" },
  { name: "Mbuji-Mayi", country: "Democratic Republic of the Congo", lat: -6.1360, lon: 23.5898, tz: "Africa/Lubumbashi" },
  { name: "Tshikapa", country: "Democratic Republic of the Congo", lat: -6.4162, lon: 20.7999, tz: "Africa/Lubumbashi" },
  { name: "Aalborg", country: "Denmark", lat: 57.0480, lon: 9.9187, tz: "Europe/Copenhagen" },
  { name: "Copenhagen", country: "Denmark", lat: 55.6759, lon: 12.5655, tz: "Europe/Copenhagen" },
  { name: "Esbjerg", country: "Denmark", lat: 55.4703, lon: 8.4519, tz: "Europe/Copenhagen" },
  { name: "Frederiksberg", country: "Denmark", lat: 55.6794, lon: 12.5346, tz: "Europe/Copenhagen" },
  { name: "Horsens", country: "Denmark", lat: 55.8607, lon: 9.8503, tz: "Europe/Copenhagen" },
  { name: "Kolding", country: "Denmark", lat: 55.4904, lon: 9.4722, tz: "Europe/Copenhagen" },
  { name: "Odense", country: "Denmark", lat: 55.3959, lon: 10.3883, tz: "Europe/Copenhagen" },
  { name: "Randers", country: "Denmark", lat: 56.4607, lon: 10.0364, tz: "Europe/Copenhagen" },
  { name: "Vejle", country: "Denmark", lat: 55.7093, lon: 9.5357, tz: "Europe/Copenhagen" },
  { name: "\u00c5rhus", country: "Denmark", lat: 56.1567, lon: 10.2108, tz: "Europe/Copenhagen" },
  { name: "Ali Sabih", country: "Djibouti", lat: 11.1558, lon: 42.7125, tz: "Africa/Djibouti" },
  { name: "Arta", country: "Djibouti", lat: 11.5264, lon: 42.8519, tz: "Africa/Djibouti" },
  { name: "Dikhil", country: "Djibouti", lat: 11.1045, lon: 42.3697, tz: "Africa/Djibouti" },
  { name: "Djibouti", country: "Djibouti", lat: 11.5890, lon: 43.1450, tz: "Africa/Djibouti" },
  { name: "Obock", country: "Djibouti", lat: 11.9669, lon: 43.2884, tz: "Africa/Djibouti" },
  { name: "Tadjoura", country: "Djibouti", lat: 11.7878, lon: 42.8822, tz: "Africa/Djibouti" },
  { name: "Roseau", country: "Dominica", lat: 15.3017, lon: -61.3881, tz: "America/Dominica" },
  { name: "Bella Vista", country: "Dominican Republic", lat: 18.4554, lon: -69.9454, tz: "America/Santo_Domingo" },
  { name: "La Romana", country: "Dominican Republic", lat: 18.4233, lon: -68.9664, tz: "America/Santo_Domingo" },
  { name: "Puerto Plata", country: "Dominican Republic", lat: 19.7934, lon: -70.6884, tz: "America/Santo_Domingo" },
  { name: "San Crist\u00f3bal", country: "Dominican Republic", lat: 18.4171, lon: -70.1068, tz: "America/Santo_Domingo" },
  { name: "San Francisco de Macor\u00eds", country: "Dominican Republic", lat: 19.2938, lon: -70.2528, tz: "America/Santo_Domingo" },
  { name: "San Pedro de Macor\u00eds", country: "Dominican Republic", lat: 18.4539, lon: -69.3086, tz: "America/Santo_Domingo" },
  { name: "Santiago de los Caballeros", country: "Dominican Republic", lat: 19.4504, lon: -70.6908, tz: "America/Santo_Domingo" },
  { name: "Santo Domingo", country: "Dominican Republic", lat: 18.4719, lon: -69.8923, tz: "America/Santo_Domingo" },
  { name: "Santo Domingo Este", country: "Dominican Republic", lat: 18.4851, lon: -69.8476, tz: "America/Santo_Domingo" },
  { name: "Santo Domingo Oeste", country: "Dominican Republic", lat: 18.5000, lon: -70.0000, tz: "America/Santo_Domingo" },
  { name: "Ambato", country: "Ecuador", lat: -1.2491, lon: -78.6167, tz: "America/Guayaquil" },
  { name: "Cuenca", country: "Ecuador", lat: -2.8953, lon: -78.9963, tz: "America/Guayaquil" },
  { name: "Eloy Alfaro", country: "Ecuador", lat: -2.1692, lon: -79.8399, tz: "America/Guayaquil" },
  { name: "Guayaquil", country: "Ecuador", lat: -2.1962, lon: -79.8862, tz: "America/Guayaquil" },
  { name: "Loja", country: "Ecuador", lat: -3.9931, lon: -79.2042, tz: "America/Guayaquil" },
  { name: "Machala", country: "Ecuador", lat: -3.2589, lon: -79.9588, tz: "America/Guayaquil" },
  { name: "Manta", country: "Ecuador", lat: -0.9494, lon: -80.7314, tz: "America/Guayaquil" },
  { name: "Portoviejo", country: "Ecuador", lat: -1.0576, lon: -80.4514, tz: "America/Guayaquil" },
  { name: "Quito", country: "Ecuador", lat: -0.2298, lon: -78.5250, tz: "America/Guayaquil" },
  { name: "Santo Domingo de los Colorados", country: "Ecuador", lat: -0.2537, lon: -79.1763, tz: "America/Guayaquil" },
  { name: "Al Mansurah", country: "Egypt", lat: 31.0364, lon: 31.3807, tz: "Africa/Cairo" },
  { name: "Al Ma\u1e29allah al Kubr\u00e1", country: "Egypt", lat: 30.9706, lon: 31.1669, tz: "Africa/Cairo" },
  { name: "Alexandria", country: "Egypt", lat: 31.2018, lon: 29.9158, tz: "Africa/Cairo" },
  { name: "Cairo", country: "Egypt", lat: 30.0626, lon: 31.2497, tz: "Africa/Cairo" },
  { name: "Giza", country: "Egypt", lat: 30.0094, lon: 31.2086, tz: "Africa/Cairo" },
  { name: "Mad\u012bnat an Na\u015fr", country: "Egypt", lat: 30.0667, lon: 31.3000, tz: "Africa/Cairo" },
  { name: "Port Said", country: "Egypt", lat: 31.2653, lon: 32.3019, tz: "Africa/Cairo" },
  { name: "Shubr\u0101 al Khaymah", country: "Egypt", lat: 30.1251, lon: 31.2505, tz: "Africa/Cairo" },
  { name: "Suez", country: "Egypt", lat: 29.9737, lon: 32.5263, tz: "Africa/Cairo" },
  { name: "Tanta", country: "Egypt", lat: 30.7885, lon: 31.0019, tz: "Africa/Cairo" },
  { name: "Apopa", country: "El Salvador", lat: 13.8072, lon: -89.1792, tz: "America/El_Salvador" },
  { name: "Delgado", country: "El Salvador", lat: 13.7217, lon: -89.1687, tz: "America/El_Salvador" },
  { name: "Mejicanos", country: "El Salvador", lat: 13.7240, lon: -89.1880, tz: "America/El_Salvador" },
  { name: "San Marcos", country: "El Salvador", lat: 13.6611, lon: -89.1825, tz: "America/El_Salvador" },
  { name: "San Miguel", country: "El Salvador", lat: 13.4826, lon: -88.1821, tz: "America/El_Salvador" },
  { name: "San Salvador", country: "El Salvador", lat: 13.6893, lon: -89.1872, tz: "America/El_Salvador" },
  { name: "Santa Ana", country: "El Salvador", lat: 13.9883, lon: -89.5568, tz: "America/El_Salvador" },
  { name: "Santa Tecla", country: "El Salvador", lat: 13.6774, lon: -89.2833, tz: "America/El_Salvador" },
  { name: "Sonsonate", country: "El Salvador", lat: 13.7173, lon: -89.7230, tz: "America/El_Salvador" },
  { name: "Soyapango", country: "El Salvador", lat: 13.7102, lon: -89.1399, tz: "America/El_Salvador" },
  { name: "Bata", country: "Equatorial Guinea", lat: 1.8639, lon: 9.7658, tz: "Africa/Malabo" },
  { name: "Ebebiyin", country: "Equatorial Guinea", lat: 2.1522, lon: 11.3301, tz: "Africa/Malabo" },
  { name: "Malabo", country: "Equatorial Guinea", lat: 3.7558, lon: 8.7817, tz: "Africa/Malabo" },
  { name: "Asmara", country: "Eritrea", lat: 15.3381, lon: 38.9318, tz: "Africa/Asmara" },
  { name: "Assab", country: "Eritrea", lat: 13.0092, lon: 42.7394, tz: "Africa/Asmara" },
  { name: "Barentu", country: "Eritrea", lat: 15.1058, lon: 37.5907, tz: "Africa/Asmara" },
  { name: "Himora", country: "Eritrea", lat: 14.3043, lon: 36.6060, tz: "Africa/Asmara" },
  { name: "Keren", country: "Eritrea", lat: 15.7779, lon: 38.4511, tz: "Africa/Asmara" },
  { name: "Massawa", country: "Eritrea", lat: 15.6081, lon: 39.4746, tz: "Africa/Asmara" },
  { name: "Mendefera", country: "Eritrea", lat: 14.8872, lon: 38.8153, tz: "Africa/Asmara" },
  { name: "Kohtla-J\u00e4rve", country: "Estonia", lat: 59.3986, lon: 27.2731, tz: "Europe/Tallinn" },
  { name: "Maardu", country: "Estonia", lat: 59.4653, lon: 24.9822, tz: "Europe/Tallinn" },
  { name: "Narva", country: "Estonia", lat: 59.3772, lon: 28.1903, tz: "Europe/Tallinn" },
  { name: "N\u00f5mme", country: "Estonia", lat: 59.3776, lon: 24.6859, tz: "Europe/Tallinn" },
  { name: "P\u00e4rnu", country: "Estonia", lat: 58.3859, lon: 24.4971, tz: "Europe/Tallinn" },
  { name: "Tallinn", country: "Estonia", lat: 59.4370, lon: 24.7535, tz: "Europe/Tallinn" },
  { name: "Tartu", country: "Estonia", lat: 58.3806, lon: 26.7251, tz: "Europe/Tallinn" },
  { name: "Viljandi", country: "Estonia", lat: 58.3639, lon: 25.5900, tz: "Europe/Tallinn" },
  { name: "Lobamba", country: "Eswatini", lat: -26.4667, lon: 31.2000, tz: "Africa/Mbabane" },
  { name: "Manzini", country: "Eswatini", lat: -26.4988, lon: 31.3800, tz: "Africa/Mbabane" },
  { name: "Mbabane", country: "Eswatini", lat: -26.3167, lon: 31.1333, tz: "Africa/Mbabane" },
  { name: "Addis Ababa", country: "Ethiopia", lat: 9.0250, lon: 38.7469, tz: "Africa/Addis_Ababa" },
  { name: "Awasa", country: "Ethiopia", lat: 7.0621, lon: 38.4763, tz: "Africa/Addis_Ababa" },
  { name: "Bahir Dar", country: "Ethiopia", lat: 11.5936, lon: 37.3908, tz: "Africa/Addis_Ababa" },
  { name: "Dessie", country: "Ethiopia", lat: 11.1333, lon: 39.6333, tz: "Africa/Addis_Ababa" },
  { name: "Dire Dawa", country: "Ethiopia", lat: 9.5931, lon: 41.8661, tz: "Africa/Addis_Ababa" },
  { name: "Gonder", country: "Ethiopia", lat: 12.6000, lon: 37.4667, tz: "Africa/Addis_Ababa" },
  { name: "Jijiga", country: "Ethiopia", lat: 9.3500, lon: 42.8000, tz: "Africa/Addis_Ababa" },
  { name: "Jimma", country: "Ethiopia", lat: 7.6734, lon: 36.8344, tz: "Africa/Addis_Ababa" },
  { name: "Mek'ele", country: "Ethiopia", lat: 13.4967, lon: 39.4753, tz: "Africa/Addis_Ababa" },
  { name: "Nazr\u0113t", country: "Ethiopia", lat: 8.5500, lon: 39.2667, tz: "Africa/Addis_Ababa" },
  { name: "Stanley", country: "Falkland Islands", lat: -51.6938, lon: -57.8570, tz: "Atlantic/Stanley" },
  { name: "T\u00f3rshavn", country: "Faroe Islands", lat: 62.0097, lon: -6.7716, tz: "Atlantic/Faroe" },
  { name: "Labasa", country: "Fiji", lat: -16.4332, lon: 179.3645, tz: "Pacific/Fiji" },
  { name: "Lautoka", country: "Fiji", lat: -17.6169, lon: 177.4505, tz: "Pacific/Fiji" },
  { name: "Nadi", country: "Fiji", lat: -17.8031, lon: 177.4162, tz: "Pacific/Fiji" },
  { name: "Nasinu", country: "Fiji", lat: -18.0705, lon: 178.5131, tz: "Pacific/Fiji" },
  { name: "Sigatoka", country: "Fiji", lat: -18.1431, lon: 177.5069, tz: "Pacific/Fiji" },
  { name: "Suva", country: "Fiji", lat: -18.1368, lon: 178.4253, tz: "Pacific/Fiji" },
  { name: "East Helsinki", country: "Finland", lat: 60.2104, lon: 25.0829, tz: "Europe/Helsinki" },
  { name: "Espoo", country: "Finland", lat: 60.2052, lon: 24.6522, tz: "Europe/Helsinki" },
  { name: "Helsinki", country: "Finland", lat: 60.1695, lon: 24.9354, tz: "Europe/Helsinki" },
  { name: "Jyv\u00e4skyl\u00e4", country: "Finland", lat: 62.2415, lon: 25.7209, tz: "Europe/Helsinki" },
  { name: "Kuopio", country: "Finland", lat: 62.8924, lon: 27.6770, tz: "Europe/Helsinki" },
  { name: "Lahti", country: "Finland", lat: 60.9827, lon: 25.6615, tz: "Europe/Helsinki" },
  { name: "Oulu", country: "Finland", lat: 65.0124, lon: 25.4682, tz: "Europe/Helsinki" },
  { name: "Tampere", country: "Finland", lat: 61.4991, lon: 23.7871, tz: "Europe/Helsinki" },
  { name: "Turku", country: "Finland", lat: 60.4515, lon: 22.2687, tz: "Europe/Helsinki" },
  { name: "Vantaa", country: "Finland", lat: 60.2941, lon: 25.0410, tz: "Europe/Helsinki" },
  { name: "Bordeaux", country: "France", lat: 44.8404, lon: -0.5805, tz: "Europe/Paris" },
  { name: "Lyon", country: "France", lat: 45.7485, lon: 4.8467, tz: "Europe/Paris" },
  { name: "Marne La Vall\u00e9e", country: "France", lat: 48.8358, lon: 2.6424, tz: "Europe/Paris" },
  { name: "Marseille", country: "France", lat: 43.2970, lon: 5.3811, tz: "Europe/Paris" },
  { name: "Montpellier", country: "France", lat: 43.6109, lon: 3.8763, tz: "Europe/Paris" },
  { name: "Nantes", country: "France", lat: 47.2172, lon: -1.5534, tz: "Europe/Paris" },
  { name: "Nice", country: "France", lat: 43.7031, lon: 7.2661, tz: "Europe/Paris" },
  { name: "Paris", country: "France", lat: 48.8534, lon: 2.3488, tz: "Europe/Paris" },
  { name: "Strasbourg", country: "France", lat: 48.5839, lon: 7.7455, tz: "Europe/Paris" },
  { name: "Toulouse", country: "France", lat: 43.6043, lon: 1.4437, tz: "Europe/Paris" },
  { name: "Cayenne", country: "French Guiana", lat: 4.9381, lon: -52.3346, tz: "America/Cayenne" },
  { name: "Kourou", country: "French Guiana", lat: 5.1628, lon: -52.6427, tz: "America/Cayenne" },
  { name: "Matoury", country: "French Guiana", lat: 4.8492, lon: -52.3235, tz: "America/Cayenne" },
  { name: "R\u00e9mire-Montjoly", country: "French Guiana", lat: 4.9167, lon: -52.2667, tz: "America/Cayenne" },
  { name: "Saint-Laurent-du-Maroni", country: "French Guiana", lat: 5.5015, lon: -54.0292, tz: "America/Cayenne" },
  { name: "Faaa", country: "French Polynesia", lat: -17.5593, lon: -149.6035, tz: "Pacific/Tahiti" },
  { name: "Papeete", country: "French Polynesia", lat: -17.5347, lon: -149.5684, tz: "Pacific/Tahiti" },
  { name: "Punaauia", country: "French Polynesia", lat: -17.6333, lon: -149.6000, tz: "Pacific/Tahiti" },
  { name: "Port-aux-Fran\u00e7ais", country: "French Southern Territories", lat: -49.3492, lon: 70.2194, tz: "Indian/Kerguelen" },
  { name: "Akanda", country: "Gabon", lat: 0.6073, lon: 9.3293, tz: "Africa/Libreville" },
  { name: "Franceville", country: "Gabon", lat: -1.6333, lon: 13.5836, tz: "Africa/Libreville" },
  { name: "Lambar\u00e9n\u00e9", country: "Gabon", lat: -0.7001, lon: 10.2406, tz: "Africa/Libreville" },
  { name: "Libreville", country: "Gabon", lat: 0.3924, lon: 9.4536, tz: "Africa/Libreville" },
  { name: "Moanda", country: "Gabon", lat: -1.5665, lon: 13.1987, tz: "Africa/Libreville" },
  { name: "Mouila", country: "Gabon", lat: -1.8685, lon: 11.0559, tz: "Africa/Libreville" },
  { name: "Ntoum", country: "Gabon", lat: 0.3905, lon: 9.7610, tz: "Africa/Libreville" },
  { name: "Owendo", country: "Gabon", lat: 0.2912, lon: 9.5046, tz: "Africa/Libreville" },
  { name: "Oyem", country: "Gabon", lat: 1.5995, lon: 11.5793, tz: "Africa/Libreville" },
  { name: "Port-Gentil", country: "Gabon", lat: -0.7193, lon: 8.7815, tz: "Africa/Libreville" },
  { name: "Bakau", country: "Gambia", lat: 13.4781, lon: -16.6819, tz: "Africa/Banjul" },
  { name: "Banjul", country: "Gambia", lat: 13.4527, lon: -16.5780, tz: "Africa/Banjul" },
  { name: "Brikama", country: "Gambia", lat: 13.2714, lon: -16.6494, tz: "Africa/Banjul" },
  { name: "Bununka Kunda", country: "Gambia", lat: 13.4272, lon: -16.6853, tz: "Africa/Banjul" },
  { name: "Faji Kunda", country: "Gambia", lat: 13.4178, lon: -16.6667, tz: "Africa/Banjul" },
  { name: "Farafenni", country: "Gambia", lat: 13.5667, lon: -15.6000, tz: "Africa/Banjul" },
  { name: "Nema Kunku", country: "Gambia", lat: 13.2500, lon: -15.8833, tz: "Africa/Banjul" },
  { name: "Serekunda", country: "Gambia", lat: 13.4383, lon: -16.6781, tz: "Africa/Banjul" },
  { name: "Sukuta", country: "Gambia", lat: 13.4103, lon: -16.7081, tz: "Africa/Banjul" },
  { name: "Talinding", country: "Gambia", lat: 13.4256, lon: -16.6726, tz: "Africa/Banjul" },
  { name: "Batumi", country: "Georgia", lat: 41.6408, lon: 41.6306, tz: "Asia/Tbilisi" },
  { name: "Gori", country: "Georgia", lat: 41.9853, lon: 44.1129, tz: "Asia/Tbilisi" },
  { name: "Kutaisi", country: "Georgia", lat: 42.2679, lon: 42.6946, tz: "Asia/Tbilisi" },
  { name: "Poti", country: "Georgia", lat: 42.1427, lon: 41.6738, tz: "Asia/Tbilisi" },
  { name: "Rustavi", country: "Georgia", lat: 41.5627, lon: 44.9787, tz: "Asia/Tbilisi" },
  { name: "Sokhumi", country: "Georgia", lat: 43.0070, lon: 40.9893, tz: "Asia/Tbilisi" },
  { name: "Stantsiya Novyy Afon", country: "Georgia", lat: 43.0806, lon: 40.8383, tz: "Asia/Tbilisi" },
  { name: "Tbilisi", country: "Georgia", lat: 41.6914, lon: 44.8341, tz: "Asia/Tbilisi" },
  { name: "Ts\u2019khinvali", country: "Georgia", lat: 42.2276, lon: 43.9686, tz: "Asia/Tbilisi" },
  { name: "Zugdidi", country: "Georgia", lat: 42.5084, lon: 41.8680, tz: "Asia/Tbilisi" },
  { name: "Berlin", country: "Germany", lat: 52.5244, lon: 13.4105, tz: "Europe/Berlin" },
  { name: "Dortmund", country: "Germany", lat: 51.5149, lon: 7.4660, tz: "Europe/Berlin" },
  { name: "Dresden", country: "Germany", lat: 51.0509, lon: 13.7383, tz: "Europe/Berlin" },
  { name: "D\u00fcsseldorf", country: "Germany", lat: 51.2217, lon: 6.7762, tz: "Europe/Berlin" },
  { name: "Essen", country: "Germany", lat: 51.4566, lon: 7.0123, tz: "Europe/Berlin" },
  { name: "Frankfurt am Main", country: "Germany", lat: 50.1155, lon: 8.6842, tz: "Europe/Berlin" },
  { name: "Hamburg", country: "Germany", lat: 53.5507, lon: 9.9930, tz: "Europe/Berlin" },
  { name: "K\u00f6ln", country: "Germany", lat: 50.9333, lon: 6.9500, tz: "Europe/Berlin" },
  { name: "Munich", country: "Germany", lat: 48.1374, lon: 11.5755, tz: "Europe/Berlin" },
  { name: "Stuttgart", country: "Germany", lat: 48.7823, lon: 9.1770, tz: "Europe/Berlin" },
  { name: "Accra", country: "Ghana", lat: 5.5560, lon: -0.1969, tz: "Africa/Accra" },
  { name: "Ashaiman", country: "Ghana", lat: 5.6995, lon: -0.0348, tz: "Africa/Accra" },
  { name: "Atsiaman", country: "Ghana", lat: 5.6978, lon: -0.3282, tz: "Africa/Accra" },
  { name: "Cape Coast", country: "Ghana", lat: 5.1053, lon: -1.2466, tz: "Africa/Accra" },
  { name: "Kumasi", country: "Ghana", lat: 6.6885, lon: -1.6244, tz: "Africa/Accra" },
  { name: "Obuase", country: "Ghana", lat: 6.2023, lon: -1.6680, tz: "Africa/Accra" },
  { name: "Sekondi", country: "Ghana", lat: 4.9342, lon: -1.7145, tz: "Africa/Accra" },
  { name: "Takoradi", country: "Ghana", lat: 4.8982, lon: -1.7603, tz: "Africa/Accra" },
  { name: "Tamale", country: "Ghana", lat: 9.4008, lon: -0.8393, tz: "Africa/Accra" },
  { name: "Tema", country: "Ghana", lat: 5.6698, lon: -0.0166, tz: "Africa/Accra" },
  { name: "Gibraltar", country: "Gibraltar", lat: 36.1447, lon: -5.3526, tz: "Europe/Gibraltar" },
  { name: "Acharn\u00e9s", country: "Greece", lat: 38.0833, lon: 23.7333, tz: "Europe/Athens" },
  { name: "Athens", country: "Greece", lat: 37.9838, lon: 23.7278, tz: "Europe/Athens" },
  { name: "Ir\u00e1kleion", country: "Greece", lat: 35.3279, lon: 25.1434, tz: "Europe/Athens" },
  { name: "Kalamari\u00e1", country: "Greece", lat: 40.5825, lon: 22.9503, tz: "Europe/Athens" },
  { name: "Kallith\u00e9a", country: "Greece", lat: 37.9500, lon: 23.7000, tz: "Europe/Athens" },
  { name: "L\u00e1risa", country: "Greece", lat: 39.6369, lon: 22.4176, tz: "Europe/Athens" },
  { name: "Perist\u00e9ri", country: "Greece", lat: 38.0154, lon: 23.6919, tz: "Europe/Athens" },
  { name: "Piraeus", country: "Greece", lat: 37.9420, lon: 23.6462, tz: "Europe/Athens" },
  { name: "P\u00e1tra", country: "Greece", lat: 38.2444, lon: 21.7344, tz: "Europe/Athens" },
  { name: "Thessalon\u00edki", country: "Greece", lat: 40.6436, lon: 22.9309, tz: "Europe/Athens" },
  { name: "Nuuk", country: "Greenland", lat: 64.1835, lon: -51.7216, tz: "America/Nuuk" },
  { name: "Saint George's", country: "Grenada", lat: 12.0529, lon: -61.7523, tz: "America/Grenada" },
  { name: "Baie-Mahault", country: "Guadeloupe", lat: 16.2674, lon: -61.5854, tz: "America/Guadeloupe" },
  { name: "Basse-Terre", country: "Guadeloupe", lat: 15.9971, lon: -61.7321, tz: "America/Guadeloupe" },
  { name: "Capesterre-Belle-Eau", country: "Guadeloupe", lat: 16.0450, lon: -61.5643, tz: "America/Guadeloupe" },
  { name: "Le Gosier", country: "Guadeloupe", lat: 16.2062, lon: -61.4931, tz: "America/Guadeloupe" },
  { name: "Le Moule", country: "Guadeloupe", lat: 16.3316, lon: -61.3466, tz: "America/Guadeloupe" },
  { name: "Les Abymes", country: "Guadeloupe", lat: 16.2730, lon: -61.5051, tz: "America/Guadeloupe" },
  { name: "Petit-Bourg", country: "Guadeloupe", lat: 16.1916, lon: -61.5914, tz: "America/Guadeloupe" },
  { name: "Pointe-\u00e0-Pitre", country: "Guadeloupe", lat: 16.2364, lon: -61.5346, tz: "America/Guadeloupe" },
  { name: "Sainte-Anne", country: "Guadeloupe", lat: 16.2256, lon: -61.3810, tz: "America/Guadeloupe" },
  { name: "Sainte-Rose", country: "Guadeloupe", lat: 16.3326, lon: -61.6969, tz: "America/Guadeloupe" },
  { name: "Dededo Village", country: "Guam", lat: 13.5178, lon: 144.8391, tz: "Pacific/Guam" },
  { name: "Hag\u00e5t\u00f1a", country: "Guam", lat: 13.4757, lon: 144.7489, tz: "Pacific/Guam" },
  { name: "Mangilao Village", country: "Guam", lat: 13.4476, lon: 144.8011, tz: "Pacific/Guam" },
  { name: "Tamuning-Tumon-Harmon Village", country: "Guam", lat: 13.4877, lon: 144.7814, tz: "Pacific/Guam" },
  { name: "Yigo Village", country: "Guam", lat: 13.5360, lon: 144.8886, tz: "Pacific/Guam" },
  { name: "Chichicastenango", country: "Guatemala", lat: 14.9433, lon: -91.1112, tz: "America/Guatemala" },
  { name: "Cob\u00e1n", country: "Guatemala", lat: 15.4703, lon: -90.3745, tz: "America/Guatemala" },
  { name: "Escuintla", country: "Guatemala", lat: 14.3010, lon: -90.7882, tz: "America/Guatemala" },
  { name: "Guatemala City", country: "Guatemala", lat: 14.6407, lon: -90.5133, tz: "America/Guatemala" },
  { name: "Jalapa", country: "Guatemala", lat: 14.6354, lon: -89.9911, tz: "America/Guatemala" },
  { name: "Jutiapa", country: "Guatemala", lat: 14.2849, lon: -89.8880, tz: "America/Guatemala" },
  { name: "Mixco", country: "Guatemala", lat: 14.6308, lon: -90.6071, tz: "America/Guatemala" },
  { name: "Quetzaltenango", country: "Guatemala", lat: 14.8446, lon: -91.5232, tz: "America/Guatemala" },
  { name: "Villa Canales", country: "Guatemala", lat: 14.4828, lon: -90.5343, tz: "America/Guatemala" },
  { name: "Villa Nueva", country: "Guatemala", lat: 14.5251, lon: -90.5854, tz: "America/Guatemala" },
  { name: "Saint Peter Port", country: "Guernsey", lat: 49.4598, lon: -2.5353, tz: "Europe/Guernsey" },
  { name: "Camayenne", country: "Guinea", lat: 9.5350, lon: -13.6878, tz: "Africa/Conakry" },
  { name: "Conakry", country: "Guinea", lat: 9.5380, lon: -13.6773, tz: "Africa/Conakry" },
  { name: "Dubr\u00e9ka", country: "Guinea", lat: 9.7911, lon: -13.5233, tz: "Africa/Conakry" },
  { name: "Kamsar", country: "Guinea", lat: 10.6500, lon: -14.6167, tz: "Africa/Conakry" },
  { name: "Kankan", country: "Guinea", lat: 10.3854, lon: -9.3057, tz: "Africa/Conakry" },
  { name: "Kindia", country: "Guinea", lat: 10.0569, lon: -12.8658, tz: "Africa/Conakry" },
  { name: "Kissidougou", country: "Guinea", lat: 9.1848, lon: -10.0999, tz: "Africa/Conakry" },
  { name: "Man\u00e9ah", country: "Guinea", lat: 9.7333, lon: -13.4167, tz: "Africa/Conakry" },
  { name: "Nz\u00e9r\u00e9kor\u00e9", country: "Guinea", lat: 7.7562, lon: -8.8179, tz: "Africa/Conakry" },
  { name: "Siguiri", country: "Guinea", lat: 11.4228, lon: -9.1685, tz: "Africa/Conakry" },
  { name: "Bafat\u00e1", country: "Guinea-Bissau", lat: 12.1658, lon: -14.6617, tz: "Africa/Bissau" },
  { name: "Bissau", country: "Guinea-Bissau", lat: 11.8636, lon: -15.5977, tz: "Africa/Bissau" },
  { name: "Gab\u00fa", country: "Guinea-Bissau", lat: 12.2800, lon: -14.2222, tz: "Africa/Bissau" },
  { name: "Georgetown", country: "Guyana", lat: 6.8045, lon: -58.1553, tz: "America/Guyana" },
  { name: "Linden", country: "Guyana", lat: 6.0081, lon: -58.3072, tz: "America/Guyana" },
  { name: "New Amsterdam", country: "Guyana", lat: 6.2492, lon: -57.5158, tz: "America/Guyana" },
  { name: "Cap-Ha\u00eftien", country: "Haiti", lat: 19.7594, lon: -72.1981, tz: "America/Port-au-Prince" },
  { name: "Carrefour", country: "Haiti", lat: 18.5411, lon: -72.3992, tz: "America/Port-au-Prince" },
  { name: "Croix-des-Bouquets", country: "Haiti", lat: 18.5768, lon: -72.2262, tz: "America/Port-au-Prince" },
  { name: "Delmas", country: "Haiti", lat: 18.5448, lon: -72.3004, tz: "America/Port-au-Prince" },
  { name: "Jacmel", country: "Haiti", lat: 18.2357, lon: -72.5369, tz: "America/Port-au-Prince" },
  { name: "Les Cayes", country: "Haiti", lat: 18.1920, lon: -73.7495, tz: "America/Port-au-Prince" },
  { name: "L\u00e9og\u00e2ne", country: "Haiti", lat: 18.5111, lon: -72.6334, tz: "America/Port-au-Prince" },
  { name: "Port-au-Prince", country: "Haiti", lat: 18.5435, lon: -72.3388, tz: "America/Port-au-Prince" },
  { name: "Port-de-Paix", country: "Haiti", lat: 19.9398, lon: -72.8304, tz: "America/Port-au-Prince" },
  { name: "P\u00e9tionville", country: "Haiti", lat: 18.5125, lon: -72.2853, tz: "America/Port-au-Prince" },
  { name: "Choloma", country: "Honduras", lat: 15.6144, lon: -87.9530, tz: "America/Tegucigalpa" },
  { name: "Ciudad Choluteca", country: "Honduras", lat: 13.3057, lon: -87.1788, tz: "America/Tegucigalpa" },
  { name: "Comayagua", country: "Honduras", lat: 14.4558, lon: -87.6397, tz: "America/Tegucigalpa" },
  { name: "Danl\u00ed", country: "Honduras", lat: 14.0333, lon: -86.5833, tz: "America/Tegucigalpa" },
  { name: "El Progreso", country: "Honduras", lat: 15.4000, lon: -87.8000, tz: "America/Tegucigalpa" },
  { name: "La Ceiba", country: "Honduras", lat: 15.7597, lon: -86.7822, tz: "America/Tegucigalpa" },
  { name: "La Lima", country: "Honduras", lat: 15.4333, lon: -87.9167, tz: "America/Tegucigalpa" },
  { name: "Puerto Cortez", country: "Honduras", lat: 15.8256, lon: -87.9297, tz: "America/Tegucigalpa" },
  { name: "San Pedro Sula", country: "Honduras", lat: 15.5059, lon: -88.0259, tz: "America/Tegucigalpa" },
  { name: "Tegucigalpa", country: "Honduras", lat: 14.0818, lon: -87.2068, tz: "America/Tegucigalpa" },
  { name: "Hong Kong", country: "Hong Kong", lat: 22.2783, lon: 114.1747, tz: "Asia/Hong_Kong" },
  { name: "Hong Kong Island", country: "Hong Kong", lat: 22.2630, lon: 114.1842, tz: "Asia/Hong_Kong" },
  { name: "Kowloon", country: "Hong Kong", lat: 22.3167, lon: 114.1833, tz: "Asia/Hong_Kong" },
  { name: "Kowloon City", country: "Hong Kong", lat: 22.3305, lon: 114.1922, tz: "Asia/Hong_Kong" },
  { name: "New Territories", country: "Hong Kong", lat: 22.4244, lon: 114.1110, tz: "Asia/Hong_Kong" },
  { name: "Sha Tin", country: "Hong Kong", lat: 22.3833, lon: 114.1833, tz: "Asia/Hong_Kong" },
  { name: "Sham Shui Po", country: "Hong Kong", lat: 22.3302, lon: 114.1595, tz: "Asia/Hong_Kong" },
  { name: "Tuen Mun", country: "Hong Kong", lat: 22.3917, lon: 113.9716, tz: "Asia/Hong_Kong" },
  { name: "Victoria", country: "Hong Kong", lat: 22.2875, lon: 114.1442, tz: "Asia/Hong_Kong" },
  { name: "Wong Tai Sin", country: "Hong Kong", lat: 22.3500, lon: 114.1833, tz: "Asia/Hong_Kong" },
  { name: "Budapest", country: "Hungary", lat: 47.4984, lon: 19.0404, tz: "Europe/Budapest" },
  { name: "Budapest III. ker\u00fclet", country: "Hungary", lat: 47.5416, lon: 19.0450, tz: "Europe/Budapest" },
  { name: "Budapest XI. ker\u00fclet", country: "Hungary", lat: 47.4760, lon: 19.0360, tz: "Europe/Budapest" },
  { name: "Debrecen", country: "Hungary", lat: 47.5317, lon: 21.6244, tz: "Europe/Budapest" },
  { name: "Gy\u0151r", country: "Hungary", lat: 47.6833, lon: 17.6351, tz: "Europe/Budapest" },
  { name: "Miskolc", country: "Hungary", lat: 48.1031, lon: 20.7781, tz: "Europe/Budapest" },
  { name: "Ny\u00edregyh\u00e1za", country: "Hungary", lat: 47.9554, lon: 21.7167, tz: "Europe/Budapest" },
  { name: "P\u00e9cs", country: "Hungary", lat: 46.0725, lon: 18.2308, tz: "Europe/Budapest" },
  { name: "Szeged", country: "Hungary", lat: 46.2530, lon: 20.1482, tz: "Europe/Budapest" },
  { name: "Zugl\u00f3", country: "Hungary", lat: 47.5176, lon: 19.1055, tz: "Europe/Budapest" },
  { name: "Akureyri", country: "Iceland", lat: 65.6835, lon: -18.0878, tz: "Atlantic/Reykjavik" },
  { name: "Hafnarfj\u00f6r\u00f0ur", country: "Iceland", lat: 64.0671, lon: -21.9377, tz: "Atlantic/Reykjavik" },
  { name: "Keflav\u00edk", country: "Iceland", lat: 64.0049, lon: -22.5624, tz: "Atlantic/Reykjavik" },
  { name: "K\u00f3pavogur", country: "Iceland", lat: 64.1123, lon: -21.9130, tz: "Atlantic/Reykjavik" },
  { name: "Reykjanesb\u00e6r", country: "Iceland", lat: 63.9981, lon: -22.5611, tz: "Atlantic/Reykjavik" },
  { name: "Reykjav\u00edk", country: "Iceland", lat: 64.1355, lon: -21.8954, tz: "Atlantic/Reykjavik" },
  { name: "Ahmedabad", country: "India", lat: 23.0258, lon: 72.5873, tz: "Asia/Kolkata" },
  { name: "Bengaluru", country: "India", lat: 12.9719, lon: 77.5937, tz: "Asia/Kolkata" },
  { name: "Chennai", country: "India", lat: 13.0878, lon: 80.2785, tz: "Asia/Kolkata" },
  { name: "Delhi", country: "India", lat: 28.6519, lon: 77.2315, tz: "Asia/Kolkata" },
  { name: "Hyderabad", country: "India", lat: 17.3840, lon: 78.4564, tz: "Asia/Kolkata" },
  { name: "Jaipur", country: "India", lat: 26.9196, lon: 75.7878, tz: "Asia/Kolkata" },
  { name: "Kolkata", country: "India", lat: 22.5626, lon: 88.3630, tz: "Asia/Kolkata" },
  { name: "Mumbai", country: "India", lat: 19.0728, lon: 72.8826, tz: "Asia/Kolkata" },
  { name: "Pune", country: "India", lat: 18.5196, lon: 73.8554, tz: "Asia/Kolkata" },
  { name: "Surat", country: "India", lat: 21.1959, lon: 72.8302, tz: "Asia/Kolkata" },
  { name: "Bandung", country: "Indonesia", lat: -6.9222, lon: 107.6069, tz: "Asia/Jakarta" },
  { name: "Bekasi", country: "Indonesia", lat: -6.2349, lon: 106.9896, tz: "Asia/Jakarta" },
  { name: "Depok", country: "Indonesia", lat: -6.4000, lon: 106.8186, tz: "Asia/Jakarta" },
  { name: "Jakarta", country: "Indonesia", lat: -6.2146, lon: 106.8451, tz: "Asia/Jakarta" },
  { name: "Makassar", country: "Indonesia", lat: -5.1486, lon: 119.4319, tz: "Asia/Makassar" },
  { name: "Medan", country: "Indonesia", lat: 3.5833, lon: 98.6667, tz: "Asia/Jakarta" },
  { name: "Palembang", country: "Indonesia", lat: -2.9167, lon: 104.7458, tz: "Asia/Jakarta" },
  { name: "Semarang", country: "Indonesia", lat: -6.9931, lon: 110.4208, tz: "Asia/Jakarta" },
  { name: "Surabaya", country: "Indonesia", lat: -7.2492, lon: 112.7508, tz: "Asia/Jakarta" },
  { name: "Tangerang", country: "Indonesia", lat: -6.1781, lon: 106.6300, tz: "Asia/Jakarta" },
  { name: "Ahvaz", country: "Iran", lat: 31.3190, lon: 48.6842, tz: "Asia/Tehran" },
  { name: "Isfahan", country: "Iran", lat: 32.6525, lon: 51.6746, tz: "Asia/Tehran" },
  { name: "Karaj", country: "Iran", lat: 35.8327, lon: 50.9915, tz: "Asia/Tehran" },
  { name: "Kermanshah", country: "Iran", lat: 34.3142, lon: 47.0650, tz: "Asia/Tehran" },
  { name: "Mashhad", country: "Iran", lat: 36.2981, lon: 59.6057, tz: "Asia/Tehran" },
  { name: "Pasragad Branch", country: "Iran", lat: 34.7777, lon: 48.4717, tz: "Asia/Tehran" },
  { name: "Qom", country: "Iran", lat: 34.6401, lon: 50.8764, tz: "Asia/Tehran" },
  { name: "Shiraz", country: "Iran", lat: 29.6103, lon: 52.5311, tz: "Asia/Tehran" },
  { name: "Tabriz", country: "Iran", lat: 38.0800, lon: 46.2919, tz: "Asia/Tehran" },
  { name: "Tehran", country: "Iran", lat: 35.6944, lon: 51.4215, tz: "Asia/Tehran" },
  { name: "Ab\u016b Ghurayb", country: "Iraq", lat: 33.3056, lon: 44.1848, tz: "Asia/Baghdad" },
  { name: "Al Ba\u015frah al Qad\u012bmah", country: "Iraq", lat: 30.5032, lon: 47.8151, tz: "Asia/Baghdad" },
  { name: "Al Maw\u015fil al Jad\u012bdah", country: "Iraq", lat: 36.3327, lon: 43.1056, tz: "Asia/Baghdad" },
  { name: "As Sulaym\u0101n\u012byah", country: "Iraq", lat: 35.5650, lon: 45.4329, tz: "Asia/Baghdad" },
  { name: "Baghdad", country: "Iraq", lat: 33.3406, lon: 44.4009, tz: "Asia/Baghdad" },
  { name: "Basrah", country: "Iraq", lat: 30.5085, lon: 47.7804, tz: "Asia/Baghdad" },
  { name: "Erbil", country: "Iraq", lat: 36.1912, lon: 44.0094, tz: "Asia/Baghdad" },
  { name: "Karbala", country: "Iraq", lat: 32.6160, lon: 44.0249, tz: "Asia/Baghdad" },
  { name: "Kirkuk", country: "Iraq", lat: 35.4681, lon: 44.3922, tz: "Asia/Baghdad" },
  { name: "Mosul", country: "Iraq", lat: 36.3350, lon: 43.1189, tz: "Asia/Baghdad" },
  { name: "Cork", country: "Ireland", lat: 51.8980, lon: -8.4706, tz: "Europe/Dublin" },
  { name: "Drogheda", country: "Ireland", lat: 53.7189, lon: -6.3478, tz: "Europe/Dublin" },
  { name: "Dublin", country: "Ireland", lat: 53.3331, lon: -6.2489, tz: "Europe/Dublin" },
  { name: "Dundalk", country: "Ireland", lat: 54.0000, lon: -6.4167, tz: "Europe/Dublin" },
  { name: "Galway", country: "Ireland", lat: 53.2724, lon: -9.0510, tz: "Europe/Dublin" },
  { name: "Limerick", country: "Ireland", lat: 52.6647, lon: -8.6231, tz: "Europe/Dublin" },
  { name: "South Dublin", country: "Ireland", lat: 53.2903, lon: -6.3415, tz: "Europe/Dublin" },
  { name: "Swords", country: "Ireland", lat: 53.4597, lon: -6.2181, tz: "Europe/Dublin" },
  { name: "Tallaght", country: "Ireland", lat: 53.2859, lon: -6.3734, tz: "Europe/Dublin" },
  { name: "Waterford", country: "Ireland", lat: 52.2583, lon: -7.1119, tz: "Europe/Dublin" },
  { name: "Douglas", country: "Isle of Man", lat: 54.1500, lon: -4.4833, tz: "Europe/Isle_of_Man" },
  { name: "Ashdod", country: "Israel", lat: 31.7921, lon: 34.6497, tz: "Asia/Jerusalem" },
  { name: "Bnei Brak", country: "Israel", lat: 32.0807, lon: 34.8338, tz: "Asia/Jerusalem" },
  { name: "Haifa", country: "Israel", lat: 32.8130, lon: 34.9993, tz: "Asia/Jerusalem" },
  { name: "H\u0331olon", country: "Israel", lat: 32.0103, lon: 34.7792, tz: "Asia/Jerusalem" },
  { name: "Jerusalem", country: "Israel", lat: 31.7690, lon: 35.2163, tz: "Asia/Jerusalem" },
  { name: "Netanya", country: "Israel", lat: 32.3329, lon: 34.8599, tz: "Asia/Jerusalem" },
  { name: "Peta\u1e96 Tiqva", country: "Israel", lat: 32.0871, lon: 34.8875, tz: "Asia/Jerusalem" },
  { name: "Rishon LeTsiyyon", country: "Israel", lat: 31.9710, lon: 34.7894, tz: "Asia/Jerusalem" },
  { name: "Tel Aviv", country: "Israel", lat: 32.0809, lon: 34.7806, tz: "Asia/Jerusalem" },
  { name: "West Jerusalem", country: "Israel", lat: 31.7820, lon: 35.2196, tz: "Asia/Jerusalem" },
  { name: "Bari", country: "Italy", lat: 41.1207, lon: 16.8698, tz: "Europe/Rome" },
  { name: "Bologna", country: "Italy", lat: 44.4938, lon: 11.3387, tz: "Europe/Rome" },
  { name: "Catania", country: "Italy", lat: 37.4922, lon: 15.0704, tz: "Europe/Rome" },
  { name: "Florence", country: "Italy", lat: 43.7792, lon: 11.2463, tz: "Europe/Rome" },
  { name: "Genoa", country: "Italy", lat: 44.4048, lon: 8.9444, tz: "Europe/Rome" },
  { name: "Milan", country: "Italy", lat: 45.4643, lon: 9.1895, tz: "Europe/Rome" },
  { name: "Naples", country: "Italy", lat: 40.8522, lon: 14.2681, tz: "Europe/Rome" },
  { name: "Palermo", country: "Italy", lat: 38.1166, lon: 13.3636, tz: "Europe/Rome" },
  { name: "Rome", country: "Italy", lat: 41.8919, lon: 12.5113, tz: "Europe/Rome" },
  { name: "Turin", country: "Italy", lat: 45.0705, lon: 7.6868, tz: "Europe/Rome" },
  { name: "Abidjan", country: "Ivory Coast", lat: 5.3544, lon: -4.0017, tz: "Africa/Abidjan" },
  { name: "Abobo", country: "Ivory Coast", lat: 5.4161, lon: -4.0159, tz: "Africa/Abidjan" },
  { name: "Bouak\u00e9", country: "Ivory Coast", lat: 7.6939, lon: -5.0303, tz: "Africa/Abidjan" },
  { name: "Daloa", country: "Ivory Coast", lat: 6.8773, lon: -6.4502, tz: "Africa/Abidjan" },
  { name: "Gagnoa", country: "Ivory Coast", lat: 6.1319, lon: -5.9506, tz: "Africa/Abidjan" },
  { name: "Korhogo", country: "Ivory Coast", lat: 9.4580, lon: -5.6296, tz: "Africa/Abidjan" },
  { name: "Koumassi", country: "Ivory Coast", lat: 5.2972, lon: -3.9675, tz: "Africa/Abidjan" },
  { name: "San-P\u00e9dro", country: "Ivory Coast", lat: 4.7485, lon: -6.6363, tz: "Africa/Abidjan" },
  { name: "Sinfra", country: "Ivory Coast", lat: 6.6210, lon: -5.9114, tz: "Africa/Abidjan" },
  { name: "Yamoussoukro", country: "Ivory Coast", lat: 6.8205, lon: -5.2767, tz: "Africa/Abidjan" },
  { name: "Half Way Tree", country: "Jamaica", lat: 18.0125, lon: -76.7993, tz: "America/Jamaica" },
  { name: "Kingston", country: "Jamaica", lat: 17.9970, lon: -76.7936, tz: "America/Jamaica" },
  { name: "Linstead", country: "Jamaica", lat: 18.1368, lon: -77.0317, tz: "America/Jamaica" },
  { name: "Mandeville", country: "Jamaica", lat: 18.0417, lon: -77.5071, tz: "America/Jamaica" },
  { name: "May Pen", country: "Jamaica", lat: 17.9645, lon: -77.2451, tz: "America/Jamaica" },
  { name: "Montego Bay", country: "Jamaica", lat: 18.4712, lon: -77.9188, tz: "America/Jamaica" },
  { name: "New Kingston", country: "Jamaica", lat: 18.0075, lon: -76.7832, tz: "America/Jamaica" },
  { name: "Old Harbour", country: "Jamaica", lat: 17.9414, lon: -77.1090, tz: "America/Jamaica" },
  { name: "Portmore", country: "Jamaica", lat: 17.9710, lon: -76.8869, tz: "America/Jamaica" },
  { name: "Spanish Town", country: "Jamaica", lat: 17.9911, lon: -76.9574, tz: "America/Jamaica" },
  { name: "Fukuoka", country: "Japan", lat: 33.6000, lon: 130.4167, tz: "Asia/Tokyo" },
  { name: "Kawasaki", country: "Japan", lat: 35.5206, lon: 139.7172, tz: "Asia/Tokyo" },
  { name: "Kobe", country: "Japan", lat: 34.6913, lon: 135.1830, tz: "Asia/Tokyo" },
  { name: "Kyoto", country: "Japan", lat: 35.0211, lon: 135.7538, tz: "Asia/Tokyo" },
  { name: "Nagoya", country: "Japan", lat: 35.1815, lon: 136.9064, tz: "Asia/Tokyo" },
  { name: "Osaka", country: "Japan", lat: 34.6938, lon: 135.5011, tz: "Asia/Tokyo" },
  { name: "Saitama", country: "Japan", lat: 35.9081, lon: 139.6566, tz: "Asia/Tokyo" },
  { name: "Sapporo", country: "Japan", lat: 43.0667, lon: 141.3500, tz: "Asia/Tokyo" },
  { name: "Tokyo", country: "Japan", lat: 35.6895, lon: 139.6917, tz: "Asia/Tokyo" },
  { name: "Yokohama", country: "Japan", lat: 35.4333, lon: 139.6500, tz: "Asia/Tokyo" },
  { name: "Saint Helier", country: "Jersey", lat: 49.1880, lon: -2.1049, tz: "Europe/Jersey" },
  { name: "Amman", country: "Jordan", lat: 31.9552, lon: 35.9450, tz: "Asia/Amman" },
  { name: "Ar Ramth\u0101", country: "Jordan", lat: 32.5587, lon: 36.0082, tz: "Asia/Amman" },
  { name: "Irbid", country: "Jordan", lat: 32.5556, lon: 35.8500, tz: "Asia/Amman" },
  { name: "Khuraybat as S\u016bq", country: "Jordan", lat: 31.8791, lon: 35.9210, tz: "Asia/Amman" },
  { name: "Russeifa", country: "Jordan", lat: 32.0178, lon: 36.0464, tz: "Asia/Amman" },
  { name: "W\u0101d\u012b as S\u012br", country: "Jordan", lat: 31.9545, lon: 35.8183, tz: "Asia/Amman" },
  { name: "Zarqa", country: "Jordan", lat: 32.0727, lon: 36.0880, tz: "Asia/Amman" },
  { name: "\u1e28ayy Khild\u0101", country: "Jordan", lat: 31.9920, lon: 35.8400, tz: "Asia/Amman" },
  { name: "\u1e62uwayli\u1e25", country: "Jordan", lat: 32.0221, lon: 35.8439, tz: "Asia/Amman" },
  { name: "\u2018Ajl\u016bn", country: "Jordan", lat: 32.3333, lon: 35.7528, tz: "Asia/Amman" },
  { name: "Aktobe", country: "Kazakhstan", lat: 50.2797, lon: 57.2072, tz: "Asia/Aqtobe" },
  { name: "Almaty", country: "Kazakhstan", lat: 43.2525, lon: 76.9115, tz: "Asia/Almaty" },
  { name: "Astana", country: "Kazakhstan", lat: 51.1801, lon: 71.4460, tz: "Asia/Almaty" },
  { name: "Karagandy", country: "Kazakhstan", lat: 49.8019, lon: 73.1021, tz: "Asia/Almaty" },
  { name: "Kyzylorda", country: "Kazakhstan", lat: 44.8528, lon: 65.5092, tz: "Asia/Qyzylorda" },
  { name: "Oral", country: "Kazakhstan", lat: 51.2460, lon: 51.4256, tz: "Asia/Oral" },
  { name: "Pavlodar", country: "Kazakhstan", lat: 52.2760, lon: 76.9688, tz: "Asia/Almaty" },
  { name: "Shymkent", country: "Kazakhstan", lat: 42.3099, lon: 69.6004, tz: "Asia/Almaty" },
  { name: "Taraz", country: "Kazakhstan", lat: 42.8980, lon: 71.3733, tz: "Asia/Almaty" },
  { name: "Ust-Kamenogorsk", country: "Kazakhstan", lat: 49.9714, lon: 82.6059, tz: "Asia/Almaty" },
  { name: "Eldoret", country: "Kenya", lat: 0.5204, lon: 35.2699, tz: "Africa/Nairobi" },
  { name: "Kakamega", country: "Kenya", lat: 0.2842, lon: 34.7523, tz: "Africa/Nairobi" },
  { name: "Kikuyu", country: "Kenya", lat: -1.2463, lon: 36.6629, tz: "Africa/Nairobi" },
  { name: "Kisumu", country: "Kenya", lat: -0.1022, lon: 34.7617, tz: "Africa/Nairobi" },
  { name: "Mombasa", country: "Kenya", lat: -4.0547, lon: 39.6636, tz: "Africa/Nairobi" },
  { name: "Nairobi", country: "Kenya", lat: -1.2833, lon: 36.8167, tz: "Africa/Nairobi" },
  { name: "Naivasha", country: "Kenya", lat: -0.7138, lon: 36.4326, tz: "Africa/Nairobi" },
  { name: "Nakuru", country: "Kenya", lat: -0.3072, lon: 36.0722, tz: "Africa/Nairobi" },
  { name: "Ruiru", country: "Kenya", lat: -1.1466, lon: 36.9609, tz: "Africa/Nairobi" },
  { name: "Thika", country: "Kenya", lat: -1.0333, lon: 37.0693, tz: "Africa/Nairobi" },
  { name: "Tarawa", country: "Kiribati", lat: 1.3278, lon: 172.9770, tz: "Pacific/Tarawa" },
  { name: "De\u00e7an", country: "Kosovo", lat: 42.5402, lon: 20.2879, tz: "Europe/Belgrade" },
  { name: "Ferizaj", country: "Kosovo", lat: 42.3706, lon: 21.1553, tz: "Europe/Belgrade" },
  { name: "Gjakov\u00eb", country: "Kosovo", lat: 42.3803, lon: 20.4308, tz: "Europe/Belgrade" },
  { name: "Gjilan", country: "Kosovo", lat: 42.4605, lon: 21.4699, tz: "Europe/Belgrade" },
  { name: "Glogovac", country: "Kosovo", lat: 42.6283, lon: 20.8939, tz: "Europe/Belgrade" },
  { name: "Mitrovic\u00eb", country: "Kosovo", lat: 42.8833, lon: 20.8667, tz: "Europe/Belgrade" },
  { name: "Pej\u00eb", country: "Kosovo", lat: 42.6591, lon: 20.2883, tz: "Europe/Belgrade" },
  { name: "Pristina", country: "Kosovo", lat: 42.6727, lon: 21.1669, tz: "Europe/Belgrade" },
  { name: "Prizren", country: "Kosovo", lat: 42.2139, lon: 20.7397, tz: "Europe/Belgrade" },
  { name: "Suva Reka", country: "Kosovo", lat: 42.3586, lon: 20.8250, tz: "Europe/Belgrade" },
  { name: "Al A\u1e29mad\u012b", country: "Kuwait", lat: 29.0769, lon: 48.0839, tz: "Asia/Kuwait" },
  { name: "Al Farw\u0101n\u012byah", country: "Kuwait", lat: 29.2775, lon: 47.9586, tz: "Asia/Kuwait" },
  { name: "Al Fa\u1e29\u0101\u1e29\u012bl", country: "Kuwait", lat: 29.0825, lon: 48.1303, tz: "Asia/Kuwait" },
  { name: "Ar Riqqah", country: "Kuwait", lat: 29.1458, lon: 48.0947, tz: "Asia/Kuwait" },
  { name: "Ar Rumayth\u012byah", country: "Kuwait", lat: 29.3117, lon: 48.0742, tz: "Asia/Kuwait" },
  { name: "As S\u0101lim\u012byah", country: "Kuwait", lat: 29.3339, lon: 48.0761, tz: "Asia/Kuwait" },
  { name: "Kuwait City", country: "Kuwait", lat: 29.3670, lon: 47.9743, tz: "Asia/Kuwait" },
  { name: "Salw\u00e1", country: "Kuwait", lat: 29.2958, lon: 48.0786, tz: "Asia/Kuwait" },
  { name: "\u015eab\u0101\u1e29 as S\u0101lim", country: "Kuwait", lat: 29.2572, lon: 48.0572, tz: "Asia/Kuwait" },
  { name: "\u1e28awall\u012b", country: "Kuwait", lat: 29.3328, lon: 48.0286, tz: "Asia/Kuwait" },
  { name: "Balykchy", country: "Kyrgyzstan", lat: 42.4602, lon: 76.1871, tz: "Asia/Bishkek" },
  { name: "Bazar-Korgon", country: "Kyrgyzstan", lat: 41.0376, lon: 72.7459, tz: "Asia/Bishkek" },
  { name: "Bishkek", country: "Kyrgyzstan", lat: 42.8700, lon: 74.5900, tz: "Asia/Bishkek" },
  { name: "Jalal-Abad", country: "Kyrgyzstan", lat: 40.9450, lon: 72.9931, tz: "Asia/Bishkek" },
  { name: "Kara-Balta", country: "Kyrgyzstan", lat: 42.8142, lon: 73.8481, tz: "Asia/Bishkek" },
  { name: "Karakol", country: "Kyrgyzstan", lat: 42.4892, lon: 78.3936, tz: "Asia/Bishkek" },
  { name: "Naryn", country: "Kyrgyzstan", lat: 41.4283, lon: 75.9957, tz: "Asia/Bishkek" },
  { name: "Osh", country: "Kyrgyzstan", lat: 40.5283, lon: 72.7985, tz: "Asia/Bishkek" },
  { name: "Tokmok", country: "Kyrgyzstan", lat: 42.8419, lon: 75.3015, tz: "Asia/Bishkek" },
  { name: "Uzgen", country: "Kyrgyzstan", lat: 40.7699, lon: 73.3007, tz: "Asia/Bishkek" },
  { name: "Ban Nap\u00e8", country: "Laos", lat: 18.3053, lon: 105.0718, tz: "Asia/Vientiane" },
  { name: "Ban X\u00e9n\u00f4", country: "Laos", lat: 16.6759, lon: 104.9880, tz: "Asia/Vientiane" },
  { name: "Luang Prabang", country: "Laos", lat: 19.8933, lon: 102.1525, tz: "Asia/Vientiane" },
  { name: "Muang Ph\u00f4nsavan", country: "Laos", lat: 19.4494, lon: 103.1917, tz: "Asia/Vientiane" },
  { name: "Pakse", country: "Laos", lat: 15.1202, lon: 105.7990, tz: "Asia/Vientiane" },
  { name: "Savannakhet", country: "Laos", lat: 16.5703, lon: 104.7622, tz: "Asia/Vientiane" },
  { name: "Sekong", country: "Laos", lat: 15.3458, lon: 106.7237, tz: "Asia/Vientiane" },
  { name: "Thakh\u00e8k", country: "Laos", lat: 17.4103, lon: 104.8307, tz: "Asia/Vientiane" },
  { name: "Vientiane", country: "Laos", lat: 17.9667, lon: 102.6000, tz: "Asia/Vientiane" },
  { name: "Xam Nua", country: "Laos", lat: 20.4164, lon: 104.0450, tz: "Asia/Vientiane" },
  { name: "Daugavpils", country: "Latvia", lat: 55.8833, lon: 26.5333, tz: "Europe/Riga" },
  { name: "Jelgava", country: "Latvia", lat: 56.6500, lon: 23.7128, tz: "Europe/Riga" },
  { name: "J\u0113kabpils", country: "Latvia", lat: 56.4990, lon: 25.8574, tz: "Europe/Riga" },
  { name: "J\u016brmala", country: "Latvia", lat: 56.9680, lon: 23.7704, tz: "Europe/Riga" },
  { name: "Liep\u0101ja", country: "Latvia", lat: 56.5047, lon: 21.0109, tz: "Europe/Riga" },
  { name: "Ogre", country: "Latvia", lat: 56.8162, lon: 24.6140, tz: "Europe/Riga" },
  { name: "Riga", country: "Latvia", lat: 56.9460, lon: 24.1059, tz: "Europe/Riga" },
  { name: "R\u0113zekne", country: "Latvia", lat: 56.5103, lon: 27.3400, tz: "Europe/Riga" },
  { name: "Valmiera", country: "Latvia", lat: 57.5411, lon: 25.4275, tz: "Europe/Riga" },
  { name: "Ventspils", country: "Latvia", lat: 57.3948, lon: 21.5612, tz: "Europe/Riga" },
  { name: "Aley", country: "Lebanon", lat: 33.8053, lon: 35.6000, tz: "Asia/Beirut" },
  { name: "Beirut", country: "Lebanon", lat: 33.8933, lon: 35.5016, tz: "Asia/Beirut" },
  { name: "Habbo\u00fbch", country: "Lebanon", lat: 33.4073, lon: 35.4817, tz: "Asia/Beirut" },
  { name: "Jounieh", country: "Lebanon", lat: 33.9808, lon: 35.6178, tz: "Asia/Beirut" },
  { name: "Nabat\u00eey\u00e9 et Tahta", country: "Lebanon", lat: 33.3789, lon: 35.4839, tz: "Asia/Beirut" },
  { name: "Ra\u2019s Bayr\u016bt", country: "Lebanon", lat: 33.9000, lon: 35.4833, tz: "Asia/Beirut" },
  { name: "Sidon", country: "Lebanon", lat: 33.5575, lon: 35.3715, tz: "Asia/Beirut" },
  { name: "Tripoli", country: "Lebanon", lat: 34.4335, lon: 35.8441, tz: "Asia/Beirut" },
  { name: "Tyre", country: "Lebanon", lat: 33.2733, lon: 35.1939, tz: "Asia/Beirut" },
  { name: "Zahl\u00e9", country: "Lebanon", lat: 33.8468, lon: 35.9020, tz: "Asia/Beirut" },
  { name: "Butha-Buthe", country: "Lesotho", lat: -28.7666, lon: 28.2494, tz: "Africa/Maseru" },
  { name: "Hlotse", country: "Lesotho", lat: -28.8718, lon: 28.0450, tz: "Africa/Maseru" },
  { name: "Mafeteng", country: "Lesotho", lat: -29.8230, lon: 27.2374, tz: "Africa/Maseru" },
  { name: "Maputsoe", country: "Lesotho", lat: -28.8866, lon: 27.8991, tz: "Africa/Maseru" },
  { name: "Maseru", country: "Lesotho", lat: -29.3167, lon: 27.4833, tz: "Africa/Maseru" },
  { name: "Mohale's Hoek", country: "Lesotho", lat: -30.1514, lon: 27.4769, tz: "Africa/Maseru" },
  { name: "Qacha\u2019s Nek", country: "Lesotho", lat: -30.1154, lon: 28.6894, tz: "Africa/Maseru" },
  { name: "Quthing", country: "Lesotho", lat: -30.4000, lon: 27.7003, tz: "Africa/Maseru" },
  { name: "Teyateyaneng", country: "Lesotho", lat: -29.1472, lon: 27.7490, tz: "Africa/Maseru" },
  { name: "Thaba-Tseka", country: "Lesotho", lat: -29.5220, lon: 28.6084, tz: "Africa/Maseru" },
  { name: "Buchanan", country: "Liberia", lat: 5.8769, lon: -10.0496, tz: "Africa/Monrovia" },
  { name: "Foya Kamara", country: "Liberia", lat: 8.3603, lon: -10.2064, tz: "Africa/Monrovia" },
  { name: "Ganta", country: "Liberia", lat: 7.2350, lon: -8.9844, tz: "Africa/Monrovia" },
  { name: "Gbarnga", country: "Liberia", lat: 6.9954, lon: -9.4712, tz: "Africa/Monrovia" },
  { name: "Harbel", country: "Liberia", lat: 6.2468, lon: -10.3564, tz: "Africa/Monrovia" },
  { name: "Harper", country: "Liberia", lat: 4.3782, lon: -7.7108, tz: "Africa/Monrovia" },
  { name: "Kakata", country: "Liberia", lat: 6.5310, lon: -10.3537, tz: "Africa/Monrovia" },
  { name: "Monrovia", country: "Liberia", lat: 6.3005, lon: -10.7969, tz: "Africa/Monrovia" },
  { name: "Pleebo City", country: "Liberia", lat: 4.5895, lon: -7.6722, tz: "Africa/Monrovia" },
  { name: "Zwedru", country: "Liberia", lat: 6.0685, lon: -8.1356, tz: "Africa/Monrovia" },
  { name: "Al Khums", country: "Libya", lat: 32.6486, lon: 14.2619, tz: "Africa/Tripoli" },
  { name: "Az Z\u0101w\u012byah", country: "Libya", lat: 32.7571, lon: 12.7276, tz: "Africa/Tripoli" },
  { name: "Benghazi", country: "Libya", lat: 32.1149, lon: 20.0686, tz: "Africa/Tripoli" },
  { name: "Janz\u016br", country: "Libya", lat: 32.8187, lon: 13.0173, tz: "Africa/Tripoli" },
  { name: "Misratah", country: "Libya", lat: 32.3753, lon: 15.0925, tz: "Africa/Tripoli" },
  { name: "Sabha", country: "Libya", lat: 27.0377, lon: 14.4283, tz: "Africa/Tripoli" },
  { name: "Tobruk", country: "Libya", lat: 32.0896, lon: 23.9538, tz: "Africa/Tripoli" },
  { name: "Tripoli", country: "Libya", lat: 32.8874, lon: 13.1873, tz: "Africa/Tripoli" },
  { name: "Zawiya", country: "Libya", lat: 32.7522, lon: 12.7278, tz: "Africa/Tripoli" },
  { name: "Zliten", country: "Libya", lat: 32.4674, lon: 14.5687, tz: "Africa/Tripoli" },
  { name: "Vaduz", country: "Liechtenstein", lat: 47.1415, lon: 9.5215, tz: "Europe/Vaduz" },
  { name: "Alytus", country: "Lithuania", lat: 54.3963, lon: 24.0414, tz: "Europe/Vilnius" },
  { name: "Antakalnis", country: "Lithuania", lat: 54.7078, lon: 25.3171, tz: "Europe/Vilnius" },
  { name: "Dainava (Kaunas)", country: "Lithuania", lat: 54.9153, lon: 23.9683, tz: "Europe/Vilnius" },
  { name: "Eiguliai", country: "Lithuania", lat: 54.9313, lon: 23.9324, tz: "Europe/Vilnius" },
  { name: "Kaunas", country: "Lithuania", lat: 54.9027, lon: 23.9096, tz: "Europe/Vilnius" },
  { name: "Klaip\u0117da", country: "Lithuania", lat: 55.7068, lon: 21.1391, tz: "Europe/Vilnius" },
  { name: "Panev\u0117\u017eys", country: "Lithuania", lat: 55.7319, lon: 24.3598, tz: "Europe/Vilnius" },
  { name: "Vilnius", country: "Lithuania", lat: 54.6892, lon: 25.2798, tz: "Europe/Vilnius" },
  { name: "\u0160iauliai", country: "Lithuania", lat: 55.9333, lon: 23.3167, tz: "Europe/Vilnius" },
  { name: "\u0160ilainiai", country: "Lithuania", lat: 54.9291, lon: 23.8860, tz: "Europe/Vilnius" },
  { name: "Dudelange", country: "Luxembourg", lat: 49.4806, lon: 6.0875, tz: "Europe/Luxembourg" },
  { name: "Esch-sur-Alzette", country: "Luxembourg", lat: 49.4958, lon: 5.9806, tz: "Europe/Luxembourg" },
  { name: "Luxembourg", country: "Luxembourg", lat: 49.6098, lon: 6.1327, tz: "Europe/Luxembourg" },
  { name: "Lai Chi Van", country: "Macao", lat: 22.1197, lon: 113.5511, tz: "Asia/Macau" },
  { name: "Luhuan", country: "Macao", lat: 22.1181, lon: 113.5519, tz: "Asia/Macau" },
  { name: "Macau", country: "Macao", lat: 22.2006, lon: 113.5461, tz: "Asia/Macau" },
  { name: "Taipa", country: "Macao", lat: 22.1558, lon: 113.5569, tz: "Asia/Macau" },
  { name: "Zhuojiacun", country: "Macao", lat: 22.1614, lon: 113.5564, tz: "Asia/Macau" },
  { name: "Antalaha", country: "Madagascar", lat: -14.9003, lon: 50.2788, tz: "Indian/Antananarivo" },
  { name: "Antananarivo", country: "Madagascar", lat: -18.9137, lon: 47.5361, tz: "Indian/Antananarivo" },
  { name: "Antsirabe", country: "Madagascar", lat: -19.8659, lon: 47.0333, tz: "Indian/Antananarivo" },
  { name: "Antsiranana", country: "Madagascar", lat: -12.3173, lon: 49.2919, tz: "Indian/Antananarivo" },
  { name: "Fianarantsoa", country: "Madagascar", lat: -21.4527, lon: 47.0857, tz: "Indian/Antananarivo" },
  { name: "Imerintsiatosika", country: "Madagascar", lat: -18.9833, lon: 47.3167, tz: "Indian/Antananarivo" },
  { name: "Mahajanga", country: "Madagascar", lat: -15.7167, lon: 46.3167, tz: "Indian/Antananarivo" },
  { name: "Sambava", country: "Madagascar", lat: -14.2667, lon: 50.1667, tz: "Indian/Antananarivo" },
  { name: "Toamasina", country: "Madagascar", lat: -18.1492, lon: 49.4023, tz: "Indian/Antananarivo" },
  { name: "Toliara", country: "Madagascar", lat: -23.3500, lon: 43.6667, tz: "Indian/Antananarivo" },
  { name: "Balaka", country: "Malawi", lat: -14.9793, lon: 34.9558, tz: "Africa/Blantyre" },
  { name: "Blantyre", country: "Malawi", lat: -15.7850, lon: 35.0085, tz: "Africa/Blantyre" },
  { name: "Karonga", country: "Malawi", lat: -9.9333, lon: 33.9333, tz: "Africa/Blantyre" },
  { name: "Kasungu", country: "Malawi", lat: -13.0333, lon: 33.4833, tz: "Africa/Blantyre" },
  { name: "Lilongwe", country: "Malawi", lat: -13.9669, lon: 33.7873, tz: "Africa/Blantyre" },
  { name: "Liwonde", country: "Malawi", lat: -15.0666, lon: 35.2254, tz: "Africa/Blantyre" },
  { name: "Mangochi", country: "Malawi", lat: -14.4781, lon: 35.2645, tz: "Africa/Blantyre" },
  { name: "Mzuzu", country: "Malawi", lat: -11.4656, lon: 34.0207, tz: "Africa/Blantyre" },
  { name: "Salima", country: "Malawi", lat: -13.7804, lon: 34.4587, tz: "Africa/Blantyre" },
  { name: "Zomba", country: "Malawi", lat: -15.3860, lon: 35.3188, tz: "Africa/Blantyre" },
  { name: "Bukit Rahman Putra", country: "Malaysia", lat: 3.2173, lon: 101.5608, tz: "Asia/Kuala_Lumpur" },
  { name: "Ipoh", country: "Malaysia", lat: 4.5841, lon: 101.0829, tz: "Asia/Kuala_Lumpur" },
  { name: "Johor Bahru", country: "Malaysia", lat: 1.4655, lon: 103.7578, tz: "Asia/Kuala_Lumpur" },
  { name: "Kampung Baru Subang", country: "Malaysia", lat: 3.1500, lon: 101.5333, tz: "Asia/Kuala_Lumpur" },
  { name: "Klang", country: "Malaysia", lat: 3.0367, lon: 101.4433, tz: "Asia/Kuala_Lumpur" },
  { name: "Kuala Lumpur", country: "Malaysia", lat: 3.1412, lon: 101.6865, tz: "Asia/Kuala_Lumpur" },
  { name: "Pelentong", country: "Malaysia", lat: 1.5243, lon: 103.8240, tz: "Asia/Kuala_Lumpur" },
  { name: "Petaling Jaya", country: "Malaysia", lat: 3.1073, lon: 101.6067, tz: "Asia/Kuala_Lumpur" },
  { name: "Shah Alam", country: "Malaysia", lat: 3.0851, lon: 101.5328, tz: "Asia/Kuala_Lumpur" },
  { name: "Subang Jaya", country: "Malaysia", lat: 3.0438, lon: 101.5806, tz: "Asia/Kuala_Lumpur" },
  { name: "Male", country: "Maldives", lat: 4.1752, lon: 73.5092, tz: "Indian/Maldives" },
  { name: "Bamako", country: "Mali", lat: 12.6091, lon: -7.9752, tz: "Africa/Bamako" },
  { name: "Gao", country: "Mali", lat: 16.2717, lon: -0.0447, tz: "Africa/Bamako" },
  { name: "Kalaban Koro", country: "Mali", lat: 12.5733, lon: -8.0291, tz: "Africa/Bamako" },
  { name: "Kati", country: "Mali", lat: 12.7441, lon: -8.0726, tz: "Africa/Bamako" },
  { name: "Kayes", country: "Mali", lat: 14.4478, lon: -11.4439, tz: "Africa/Bamako" },
  { name: "Koutiala", country: "Mali", lat: 12.3872, lon: -5.4651, tz: "Africa/Bamako" },
  { name: "Mopti", country: "Mali", lat: 14.4843, lon: -4.1830, tz: "Africa/Bamako" },
  { name: "San", country: "Mali", lat: 13.2970, lon: -4.8963, tz: "Africa/Bamako" },
  { name: "Sikasso", country: "Mali", lat: 11.3176, lon: -5.6665, tz: "Africa/Bamako" },
  { name: "S\u00e9gou", country: "Mali", lat: 13.4403, lon: -6.2595, tz: "Africa/Bamako" },
  { name: "Birkirkara", country: "Malta", lat: 35.8972, lon: 14.4611, tz: "Europe/Malta" },
  { name: "Mosta", country: "Malta", lat: 35.9092, lon: 14.4256, tz: "Europe/Malta" },
  { name: "Qormi", country: "Malta", lat: 35.8760, lon: 14.4720, tz: "Europe/Malta" },
  { name: "San Pawl il-Ba\u0127ar", country: "Malta", lat: 35.9506, lon: 14.4156, tz: "Europe/Malta" },
  { name: "Sliema", country: "Malta", lat: 35.9125, lon: 14.5019, tz: "Europe/Malta" },
  { name: "Valletta", country: "Malta", lat: 35.8997, lon: 14.5148, tz: "Europe/Malta" },
  { name: "\u017babbar", country: "Malta", lat: 35.8761, lon: 14.5350, tz: "Europe/Malta" },
  { name: "Majuro", country: "Marshall Islands", lat: 7.0897, lon: 171.3803, tz: "Pacific/Majuro" },
  { name: "Ducos", country: "Martinique", lat: 14.5753, lon: -60.9745, tz: "America/Martinique" },
  { name: "Fort-de-France", country: "Martinique", lat: 14.6037, lon: -61.0742, tz: "America/Martinique" },
  { name: "La Trinit\u00e9", country: "Martinique", lat: 14.7381, lon: -60.9632, tz: "America/Martinique" },
  { name: "Le Fran\u00e7ois", country: "Martinique", lat: 14.6151, lon: -60.9031, tz: "America/Martinique" },
  { name: "Le Lamentin", country: "Martinique", lat: 14.6134, lon: -60.9996, tz: "America/Martinique" },
  { name: "Le Robert", country: "Martinique", lat: 14.6775, lon: -60.9423, tz: "America/Martinique" },
  { name: "Saint-Joseph", country: "Martinique", lat: 14.6710, lon: -61.0391, tz: "America/Martinique" },
  { name: "Sainte-Marie", country: "Martinique", lat: 14.7835, lon: -60.9923, tz: "America/Martinique" },
  { name: "Dar Naim", country: "Mauritania", lat: 18.1081, lon: -15.9267, tz: "Africa/Nouakchott" },
  { name: "Ka\u00e9di", country: "Mauritania", lat: 16.1507, lon: -13.5058, tz: "Africa/Nouakchott" },
  { name: "Kiffa", country: "Mauritania", lat: 16.6207, lon: -11.4021, tz: "Africa/Nouakchott" },
  { name: "Mbera", country: "Mauritania", lat: 15.8561, lon: -5.7907, tz: "Africa/Nouakchott" },
  { name: "Nouadhibou", country: "Mauritania", lat: 20.9419, lon: -17.0384, tz: "Africa/Nouakchott" },
  { name: "Nouakchott", country: "Mauritania", lat: 18.0858, lon: -15.9785, tz: "Africa/Nouakchott" },
  { name: "N\u00e9ma", country: "Mauritania", lat: 16.6170, lon: -7.2565, tz: "Africa/Nouakchott" },
  { name: "S\u00e9libaby", country: "Mauritania", lat: 15.1585, lon: -12.1843, tz: "Africa/Nouakchott" },
  { name: "Tevragh Zeina", country: "Mauritania", lat: 18.0989, lon: -15.9885, tz: "Africa/Nouakchott" },
  { name: "Zou\u00e9rat", country: "Mauritania", lat: 22.7354, lon: -12.4713, tz: "Africa/Nouakchott" },
  { name: "Bambous", country: "Mauritius", lat: -20.2567, lon: 57.4061, tz: "Indian/Mauritius" },
  { name: "Beau Bassin-Rose Hill", country: "Mauritius", lat: -20.2333, lon: 57.4661, tz: "Indian/Mauritius" },
  { name: "Bel Air Rivi\u00e8re S\u00e8che", country: "Mauritius", lat: -20.2578, lon: 57.7498, tz: "Indian/Mauritius" },
  { name: "Centre de Flacq", country: "Mauritius", lat: -20.1897, lon: 57.7144, tz: "Indian/Mauritius" },
  { name: "Curepipe", country: "Mauritius", lat: -20.3163, lon: 57.5259, tz: "Indian/Mauritius" },
  { name: "Goodlands", country: "Mauritius", lat: -20.0384, lon: 57.6506, tz: "Indian/Mauritius" },
  { name: "Port Louis", country: "Mauritius", lat: -20.1619, lon: 57.4989, tz: "Indian/Mauritius" },
  { name: "Quatre Bornes", country: "Mauritius", lat: -20.2638, lon: 57.4791, tz: "Indian/Mauritius" },
  { name: "Triolet", country: "Mauritius", lat: -20.0576, lon: 57.5502, tz: "Indian/Mauritius" },
  { name: "Vacoas", country: "Mauritius", lat: -20.2981, lon: 57.4783, tz: "Indian/Mauritius" },
  { name: "Dzaoudzi", country: "Mayotte", lat: -12.7834, lon: 45.2569, tz: "Indian/Mayotte" },
  { name: "Kaou\u00e9ni", country: "Mayotte", lat: -12.7712, lon: 45.2242, tz: "Indian/Mayotte" },
  { name: "Koungou", country: "Mayotte", lat: -12.7336, lon: 45.2042, tz: "Indian/Mayotte" },
  { name: "Labattoir", country: "Mayotte", lat: -12.7770, lon: 45.2823, tz: "Indian/Mayotte" },
  { name: "Mamoudzou", country: "Mayotte", lat: -12.7823, lon: 45.2288, tz: "Indian/Mayotte" },
  { name: "Mtsap\u00e9r\u00e9", country: "Mayotte", lat: -12.7864, lon: 45.2173, tz: "Indian/Mayotte" },
  { name: "Ciudad Ju\u00e1rez", country: "Mexico", lat: 31.7202, lon: -106.4608, tz: "America/Ciudad_Juarez" },
  { name: "Ecatepec de Morelos", country: "Mexico", lat: 19.6049, lon: -99.0606, tz: "America/Mexico_City" },
  { name: "Guadalajara", country: "Mexico", lat: 20.6774, lon: -103.3475, tz: "America/Mexico_City" },
  { name: "Iztapalapa", country: "Mexico", lat: 19.3553, lon: -99.0622, tz: "America/Mexico_City" },
  { name: "Le\u00f3n de los Aldama", country: "Mexico", lat: 21.1291, lon: -101.6737, tz: "America/Mexico_City" },
  { name: "Mexico City", country: "Mexico", lat: 19.4285, lon: -99.1277, tz: "America/Mexico_City" },
  { name: "Puebla", country: "Mexico", lat: 19.0478, lon: -98.2072, tz: "America/Mexico_City" },
  { name: "Santiago de Quer\u00e9taro", country: "Mexico", lat: 20.5881, lon: -100.3881, tz: "America/Mexico_City" },
  { name: "Tijuana", country: "Mexico", lat: 32.5027, lon: -117.0037, tz: "America/Tijuana" },
  { name: "Zapopan", country: "Mexico", lat: 20.7211, lon: -103.3874, tz: "America/Mexico_City" },
  { name: "Palikir", country: "Micronesia", lat: 6.9248, lon: 158.1611, tz: "Pacific/Pohnpei" },
  { name: "Bender", country: "Moldova", lat: 46.8316, lon: 29.4777, tz: "Europe/Chisinau" },
  { name: "B\u0103l\u0163i", country: "Moldova", lat: 47.7631, lon: 27.9293, tz: "Europe/Chisinau" },
  { name: "Cahul", country: "Moldova", lat: 45.9042, lon: 28.1993, tz: "Europe/Chisinau" },
  { name: "Chisinau", country: "Moldova", lat: 47.0090, lon: 28.8594, tz: "Europe/Chisinau" },
  { name: "Dub\u0103sari", country: "Moldova", lat: 47.2656, lon: 29.1667, tz: "Europe/Chisinau" },
  { name: "Orhei", country: "Moldova", lat: 47.3849, lon: 28.8245, tz: "Europe/Chisinau" },
  { name: "R\u00eebni\u0163a", country: "Moldova", lat: 47.7682, lon: 29.0100, tz: "Europe/Chisinau" },
  { name: "Soroca", country: "Moldova", lat: 48.1566, lon: 28.2849, tz: "Europe/Chisinau" },
  { name: "Tiraspol", country: "Moldova", lat: 46.8427, lon: 29.6291, tz: "Europe/Chisinau" },
  { name: "Ungheni", country: "Moldova", lat: 47.2108, lon: 27.8005, tz: "Europe/Chisinau" },
  { name: "Monaco", country: "Monaco", lat: 43.7372, lon: 7.4215, tz: "Europe/Monaco" },
  { name: "Monte-Carlo", country: "Monaco", lat: 43.7398, lon: 7.4273, tz: "Europe/Monaco" },
  { name: "Arvayheer", country: "Mongolia", lat: 46.2639, lon: 102.7750, tz: "Asia/Ulaanbaatar" },
  { name: "Bayanhongor", country: "Mongolia", lat: 46.1944, lon: 100.7181, tz: "Asia/Ulaanbaatar" },
  { name: "Bogd", country: "Mongolia", lat: 44.6702, lon: 102.1749, tz: "Asia/Ulaanbaatar" },
  { name: "Darhan", country: "Mongolia", lat: 49.4867, lon: 105.9228, tz: "Asia/Ulaanbaatar" },
  { name: "Erdenet", country: "Mongolia", lat: 49.0333, lon: 104.0833, tz: "Asia/Ulaanbaatar" },
  { name: "Khovd", country: "Mongolia", lat: 48.0056, lon: 91.6419, tz: "Asia/Hovd" },
  { name: "M\u00f6r\u00f6n", country: "Mongolia", lat: 49.6342, lon: 100.1625, tz: "Asia/Ulaanbaatar" },
  { name: "Ulaangom", country: "Mongolia", lat: 49.9811, lon: 92.0667, tz: "Asia/Hovd" },
  { name: "Ulan Bator", country: "Mongolia", lat: 47.9077, lon: 106.8832, tz: "Asia/Ulaanbaatar" },
  { name: "\u00d6lgii", country: "Mongolia", lat: 48.9683, lon: 89.9625, tz: "Asia/Hovd" },
  { name: "Bar", country: "Montenegro", lat: 42.0937, lon: 19.0984, tz: "Europe/Podgorica" },
  { name: "Bijelo Polje", country: "Montenegro", lat: 43.0383, lon: 19.7476, tz: "Europe/Podgorica" },
  { name: "Budva", country: "Montenegro", lat: 42.2864, lon: 18.8400, tz: "Europe/Podgorica" },
  { name: "Cetinje", country: "Montenegro", lat: 42.3906, lon: 18.9142, tz: "Europe/Podgorica" },
  { name: "Herceg Novi", country: "Montenegro", lat: 42.4531, lon: 18.5375, tz: "Europe/Podgorica" },
  { name: "Nik\u0161i\u0107", country: "Montenegro", lat: 42.7731, lon: 18.9445, tz: "Europe/Podgorica" },
  { name: "Pljevlja", country: "Montenegro", lat: 43.3567, lon: 19.3584, tz: "Europe/Podgorica" },
  { name: "Podgorica", country: "Montenegro", lat: 42.4412, lon: 19.2631, tz: "Europe/Podgorica" },
  { name: "Brades", country: "Montserrat", lat: 16.7918, lon: -62.2106, tz: "America/Montserrat" },
  { name: "Plymouth", country: "Montserrat", lat: 16.7055, lon: -62.2129, tz: "America/Montserrat" },
  { name: "Agadir", country: "Morocco", lat: 30.4202, lon: -9.5982, tz: "Africa/Casablanca" },
  { name: "Casablanca", country: "Morocco", lat: 33.5883, lon: -7.6114, tz: "Africa/Casablanca" },
  { name: "Fes", country: "Morocco", lat: 34.0331, lon: -5.0003, tz: "Africa/Casablanca" },
  { name: "Kenitra", country: "Morocco", lat: 34.2610, lon: -6.5802, tz: "Africa/Casablanca" },
  { name: "Marrakesh", country: "Morocco", lat: 31.6342, lon: -7.9999, tz: "Africa/Casablanca" },
  { name: "Meknes", country: "Morocco", lat: 33.8935, lon: -5.5473, tz: "Africa/Casablanca" },
  { name: "Oujda", country: "Morocco", lat: 34.6814, lon: -1.9086, tz: "Africa/Casablanca" },
  { name: "Rabat", country: "Morocco", lat: 34.0132, lon: -6.8326, tz: "Africa/Casablanca" },
  { name: "Sal\u00e9", country: "Morocco", lat: 34.0531, lon: -6.7985, tz: "Africa/Casablanca" },
  { name: "Tangier", country: "Morocco", lat: 35.7673, lon: -5.7998, tz: "Africa/Casablanca" },
  { name: "Beira", country: "Mozambique", lat: -19.8436, lon: 34.8389, tz: "Africa/Maputo" },
  { name: "Chimoio", country: "Mozambique", lat: -19.1164, lon: 33.4833, tz: "Africa/Maputo" },
  { name: "Lichinga", country: "Mozambique", lat: -13.3128, lon: 35.2406, tz: "Africa/Maputo" },
  { name: "Maputo", country: "Mozambique", lat: -25.9655, lon: 32.5832, tz: "Africa/Maputo" },
  { name: "Matola", country: "Mozambique", lat: -25.9622, lon: 32.4589, tz: "Africa/Maputo" },
  { name: "Nacala", country: "Mozambique", lat: -14.5626, lon: 40.6854, tz: "Africa/Maputo" },
  { name: "Nampula", country: "Mozambique", lat: -15.1165, lon: 39.2666, tz: "Africa/Maputo" },
  { name: "Pemba", country: "Mozambique", lat: -12.9740, lon: 40.5177, tz: "Africa/Maputo" },
  { name: "Quelimane", country: "Mozambique", lat: -17.8786, lon: 36.8883, tz: "Africa/Maputo" },
  { name: "Tete", country: "Mozambique", lat: -16.1564, lon: 33.5867, tz: "Africa/Maputo" },
  { name: "Amarapura", country: "Myanmar", lat: 21.9071, lon: 96.0489, tz: "Asia/Yangon" },
  { name: "Bago", country: "Myanmar", lat: 17.3352, lon: 96.4814, tz: "Asia/Yangon" },
  { name: "Insein", country: "Myanmar", lat: 16.8887, lon: 96.1026, tz: "Asia/Yangon" },
  { name: "Kyain Seikgyi Township", country: "Myanmar", lat: 15.8229, lon: 98.2526, tz: "Asia/Yangon" },
  { name: "Mandalay", country: "Myanmar", lat: 21.9747, lon: 96.0836, tz: "Asia/Yangon" },
  { name: "Mawlamyine", country: "Myanmar", lat: 16.4905, lon: 97.6282, tz: "Asia/Yangon" },
  { name: "Nay Pyi Taw", country: "Myanmar", lat: 19.7450, lon: 96.1297, tz: "Asia/Yangon" },
  { name: "Pathein", country: "Myanmar", lat: 16.7792, lon: 94.7321, tz: "Asia/Yangon" },
  { name: "Yangon", country: "Myanmar", lat: 16.8053, lon: 96.1561, tz: "Asia/Yangon" },
  { name: "Yawnghwe", country: "Myanmar", lat: 20.6597, lon: 96.9338, tz: "Asia/Yangon" },
  { name: "Katima Mulilo", country: "Namibia", lat: -17.5047, lon: 24.2757, tz: "Africa/Windhoek" },
  { name: "Okahandja", country: "Namibia", lat: -21.9814, lon: 16.9029, tz: "Africa/Windhoek" },
  { name: "Ondangwa", country: "Namibia", lat: -17.9082, lon: 15.9804, tz: "Africa/Windhoek" },
  { name: "Oshakati", country: "Namibia", lat: -17.7883, lon: 15.7044, tz: "Africa/Windhoek" },
  { name: "Otjiwarongo", country: "Namibia", lat: -20.4637, lon: 16.6477, tz: "Africa/Windhoek" },
  { name: "Rehoboth", country: "Namibia", lat: -23.3170, lon: 17.0900, tz: "Africa/Windhoek" },
  { name: "Rundu", country: "Namibia", lat: -17.9180, lon: 19.7731, tz: "Africa/Windhoek" },
  { name: "Swakopmund", country: "Namibia", lat: -22.6784, lon: 14.5266, tz: "Africa/Windhoek" },
  { name: "Walvis Bay", country: "Namibia", lat: -22.9575, lon: 14.5053, tz: "Africa/Windhoek" },
  { name: "Windhoek", country: "Namibia", lat: -22.5594, lon: 17.0832, tz: "Africa/Windhoek" },
  { name: "Yaren", country: "Nauru", lat: -0.5508, lon: 166.9252, tz: "Pacific/Nauru" },
  { name: "Bharatpur", country: "Nepal", lat: 27.6768, lon: 84.4359, tz: "Asia/Kathmandu" },
  { name: "Biratnagar", country: "Nepal", lat: 26.4550, lon: 87.2701, tz: "Asia/Kathmandu" },
  { name: "Birga\u00f1j", country: "Nepal", lat: 27.0171, lon: 84.8808, tz: "Asia/Kathmandu" },
  { name: "Butw\u0101l", country: "Nepal", lat: 27.7005, lon: 83.4484, tz: "Asia/Kathmandu" },
  { name: "Dhanga\u1e0dhi\u0307\u0304", country: "Nepal", lat: 28.7016, lon: 80.5899, tz: "Asia/Kathmandu" },
  { name: "Hetauda", country: "Nepal", lat: 27.4284, lon: 85.0322, tz: "Asia/Kathmandu" },
  { name: "Janakpur", country: "Nepal", lat: 26.7288, lon: 85.9263, tz: "Asia/Kathmandu" },
  { name: "Kathmandu", country: "Nepal", lat: 27.7017, lon: 85.3206, tz: "Asia/Kathmandu" },
  { name: "Pokhara", country: "Nepal", lat: 28.2669, lon: 83.9685, tz: "Asia/Kathmandu" },
  { name: "P\u0101tan", country: "Nepal", lat: 27.6766, lon: 85.3142, tz: "Asia/Kathmandu" },
  { name: "Dumb\u00e9a", country: "New Caledonia", lat: -22.1576, lon: 166.4572, tz: "Pacific/Noumea" },
  { name: "Mont-Dore", country: "New Caledonia", lat: -22.2626, lon: 166.5662, tz: "Pacific/Noumea" },
  { name: "Noum\u00e9a", country: "New Caledonia", lat: -22.2741, lon: 166.4488, tz: "Pacific/Noumea" },
  { name: "Auckland", country: "New Zealand", lat: -36.8485, lon: 174.7635, tz: "Pacific/Auckland" },
  { name: "Christchurch", country: "New Zealand", lat: -43.5333, lon: 172.6333, tz: "Pacific/Auckland" },
  { name: "Dunedin", country: "New Zealand", lat: -45.8742, lon: 170.5036, tz: "Pacific/Auckland" },
  { name: "Hamilton", country: "New Zealand", lat: -37.7833, lon: 175.2833, tz: "Pacific/Auckland" },
  { name: "Lower Hutt", country: "New Zealand", lat: -41.2167, lon: 174.9167, tz: "Pacific/Auckland" },
  { name: "Manukau City", country: "New Zealand", lat: -36.9928, lon: 174.8799, tz: "Pacific/Auckland" },
  { name: "North Shore", country: "New Zealand", lat: -36.8000, lon: 174.7500, tz: "Pacific/Auckland" },
  { name: "Palmerston North", country: "New Zealand", lat: -40.3564, lon: 175.6111, tz: "Pacific/Auckland" },
  { name: "Tauranga", country: "New Zealand", lat: -37.6861, lon: 176.1667, tz: "Pacific/Auckland" },
  { name: "Wellington", country: "New Zealand", lat: -41.2866, lon: 174.7756, tz: "Pacific/Auckland" },
  { name: "Chinandega", country: "Nicaragua", lat: 12.6295, lon: -87.1313, tz: "America/Managua" },
  { name: "El Viejo", country: "Nicaragua", lat: 12.6635, lon: -87.1666, tz: "America/Managua" },
  { name: "Estel\u00ed", country: "Nicaragua", lat: 13.0929, lon: -86.3559, tz: "America/Managua" },
  { name: "Granada", country: "Nicaragua", lat: 11.9306, lon: -85.9537, tz: "America/Managua" },
  { name: "Jinotega", country: "Nicaragua", lat: 13.0910, lon: -86.0023, tz: "America/Managua" },
  { name: "Le\u00f3n", country: "Nicaragua", lat: 12.4353, lon: -86.8786, tz: "America/Managua" },
  { name: "Managua", country: "Nicaragua", lat: 12.1328, lon: -86.2504, tz: "America/Managua" },
  { name: "Masaya", country: "Nicaragua", lat: 11.9732, lon: -86.0959, tz: "America/Managua" },
  { name: "Matagalpa", country: "Nicaragua", lat: 12.9279, lon: -85.9191, tz: "America/Managua" },
  { name: "Mateare", country: "Nicaragua", lat: 12.2351, lon: -86.4281, tz: "America/Managua" },
  { name: "Agadez", country: "Niger", lat: 16.9733, lon: 7.9911, tz: "Africa/Niamey" },
  { name: "Alaghsas", country: "Niger", lat: 17.0187, lon: 8.0168, tz: "Africa/Niamey" },
  { name: "Arlit", country: "Niger", lat: 18.7369, lon: 7.3853, tz: "Africa/Niamey" },
  { name: "Birni N Konni", country: "Niger", lat: 13.7960, lon: 5.2503, tz: "Africa/Niamey" },
  { name: "Dosso", country: "Niger", lat: 13.0490, lon: 3.1937, tz: "Africa/Niamey" },
  { name: "Gaya", country: "Niger", lat: 11.8843, lon: 3.4492, tz: "Africa/Niamey" },
  { name: "Maradi", country: "Niger", lat: 13.5000, lon: 7.1017, tz: "Africa/Niamey" },
  { name: "Niamey", country: "Niger", lat: 13.5137, lon: 2.1098, tz: "Africa/Niamey" },
  { name: "Tahoua", country: "Niger", lat: 14.8888, lon: 5.2692, tz: "Africa/Niamey" },
  { name: "Zinder", country: "Niger", lat: 13.8072, lon: 8.9881, tz: "Africa/Niamey" },
  { name: "Aba", country: "Nigeria", lat: 5.1066, lon: 7.3667, tz: "Africa/Lagos" },
  { name: "Abuja", country: "Nigeria", lat: 9.0579, lon: 7.4951, tz: "Africa/Lagos" },
  { name: "Benin City", country: "Nigeria", lat: 6.3381, lon: 5.6258, tz: "Africa/Lagos" },
  { name: "Ibadan", country: "Nigeria", lat: 7.3776, lon: 3.9059, tz: "Africa/Lagos" },
  { name: "Kaduna", country: "Nigeria", lat: 10.5264, lon: 7.4388, tz: "Africa/Lagos" },
  { name: "Kano", country: "Nigeria", lat: 12.0001, lon: 8.5167, tz: "Africa/Lagos" },
  { name: "Lagos", country: "Nigeria", lat: 6.4541, lon: 3.3947, tz: "Africa/Lagos" },
  { name: "Maiduguri", country: "Nigeria", lat: 11.8469, lon: 13.1571, tz: "Africa/Lagos" },
  { name: "Onitsha", country: "Nigeria", lat: 6.1498, lon: 6.7857, tz: "Africa/Lagos" },
  { name: "Port Harcourt", country: "Nigeria", lat: 4.7774, lon: 7.0134, tz: "Africa/Lagos" },
  { name: "Alofi", country: "Niue", lat: -19.0529, lon: -169.9196, tz: "Pacific/Niue" },
  { name: "Kingston", country: "Norfolk Island", lat: -29.0546, lon: 167.9663, tz: "Pacific/Norfolk" },
  { name: "Chongjin", country: "North Korea", lat: 41.7956, lon: 129.7758, tz: "Asia/Pyongyang" },
  { name: "Hamh\u016dng", country: "North Korea", lat: 39.9183, lon: 127.5364, tz: "Asia/Pyongyang" },
  { name: "H\u016dngnam", country: "North Korea", lat: 39.8420, lon: 127.6321, tz: "Asia/Pyongyang" },
  { name: "Kaech\u2019\u014fn", country: "North Korea", lat: 39.7006, lon: 125.8933, tz: "Asia/Pyongyang" },
  { name: "Kaes\u014fng", country: "North Korea", lat: 37.9708, lon: 126.5544, tz: "Asia/Pyongyang" },
  { name: "Man\u2019gy\u014fngdae-ri", country: "North Korea", lat: 38.9918, lon: 125.6587, tz: "Asia/Pyongyang" },
  { name: "Namp\u2019o", country: "North Korea", lat: 38.7375, lon: 125.4078, tz: "Asia/Pyongyang" },
  { name: "Pyongyang", country: "North Korea", lat: 39.0339, lon: 125.7543, tz: "Asia/Pyongyang" },
  { name: "Sunch\u2019\u014fn", country: "North Korea", lat: 39.4317, lon: 125.9328, tz: "Asia/Pyongyang" },
  { name: "W\u014fnsan", country: "North Korea", lat: 39.1528, lon: 127.4436, tz: "Asia/Pyongyang" },
  { name: "Bitola", country: "North Macedonia", lat: 41.0314, lon: 21.3347, tz: "Europe/Skopje" },
  { name: "Gostivar", country: "North Macedonia", lat: 41.7960, lon: 20.9082, tz: "Europe/Skopje" },
  { name: "Kisela Voda", country: "North Macedonia", lat: 41.9489, lon: 21.5028, tz: "Europe/Skopje" },
  { name: "Kumanovo", country: "North Macedonia", lat: 42.1322, lon: 21.7144, tz: "Europe/Skopje" },
  { name: "Prilep", country: "North Macedonia", lat: 41.3451, lon: 21.5550, tz: "Europe/Skopje" },
  { name: "Shtip", country: "North Macedonia", lat: 41.7458, lon: 22.1958, tz: "Europe/Skopje" },
  { name: "Skopje", country: "North Macedonia", lat: 41.9965, lon: 21.4314, tz: "Europe/Skopje" },
  { name: "Tetovo", country: "North Macedonia", lat: 42.0097, lon: 20.9716, tz: "Europe/Skopje" },
  { name: "Veles", country: "North Macedonia", lat: 41.7156, lon: 21.7756, tz: "Europe/Skopje" },
  { name: "\u010cair", country: "North Macedonia", lat: 42.0153, lon: 21.4411, tz: "Europe/Skopje" },
  { name: "Saipan", country: "Northern Mariana Islands", lat: 15.2123, lon: 145.7545, tz: "Pacific/Saipan" },
  { name: "Ask\u00f8y", country: "Norway", lat: 60.4430, lon: 5.1524, tz: "Europe/Oslo" },
  { name: "Bergen", country: "Norway", lat: 60.3930, lon: 5.3242, tz: "Europe/Oslo" },
  { name: "Drammen", country: "Norway", lat: 59.7439, lon: 10.2045, tz: "Europe/Oslo" },
  { name: "Fredrikstad", country: "Norway", lat: 59.2181, lon: 10.9298, tz: "Europe/Oslo" },
  { name: "Kleppest\u00f8", country: "Norway", lat: 60.4079, lon: 5.2341, tz: "Europe/Oslo" },
  { name: "Kristiansand", country: "Norway", lat: 58.1467, lon: 7.9956, tz: "Europe/Oslo" },
  { name: "Lillestr\u00f8m", country: "Norway", lat: 59.9560, lon: 11.0492, tz: "Europe/Oslo" },
  { name: "Molde", country: "Norway", lat: 62.7372, lon: 7.1599, tz: "Europe/Oslo" },
  { name: "Oslo", country: "Norway", lat: 59.9127, lon: 10.7461, tz: "Europe/Oslo" },
  { name: "Sandefjord", country: "Norway", lat: 59.1312, lon: 10.2166, tz: "Europe/Oslo" },
  { name: "Sandnes", country: "Norway", lat: 58.8524, lon: 5.7352, tz: "Europe/Oslo" },
  { name: "Stavanger", country: "Norway", lat: 58.9701, lon: 5.7333, tz: "Europe/Oslo" },
  { name: "Trondheim", country: "Norway", lat: 63.4305, lon: 10.3951, tz: "Europe/Oslo" },
  { name: "As Suwayq", country: "Oman", lat: 23.8494, lon: 57.4386, tz: "Asia/Muscat" },
  { name: "Bark\u0101\u2019", country: "Oman", lat: 23.6787, lon: 57.8860, tz: "Asia/Muscat" },
  { name: "Bawshar", country: "Oman", lat: 23.5777, lon: 58.3998, tz: "Asia/Muscat" },
  { name: "Muscat", country: "Oman", lat: 23.5841, lon: 58.4078, tz: "Asia/Muscat" },
  { name: "Rustaq", country: "Oman", lat: 23.3908, lon: 57.4244, tz: "Asia/Muscat" },
  { name: "Seeb", country: "Oman", lat: 23.6703, lon: 58.1891, tz: "Asia/Muscat" },
  { name: "Sohar", country: "Oman", lat: 24.3474, lon: 56.7094, tz: "Asia/Muscat" },
  { name: "\u015eal\u0101lah", country: "Oman", lat: 17.0150, lon: 54.0924, tz: "Asia/Muscat" },
  { name: "\u015ea\u1e29am", country: "Oman", lat: 24.1722, lon: 56.8886, tz: "Asia/Muscat" },
  { name: "\u2018Ibr\u012b", country: "Oman", lat: 23.2257, lon: 56.5157, tz: "Asia/Muscat" },
  { name: "Bannu", country: "Pakistan", lat: 32.9853, lon: 70.6040, tz: "Asia/Karachi" },
  { name: "Faisalabad", country: "Pakistan", lat: 31.4155, lon: 73.0897, tz: "Asia/Karachi" },
  { name: "Gujranwala", country: "Pakistan", lat: 32.1557, lon: 74.1870, tz: "Asia/Karachi" },
  { name: "Hyderabad", country: "Pakistan", lat: 25.3969, lon: 68.3772, tz: "Asia/Karachi" },
  { name: "Karachi", country: "Pakistan", lat: 24.8608, lon: 67.0104, tz: "Asia/Karachi" },
  { name: "Lahore", country: "Pakistan", lat: 31.5580, lon: 74.3507, tz: "Asia/Karachi" },
  { name: "Multan", country: "Pakistan", lat: 30.1968, lon: 71.4782, tz: "Asia/Karachi" },
  { name: "Peshawar", country: "Pakistan", lat: 34.0080, lon: 71.5785, tz: "Asia/Karachi" },
  { name: "Quetta", country: "Pakistan", lat: 30.1841, lon: 67.0014, tz: "Asia/Karachi" },
  { name: "Rawalpindi", country: "Pakistan", lat: 33.5973, lon: 73.0479, tz: "Asia/Karachi" },
  { name: "Ngerulmud", country: "Palau", lat: 7.5008, lon: 134.6238, tz: "Pacific/Palau" },
  { name: "Battir", country: "Palestinian Territory", lat: 31.7000, lon: 35.1167, tz: "Asia/Jerusalem" },
  { name: "Bayt L\u0101hy\u0101", country: "Palestinian Territory", lat: 31.5464, lon: 34.4951, tz: "Asia/Gaza" },
  { name: "Dayr al Bala\u1e29", country: "Palestinian Territory", lat: 31.4183, lon: 34.3493, tz: "Asia/Gaza" },
  { name: "East Jerusalem", country: "Palestinian Territory", lat: 31.7834, lon: 35.2339, tz: "Asia/Hebron" },
  { name: "Gaza", country: "Palestinian Territory", lat: 31.5016, lon: 34.4667, tz: "Asia/Gaza" },
  { name: "Hebron", country: "Palestinian Territory", lat: 31.5294, lon: 35.0938, tz: "Asia/Hebron" },
  { name: "Jab\u0101ly\u0101", country: "Palestinian Territory", lat: 31.5272, lon: 34.4835, tz: "Asia/Gaza" },
  { name: "Kh\u0101n Y\u016bnis", country: "Palestinian Territory", lat: 31.3402, lon: 34.3063, tz: "Asia/Gaza" },
  { name: "Nablus", country: "Palestinian Territory", lat: 32.2211, lon: 35.2544, tz: "Asia/Hebron" },
  { name: "Rafa\u1e29", country: "Palestinian Territory", lat: 31.2972, lon: 34.2436, tz: "Asia/Gaza" },
  { name: "Arraij\u00e1n", country: "Panama", lat: 8.9398, lon: -79.6404, tz: "America/Panama" },
  { name: "Col\u00f3n", country: "Panama", lat: 9.3603, lon: -79.9002, tz: "America/Panama" },
  { name: "David", country: "Panama", lat: 8.4273, lon: -82.4309, tz: "America/Panama" },
  { name: "Juan D\u00edaz", country: "Panama", lat: 9.0406, lon: -79.4408, tz: "America/Panama" },
  { name: "La Chorrera", country: "Panama", lat: 8.8776, lon: -79.7801, tz: "America/Panama" },
  { name: "Las Cumbres", country: "Panama", lat: 9.0921, lon: -79.5389, tz: "America/Panama" },
  { name: "Panam\u00e1", country: "Panama", lat: 8.9936, lon: -79.5197, tz: "America/Panama" },
  { name: "Pedregal", country: "Panama", lat: 9.0661, lon: -79.4258, tz: "America/Panama" },
  { name: "San Miguelito", country: "Panama", lat: 9.0503, lon: -79.4707, tz: "America/Panama" },
  { name: "Tocumen", country: "Panama", lat: 9.0894, lon: -79.3831, tz: "America/Panama" },
  { name: "Arawa", country: "Papua New Guinea", lat: -6.2298, lon: 155.5660, tz: "Pacific/Bougainville" },
  { name: "Goroka", country: "Papua New Guinea", lat: -6.0850, lon: 145.3867, tz: "Pacific/Port_Moresby" },
  { name: "Kimbe", country: "Papua New Guinea", lat: -5.5508, lon: 150.1377, tz: "Pacific/Port_Moresby" },
  { name: "Kokopo", country: "Papua New Guinea", lat: -4.3432, lon: 152.2687, tz: "Pacific/Port_Moresby" },
  { name: "Lae", country: "Papua New Guinea", lat: -6.7233, lon: 146.9961, tz: "Pacific/Port_Moresby" },
  { name: "Madang", country: "Papua New Guinea", lat: -5.2215, lon: 145.7869, tz: "Pacific/Port_Moresby" },
  { name: "Mendi", country: "Papua New Guinea", lat: -6.1475, lon: 143.6563, tz: "Pacific/Port_Moresby" },
  { name: "Mount Hagen", country: "Papua New Guinea", lat: -5.8575, lon: 144.2306, tz: "Pacific/Port_Moresby" },
  { name: "Popondetta", country: "Papua New Guinea", lat: -8.7654, lon: 148.2325, tz: "Pacific/Port_Moresby" },
  { name: "Port Moresby", country: "Papua New Guinea", lat: -9.4772, lon: 147.1509, tz: "Pacific/Port_Moresby" },
  { name: "Asunci\u00f3n", country: "Paraguay", lat: -25.2865, lon: -57.6470, tz: "America/Asuncion" },
  { name: "Capiat\u00e1", country: "Paraguay", lat: -25.3552, lon: -57.4455, tz: "America/Asuncion" },
  { name: "Ciudad del Este", country: "Paraguay", lat: -25.5036, lon: -54.6507, tz: "America/Asuncion" },
  { name: "Encarnaci\u00f3n", country: "Paraguay", lat: -27.3318, lon: -55.8666, tz: "America/Asuncion" },
  { name: "Fernando de la Mora", country: "Paraguay", lat: -25.3386, lon: -57.5217, tz: "America/Asuncion" },
  { name: "Lambar\u00e9", country: "Paraguay", lat: -25.3468, lon: -57.6065, tz: "America/Asuncion" },
  { name: "Limpio", country: "Paraguay", lat: -25.1661, lon: -57.4856, tz: "America/Asuncion" },
  { name: "Nemby", country: "Paraguay", lat: -25.3949, lon: -57.5357, tz: "America/Asuncion" },
  { name: "Pedro Juan Caballero", country: "Paraguay", lat: -22.5493, lon: -55.7387, tz: "America/Asuncion" },
  { name: "San Lorenzo", country: "Paraguay", lat: -25.3397, lon: -57.5088, tz: "America/Asuncion" },
  { name: "Arequipa", country: "Peru", lat: -16.3990, lon: -71.5375, tz: "America/Lima" },
  { name: "Callao", country: "Peru", lat: -12.0516, lon: -77.1345, tz: "America/Lima" },
  { name: "Chiclayo", country: "Peru", lat: -6.7701, lon: -79.8550, tz: "America/Lima" },
  { name: "Cusco", country: "Peru", lat: -13.5319, lon: -71.9670, tz: "America/Lima" },
  { name: "Huancayo", country: "Peru", lat: -12.0687, lon: -75.2103, tz: "America/Lima" },
  { name: "Iquitos", country: "Peru", lat: -3.7481, lon: -73.2529, tz: "America/Lima" },
  { name: "Lima", country: "Peru", lat: -12.0432, lon: -77.0282, tz: "America/Lima" },
  { name: "Piura", country: "Peru", lat: -5.1819, lon: -80.6572, tz: "America/Lima" },
  { name: "Pucallpa", country: "Peru", lat: -8.3791, lon: -74.5539, tz: "America/Lima" },
  { name: "Trujillo", country: "Peru", lat: -8.1160, lon: -79.0300, tz: "America/Lima" },
  { name: "Antipolo", country: "Philippines", lat: 14.6258, lon: 121.1225, tz: "Asia/Manila" },
  { name: "Budta", country: "Philippines", lat: 7.2042, lon: 124.4397, tz: "Asia/Manila" },
  { name: "Cagayan de Oro", country: "Philippines", lat: 8.4822, lon: 124.6472, tz: "Asia/Manila" },
  { name: "Caloocan City", country: "Philippines", lat: 14.6495, lon: 120.9679, tz: "Asia/Manila" },
  { name: "Cebu City", country: "Philippines", lat: 10.3167, lon: 123.8907, tz: "Asia/Manila" },
  { name: "Davao", country: "Philippines", lat: 7.0731, lon: 125.6128, tz: "Asia/Manila" },
  { name: "Malingao", country: "Philippines", lat: 7.1608, lon: 124.4750, tz: "Asia/Manila" },
  { name: "Manila", country: "Philippines", lat: 14.6042, lon: 120.9822, tz: "Asia/Manila" },
  { name: "Quezon City", country: "Philippines", lat: 14.6488, lon: 121.0509, tz: "Asia/Manila" },
  { name: "Zamboanga", country: "Philippines", lat: 6.9103, lon: 122.0739, tz: "Asia/Manila" },
  { name: "Adamstown", country: "Pitcairn", lat: -25.0660, lon: -130.1015, tz: "Pacific/Pitcairn" },
  { name: "Bydgoszcz", country: "Poland", lat: 53.1235, lon: 18.0076, tz: "Europe/Warsaw" },
  { name: "Gda\u0144sk", country: "Poland", lat: 54.3523, lon: 18.6491, tz: "Europe/Warsaw" },
  { name: "Katowice", country: "Poland", lat: 50.2584, lon: 19.0275, tz: "Europe/Warsaw" },
  { name: "Krak\u00f3w", country: "Poland", lat: 50.0614, lon: 19.9366, tz: "Europe/Warsaw" },
  { name: "Lublin", country: "Poland", lat: 51.2500, lon: 22.5667, tz: "Europe/Warsaw" },
  { name: "Pozna\u0144", country: "Poland", lat: 52.4069, lon: 16.9299, tz: "Europe/Warsaw" },
  { name: "Szczecin", country: "Poland", lat: 53.4289, lon: 14.5530, tz: "Europe/Warsaw" },
  { name: "Warsaw", country: "Poland", lat: 52.2298, lon: 21.0118, tz: "Europe/Warsaw" },
  { name: "Wroc\u0142aw", country: "Poland", lat: 51.1000, lon: 17.0333, tz: "Europe/Warsaw" },
  { name: "\u0141\u00f3d\u017a", country: "Poland", lat: 51.7706, lon: 19.4739, tz: "Europe/Warsaw" },
  { name: "Amadora", country: "Portugal", lat: 38.7538, lon: -9.2308, tz: "Europe/Lisbon" },
  { name: "Braga", country: "Portugal", lat: 41.5503, lon: -8.4200, tz: "Europe/Lisbon" },
  { name: "Cac\u00e9m", country: "Portugal", lat: 38.7670, lon: -9.2979, tz: "Europe/Lisbon" },
  { name: "Coimbra", country: "Portugal", lat: 40.2056, lon: -8.4195, tz: "Europe/Lisbon" },
  { name: "Funchal", country: "Portugal", lat: 32.6657, lon: -16.9255, tz: "Atlantic/Madeira" },
  { name: "Lisbon", country: "Portugal", lat: 38.7251, lon: -9.1498, tz: "Europe/Lisbon" },
  { name: "Porto", country: "Portugal", lat: 41.1496, lon: -8.6110, tz: "Europe/Lisbon" },
  { name: "Queluz", country: "Portugal", lat: 38.7566, lon: -9.2545, tz: "Europe/Lisbon" },
  { name: "Set\u00fabal", country: "Portugal", lat: 38.5244, lon: -8.8882, tz: "Europe/Lisbon" },
  { name: "Vila Nova de Gaia", country: "Portugal", lat: 41.1240, lon: -8.6124, tz: "Europe/Lisbon" },
  { name: "Arecibo", country: "Puerto Rico", lat: 18.4724, lon: -66.7157, tz: "America/Puerto_Rico" },
  { name: "Bayam\u00f3n", country: "Puerto Rico", lat: 18.3986, lon: -66.1557, tz: "America/Puerto_Rico" },
  { name: "Caguas", country: "Puerto Rico", lat: 18.2341, lon: -66.0485, tz: "America/Puerto_Rico" },
  { name: "Carolina", country: "Puerto Rico", lat: 18.3808, lon: -65.9574, tz: "America/Puerto_Rico" },
  { name: "Fajardo", country: "Puerto Rico", lat: 18.3258, lon: -65.6524, tz: "America/Puerto_Rico" },
  { name: "Guaynabo", country: "Puerto Rico", lat: 18.3575, lon: -66.1110, tz: "America/Puerto_Rico" },
  { name: "Mayag\u00fcez", country: "Puerto Rico", lat: 18.2011, lon: -67.1396, tz: "America/Puerto_Rico" },
  { name: "Ponce", country: "Puerto Rico", lat: 18.0103, lon: -66.6240, tz: "America/Puerto_Rico" },
  { name: "San Juan", country: "Puerto Rico", lat: 18.4663, lon: -66.1057, tz: "America/Puerto_Rico" },
  { name: "Trujillo Alto", country: "Puerto Rico", lat: 18.3547, lon: -66.0074, tz: "America/Puerto_Rico" },
  { name: "Al Man\u015f\u016brah", country: "Qatar", lat: 25.2681, lon: 51.5322, tz: "Asia/Qatar" },
  { name: "Al Ma\u0163\u0101r al \u2018At\u012bq", country: "Qatar", lat: 25.2514, lon: 51.5557, tz: "Asia/Qatar" },
  { name: "Al \u2018Unayzah", country: "Qatar", lat: 25.3376, lon: 51.5095, tz: "Asia/Qatar" },
  { name: "An Najmah", country: "Qatar", lat: 25.2670, lon: 51.5432, tz: "Asia/Qatar" },
  { name: "Ar Rayy\u0101n", country: "Qatar", lat: 25.2919, lon: 51.4244, tz: "Asia/Qatar" },
  { name: "As Sadd", country: "Qatar", lat: 25.2862, lon: 51.5007, tz: "Asia/Qatar" },
  { name: "Doha", country: "Qatar", lat: 25.2855, lon: 51.5310, tz: "Asia/Qatar" },
  { name: "Far\u012bj Kulayb", country: "Qatar", lat: 25.3131, lon: 51.4929, tz: "Asia/Qatar" },
  { name: "Mad\u012bnat Khal\u012bfah", country: "Qatar", lat: 25.3205, lon: 51.4779, tz: "Asia/Qatar" },
  { name: "Umm Ghuwayl\u012bnah", country: "Qatar", lat: 25.5872, lon: 51.1499, tz: "Asia/Qatar" },
  { name: "Brazzaville", country: "Republic of the Congo", lat: -4.2661, lon: 15.2832, tz: "Africa/Brazzaville" },
  { name: "Dolisie", country: "Republic of the Congo", lat: -4.1983, lon: 12.6666, tz: "Africa/Brazzaville" },
  { name: "Impfondo", country: "Republic of the Congo", lat: 1.6180, lon: 18.0598, tz: "Africa/Brazzaville" },
  { name: "Kayes", country: "Republic of the Congo", lat: -4.2049, lon: 13.2861, tz: "Africa/Brazzaville" },
  { name: "Lout\u00e9t\u00e9", country: "Republic of the Congo", lat: -4.2961, lon: 13.8508, tz: "Africa/Brazzaville" },
  { name: "Nkayi", country: "Republic of the Congo", lat: -4.1840, lon: 13.2859, tz: "Africa/Brazzaville" },
  { name: "Ou\u00e9sso", country: "Republic of the Congo", lat: 1.6136, lon: 16.0517, tz: "Africa/Brazzaville" },
  { name: "Owando", country: "Republic of the Congo", lat: -0.4819, lon: 15.8999, tz: "Africa/Brazzaville" },
  { name: "Pointe-Noire", country: "Republic of the Congo", lat: -4.7761, lon: 11.8635, tz: "Africa/Brazzaville" },
  { name: "Sibiti", country: "Republic of the Congo", lat: -3.6819, lon: 13.3498, tz: "Africa/Brazzaville" },
  { name: "La Possession", country: "Reunion", lat: -20.9291, lon: 55.3348, tz: "Indian/Reunion" },
  { name: "Le Tampon", country: "Reunion", lat: -21.2766, lon: 55.5177, tz: "Indian/Reunion" },
  { name: "Saint-Andr\u00e9", country: "Reunion", lat: -20.9633, lon: 55.6503, tz: "Indian/Reunion" },
  { name: "Saint-Beno\u00eet", country: "Reunion", lat: -21.0379, lon: 55.7155, tz: "Indian/Reunion" },
  { name: "Saint-Denis", country: "Reunion", lat: -20.8823, lon: 55.4504, tz: "Indian/Reunion" },
  { name: "Saint-Joseph", country: "Reunion", lat: -21.3777, lon: 55.6169, tz: "Indian/Reunion" },
  { name: "Saint-Leu", country: "Reunion", lat: -21.1706, lon: 55.2882, tz: "Indian/Reunion" },
  { name: "Saint-Louis", country: "Reunion", lat: -21.2858, lon: 55.4112, tz: "Indian/Reunion" },
  { name: "Saint-Paul", country: "Reunion", lat: -21.0096, lon: 55.2713, tz: "Indian/Reunion" },
  { name: "Saint-Pierre", country: "Reunion", lat: -21.3393, lon: 55.4781, tz: "Indian/Reunion" },
  { name: "Bra\u015fov", country: "Romania", lat: 45.6486, lon: 25.6061, tz: "Europe/Bucharest" },
  { name: "Bucharest", country: "Romania", lat: 44.4323, lon: 26.1063, tz: "Europe/Bucharest" },
  { name: "Cluj-Napoca", country: "Romania", lat: 46.7667, lon: 23.6000, tz: "Europe/Bucharest" },
  { name: "Constan\u0163a", country: "Romania", lat: 44.1807, lon: 28.6343, tz: "Europe/Bucharest" },
  { name: "Ia\u015fi", country: "Romania", lat: 47.1667, lon: 27.6000, tz: "Europe/Bucharest" },
  { name: "Sector 2", country: "Romania", lat: 44.4528, lon: 26.1332, tz: "Europe/Bucharest" },
  { name: "Sector 3", country: "Romania", lat: 44.4234, lon: 26.1687, tz: "Europe/Bucharest" },
  { name: "Sector 4", country: "Romania", lat: 44.3757, lon: 26.1209, tz: "Europe/Bucharest" },
  { name: "Sector 5", country: "Romania", lat: 44.3881, lon: 26.0714, tz: "Europe/Bucharest" },
  { name: "Sector 6", country: "Romania", lat: 44.4358, lon: 26.0165, tz: "Europe/Bucharest" },
  { name: "Chelyabinsk", country: "Russia", lat: 55.1540, lon: 61.4291, tz: "Asia/Yekaterinburg" },
  { name: "Kazan", country: "Russia", lat: 55.7887, lon: 49.1221, tz: "Europe/Moscow" },
  { name: "Moscow", country: "Russia", lat: 55.7522, lon: 37.6156, tz: "Europe/Moscow" },
  { name: "Nizhniy Novgorod", country: "Russia", lat: 56.3287, lon: 44.0020, tz: "Europe/Moscow" },
  { name: "Novosibirsk", country: "Russia", lat: 55.0344, lon: 82.9434, tz: "Asia/Novosibirsk" },
  { name: "Omsk", country: "Russia", lat: 54.9924, lon: 73.3686, tz: "Asia/Omsk" },
  { name: "Rostov-na-Donu", country: "Russia", lat: 47.2313, lon: 39.7233, tz: "Europe/Moscow" },
  { name: "Saint Petersburg", country: "Russia", lat: 59.9386, lon: 30.3141, tz: "Europe/Moscow" },
  { name: "Samara", country: "Russia", lat: 53.2001, lon: 50.1500, tz: "Europe/Samara" },
  { name: "Yekaterinburg", country: "Russia", lat: 56.8519, lon: 60.6122, tz: "Asia/Yekaterinburg" },
  { name: "Butare", country: "Rwanda", lat: -2.5967, lon: 29.7394, tz: "Africa/Kigali" },
  { name: "Cyangugu", country: "Rwanda", lat: -2.4846, lon: 28.9075, tz: "Africa/Kigali" },
  { name: "Gisenyi", country: "Rwanda", lat: -1.7028, lon: 29.2564, tz: "Africa/Kigali" },
  { name: "Gitarama", country: "Rwanda", lat: -2.0744, lon: 29.7567, tz: "Africa/Kigali" },
  { name: "Kibuye", country: "Rwanda", lat: -2.0603, lon: 29.3478, tz: "Africa/Kigali" },
  { name: "Kigali", country: "Rwanda", lat: -1.9500, lon: 30.0588, tz: "Africa/Kigali" },
  { name: "Muhanga", country: "Rwanda", lat: -2.0227, lon: 29.7069, tz: "Africa/Kigali" },
  { name: "Musanze", country: "Rwanda", lat: -1.4998, lon: 29.6350, tz: "Africa/Kigali" },
  { name: "Nyagatare", country: "Rwanda", lat: -1.2952, lon: 30.3226, tz: "Africa/Kigali" },
  { name: "Rwamagana", country: "Rwanda", lat: -1.9487, lon: 30.4347, tz: "Africa/Kigali" },
  { name: "Gustavia", country: "Saint Barthelemy", lat: 17.8962, lon: -62.8498, tz: "America/St_Barthelemy" },
  { name: "Jamestown", country: "Saint Helena", lat: -15.9249, lon: -5.7182, tz: "Atlantic/St_Helena" },
  { name: "Basseterre", country: "Saint Kitts and Nevis", lat: 17.2955, lon: -62.7250, tz: "America/St_Kitts" },
  { name: "Castries", country: "Saint Lucia", lat: 13.9957, lon: -61.0061, tz: "America/St_Lucia" },
  { name: "Gros Islet", country: "Saint Lucia", lat: 14.0667, lon: -60.9500, tz: "America/St_Lucia" },
  { name: "Marigot", country: "Saint Martin", lat: 18.0682, lon: -63.0830, tz: "America/Marigot" },
  { name: "Saint-Pierre", country: "Saint Pierre and Miquelon", lat: 46.7791, lon: -56.1773, tz: "America/Miquelon" },
  { name: "Calliaqua", country: "Saint Vincent and the Grenadines", lat: 13.1287, lon: -61.1918, tz: "America/St_Vincent" },
  { name: "Kingstown", country: "Saint Vincent and the Grenadines", lat: 13.1553, lon: -61.2274, tz: "America/St_Vincent" },
  { name: "Apia", country: "Samoa", lat: -13.8333, lon: -171.7667, tz: "Pacific/Apia" },
  { name: "San Marino", country: "San Marino", lat: 43.9367, lon: 12.4464, tz: "Europe/San_Marino" },
  { name: "S\u00e3o Tom\u00e9", country: "Sao Tome and Principe", lat: 0.3376, lon: 6.7299, tz: "Africa/Sao_Tome" },
  { name: "Buraydah", country: "Saudi Arabia", lat: 26.3260, lon: 43.9750, tz: "Asia/Riyadh" },
  { name: "Dammam", country: "Saudi Arabia", lat: 26.4344, lon: 50.1033, tz: "Asia/Riyadh" },
  { name: "Ha'il", country: "Saudi Arabia", lat: 27.5219, lon: 41.6907, tz: "Asia/Riyadh" },
  { name: "Jeddah", country: "Saudi Arabia", lat: 21.4901, lon: 39.1862, tz: "Asia/Riyadh" },
  { name: "Madinah", country: "Saudi Arabia", lat: 24.4686, lon: 39.6142, tz: "Asia/Riyadh" },
  { name: "Makkah", country: "Saudi Arabia", lat: 21.4266, lon: 39.8256, tz: "Asia/Riyadh" },
  { name: "Riyadh", country: "Saudi Arabia", lat: 24.6877, lon: 46.7219, tz: "Asia/Riyadh" },
  { name: "Sul\u0163\u0101nah", country: "Saudi Arabia", lat: 24.4926, lon: 39.5857, tz: "Asia/Riyadh" },
  { name: "Tabuk", country: "Saudi Arabia", lat: 28.3998, lon: 36.5715, tz: "Asia/Riyadh" },
  { name: "Ta\u2019if", country: "Saudi Arabia", lat: 21.2703, lon: 40.4158, tz: "Asia/Riyadh" },
  { name: "Dakar", country: "Senegal", lat: 14.6937, lon: -17.4441, tz: "Africa/Dakar" },
  { name: "Gu\u00e9diawaye", country: "Senegal", lat: 14.7745, lon: -17.4021, tz: "Africa/Dakar" },
  { name: "Kaolack", country: "Senegal", lat: 14.1520, lon: -16.0726, tz: "Africa/Dakar" },
  { name: "Mbour", country: "Senegal", lat: 14.4220, lon: -16.9638, tz: "Africa/Dakar" },
  { name: "Pikine", country: "Senegal", lat: 14.7646, lon: -17.3907, tz: "Africa/Dakar" },
  { name: "Rufisque", country: "Senegal", lat: 14.7154, lon: -17.2733, tz: "Africa/Dakar" },
  { name: "Saint-Louis", country: "Senegal", lat: 16.0179, lon: -16.4896, tz: "Africa/Dakar" },
  { name: "Thi\u00e8s", country: "Senegal", lat: 14.7894, lon: -16.9260, tz: "Africa/Dakar" },
  { name: "Thi\u00e8s Nones", country: "Senegal", lat: 14.7833, lon: -16.9667, tz: "Africa/Dakar" },
  { name: "Touba", country: "Senegal", lat: 14.8500, lon: -15.8833, tz: "Africa/Dakar" },
  { name: "Belgrade", country: "Serbia", lat: 44.8040, lon: 20.4651, tz: "Europe/Belgrade" },
  { name: "Kragujevac", country: "Serbia", lat: 44.0167, lon: 20.9167, tz: "Europe/Belgrade" },
  { name: "Kraljevo", country: "Serbia", lat: 43.7258, lon: 20.6894, tz: "Europe/Belgrade" },
  { name: "Leskovac", country: "Serbia", lat: 42.9981, lon: 21.9461, tz: "Europe/Belgrade" },
  { name: "Ni\u0161", country: "Serbia", lat: 43.3247, lon: 21.9033, tz: "Europe/Belgrade" },
  { name: "Novi Pazar", country: "Serbia", lat: 43.1367, lon: 20.5122, tz: "Europe/Belgrade" },
  { name: "Novi Sad", country: "Serbia", lat: 45.2517, lon: 19.8369, tz: "Europe/Belgrade" },
  { name: "Subotica", country: "Serbia", lat: 46.1000, lon: 19.6667, tz: "Europe/Belgrade" },
  { name: "Zemun", country: "Serbia", lat: 44.8458, lon: 20.4012, tz: "Europe/Belgrade" },
  { name: "\u010ca\u010dak", country: "Serbia", lat: 43.8914, lon: 20.3497, tz: "Europe/Belgrade" },
  { name: "Victoria", country: "Seychelles", lat: -4.6200, lon: 55.4550, tz: "Indian/Mahe" },
  { name: "Bo", country: "Sierra Leone", lat: 7.9647, lon: -11.7383, tz: "Africa/Freetown" },
  { name: "Freetown", country: "Sierra Leone", lat: 8.4871, lon: -13.2356, tz: "Africa/Freetown" },
  { name: "Goderich", country: "Sierra Leone", lat: 8.4276, lon: -13.2860, tz: "Africa/Freetown" },
  { name: "Kenema", country: "Sierra Leone", lat: 7.8769, lon: -11.1903, tz: "Africa/Freetown" },
  { name: "Koidu", country: "Sierra Leone", lat: 8.6439, lon: -10.9714, tz: "Africa/Freetown" },
  { name: "Lunsar", country: "Sierra Leone", lat: 8.6844, lon: -12.5350, tz: "Africa/Freetown" },
  { name: "Makeni", country: "Sierra Leone", lat: 8.8860, lon: -12.0442, tz: "Africa/Freetown" },
  { name: "Njala", country: "Sierra Leone", lat: 8.2323, lon: -12.0156, tz: "Africa/Freetown" },
  { name: "Port Loko", country: "Sierra Leone", lat: 8.7661, lon: -12.7870, tz: "Africa/Freetown" },
  { name: "Waterloo", country: "Sierra Leone", lat: 8.3389, lon: -13.0709, tz: "Africa/Freetown" },
  { name: "Bedok New Town", country: "Singapore", lat: 1.3264, lon: 103.9417, tz: "Asia/Singapore" },
  { name: "Hougang New Town", country: "Singapore", lat: 1.3567, lon: 103.8908, tz: "Asia/Singapore" },
  { name: "Jurong Town", country: "Singapore", lat: 1.3342, lon: 103.7228, tz: "Asia/Singapore" },
  { name: "Jurong West", country: "Singapore", lat: 1.3503, lon: 103.7228, tz: "Asia/Singapore" },
  { name: "Sengkang New Town", country: "Singapore", lat: 1.3917, lon: 103.8944, tz: "Asia/Singapore" },
  { name: "Singapore", country: "Singapore", lat: 1.2897, lon: 103.8501, tz: "Asia/Singapore" },
  { name: "Tampines Estate", country: "Singapore", lat: 1.3581, lon: 103.9403, tz: "Asia/Singapore" },
  { name: "Tampines New Town", country: "Singapore", lat: 1.3492, lon: 103.9497, tz: "Asia/Singapore" },
  { name: "Ulu Bedok", country: "Singapore", lat: 1.3333, lon: 103.9333, tz: "Asia/Singapore" },
  { name: "Woodlands", country: "Singapore", lat: 1.4380, lon: 103.7888, tz: "Asia/Singapore" },
  { name: "Philipsburg", country: "Sint Maarten", lat: 18.0260, lon: -63.0458, tz: "America/Lower_Princes" },
  { name: "Bansk\u00e1 Bystrica", country: "Slovakia", lat: 48.7395, lon: 19.1535, tz: "Europe/Bratislava" },
  { name: "Bratislava", country: "Slovakia", lat: 48.1482, lon: 17.1067, tz: "Europe/Bratislava" },
  { name: "Ko\u0161ice", country: "Slovakia", lat: 48.7139, lon: 21.2581, tz: "Europe/Bratislava" },
  { name: "Nitra", country: "Slovakia", lat: 48.3076, lon: 18.0845, tz: "Europe/Bratislava" },
  { name: "Petr\u017ealka", country: "Slovakia", lat: 48.1233, lon: 17.1287, tz: "Europe/Bratislava" },
  { name: "Poprad", country: "Slovakia", lat: 49.0614, lon: 20.2980, tz: "Europe/Bratislava" },
  { name: "Pre\u0161ov", country: "Slovakia", lat: 48.9984, lon: 21.2339, tz: "Europe/Bratislava" },
  { name: "Tren\u010d\u00edn", country: "Slovakia", lat: 48.8945, lon: 18.0444, tz: "Europe/Bratislava" },
  { name: "Trnava", country: "Slovakia", lat: 48.3774, lon: 17.5872, tz: "Europe/Bratislava" },
  { name: "\u017dilina", country: "Slovakia", lat: 49.2231, lon: 18.7394, tz: "Europe/Bratislava" },
  { name: "Celje", country: "Slovenia", lat: 46.2309, lon: 15.2604, tz: "Europe/Ljubljana" },
  { name: "Koper", country: "Slovenia", lat: 45.5469, lon: 13.7294, tz: "Europe/Ljubljana" },
  { name: "Kranj", country: "Slovenia", lat: 46.2389, lon: 14.3556, tz: "Europe/Ljubljana" },
  { name: "Ljubljana", country: "Slovenia", lat: 46.0511, lon: 14.5051, tz: "Europe/Ljubljana" },
  { name: "Maribor", country: "Slovenia", lat: 46.5547, lon: 15.6467, tz: "Europe/Ljubljana" },
  { name: "Novo Mesto", country: "Slovenia", lat: 45.8040, lon: 15.1689, tz: "Europe/Ljubljana" },
  { name: "Ptuj", country: "Slovenia", lat: 46.4201, lon: 15.8702, tz: "Europe/Ljubljana" },
  { name: "Trbovlje", country: "Slovenia", lat: 46.1550, lon: 15.0533, tz: "Europe/Ljubljana" },
  { name: "Velenje", country: "Slovenia", lat: 46.3592, lon: 15.1103, tz: "Europe/Ljubljana" },
  { name: "Honiara", country: "Solomon Islands", lat: -9.4333, lon: 159.9500, tz: "Pacific/Guadalcanal" },
  { name: "Baidoa", country: "Somalia", lat: 3.1138, lon: 43.6498, tz: "Africa/Mogadishu" },
  { name: "Berbera", country: "Somalia", lat: 10.4396, lon: 45.0143, tz: "Africa/Mogadishu" },
  { name: "Borama", country: "Somalia", lat: 9.9361, lon: 43.1828, tz: "Africa/Mogadishu" },
  { name: "Burao", country: "Somalia", lat: 9.5221, lon: 45.5336, tz: "Africa/Mogadishu" },
  { name: "Cabudwaaq", country: "Somalia", lat: 6.2458, lon: 46.2247, tz: "Africa/Addis_Ababa" },
  { name: "Hargeysa", country: "Somalia", lat: 9.5600, lon: 44.0650, tz: "Africa/Mogadishu" },
  { name: "Kismayo", country: "Somalia", lat: -0.3582, lon: 42.5454, tz: "Africa/Mogadishu" },
  { name: "Marka", country: "Somalia", lat: 1.7159, lon: 44.7717, tz: "Africa/Mogadishu" },
  { name: "Mogadishu", country: "Somalia", lat: 2.0371, lon: 45.3438, tz: "Africa/Mogadishu" },
  { name: "Ruqi", country: "Somalia", lat: 9.9729, lon: 43.4276, tz: "Africa/Mogadishu" },
  { name: "Benoni", country: "South Africa", lat: -26.1885, lon: 28.3208, tz: "Africa/Johannesburg" },
  { name: "Cape Town", country: "South Africa", lat: -33.9258, lon: 18.4232, tz: "Africa/Johannesburg" },
  { name: "Durban", country: "South Africa", lat: -29.8579, lon: 31.0292, tz: "Africa/Johannesburg" },
  { name: "Evaton", country: "South Africa", lat: -26.5333, lon: 27.8500, tz: "Africa/Johannesburg" },
  { name: "Johannesburg", country: "South Africa", lat: -26.2023, lon: 28.0436, tz: "Africa/Johannesburg" },
  { name: "Pietermaritzburg", country: "South Africa", lat: -29.6168, lon: 30.3928, tz: "Africa/Johannesburg" },
  { name: "Port Elizabeth", country: "South Africa", lat: -33.9611, lon: 25.6149, tz: "Africa/Johannesburg" },
  { name: "Pretoria", country: "South Africa", lat: -25.7449, lon: 28.1878, tz: "Africa/Johannesburg" },
  { name: "Soshanguve", country: "South Africa", lat: -25.4729, lon: 28.0992, tz: "Africa/Johannesburg" },
  { name: "Soweto", country: "South Africa", lat: -26.2678, lon: 27.8585, tz: "Africa/Johannesburg" },
  { name: "Grytviken", country: "South Georgia and the South Sandwich Islands", lat: -54.2811, lon: -36.5092, tz: "Atlantic/South_Georgia" },
  { name: "Busan", country: "South Korea", lat: 35.1017, lon: 129.0300, tz: "Asia/Seoul" },
  { name: "Changwon", country: "South Korea", lat: 35.2281, lon: 128.6811, tz: "Asia/Seoul" },
  { name: "Daegu", country: "South Korea", lat: 35.8703, lon: 128.5911, tz: "Asia/Seoul" },
  { name: "Daejeon", country: "South Korea", lat: 36.3491, lon: 127.3849, tz: "Asia/Seoul" },
  { name: "Goyang-si", country: "South Korea", lat: 37.6564, lon: 126.8350, tz: "Asia/Seoul" },
  { name: "Gwangju", country: "South Korea", lat: 35.1547, lon: 126.9156, tz: "Asia/Seoul" },
  { name: "Incheon", country: "South Korea", lat: 37.4565, lon: 126.7052, tz: "Asia/Seoul" },
  { name: "Seoul", country: "South Korea", lat: 37.5660, lon: 126.9784, tz: "Asia/Seoul" },
  { name: "Suwon", country: "South Korea", lat: 37.2911, lon: 127.0089, tz: "Asia/Seoul" },
  { name: "Ulsan", country: "South Korea", lat: 35.5372, lon: 129.3167, tz: "Asia/Seoul" },
  { name: "Aweil", country: "South Sudan", lat: 8.7619, lon: 27.3919, tz: "Africa/Juba" },
  { name: "Gogrial", country: "South Sudan", lat: 8.5332, lon: 28.1004, tz: "Africa/Juba" },
  { name: "Juba", country: "South Sudan", lat: 4.8517, lon: 31.5825, tz: "Africa/Juba" },
  { name: "Kuacjok", country: "South Sudan", lat: 8.3028, lon: 27.9800, tz: "Africa/Juba" },
  { name: "Malakal", country: "South Sudan", lat: 9.5334, lon: 31.6605, tz: "Africa/Juba" },
  { name: "Pajok", country: "South Sudan", lat: 3.8651, lon: 32.4821, tz: "Africa/Juba" },
  { name: "Wau", country: "South Sudan", lat: 7.7011, lon: 27.9897, tz: "Africa/Juba" },
  { name: "Winejok", country: "South Sudan", lat: 9.0122, lon: 27.5708, tz: "Africa/Juba" },
  { name: "Yambio", country: "South Sudan", lat: 4.5708, lon: 28.3942, tz: "Africa/Juba" },
  { name: "Yei", country: "South Sudan", lat: 4.0944, lon: 30.6764, tz: "Africa/Juba" },
  { name: "Alicante", country: "Spain", lat: 38.3452, lon: -0.4815, tz: "Europe/Madrid" },
  { name: "Barcelona", country: "Spain", lat: 41.3888, lon: 2.1590, tz: "Europe/Madrid" },
  { name: "Las Palmas de Gran Canaria", country: "Spain", lat: 28.0997, lon: -15.4134, tz: "Atlantic/Canary" },
  { name: "Madrid", country: "Spain", lat: 40.4165, lon: -3.7026, tz: "Europe/Madrid" },
  { name: "Murcia", country: "Spain", lat: 37.9870, lon: -1.1300, tz: "Europe/Madrid" },
  { name: "M\u00e1laga", country: "Spain", lat: 36.7202, lon: -4.4203, tz: "Europe/Madrid" },
  { name: "Palma", country: "Spain", lat: 39.5694, lon: 2.6502, tz: "Europe/Madrid" },
  { name: "Sevilla", country: "Spain", lat: 37.3828, lon: -5.9732, tz: "Europe/Madrid" },
  { name: "Valencia", country: "Spain", lat: 39.4739, lon: -0.3797, tz: "Europe/Madrid" },
  { name: "Zaragoza", country: "Spain", lat: 41.6561, lon: -0.8773, tz: "Europe/Madrid" },
  { name: "Colombo", country: "Sri Lanka", lat: 6.9355, lon: 79.8487, tz: "Asia/Colombo" },
  { name: "Dehiwala-Mount Lavinia", country: "Sri Lanka", lat: 6.8402, lon: 79.8712, tz: "Asia/Colombo" },
  { name: "Jaffna", country: "Sri Lanka", lat: 9.6684, lon: 80.0074, tz: "Asia/Colombo" },
  { name: "Kandy", country: "Sri Lanka", lat: 7.2906, lon: 80.6336, tz: "Asia/Colombo" },
  { name: "Maharagama", country: "Sri Lanka", lat: 6.8480, lon: 79.9265, tz: "Asia/Colombo" },
  { name: "Moratuwa", country: "Sri Lanka", lat: 6.7730, lon: 79.8816, tz: "Asia/Colombo" },
  { name: "Negombo", country: "Sri Lanka", lat: 7.2083, lon: 79.8358, tz: "Asia/Colombo" },
  { name: "Pita Kotte", country: "Sri Lanka", lat: 6.8905, lon: 79.9015, tz: "Asia/Colombo" },
  { name: "Sri Jayewardenepura Kotte", country: "Sri Lanka", lat: 6.8830, lon: 79.9071, tz: "Asia/Colombo" },
  { name: "Trincomalee", country: "Sri Lanka", lat: 8.5778, lon: 81.2289, tz: "Asia/Colombo" },
  { name: "Al Qadarif", country: "Sudan", lat: 14.0349, lon: 35.3834, tz: "Africa/Khartoum" },
  { name: "El Obeid", country: "Sudan", lat: 13.1842, lon: 30.2167, tz: "Africa/Khartoum" },
  { name: "Kassala", country: "Sudan", lat: 15.4510, lon: 36.4000, tz: "Africa/Khartoum" },
  { name: "Khartoum", country: "Sudan", lat: 15.5518, lon: 32.5324, tz: "Africa/Khartoum" },
  { name: "Khartoum North", country: "Sudan", lat: 15.6493, lon: 32.5346, tz: "Africa/Khartoum" },
  { name: "Kosti", country: "Sudan", lat: 13.1629, lon: 32.6635, tz: "Africa/Khartoum" },
  { name: "Nyala", country: "Sudan", lat: 12.0489, lon: 24.8807, tz: "Africa/Khartoum" },
  { name: "Omdurman", country: "Sudan", lat: 15.6445, lon: 32.4777, tz: "Africa/Khartoum" },
  { name: "Port Sudan", country: "Sudan", lat: 19.6175, lon: 37.2164, tz: "Africa/Khartoum" },
  { name: "Wad Medani", country: "Sudan", lat: 14.4012, lon: 33.5199, tz: "Africa/Khartoum" },
  { name: "Lelydorp", country: "Suriname", lat: 5.7000, lon: -55.2333, tz: "America/Paramaribo" },
  { name: "Paramaribo", country: "Suriname", lat: 5.8664, lon: -55.1668, tz: "America/Paramaribo" },
  { name: "Longyearbyen", country: "Svalbard and Jan Mayen", lat: 78.2233, lon: 15.6469, tz: "Arctic/Longyearbyen" },
  { name: "G\u00f6teborg", country: "Sweden", lat: 57.7072, lon: 11.9668, tz: "Europe/Stockholm" },
  { name: "Link\u00f6ping", country: "Sweden", lat: 58.4109, lon: 15.6216, tz: "Europe/Stockholm" },
  { name: "Malm\u00f6", country: "Sweden", lat: 55.6059, lon: 13.0007, tz: "Europe/Stockholm" },
  { name: "Sollentuna", country: "Sweden", lat: 59.4280, lon: 17.9509, tz: "Europe/Stockholm" },
  { name: "Stockholm", country: "Sweden", lat: 59.3294, lon: 18.0687, tz: "Europe/Stockholm" },
  { name: "S\u00f6dermalm", country: "Sweden", lat: 59.3128, lon: 18.0758, tz: "Europe/Stockholm" },
  { name: "Ume\u00e5", country: "Sweden", lat: 63.8284, lon: 20.2597, tz: "Europe/Stockholm" },
  { name: "Uppsala", country: "Sweden", lat: 59.8588, lon: 17.6389, tz: "Europe/Stockholm" },
  { name: "V\u00e4ster\u00e5s", country: "Sweden", lat: 59.6162, lon: 16.5528, tz: "Europe/Stockholm" },
  { name: "\u00d6rebro", country: "Sweden", lat: 59.2741, lon: 15.2066, tz: "Europe/Stockholm" },
  { name: "Basel", country: "Switzerland", lat: 47.5584, lon: 7.5733, tz: "Europe/Zurich" },
  { name: "Bern", country: "Switzerland", lat: 46.9481, lon: 7.4474, tz: "Europe/Zurich" },
  { name: "Biel/Bienne", country: "Switzerland", lat: 47.1371, lon: 7.2461, tz: "Europe/Zurich" },
  { name: "Gen\u00e8ve", country: "Switzerland", lat: 46.2022, lon: 6.1457, tz: "Europe/Zurich" },
  { name: "Lausanne", country: "Switzerland", lat: 46.5160, lon: 6.6328, tz: "Europe/Zurich" },
  { name: "Lugano", country: "Switzerland", lat: 46.0101, lon: 8.9600, tz: "Europe/Zurich" },
  { name: "Luzern", country: "Switzerland", lat: 47.0505, lon: 8.3064, tz: "Europe/Zurich" },
  { name: "Sankt Gallen", country: "Switzerland", lat: 47.4239, lon: 9.3748, tz: "Europe/Zurich" },
  { name: "Winterthur", country: "Switzerland", lat: 47.5056, lon: 8.7241, tz: "Europe/Zurich" },
  { name: "Z\u00fcrich", country: "Switzerland", lat: 47.3667, lon: 8.5500, tz: "Europe/Zurich" },
  { name: "Al B\u0101b", country: "Syria", lat: 36.3705, lon: 37.5157, tz: "Asia/Damascus" },
  { name: "Al \u1e28asakah", country: "Syria", lat: 36.5024, lon: 40.7477, tz: "Asia/Damascus" },
  { name: "Aleppo", country: "Syria", lat: 36.2012, lon: 37.1612, tz: "Asia/Damascus" },
  { name: "Ar Raqqah", country: "Syria", lat: 35.9528, lon: 39.0079, tz: "Asia/Damascus" },
  { name: "Damascus", country: "Syria", lat: 33.5102, lon: 36.2913, tz: "Asia/Damascus" },
  { name: "Deir ez-Zor", country: "Syria", lat: 35.3359, lon: 40.1408, tz: "Asia/Damascus" },
  { name: "Homs", country: "Syria", lat: 34.7240, lon: 36.7256, tz: "Asia/Damascus" },
  { name: "Latakia", country: "Syria", lat: 35.5312, lon: 35.7909, tz: "Asia/Damascus" },
  { name: "\u0162ar\u0163\u016bs", country: "Syria", lat: 34.8890, lon: 35.8866, tz: "Asia/Damascus" },
  { name: "\u1e28am\u0101h", country: "Syria", lat: 35.1318, lon: 36.7578, tz: "Asia/Damascus" },
  { name: "Banqiao", country: "Taiwan", lat: 25.0143, lon: 121.4672, tz: "Asia/Taipei" },
  { name: "Hsinchu", country: "Taiwan", lat: 24.8036, lon: 120.9686, tz: "Asia/Taipei" },
  { name: "Kaohsiung", country: "Taiwan", lat: 22.6163, lon: 120.3133, tz: "Asia/Taipei" },
  { name: "Keelung", country: "Taiwan", lat: 25.1309, lon: 121.7409, tz: "Asia/Taipei" },
  { name: "New Taipei City", country: "Taiwan", lat: 25.0620, lon: 121.4570, tz: "Asia/Taipei" },
  { name: "Taichung", country: "Taiwan", lat: 24.1469, lon: 120.6839, tz: "Asia/Taipei" },
  { name: "Tainan", country: "Taiwan", lat: 22.9908, lon: 120.2133, tz: "Asia/Taipei" },
  { name: "Taipei", country: "Taiwan", lat: 25.0531, lon: 121.5264, tz: "Asia/Taipei" },
  { name: "Taoyuan", country: "Taiwan", lat: 24.9896, lon: 121.3187, tz: "Asia/Taipei" },
  { name: "Taoyuan City", country: "Taiwan", lat: 24.9937, lon: 121.2970, tz: "Asia/Taipei" },
  { name: "Bokhtar", country: "Tajikistan", lat: 37.8367, lon: 68.7775, tz: "Asia/Dushanbe" },
  { name: "Dushanbe", country: "Tajikistan", lat: 38.5358, lon: 68.7790, tz: "Asia/Dushanbe" },
  { name: "Isfara", country: "Tajikistan", lat: 40.1265, lon: 70.6253, tz: "Asia/Dushanbe" },
  { name: "Istaravshan", country: "Tajikistan", lat: 39.9142, lon: 69.0033, tz: "Asia/Dushanbe" },
  { name: "Khujand", country: "Tajikistan", lat: 40.2826, lon: 69.6222, tz: "Asia/Dushanbe" },
  { name: "Konibodom", country: "Tajikistan", lat: 40.2941, lon: 70.4312, tz: "Asia/Dushanbe" },
  { name: "K\u016dlob", country: "Tajikistan", lat: 37.9146, lon: 69.7845, tz: "Asia/Dushanbe" },
  { name: "Qayroqqum", country: "Tajikistan", lat: 40.2660, lon: 69.7959, tz: "Asia/Dushanbe" },
  { name: "Tursunzoda", country: "Tajikistan", lat: 38.5127, lon: 68.2316, tz: "Asia/Dushanbe" },
  { name: "Vahdat", country: "Tajikistan", lat: 38.5563, lon: 69.0135, tz: "Asia/Dushanbe" },
  { name: "Arusha", country: "Tanzania", lat: -3.3667, lon: 36.6833, tz: "Africa/Dar_es_Salaam" },
  { name: "Dar es Salaam", country: "Tanzania", lat: -6.8235, lon: 39.2695, tz: "Africa/Dar_es_Salaam" },
  { name: "Dodoma", country: "Tanzania", lat: -6.1722, lon: 35.7395, tz: "Africa/Dar_es_Salaam" },
  { name: "Geita", country: "Tanzania", lat: -2.8725, lon: 32.2325, tz: "Africa/Dar_es_Salaam" },
  { name: "Kahama", country: "Tanzania", lat: -3.8333, lon: 32.6000, tz: "Africa/Dar_es_Salaam" },
  { name: "Mbeya", country: "Tanzania", lat: -8.9000, lon: 33.4500, tz: "Africa/Dar_es_Salaam" },
  { name: "Morogoro", country: "Tanzania", lat: -6.8210, lon: 37.6612, tz: "Africa/Dar_es_Salaam" },
  { name: "Mwanza", country: "Tanzania", lat: -2.5167, lon: 32.9000, tz: "Africa/Dar_es_Salaam" },
  { name: "Tanga", country: "Tanzania", lat: -5.0689, lon: 39.0988, tz: "Africa/Dar_es_Salaam" },
  { name: "Zanzibar", country: "Tanzania", lat: -6.1639, lon: 39.1979, tz: "Africa/Dar_es_Salaam" },
  { name: "Bang Khae", country: "Thailand", lat: 13.6920, lon: 100.4069, tz: "Asia/Bangkok" },
  { name: "Bangkok", country: "Thailand", lat: 13.7540, lon: 100.5014, tz: "Asia/Bangkok" },
  { name: "Chon Buri", country: "Thailand", lat: 13.3622, lon: 100.9835, tz: "Asia/Bangkok" },
  { name: "Hat Yai", country: "Thailand", lat: 7.0084, lon: 100.4767, tz: "Asia/Bangkok" },
  { name: "Mueang Nonthaburi", country: "Thailand", lat: 13.8607, lon: 100.5148, tz: "Asia/Bangkok" },
  { name: "Pak Kret", country: "Thailand", lat: 13.9130, lon: 100.4988, tz: "Asia/Bangkok" },
  { name: "Phra Pradaeng", country: "Thailand", lat: 13.6585, lon: 100.5336, tz: "Asia/Bangkok" },
  { name: "Sai Mai", country: "Thailand", lat: 13.9207, lon: 100.6455, tz: "Asia/Bangkok" },
  { name: "Samut Prakan", country: "Thailand", lat: 13.5976, lon: 100.5972, tz: "Asia/Bangkok" },
  { name: "Si Racha", country: "Thailand", lat: 13.1737, lon: 100.9311, tz: "Asia/Bangkok" },
  { name: "Almere Stad", country: "The Netherlands", lat: 52.3702, lon: 5.2141, tz: "Europe/Amsterdam" },
  { name: "Amsterdam", country: "The Netherlands", lat: 52.3740, lon: 4.8897, tz: "Europe/Amsterdam" },
  { name: "Breda", country: "The Netherlands", lat: 51.5866, lon: 4.7760, tz: "Europe/Amsterdam" },
  { name: "Eindhoven", country: "The Netherlands", lat: 51.4408, lon: 5.4778, tz: "Europe/Amsterdam" },
  { name: "Groningen", country: "The Netherlands", lat: 53.2192, lon: 6.5667, tz: "Europe/Amsterdam" },
  { name: "Nijmegen", country: "The Netherlands", lat: 51.8425, lon: 5.8528, tz: "Europe/Amsterdam" },
  { name: "Rotterdam", country: "The Netherlands", lat: 51.9225, lon: 4.4792, tz: "Europe/Amsterdam" },
  { name: "The Hague", country: "The Netherlands", lat: 52.0767, lon: 4.2986, tz: "Europe/Amsterdam" },
  { name: "Tilburg", country: "The Netherlands", lat: 51.5555, lon: 5.0913, tz: "Europe/Amsterdam" },
  { name: "Utrecht", country: "The Netherlands", lat: 52.0908, lon: 5.1222, tz: "Europe/Amsterdam" },
  { name: "Aileu", country: "Timor Leste", lat: -8.7281, lon: 125.5664, tz: "Asia/Dili" },
  { name: "Baukau", country: "Timor Leste", lat: -8.4757, lon: 126.4563, tz: "Asia/Dili" },
  { name: "Dili", country: "Timor Leste", lat: -8.5586, lon: 125.5736, tz: "Asia/Dili" },
  { name: "Likis\u00e1", country: "Timor Leste", lat: -8.5875, lon: 125.3419, tz: "Asia/Dili" },
  { name: "Lospalos", country: "Timor Leste", lat: -8.5217, lon: 126.9983, tz: "Asia/Dili" },
  { name: "Maliana", country: "Timor Leste", lat: -8.9917, lon: 125.2197, tz: "Asia/Dili" },
  { name: "Maubara", country: "Timor Leste", lat: -8.6119, lon: 125.2061, tz: "Asia/Dili" },
  { name: "Suai", country: "Timor Leste", lat: -9.3129, lon: 125.2565, tz: "Asia/Dili" },
  { name: "Venilale", country: "Timor Leste", lat: -8.6431, lon: 126.3783, tz: "Asia/Dili" },
  { name: "An\u00e9ho", country: "Togo", lat: 6.2280, lon: 1.5919, tz: "Africa/Lome" },
  { name: "Atakpam\u00e9", country: "Togo", lat: 7.5333, lon: 1.1333, tz: "Africa/Lome" },
  { name: "Bassar", country: "Togo", lat: 9.2502, lon: 0.7821, tz: "Africa/Lome" },
  { name: "Dapaong", country: "Togo", lat: 10.8622, lon: 0.2076, tz: "Africa/Lome" },
  { name: "Kara", country: "Togo", lat: 9.5511, lon: 1.1861, tz: "Africa/Lome" },
  { name: "Kpalim\u00e9", country: "Togo", lat: 6.9000, lon: 0.6333, tz: "Africa/Lome" },
  { name: "Lom\u00e9", country: "Togo", lat: 6.1287, lon: 1.2215, tz: "Africa/Lome" },
  { name: "Mango", country: "Togo", lat: 10.3592, lon: 0.4708, tz: "Africa/Lome" },
  { name: "Sokod\u00e9", country: "Togo", lat: 8.9833, lon: 1.1333, tz: "Africa/Lome" },
  { name: "Ts\u00e9vi\u00e9", country: "Togo", lat: 6.4261, lon: 1.2133, tz: "Africa/Lome" },
  { name: "Nuku\u2018alofa", country: "Tonga", lat: -21.1368, lon: -175.2011, tz: "Pacific/Tongatapu" },
  { name: "Arima", country: "Trinidad and Tobago", lat: 10.6374, lon: -61.2823, tz: "America/Port_of_Spain" },
  { name: "Chaguanas", country: "Trinidad and Tobago", lat: 10.5167, lon: -61.4167, tz: "America/Port_of_Spain" },
  { name: "Diego Martin", country: "Trinidad and Tobago", lat: 10.7208, lon: -61.5662, tz: "America/Port_of_Spain" },
  { name: "Laventille", country: "Trinidad and Tobago", lat: 10.6492, lon: -61.4989, tz: "America/Port_of_Spain" },
  { name: "Marabella", country: "Trinidad and Tobago", lat: 10.3062, lon: -61.4467, tz: "America/Port_of_Spain" },
  { name: "Mon Repos", country: "Trinidad and Tobago", lat: 10.2798, lon: -61.4459, tz: "America/Port_of_Spain" },
  { name: "Point Fortin", country: "Trinidad and Tobago", lat: 10.1741, lon: -61.6841, tz: "America/Port_of_Spain" },
  { name: "Port of Spain", country: "Trinidad and Tobago", lat: 10.6667, lon: -61.5189, tz: "America/Port_of_Spain" },
  { name: "Rio Claro", country: "Trinidad and Tobago", lat: 10.3059, lon: -61.1756, tz: "America/Port_of_Spain" },
  { name: "San Fernando", country: "Trinidad and Tobago", lat: 10.2797, lon: -61.4684, tz: "America/Port_of_Spain" },
  { name: "Aryanah", country: "Tunisia", lat: 36.8601, lon: 10.1934, tz: "Africa/Tunis" },
  { name: "Bizerte", country: "Tunisia", lat: 37.2744, lon: 9.8739, tz: "Africa/Tunis" },
  { name: "El Mourouj", country: "Tunisia", lat: 36.7136, lon: 10.2096, tz: "Africa/Tunis" },
  { name: "Gab\u00e8s", country: "Tunisia", lat: 33.8815, lon: 10.0982, tz: "Africa/Tunis" },
  { name: "Kairouan", country: "Tunisia", lat: 35.6781, lon: 10.0963, tz: "Africa/Tunis" },
  { name: "Sejoumi", country: "Tunisia", lat: 36.7558, lon: 10.1183, tz: "Africa/Tunis" },
  { name: "Sfax", country: "Tunisia", lat: 34.7406, lon: 10.7603, tz: "Africa/Tunis" },
  { name: "Sousse", country: "Tunisia", lat: 35.8254, lon: 10.6370, tz: "Africa/Tunis" },
  { name: "Sukrah", country: "Tunisia", lat: 36.8774, lon: 10.2468, tz: "Africa/Tunis" },
  { name: "Tunis", country: "Tunisia", lat: 36.8190, lon: 10.1658, tz: "Africa/Tunis" },
  { name: "Adana", country: "Turkey", lat: 36.9862, lon: 35.3253, tz: "Europe/Istanbul" },
  { name: "Ankara", country: "Turkey", lat: 39.9199, lon: 32.8543, tz: "Europe/Istanbul" },
  { name: "Antalya", country: "Turkey", lat: 36.9081, lon: 30.6956, tz: "Europe/Istanbul" },
  { name: "Bursa", country: "Turkey", lat: 40.1956, lon: 29.0601, tz: "Europe/Istanbul" },
  { name: "Diyarbak\u0131r", country: "Turkey", lat: 37.9136, lon: 40.2172, tz: "Europe/Istanbul" },
  { name: "Gaziantep", country: "Turkey", lat: 37.0594, lon: 37.3825, tz: "Europe/Istanbul" },
  { name: "Istanbul", country: "Turkey", lat: 41.0138, lon: 28.9497, tz: "Europe/Istanbul" },
  { name: "Kayseri", country: "Turkey", lat: 38.7322, lon: 35.4853, tz: "Europe/Istanbul" },
  { name: "Konya", country: "Turkey", lat: 37.8713, lon: 32.4846, tz: "Europe/Istanbul" },
  { name: "\u0130zmir", country: "Turkey", lat: 38.4127, lon: 27.1384, tz: "Europe/Istanbul" },
  { name: "Abadan", country: "Turkmenistan", lat: 38.0541, lon: 58.1972, tz: "Asia/Ashgabat" },
  { name: "Ashgabat", country: "Turkmenistan", lat: 37.9500, lon: 58.3833, tz: "Asia/Ashgabat" },
  { name: "Balkanabat", country: "Turkmenistan", lat: 39.5108, lon: 54.3671, tz: "Asia/Ashgabat" },
  { name: "Bayramaly", country: "Turkmenistan", lat: 37.6185, lon: 62.1671, tz: "Asia/Ashgabat" },
  { name: "Da\u015foguz", country: "Turkmenistan", lat: 41.8362, lon: 59.9666, tz: "Asia/Ashgabat" },
  { name: "Mary", country: "Turkmenistan", lat: 37.5938, lon: 61.8303, tz: "Asia/Ashgabat" },
  { name: "Tejen", country: "Turkmenistan", lat: 37.3834, lon: 60.5055, tz: "Asia/Ashgabat" },
  { name: "T\u00fcrkmenabat", country: "Turkmenistan", lat: 39.0733, lon: 63.5786, tz: "Asia/Ashgabat" },
  { name: "T\u00fcrkmenba\u015fy", country: "Turkmenistan", lat: 40.0222, lon: 52.9552, tz: "Asia/Ashgabat" },
  { name: "Yol\u00f6ten", country: "Turkmenistan", lat: 37.2989, lon: 62.3597, tz: "Asia/Ashgabat" },
  { name: "Cockburn Town", country: "Turks and Caicos Islands", lat: 21.4612, lon: -71.1419, tz: "America/Grand_Turk" },
  { name: "Providenciales", country: "Turks and Caicos Islands", lat: 21.7825, lon: -72.2521, tz: "America/Grand_Turk" },
  { name: "Funafuti", country: "Tuvalu", lat: -8.5243, lon: 179.1942, tz: "Pacific/Funafuti" },
  { name: "Charlotte Amalie", country: "U.S. Virgin Islands", lat: 18.3419, lon: -64.9307, tz: "America/St_Thomas" },
  { name: "Saint Croix", country: "U.S. Virgin Islands", lat: 17.7275, lon: -64.7470, tz: "America/St_Thomas" },
  { name: "Birmingham", country: "UK", lat: 52.4814, lon: -1.8998, tz: "Europe/London" },
  { name: "Bristol", country: "UK", lat: 51.4552, lon: -2.5966, tz: "Europe/London" },
  { name: "Cardiff", country: "UK", lat: 51.4800, lon: -3.1800, tz: "Europe/London" },
  { name: "Coventry", country: "UK", lat: 52.4068, lon: -1.5197, tz: "Europe/London" },
  { name: "Edinburgh", country: "UK", lat: 55.9521, lon: -3.1965, tz: "Europe/London" },
  { name: "Glasgow", country: "UK", lat: 55.8651, lon: -4.2576, tz: "Europe/London" },
  { name: "Leeds", country: "UK", lat: 53.7965, lon: -1.5478, tz: "Europe/London" },
  { name: "Liverpool", country: "UK", lat: 53.4106, lon: -2.9779, tz: "Europe/London" },
  { name: "London", country: "UK", lat: 51.5085, lon: -0.1257, tz: "Europe/London" },
  { name: "Manchester", country: "UK", lat: 53.4809, lon: -2.2374, tz: "Europe/London" },
  { name: "Sheffield", country: "UK", lat: 53.3830, lon: -1.4659, tz: "Europe/London" },
  { name: "Aberdeen, SD", country: "USA", lat: 45.4647, lon: -98.4865, tz: "America/Chicago" },
  { name: "Adams Morgan, DC", country: "USA", lat: 38.9215, lon: -77.0422, tz: "America/New_York" },
  { name: "Akron, OH", country: "USA", lat: 41.0814, lon: -81.5190, tz: "America/New_York" },
  { name: "Albany, NY", country: "USA", lat: 42.6526, lon: -73.7562, tz: "America/New_York" },
  { name: "Albuquerque, NM", country: "USA", lat: 35.0845, lon: -106.6511, tz: "America/Denver" },
  { name: "Allentown, PA", country: "USA", lat: 40.6084, lon: -75.4902, tz: "America/New_York" },
  { name: "Anchorage, AK", country: "USA", lat: 61.2181, lon: -149.9003, tz: "America/Anchorage" },
  { name: "Ann Arbor, MI", country: "USA", lat: 42.2776, lon: -83.7409, tz: "America/Detroit" },
  { name: "Annapolis, MD", country: "USA", lat: 38.9786, lon: -76.4918, tz: "America/New_York" },
  { name: "Arlington, VA", country: "USA", lat: 38.8810, lon: -77.1043, tz: "America/New_York" },
  { name: "Athens, GA", country: "USA", lat: 33.9609, lon: -83.3779, tz: "America/New_York" },
  { name: "Atlanta, GA", country: "USA", lat: 33.7490, lon: -84.3880, tz: "America/New_York" },
  { name: "Augusta, ME", country: "USA", lat: 44.3106, lon: -69.7795, tz: "America/New_York" },
  { name: "Aurora, CO", country: "USA", lat: 39.7294, lon: -104.8319, tz: "America/Denver" },
  { name: "Aurora, IL", country: "USA", lat: 41.7606, lon: -88.3201, tz: "America/Chicago" },
  { name: "Austin, TX", country: "USA", lat: 30.2672, lon: -97.7431, tz: "America/Chicago" },
  { name: "Badger, AK", country: "USA", lat: 64.8000, lon: -147.5333, tz: "America/Anchorage" },
  { name: "Baltimore, MD", country: "USA", lat: 39.2904, lon: -76.6122, tz: "America/New_York" },
  { name: "Bangor, ME", country: "USA", lat: 44.7988, lon: -68.7726, tz: "America/New_York" },
  { name: "Baton Rouge, LA", country: "USA", lat: 30.4433, lon: -91.1875, tz: "America/Chicago" },
  { name: "Bear, DE", country: "USA", lat: 39.6293, lon: -75.6583, tz: "America/New_York" },
  { name: "Bellevue, NE", country: "USA", lat: 41.1367, lon: -95.8908, tz: "America/Chicago" },
  { name: "Billings, MT", country: "USA", lat: 45.7833, lon: -108.5007, tz: "America/Denver" },
  { name: "Birmingham, AL", country: "USA", lat: 33.5207, lon: -86.8025, tz: "America/Chicago" },
  { name: "Bismarck, ND", country: "USA", lat: 46.8083, lon: -100.7837, tz: "America/Chicago" },
  { name: "Bloomington, MN", country: "USA", lat: 44.8408, lon: -93.2983, tz: "America/Chicago" },
  { name: "Boise, ID", country: "USA", lat: 43.6135, lon: -116.2035, tz: "America/Boise" },
  { name: "Boston, MA", country: "USA", lat: 42.3584, lon: -71.0598, tz: "America/New_York" },
  { name: "Bozeman, MT", country: "USA", lat: 45.6797, lon: -111.0386, tz: "America/Denver" },
  { name: "Bridgeport, CT", country: "USA", lat: 41.1792, lon: -73.1894, tz: "America/New_York" },
  { name: "Broken Arrow, OK", country: "USA", lat: 36.0526, lon: -95.7908, tz: "America/Chicago" },
  { name: "Brookings, SD", country: "USA", lat: 44.3114, lon: -96.7984, tz: "America/Chicago" },
  { name: "Brooklyn, NY", country: "USA", lat: 40.6501, lon: -73.9496, tz: "America/New_York" },
  { name: "Burlington, VT", country: "USA", lat: 44.4759, lon: -73.2121, tz: "America/New_York" },
  { name: "Butte, MT", country: "USA", lat: 46.0038, lon: -112.5347, tz: "America/Denver" },
  { name: "Carmel, IN", country: "USA", lat: 39.9784, lon: -86.1180, tz: "America/Indiana/Indianapolis" },
  { name: "Carson City, NV", country: "USA", lat: 39.1638, lon: -119.7674, tz: "America/Los_Angeles" },
  { name: "Casper, WY", country: "USA", lat: 42.8666, lon: -106.3131, tz: "America/Denver" },
  { name: "Cedar Rapids, IA", country: "USA", lat: 42.0083, lon: -91.6441, tz: "America/Chicago" },
  { name: "Chandler, AZ", country: "USA", lat: 33.3062, lon: -111.8413, tz: "America/Phoenix" },
  { name: "Charleston, SC", country: "USA", lat: 32.7763, lon: -79.9327, tz: "America/New_York" },
  { name: "Charleston, WV", country: "USA", lat: 38.3498, lon: -81.6326, tz: "America/New_York" },
  { name: "Charlotte, NC", country: "USA", lat: 35.2271, lon: -80.8431, tz: "America/New_York" },
  { name: "Chattanooga, TN", country: "USA", lat: 35.0456, lon: -85.3097, tz: "America/New_York" },
  { name: "Chesapeake, VA", country: "USA", lat: 36.8190, lon: -76.2749, tz: "America/New_York" },
  { name: "Cheyenne, WY", country: "USA", lat: 41.1400, lon: -104.8203, tz: "America/Denver" },
  { name: "Chicago, IL", country: "USA", lat: 41.8500, lon: -87.6500, tz: "America/Chicago" },
  { name: "Cincinnati, OH", country: "USA", lat: 39.1271, lon: -84.5144, tz: "America/New_York" },
  { name: "Cleveland, OH", country: "USA", lat: 41.4995, lon: -81.6954, tz: "America/New_York" },
  { name: "Colchester, VT", country: "USA", lat: 44.5439, lon: -73.1479, tz: "America/New_York" },
  { name: "Colorado Springs, CO", country: "USA", lat: 38.8339, lon: -104.8214, tz: "America/Denver" },
  { name: "Columbia, MD", country: "USA", lat: 39.2404, lon: -76.8394, tz: "America/New_York" },
  { name: "Columbia, MO", country: "USA", lat: 38.9517, lon: -92.3341, tz: "America/Chicago" },
  { name: "Columbia, SC", country: "USA", lat: 34.0007, lon: -81.0348, tz: "America/New_York" },
  { name: "Columbus, GA", country: "USA", lat: 32.4610, lon: -84.9877, tz: "America/New_York" },
  { name: "Columbus, OH", country: "USA", lat: 39.9612, lon: -82.9988, tz: "America/New_York" },
  { name: "Concord, NH", country: "USA", lat: 43.2081, lon: -71.5376, tz: "America/New_York" },
  { name: "Cranston, RI", country: "USA", lat: 41.7798, lon: -71.4373, tz: "America/New_York" },
  { name: "Dallas, TX", country: "USA", lat: 32.7831, lon: -96.8067, tz: "America/Chicago" },
  { name: "Davenport, IA", country: "USA", lat: 41.5236, lon: -90.5776, tz: "America/Chicago" },
  { name: "Denver, CO", country: "USA", lat: 39.7392, lon: -104.9847, tz: "America/Denver" },
  { name: "Derry Village, NH", country: "USA", lat: 42.8918, lon: -71.3120, tz: "America/New_York" },
  { name: "Des Moines, IA", country: "USA", lat: 41.6005, lon: -93.6091, tz: "America/Chicago" },
  { name: "Detroit, MI", country: "USA", lat: 42.3314, lon: -83.0457, tz: "America/Detroit" },
  { name: "Dover, DE", country: "USA", lat: 39.1582, lon: -75.5244, tz: "America/New_York" },
  { name: "Duluth, MN", country: "USA", lat: 46.7833, lon: -92.1066, tz: "America/Chicago" },
  { name: "Durham, NC", country: "USA", lat: 35.9940, lon: -78.8986, tz: "America/New_York" },
  { name: "Eagle River, AK", country: "USA", lat: 61.3214, lon: -149.5678, tz: "America/Anchorage" },
  { name: "East Concord, NH", country: "USA", lat: 43.2420, lon: -71.5381, tz: "America/New_York" },
  { name: "East Honolulu, HI", country: "USA", lat: 21.2891, lon: -157.7173, tz: "Pacific/Honolulu" },
  { name: "East Providence, RI", country: "USA", lat: 41.8137, lon: -71.3701, tz: "America/New_York" },
  { name: "Edison, NJ", country: "USA", lat: 40.5187, lon: -74.4121, tz: "America/New_York" },
  { name: "Elizabeth, NJ", country: "USA", lat: 40.6640, lon: -74.2107, tz: "America/New_York" },
  { name: "Enchanted Hills, NM", country: "USA", lat: 35.3368, lon: -106.5930, tz: "America/Denver" },
  { name: "Erie, PA", country: "USA", lat: 42.1292, lon: -80.0851, tz: "America/New_York" },
  { name: "Eugene, OR", country: "USA", lat: 44.0521, lon: -123.0867, tz: "America/Los_Angeles" },
  { name: "Evansville, IN", country: "USA", lat: 37.9748, lon: -87.5559, tz: "America/Chicago" },
  { name: "Fairbanks, AK", country: "USA", lat: 64.8378, lon: -147.7164, tz: "America/Anchorage" },
  { name: "Fargo, ND", country: "USA", lat: 46.8772, lon: -96.7898, tz: "America/Chicago" },
  { name: "Fayetteville, AR", country: "USA", lat: 36.0626, lon: -94.1574, tz: "America/Chicago" },
  { name: "Fort Collins, CO", country: "USA", lat: 40.5853, lon: -105.0844, tz: "America/Denver" },
  { name: "Fort Smith, AR", country: "USA", lat: 35.3859, lon: -94.3986, tz: "America/Chicago" },
  { name: "Fort Wayne, IN", country: "USA", lat: 41.1306, lon: -85.1289, tz: "America/Indiana/Indianapolis" },
  { name: "Fort Worth, TX", country: "USA", lat: 32.7254, lon: -97.3208, tz: "America/Chicago" },
  { name: "Frankfort, KY", country: "USA", lat: 38.2009, lon: -84.8733, tz: "America/New_York" },
  { name: "Frederick, MD", country: "USA", lat: 39.4143, lon: -77.4105, tz: "America/New_York" },
  { name: "Fresno, CA", country: "USA", lat: 36.7477, lon: -119.7724, tz: "America/Los_Angeles" },
  { name: "Germantown, MD", country: "USA", lat: 39.1732, lon: -77.2716, tz: "America/New_York" },
  { name: "Gilbert, AZ", country: "USA", lat: 33.3528, lon: -111.7890, tz: "America/Phoenix" },
  { name: "Gillette, WY", country: "USA", lat: 44.2911, lon: -105.5022, tz: "America/Denver" },
  { name: "Grand Forks, ND", country: "USA", lat: 47.9253, lon: -97.0328, tz: "America/Chicago" },
  { name: "Grand Island, NE", country: "USA", lat: 40.9250, lon: -98.3420, tz: "America/Chicago" },
  { name: "Grand Rapids, MI", country: "USA", lat: 42.9634, lon: -85.6681, tz: "America/Detroit" },
  { name: "Great Falls, MT", country: "USA", lat: 47.5002, lon: -111.3008, tz: "America/Denver" },
  { name: "Green Bay, WI", country: "USA", lat: 44.5192, lon: -88.0198, tz: "America/Chicago" },
  { name: "Greensboro, NC", country: "USA", lat: 36.0726, lon: -79.7920, tz: "America/New_York" },
  { name: "Gresham, OR", country: "USA", lat: 45.4982, lon: -122.4315, tz: "America/Los_Angeles" },
  { name: "Gulfport, MS", country: "USA", lat: 30.3674, lon: -89.0928, tz: "America/Chicago" },
  { name: "Harrisburg, PA", country: "USA", lat: 40.2737, lon: -76.8844, tz: "America/New_York" },
  { name: "Hartford, CT", country: "USA", lat: 41.7637, lon: -72.6851, tz: "America/New_York" },
  { name: "Hattiesburg, MS", country: "USA", lat: 31.3271, lon: -89.2903, tz: "America/Chicago" },
  { name: "Helena, MT", country: "USA", lat: 46.5927, lon: -112.0361, tz: "America/Denver" },
  { name: "Henderson, NV", country: "USA", lat: 36.0397, lon: -114.9819, tz: "America/Los_Angeles" },
  { name: "Hillsboro, OR", country: "USA", lat: 45.5229, lon: -122.9898, tz: "America/Los_Angeles" },
  { name: "Honolulu, HI", country: "USA", lat: 21.3069, lon: -157.8583, tz: "Pacific/Honolulu" },
  { name: "Houston, TX", country: "USA", lat: 29.7633, lon: -95.3633, tz: "America/Chicago" },
  { name: "Huntington, WV", country: "USA", lat: 38.4192, lon: -82.4451, tz: "America/New_York" },
  { name: "Huntsville, AL", country: "USA", lat: 34.7304, lon: -86.5859, tz: "America/Chicago" },
  { name: "Idaho Falls, ID", country: "USA", lat: 43.4666, lon: -112.0341, tz: "America/Boise" },
  { name: "Independence, MO", country: "USA", lat: 39.0911, lon: -94.4155, tz: "America/Chicago" },
  { name: "Indianapolis, IN", country: "USA", lat: 39.7684, lon: -86.1580, tz: "America/Indiana/Indianapolis" },
  { name: "Iowa City, IA", country: "USA", lat: 41.6611, lon: -91.5302, tz: "America/Chicago" },
  { name: "Ironville, KY", country: "USA", lat: 38.4565, lon: -82.6924, tz: "America/New_York" },
  { name: "Jackson, MS", country: "USA", lat: 32.2988, lon: -90.1848, tz: "America/Chicago" },
  { name: "Jacksonville, FL", country: "USA", lat: 30.3322, lon: -81.6556, tz: "America/New_York" },
  { name: "Jefferson City, MO", country: "USA", lat: 38.5767, lon: -92.1735, tz: "America/Chicago" },
  { name: "Jersey City, NJ", country: "USA", lat: 40.7282, lon: -74.0776, tz: "America/New_York" },
  { name: "Joliet, IL", country: "USA", lat: 41.5252, lon: -88.0834, tz: "America/Chicago" },
  { name: "Jonesboro, AR", country: "USA", lat: 35.8423, lon: -90.7043, tz: "America/Chicago" },
  { name: "Juneau, AK", country: "USA", lat: 58.3019, lon: -134.4197, tz: "America/Juneau" },
  { name: "Kalihi-Palama, HI", country: "USA", lat: 21.3261, lon: -157.8759, tz: "Pacific/Honolulu" },
  { name: "Kansas City, KS", country: "USA", lat: 39.1142, lon: -94.6275, tz: "America/Chicago" },
  { name: "Kansas City, MO", country: "USA", lat: 39.0997, lon: -94.5786, tz: "America/Chicago" },
  { name: "Kearney, NE", country: "USA", lat: 40.6995, lon: -99.0815, tz: "America/Chicago" },
  { name: "Kenosha, WI", country: "USA", lat: 42.5847, lon: -87.8212, tz: "America/Chicago" },
  { name: "Knoxville, TN", country: "USA", lat: 35.9606, lon: -83.9207, tz: "America/New_York" },
  { name: "Lakewood, CO", country: "USA", lat: 39.7047, lon: -105.0814, tz: "America/Denver" },
  { name: "Lansing, MI", country: "USA", lat: 42.7325, lon: -84.5555, tz: "America/Detroit" },
  { name: "Laramie, WY", country: "USA", lat: 41.3114, lon: -105.5911, tz: "America/Denver" },
  { name: "Las Cruces, NM", country: "USA", lat: 32.3123, lon: -106.7783, tz: "America/Denver" },
  { name: "Las Vegas, NV", country: "USA", lat: 36.1750, lon: -115.1372, tz: "America/Los_Angeles" },
  { name: "Lawton, OK", country: "USA", lat: 34.6087, lon: -98.3903, tz: "America/Chicago" },
  { name: "Lewiston, ME", country: "USA", lat: 44.1003, lon: -70.2148, tz: "America/New_York" },
  { name: "Lexington, KY", country: "USA", lat: 37.9887, lon: -84.4777, tz: "America/New_York" },
  { name: "Lexington-Fayette, KY", country: "USA", lat: 38.0498, lon: -84.4586, tz: "America/New_York" },
  { name: "Lincoln, NE", country: "USA", lat: 40.8000, lon: -96.6670, tz: "America/Chicago" },
  { name: "Little Rock, AR", country: "USA", lat: 34.7465, lon: -92.2896, tz: "America/Chicago" },
  { name: "Los Angeles, CA", country: "USA", lat: 34.0522, lon: -118.2437, tz: "America/Los_Angeles" },
  { name: "Louisville, KY", country: "USA", lat: 38.2542, lon: -85.7594, tz: "America/Kentucky/Louisville" },
  { name: "Lowell, MA", country: "USA", lat: 42.6334, lon: -71.3162, tz: "America/New_York" },
  { name: "Madison, WI", country: "USA", lat: 43.0731, lon: -89.4012, tz: "America/Chicago" },
  { name: "Makakilo / Kapolei / Honokai Hale, HI", country: "USA", lat: 21.3374, lon: -158.0968, tz: "Pacific/Honolulu" },
  { name: "Manchester, NH", country: "USA", lat: 42.9956, lon: -71.4548, tz: "America/New_York" },
  { name: "Manhattan, NY", country: "USA", lat: 40.7834, lon: -73.9663, tz: "America/New_York" },
  { name: "Meads, KY", country: "USA", lat: 38.4126, lon: -82.7091, tz: "America/New_York" },
  { name: "Memphis, TN", country: "USA", lat: 35.1495, lon: -90.0490, tz: "America/Chicago" },
  { name: "Meridian, ID", country: "USA", lat: 43.6121, lon: -116.3915, tz: "America/Boise" },
  { name: "Mesa, AZ", country: "USA", lat: 33.4223, lon: -111.8226, tz: "America/Phoenix" },
  { name: "Metairie Terrace, LA", country: "USA", lat: 29.9785, lon: -90.1640, tz: "America/Chicago" },
  { name: "Metairie, LA", country: "USA", lat: 29.9841, lon: -90.1529, tz: "America/Chicago" },
  { name: "Miami, FL", country: "USA", lat: 25.7743, lon: -80.1937, tz: "America/New_York" },
  { name: "Middletown, DE", country: "USA", lat: 39.4496, lon: -75.7163, tz: "America/New_York" },
  { name: "Milwaukee, WI", country: "USA", lat: 43.0389, lon: -87.9065, tz: "America/Chicago" },
  { name: "Minneapolis, MN", country: "USA", lat: 44.9800, lon: -93.2638, tz: "America/Chicago" },
  { name: "Minot, ND", country: "USA", lat: 48.2325, lon: -101.2963, tz: "America/Chicago" },
  { name: "Missoula, MT", country: "USA", lat: 46.8721, lon: -113.9940, tz: "America/Denver" },
  { name: "Mobile, AL", country: "USA", lat: 30.6944, lon: -88.0430, tz: "America/Chicago" },
  { name: "Montgomery, AL", country: "USA", lat: 32.3668, lon: -86.3000, tz: "America/Chicago" },
  { name: "Morgantown, WV", country: "USA", lat: 39.6295, lon: -79.9559, tz: "America/New_York" },
  { name: "Mount Pleasant, SC", country: "USA", lat: 32.7941, lon: -79.8626, tz: "America/New_York" },
  { name: "Nampa, ID", country: "USA", lat: 43.5407, lon: -116.5635, tz: "America/Boise" },
  { name: "Naperville, IL", country: "USA", lat: 41.7859, lon: -88.1473, tz: "America/Chicago" },
  { name: "Nashua, NH", country: "USA", lat: 42.7654, lon: -71.4676, tz: "America/New_York" },
  { name: "Nashville, TN", country: "USA", lat: 36.1659, lon: -86.7844, tz: "America/Chicago" },
  { name: "New Haven, CT", country: "USA", lat: 41.3081, lon: -72.9282, tz: "America/New_York" },
  { name: "New Orleans, LA", country: "USA", lat: 29.9547, lon: -90.0751, tz: "America/Chicago" },
  { name: "New South Memphis, TN", country: "USA", lat: 35.0868, lon: -90.0568, tz: "America/Chicago" },
  { name: "New York City, NY", country: "USA", lat: 40.7143, lon: -74.0060, tz: "America/New_York" },
  { name: "Newark, DE", country: "USA", lat: 39.6837, lon: -75.7497, tz: "America/New_York" },
  { name: "Newark, NJ", country: "USA", lat: 40.7357, lon: -74.1724, tz: "America/New_York" },
  { name: "Norfolk, VA", country: "USA", lat: 36.8468, lon: -76.2852, tz: "America/New_York" },
  { name: "Norman, OK", country: "USA", lat: 35.2226, lon: -97.4395, tz: "America/Chicago" },
  { name: "North Charleston, SC", country: "USA", lat: 32.8546, lon: -79.9748, tz: "America/New_York" },
  { name: "North Las Vegas, NV", country: "USA", lat: 36.1989, lon: -115.1175, tz: "America/Los_Angeles" },
  { name: "North Stamford, CT", country: "USA", lat: 41.1382, lon: -73.5435, tz: "America/New_York" },
  { name: "Oklahoma City, OK", country: "USA", lat: 35.4676, lon: -97.5164, tz: "America/Chicago" },
  { name: "Olathe, KS", country: "USA", lat: 38.8814, lon: -94.8191, tz: "America/Chicago" },
  { name: "Olympia, WA", country: "USA", lat: 47.0449, lon: -122.9017, tz: "America/Los_Angeles" },
  { name: "Omaha, NE", country: "USA", lat: 41.2563, lon: -95.9404, tz: "America/Chicago" },
  { name: "Orem, UT", country: "USA", lat: 40.2969, lon: -111.6946, tz: "America/Denver" },
  { name: "Orlando, FL", country: "USA", lat: 28.5383, lon: -81.3792, tz: "America/New_York" },
  { name: "Overland Park, KS", country: "USA", lat: 38.9822, lon: -94.6708, tz: "America/Chicago" },
  { name: "Paradise, NV", country: "USA", lat: 36.0972, lon: -115.1467, tz: "America/Los_Angeles" },
  { name: "Parkersburg, WV", country: "USA", lat: 39.2667, lon: -81.5615, tz: "America/New_York" },
  { name: "Paterson, NJ", country: "USA", lat: 40.9168, lon: -74.1718, tz: "America/New_York" },
  { name: "Pawtucket, RI", country: "USA", lat: 41.8787, lon: -71.3826, tz: "America/New_York" },
  { name: "Pearl City, HI", country: "USA", lat: 21.3973, lon: -157.9752, tz: "Pacific/Honolulu" },
  { name: "Philadelphia, PA", country: "USA", lat: 39.9524, lon: -75.1636, tz: "America/New_York" },
  { name: "Phoenix, AZ", country: "USA", lat: 33.4484, lon: -112.0740, tz: "America/Phoenix" },
  { name: "Pittsburgh, PA", country: "USA", lat: 40.4406, lon: -79.9959, tz: "America/New_York" },
  { name: "Pocatello, ID", country: "USA", lat: 42.8713, lon: -112.4455, tz: "America/Boise" },
  { name: "Portland, ME", country: "USA", lat: 43.6574, lon: -70.2589, tz: "America/New_York" },
  { name: "Portland, OR", country: "USA", lat: 45.5234, lon: -122.6762, tz: "America/Los_Angeles" },
  { name: "Providence, RI", country: "USA", lat: 41.8240, lon: -71.4128, tz: "America/New_York" },
  { name: "Provo, UT", country: "USA", lat: 40.2338, lon: -111.6585, tz: "America/Denver" },
  { name: "Queens, NY", country: "USA", lat: 40.6815, lon: -73.8365, tz: "America/New_York" },
  { name: "Racine, WI", country: "USA", lat: 42.7261, lon: -87.7828, tz: "America/Chicago" },
  { name: "Raleigh, NC", country: "USA", lat: 35.7721, lon: -78.6386, tz: "America/New_York" },
  { name: "Rapid City, SD", country: "USA", lat: 44.0805, lon: -103.2310, tz: "America/Denver" },
  { name: "Reading, PA", country: "USA", lat: 40.3357, lon: -75.9269, tz: "America/New_York" },
  { name: "Reno, NV", country: "USA", lat: 39.5296, lon: -119.8138, tz: "America/Los_Angeles" },
  { name: "Richmond, VA", country: "USA", lat: 37.5538, lon: -77.4603, tz: "America/New_York" },
  { name: "Rio Rancho, NM", country: "USA", lat: 35.2334, lon: -106.6645, tz: "America/Denver" },
  { name: "Rochester, MN", country: "USA", lat: 44.0216, lon: -92.4699, tz: "America/Chicago" },
  { name: "Rock Hill, SC", country: "USA", lat: 34.9249, lon: -81.0251, tz: "America/New_York" },
  { name: "Rock Springs, WY", country: "USA", lat: 41.5875, lon: -109.2029, tz: "America/Denver" },
  { name: "Rockford, IL", country: "USA", lat: 42.2711, lon: -89.0940, tz: "America/Chicago" },
  { name: "Rutland, VT", country: "USA", lat: 43.6106, lon: -72.9726, tz: "America/New_York" },
  { name: "Sacramento, CA", country: "USA", lat: 38.5816, lon: -121.4944, tz: "America/Los_Angeles" },
  { name: "Saint Paul, MN", country: "USA", lat: 44.9444, lon: -93.0933, tz: "America/Chicago" },
  { name: "Salem, OR", country: "USA", lat: 44.9429, lon: -123.0351, tz: "America/Los_Angeles" },
  { name: "Salt Lake City, UT", country: "USA", lat: 40.7608, lon: -111.8911, tz: "America/Denver" },
  { name: "San Antonio, TX", country: "USA", lat: 29.4241, lon: -98.4936, tz: "America/Chicago" },
  { name: "San Diego, CA", country: "USA", lat: 32.7157, lon: -117.1647, tz: "America/Los_Angeles" },
  { name: "San Francisco, CA", country: "USA", lat: 37.7749, lon: -122.4194, tz: "America/Los_Angeles" },
  { name: "San Jose, CA", country: "USA", lat: 37.3394, lon: -121.8950, tz: "America/Los_Angeles" },
  { name: "Santa Fe, NM", country: "USA", lat: 35.6870, lon: -105.9378, tz: "America/Denver" },
  { name: "Savannah, GA", country: "USA", lat: 32.0835, lon: -81.0998, tz: "America/New_York" },
  { name: "Seattle, WA", country: "USA", lat: 47.6062, lon: -122.3321, tz: "America/Los_Angeles" },
  { name: "Shaw, DC", country: "USA", lat: 38.9121, lon: -77.0214, tz: "America/New_York" },
  { name: "Shreveport, LA", country: "USA", lat: 32.5251, lon: -93.7502, tz: "America/Chicago" },
  { name: "Silver Spring, MD", country: "USA", lat: 38.9907, lon: -77.0261, tz: "America/New_York" },
  { name: "Sioux City, IA", country: "USA", lat: 42.5000, lon: -96.4003, tz: "America/Chicago" },
  { name: "Sioux Falls, SD", country: "USA", lat: 43.5500, lon: -96.7003, tz: "America/Chicago" },
  { name: "South Bend, IN", country: "USA", lat: 41.6834, lon: -86.2500, tz: "America/Indiana/Indianapolis" },
  { name: "South Boston, MA", country: "USA", lat: 42.3334, lon: -71.0495, tz: "America/New_York" },
  { name: "South Burlington, VT", country: "USA", lat: 44.4670, lon: -73.1710, tz: "America/New_York" },
  { name: "South Fulton, GA", country: "USA", lat: 33.5926, lon: -84.6729, tz: "America/New_York" },
  { name: "South Portland, ME", country: "USA", lat: 43.6415, lon: -70.2409, tz: "America/New_York" },
  { name: "Southaven, MS", country: "USA", lat: 34.9890, lon: -90.0126, tz: "America/Chicago" },
  { name: "Spokane, WA", country: "USA", lat: 47.6597, lon: -117.4291, tz: "America/Los_Angeles" },
  { name: "Springdale, AR", country: "USA", lat: 36.1867, lon: -94.1288, tz: "America/Chicago" },
  { name: "Springfield, IL", country: "USA", lat: 39.8017, lon: -89.6437, tz: "America/Chicago" },
  { name: "Springfield, MA", country: "USA", lat: 42.1015, lon: -72.5898, tz: "America/New_York" },
  { name: "Springfield, MO", country: "USA", lat: 37.2153, lon: -93.2982, tz: "America/Chicago" },
  { name: "St. Louis, MO", country: "USA", lat: 38.6273, lon: -90.1979, tz: "America/Chicago" },
  { name: "St. Petersburg, FL", country: "USA", lat: 27.7709, lon: -82.6793, tz: "America/New_York" },
  { name: "Stamford, CT", country: "USA", lat: 41.0534, lon: -73.5387, tz: "America/New_York" },
  { name: "Sterling Heights, MI", country: "USA", lat: 42.5803, lon: -83.0302, tz: "America/Detroit" },
  { name: "Tacoma, WA", country: "USA", lat: 47.2529, lon: -122.4443, tz: "America/Los_Angeles" },
  { name: "Tallahassee, FL", country: "USA", lat: 30.4383, lon: -84.2807, tz: "America/New_York" },
  { name: "Tampa, FL", country: "USA", lat: 27.9475, lon: -82.4584, tz: "America/New_York" },
  { name: "The Bronx, NY", country: "USA", lat: 40.8499, lon: -73.8664, tz: "America/New_York" },
  { name: "Toledo, OH", country: "USA", lat: 41.6639, lon: -83.5552, tz: "America/New_York" },
  { name: "Topeka, KS", country: "USA", lat: 39.0483, lon: -95.6780, tz: "America/Chicago" },
  { name: "Trenton, NJ", country: "USA", lat: 40.2171, lon: -74.7429, tz: "America/New_York" },
  { name: "Tri-Cities, WA", country: "USA", lat: 46.2454, lon: -119.1962, tz: "America/Los_Angeles" },
  { name: "Tucson, AZ", country: "USA", lat: 32.2217, lon: -110.9265, tz: "America/Phoenix" },
  { name: "Tulsa, OK", country: "USA", lat: 36.1540, lon: -95.9928, tz: "America/Chicago" },
  { name: "Tuscaloosa, AL", country: "USA", lat: 33.2098, lon: -87.5692, tz: "America/Chicago" },
  { name: "Vancouver, WA", country: "USA", lat: 45.6387, lon: -122.6615, tz: "America/Los_Angeles" },
  { name: "Virginia Beach, VA", country: "USA", lat: 36.8529, lon: -75.9780, tz: "America/New_York" },
  { name: "Warren, MI", country: "USA", lat: 42.4904, lon: -83.0130, tz: "America/Detroit" },
  { name: "Warwick, RI", country: "USA", lat: 41.7001, lon: -71.4162, tz: "America/New_York" },
  { name: "Washington, DC", country: "USA", lat: 38.8951, lon: -77.0364, tz: "America/New_York" },
  { name: "Watertown, SD", country: "USA", lat: 44.8994, lon: -97.1151, tz: "America/Chicago" },
  { name: "West Fargo, ND", country: "USA", lat: 46.8750, lon: -96.9004, tz: "America/Chicago" },
  { name: "West Gulfport, MS", country: "USA", lat: 30.4041, lon: -89.0942, tz: "America/Chicago" },
  { name: "West Jordan, UT", country: "USA", lat: 40.6097, lon: -111.9391, tz: "America/Denver" },
  { name: "West Raleigh, NC", country: "USA", lat: 35.7868, lon: -78.6639, tz: "America/New_York" },
  { name: "West Scarborough, ME", country: "USA", lat: 43.5704, lon: -70.3878, tz: "America/New_York" },
  { name: "West Valley City, UT", country: "USA", lat: 40.6916, lon: -112.0011, tz: "America/Denver" },
  { name: "Wheeling, WV", country: "USA", lat: 40.0640, lon: -80.7209, tz: "America/New_York" },
  { name: "Wichita, KS", country: "USA", lat: 37.6922, lon: -97.3375, tz: "America/Chicago" },
  { name: "Wilmington, DE", country: "USA", lat: 39.7460, lon: -75.5466, tz: "America/New_York" },
  { name: "Worcester, MA", country: "USA", lat: 42.2626, lon: -71.8023, tz: "America/New_York" },
  { name: "Bunamwaya", country: "Uganda", lat: 0.2534, lon: 32.5572, tz: "Africa/Kampala" },
  { name: "Gulu", country: "Uganda", lat: 2.7746, lon: 32.2990, tz: "Africa/Kampala" },
  { name: "Kampala", country: "Uganda", lat: 0.3163, lon: 32.5822, tz: "Africa/Kampala" },
  { name: "Kasangati", country: "Uganda", lat: 0.4380, lon: 32.6025, tz: "Africa/Kampala" },
  { name: "Kira", country: "Uganda", lat: 0.4000, lon: 32.6333, tz: "Africa/Kampala" },
  { name: "Kyengera", country: "Uganda", lat: 0.2952, lon: 32.5016, tz: "Africa/Kampala" },
  { name: "Mbarara", country: "Uganda", lat: -0.6047, lon: 30.6485, tz: "Africa/Kampala" },
  { name: "Mukono", country: "Uganda", lat: 0.3533, lon: 32.7553, tz: "Africa/Kampala" },
  { name: "Nansana", country: "Uganda", lat: 0.3639, lon: 32.5286, tz: "Africa/Kampala" },
  { name: "Njeru", country: "Uganda", lat: 0.4417, lon: 33.1792, tz: "Africa/Kampala" },
  { name: "Dnipro", country: "Ukraine", lat: 48.4666, lon: 35.0407, tz: "Europe/Kyiv" },
  { name: "Donetsk", country: "Ukraine", lat: 48.0230, lon: 37.8022, tz: "Europe/Kyiv" },
  { name: "Kharkiv", country: "Ukraine", lat: 49.9818, lon: 36.2548, tz: "Europe/Kyiv" },
  { name: "Kryvyy Rih", country: "Ukraine", lat: 47.9057, lon: 33.3940, tz: "Europe/Kyiv" },
  { name: "Kyiv", country: "Ukraine", lat: 50.4547, lon: 30.5238, tz: "Europe/Kyiv" },
  { name: "Lviv", country: "Ukraine", lat: 49.8383, lon: 24.0232, tz: "Europe/Kyiv" },
  { name: "Mykolayiv", country: "Ukraine", lat: 46.9763, lon: 31.9930, tz: "Europe/Kyiv" },
  { name: "Odesa", country: "Ukraine", lat: 46.4857, lon: 30.7438, tz: "Europe/Kyiv" },
  { name: "Sevastopol", country: "Ukraine", lat: 44.6080, lon: 33.5213, tz: "Europe/Simferopol" },
  { name: "Zaporizhzhya", country: "Ukraine", lat: 47.8517, lon: 35.1171, tz: "Europe/Kyiv" },
  { name: "Abu Dhabi", country: "United Arab Emirates", lat: 24.4512, lon: 54.3970, tz: "Asia/Dubai" },
  { name: "Ajman City", country: "United Arab Emirates", lat: 25.4018, lon: 55.4788, tz: "Asia/Dubai" },
  { name: "Al Ain City", country: "United Arab Emirates", lat: 24.1917, lon: 55.7606, tz: "Asia/Dubai" },
  { name: "Business Bay", country: "United Arab Emirates", lat: 25.1854, lon: 55.2697, tz: "Asia/Dubai" },
  { name: "Dayrah", country: "United Arab Emirates", lat: 25.2714, lon: 55.3021, tz: "Asia/Dubai" },
  { name: "Dubai", country: "United Arab Emirates", lat: 25.0772, lon: 55.3093, tz: "Asia/Dubai" },
  { name: "Jebel Ali", country: "United Arab Emirates", lat: 25.0025, lon: 55.1081, tz: "Asia/Dubai" },
  { name: "Musaffah", country: "United Arab Emirates", lat: 24.3589, lon: 54.4827, tz: "Asia/Dubai" },
  { name: "Ras Al Khaimah City", country: "United Arab Emirates", lat: 25.7895, lon: 55.9432, tz: "Asia/Dubai" },
  { name: "Sharjah", country: "United Arab Emirates", lat: 25.3374, lon: 55.4121, tz: "Asia/Dubai" },
  { name: "Artigas", country: "Uruguay", lat: -30.4043, lon: -56.4693, tz: "America/Montevideo" },
  { name: "Las Piedras", country: "Uruguay", lat: -34.7302, lon: -56.2191, tz: "America/Montevideo" },
  { name: "Maldonado", country: "Uruguay", lat: -34.8979, lon: -54.9502, tz: "America/Montevideo" },
  { name: "Melo", country: "Uruguay", lat: -32.3682, lon: -54.1641, tz: "America/Montevideo" },
  { name: "Mercedes", country: "Uruguay", lat: -33.2524, lon: -58.0305, tz: "America/Montevideo" },
  { name: "Montevideo", country: "Uruguay", lat: -34.9033, lon: -56.1882, tz: "America/Montevideo" },
  { name: "Paysand\u00fa", country: "Uruguay", lat: -32.3171, lon: -58.0807, tz: "America/Montevideo" },
  { name: "Rivera", country: "Uruguay", lat: -30.9053, lon: -55.5508, tz: "America/Montevideo" },
  { name: "Salto", country: "Uruguay", lat: -31.3881, lon: -57.9598, tz: "America/Montevideo" },
  { name: "Tacuaremb\u00f3", country: "Uruguay", lat: -31.7188, lon: -55.9793, tz: "America/Montevideo" },
  { name: "Andijon", country: "Uzbekistan", lat: 40.7834, lon: 72.3507, tz: "Asia/Tashkent" },
  { name: "Bukhara", country: "Uzbekistan", lat: 39.7703, lon: 64.4307, tz: "Asia/Samarkand" },
  { name: "Chilanzar", country: "Uzbekistan", lat: 41.2819, lon: 69.1811, tz: "Asia/Tashkent" },
  { name: "Fergana", country: "Uzbekistan", lat: 40.3842, lon: 71.7843, tz: "Asia/Tashkent" },
  { name: "Namangan", country: "Uzbekistan", lat: 40.9983, lon: 71.6726, tz: "Asia/Tashkent" },
  { name: "Nukus", country: "Uzbekistan", lat: 42.4586, lon: 59.6058, tz: "Asia/Samarkand" },
  { name: "Qarshi", country: "Uzbekistan", lat: 38.8606, lon: 65.7891, tz: "Asia/Samarkand" },
  { name: "Samarkand", country: "Uzbekistan", lat: 39.6546, lon: 66.9644, tz: "Asia/Samarkand" },
  { name: "Tashkent", country: "Uzbekistan", lat: 41.2647, lon: 69.2163, tz: "Asia/Tashkent" },
  { name: "Yunusobod", country: "Uzbekistan", lat: 41.3714, lon: 69.2794, tz: "Asia/Tashkent" },
  { name: "Port-Vila", country: "Vanuatu", lat: -17.7365, lon: 168.3137, tz: "Pacific/Efate" },
  { name: "Vatican City", country: "Vatican", lat: 41.9027, lon: 12.4541, tz: "Europe/Vatican" },
  { name: "Barcelona", country: "Venezuela", lat: 10.1384, lon: -64.6877, tz: "America/Caracas" },
  { name: "Barquisimeto", country: "Venezuela", lat: 10.0647, lon: -69.3570, tz: "America/Caracas" },
  { name: "Caracas", country: "Venezuela", lat: 10.4880, lon: -66.8792, tz: "America/Caracas" },
  { name: "Ciudad Bol\u00edvar", country: "Venezuela", lat: 8.1237, lon: -63.5469, tz: "America/Caracas" },
  { name: "Ciudad Guayana", country: "Venezuela", lat: 8.3512, lon: -62.6410, tz: "America/Caracas" },
  { name: "Cuman\u00e1", country: "Venezuela", lat: 10.4639, lon: -64.1786, tz: "America/Caracas" },
  { name: "Maracaibo", country: "Venezuela", lat: 10.6423, lon: -71.6109, tz: "America/Caracas" },
  { name: "Maracay", country: "Venezuela", lat: 10.2497, lon: -67.5948, tz: "America/Caracas" },
  { name: "Matur\u00edn", country: "Venezuela", lat: 9.7457, lon: -63.1832, tz: "America/Caracas" },
  { name: "Valencia", country: "Venezuela", lat: 10.1615, lon: -68.0004, tz: "America/Caracas" },
  { name: "Bi\u00ean H\u00f2a", country: "Vietnam", lat: 10.9447, lon: 106.8243, tz: "Asia/Ho_Chi_Minh" },
  { name: "C\u1ea7n Th\u01a1", country: "Vietnam", lat: 10.0371, lon: 105.7883, tz: "Asia/Ho_Chi_Minh" },
  { name: "Da Nang", country: "Vietnam", lat: 16.0678, lon: 108.2208, tz: "Asia/Ho_Chi_Minh" },
  { name: "Haiphong", country: "Vietnam", lat: 20.8648, lon: 106.6834, tz: "Asia/Bangkok" },
  { name: "Hanoi", country: "Vietnam", lat: 21.0245, lon: 105.8412, tz: "Asia/Bangkok" },
  { name: "Ho Chi Minh City", country: "Vietnam", lat: 10.8230, lon: 106.6296, tz: "Asia/Ho_Chi_Minh" },
  { name: "Hu\u1ebf", country: "Vietnam", lat: 16.4619, lon: 107.5955, tz: "Asia/Bangkok" },
  { name: "Thanh H\u00f3a", country: "Vietnam", lat: 19.8000, lon: 105.7667, tz: "Asia/Bangkok" },
  { name: "Thu\u1eadn An", country: "Vietnam", lat: 10.9239, lon: 106.7143, tz: "Asia/Ho_Chi_Minh" },
  { name: "Vinh", country: "Vietnam", lat: 18.6734, lon: 105.6923, tz: "Asia/Bangkok" },
  { name: "Mata-Utu", country: "Wallis and Futuna", lat: -13.2816, lon: -176.1745, tz: "Pacific/Wallis" },
  { name: "Boujdour", country: "Western Sahara", lat: 26.1307, lon: -14.4851, tz: "Africa/El_Aaiun" },
  { name: "Dakhla", country: "Western Sahara", lat: 23.6848, lon: -15.9580, tz: "Africa/El_Aaiun" },
  { name: "Laayoune", country: "Western Sahara", lat: 27.1418, lon: -13.1880, tz: "Africa/El_Aaiun" },
  { name: "Aden", country: "Yemen", lat: 12.7794, lon: 45.0367, tz: "Asia/Aden" },
  { name: "Al \u1e28udaydah", country: "Yemen", lat: 14.7978, lon: 42.9545, tz: "Asia/Aden" },
  { name: "Dham\u0101r", country: "Yemen", lat: 14.5427, lon: 44.4051, tz: "Asia/Aden" },
  { name: "Ibb", country: "Yemen", lat: 13.9667, lon: 44.1833, tz: "Asia/Aden" },
  { name: "Mukalla", country: "Yemen", lat: 14.5425, lon: 49.1242, tz: "Asia/Aden" },
  { name: "Sanaa", country: "Yemen", lat: 15.3545, lon: 44.2065, tz: "Asia/Aden" },
  { name: "Sayy\u0101n", country: "Yemen", lat: 15.1718, lon: 44.3244, tz: "Asia/Aden" },
  { name: "Taiz", country: "Yemen", lat: 13.5795, lon: 44.0209, tz: "Asia/Aden" },
  { name: "Zab\u012bd", country: "Yemen", lat: 14.1951, lon: 43.3152, tz: "Asia/Aden" },
  { name: "\u2018Amr\u0101n", country: "Yemen", lat: 15.6594, lon: 43.9438, tz: "Asia/Aden" },
  { name: "Chingola", country: "Zambia", lat: -12.5290, lon: 27.8838, tz: "Africa/Lusaka" },
  { name: "Chipata", country: "Zambia", lat: -13.6333, lon: 32.6500, tz: "Africa/Lusaka" },
  { name: "Kabwe", country: "Zambia", lat: -14.4469, lon: 28.4464, tz: "Africa/Lusaka" },
  { name: "Kasama", country: "Zambia", lat: -10.2129, lon: 31.1808, tz: "Africa/Lusaka" },
  { name: "Kitwe", country: "Zambia", lat: -12.8024, lon: 28.2132, tz: "Africa/Lusaka" },
  { name: "Livingstone", country: "Zambia", lat: -17.8419, lon: 25.8543, tz: "Africa/Lusaka" },
  { name: "Luanshya", country: "Zambia", lat: -13.1367, lon: 28.4166, tz: "Africa/Lusaka" },
  { name: "Lusaka", country: "Zambia", lat: -15.4067, lon: 28.2871, tz: "Africa/Lusaka" },
  { name: "Ndola", country: "Zambia", lat: -12.9587, lon: 28.6366, tz: "Africa/Lusaka" },
  { name: "Solwezi", country: "Zambia", lat: -12.1688, lon: 26.3894, tz: "Africa/Lusaka" },
  { name: "Bulawayo", country: "Zimbabwe", lat: -20.1500, lon: 28.5833, tz: "Africa/Harare" },
  { name: "Chinhoyi", country: "Zimbabwe", lat: -17.3667, lon: 30.2000, tz: "Africa/Harare" },
  { name: "Chitungwiza", country: "Zimbabwe", lat: -18.0127, lon: 31.0755, tz: "Africa/Harare" },
  { name: "Epworth", country: "Zimbabwe", lat: -17.8900, lon: 31.1475, tz: "Africa/Harare" },
  { name: "Gweru", country: "Zimbabwe", lat: -19.4500, lon: 29.8167, tz: "Africa/Harare" },
  { name: "Harare", country: "Zimbabwe", lat: -17.8277, lon: 31.0534, tz: "Africa/Harare" },
  { name: "Kadoma", country: "Zimbabwe", lat: -18.3333, lon: 29.9153, tz: "Africa/Harare" },
  { name: "Kwekwe", country: "Zimbabwe", lat: -18.9281, lon: 29.8149, tz: "Africa/Harare" },
  { name: "Mutare", country: "Zimbabwe", lat: -18.9707, lon: 32.6709, tz: "Africa/Harare" },
  { name: "Ruwa", country: "Zimbabwe", lat: -17.8897, lon: 31.2447, tz: "Africa/Harare" },
];

/* ================================================================
   SECTION 2 — APP STATE
   A single object that tracks what's currently selected.
================================================================ */
const state = {
  tab: 'city',           // 'city' | 'gps' | 'device'
  city: null,            // Selected city object from CITIES array
  deviceLat: null,       // Latitude from device geolocation
  deviceLon: null,       // Longitude from device geolocation
  resolvedTz: null,      // IANA timezone resolved for GPS/device coords
};

/* ================================================================
   SECTION 3 — DOM REFERENCES
   Grab all elements we need once at startup.
================================================================ */
const $ = id => document.getElementById(id);

const els = {
  // Tabs
  tabCity:   $('tab-city'),
  tabGps:    $('tab-gps'),
  tabDevice: $('tab-device'),
  // Panels
  panelCity:   $('panel-city'),
  panelGps:    $('panel-gps'),
  panelDevice: $('panel-device'),
  // City search
  citySearch:   $('city-search'),
  cityDropdown: $('city-dropdown'),
  citySelected: $('city-selected'),
  citySelectedText: $('city-selected-text'),
  cityClearBtn: $('city-clear-btn'),
  // GPS inputs
  latInput: $('lat-input'),
  lonInput: $('lon-input'),
  // Device panel
  getLocationBtn: $('get-location-btn'),
  deviceCoords:   $('device-coords'),
  // Date
  dateInput: $('date-input'),
  // Calculate
  calculateBtn: $('calculate-btn'),
  errorMsg:     $('error-msg'),
  // Results
  resultsCard:    $('results-card'),
  resCoords:  $('res-coords'),
  resDate:    $('res-date'),
  resTz:      $('res-tz'),
  resSunrise: $('res-sunrise'),
  resNoon:    $('res-noon'),
  resSunset:  $('res-sunset'),
  resDayLength: $('res-daylength'),
  dayBarFill: $('day-bar-fill'),
  // Loading
  loadingOverlay: $('loading-overlay'),
};

/* ================================================================
   SECTION 4 — INITIALISE UI
================================================================ */
function init() {
  // Set date picker to today's local date (YYYY-MM-DD format)
  els.dateInput.value = getTodayString();

  // Wire up tab click events
  [els.tabCity, els.tabGps, els.tabDevice].forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  // Wire up city search
  els.citySearch.addEventListener('input', onCityInput);
  els.citySearch.addEventListener('keydown', onCityKeydown);
  els.cityClearBtn.addEventListener('click', clearCity);

  // Close dropdown when clicking outside
  document.addEventListener('click', e => {
    if (!e.target.closest('.search-wrapper')) closeDropdown();
  });

  // Wire up device geolocation button
  els.getLocationBtn.addEventListener('click', requestDeviceLocation);

  // Calculate button
  els.calculateBtn.addEventListener('click', onCalculate);
}

/* ================================================================
   SECTION 5 — TAB SWITCHING
================================================================ */
function switchTab(tab) {
  state.tab = tab;

  // Update aria/visual state for all tabs
  [els.tabCity, els.tabGps, els.tabDevice].forEach(btn => {
    const isActive = btn.dataset.tab === tab;
    btn.classList.toggle('active', isActive);
    btn.setAttribute('aria-selected', isActive);
  });

  // Show / hide panels
  // Using the 'hidden' attribute (which CSS maps to display:none)
  els.panelCity.hidden   = (tab !== 'city');
  els.panelGps.hidden    = (tab !== 'gps');
  els.panelDevice.hidden = (tab !== 'device');
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
  const matches = CITIES.filter(c =>
    c.name.toLowerCase().includes(query) ||
    c.country.toLowerCase().includes(query)
  ).slice(0, 8); // Limit to 8 results for usability

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
    li.dataset.index = i;
    li.innerHTML = `
      <span class="city-name">${city.name}</span>
      <span class="city-country">${city.country}</span>
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

function setHighlight(index) {
  const items = els.cityDropdown.querySelectorAll('li');
  items.forEach((li, i) => li.classList.toggle('highlighted', i === index));
  highlightIndex = index;
}

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
  els.citySearch.focus();
}

function closeDropdown() {
  els.cityDropdown.setAttribute('hidden', '');
  els.citySearch.setAttribute('aria-expanded', 'false');
  els.cityDropdown.innerHTML = '';
}

/* ================================================================
   SECTION 7 — DEVICE GEOLOCATION
================================================================ */
function requestDeviceLocation() {
  if (!('geolocation' in navigator)) {
    showError('Geolocation is not supported by this browser.');
    return;
  }

  els.getLocationBtn.disabled = true;
  els.getLocationBtn.textContent = 'Requesting…';

  navigator.geolocation.getCurrentPosition(
    position => {
      state.deviceLat = position.coords.latitude;
      state.deviceLon = position.coords.longitude;
      state.resolvedTz = null; // Will be resolved on calculate

      // Show coordinates in the panel
      els.deviceCoords.textContent =
        `📍 ${state.deviceLat.toFixed(5)}°, ${state.deviceLon.toFixed(5)}°`;
      els.deviceCoords.removeAttribute('hidden');

      els.getLocationBtn.disabled = false;
      els.getLocationBtn.innerHTML = `<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M5.05 4.05a7 7 0 119.9 9.9L10 18.9l-4.95-4.95a7 7 0 010-9.9zM10 11a2 2 0 100-4 2 2 0 000 4z" clip-rule="evenodd"/></svg> Location Retrieved ✓`;
    },
    err => {
      els.getLocationBtn.disabled = false;
      els.getLocationBtn.innerHTML = `<svg viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M5.05 4.05a7 7 0 119.9 9.9L10 18.9l-4.95-4.95a7 7 0 010-9.9zM10 11a2 2 0 100-4 2 2 0 000 4z" clip-rule="evenodd"/></svg> Get My Location`;

      const messages = {
        1: 'Location access was denied. Please allow location in browser settings.',
        2: 'Location unavailable (device signal issue).',
        3: 'Location request timed out.',
      };
      showError(messages[err.code] || 'Unknown geolocation error.');
    },
    { timeout: 10000, maximumAge: 60000 }
  );
}

/* ================================================================
   SECTION 8 — TIMEZONE RESOLUTION FOR RAW GPS COORDS
   Uses Open-Meteo's forecast API which returns timezone info
   without charging or requiring an API key.
================================================================ */
async function resolveTimezone(lat, lon) {
  // Open-Meteo returns timezone info along with (empty) forecast data.
  // We request 0 forecast days to minimise response payload.
  const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&forecast_days=0&timezone=auto`;

  const response = await fetch(url);
  if (!response.ok) throw new Error(`Open-Meteo responded with ${response.status}`);

  const data = await response.json();

  // The API returns e.g. "timezone": "Europe/Oslo", "timezone_abbreviation": "CEST"
  if (!data.timezone) throw new Error('No timezone in Open-Meteo response');
  return { ianaId: data.timezone, abbreviation: data.timezone_abbreviation || '' };
}

/* ================================================================
   SECTION 9 — MAIN CALCULATE HANDLER
================================================================ */
async function onCalculate() {
  clearError();

  // --- 9.1: Gather lat/lon and timezone based on active tab ---
  let lat, lon, tzInfo;

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
    const latVal = parseFloat(els.latInput.value);
    const lonVal = parseFloat(els.lonInput.value);

    if (isNaN(latVal) || isNaN(lonVal)) {
      showError('Please enter valid numeric latitude and longitude values.');
      return;
    }
    if (latVal < -90 || latVal > 90) {
      showError('Latitude must be between −90 and 90.');
      return;
    }
    if (lonVal < -180 || lonVal > 180) {
      showError('Longitude must be between −180 and 180.');
      return;
    }

    lat = latVal;
    lon = lonVal;

    // --- Need API call to resolve timezone ---
    showLoading(true);
    try {
      tzInfo = await resolveTimezone(lat, lon);
    } catch (err) {
      showLoading(false);
      showError(`Could not resolve timezone for these coordinates: ${err.message}`);
      return;
    }
    showLoading(false);

  } else if (state.tab === 'device') {
    if (state.deviceLat === null) {
      showError('Please retrieve your device location first.');
      return;
    }

    lat = state.deviceLat;
    lon = state.deviceLon;

    showLoading(true);
    try {
      tzInfo = await resolveTimezone(lat, lon);
    } catch (err) {
      showLoading(false);
      showError(`Could not resolve timezone for your location: ${err.message}`);
      return;
    }
    showLoading(false);
  }

  // --- 9.2: Parse the selected date ---
  // The date input gives "YYYY-MM-DD". We construct a date at local
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
  // Check sun altitude at noon to decide midnight sun vs polar night
  let polarCondition = null;
  if (!sunriseValid || !sunsetValid) {
    const noonAltitude = SunCalc.getPosition(times.solarNoon, lat, lon).altitude;
    polarCondition = noonAltitude > 0 ? 'midnight-sun' : 'polar-night';
  }

  // --- 9.5: Format the times in the TARGET timezone (not browser's!) ---
  const tz = tzInfo.ianaId;

  const sunriseStr = sunriseValid ? formatTime(times.sunrise, tz) : null;
  const noonStr    = noonValid    ? formatTime(times.solarNoon, tz) : null;
  const sunsetStr  = sunsetValid  ? formatTime(times.sunset, tz)  : null;

  // --- 9.6: Calculate day length ---
  let dayLengthStr  = null;
  let dayLengthPct  = 0;

  if (sunriseValid && sunsetValid) {
    const ms = times.sunset - times.sunrise;
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
    locationLabel = `${state.city.name}, ${state.city.country}`;
  } else {
    locationLabel = `${lat.toFixed(4)}°, ${lon.toFixed(4)}°`;
  }

  // --- 9.8: Render results ---
  renderResults({
    lat, lon, tz, tzAbbr: tzInfo.abbreviation,
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


/* ================================================================
   SECTION 12 — UI STATE HELPERS
================================================================ */

/** Display an error message below the calculate button */
function showError(msg) {
  els.errorMsg.textContent = msg;
  els.errorMsg.removeAttribute('hidden');
}

/** Clear any visible error message */
function clearError() {
  els.errorMsg.setAttribute('hidden', '');
  els.errorMsg.textContent = '';
}

/** Show or hide the full-screen loading overlay */
function showLoading(show) {
  els.loadingOverlay.hidden = !show;
}

/* ================================================================
   SECTION 13 — BOOTSTRAP
================================================================ */
// Run init once the DOM is fully parsed (script is at end of body,
// so this is essentially immediate, but we guard with DOMContentLoaded).
document.addEventListener('DOMContentLoaded', init);
