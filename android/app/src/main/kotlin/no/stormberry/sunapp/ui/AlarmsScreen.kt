package no.stormberry.sunapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import no.stormberry.sunapp.alarm.AlarmCapability
import no.stormberry.sunapp.alarm.AlarmCapabilityGap
import no.stormberry.sunapp.alarm.AlarmIntentHandoff
import no.stormberry.sunapp.alarm.AlarmIntentSeed
import no.stormberry.sunapp.alarm.OccurrenceEngine
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Occurrence
import no.stormberry.sunapp.cities.CityAssets
import no.stormberry.sunapp.data.AlarmRuleFile
import no.stormberry.sunapp.data.AlarmStore
import no.stormberry.sunapp.data.Place
import no.stormberry.sunapp.ui.components.Notice
import no.stormberry.sunapp.ui.components.SectionLabel
import no.stormberry.sunapp.ui.components.SunCard
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The alarms half of SunApp: the rule list, and the editor it opens.
 *
 * ### What this file owns, and what it deliberately does not
 *
 * [AlarmsRoot] owns the rules, the editor draft, the permission sheet and the catalogue. It
 * does **not** own scheduling. Every change to the rule list is written to the store and then
 * announced through `onRulesChanged`, and whatever the host wires to that callback is what
 * arms `AlarmManager`. Keeping the arming out of the UI means the same list works identically
 * whether it is driven by a tap, by a boot receiver or by a test, and it means this screen
 * has no opinion at all about `PendingIntent`s.
 *
 * ### Which clock the list shows
 *
 * Fire times are rendered in the **device's** zone, not the rule's. A rule anchored to sunrise
 * in Bergen fires at an absolute instant; if the phone is in Tokyo that instant is early
 * afternoon there, and "06:41" would be a lie about when the phone will make a noise. Where
 * the two zones differ the row says so and prints the rule's own local time underneath, so
 * neither reading is hidden. The editor's preview does the opposite and stays in the rule's
 * zone, because that screen is about the sun rather than about the phone; each is labelled.
 */

