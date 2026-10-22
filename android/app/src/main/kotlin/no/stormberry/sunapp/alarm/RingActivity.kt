package no.stormberry.sunapp.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.stormberry.sunapp.ui.Sun
import no.stormberry.sunapp.ui.SunAppTheme

/**
 * The screen a ringing alarm puts in front of the user, over the lock screen and with the
 * display turned on.
 *
 * It is a pure view over [AlarmService]. It starts nothing, plays nothing, and schedules
 * nothing: the service is already ringing before this activity exists, which is what makes a
 * denied full-screen intent a degradation (the alarm still sounds, the user swipes the
 * notification) rather than a failure. When the ringing stops for any reason, including a
 * snooze from the notification shade or the ten-minute auto-silence, the state it exposes
 * goes null and this activity closes itself.
 *
 * Since 1.1.4 it shows the backup ring notification's alarm too (A-2, A-4), which may have been
 * posted by a process that no longer exists, so that alarm rides along in the intent. Snooze and
 * Dismiss no longer close the screen themselves: it closes once the ringing has actually stopped,
 * so a snooze that could not be armed leaves the alarm ringing with Dismiss still in reach (A-7).
 * MainActivity opens it while an alarm rings (A-1).
 *
 * Two window behaviours matter and both split at API 27. Before 27 the only way to appear
 * over the keyguard and wake the screen was a pair of window flags, deprecated but still the
 * only mechanism on API 24 to 26; from 27 there are proper activity methods, and the flags
 * became unreliable. Both are needed, because minSdk is 24.
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        // Swallow Back, including the predictive-back gesture. A half-awake person reaching
        // for their phone should not be able to silence an alarm with the gesture they use to
        // leave every other screen; dismissing has to be a deliberate press on a control that
        // says what it does. Registered as an enabled callback rather than by overriding
        // onBackPressed so the modern dispatcher, which is what targetSdk 36 actually uses,
        // is the thing being told.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        adoptBackup(intent)

        setContent {
            SunAppTheme {
                val alarm = AlarmService.ringing.value
                LaunchedEffect(alarm) {
                    if (alarm == null) {
                        finish()
                        return@LaunchedEffect
                    }
                    // A backup ring has no service to stop it: look again at its ring timeout.
                    val left = alarm.startedAtMillis + AlarmService.AUTO_SILENCE_MS - System.currentTimeMillis()
                    if (left > 0L) delay(left + TIMEOUT_SLACK_MS)
                    if (AlarmService.current() == null) {
                        AlarmService.setBackup(null)
                        finish()
                    }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = Sun.Background) {
                    if (alarm != null) {
                        RingScreen(
                            alarm = alarm,
                            onSnooze = {
                                sendBroadcast(
                                    AlarmFireReceiver.snoozeIntent(
                                        this@RingActivity,
                                        alarm.ruleId,
                                        alarm.label,
                                    ),
                                )
                            },
                            onDismiss = {
                                sendBroadcast(
                                    AlarmFireReceiver.dismissIntent(
                                        this@RingActivity,
                                        alarm.ruleId,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        adoptBackup(intent)
    }

    /**
     * Opened by the backup ring notification, perhaps in a process that did not post it: the
     * alarm comes with the intent, and is shown until its ring timeout unless the service rings.
     */
    private fun adoptBackup(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_BACKUP, false) != true) return
        val alarm = AlarmService.alarmFrom(intent)
        val current = AlarmService.current()
        if (current != null && current.key == alarm.key) return
        if (current == null &&
            System.currentTimeMillis() < alarm.startedAtMillis + AlarmService.AUTO_SILENCE_MS
        ) {
            AlarmService.setBackup(alarm)
        }
    }

    companion object {
        /** On the backup ring notification's intent: the alarm rides along in the extras. */
        const val EXTRA_BACKUP: String = "no.stormberry.sunapp.extra.BACKUP"

        private const val TIMEOUT_SLACK_MS = 500L
    }

    /**
     * Appear over the keyguard, turn the display on, and keep it on while ringing.
     *
     * `FLAG_KEEP_SCREEN_ON` is set on every version because it never had a method equivalent,
     * and without it the display can time out mid-alarm and leave the user prodding a black
     * screen that is playing a sound.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * The ring screen itself.
 *
 * Laid out so that the controls sit at the bottom, inside the safe-drawing insets: this is an
 * edge-to-edge window on a device that may have a gesture bar, and a Dismiss button under the
 * gesture area is a button that swallows presses at the worst possible moment.
 *
 * 1.1.4, reproduced on an API 36 emulator at font size 2.0 in landscape (the defect
 * EclipseTracker's fix C found as S-3 and fix D as N-1 in the screen it inherited from this
 * one): nothing scrolled, so the text pushed Dismiss off the bottom of the screen and only
 * Snooze could be reached. Now the time, the name and the details scroll, and Snooze and
 * Dismiss stay fixed below them, side by side in landscape, so Dismiss is always on screen
 * whole. The time keeps its size unless that would not fit: it is never larger than 96 dp,
 * whatever the font size, and shrinks to fit the width on one line rather than being cut.
 * At font size 1.0 in portrait nothing looks different from 1.1.3.
 */
