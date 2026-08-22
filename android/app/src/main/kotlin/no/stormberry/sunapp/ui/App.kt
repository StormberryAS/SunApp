package no.stormberry.sunapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.stormberry.sunapp.alarm.AlarmIntentHandoff
import no.stormberry.sunapp.alarm.AlarmScheduling
import no.stormberry.sunapp.data.Settings
import no.stormberry.sunapp.data.SharedPreferencesRuleFile

/**
 * The app's composable root: two screens and the bar that swaps them.
 *
 * Deliberately thin. It builds the two collaborators that must outlive every recomposition,
 * decides which screen opens first, and connects the one wire between the alarms UI and the
 * alarm runtime. Everything else belongs to [SunTimesScreen] and [AlarmsRoot].
 *
 * ### The one wire
 *
 * `onRulesChanged` is the entire coupling between the alarm editor and `AlarmManager`. The
 * alarms screen writes its rules to the store and then says so; [AlarmScheduling.replan] reads
 * that store back and arms whatever it finds. The UI therefore never holds a `PendingIntent`
 * and the runtime never holds a Compose state, and the identical call re-arms everything
 * after a reboot, a timezone change or a fired alarm without any of those paths going near
 * this file.
 *
 * ### The first-run notice
 *
 * The one thing this root does that is not navigation: it decides whether [FirstRunNotice]
 * opens, because that decision belongs to the app rather than to either screen. The rule and
 * the copy are in `FirstRunNotice.kt`; all that happens here is reading the stored version
 * once and writing it back when the user acknowledges.
 *
 * ### Why a bottom bar rather than a top one
 *
 * The sun-times screen owns the header with the mark and the tagline, and it scrolls. Putting
 * navigation above it would either duplicate that header or push it off the top of a phone;
 * putting it below leaves both screens exactly as they were designed and puts the control
 * where a thumb already is.
 *
 * [Settings] and the rule file are `remember`ed rather than constructed inline because
 * `getSharedPreferences` hits the filesystem on first call, and a composable recomposes far
 * more often than a preference file needs opening.
 */
@Composable
fun SunApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val ruleFile = remember { SharedPreferencesRuleFile(context) }

    // An AlarmClock intent that reached AlarmIntentActivity a moment ago has left a note. It
    // is only peeked at here, to choose the opening tab; AlarmsRoot is what consumes it, so
    // the seed cannot be lost by the app opening on the wrong screen.
    var tab by remember {
        mutableStateOf(if (AlarmIntentHandoff.pending(context)) SunTab.ALARMS else SunTab.SUN_TIMES)
    }

    // Read once, at the first composition. The dialog's own dismissal is what writes the
    // store, so re-reading the preference on every recomposition would only create a window
    // in which an already-answered notice could come back.
    var showNotice by remember {
        mutableStateOf(shouldShowFirstRunNotice(settings.firstRunNoticeSeenVersion))
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (tab) {
            SunTab.SUN_TIMES -> SunTimesScreen(
                settings = settings,
                modifier = Modifier.weight(1f),
            )

            SunTab.ALARMS -> AlarmsRoot(
                ruleFile = ruleFile,
                // Whatever the user was last looking at is nearly always what they want an
                // alarm for. Offered as a starting value in the editor, never imposed.
                defaultPlace = settings.place,
                onRulesChanged = { AlarmScheduling.replan(context) },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = Sun.Border)
        SunTabBar(tab = tab, onSelect = { tab = it })

        // A dialog composes into its own window and emits no node into this Column, so it
        // sits here for readability rather than for layout. It is the last child because it
        // is the last thing this file is responsible for, not because order matters.
        if (showNotice) {
            FirstRunNotice(onDismiss = {
                settings.firstRunNoticeSeenVersion = FIRST_RUN_NOTICE_VERSION
                showNotice = false
            })
        }
    }
}

/** The two things SunApp does. */
enum class SunTab { SUN_TIMES, ALARMS }

@Composable
private fun SunTabBar(tab: SunTab, onSelect: (SunTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sun.Background)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TabChip("Sun times", tab == SunTab.SUN_TIMES, { onSelect(SunTab.SUN_TIMES) }, Modifier.weight(1f))
        TabChip("Alarms", tab == SunTab.ALARMS, { onSelect(SunTab.ALARMS) }, Modifier.weight(1f))
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = { if (!selected) onSelect() },
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Sun.Surface,
            labelColor = Sun.TextSecondary,
            selectedContainerColor = Sun.CardHover,
            selectedLabelColor = Sun.Gold,
        ),
        border = null,
        modifier = modifier,
    )
}
