# SunApp for Android

Sunrise, solar noon and sunset for anywhere on Earth, calculated on the device.

The same maths and the same city catalogue run on the web at
[sun.stormberry.as](https://sun.stormberry.as). The Kotlin port is tested against
a golden corpus generated from the JavaScript original, so the two cannot
silently disagree about when the sun comes up.

## Install

Grab the APK from [Releases](https://github.com/StormberryAS/SunApp/releases), or
from [Zapstore](https://zapstore.dev).

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
  the alarm row states exactly what is degraded, for example "approximate, may
  be several minutes late".

### The nine, one sentence each

| Permission | Why it is here |
|---|---|
| `USE_EXACT_ALARM` | Fires the alarm at the minute you set on Android 13 and later, granted at install because an alarm clock is this app's primary function. |
| `SCHEDULE_EXACT_ALARM` | The same capability on Android 12 and 12L, where `USE_EXACT_ALARM` does not exist yet; capped at `maxSdkVersion="32"` so no newer device is ever asked for it. |
| `POST_NOTIFICATIONS` | The alarm rings through a notification, so on Android 13 and later this is what makes ringing possible at all; it is the only runtime dialog in the app. |
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

## Releases

**1.1.0 is the first release.** The information-only 1.0.0 described in the
implementation plan was never cut as a separate artefact: both were built in one
sitting, so there is no commit at which the tree is alarm-free.

That is recoverable if the zero-permission audit build is wanted later, because
the split is almost perfectly path-based. Everything alarm-related lives in
`alarm/`, plus `data/AlarmStore.kt`, `ui/AlarmsScreen.kt`, `ui/RuleEditorScreen.kt`
and `ui/PermissionSheet.kt`. The only coupling is the navigation wiring in
`ui/App.kt`. Staging everything except those paths, with the nine permissions
commented out of the manifest and `expected-permissions.txt`, yields a genuine
zero-permission build; the sun-times surface references no alarm code at all.

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

## Licence

MIT, same as the rest of the repository.
