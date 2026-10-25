# SunApp for Android

Sunrise, solar noon and sunset for anywhere on Earth, calculated on the device.

The same maths and the same city catalogue run on the web at
[sun.stormberry.as](https://sun.stormberry.as). The Kotlin port is tested against
a golden corpus generated from the JavaScript original, so the two cannot
silently disagree about when the sun comes up.

## Install

Grab the APK from [Releases](https://github.com/StormberryAS/SunApp/releases), or
from [Zapstore](https://zapstore.dev).

There are two editions, two separate apps as far as Android is concerned:

| Package | Where | What it is | Signed by |
|---|---|---|---|
| `no.stormberry.sunapp` | GitHub Releases, Obtainium, Zapstore | the full app: sun times **and alarms**, nine install-time permissions | our own key, the certificate the NIP-C1 event vouches for |
| `no.stormberry.sunapp.play` | Google Play | sun times **without alarms**: no permission at all, no alarm code | Google, under Play App Signing, from a bundle we sign with a separate upload key |

They install side by side and **neither can ever update the other**, so nothing Google does
to the Play edition can reach an install that came from here. Everything below describes the
full app unless it says Play.

### Verify what you downloaded

The release carries a `.sha256` alongside it and a signed build attestation:

```sh
sha256sum -c SunApp-v1.1.0.apk.sha256
gh attestation verify SunApp-v1.1.0.apk -R StormberryAS/SunApp
apksigner verify --print-certs SunApp-v1.1.0.apk
```

And the claim worth checking yourself:

```sh
aapt dump permissions SunApp-v1.1.0.apk
```

You should see exactly the nine lines listed under [Permissions](#permissions)
and nothing else. In particular there is no `android.permission.INTERNET`, so
the app cannot reach the network even if it wanted to, and no location
permission of any kind.

## What it does

- Search 25,007 cities, each carrying its own IANA timezone. Accent-insensitive
  both ways: `tromso` finds Tromsø, `Herat` finds Herāt. English exonyms work
  too, so `Gothenburg` finds Göteborg and `Cologne` finds Köln.
- Enter raw coordinates for anywhere at all. The timezone is resolved from the
  bundled catalogue by nearest city, not from a web service.
- Any date, past or future.
- Midnight Sun and Polar Night are detected geometrically and labelled, rather
  than shown as a blank or an error.

## Permissions

Release 1.1.0 is the first release and adds solar alarms, and with them nine
permissions. The information-only 1.0.0 described in the implementation plan was
never cut as a separate artefact, so there is no zero-permission build on the
Releases page to compare against.

All nine are **install-time** permissions. Android provides no way to defer one,
so all nine appear in the F-Droid and Zapstore listing for 1.1.0 from the moment
you install it, whether or not you ever create an alarm. An alarm app that tells
you otherwise is describing something the platform cannot do.

What is genuinely deferred is every grant you can see or refuse:

- **No dialog appears and no special access is requested until you create your
  first alarm.** Searching cities, reading sun times and changing the date ask
  for nothing.
- On the first "Save alarm" the app shows one sheet, not three dialogs, saying
  plainly that these grants are needed for alarms and for nothing else.
- **On Android 7 through 11 nothing is ever requested at all**, because none of
  these grants exist on those versions.
- Refusing does not cost you the alarm. The rule is saved and armed anyway, and
  the alarm list states exactly what is degraded, for example "it may ring late
  or not at all" when exact alarms are switched off.

### The nine, one sentence each

| Permission | Why it is here |
|---|---|
| `USE_EXACT_ALARM` | Fires the alarm at the minute you set on Android 13 and later, granted at install because an alarm clock is this app's primary function. |
| `SCHEDULE_EXACT_ALARM` | The same capability on Android 12 and 12L, where `USE_EXACT_ALARM` does not exist yet; capped at `maxSdkVersion="32"` so no newer device is ever asked for it. |
| `POST_NOTIFICATIONS` | The alarm shows through a notification: on Android 13 and later this is what puts the alarm, with Snooze and Dismiss, on the screen and over the lock screen. Without it an alarm still sounds, and opening SunApp shows the alarm screen to stop it. It is the only runtime dialog in the app. |
| `RECEIVE_BOOT_COMPLETED` | A reboot erases every pending alarm, so they are recomputed and re-armed when the device comes back, including before the first unlock. |
| `WAKE_LOCK` | Keeps the CPU awake for the few seconds between the alarm broadcast landing and the ringing service taking over, so a dozing device cannot sleep through it. |
| `VIBRATE` | Vibrates while ringing; per alarm, and switchable off. |
| `USE_FULL_SCREEN_INTENT` | Puts the ring screen in front of the lock screen instead of a notification a sleeping person has to go looking for. |
| `FOREGROUND_SERVICE` | Ringing runs as a foreground service so the system cannot kill the audio part-way through. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The service type Android 14 and later require for that service; the alarm tone genuinely is media playback, routed through `AudioAttributes` `USAGE_ALARM`. |

### What is deliberately absent

- **No `INTERNET` and no `ACCESS_NETWORK_STATE`.** The sun maths, the city
  catalogue and the IANA zone identifiers are all compiled into the APK. Both
  are stripped from the merged manifest with `tools:node="remove"`, so a future
  dependency cannot quietly add them back.
- **No location permission, coarse or fine.** Coordinates come from the bundled
  city catalogue or from numbers you type. The app never asks the device where
  it is.
- **No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.** Alarms are armed with
  `setAlarmClock`, which is already exempt from Doze deferral, so asking would
  buy nothing.
- **No `SYSTEM_ALERT_WINDOW` and no `QUERY_ALL_PACKAGES`.**

The full list, with the same one-sentence justifications, is a comment block at
the top of `app/src/main/AndroidManifest.xml`. `expected-permissions.txt` is the
machine-readable copy that CI diffs the built APK against; see
[Permissions are diffed, not trusted](#permissions-are-diffed-not-trusted).

## When Android gets in the way of an alarm

Since 1.1.4 (ported from EclipseTracker's fix B) an alarm is harder to lose:

- **Notifications off, or the "Ringing alarms" or "Ringing alarms, backup"
  channel blocked or turned below High.** The alarm still sounds and vibrates,
  but Android may show nothing to stop it with. Opening SunApp then shows the
  alarm screen with Snooze and Dismiss, and the alarm list says so in its red
  banner, whose button opens the right setting (the channel's own page when
  only one channel is turned down) outside SunApp, so that setting never stands
  between you and a ringing alarm. It still stops by itself after ten minutes.
  On Android 12 with exact alarms also switched off, the alarm cannot sound
  until notifications are on again.
- **Android 12 and 12L with exact alarms switched off.** Switching them off
  cancels every exact alarm with no notice to the app. SunApp keeps an inexact
  backstop of the nearest alarm or snooze, which survives that, and sets every alarm again
  whenever it is opened. An inexact alarm may come late, and Android may refuse
  to let it start the ringing service, so it then rings through a backup
  notification on its own channel ("Ringing alarms, backup"): the phone's
  alarm sound, repeated, the alarm screen, Snooze and Dismiss. The alarm list
  says it "may ring late or not at all".
- **Snoozes.** A snooze is remembered on the device, so it survives a restart
  (it rings when the phone comes back, if that is at most ten minutes late, and
  otherwise leaves a "Missed" notice), and its snoozed notice comes back with
  it. A snooze that Android merely delivers late, as it may with exact alarms
  off, still rings, and so does one waiting through an app update, whose snoozed
  notice also comes back after the update. Deleting or switching off
  an alarm also cancels its snooze, and the snoozed notice has its own Dismiss.
- **Clock changes.** An alarm that has rung is not rung again when the clock is
  corrected back by up to an hour. If it rang because the clock had jumped
  forward past it, or the clock is set back by more than an hour, it rings again
  at its real time rather than being skipped.
- **One alarm at a time.** When a second alarm starts while one rings, the
  first is reported as missed once nothing rings, and a snooze that cannot be
  set leaves the alarm ringing rather than going quiet.

## Releases

**1.1.0 is the first release.** The information-only 1.0.0 described in the
implementation plan was never cut as a separate artefact: both were built in one
sitting, so there is no commit at which the tree is alarm-free.

The zero-permission build is the **Google Play edition**, from 1.1.7: the same source with the
alarm feature taken out (see "Publishing to Play" below). The split is path-based. Everything
alarm-related lives in `alarm/`, plus `data/AlarmStore.kt`, `ui/AlarmsScreen.kt`,
`ui/RuleEditorScreen.kt` and `ui/PermissionSheet.kt`; the only coupling is the navigation wiring
in `ui/App.kt`, `MainActivity.kt` and the first-run notice, each behind `Edition.ALARMS`.

## Build it yourself

```sh
cd android
./gradlew :app:assembleDebug
```

### Toolchain

AGP 9.3.1, Gradle 9.5.0, Kotlin 2.2.10, JDK 17 or later, compileSdk 37.1,
minSdk 24, targetSdk 36.

**On Debian, `java-25` is a JRE with no compiler**, and Gradle will pick it by
default and fail with `Toolchain installation ... does not provide the required
capabilities: [JAVA_COMPILER]`. That is not a fault in this project. Either
install `openjdk-25-jdk`, or build with:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug
```

### Core library desugaring

`minSdk` is 24 but `java.time` is API 26+, and every solar and alarm calculation
here is `java.time`. Desugaring is therefore load-bearing rather than a
convenience.

**Known consequence:** the desugar library carries its own copy of the timezone
database rather than reading Mainline updates. On API 24 and 25 only, a
government moving a DST transition can leave the zone rules stale until
`desugarJdkLibs` is bumped and the app is rebuilt. API 26 and above read the
system tzdb and are unaffected.

### The city catalogue

`app/src/main/assets/data/cities.tsv` is not in the repository. It is copied at
build time by the `copyCityData` Gradle task from `../data/cities.tsv`, which is
generated by `../update_cities.py` and shared with the web app and the eight
sibling Stormberry Labs apps. One source of truth; regenerate all of them
together.

`tools/check-city-parity.py` runs in CI before the build and fails if the
Android asset and the web `cities.js` have drifted apart.

Unlike the sibling UsernameGenerator, `cities.tsv` is **not** in `noCompress`.
It is roughly 900 KiB raw and 370 KiB deflated, `AssetManager` inflates it
transparently, and storing it uncompressed would add half a megabyte to the APK
for no measurable gain.

### Signing

Release builds are signed from `keystore.properties` (gitignored) locally, or
from `ORG_GRADLE_PROJECT_RELEASE_*` environment variables in CI. Without either,
the release variant builds unsigned rather than failing, and CI catches that
with `apksigner verify`.

Google Play uploads are signed with a **separate upload key**, never the release key: three more
properties in the same file, `playUploadStoreFile`, `playUploadStoreType` and `playUploadKeyAlias`,
and the two passwords from the keyring as `PLAY_UPLOAD_STORE_PASSWORD` and `PLAY_UPLOAD_KEY_PASSWORD`,
which are never written to the file. It signs the bundle so Play can tell
who uploaded it and never anything a user installs, which is why losing it means an upload key
reset in the Play Console rather than an app that can never be updated again. The build refuses
to configure when the two keystores are the same file.

### Permissions are diffed, not trusted

`expected-permissions.txt` lists every permission the APK may declare, nine of
them as of 1.1.0. CI diffs the built APK against it and fails in **both**
directions. An unexpected permission is the obvious failure; a listed one that
has vanished matters just as much, because a missing `RECEIVE_BOOT_COMPLETED`
would not break the build, it would quietly make every alarm die at the next
reboot.

The comparison is against `aapt dump permissions`, which reports names only, so
`SCHEDULE_EXACT_ALARM`'s `maxSdkVersion="32"` cap is invisible to it and lives
solely in the manifest. Change that file and the manifest in the same commit,
never one without the other.

The Play edition declares **no permission**, and that is checked too: `EditionTest` compares its
manifest overlay (`app/src/edition-play/AndroidManifest.xml`, all removals) with this file and with
the main manifest, and `tools/check-play-bundle.sh` reads the built bundle's manifest.

### Publishing to Play

```bash
cd android
PLAY_UPLOAD_STORE_PASSWORD="$(secret-tool lookup service android-keystore app sunapp-play-upload)" \
PLAY_UPLOAD_KEY_PASSWORD="$PLAY_UPLOAD_STORE_PASSWORD" \
  ./gradlew --no-configuration-cache :app:bundleRelease -PplayBuild=true
# app/build/outputs/bundle/release/sunapp-play-release.aab
tools/check-play-bundle.sh app/build/outputs/bundle/release/sunapp-play-release.aab 1.1.7 1009
```

`-PplayBuild=true` changes the application ID, the signing key, the version code, the source
set (`Edition.ALARMS` becomes false) and the manifest (the overlay removes the nine permissions
and the five alarm components). Gradle product flavours are not used: a flavour-level
`signingConfig` is overridden by the build type's, and a flavour dimension renames every task and
breaks the APK path in `.github/workflows/android-release.yml` and the regex in `zapstore.yaml`.
Two guards sit on the release tasks, because the failure modes are permanent:

- **`bundleRelease` without the flag fails.** That AAB would carry the sovereign application ID,
  alarms included, signed with the release key; uploading it would permanently claim that package
  on Play and enrol our release certificate into Play App Signing.
- **`assembleRelease` with the flag fails.** Play takes the bundle. (An installable test copy is
  made from the bundle with `bundletool build-apks --mode=universal`, signed with the upload key.)

**Two number lines, one codebase.** The Play build shows the same version name and its code is 1000
plus the sovereign code, so a build's channel is obvious from its code. What must stay true is the
mapping, and **every Play upload is built from a tag that shipped on GitHub Releases**; never
respin Play from an untagged tree.

| Sovereign tag | Sovereign version | Play version | Play code |
|---|---|---|---|
| `android-v1.1.7` | 1.1.7 (code 9) | 1.1.7 | 1009 |

### Releasing

Tag `android-vX.Y.Z` and push. The workflow runs the unit tests, lints, builds,
verifies the signature and the permission allowlist, publishes a provenance
attestation, and creates the GitHub release. Zapstore publishing is separate:
`SIGN_WITH=<nsec> zsp publish zapstore.yaml` from the repository root.

## Known limits

- **Above roughly 80 degrees latitude, timezone-by-nearest-city degrades.** The
  longitude weighting collapses toward the poles and the pick becomes
  longitude-blind. The highest city in the catalogue is Longyearbyen at 78.22 N,
  so this only affects manually entered coordinates further north than that.
- **English exonyms are only as good as GeoNames.** Most work; obscure ones may
  not.
- **Dark theme only.** The palette is lifted from the web app's `style.css` and a
  light variant does not exist.

## Credits

The footer credits only what the app's function is built on: [SunCalc](https://github.com/mourner/suncalc)'s
formulae (BSD 2-Clause) and the city data from [GeoNames](https://www.geonames.org/) and
[Kartverket](https://www.kartverket.no/en/api-and-data) (CC BY 4.0). The rest is credited here and in
[NOTICE](../NOTICE), not in the app:

- the [Inter](https://rsms.me/inter/) typeface (OFL 1.1), bundled as four static instances;
- the [Lucide](https://github.com/lucide-icons/lucide) LinkedIn and GitHub icons (ISC);
- [Kotlin](https://github.com/JetBrains/kotlin) and the [Jetpack](https://github.com/androidx/androidx)
  libraries, Compose and Material 3 included (Apache 2.0);
- the [Java time backport](https://github.com/google/desugar_jdk_libs) that gives Android 7 `java.time`
  (GPL 2.0 with Classpath Exception).

## Licence

MIT, same as the rest of the repository.