@Composable
fun AlarmsRoot(
    ruleFile: AlarmRuleFile,
    defaultPlace: Place?,
    onRulesChanged: (List<AlarmRule>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Read once. The store is the authority between launches, this list is the authority
    // while the screen is alive, and every write goes through commit() so the two cannot
    // disagree.
    var rules by remember { mutableStateOf(AlarmStore.load(ruleFile)) }
    var draft by remember { mutableStateOf<RuleDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<AlarmRule?>(null) }
    var showPermissions by remember { mutableStateOf(false) }
    var gaps by remember { mutableStateOf(AlarmCapability.gaps(context)) }
    var catalogue by remember { mutableStateOf<CatalogueState>(CatalogueState.Loading) }

    // Exact-alarm access can be revoked from Settings while the app is in the background and
    // the app is never told. Resume is the only moment it can be noticed.
    RefreshOnResume { gaps = AlarmCapability.gaps(context) }

    // Same parse as the sun-times screen. CityAssets caches for the life of the process, so
    // whichever screen is opened first pays the tens of milliseconds and the other gets it
    // free; doing it here as well is what lets the editor be reached directly from an
    // AlarmClock intent without going through the sun-times tab.
    LaunchedEffect(Unit) {
        catalogue = withContext(Dispatchers.IO) {
            try {
                CatalogueState.Ready(CityAssets.load(context))
            } catch (e: IOException) {
                CatalogueState.Failed("The city catalogue could not be read (${e.message}).")
            } catch (e: IllegalArgumentException) {
                CatalogueState.Failed("The city catalogue in this build is malformed (${e.message}).")
            }
        }
    }

    // A minute-resolution clock. Countdowns are shown to the minute, so ticking faster would
    // recompute thirty solar walks for no visible change; ticking slower would let "in 1 min"
    // sit on screen after the alarm had rung.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MILLIS)
            now = Instant.now()
        }
    }

    fun commit(next: List<AlarmRule>) {
        rules = next
        AlarmStore.save(ruleFile, next)
        onRulesChanged(next)
    }

    fun startEditor(seed: RuleDraft) {
        // Owner's confirmed decision 4: permissions are asked for at the moment the first
        // alarm is created and at no other moment. `rules.isEmpty()` is the whole test, so a
        // user who never opens this tab is never asked anything.
        if (rules.isEmpty() && gaps.isNotEmpty()) showPermissions = true
        draft = seed
    }

    fun newDraft(seed: AlarmIntentSeed? = null): RuleDraft = RuleDraft(
        id = UUID.randomUUID().toString(),
        label = seed?.label.orEmpty(),
        // The place the user last looked at on the sun-times screen is nearly always the
        // place they want an alarm for, so it is offered rather than demanded: the city
        // picker is right there and one tap changes it.
        place = defaultPlace,
        vibrate = seed?.vibrate ?: true,
        requestedTime = seed?.requestedTime,
    )

    // An AlarmClock intent may have asked for the editor before this composable existed. The
    // handoff is consumed exactly once, so a configuration change cannot reopen it.
    LaunchedEffect(Unit) {
        val seed = AlarmIntentHandoff.consume(context)
        if (seed != null && seed.openEditor) startEditor(newDraft(seed))
    }

    val editing = draft
    if (editing != null) {
        RuleEditorScreen(
            draft = editing,
            onDraftChange = { draft = it },
            catalogue = catalogue,
            onSave = { rule ->
                val existing = rules.indexOfFirst { it.id == rule.id }
                commit(
                    if (existing >= 0) {
                        rules.toMutableList().also { it[existing] = rule }
                    } else {
                        rules + rule
                    },
                )
                draft = null
            },
            onCancel = { draft = null },
            onDelete = if (editing.isNew) {
                null
            } else {
                {
                    commit(rules.filterNot { it.id == editing.id })
                    draft = null
                }
            },
            modifier = modifier,
        )
    } else {
        val nextFires = remember(rules, now) {
            rules.associate { rule ->
                // A disabled rule has no next firing, and walking a year of solar days to
                // work that out for a row that says "Off" would be pure waste.
                rule.id to if (rule.enabled) OccurrenceEngine.nextOccurrence(rule, now) else null
            }
        }
        AlarmsScreen(
            rules = rules,
            nextFires = nextFires,
            gaps = gaps,
            now = now,
            deviceZone = ZoneId.systemDefault(),
            onAdd = { startEditor(newDraft()) },
            onEdit = { draft = it.toDraft() },
            onToggle = { rule, enabled ->
                commit(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
            },
            onDelete = { pendingDelete = it },
            onFixPermissions = { showPermissions = true },
            modifier = modifier,
        )
    }

    if (showPermissions) {
        PermissionSheet(
            gaps = gaps,
            onRefresh = { gaps = AlarmCapability.gaps(context) },
            onContinue = { showPermissions = false },
            onDismiss = { showPermissions = false },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Sun.Surface,
            title = { Text("Delete this alarm?", color = Sun.TextPrimary) },
            text = {
                Text(
                    text = "\"${rule.label}\" will be removed and will not ring again.",
                    color = Sun.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    commit(rules.filterNot { it.id == rule.id })
                    pendingDelete = null
                }) {
                    Text("Delete", color = Sun.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = Sun.TextSecondary)
                }
            },
        )
    }
}

/**
 * The list itself: stateless, so every path through it is reachable from a preview or a test.
 *
 * @param nextFires one entry per rule id, null for a rule that is switched off.
 * @param deviceZone the zone the phone is in, which is the zone the fire times are printed
 *   in. See the file KDoc for why that is not the rule's zone.
 */
