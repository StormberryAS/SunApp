# Changelog

Every released version of SunApp for Android, newest first. Each release's store note
(`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`) names the main updates and links
here. The APKs, their `.sha256` files and their build provenance attestations are on the
[releases page](https://github.com/StormberryAS/SunApp/releases).

## 1.1.6 (versionCode 8), 24 October 2026

The footer credits less, and only what matters. Nothing about how SunApp works changes: still nine
install-time permissions for the alarm, none new, no location permission and no internet access.

- **Credits:** the footer now names only the projects and data SunApp's function is built on: SunCalc's
  formulae (BSD 2-Clause) and the city data from GeoNames and Kartverket (CC BY 4.0), each linked to its
  source and its licence. The icons (Lucide), the programming language and libraries (Kotlin, Jetpack,
  Material, the Java time backport) and the Inter typeface are no longer listed there; they are credited
  in NOTICE and the README, with their licences. Each licence label now stays on one line.
- **Website:** the footer no longer lists the browser's own Intl API next to SunCalc and GeoNames.
- **Development only, not shipped:** the footer test now checks the shorter list and that NOTICE and
  the README still document everything left out.

## 1.1.5 (versionCode 7), 24 October 2026

A new top and bottom for the Sun times screen, laid out like CheckLink's. How SunApp works does not
change: still nine install-time permissions for the alarm, none new, no location permission and no
internet access.

- **Top:** the SunApp mark with its glow and the name now sit centred, one under the other, above the
  search card, with the website's own tagline under the name (SUNRISE · SOLAR NOON · SUNSET, then
  "anywhere on Earth" on a second line). The mark and name were beside each other, on the left.
- **Bottom:** the footer is rebuilt in CheckLink's style: the Stormberry name and tagline, a sentence
  crediting what SunApp is made from, an icon row (LinkedIn, GitHub, Nostr and the Tor onion site),
  the privacy line and "Alarm permissions only. No internet. No tracking.", the company line with the
  organisation number linking to the Brønnøysund register, Privacy and Disclaimer, and the date of the
  city list. The footer runs the full width of the screen and ends the page.
- **Credits:** the footer names SunCalc (BSD 2-Clause), the Inter typeface (OFL 1.1), the Jetpack and
  Kotlin libraries (Apache 2.0), the Java time backport that the app ships for Android 7 (GPL 2.0 with
  Classpath Exception), the Lucide LinkedIn and GitHub icons (ISC) and the city data from GeoNames and
  Kartverket (CC BY 4.0), each linked to its source and its licence. The full notices, and where the
  Nostr and Tor marks came from, are in NOTICE.
- **Links:** every footer link is handed to your browser, or for Nostr to a Nostr app, as before.
  With no app that can open one, a short message appears instead of an error.
- **Permissions:** the list has not changed and is in the README.
- **Development only, not shipped:** a test pins every footer address, the icon outlines and the
  tagline against the website. The old Stormberry logo drawable and its generator script are removed,
  as nothing uses them.

## 1.1.4 (versionCode 6), 22 October 2026

Alarms that are harder to lose.

- With notifications off or turned down, the alarm still rings; open SunApp to stop it.
- On Android 12 with exact alarms off, alarms ring through a notification instead of staying silent.
- Deleting or switching off an alarm cancels its snooze, and snoozes survive a restart.
- A second alarm reports the first as missed.

## 1.1.3 (versionCode 5), 17 October 2026

Fixes the alarm screen. On a locked phone, a ringing alarm showed only as a notification: the screen
stayed dark and the alarm screen with Snooze and Dismiss did not open. It now wakes the screen and
opens over the lock screen, for a snoozed alarm too. The tone and the vibration are unchanged.

## 1.1.2 (versionCode 4), 10 October 2026

- The whole app is set in the Inter typeface, the one the website uses. The fonts are bundled, so
  nothing is downloaded.
- On narrow phones, the example in the empty city search field no longer wraps onto a second line.

## 1.1.1 (versionCode 3), 5 September 2026

- The footer claimed "No permissions", which was not true: the APK declares nine, all for the alarm.
  It was corrected to "Alarm permissions only. No internet. No tracking."
- Privacy and Disclaimer became links in the footer, and the Stormberry logo opened stormberry.as.

## 1.1.0 (versionCode 2), 22 August 2026

Sunrise, solar noon and sunset for any city or coordinates, calculated on the device, and solar
alarms with the nine install-time permissions listed in the README.
