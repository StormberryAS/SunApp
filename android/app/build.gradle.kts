import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Signing credentials never live in this repo. Locally they come from
// android/keystore.properties (gitignored); in CI from ORG_GRADLE_PROJECT_* env vars.
// Missing credentials must not break `assembleDebug`, so everything below is optional.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

fun signingValue(propKey: String, gradleKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: providers.gradleProperty(gradleKey).orNull

val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
val canSignRelease = releaseStoreFile != null && file(releaseStoreFile).exists()

// Google Play is a SEPARATE package, deliberately, and it is a SMALLER app: no alarms at all.
// `no.stormberry.sunapp` stays the sovereign build: our key, our certificate, shipped through
// GitHub Releases, Obtainium and Zapstore, with the NIP-C1 certificate link vouching for it,
// and it keeps the alarm feature with its nine install-time permissions. `-PplayBuild=true`
// builds `no.stormberry.sunapp.play` instead: the same sun times, with the alarm feature taken
// OUT (no alarm permissions, receivers, services or screens, see src/edition-play), which
// Google re-signs with an app signing key it holds and never hands back. The reason is Play
// policy, not taste: the alarm feature's USE_EXACT_ALARM, USE_FULL_SCREEN_INTENT and
// foreground-service declarations each have a review gate of their own, and the sun times
// need none of those permissions. Two package names mean two apps as far as Android is
// concerned: they install side by side and neither can ever update the other, which is
// what keeps existing installs safe from anything that happens on Play. One package name with
// two certificates would be the Mullvad failure: mutually un-installable builds and a forced
// uninstall to switch channel. Same pattern as UsernameGenerator's and MetadataScrubber's Play
// builds. Gradle product flavours are NOT used (a flavour-level signingConfig is overridden by
// the build type's, and a flavour dimension renames every task and breaks the hardcoded APK
// path in .github/workflows/android-release.yml and the regex in zapstore.yaml).
val playBuild = when (providers.gradleProperty("playBuild").orNull?.lowercase()) {
    null -> false
    // A bare `-PplayBuild` arrives as an empty string. Treating that as false would hand back
    // a sovereign artefact from a command that plainly meant Play.
    "", "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> error(
        "-PplayBuild=${providers.gradleProperty("playBuild").get()} is not a recognised " +
            "value. Use -PplayBuild=true, or omit the property for the sovereign build.",
    )
}

// The Play upload key is a separate key with a separate job: it proves who uploaded the
// bundle and never reaches a device, so it must never be the release key. If it is ever
// compromised it can be reset from the Play Console without touching a single install.
// android/keystore.properties names the file, type and alias and holds no password:
// PLAY_UPLOAD_STORE_PASSWORD and PLAY_UPLOAD_KEY_PASSWORD come from the keyring, as
// environment variables or Gradle properties. The release key's lookups above are unchanged.
fun uploadValue(propKey: String, gradleKey: String): String? =
    keystoreProperties.getProperty(propKey)
        ?: providers.gradleProperty(gradleKey).orNull
        ?: providers.environmentVariable(gradleKey).orNull

