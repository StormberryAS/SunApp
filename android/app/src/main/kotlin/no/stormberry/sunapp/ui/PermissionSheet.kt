package no.stormberry.sunapp.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import no.stormberry.sunapp.alarm.AlarmCapability
import no.stormberry.sunapp.alarm.AlarmCapabilityGap
import no.stormberry.sunapp.ui.components.Notice
import no.stormberry.sunapp.ui.components.SectionLabel

/**
 * The lazy permission request: what Android will not let an alarm clock do until the user
 * says so, asked for at the moment the user creates their first alarm and never before.
 *
 * ### Why this file exists at all
 *
 * Release 1.0.0 of SunApp declares zero permissions, and sun times still need none: the
 * catalogue is bundled, the arithmetic is local, nothing touches the network. Adding alarms
 * does not change that for anybody who does not use alarms, and the owner's fourth confirmed
 * decision is that the app must say so plainly rather than presenting a wall of requests on
 * first launch and hoping the user infers the scope.
 *
 * So the request surface is a bottom sheet that appears exactly once, on the tap that begins
 * the first rule, and every line of copy in it is about alarms. It is deliberately blunt
 * about the thing users of the web app ask first: **there is no location permission here and
 * there never will be**. Coordinates come from the bundled city picker, so SunApp cannot know
 * where you are and does not want to.
 *
 * ### Why the decisions are not in this file
 *
 * What is missing, what each gap costs the user and which Settings screen fixes it are all
 * [AlarmCapability]'s, not the sheet's. That object is shared with the alarm runtime, which
 * has to re-check the same things before every arm, and two implementations of "can this
 * alarm ring" would eventually disagree about a device in the wild. This file is layout and
 * copy over that model, plus the one thing a non-composable object cannot do: hold the
 * activity-result launcher for the single genuine runtime permission among the three.
 *
 * ### Why not simply fire the requests and let Android explain
 *
 * Two of the three are not runtime permissions at all. Exact alarms and full-screen intents
 * are per-app *settings* toggles reached through a system settings screen, with no dialog and
 * no callback; an app that fires the intent without warning drops the user into Settings with
 * no idea why. The sheet exists to give those two a sentence of context each, which is the
 * only thing that makes them answerable.
 *
 * ### The manifest is somebody else's file
 *
 * Requesting a runtime permission that is not declared in `AndroidManifest.xml` fails
 * instantly and silently. `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` and
 * `USE_FULL_SCREEN_INTENT` must all be declared there for this sheet to do anything.
 */

/**
 * Re-read a capability check every time the activity comes back to the foreground.
 *
 * Two of the three grants are changed on a system settings screen, which means the app is in
 * the background when they change and gets no callback when they do. Resume is the only
 * signal there is. The observer is attached to the hosting activity rather than through a
 * lifecycle-aware Compose helper because that would mean a dependency on
 * `lifecycle-runtime-compose`, and one `DisposableEffect` is a smaller thing to carry than an
 * extra artefact in an APK whose pitch is that it is small.
 */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
}

