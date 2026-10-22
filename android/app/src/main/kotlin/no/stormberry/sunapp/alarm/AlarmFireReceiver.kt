package no.stormberry.sunapp.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Receives the alarm from `AlarmManager`, its Android 12 backstop, the Snooze and Dismiss taps
 * from the notifications and the ring screen, and the backup ring notification going away. It is
 * the one component that must never throw, so since 1.1.4 every action runs inside one catch.
 *
 * When an alarm arrives, [FireRules] decides first, from the instant it was armed for, the clock
 * now and what is stored (1.1.4):
 *  1. it arrived more than two seconds early: the clock moved rather than time passing, so nothing
 *     rings and the re-plan arms the right instant;
 *  2. its occurrence has rung already (the backstop and the exact alarm both arriving, or a re-arm
 *     after a small backwards clock correction): nothing rings;
 *  3. a snooze that is no longer stored (cancelled, or rung already): nothing rings;
 *  4. otherwise it rings, however late it arrived: the ring is noted first when it arrived on
 *     time (a late one rang at the wrong real time and must not use up the real occurrence, fix
 *     round 1), then the re-plan runs, then the ringing service starts, or, when Android will not
 *     let it start (Android 12 and 12L with exact alarms switched off, A-2), the backup ring
 *     notification rings instead.
 *
 * A snooze that a re-plan finds more than ten minutes overdue after a restart is dropped there
 * with a Missed notice (decision D-5); one that arrives here rings.
 *
 * The re-plan runs on every arrival and inside its own catch, because everything after it (reading
 * the rule, starting a service, playing audio) has a plausible failure mode, and an alarm clock
 * that stops scheduling because one morning went wrong is worse than one that misses a single
 * ring. The ring is noted before it, so it arms the occurrence after this one.
 *
 * Snooze and Dismiss arrive here as broadcasts rather than as service or activity intents
 * because notification trampolines are blocked from Android 12: an action that starts a
 * service which then starts an activity is dropped by the platform. A broadcast to an
 * unexported receiver in our own process has none of that ambiguity.
 */
