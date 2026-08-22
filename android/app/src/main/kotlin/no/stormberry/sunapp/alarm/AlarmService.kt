package no.stormberry.sunapp.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import no.stormberry.sunapp.R
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.solar.SolarEvent
import java.util.Date
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * What is ringing right now, as far as the ring screen needs to know.
 *
 * Passed to [RingActivity] through process-local state rather than through the activity's
 * intent, because the two have to stay in step: an auto-silence, a snooze from the
 * notification shade, or a second alarm arriving all change what should be on screen while
 * the activity is already open. An intent is a snapshot; this is the current answer.
 */
data class RingingAlarm(
    val ruleId: String?,
    val label: String,
    val placeName: String,
    val anchorText: String,
    val fireAtMillis: Long,
    val usedFallback: Boolean,
    val isSnooze: Boolean,
    val ringtoneUri: String?,
    val vibrate: Boolean,
)

/**
 * Plays the alarm: foreground service, alarm-stream audio, vibration, and the notification
 * that carries the full-screen intent.
 *
 * A foreground service rather than a plain one because the ringing must survive the user
 * doing nothing at all for ten minutes on a device that wants to sleep, and because from
 * Android 12 a background app may not start a service without an exemption. `setAlarmClock`
 * provides exactly that exemption when it fires, which is the second reason it is the only
 * alarm API this app uses.
 *
 * Three properties are worth stating because each one is a way alarm clocks commonly fail.
 *
 * **The audio is on the alarm stream, always.** `USAGE_ALARM` with
 * `CONTENT_TYPE_SONIFICATION` means the alarm plays through the alarm volume, which is the
 * one channel a user who silences their phone does not usually silence, and which Do Not
 * Disturb is generally configured to let through.
 *
 * **Focus is requested and focus loss is ignored.** An alarm asks for transient focus so a
 * podcast pauses, and then it keeps playing whatever anything else asks for. A media app
 * pausing an alarm because it took focus back is not a state anybody wants at 06:00.
 *
 * **The ringtone is the system's, never a bundled file.** SunApp ships no proprietary audio.
 * The one exception is the generated tone in [startGeneratedTone], which exists solely for
 * the case where the system ringtone genuinely cannot be read.
 */