@Composable
private fun RingScreen(
    alarm: RingingAlarm,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context: Context = LocalContext.current
    val window = LocalWindowInfo.current.containerSize
    val landscape = window.width > window.height
    val density = LocalDensity.current
    val display = MaterialTheme.typography.displayLarge
    // The 1.1.3 size, but never above 96 dp, and down to 32 dp before it may be cut.
    val timeMax = with(density) { minOf(display.fontSize.toPx(), TIME_MAX_DP.dp.toPx()).toSp() }
    val timeMin = with(density) { minOf(TIME_MIN_DP.dp.toPx(), timeMax.toPx()).toSp() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = if (landscape) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (landscape) 8.dp else 48.dp))
            BasicText(
                text = AlarmService.formatTime(context, alarm.fireAtMillis),
                modifier = Modifier.fillMaxWidth(),
                style = display.copy(color = Sun.Gold, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = timeMin, maxFontSize = timeMax),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = alarm.label,
                style = MaterialTheme.typography.headlineSmall,
                color = Sun.TextPrimary,
                textAlign = TextAlign.Center,
            )
            val detail = listOfNotNull(
                alarm.placeName.takeIf { it.isNotBlank() },
                alarm.anchorText.takeIf { it.isNotBlank() },
                "snoozed".takeIf { alarm.isSnooze },
            ).joinToString(", ")
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Sun.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (alarm.usedFallback) {
                Spacer(Modifier.height(20.dp))
                FallbackWarning()
            }
            Spacer(Modifier.height(16.dp))
        }

        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnoozeButton(onSnooze, Modifier.weight(1f))
                DismissButton(onDismiss, Modifier.weight(1f), minHeight = 56.dp)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnoozeButton(onSnooze, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                DismissButton(onDismiss, Modifier.fillMaxWidth(), minHeight = 64.dp)
            }
        }
    }
}

/** At least the 1.1.3 height; taller when a large font size needs it. */
@Composable
private fun SnoozeButton(onSnooze: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onSnooze,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = "Snooze ${AlarmFireReceiver.SNOOZE_MINUTES} minutes",
            color = Sun.TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DismissButton(onDismiss: () -> Unit, modifier: Modifier, minHeight: Dp) {
    Button(
        onClick = onDismiss,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Sun.Gold,
            contentColor = Sun.Background,
        ),
    ) {
        Text(text = "Dismiss", style = MaterialTheme.typography.titleMedium)
    }
}

private const val TIME_MAX_DP = 96
private const val TIME_MIN_DP = 32

/**
 * The warning required by the owner's confirmed decision 1.
 *
 * When the anchor event did not happen at all, the alarm fell back to solar noon, and the
 * user is entitled to know that before they conclude the app rang at a random time. Worded as
 * a plain statement of what the sun did, not as an error, because nothing went wrong: this is
 * what a solar alarm inside the polar circle is.
 */
@Composable
private fun FallbackWarning(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Sun.CardHover,
        contentColor = Sun.Orange,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = "The sun did not reach that point today, so this alarm rang at solar noon.",
            modifier = Modifier.padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            style = MaterialTheme.typography.bodyMedium,
            color = Sun.Orange,
            textAlign = TextAlign.Center,
        )
    }
}