@Composable
fun AlarmsScreen(
    rules: List<AlarmRule>,
    nextFires: Map<String, Occurrence?>,
    gaps: List<AlarmCapabilityGap>,
    now: Instant,
    deviceZone: ZoneId,
    onAdd: () -> Unit,
    onEdit: (AlarmRule) -> Unit,
    onToggle: (AlarmRule, Boolean) -> Unit,
    onDelete: (AlarmRule) -> Unit,
    onFixPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Alarms",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Sun.TextPrimary,
                )
                Text(
                    text = "Anchored to the sun, not to the clock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Sun.TextSecondary,
                )
            }
            Button(
                onClick = onAdd,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Sun.Gold,
                    contentColor = Sun.Background,
                ),
            ) {
                Text("New")
            }
        }

        Spacer(Modifier.height(20.dp))

        // The banner only appears once there is something to protect. Before the first rule
        // exists there is nothing an ungranted permission could break, and nagging about it
        // then would be the pre-emptive request the whole design avoids.
        val banner = if (rules.isEmpty()) null else alarmBanner(gaps)
        if (banner != null) {
            Notice(
                title = banner.title,
                body = banner.explanation,
                // A blocking gap means the alarm makes no sound at all, which is a different
                // conversation from one that means it may be a few minutes late. Two colours,
                // because the user's response to the two should not be the same.
                accent = if (banner.blocking) Sun.Rose else Sun.Orange,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onFixPermissions) { Text(banner.fixLabel, color = Sun.Gold) }
            Spacer(Modifier.height(14.dp))
        }

        if (rules.isEmpty()) {
            EmptyAlarms(onAdd = onAdd)
        } else {
            val soonest = nextFires.values.filterNotNull().minByOrNull { it.fireAt }
            if (soonest != null) {
                SectionLabel("Next alarm")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatCountdown(now, soonest.fireAt),
                    style = MaterialTheme.typography.titleMedium,
                    color = Sun.Gold,
                )
                Spacer(Modifier.height(16.dp))
            }

            rules.forEach { rule ->
                AlarmRow(
                    rule = rule,
                    next = nextFires[rule.id],
                    now = now,
                    deviceZone = deviceZone,
                    onEdit = { onEdit(rule) },
                    onToggle = { onToggle(rule, it) },
                    onDelete = { onDelete(rule) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AlarmRow(
    rule: AlarmRule,
    next: Occurrence?,
    now: Instant,
    deviceZone: ZoneId,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    SunCard(borderColour = if (rule.enabled) Sun.BorderActive else Sun.Border) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Edit ${rule.label}") { onEdit() },
            ) {
                Text(
                    text = rule.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (rule.enabled) Sun.TextPrimary else Sun.TextMuted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = describeTiming(rule.anchor, rule.direction, rule.offsetMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = Sun.TextSecondary,
                )
                Text(
                    text = rule.placeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Sun.TextMuted,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = sunSwitchColours(),
            )
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Delete ${rule.label}") { onDelete() }
                    .padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text("×", color = Sun.TextMuted, fontSize = 20.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (!rule.enabled) {
            Text(
                text = "Off",
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.TextMuted,
            )
            return@SunCard
        }

        if (next == null) {
            // Reachable only if the day walk exhausts its 366-day bound, which needs a rule
            // whose anchor genuinely never resolves. It is a UI state rather than a crash,
            // exactly as OccurrenceEngine's contract says.
            Text(
                text = "No upcoming time could be computed for this place.",
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.Rose,
            )
            return@SunCard
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatFireClock(next.fireAt, deviceZone),
                style = MaterialTheme.typography.headlineSmall,
                color = Sun.Gold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = "  " + formatFireDay(next.fireAt, deviceZone, LocalDate.now(deviceZone)),
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.TextSecondary,
            )
        }
        Text(
            text = formatCountdown(now, next.fireAt),
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
        )

        val ruleZone = resolveZone(rule.zoneId)
        if (ruleZone.normalized() != deviceZone.normalized()) {
            Spacer(Modifier.height(6.dp))
            Text(
                // Both readings, both labelled. An alarm for another timezone is a legitimate
                // thing to want and a confusing thing to display, so neither number is hidden.
                text = "That is ${formatFireClock(next.fireAt, ruleZone)} in ${rule.zoneId}, " +
                    "where the sun is being measured.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextMuted,
            )
        }

        if (next.usedFallback) {
            Spacer(Modifier.height(10.dp))
            Notice(
                title = "No ${anchorLabel(rule.anchor).lowercase(Locale.UK)} " +
                    describeFallbackDay(next.anchorDate, LocalDate.now(resolveZone(rule.zoneId))),
                body = "The sun does not reach that point at ${rule.placeName} on that date, " +
                    "so this alarm rings at solar noon instead. Open it to see the whole month.",
                accent = Sun.Orange,
            )
        }

        if (next.clamped) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "A limit moved this one; it is not following the sun on that day.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.Blue,
            )
        }
    }
}

@Composable
private fun EmptyAlarms(onAdd: () -> Unit) {
    SunCard {
        Text(
            text = "No alarms yet",
            style = MaterialTheme.typography.titleMedium,
            color = Sun.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A SunApp alarm is anchored to sunrise, solar noon or sunset at a city you " +
                "choose, with an offset either side. It moves as the sun moves, which in " +
                "Norway means an hour and a half over a month, so the editor shows you thirty " +
                "days of real fire times before you save anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = Sun.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Sun times need no permissions and never will. Alarms need a few, and you " +
                "will be asked for them once, here, when you create the first one.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Sun.Gold,
                contentColor = Sun.Background,
            ),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Create the first alarm")
        }
    }
}

