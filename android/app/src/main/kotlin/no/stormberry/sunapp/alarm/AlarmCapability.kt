package no.stormberry.sunapp.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * One thing the platform is currently refusing that a solar alarm needs.
 *
 * Modelled as data rather than as a boolean triple because all three are individually
 * survivable and individually degrading, and the difference matters to the user. A denied
 * full-screen intent still rings; a denied notification does not ring at all. A screen that
 * collapses the three into "permissions missing" cannot tell the user which of those two
 * situations they are in, and the second one is the one that makes a person late for work.
 *
 * The text lives on the enum instead of in `strings.xml` deliberately for now: `res/` is a
 * shared file and this whole surface is one screen's worth of copy. It should move to string
 * resources before the first translation, and nothing here depends on it staying put.
 *
 * @property title what is wrong, as a headline.
 * @property explanation what actually happens as a result. Consequence first, mechanism
 *   second: users do not act on "permission X is not granted", they act on "your alarm will
 *   not make a sound".
 * @property fixLabel the button that opens [AlarmCapability.settingsIntent].
 * @property blocking true when an alarm cannot ring at all in this state. Exactly one gap is
 *   blocking, and the alarm row is expected to carry a persistent banner for it rather than a
 *   dismissible toast.
 */
enum class AlarmCapabilityGap(
    val title: String,
    val explanation: String,
    val fixLabel: String,
    val blocking: Boolean,
) {
    /**
     * Notifications are switched off for the app, so the foreground service that plays the
     * alarm cannot show its notification and Android will not let it run.
     */
    NOTIFICATIONS(
        title = "Notifications are switched off",
        explanation = "SunApp rings through a notification. With notifications blocked an alarm " +
            "can still be armed, but it cannot make a sound and it cannot take over the screen.",
        fixLabel = "Turn on notifications",
        blocking = true,
    ),

    /**
     * Exact alarms are not permitted, which on Android 12 and 13 is a switch the user can
     * turn off. SunApp keeps the alarm armed and falls back to an inexact wakeup, so this is
     * a degradation rather than a failure.
     */
    EXACT_ALARMS(
        title = "Exact alarms are not allowed",
        explanation = "Without permission for exact alarms Android may delay a wakeup to save " +
            "battery. SunApp still rings, but it can be several minutes late.",
        fixLabel = "Allow exact alarms",
        blocking = false,
    ),

    /**
     * Full-screen intents are not permitted (Android 14 and later ask for this separately).
     * The alarm rings and shows a heads-up notification, but it cannot open the ring screen
     * over a locked device.
     */
    FULL_SCREEN_INTENT(
        title = "Full-screen alarms are not allowed",
        explanation = "SunApp rings and shows a notification, but it cannot take over a locked " +
            "screen, which makes the alarm easier to sleep through.",
        fixLabel = "Allow full-screen alarms",
        blocking = false,
    ),
}

/**
 * What the platform is refusing right now, and the Settings screen that fixes each refusal.
 *
 * Two rules govern every caller.
 *
 * **Ask late.** Nothing here is consulted until the user saves their first alarm (owner's
 * confirmed decision 4). The sun times, the city picker and the polar labelling work with
 * every one of these denied, and version 1.0.0 of this app shipped with no permissions in the
 * manifest at all. A permission sheet on first launch would be asking for capabilities the
 * user has not yet said they want.
 *
 * **Re-check constantly.** Every one of these can be revoked from Settings while the app is
 * in the background, and none of them produces a callback when it changes. The armed state
 * shown in the UI must therefore be re-read on every `onResume` and immediately before every
 * arm, never cached from the last successful check. `AndroidAlarmSink` assumes nothing here
 * was checked at all and catches the resulting `SecurityException` anyway.
 *
 * There is no location permission in this list, and there will not be one: coordinates come
 * from the bundled city table, so the app has nothing to ask for.
 */
object AlarmCapability {

    /**
     * The sentence that must appear above any permission request, per the owner's confirmed
     * decision 4. It is the honest summary of everything in this file.
     */
    const val RATIONALE: String =
        "These permissions are needed only for alarms. Sun times work without any of them, and " +
            "SunApp never asks for your location: coordinates come from the city list built " +
            "into the app."

    /** Everything the platform is refusing on this device right now. Empty is the good case. */
    fun gaps(context: Context): List<AlarmCapabilityGap> = gapsFrom(
        sdkInt = Build.VERSION.SDK_INT,
        notificationsEnabled = notificationsEnabled(context),
        canScheduleExactAlarms = canScheduleExactAlarms(context),
        canUseFullScreenIntent = canUseFullScreenIntent(context),
    )