class AlarmService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var tone: AudioTrack? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var volume = START_VOLUME

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_RING) {
            // A null intent means the system restarted us, which for an alarm is never
            // useful: the moment has passed and re-ringing hours later would be worse than
            // silence. START_NOT_STICKY below makes this close to unreachable.
            stopSelf()
            return START_NOT_STICKY
        }

        // A second alarm can arrive while the first is still ringing (two rules, one minute
        // apart, or a snooze landing on a neighbouring rule). Tear the first one down rather
        // than layering two MediaPlayers on the alarm stream.
        stopRinging()

        val alarm = ringingAlarmFrom(intent)
        current.value = alarm

        if (!startInForeground(buildRingNotification(alarm))) {
            // Without foreground status the service will be killed within seconds, so the
            // best remaining move is to post the same notification as a plain one and let its
            // full-screen intent and heads-up do the waking. Degraded, but not silent.
            postFallbackNotification(alarm)
            current.value = null
            AlarmWakeLock.release()
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        // The receiver's wake lock has done its job now that this service holds its own.
        AlarmWakeLock.release()

        volume = START_VOLUME
        startAudio(alarm)
        startVibration(alarm)
        handler.postDelayed(crescendo, CRESCENDO_INTERVAL_MS)
        handler.postDelayed({ autoSilence(alarm) }, AUTO_SILENCE_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        current.value = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground and notice

    private fun startInForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID_RING, notification, foregroundType())
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException on 12 and later, SecurityException on 14
        // and later if the declared type is ever wrong. Both are fatal to the service and
        // neither is worth crashing over.
        Log.e(TAG, "could not enter the foreground", e)
        false
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

    private fun buildRingNotification(alarm: RingingAlarm): Notification {
        val ring = PendingIntent.getActivity(
            this,
            REQUEST_RING_SCREEN,
            Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            this,
            REQUEST_SNOOZE,
            AlarmFireReceiver.snoozeIntent(this, alarm.ruleId, alarm.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            this,
            REQUEST_DISMISS,
            AlarmFireReceiver.dismissIntent(this, alarm.ruleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = summaryFor(this, alarm)
        return NotificationCompat.Builder(this, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(alarm.label)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            // This notification makes no sound of its own: the service owns the audio, so
            // that it can crescendo, keep playing through focus loss, and stop on a dismiss.
            .setSilent(true)
            .setFullScreenIntent(ring, true)
            .setContentIntent(ring)
            .addAction(0, "Snooze ${AlarmFireReceiver.SNOOZE_MINUTES} min", snooze)
            .addAction(0, "Dismiss", dismiss)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun postFallbackNotification(alarm: RingingAlarm) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID_RING, buildRingNotification(alarm))
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not post the fallback ring notification", e)
        }
    }

    private fun autoSilence(alarm: RingingAlarm) {
        Log.i(TAG, "auto-silencing after ${AUTO_SILENCE_MS / 60_000} minutes")
        postMissedNotice(this, alarm)
        stopSelf()
    }

    // ------------------------------------------------------------------------------- audio

    private fun startAudio(alarm: RingingAlarm) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        requestFocus(attributes)
        val uri = ringtoneFor(alarm)
        if (uri != null && startRingtone(uri, attributes)) return
        Log.w(TAG, "no playable system ringtone, using the generated tone")
        startGeneratedTone(attributes)
    }

    /**
     * The alarm sound to use: the rule's own choice, then the user's default alarm ringtone,
     * then the platform's built-in default.
     *
     * The rule's choice arrives in the intent rather than being read here, so that this
     * service never touches the rule store. That is not tidiness: the store is
     * credential-encrypted, and the one morning this code path matters most is the one after
     * an unattended reboot, when it cannot be read at all.
     */
    private fun ringtoneFor(alarm: RingingAlarm): Uri? {
        val configured = alarm.ringtoneUri?.let { runCatching { it.toUri() }.getOrNull() }
        if (configured != null) return configured
        return runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
        }.getOrNull() ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    private fun startRingtone(uri: Uri, attributes: AudioAttributes): Boolean = try {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(attributes)
            // Keeps the CPU awake for the decode itself, independently of the service's own
            // wake lock. Requires WAKE_LOCK, which is declared for exactly this reason.
            setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(this@AlarmService, uri)
            isLooping = true
            setVolume(START_VOLUME, START_VOLUME)
            // Synchronous prepare: the source is local, and an asynchronous one would mean an
            // alarm that starts ringing at an unpredictable moment after it was due.
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        // Deliberately broad. Before first unlock this throws because the media provider is
        // credential-encrypted and the ringtone URI simply cannot be resolved yet, which is
        // the single most important case to survive: a device that rebooted overnight must
        // still wake its owner. It also covers a ringtone on removed storage, a URI whose
        // permission was revoked, and a codec failure.
        Log.w(TAG, "could not play the ringtone $uri", e)
        releasePlayer()
        false
    }

    /**
     * The fallback tone, synthesised rather than bundled.
     *
     * SunApp ships no audio file on purpose: anything worth listening to is proprietary, and
     * anything free is either enormous or unpleasant. Two seconds of arithmetic produces a
     * two-note pattern that is unmistakably an alarm, weighs nothing in the APK, and is
     * available before first unlock when nothing else is. Silence, which is what a missing
     * ringtone would otherwise mean, is not an acceptable answer for an alarm clock.
     */
    private fun startGeneratedTone(attributes: AudioAttributes) {
        try {
            val samples = generateTone()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * BYTES_PER_SAMPLE)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            // Static mode plus an infinite loop, so the pattern repeats with no gap and no
            // per-cycle work: the buffer is written once and the hardware loops it.
            track.setLoopPoints(0, samples.size, -1)
            track.setVolume(START_VOLUME)
            track.play()
            tone = track
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not start the generated tone either", e)
            releaseTone()
        }
    }

    /**
     * One cycle of the fallback pattern: a high note, a low note, then a rest.
     *
     * Each note is shaped with a short linear ramp in and out. Without it the buffer starts
     * and ends mid-wave, and the loop point produces an audible click on every repeat.
     */
    private fun generateTone(): ShortArray {
        val total = SAMPLE_RATE_HZ * TONE_CYCLE_MS / 1000
        val slot = SAMPLE_RATE_HZ * TONE_SLOT_MS / 1000
        val noteSamples = SAMPLE_RATE_HZ * TONE_NOTE_MS / 1000
        val ramp = SAMPLE_RATE_HZ * TONE_RAMP_MS / 1000
        val out = ShortArray(total)
        val frequencies = doubleArrayOf(TONE_HIGH_HZ, TONE_LOW_HZ)
        for (note in frequencies.indices) {
            val start = note * slot
            for (i in 0 until noteSamples) {
                val index = start + i
                if (index >= total) break
                val envelope = min(1.0, min(i, noteSamples - i).toDouble() / ramp)
                val wave = sin(2.0 * PI * frequencies[note] * i / SAMPLE_RATE_HZ)
                out[index] = (wave * envelope * TONE_AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun requestFocus(attributes: AudioAttributes) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    // No listener body on purpose: an alarm never yields. Losing focus to a
                    // call or a media app must not stop the sound that is trying to wake
                    // somebody up.
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = request
                audio.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        } catch (e: RuntimeException) {
            // Focus is a courtesy to other apps. Failing to get it is not a reason to skip
            // the alarm.
            Log.w(TAG, "could not take audio focus", e)
        }
    }

    private fun abandonFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        try {
            val request = focusRequest
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                audio.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(null)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not release audio focus", e)
        }
        focusRequest = null
    }

    /**
     * Fade in over half a minute.
     *
     * Starting at full volume is how an alarm becomes something the user disables. Starting
     * near-silent and climbing gives a light sleeper a chance to wake gently and a heavy one
     * the full volume thirty seconds later, which is still long before the ten-minute
     * auto-silence.
     */
    private val crescendo = object : Runnable {
        override fun run() {
            volume = min(1f, volume + CRESCENDO_STEP)
            mediaPlayer?.runCatching { setVolume(volume, volume) }
            tone?.runCatching { setVolume(volume) }
            if (volume < 1f) handler.postDelayed(this, CRESCENDO_INTERVAL_MS)
        }
    }

    // --------------------------------------------------------------------------- vibration

    private fun startVibration(alarm: RingingAlarm) {
        if (!alarm.vibrate) return
        val device = resolveVibrator() ?: return
        vibrator = device
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // USAGE_ALARM is what keeps the vibration alive under Do Not Disturb and
                    // under a "vibrate off" setting that was only ever meant for messages.
                    device.vibrate(
                        effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrate(effect, alarmAudioAttributes())
                }
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(VIBRATION_PATTERN, VIBRATION_REPEAT, alarmAudioAttributes())
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not start vibration", e)
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun alarmAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // ------------------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunApp:ring").apply {
            setReferenceCounted(false)
            // Never longer than the auto-silence plus a margin: a wake lock outliving the
            // sound it was taken for is a battery bug the user cannot see.
            acquire(AUTO_SILENCE_MS + WAKE_LOCK_MARGIN_MS)
        }
    }

    private fun releaseWakeLock() {
        val held = wakeLock ?: return
        wakeLock = null
        if (held.isHeld) runCatching { held.release() }
    }

    // --------------------------------------------------------------------------- teardown

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        releaseTone()
        abandonFocus()
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun releaseTone() {
        val track = tone ?: return
        tone = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun ringingAlarmFrom(intent: Intent): RingingAlarm = RingingAlarm(
        ruleId = intent.getStringExtra(AlarmFireReceiver.EXTRA_RULE_ID),
        label = intent.getStringExtra(EXTRA_LABEL) ?: AlarmFireReceiver.DEFAULT_LABEL,
        placeName = intent.getStringExtra(EXTRA_PLACE).orEmpty(),
        anchorText = intent.getStringExtra(EXTRA_ANCHOR_TEXT).orEmpty(),
        fireAtMillis = intent.getLongExtra(AlarmFireReceiver.EXTRA_FIRE_AT, 0L),
        usedFallback = intent.getBooleanExtra(AlarmFireReceiver.EXTRA_USED_FALLBACK, false),
        isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false),
        ringtoneUri = intent.getStringExtra(EXTRA_RINGTONE),
        // Defaults to true when the rule could not be read, matching AlarmRule's own default.
        // A silent, still phone is the one outcome an alarm must never produce by accident.
        vibrate = intent.getBooleanExtra(EXTRA_VIBRATE, true),
    )

    companion object {

        private const val TAG = "SunAlarmService"

        /** The only action this service accepts. */
        const val ACTION_RING: String = "no.stormberry.sunapp.action.RING"

        private const val EXTRA_LABEL = "no.stormberry.sunapp.extra.SERVICE_LABEL"
        private const val EXTRA_PLACE = "no.stormberry.sunapp.extra.PLACE"
        private const val EXTRA_ANCHOR_TEXT = "no.stormberry.sunapp.extra.ANCHOR_TEXT"
        private const val EXTRA_IS_SNOOZE = "no.stormberry.sunapp.extra.IS_SNOOZE"
        private const val EXTRA_RINGTONE = "no.stormberry.sunapp.extra.RINGTONE"
        private const val EXTRA_VIBRATE = "no.stormberry.sunapp.extra.VIBRATE"

        /** The ringing alarm. Silent as a notification: the service plays the sound itself. */
        const val CHANNEL_RING: String = "alarm_ring"

        /** Snoozed and auto-silenced alarms. Ordinary importance, no full-screen intent. */
        const val CHANNEL_NOTICES: String = "alarm_notices"

        private const val NOTIFICATION_ID_RING = 0x5A01
        private const val NOTIFICATION_ID_NOTICE = 0x5A02

        private const val REQUEST_RING_SCREEN = 0x5A10
        private const val REQUEST_SNOOZE = 0x5A11
        private const val REQUEST_DISMISS = 0x5A12

        /**
         * How long an unanswered alarm rings before it gives up.
         *
         * Ten minutes matches what every other alarm clock does. The lower bound is set by
         * the user being asleep; the upper bound by a phone in a bag on a train, where an
         * alarm nobody can hear should eventually stop rather than flatten the battery.
         */
        private const val AUTO_SILENCE_MS = 10L * 60L * 1000L
        private const val WAKE_LOCK_MARGIN_MS = 30L * 1000L

        private const val START_VOLUME = 0.12f
        private const val CRESCENDO_INTERVAL_MS = 500L
        /** 0.12 to 1.0 in half-second steps takes about thirty seconds. */
        private const val CRESCENDO_STEP = 0.0147f

        private val VIBRATION_PATTERN = longArrayOf(0L, 500L, 800L)
        private const val VIBRATION_REPEAT = 0

        private const val SAMPLE_RATE_HZ = 44_100
        private const val BYTES_PER_SAMPLE = 2
        private const val TONE_CYCLE_MS = 1_400
        private const val TONE_SLOT_MS = 450
        private const val TONE_NOTE_MS = 350
        private const val TONE_RAMP_MS = 12
        private const val TONE_HIGH_HZ = 880.0
        private const val TONE_LOW_HZ = 660.0
        private const val TONE_AMPLITUDE = 0.6

        /**
         * Process-local state describing the alarm currently ringing, or null when nothing is.
         *
         * A Compose `State` rather than a listener or a binder because the only consumer is
         * [RingActivity], which lives in this same process and is written in Compose:
         * recomposition on change is the whole mechanism, and the activity finishing itself
         * when this goes null is how a snooze from the notification shade closes the screen.
         */
        private val current = mutableStateOf<RingingAlarm?>(null)

        /** The alarm currently ringing, for the ring screen to render. */
        val ringing: State<RingingAlarm?> get() = current

        /**
         * Create both notification channels if they do not exist.
         *
         * Idempotent and cheap, and called from every entry point rather than once at
         * startup: a channel created before the process was last killed is still there, but a
         * service that assumes so and is wrong posts a notification into nothing, which on
         * API 26 and later means the foreground service dies on the spot.
         *
         * Re-running it after a locale change refreshes the visible names. Everything else
         * about a channel (importance, sound, vibration) belongs to the user once created,
         * and re-creating deliberately does not override their choices.
         */
        fun ensureChannels(context: Context) {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_RING, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Ringing alarms")
                    .setDescription("The alarm itself, while it is ringing.")
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .setShowBadge(false)
                    .build(),
            )
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL_NOTICES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Snoozed and missed alarms")
                    .setDescription("Told you an alarm was snoozed, or that it rang unanswered.")
                    .setShowBadge(false)
                    .build(),
            )
        }

        /**
         * The intent that starts a ring, with everything the service needs to describe the
         * alarm without reading the rule store.
         *
         * The rule is resolved by the caller and flattened into extras here, because the
         * service can be started on a locked device where the rule is unreadable. Passing a
         * rule id alone would mean a ring screen that says nothing but "Alarm".
         */
        fun ringIntent(
            context: Context,
            ruleId: String?,
            rule: AlarmRule?,
            fireAtMillis: Long,
            usedFallback: Boolean,
            isSnooze: Boolean,
        ): Intent = Intent(context, AlarmService::class.java)
            .setAction(ACTION_RING)
            .putExtra(AlarmFireReceiver.EXTRA_RULE_ID, ruleId ?: rule?.id)
            .putExtra(
                EXTRA_LABEL,
                rule?.label?.takeIf { it.isNotBlank() } ?: AlarmFireReceiver.DEFAULT_LABEL,
            )
            .putExtra(EXTRA_PLACE, rule?.placeName.orEmpty())
            .putExtra(EXTRA_ANCHOR_TEXT, rule?.let { describeAnchor(it) }.orEmpty())
            .putExtra(AlarmFireReceiver.EXTRA_FIRE_AT, fireAtMillis)
            .putExtra(AlarmFireReceiver.EXTRA_USED_FALLBACK, usedFallback)
            .putExtra(EXTRA_IS_SNOOZE, isSnooze)
            .putExtra(EXTRA_RINGTONE, rule?.ringtoneUri)
            .putExtra(EXTRA_VIBRATE, rule?.vibrate ?: true)

        /** Tell the user their alarm was snoozed and when it will come back. */
        @SuppressLint("MissingPermission")
        fun postSnoozedNotice(context: Context, label: String, untilMillis: Long) {
            ensureChannels(context)
            val notice = NotificationCompat.Builder(context, CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("$label snoozed")
                .setContentText("Ringing again at ${formatTime(context, untilMillis)}.")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_NOTICE, notice) }
        }

        /**
         * Tell the user an alarm rang and nobody answered.
         *
         * An alarm that stops on its own and leaves no trace is indistinguishable from an
         * alarm that never rang, and the two need very different responses from the user.
         */
        @SuppressLint("MissingPermission")
        private fun postMissedNotice(context: Context, alarm: RingingAlarm) {
            ensureChannels(context)
            val minutes = AUTO_SILENCE_MS / 60_000
            val notice = NotificationCompat.Builder(context, CHANNEL_NOTICES)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle("Missed ${alarm.label}")
                .setContentText(
                    "Rang at ${formatTime(context, alarm.fireAtMillis)} for $minutes minutes " +
                        "with no response.",
                )
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_NOTICE, notice) }
        }

        /**
         * The one-line description under the alarm's name, on both the notification and the
         * ring screen.
         *
         * The fallback warning is not decoration. A rule anchored to sunrise in Tromsø in
         * December has no sunrise to anchor to, so it rang at solar noon instead (owner's
         * confirmed decision 1), and a user who is not told that will reasonably conclude the
         * app is broken.
         */
        fun summaryFor(context: Context, alarm: RingingAlarm): String {
            val parts = mutableListOf(formatTime(context, alarm.fireAtMillis))
            if (alarm.placeName.isNotBlank()) parts += alarm.placeName
            if (alarm.anchorText.isNotBlank()) parts += alarm.anchorText
            if (alarm.isSnooze) parts += "snoozed"
            val line = parts.joinToString(", ")
            return if (alarm.usedFallback) {
                "$line. The sun did not reach that point today, so this alarm used solar noon."
            } else {
                line
            }
        }

        /** Respects the user's 12 or 24 hour setting, which java.time formatting does not. */
        fun formatTime(context: Context, millis: Long): String =
            DateFormat.getTimeFormat(context).format(Date(millis))

        /**
         * "Sunrise, 30 minutes before" and friends.
         *
         * Covers all fourteen solar events even though the editor offers only three at 1.1.0
         * (owner's confirmed decision 5), because a rule written by a later version, or by
         * hand, must still describe itself rather than showing an enum name at six in the
         * morning.
         */
        fun describeAnchor(rule: AlarmRule): String {
            val anchor = when (rule.anchor) {
                SolarEvent.SUNRISE -> "sunrise"
                SolarEvent.SUNSET -> "sunset"
                SolarEvent.SOLAR_NOON -> "solar noon"
                SolarEvent.NADIR -> "solar midnight"
                SolarEvent.SUNRISE_END -> "the end of sunrise"
                SolarEvent.SUNSET_START -> "the start of sunset"
                SolarEvent.DAWN -> "dawn"
                SolarEvent.DUSK -> "dusk"
                SolarEvent.NAUTICAL_DAWN -> "nautical dawn"
                SolarEvent.NAUTICAL_DUSK -> "nautical dusk"
                SolarEvent.NIGHT_END -> "the end of night"
                SolarEvent.NIGHT -> "nightfall"
                SolarEvent.GOLDEN_HOUR_END -> "the end of the golden hour"
                SolarEvent.GOLDEN_HOUR -> "the golden hour"
            }
            val minutes = rule.offsetMinutes
            return when {
                rule.direction == Direction.AT || minutes == 0 -> "at $anchor"
                rule.direction == Direction.BEFORE -> "$minutes min before $anchor"
                else -> "$minutes min after $anchor"
            }
        }
    }
}
