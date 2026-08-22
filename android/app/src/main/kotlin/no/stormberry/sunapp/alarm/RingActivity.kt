package no.stormberry.sunapp.alarm

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import no.stormberry.sunapp.ui.Sun
import no.stormberry.sunapp.ui.SunAppTheme

/**
 * The screen a ringing alarm puts in front of the user, over the lock screen and with the
 * display turned on.
 *
 * It is a pure view over [AlarmService]. It starts nothing, plays nothing, and schedules
 * nothing: the service is already ringing before this activity exists, which is what makes a
 * denied full-screen intent a degradation (the alarm still sounds, the user swipes the
 * notification) rather than a failure. When the service stops for any reason, including a
 * snooze from the notification shade or the ten-minute auto-silence, the state it exposes
 * goes null and this activity closes itself.
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

        setContent {
            SunAppTheme {
                val alarm = AlarmService.ringing.value
                LaunchedEffect(alarm) {
                    if (alarm == null) finish()
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
                                finish()
                            },
                            onDismiss = {
                                sendBroadcast(
                                    AlarmFireReceiver.dismissIntent(
                                        this@RingActivity,
                                        alarm.ruleId,
                                    ),
                                )
                                finish()
                            },
                        )
                    }
                }
            }
        }
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
 */
@Composable
private fun RingScreen(
    alarm: RingingAlarm,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context: Context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = AlarmService.formatTime(context, alarm.fireAtMillis),
                style = MaterialTheme.typography.displayLarge,
                color = Sun.Gold,
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
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = "Snooze ${AlarmFireReceiver.SNOOZE_MINUTES} minutes",
                    color = Sun.TextPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Sun.Gold,
                    contentColor = Sun.Background,
                ),
            ) {
                Text(text = "Dismiss", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

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