val uploadStoreFile = uploadValue("playUploadStoreFile", "PLAY_UPLOAD_STORE_FILE")
val uploadStoreType = uploadValue("playUploadStoreType", "PLAY_UPLOAD_STORE_TYPE")
val uploadStorePassword = uploadValue("playUploadStorePassword", "PLAY_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = uploadValue("playUploadKeyAlias", "PLAY_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = uploadValue("playUploadKeyPassword", "PLAY_UPLOAD_KEY_PASSWORD")
val canSignPlayUpload = uploadStoreFile != null && file(uploadStoreFile).exists() &&
    uploadStorePassword != null && uploadKeyAlias != null && uploadKeyPassword != null

// The two keys must be two different files. A Play upload config pointing at the release
// keystore (or a byte-for-byte copy of it) would enrol the sovereign certificate in Play App
// Signing, which cannot be undone. Refused before anything is built.
if (releaseStoreFile != null && uploadStoreFile != null) {
    val rel = file(releaseStoreFile)
    val upl = file(uploadStoreFile)
    if (rel.exists() && upl.exists() &&
        (rel.canonicalFile == upl.canonicalFile || rel.readBytes().contentEquals(upl.readBytes()))
    ) {
        error(
            "The Play upload keystore and the release keystore are the same key " +
                "($uploadStoreFile). The release key signs the Zapstore APK only and must never " +
                "reach Play; the upload key signs the Play bundle only.",
        )
    }
}

// The upload passwords are read while Gradle configures the build, and gradle.properties turns
// the configuration cache on. A cached configuration stores those values under
// android/.gradle/configuration-cache, which breaks the rule that the password is never written
// anywhere. So the build refuses to configure, before any cache entry exists, whenever an upload
// password is set and the cache is in use.
abstract class BuildFeaturesHolder @Inject constructor(val features: org.gradle.api.configuration.BuildFeatures)

val configurationCacheRequested =
    objects.newInstance<BuildFeaturesHolder>().features.configurationCache.requested.getOrElse(false)
if (configurationCacheRequested && (uploadStorePassword != null || uploadKeyPassword != null)) {
    error(
        "Play upload passwords are set, so this build must run with --no-configuration-cache; " +
            "otherwise Gradle would store them in its configuration cache.",
    )
}

// Guards on the RESOLVED TASK GRAPH, not on the command line, because the command line lies:
// `build`, `assemble` and Gradle's `bR` abbreviation reach a release artefact without
// containing "bundleRelease" or "assembleRelease". packageRelease (APK) and signReleaseBundle
// (AAB) are the two tasks that consume a signing config, so the checks belong on them.
//
// The dangerous mistake is the one with no flag: `bundleRelease` on its own would write an AAB
// carrying the SOVEREIGN application ID, alarms and nine permissions included, signed with the
// release key. Uploading it would permanently claim no.stormberry.sunapp on Play and enrol the
// release certificate in Play App Signing, which cannot be undone.
tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
    val isBundle = name == "signReleaseBundle"
    val play = playBuild
    val haveUploadKey = canSignPlayUpload
    doFirst {
        if (isBundle && !play) {
            error(
                "Refusing to build a release bundle without -PplayBuild=true. This AAB would " +
                    "carry the sovereign application ID signed with the release key, and " +
                    "uploading it would permanently claim that package on Play. The sovereign " +
                    "channel ships APKs; Play takes the bundle, with -PplayBuild=true.",
            )
        }
        if (!isBundle && play) {
            error(
                "Refusing to build a release APK with -PplayBuild=true. Play takes the app " +
                    "bundle: run :app:bundleRelease -PplayBuild=true. An installable .play APK " +
                    "has no destination and must never be mistaken for the Zapstore one.",
            )
        }
        if (isBundle && play && !haveUploadKey) {
            error(
                "playBuild=true but the Play upload keystore is not fully configured. " +
                    "playUploadStoreFile, playUploadKeyAlias (android/keystore.properties) and " +
                    "PLAY_UPLOAD_STORE_PASSWORD and PLAY_UPLOAD_KEY_PASSWORD (from the keyring) " +
                    "must all be set, and the store file must exist.",
            )
        }
    }
}

// ONE VISIBLE VERSION, TWO CODE LINES. The sovereign versionCode and versionName in
// defaultConfig below are the only numbers ever bumped by hand. The Play build derives its
// code from them and shows the same version name (Marcos, 2026-10-05, for MetadataScrubber:
// "1.0.0 for both"):
//   Play versionCode = 1000 + sovereign versionCode   (1.1.7 code 9 -> code 1009)
//   Play versionName = sovereign versionName          (1.1.7 -> 1.1.7)
// Play only needs monotonic codes within its own package, and the offset keeps them distinct
// from the sovereign line and rising with it. The package name and the signing key are what
// keep the two apart, not the version. Build every Play upload from a sovereign release tag.
val playVersionCodeOffset = 1000

// Which edition the code is compiled as: `Edition.ALARMS` is a compile-time constant, so the
// Play build contains no alarm code for R8 to keep. One directory per edition, added to the main
// source set; the tiny Edition.kt in each is the only file that differs.
val editionDir = if (playBuild) "src/edition-play" else "src/edition-sovereign"

val cityAssetsDir = layout.buildDirectory.dir("generated/assets/cities")