/* ------------------------------------------------------------------ *
 * Pure formatting. No Compose, no Android: every one of these is a function of
 * its arguments and is covered by AlarmUiTest.
 * ------------------------------------------------------------------ */

/** "06:41" in whichever zone was asked for. */
internal fun formatFireClock(fireAt: Instant, zone: ZoneId): String =
    FIRE_CLOCK.format(fireAt.atZone(zone))

/** "today", "tomorrow", or "Sat 23 Aug", relative to [today]. */
internal fun formatFireDay(fireAt: Instant, zone: ZoneId, today: LocalDate): String {
    val date = fireAt.atZone(zone).toLocalDate()
    return when (date.toEpochDay() - today.toEpochDay()) {
        0L -> "today"
        1L -> "tomorrow"
        else -> FIRE_DATE.format(date)
    }
}

/** "tomorrow", "on Sat 23 Aug". The tail of the polar-night warning sentence. */
internal fun describeFallbackDay(anchorDate: LocalDate, today: LocalDate): String =
    when (anchorDate.toEpochDay() - today.toEpochDay()) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "on ${FIRE_DATE.format(anchorDate)}"
    }

/**
 * "in 7 h 12 min", "in 40 min", "in under a minute".
 *
 * Truncating rather than rounding, and never below "under a minute": an alarm 90 seconds away
 * described as "in 2 min" invites somebody to look away for two minutes, and one described as
 * "in 0 min" reads as broken.
 */
internal fun formatCountdown(now: Instant, fireAt: Instant): String {
    val remaining = Duration.between(now, fireAt)
    // Tested on the duration's own sign rather than on the minute count. `toMinutes`
    // truncates towards zero, so thirty seconds in the past and thirty seconds in the future
    // both come back as 0 and a past alarm would read as one about to happen.
    if (remaining.isNegative) return "any moment now"

    val minutes = remaining.toMinutes()
    return when {
        minutes == 0L -> "in under a minute"
        minutes < 60L -> "in $minutes min"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            if (rest == 0L) "in $hours h" else "in $hours h $rest min"
        }
    }
}

/**
 * The one gap worth a banner above the list, or null when there is nothing to say.
 *
 * At most one. Three stacked warnings above a list of two alarms is a screen nobody reads,
 * and the ordering [AlarmCapability.gapsFrom] already applies is by severity, so the worst
 * one is the one that survives. The rest are a tap away in the sheet.
 */
internal fun alarmBanner(gaps: List<AlarmCapabilityGap>): AlarmCapabilityGap? =
    gaps.firstOrNull { it.blocking } ?: gaps.firstOrNull()

private const val TICK_MILLIS = 30_000L
private val FIRE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val FIRE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