class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_ALARM_FIRED, ACTION_GUARD -> onAlarmFired(context, intent)
                ACTION_SNOOZE -> onSnooze(context, intent)
                ACTION_DISMISS -> onDismiss(context, intent)
                ACTION_BACKUP_GONE -> onBackupGone(context, intent)
                else -> Log.w(TAG, "ignoring unexpected action ${intent.action}")
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not handle ${intent.action}", e)
        }
    }

    private fun onAlarmFired(context: Context, intent: Intent) {
        // The system's own wake lock ends when onReceive returns, which can easily be before
        // the service has started its audio. Ours bridges that gap and is handed to the
        // service, which releases it once it holds one of its own.
        AlarmWakeLock.acquire(context)
        var handedOff = false
        try {
            val targetMillis = intent.getLongExtra(EXTRA_FIRE_AT, 0L)
            val now = System.currentTimeMillis()
            val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
            // Snoozes use the odd sibling of the rule's request code; alarms and their backstop
            // the even one.
            val isSnooze = intent.getIntExtra(EXTRA_REQUEST_CODE, 0) and 1 == 1
            val decision = if (isSnooze) {
                FireRules.decideSnooze(targetMillis, now, ruleId?.let { AlarmScheduling.snoozeStored(context, it, targetMillis) })
            } else {
                FireRules.decide(targetMillis, now, ruleId?.let { AlarmScheduling.lastRung(context, it) })
            }

            if (ruleId != null) {
                when {
                    // A snooze that rings now is no longer waiting: forgotten before the re-plan,
                    // which would otherwise arm it again for a past instant.
                    isSnooze && decision == FireDecision.Ring ->
                        AlarmScheduling.forgetSnooze(context, ruleId)
                    // The ring is noted before the re-plan, so the re-plan arms the occurrence
                    // after this one even when this one arrived a moment early. Only an on-time
                    // ring is noted (fix round 1): one that arrived late because the clock jumped
                    // forward past it rang at the wrong real time, and noting it would make the
                    // re-plan after the clock is corrected skip the real occurrence.
                    !isSnooze && decision == FireDecision.Ring && targetMillis > 0L &&
                        FireRules.shouldRecordRing(targetMillis, now, inexact = intent.action == ACTION_GUARD || !exactAllowed(context)) ->
                        AlarmScheduling.markRung(context, ruleId, targetMillis)
                }
            }

            // Inside its own catch: the schedule must survive whatever happens next. Re-planning
            // everything rather than just this rule keeps the one code path that arms alarms, and
            // is idempotent because request codes are stable.
            try {
                AlarmScheduling.replan(context, tell = decision != FireDecision.Ring)
            } catch (e: RuntimeException) {
                Log.e(TAG, "re-planning after a fire failed", e)
            }

            // A rule that cannot be found still rings, with a generic label. It can be absent
            // for two reasons: the device is locked and the mirror is thin, or the rule was
            // deleted between arming and firing. Staying silent would be right for the second
            // and catastrophic for the first, and only one of the two makes a person late.
            val rule = ruleId?.let {
                try {
                    AlarmScheduling.ruleFor(context, it)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "could not read rule $it while firing", e)
                    null
                }
            }

            when (decision) {
                FireDecision.Ring -> {
                    if (rule == null) Log.w(TAG, "firing request code without a readable rule: $ruleId")
                    val alarm = AlarmService.alarmFor(
                        ruleId = ruleId,
                        rule = rule,
                        fireAtMillis = if (targetMillis > 0L) targetMillis else now,
                        usedFallback = intent.getBooleanExtra(EXTRA_USED_FALLBACK, false),
                        isSnooze = isSnooze,
                        startedAtMillis = now,
                    )
                    handedOff = ring(context, alarm)
                }
                FireDecision.Early ->
                    // The wall clock moved rather than time passing: a manual change, a network
                    // time correction, or a device whose RTC drifted while it was off. Ringing now
                    // would wake someone in the middle of the night; the re-plan above has armed
                    // the correct instant against the corrected clock.
                    Log.w(TAG, "alarm arrived ${targetMillis - now} ms early, treating it as a clock jump")
                FireDecision.AlreadyRung -> Log.w(TAG, "this occurrence has rung already; not ringing it twice")
                FireDecision.Gone -> Log.w(TAG, "this snooze was cancelled or has rung; not ringing")
            }
        } finally {
            if (!handedOff) AlarmWakeLock.release()
        }
    }

    /**
     * Whether exact alarms are allowed now: always before Android 12, and from it as Android says.
     * When this cannot be read the alarm counts as inexact, which only widens the window in which
     * its ring is noted.
     */
    private fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)

    /**
     * Start the ringing service; when Android refuses (on Android 12 and 12L an inexact alarm
     * grants no exemption to start a foreground service from the background), ring through the
     * backup notification instead (1.1.4, A-2). True when the wake lock was handed to the service.
     */
    private fun ring(context: Context, alarm: RingingAlarm): Boolean =
        try {
            ContextCompat.startForegroundService(context, AlarmService.ringIntent(context, alarm))
            true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException on Android 12 and later. 1.1.3 only
            // logged it, so nothing rang and nothing showed.
            Log.e(TAG, "could not start the ringing service; ringing through the backup notification", e)
            AlarmService.postBackupRing(context, alarm)
            false
        }

    /**
     * Snooze. The snooze is stored and armed FIRST; only when it is armed (exactly, or inexactly)
     * does the ringing stop (1.1.4, A-7). If neither works the alarm keeps ringing, so Dismiss is
     * still there and nothing is lost silently. The stored snooze is what lets a restart re-arm it
     * and a deleted or switched-off rule cancel it, and its notice has Dismiss (A-6).
     */
    private fun onSnooze(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        if (ruleId == null) {
            Log.w(TAG, "snooze without a rule id, nothing to re-arm")
            stopRinging(context)
            return
        }
        val label = (intent.getStringExtra(EXTRA_LABEL) ?: DEFAULT_LABEL).take(PendingSnooze.MAX_LABEL)
        val snooze = PendingSnooze(ruleId, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, label)
        if (AlarmScheduling.snooze(context, snooze) == ArmResult.FAILED) {
            Log.e(TAG, "the snooze could not be armed; the alarm keeps ringing")
            return
        }
        stopRinging(context)
        AlarmService.postSnoozedNotice(context, snooze)
    }

    private fun onDismiss(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID)
        // Dismiss from a snoozed notice must not silence another alarm that rings meanwhile.
        val current = AlarmService.current()
        if (current == null || current.ruleId == ruleId) stopRinging(context)
        if (ruleId == null) return
        // Dismiss means dismiss. If the user snoozed and then changed their mind, the pending
        // snooze goes with it, and so does its notice.
        AlarmScheduling.cancelSnooze(context, ruleId)
        AlarmService.cancelNotice(context, ruleId)
    }

    /**
     * The backup ring notification was swiped away or reached its timeout (1.1.4, A-2). When it
     * rang to the end, it says so, as the ringing service does.
     */
    private fun onBackupGone(context: Context, intent: Intent) {
        val alarm = AlarmService.alarmFrom(intent)
        val current = AlarmService.current()
        if (current == null || current.key == alarm.key) {
            AlarmService.setBackup(null)
            AlarmService.repostNotices(context)
        }
        if (System.currentTimeMillis() >= alarm.startedAtMillis + AlarmService.AUTO_SILENCE_MS - BACKUP_TIMEOUT_SLACK_MS) {
            AlarmService.postMissedNotice(context, alarm)
        }
    }

    /**
     * Stop the service outright rather than sending it a command, and take the backup ring
     * notification away with it.
     *
     * `stopService` is permitted from the background on every Android version, while starting
     * one is not, and the service's `onDestroy` already releases the audio, the vibrator, the
     * wake lock and the notification. Routing Dismiss through a start command would add a
     * background-start restriction to the one path that absolutely has to work. The ring
     * notification id is cancelled as well (A-4): a backup ring posted plainly has no service
     * to remove it.
     */
    private fun stopRinging(context: Context) {
        try {
            context.stopService(Intent(context, AlarmService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not stop the ringing service", e)
        }
        AlarmService.setBackup(null)
        AlarmService.cancelRingNotification(context)
        // The notices a ring takes away come back once nothing rings.
        AlarmService.repostNotices(context)
    }

    companion object {
        private const val TAG = "SunAlarmFire"

        /** Sent by `AlarmManager` when a planned alarm comes due. */
        const val ACTION_ALARM_FIRED: String = "no.stormberry.sunapp.action.ALARM_FIRED"

        /** Sent by the Android 12 and 12L backstop of the nearest alarm (1.1.4, A-2). */
        const val ACTION_GUARD: String = "no.stormberry.sunapp.action.ALARM_GUARD"

        /** Sent by the Snooze control in the notification and on the ring screen. */
        const val ACTION_SNOOZE: String = "no.stormberry.sunapp.action.SNOOZE"

        /** Sent by the Dismiss control in the notification, the snoozed notice and the ring screen. */
        const val ACTION_DISMISS: String = "no.stormberry.sunapp.action.DISMISS"

        /** Sent when the backup ring notification is swiped away or times out (1.1.4, A-2). */
        const val ACTION_BACKUP_GONE: String = "no.stormberry.sunapp.action.BACKUP_GONE"

        const val EXTRA_RULE_ID: String = "no.stormberry.sunapp.extra.RULE_ID"
        const val EXTRA_FIRE_AT: String = "no.stormberry.sunapp.extra.FIRE_AT"
        const val EXTRA_USED_FALLBACK: String = "no.stormberry.sunapp.extra.USED_FALLBACK"
        const val EXTRA_REQUEST_CODE: String = "no.stormberry.sunapp.extra.REQUEST_CODE"
        const val EXTRA_LABEL: String = "no.stormberry.sunapp.extra.LABEL"

        /**
         * How long a snooze lasts. Nine minutes because that is what every alarm clock has
         * done since mechanical snooze bars, and a user's expectation about their alarm is
         * not the place to be original.
         */
        const val SNOOZE_MINUTES: Int = 9

        /** Shown when the rule behind a ringing alarm cannot be read. */
        const val DEFAULT_LABEL: String = "Solar alarm"

        /** A backup notification gone within this of its timeout counts as timed out (rang to the end). */
        private const val BACKUP_TIMEOUT_SLACK_MS = 60_000L

        /**
         * The intent `AlarmManager` will deliver, and the same shape [AndroidAlarmSink.cancel]
         * rebuilds from a request code alone.
         *
         * The per-code `data` URI is what makes that rebuild sound. `PendingIntent` matching
         * compares action, data, type, class and categories and ignores extras entirely, so
         * without it every alarm in the app would share one identity and cancelling any rule
         * would cancel all of them.
         */
        fun fireIntent(
            context: Context,
            requestCode: Int,
            ruleId: String?,
            fireAtMillis: Long,
            usedFallback: Boolean,
        ): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_ALARM_FIRED)
            .setData("sunapp://alarm/$requestCode".toUri())
            .putExtra(EXTRA_REQUEST_CODE, requestCode)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_FIRE_AT, fireAtMillis)
            .putExtra(EXTRA_USED_FALLBACK, usedFallback)

        /**
         * A backstop's intent (1.1.4, A-2): an address of its own ([path]), so it never replaces
         * an alarm's or the other backstop's, carrying the alarm it stands in for.
         */
        fun guardIntent(
            context: Context,
            path: String,
            requestCode: Int,
            ruleId: String?,
            fireAtMillis: Long,
            usedFallback: Boolean,
        ): Intent = Intent(context, AlarmFireReceiver::class.java)
            .setAction(ACTION_GUARD)
            .setData("sunapp://$path".toUri())
            .putExtra(EXTRA_REQUEST_CODE, requestCode)
            .putExtra(EXTRA_RULE_ID, ruleId)
            .putExtra(EXTRA_FIRE_AT, fireAtMillis)
            .putExtra(EXTRA_USED_FALLBACK, usedFallback)

        /** The Snooze control's intent. Explicit, so it can never leave the app. */
        fun snoozeIntent(context: Context, ruleId: String?, label: String): Intent =
            Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .setData("sunapp://snooze/${ruleId.orEmpty()}".toUri())
                .putExtra(EXTRA_RULE_ID, ruleId)
                .putExtra(EXTRA_LABEL, label)

        /** The Dismiss control's intent. Explicit, so it can never leave the app. */
        fun dismissIntent(context: Context, ruleId: String?): Intent =
            Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData("sunapp://dismiss/${ruleId.orEmpty()}".toUri())
                .putExtra(EXTRA_RULE_ID, ruleId)

        /** The backup ring notification's delete intent; the caller adds the alarm. */
        fun backupGoneIntent(context: Context, ruleId: String?): Intent =
            Intent(context, AlarmFireReceiver::class.java)
                .setAction(ACTION_BACKUP_GONE)
                .setData("sunapp://backup-gone/${ruleId.orEmpty()}".toUri())
    }
}