android {
    namespace = "no.stormberry.sunapp"
    // compileSdk is 37.1 because androidx (core-ktx 1.19.0, lifecycle 2.11.0,
    // compose 1.11.4) requires compiling against API 37 or later. targetSdk stays
    // at 36, which is what Google Play requires for new apps from 31 August 2026;
    // the two are deliberately independent.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        // The sovereign build keeps the original ID; the Play build gets its own. Once the Play
        // listing is published this string is permanent, because Play treats a changed
        // application ID as a completely different app. `namespace` above is unrelated: it is
        // the R class package, so nothing in the Kotlin source changes with the flag.
        applicationId = if (playBuild) "no.stormberry.sunapp.play" else "no.stormberry.sunapp"
        minSdk = 24
        targetSdk = 36
        // 1.1.0, not 1.0.0. The tree carries the alarm feature and its nine
        // install-time permissions, so a 1.0.0 label would misdescribe the
        // binary. The information-only 1.0.0 described in the plan was never
        // cut as a separate artefact; see android/README.md "Releases".
        //
        // 1.1.1 (code 3): the footer. android-v1.1.0 was tagged BEFORE the 2026-09-04
        // correction of the permissions line, so every installed copy still claimed "No
        // permissions", which was false: the APK declares nine, all for the alarm. Zapstore
        // was still serving code 2 on 2026-09-05, which is how the stale claim was noticed.
        //
        // 1.1.2 (code 4), 2026-10-10: Inter. The app is set in the typeface sun.stormberry.as
        // self-hosts, bundled in res/font, plus one layout fix it forced (the city search
        // hint stays on one line on a 360 dp phone).
        //
        // 1.1.3 (code 5), 2026-10-16: the ring screen opens over the lock screen. Android drops
        // the full-screen intent of a notification it treats as silent, and the ringing one
        // was: by its own setSilent(true), and, after a snooze, by the system auto-grouping it
        // with the "snoozed" notice. A locked phone showed only a notification. See AlarmService.
        //
        // 1.1.4 (code 6), 2026-10-21: alarms that are harder to lose, ported from EclipseTracker's
        // fix B: notifications off still rings and opening the app shows the ring screen (A-1);
        // Android 12 with exact alarms off rings through a backup notification and a backstop,
        // and the app re-arms on open (A-2); a pending snooze is stored, survives a restart and is
        // cancelled with its rule (A-6); a second alarm reports the first as missed (A-5); a
        // snooze that cannot be armed keeps the alarm ringing (A-7); a refused foreground rings
        // through the backup (A-4). No new permission.
        //
        // 1.1.5 (code 7), 2026-10-23: a new top and bottom for the sun-times screen, laid out like
        // CheckLink's: a centred hero (mark, name, tagline) and a footer with the Stormberry
        // wordmark, the icon row, the privacy line and the legal line. No new permission.
        //
        // 1.1.6 (code 8), 2026-10-24: the footer's credit sentence names only SunCalc, GeoNames and
        // Kartverket; icons, languages, libraries and the font are credited in NOTICE and the
        // README. No new permission.
        //
        // 1.1.7 (code 9), 2026-10-25: the app itself does not change; the build gains the switch
        // that makes the alarm-free Google Play edition (see the `playBuild` comment above).
        // Zapstore only offers an update when the version rises, and a Play package is built only
        // from a signed tag that shipped on GitHub Releases, so the switch ships in a release of
        // its own. Permissions: the same nine.
        //
        // 1.1.8 (code 10), 2026-10-30: the Sun times screen shows the Stormberry Labs carousel above
        // the footer, as the website does; each card opens that app's website in the browser through
        // an ACTION_VIEW intent. No new permission, no INTERNET. Play edition: 1.1.8, code 1010.
        versionCode = 10
        versionName = "1.1.8"
        // Play line, derived from the code above (see playVersionCodeOffset); same versionName.
        if (playBuild) {
            versionCode = playVersionCodeOffset + versionCode!!
        }

        // No instrumentation tests, no test runner, nothing that pulls in extra permissions.
        // Density-split PNGs are generated at build time from vectors and are a source of
        // build nondeterminism, so keep the vectors only.
        vectorDrawables.generatedDensities()
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Pinned explicitly: AGP documents these as "if null, a default value is
                // used" without saying what that default is, so an AGP upgrade could
                // silently change what the APK carries. minSdk 24 means v1 JAR signing
                // is dead weight; v2 covers 7.0+, v3 enables key rotation later.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
        if (canSignPlayUpload) {
            create("playUpload") {
                storeFile = file(uploadStoreFile!!)
                if (uploadStoreType != null) storeType = uploadStoreType
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
                // v1 stays ON here, unlike the release config above. An app bundle is signed
                // like a JAR, and this signature exists only so Play can verify who uploaded
                // it: Play strips it and re-signs the APKs it generates with the app signing key
                // it holds. Nothing a device installs from Play carries this signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The Play build never touches the release key and the sovereign build never touches
            // the upload key. Both stay conditional so a clean checkout can still assembleDebug.
            if (playBuild) {
                if (canSignPlayUpload) signingConfig = signingConfigs.getByName("playUpload")
            } else if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // The city catalogue is shared with the web app and with the eight sibling
    // Labs apps. One source of truth: SunApp/data/cities.tsv is generated by
    // SunApp/update_cities.py and copied into assets at build time rather than
    // duplicated here.
    sourceSets["main"].assets.directories.add(cityAssetsDir.get().asFile.absolutePath)

    // The edition (see `editionDir` above). Edition.kt is compiled with the main source set; the
    // Play edition also overlays a manifest that REMOVES the alarm feature's permissions and
    // components from the merged manifest (AGP merges a build type's manifest over main's). Set
    // on both build types so that a Play debug build shows the same permissions. The sovereign
    // build adds no manifest at all, so its merged manifest is main's, as before.
    sourceSets["main"].kotlin.directories.add(editionDir)
    if (playBuild) {
        sourceSets["release"].manifest.srcFile("src/edition-play/AndroidManifest.xml")
        sourceSets["debug"].manifest.srcFile("src/edition-play/AndroidManifest.xml")
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time is API 26+ and minSdk is 24, so two API levels would not have
        // it. Every solar and alarm calculation in this app is java.time, so
        // desugaring is load-bearing rather than a convenience. Note the desugar
        // library carries its OWN tzdb rather than reading Mainline updates, so on
        // API 24 and 25 a government moving a DST transition can leave the zone
        // rules stale until this dependency is bumped. See android/README.md.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    androidResources {
        // Deliberately NOT adding cities.tsv to noCompress, which is the opposite of
        // the choice UsernameGenerator made for its word lists. That file is ~900 KiB
        // raw and roughly 370 KiB deflated, AssetManager.open() inflates it
        // transparently, and the inflate cost lands inside the parse budget we were
        // going to pay anyway. Storing it uncompressed would add half a megabyte to
        // the APK for no measurable gain.
    }

    dependenciesInfo {
        // Strips the Google-signed dependency blob from the APK. It is encrypted, it is
        // not reproducible, and for an app whose whole claim is "nothing leaves the
        // device" an opaque metadata block is the wrong look.
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // Version bumps are a deliberate, reviewed act here, not something lint
            // should fail the build over. See gradle/libs.versions.toml.
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "ObsoleteLintCustomCheck",
            // targetSdk 36 is intentional. It is what Google Play requires for new
            // apps from 31 August 2026, and API 37 opts into runtime behaviour this
            // app has not been tested against. compileSdk is already 37.1.
            "OldTargetApi",
        )
        // The Play edition removes POST_NOTIFICATIONS from its manifest and, with it, every
        // notification the alarm feature posts. Lint reads the SOURCE, where AlarmService still
        // posts one, and cannot see that Edition.ALARMS (false there) makes that code unreachable
        // and that R8 deletes it: it reported "posting a notification requires POST_NOTIFICATIONS"
        // on the Play build. The check stays ON for the sovereign build, which declares the
        // permission, and tools/check-play-bundle.sh proves that the Play bundle holds no alarm
        // class at all. Nothing else is disabled for Play.
        if (playBuild) disable += "NotificationPermission"
    }
}

// Under -PplayBuild=true the artefact is named for what it is, so a Play bundle can never be
// mistaken for a sovereign one on disk. Deliberately NOT set for the sovereign build, whose
// app-release.apk path the release workflow expects.
if (playBuild) {
    base { archivesName = "sunapp-play" }
}

kotlin {
    // The JDK that COMPILES, as distinct from the bytecode level, which stays at 17 in
    // compileOptions above and in jvmTarget below. Pinned to 21 because that is what
    // .github/workflows/android-release.yml installs, and a third party rebuilding from a
    // tag is meant to get the same bytes as the released artefact: the compiler version is
    // part of the build definition, not a property of whoever is building.
    //
    // Without this, Gradle compiles with whatever JVM happens to run the daemon. On this
    // machine the default `java` is /usr/lib/jvm/java-25-openjdk-amd64, which on Debian 13
    // is a JRE with no compiler, so the build died outright with "Toolchain installation ...
    // does not provide the required capabilities: [JAVA_COMPILER]". Gradle auto-detects
    // installed JDKs, so any vendor's 21 satisfies this and no machine-specific path is
    // written into the repo.
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

val copyCityData by tasks.registering(Copy::class) {
    description = "Copies the packed city catalogue from the repo root into app assets."
    group = "build"
    from(rootProject.file("../data")) {
        include("cities.tsv")
    }
    into(cityAssetsDir.map { it.dir("data") })
    // Deterministic ordering, so the APK is byte-reproducible across machines.
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

tasks.named("preBuild") { dependsOn(copyCityData) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    // Real org.json ahead of the stubbed framework copy on the unit-test classpath only.
    // Nothing here reaches the APK: AlarmStore compiles and runs against the platform
    // org.json on a device, and this dependency exists purely so the same code can be
    // exercised by a JVM test instead of needing Robolectric or an emulator.
    testImplementation(libs.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
