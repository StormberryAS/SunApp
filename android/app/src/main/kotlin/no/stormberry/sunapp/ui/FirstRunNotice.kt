package no.stormberry.sunapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import no.stormberry.sunapp.ui.components.Notice

/**
 * What SunApp says about itself before the first launch gets going.
 *
 * ### Why a first-run notice at all
 *
 * The disclaimer that governs every Stormberry app is in `DISCLAIMER.md`, and a document
 * nobody opens sets no expectations. This dialog exists so that the three things a user would
 * be entitled to be annoyed about later are things they demonstrably saw first: that this is a
 * prototype rather than an instrument, that its figures are computed rather than observed, and
 * that an Android alarm is best effort no matter how correct the arithmetic behind it is.
 *
 * It is not an apology for the app. The solar arithmetic is tested against the web app's own
 * `suncalc.js` to the second, and the alarm runtime re-checks its capabilities before every
 * arm. Saying plainly what a calculation can and cannot promise is the opposite of hedging.
 *
 * ### Why it is dismissible rather than blocking
 *
 * SunApp computes real figures from published algorithms, so nothing here needs an active
 * acknowledgement the way a simulated-data app would. One reading is enough: "Got it" closes
 * it and [FIRST_RUN_NOTICE_VERSION] keeps it closed. The owner's tiering decision, in full,
 * is in `DISCLAIMER.md`.
 *
 * ### Why a dialog rather than the bottom sheet used elsewhere
 *
 * `PermissionSheet` is a sheet because it answers a tap: the user asked for an alarm and the
 * sheet is the reply. This notice answers nothing. It arrives unbidden on a screen the user
 * has not seen yet, so it takes the centre of the screen and one button, which is the shape
 * users already read as "acknowledge and continue".
 *
 * ### The permission surface is untouched
 *
 * Nothing in this file needs a permission, a network call or a file outside the app's own
 * preferences. `android/expected-permissions.txt` is unchanged by it, and the CI diff against
 * the built APK is what keeps that claim honest.
 */

/**
 * Which revision of the notice text this build carries.
 *
 * Bump it only when the wording changes **materially**, because a bump re-shows the dialog to
 * every existing install, and a notice that reappears after a typo fix teaches people to
 * dismiss it unread. Fixing a comma is not a bump; adding a fourth point is.
 *
 * Deliberately starts at 1 rather than 0: 0 is the value a fresh install reads back from
 * SharedPreferences when the key is absent, so it has to mean "seen nothing".
 */
const val FIRST_RUN_NOTICE_VERSION = 1

/**
 * The whole decision behind showing the dialog, as a function of two integers.
 *
 * `<` rather than `!=` on purpose. An install can hold a version this build has never heard
 * of, by downgrading from a later APK or by restoring a backup taken from one, and the honest
 * reading of that state is "they have already read a notice at least as complete as mine",
 * not "show them an older version of it".
 *
 * @param seenVersion what the install has acknowledged, 0 on a fresh install.
 * @param currentVersion what this build would show. A parameter only so a test can drive both
 *   directions without editing the constant.
 */
fun shouldShowFirstRunNotice(
    seenVersion: Int,
    currentVersion: Int = FIRST_RUN_NOTICE_VERSION,
): Boolean = seenVersion < currentVersion

/**
 * The dialog itself.
 *
 * @param onDismiss the notice has been seen. The caller both records the version and stops
 *   composing the dialog; this composable holds no state of its own, so it can never disagree
 *   with what was written to the store.
 */
@Composable
fun FirstRunNotice(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sun.Surface,
        properties = DialogProperties(
            // Back press still closes it, because trapping the user in a dialog is hostile
            // and they have seen it by then either way. A stray tap on the scrim does not,
            // because that is the one dismissal a user can make before reading a word.
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = "A note before you start",
                style = MaterialTheme.typography.titleLarge,
                color = Sun.TextPrimary,
            )
        },
        text = {
            // Scrollable because three paragraphs at a 200% font scale on a short phone is
            // taller than a dialog, and the alarm point is the one that would be cut off.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Point(
                    title = "A functioning prototype",
                    // The owner's agreed sentence, verbatim from DISCLAIMER.md rather than
                    // paraphrased, so the app and the document cannot drift apart.
                    body = "SunApp is a functioning prototype, published to show what " +
                        "Stormberry AS builds. It is not a certified instrument, not a " +
                        "professional service, and not a substitute for an authoritative " +
                        "source.",
                )
                Spacer(Modifier.height(14.dp))
                Point(
                    title = "Calculated, not measured",
                    body = "Sunrise, solar noon and sunset are computed on this device from " +
                        "published astronomical algorithms. The figures are calculated, not " +
                        "measured. Check anything important against an official source.",
                )
                Spacer(Modifier.height(16.dp))
                Notice(
                    // The point that matters most gets the treatment the permission sheet
                    // gives a blocking gap, because this is the one that can cost somebody a
                    // flight rather than a few seconds of sunrise.
                    title = "Alarms are best effort",
                    body = "Android may delay or cancel an alarm when the device is off, out " +
                        "of battery, in a restricted power mode, or when the manufacturer " +
                        "force-stops the app. Samsung and Xiaomi are among the manufacturers " +
                        "known to do this. Do not rely on SunApp as your only alarm for " +
                        "anything that matters.",
                    accent = Sun.Orange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it", color = Sun.Gold)
            }
        },
    )
}

/** One of the two plain points: a gold heading and the sentence under it. */
@Composable
private fun Point(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Sun.Gold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = Sun.TextSecondary,
    )
}