    /**
     * The decision half of [gaps], with the platform reading passed in.
     *
     * Split out so the API-level rules are testable on the JVM. They are the part most likely
     * to be got wrong, because each gap became askable at a different Android version and a
     * naive check reports a permanent, unfixable gap on the versions where the switch does
     * not exist: `canScheduleExactAlarms()` did not exist before 31, and full-screen intents
     * were ungated before 34. A device on API 24 to 30 must see an empty list here, which is
     * what makes the plan's "on API 24 to 30 the sheet never appears at all" true rather than
     * aspirational.
     *
     * Order is by severity, so a caller that shows only the first row shows the worst one.
     */
    fun gapsFrom(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        canScheduleExactAlarms: Boolean,
        canUseFullScreenIntent: Boolean,
    ): List<AlarmCapabilityGap> = buildList {
        if (!notificationsEnabled) add(AlarmCapabilityGap.NOTIFICATIONS)
        if (sdkInt >= Build.VERSION_CODES.S && !canScheduleExactAlarms) {
            add(AlarmCapabilityGap.EXACT_ALARMS)
        }
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUseFullScreenIntent) {
            add(AlarmCapabilityGap.FULL_SCREEN_INTENT)
        }
    }

    /**
     * True when a notification posted by this app can actually appear.
     *
     * Covers both the runtime permission introduced in API 33 and the much older per-app
     * switch in Settings, because [NotificationManagerCompat.areNotificationsEnabled] answers
     * the question the app actually cares about ("will anything be shown") rather than the
     * narrower question of whether a permission string was granted.
     */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * True when `setAlarmClock` will be honoured exactly.
     *
     * Below API 31 exact alarms need no grant, so the honest answer is true rather than
     * "unknown". With `USE_EXACT_ALARM` in the manifest this also returns true on 33 and
     * later, because that permission is granted at install for apps whose core function is an
     * alarm clock, leaving 31 and 32 as the only versions where a user can actually take it
     * away.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** True when the ring screen can be launched over the lock screen. Ungated before API 34. */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /**
     * The Settings screen that fixes [gap], as an intent ready for `startActivity`.
     *
     * Deep links rather than dialogs, because none of these three can be granted by a dialog
     * on the versions where they are actually refusable: exact alarms and full-screen intents
     * are Settings toggles with no request API, and a notification permission that has been
     * denied twice is permanently denied and stops showing its dialog. Every branch falls
     * back to the app's own details page, which exists on every Android version and always
     * has the switch somewhere, so a caller never has to handle "no intent available".
     */
    fun settingsIntent(context: Context, gap: AlarmCapabilityGap): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return when (gap) {
            AlarmCapabilityGap.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    appDetails(packageUri)
                }

            AlarmCapabilityGap.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                } else {
                    appDetails(packageUri)
                }

            AlarmCapabilityGap.FULL_SCREEN_INTENT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
                } else {
                    appDetails(packageUri)
                }
        }
    }

    /**
     * The battery optimisation list, for the troubleshooting path only.
     *
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is deliberately not in the manifest: it is
     * restricted on Google Play, and `setAlarmClock` is already exempt from Doze, so an app
     * that asks for it is usually papering over the wrong API. What remains useful is sending
     * the user to the list so they can find an aggressive OEM setting themselves, which needs
     * no permission at all.
     */
    fun batteryOptimisationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * Vendor-specific advice for a device whose manufacturer is known to kill background
     * apps beyond what Android itself does, or null when there is nothing extra to say.
     *
     * Pure and matched on a lowercase substring so it can be tested without a device. This
     * exists because "my alarm did not ring" on these devices is almost never an app bug and
     * almost always a vendor power manager, and a diagnostics screen that says so saves the
     * user an evening. Match on the substring rather than an exact string: `Build.MANUFACTURER`
     * is not standardised, and sub-brands report their own names.
     */
    fun oemGuidance(manufacturer: String): String? {
        val name = manufacturer.lowercase()
        return when {
            name.containsAny("xiaomi", "redmi", "poco") ->
                "MIUI and HyperOS restrict new apps by default. Open Settings, Apps, SunApp, " +
                    "set Battery saver to No restrictions, and turn on Autostart."

            name.containsAny("oppo", "realme", "oneplus") ->
                "ColorOS and OxygenOS pause background apps. Open Settings, Battery, SunApp, " +
                    "and choose Allow background activity plus Allow auto launch."

            name.containsAny("vivo", "iqoo") ->
                "Funtouch OS and OriginOS need SunApp added to High background power " +
                    "consumption and to Auto start in the i Manager app."

            name.containsAny("huawei", "honor") ->
                "EMUI and MagicOS need SunApp set to Manage manually in Settings, Battery, " +
                    "App launch, with Auto launch and Run in background switched on."

            name.containsAny("samsung") ->
                "One UI can put a rarely used app to sleep. Open Settings, Battery, " +
                    "Background usage limits, and make sure SunApp is not listed under " +
                    "Sleeping apps or Deep sleeping apps."

            name.containsAny("meizu", "asus", "tecno", "infinix", "itel") ->
                "This manufacturer ships its own power manager on top of Android. Look for an " +
                    "auto start or background activity list and allow SunApp there."

            else -> null
        }
    }

    private fun appDetails(packageUri: Uri): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