/**
 * The wake lock that carries an alarm from the broadcast into the ringing service.
 *
 * Process-wide and not reference counted, so the handover is a single object with a single
 * owner at a time: the receiver takes it, the service releases it as soon as it holds its
 * own. The timeout is the safety net for the case the service never arrives at all, because
 * a partial wake lock leaked by an alarm clock is a battery complaint the user will blame on
 * the alarm they did not get.
 */
internal object AlarmWakeLock {

    private const val TAG = "SunAlarmWakeLock"

    /**
     * Deliberately generous next to the ten seconds a receiver gets: on a cold device the
     * service still has to create a channel, resolve a ringtone and prepare a MediaPlayer.
     * Nothing is expected to hold it this long.
     */
    private const val TIMEOUT_MS = 60_000L

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) return
        val power = context.applicationContext.getSystemService(PowerManager::class.java)
        if (power == null) {
            Log.w(TAG, "no PowerManager, ringing without a wake lock")
            return
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunApp:alarm").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS)
        }
    }

    @Synchronized
    fun release() {
        val held = wakeLock ?: return
        wakeLock = null
        if (held.isHeld) {
            try {
                held.release()
            } catch (e: RuntimeException) {
                Log.w(TAG, "wake lock was already released", e)
            }
        }
    }
}