/**
 * The sheet itself.
 *
 * @param gaps what the platform is currently refusing, from [AlarmCapability.gaps]. An empty
 *   list is a real and common state: on API 24 to 30 with notifications on, nothing here is
 *   askable, and the sheet says so in one line rather than inventing something to request.
 * @param onRefresh called after anything that might have changed an answer. The caller
 *   re-reads and passes new [gaps] down; this composable holds no capability state of its
 *   own, so it can never show a stale grant.
 * @param onContinue the user is done here and wants to get on with creating the alarm. This
 *   is reachable with permissions still missing, on purpose: refusing to let someone write a
 *   rule until they have granted everything is coercion, and a rule that cannot ring yet is
 *   still a rule they can finish later.
 * @param onDismiss the sheet was swiped or backed out of.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(
    gaps: List<AlarmCapabilityGap>,
    onRefresh: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Without an activity there is nothing to launch a permission request or a settings screen
    // from. Unreachable in the app; returning is still better than a cast that throws.
    val activity = LocalActivity.current ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    RefreshOnResume(onRefresh)

    // The only true runtime permission of the three. The result is ignored beyond triggering a
    // re-read: a denial is the user's answer, not an error, and the row simply stays with its
    // Settings button, which is the only route left once Android has stopped showing the
    // dialog for a twice-denied permission.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Sun.Surface,
        contentColor = Sun.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Permissions, only for alarms",
                style = MaterialTheme.typography.headlineSmall,
                color = Sun.TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                // The owner's decision-4 sentence, taken from the shared model rather than
                // retyped, so the promise the runtime is written against is the promise the
                // user reads.
                text = AlarmCapability.RATIONALE,
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You are seeing this because you are creating your first alarm, and " +
                    "for no other reason.",
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.TextSecondary,
            )

            Spacer(Modifier.height(18.dp))

            if (gaps.isEmpty()) {
                SectionLabel("Nothing to ask for")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Android is already allowing everything an alarm needs on this " +
                        "device. Nothing further is required from you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Sun.Gold,
                )
            } else {
                SectionLabel("What Android is refusing")
                Spacer(Modifier.height(10.dp))
                gaps.forEachIndexed { index, gap ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    GapRow(
                        gap = gap,
                        onFix = {
                            if (gap == AlarmCapabilityGap.NOTIFICATIONS &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                // The runtime dialog is a far better first ask than a trip to
                                // Settings, and it only exists from API 33.
                                notificationLauncher.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                )
                            } else {
                                activity.open(AlarmCapability.settingsIntent(activity, gap))
                            }
                        },
                        onSettings = {
                            activity.open(AlarmCapability.settingsIntent(activity, gap))
                        }.takeIf {
                            gap == AlarmCapabilityGap.NOTIFICATIONS &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Notice(
                title = "Still no location permission",
                body = "SunApp never asks where you are, for alarms or for anything else. An " +
                    "alarm's sunrise is computed from the city you pick out of the bundled " +
                    "catalogue, on the device, with no network access.",
                accent = Sun.Blue,
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Not now", color = Sun.TextSecondary)
                }
                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Sun.Gold,
                        contentColor = Sun.Background,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (gaps.isEmpty()) "Continue" else "Continue anyway")
                }
            }
        }
    }
}

/**
 * One refusal: what it costs, and the button that fixes it.
 *
 * @param onSettings a second route to the same fix, present only where the primary button is
 *   a runtime dialog that Android may have stopped showing.
 */
@Composable
private fun GapRow(
    gap: AlarmCapabilityGap,
    onFix: () -> Unit,
    onSettings: (() -> Unit)?,
) {
    val accent = if (gap.blocking) Sun.Rose else Sun.Orange
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sun.Card, shape)
            .border(1.dp, accent.copy(alpha = 0.32f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = gap.title,
                style = MaterialTheme.typography.titleSmall,
                color = Sun.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                // A word rather than a glyph: it survives a screen reader and a 200% font
                // scale, and this app does not depend on material-icons.
                text = if (gap.blocking) "Required" else "Optional",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = gap.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onFix) { Text(gap.fixLabel, color = Sun.Gold) }
            if (onSettings != null) {
                TextButton(onClick = onSettings) { Text("Settings", color = Sun.TextSecondary) }
            }
        }
    }
}

/**
 * Start a Settings screen, or do nothing at all.
 *
 * [AlarmCapability.settingsIntent] already falls back to the app's own details page, which
 * every Android build has. This catch is for the layer below that: vendor ROMs that ship a
 * details page guarded by a signature permission, or a device with the Settings app disabled
 * outright. A helper button must never be the thing that crashes an alarm app.
 */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing further to try; the sheet stays open with the row unchanged.
    } catch (_: SecurityException) {
        // As above.
    }
}
