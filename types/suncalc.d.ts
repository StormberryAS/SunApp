// Hand-written declarations for the vendored SunCalc 1.8.0 (suncalc.js), which index.html
// loads as a classic script before app.js and which publishes itself as window.SunCalc.
// Only the shapes the library really returns are declared. Never served.

interface SunCalcMoonPosition {
  /** Radians, measured from south towards west. */
  azimuth: number;
  /** Radians above the horizon, refraction included. */
  altitude: number;
  /** Kilometres from the centre of the Earth. */
  distance: number;
  parallacticAngle: number;
}

interface SunCalcMoonIllumination {
  /** Illuminated fraction, 0 to 1. */
  fraction: number;
  /** 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
  phase: number;
  /** Midpoint angle of the illuminated limb, radians. */
  angle: number;
}

/** getMoonTimes leaves out rise or set when it does not happen in the window. */
interface SunCalcMoonTimes {
  rise?: Date;
  set?: Date;
  alwaysUp?: true;
  alwaysDown?: true;
}

interface SunCalcSunPosition {
  azimuth: number;
  altitude: number;
}

/** Keys of the default table plus anything added with addTime. */
interface SunCalcTimes {
  solarNoon: Date;
  nadir: Date;
  sunrise: Date;
  sunset: Date;
  sunriseEnd: Date;
  sunsetStart: Date;
  dawn: Date;
  dusk: Date;
  nauticalDawn: Date;
  nauticalDusk: Date;
  nightEnd: Date;
  night: Date;
  goldenHourEnd: Date;
  goldenHour: Date;
  [extra: string]: Date;
}

interface SunCalcStatic {
  getPosition(date: Date, lat: number, lng: number): SunCalcSunPosition;
  getTimes(date: Date, lat: number, lng: number, height?: number): SunCalcTimes;
  addTime(angle: number, riseName: string, setName: string): void;
  times: Array<[number, string, string]>;
  getMoonPosition(date: Date, lat: number, lng: number): SunCalcMoonPosition;
  getMoonIllumination(date?: Date): SunCalcMoonIllumination;
  getMoonTimes(date: Date | number, lat: number, lng: number, inUTC?: boolean): SunCalcMoonTimes;
}

declare var SunCalc: SunCalcStatic;
