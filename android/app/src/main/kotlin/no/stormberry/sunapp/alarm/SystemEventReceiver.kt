package no.stormberry.sunapp.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Re-plans every alarm after any event that can invalidate what is currently armed.
 *
 * `AlarmManager` forgets everything at a reboot, and it keeps trigger *instants* rather than
 * intentions, so an alarm armed before a time-zone change is still armed for the instant that
 * was 06:00 in the old zone. Both cases are silent: nothing tells the app, and the user finds
 * out by oversleeping. Hence a receiver whose entire job is to notice and recompute.
 *
 * Each action is here for a specific failure it prevents.
 *
 * - `LOCKED_BOOT_COMPLETED` re-arms before the user has unlocked, from the device-protected
 *   rule mirror. This is the case that matters after an unattended overnight reboot: without
 *   it the first alarm after an update or a battery-flat restart never rings.
 * - `BOOT_COMPLETED` re-arms again once the real rule file is readable, which corrects
 *   anything the mirror was missing.
 * - `MY_PACKAGE_REPLACED` covers an app update, which also clears pending alarms.
 * - `TIMEZONE_CHANGED` and `TIME_SET` cover flying somewhere and correcting the clock. The
 *   solar maths is anchored to coordinates, so a rule for Bergen keeps ringing at Bergen's
 *   sunrise while the traveller is in Tokyo, which is the correct and slightly surprising
 *   behaviour: the fix is to change the rule's city, not to reinterpret it silently.
 * - `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (Android 12 and later) fires when the
 *   user grants or revokes exact alarms. On revoke the platform has already cancelled every
 *   exact alarm this app owned, so without a re-plan into the inexact fallback the alarms are
 *   simply gone.
 * - `LOCALE_CHANGED` refreshes the notification channel names only. It deliberately does not
 *   recompute a single trigger time: language has nothing to do with when the sun rises, and
 *   re-arming on it would be a needless write on a common event.
 *
 * DST is absent from that list on purpose. There is no DST broadcast, and none is needed:
 * offsets from a solar instant are pure instant arithmetic, so the only DST-sensitive path in
 * the app is an optional clamp, and the daily re-plan that follows every fire re-evaluates it
 * against the zone rules of the day it will actually ring on.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == Intent.ACTION_LOCALE_CHANGED) {
            AlarmService.ensureChannels(context)
            return
        }

        if (action !in REPLAN_ACTIONS) {
            Log.w(TAG, "ignoring unexpected action $action")
            return
        }

        // goAsync rather than working on the main thread, because these arrive during boot,
        // when the device is at its most contended and a receiver that overruns its window is
        // killed mid-arm. The work itself is small (a JSON parse of a handful of rows and a
        // solar evaluation each), so the thread is about scheduling fairness rather than
        // duration.
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread({
            try {
                val plan = AlarmScheduling.replan(appContext)
                Log.i(TAG, "$action re-armed ${plan.size} alarm(s)")
            } catch (e: RuntimeException) {
                // A boot receiver that throws takes every remaining alarm with it.
                Log.e(TAG, "re-planning after $action failed", e)
            } finally {
                pending.finish()
            }
        }, "sunapp-replan").start()
    }

    private companion object {
        const val TAG = "SunAlarmSystemEvent"

        /**
         * The actions that mean "what is armed can no longer be trusted".
         *
         * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` sits behind a version check
         * only so the set describes what this device can actually deliver. The constant is a
         * compile-time string and is inlined, so referencing it on API 24 is harmless; the
         * broadcast simply does not exist before 31.
         */
        val REPLAN_ACTIONS: Set<String> = buildSet {
            add(Intent.ACTION_BOOT_COMPLETED)
            add(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            add(Intent.ACTION_MY_PACKAGE_REPLACED)
            add(Intent.ACTION_TIMEZONE_CHANGED)
            add(Intent.ACTION_TIME_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
            }
        }
    }
}
