package no.stormberry.sunapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import no.stormberry.sunapp.alarm.AlarmScheduling
import no.stormberry.sunapp.alarm.AlarmService
import no.stormberry.sunapp.alarm.RingActivity
import no.stormberry.sunapp.ui.Sun
import no.stormberry.sunapp.ui.SunApp
import no.stormberry.sunapp.ui.SunAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SunAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Sun.Background,
                ) {
                    SunApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .consumeWindowInsets(WindowInsets.systemBars),
                    )
                }
            }
        }

        // 1.1.4, A-1: while this screen is in front, an alarm that rings brings the ring screen:
        // at once on resume when one already rings, and whenever one starts while it is open.
        // With notifications off or the ring channel turned down an alarm rings with nothing on
        // screen to stop it, so opening SunApp has to be a way to stop it (EclipseTracker's
        // showRingScreenIfRinging).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { AlarmService.ringing.value }.collect { showRingScreenIfRinging() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        replanInBackground()
    }

    /**
     * 1.1.4, A-2: re-plan whenever the app comes to the front. On Android 12 and 12L, switching
     * exact alarms off cancels every exact alarm with no broadcast to say so, and until 1.1.4
     * nothing re-armed them before the next reboot, clock change or rule edit. Off the main
     * thread, as SystemEventReceiver does: the work is small, but it is a JSON parse and a solar
     * evaluation per rule.
     */
    private fun replanInBackground() {
        val appContext = applicationContext
        Thread({
            try {
                AlarmScheduling.replan(appContext)
            } catch (e: RuntimeException) {
                Log.e(TAG, "re-planning on resume failed", e)
            }
        }, "sunapp-resume-replan").start()
    }

    private fun showRingScreenIfRinging() {
        if (AlarmService.current() == null || isFinishing || isDestroyed) return
        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not open the ring screen", e)
        }
    }

    private companion object {
        const val TAG = "SunMain"
    }
}
