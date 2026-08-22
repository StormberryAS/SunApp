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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import no.stormberry.sunapp.alarm.OccurrenceEngine
import no.stormberry.sunapp.alarm.model.AlarmRule
import no.stormberry.sunapp.alarm.model.Clamp
import no.stormberry.sunapp.alarm.model.Direction
import no.stormberry.sunapp.alarm.model.Occurrence
import no.stormberry.sunapp.cities.City
import no.stormberry.sunapp.cities.CitySearch
import no.stormberry.sunapp.data.Place
import no.stormberry.sunapp.solar.SolarEvent
import no.stormberry.sunapp.ui.components.Notice
import no.stormberry.sunapp.ui.components.SectionLabel
import no.stormberry.sunapp.ui.components.SunCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Create or edit one alarm rule.
 *
 * ### The three anchors, and the eleven that are not here
 *
 * [OccurrenceEngine] and [SolarEvent] support all fourteen solar events, from astronomical
 * dawn to nadir. This screen offers exactly three: sunrise, solar noon, sunset (owner's
 * confirmed decision 5). That is not a limitation waiting to be lifted, it is the product:
 * the three events every person already has a word for, on a picker that fits one row. The
 * engine keeps the other eleven because a rule can arrive from a hand-edited store or from a
 * later version, and [EDITOR_ANCHORS] is the only place that decides what the picker shows.
 *
 * ### The preview is the whole safety story
 *
 * There is no forced clamp in SunApp (owner's confirmed decision 2), which means a rule
 * genuinely can walk from 04:30 in June to 09:30 in December in Bergen, and further north it
 * is worse. The mitigation is not a hidden bound, it is that the user sees thirty days of
 * real fire times before they press Save, with the earliest, the latest and the spread called
 * out above them. The rows come from [OccurrenceEngine.preview] and from nothing else: a
 * second implementation of that arithmetic here would be a preview that agrees with the
 * scheduler right up until the day it does not, which is worse than no preview at all.
 *
 * ### State is hoisted
 *
 * The editor holds no draft of its own. [RuleDraft] lives in the caller, which is what lets
 * the alarms list keep an in-progress rule across a permission sheet, a system settings trip
 * or a configuration change without this screen knowing that any of those exist.
 */

/** The anchors the picker offers, in the order the sun reaches them. */
internal val EDITOR_ANCHORS: List<SolarEvent> =
    listOf(SolarEvent.SUNRISE, SolarEvent.SOLAR_NOON, SolarEvent.SUNSET)

/**
 * An alarm rule part-way through being written.
 *
 * The offset is held as two strings rather than as an `Int` because a text field whose value
 * is derived from a number cannot represent "the user has cleared the box and not typed the
 * new digit yet". Parsing happens once, in [parseOffsetMinutes], at the point the draft is
 * turned into a rule.
 *
 * @property requestedTime a wall time asked for by an `AlarmClock.ACTION_SET_ALARM` intent.
 *   SunApp cannot honour a fixed time (its alarms track the sun by definition), so this is
 *   carried purely so the editor can offer to match it on today's date and then show the
 *   drift that follows. See `AlarmIntentActivity`.
 */
data class RuleDraft(
    val id: String,
    val label: String = "",
    val anchor: SolarEvent = SolarEvent.SUNRISE,
    val direction: Direction = Direction.AT,
    val offsetHours: String = "0",
    val offsetMinutes: String = "0",
    val place: Place? = null,
    val clampOpen: Boolean = false,
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
    val vibrate: Boolean = true,
    val enabled: Boolean = true,
    val isNew: Boolean = true,
    val requestedTime: LocalTime? = null,
)

/** Open an existing rule for editing, losing nothing that the editor cannot express. */
fun AlarmRule.toDraft(): RuleDraft = RuleDraft(
    id = id,
    label = label,
    anchor = anchor,
    direction = direction,
    offsetHours = (offsetMinutes / 60).toString(),
    offsetMinutes = (offsetMinutes % 60).toString(),
    place = Place(
        label = placeName,
        latDeg = latDeg,
        lonDeg = lonDeg,
        zoneId = zoneId,
        // Unknowable after the fact and only used to prefill the sun-times coordinate boxes,
        // so the safe answer is the one that prefills nothing.
        fromCatalogue = true,
    ),
    // Opened because it is set. A user who gave a rule a clamp should see it without hunting.
    clampOpen = clamp != null,
    earliest = clamp?.earliest,
    latest = clamp?.latest,
    vibrate = vibrate,
    enabled = enabled,
    isNew = false,
)

/**
 * Turn a draft into a rule, or null when it is not yet complete.
 *
 * Null is not an error state to report; it is what disables the Save button. The reason a
 * draft is incomplete is shown next to the field that is incomplete, which is why this
 * returns a nullable rule rather than a result type carrying a message.
 */
fun RuleDraft.toRule(): AlarmRule? {
    val place = place ?: return null
    val offset = parseOffsetMinutes(offsetHours, offsetMinutes) ?: return null
    if (earliest != null && latest != null && !earliest.isBefore(latest)) return null

    val clamp = if (earliest == null && latest == null) null else Clamp(earliest, latest)
    return AlarmRule(
        id = id,
        label = label.trim().ifEmpty { defaultLabel(anchor, direction, offset, place.label) },
        anchor = anchor,
        direction = direction,
        offsetMinutes = offset,
        latDeg = place.latDeg,
        lonDeg = place.lonDeg,
        zoneId = place.zoneId,
        placeName = place.label,
        // A clamp the user collapsed without setting a bound is not a clamp. Storing an empty
        // one would be indistinguishable in behaviour and would make the rule look clamped in
        // the list, so it is dropped here rather than carried.
        clamp = clamp,
        enabled = enabled,
        ringtoneUri = null,
        vibrate = vibrate,
    )
}

/**
 * Read the two offset boxes.
 *
 * Blank reads as zero, because clearing a box to type into it should not make the whole draft
 * invalid mid-keystroke. Anything else that is not a plain non-negative number is rejected:
 * the sign belongs to [Direction] and nowhere else, and a negative typed here would silently
 * mean the opposite of the button the user pressed.
 */
internal fun parseOffsetMinutes(hours: String, minutes: String): Int? {
    val h = hours.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
    val m = minutes.trim().ifEmpty { "0" }.toIntOrNull() ?: return null
    if (h < 0 || m < 0) return null
    // A day and a half of offset is already past the point where "before sunrise" describes
    // anything, and beyond it the day walk in nextOccurrence starts paying for lookback it
    // will never use.
    if (h > 36 || m > 59) return null
    return h * 60 + m
}

/** "Sunrise", "Solar noon", "Sunset", and a readable name for the eleven the picker hides. */
internal fun anchorLabel(anchor: SolarEvent): String = when (anchor) {
    SolarEvent.SUNRISE -> "Sunrise"
    SolarEvent.SOLAR_NOON -> "Solar noon"
    SolarEvent.SUNSET -> "Sunset"
    SolarEvent.SUNRISE_END -> "Sunrise ends"
    SolarEvent.SUNSET_START -> "Sunset starts"
    SolarEvent.DAWN -> "Civil dawn"
    SolarEvent.DUSK -> "Civil dusk"
    SolarEvent.NAUTICAL_DAWN -> "Nautical dawn"
    SolarEvent.NAUTICAL_DUSK -> "Nautical dusk"
    SolarEvent.NIGHT_END -> "Astronomical dawn"
    SolarEvent.NIGHT -> "Astronomical dusk"
    SolarEvent.GOLDEN_HOUR_END -> "Golden hour ends"
    SolarEvent.GOLDEN_HOUR -> "Golden hour"
    SolarEvent.NADIR -> "Solar midnight"
}

/** "1 h 30 min", "45 min", "0 min". Spaced units, because "1h30" is not English. */
internal fun formatOffset(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** "30 min before sunrise", "At sunset". The sentence the list row and the editor both show. */
internal fun describeTiming(anchor: SolarEvent, direction: Direction, offsetMinutes: Int): String {
    val event = anchorLabel(anchor).lowercase(Locale.UK)
    return when {
        direction == Direction.AT || offsetMinutes == 0 -> "At $event"
        direction == Direction.BEFORE -> "${formatOffset(offsetMinutes)} before $event"
        else -> "${formatOffset(offsetMinutes)} after $event"
    }
}

/** The name a rule gets when the user does not type one. */
internal fun defaultLabel(
    anchor: SolarEvent,
    direction: Direction,
    offsetMinutes: Int,
    placeName: String,
): String {
    val timing = describeTiming(anchor, direction, offsetMinutes)
    return if (placeName.isBlank()) timing else "$timing, ${placeName.substringBefore(',')}"
}

/**
 * What the thirty rows add up to.
 *
 * @property spreadMinutes the width of the window between the earliest and the latest fire
 *   time. This is the number the whole preview exists to put in front of the user: at 60
 *   degrees north a sunrise rule moves by roughly an hour and a half over a month either side
 *   of the equinox, and nobody expects that until they see it.
 */
internal data class PreviewSummary(
    val rows: List<Occurrence>,
    val earliest: Occurrence?,
    val latest: Occurrence?,
    val spreadMinutes: Int,
    val fallbackDays: Int,
    val clampedDays: Int,
)

/**
 * Reduce a preview to its extremes.
 *
 * Times of day are compared relative to the first row rather than as absolute minutes past
 * midnight, and the differences are folded into plus or minus twelve hours. Without that, a
 * rule that rings at 23:55 on one day and 00:05 on the next reads as a spread of 23 hours 50
 * minutes instead of the ten minutes it actually is, and the headline number of the whole
 * screen would be nonsense for exactly the rules (sunset in high summer, anything with a
 * large offset) that most need it.
 */
internal fun summarisePreview(rows: List<Occurrence>, zone: ZoneId): PreviewSummary {
    val first = rows.firstOrNull()
        ?: return PreviewSummary(rows, null, null, 0, 0, 0)

    val base = minuteOfDay(first, zone)
    var minRelative = 0
    var maxRelative = 0
    var earliest = first
    var latest = first
    for (row in rows) {
        val relative = wrapHalfDay(minuteOfDay(row, zone) - base)
        if (relative < minRelative) {
            minRelative = relative
            earliest = row
        }
        if (relative > maxRelative) {
            maxRelative = relative
            latest = row
        }
    }
    return PreviewSummary(
        rows = rows,
        earliest = earliest,
        latest = latest,
        spreadMinutes = maxRelative - minRelative,
        fallbackDays = rows.count { it.usedFallback },
        clampedDays = rows.count { it.clamped },
    )
}

/** Fold a minute difference into [-720, 720), so a window straddling midnight measures small. */
internal fun wrapHalfDay(delta: Int): Int {
    val positive = ((delta % 1440) + 1440) % 1440
    return if (positive >= 720) positive - 1440 else positive
}

private fun minuteOfDay(occurrence: Occurrence, zone: ZoneId): Int {
    val time = occurrence.fireAt.atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/* ------------------------------------------------------------------ *
 * The screen
 * ------------------------------------------------------------------ */

@Composable
fun RuleEditorScreen(
    draft: RuleDraft,
    onDraftChange: (RuleDraft) -> Unit,
    catalogue: CatalogueState,
    onSave: (AlarmRule) -> Unit,
    onCancel: () -> Unit,
    // modifier comes first among the optional parameters. Compose's ModifierParameter
    // lint check enforces this, and the build runs lint with warningsAsErrors, so the
    // other order fails CI on tag rather than at desk. Convention, not taste: callers
    // expect to pass a modifier positionally after the required arguments.
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var editingBound by remember { mutableStateOf<ClampBoundField?>(null) }

    val suggestions = remember(query, catalogue) {
        val ready = catalogue as? CatalogueState.Ready
        if (ready == null || query.isBlank()) emptyList() else CitySearch.search(ready.table, query)
    }

    val offset = parseOffsetMinutes(draft.offsetHours, draft.offsetMinutes)
    val rule = draft.toRule()

    // Thirty days of solar arithmetic is roughly sixty SunCalc evaluations, which is tens of
    // microseconds: cheap enough to do inline, and keyed on the rule so that typing in the
    // label box does not recompute it.
    val summary = remember(rule) {
        if (rule == null) {
            null
        } else {
            val zone = resolveZone(rule.zoneId)
            summarisePreview(
                rows = OccurrenceEngine.preview(rule, LocalDate.now(zone), PREVIEW_DAYS),
                zone = zone,
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        EditorHeader(
            title = if (draft.isNew) "New alarm" else "Edit alarm",
            onCancel = onCancel,
        )
        Spacer(Modifier.height(20.dp))

        SunCard {
            SectionLabel("Name")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.label,
                onValueChange = { onDraftChange(draft.copy(label = it)) },
                singleLine = true,
                placeholder = {
                    Text(
                        // Shows the name the rule will actually be saved under, so an empty
                        // box is a preview rather than a blank.
                        text = defaultLabel(
                            draft.anchor,
                            draft.direction,
                            offset ?: 0,
                            draft.place?.label.orEmpty(),
                        ),
                        color = Sun.TextMuted,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                shape = RoundedCornerShape(14.dp),
                colors = sunFieldColours(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        SunCard {
            SectionLabel("Track")
            Spacer(Modifier.height(8.dp))
            AnchorPicker(
                anchor = draft.anchor,
                onSelect = { onDraftChange(draft.copy(anchor = it)) },
            )

            Spacer(Modifier.height(18.dp))
            SectionLabel("When")
            Spacer(Modifier.height(8.dp))
            DirectionPicker(
                direction = draft.direction,
                onSelect = { onDraftChange(draft.copy(direction = it)) },
            )

            if (draft.direction != Direction.AT) {
                Spacer(Modifier.height(12.dp))
                OffsetFields(
                    hours = draft.offsetHours,
                    minutes = draft.offsetMinutes,
                    onHoursChange = { onDraftChange(draft.copy(offsetHours = it)) },
                    onMinutesChange = { onDraftChange(draft.copy(offsetMinutes = it)) },
                    invalid = offset == null,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = describeTiming(draft.anchor, draft.direction, offset ?: 0),
                style = MaterialTheme.typography.titleMedium,
                color = Sun.Gold,
            )
        }

        Spacer(Modifier.height(16.dp))

        SunCard {
            SectionLabel("Where the sun is measured")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "An alarm needs a place before it can have a sunrise. This is a city " +
                    "from the bundled catalogue, never your device's location: SunApp has no " +
                    "location permission to ask for.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            CityPicker(
                state = catalogue,
                query = query,
                onQueryChange = { query = it },
                suggestions = suggestions,
                onSelect = { city: City ->
                    onDraftChange(
                        draft.copy(
                            place = Place(
                                label = "${city.name}, ${city.country}",
                                latDeg = city.lat,
                                lonDeg = city.lon,
                                zoneId = city.tz,
                                fromCatalogue = true,
                            ),
                        ),
                    )
                    query = ""
                    focusManager.clearFocus()
                },
                selectedLabel = draft.place?.label,
                onClear = { onDraftChange(draft.copy(place = null)) },
            )
            draft.place?.let { place ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Times computed and shown in ${place.zoneId}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Sun.TextMuted,
                )
            }
        }

        if (draft.requestedTime != null && draft.place != null) {
            Spacer(Modifier.height(16.dp))
            MatchRequestedTimeCard(
                requested = draft.requestedTime,
                draft = draft,
                onApply = onDraftChange,
            )
        }

        Spacer(Modifier.height(16.dp))
        PreviewCard(summary = summary, rule = rule)

        Spacer(Modifier.height(16.dp))
        ClampCard(
            draft = draft,
            onDraftChange = onDraftChange,
            onEditBound = { editingBound = it },
        )

        Spacer(Modifier.height(16.dp))
        SunCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Vibrate",
                        style = MaterialTheme.typography.titleSmall,
                        color = Sun.TextPrimary,
                    )
                    Text(
                        text = "In addition to the sound, when the phone allows it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Sun.TextSecondary,
                    )
                }
                Switch(
                    checked = draft.vibrate,
                    onCheckedChange = { onDraftChange(draft.copy(vibrate = it)) },
                    colors = sunSwitchColours(),
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        if (rule == null) {
            Text(
                text = saveBlockedReason(draft, offset),
                style = MaterialTheme.typography.bodySmall,
                color = Sun.Rose,
            )
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = { rule?.let(onSave) },
            enabled = rule != null,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Sun.Gold,
                contentColor = Sun.Background,
                disabledContainerColor = Sun.Surface,
                disabledContentColor = Sun.TextMuted,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (draft.isNew) "Create alarm" else "Save changes")
        }

        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete this alarm", color = Sun.Rose)
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    editingBound?.let { field ->
        TimeDialog(
            title = if (field == ClampBoundField.EARLIEST) "Never before" else "Never after",
            initial = when (field) {
                ClampBoundField.EARLIEST -> draft.earliest ?: LocalTime.of(6, 0)
                ClampBoundField.LATEST -> draft.latest ?: LocalTime.of(9, 0)
            },
            onDismiss = { editingBound = null },
            onPick = { picked ->
                onDraftChange(
                    when (field) {
                        ClampBoundField.EARLIEST -> draft.copy(earliest = picked)
                        ClampBoundField.LATEST -> draft.copy(latest = picked)
                    },
                )
                editingBound = null
            },
        )
    }
}

/** Which of the two clamp bounds a time dialog is currently editing. */
internal enum class ClampBoundField { EARLIEST, LATEST }

private fun saveBlockedReason(draft: RuleDraft, offset: Int?): String = when {
    draft.place == null -> "Choose a city before saving. The sun has to be measured somewhere."
    offset == null -> "The offset must be whole numbers: up to 36 hours and up to 59 minutes."
    draft.earliest != null && draft.latest != null && !draft.earliest.isBefore(draft.latest) ->
        "The earliest bound has to come before the latest one on the same day. A window that " +
            "wraps past midnight is not something a single day's clamp can express."
    else -> "This alarm is not complete yet."
}

/* ------------------------------------------------------------------ *
 * Pieces
 * ------------------------------------------------------------------ */

@Composable
private fun EditorHeader(title: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = Sun.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancel", color = Sun.TextSecondary) }
    }
}

/**
 * Three chips, and a fourth only when a stored rule uses something else.
 *
 * The fourth is read-only and cannot be selected. It exists so that opening a rule created by
 * a future version, or by hand in the JSON store, does not quietly rewrite its anchor to
 * sunrise the moment the editor renders. Losing someone's "astronomical dawn" rule by opening
 * it would be a data-loss bug dressed up as a simplification.
 */
@Composable
private fun AnchorPicker(anchor: SolarEvent, onSelect: (SolarEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EDITOR_ANCHORS.forEach { candidate ->
            ChoiceChip(
                label = anchorLabel(candidate),
                selected = anchor == candidate,
                onSelect = { onSelect(candidate) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (anchor !in EDITOR_ANCHORS) {
        Spacer(Modifier.height(10.dp))
        Notice(
            title = "This alarm tracks ${anchorLabel(anchor).lowercase(Locale.UK)}",
            body = "That is not one of the three this version offers. It is kept exactly as " +
                "it is unless you pick one of the buttons above, which would replace it.",
            accent = Sun.Blue,
        )
    }
}

@Composable
private fun DirectionPicker(direction: Direction, onSelect: (Direction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip("Before", direction == Direction.BEFORE, { onSelect(Direction.BEFORE) }, Modifier.weight(1f))
        ChoiceChip("At", direction == Direction.AT, { onSelect(Direction.AT) }, Modifier.weight(1f))
        ChoiceChip("After", direction == Direction.AFTER, { onSelect(Direction.AFTER) }, Modifier.weight(1f))
    }
}

@Composable
private fun ChoiceChip(
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

@Composable
private fun OffsetFields(
    hours: String,
    minutes: String,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    invalid: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OffsetField(
            value = hours,
            onValueChange = onHoursChange,
            unit = "hours",
            invalid = invalid,
            modifier = Modifier.weight(1f),
        )
        OffsetField(
            value = minutes,
            onValueChange = onMinutesChange,
            unit = "minutes",
            invalid = invalid,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OffsetField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    invalid: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // Digits only, filtered here rather than validated later: a number keyboard still
        // offers a minus sign and a decimal point on plenty of keyboards, and the sign of an
        // offset belongs to the Before/After buttons alone.
        onValueChange = { typed -> onValueChange(typed.filter { it.isDigit() }.take(2)) },
        singleLine = true,
        isError = invalid,
        label = { Text(unit, color = Sun.TextMuted) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(14.dp),
        colors = sunFieldColours(),
        modifier = modifier,
    )
}

/**
 * The thirty-day preview: the header numbers first, then every row.
 *
 * The header is the part that does the work. Thirty timestamps are data; "earliest 05:41,
 * latest 07:16, a spread of 1 h 35 min" is the sentence that tells somebody whether they want
 * this alarm. The rows are underneath for the person who wants to check a particular date,
 * and because a run of "solar noon" rows in December is more convincing seen than counted.
 */
@Composable
private fun PreviewCard(summary: PreviewSummary?, rule: AlarmRule?) {
    SunCard {
        SectionLabel("Next $PREVIEW_DAYS days")
        Spacer(Modifier.height(8.dp))

        if (summary == null || rule == null || summary.rows.isEmpty()) {
            Text(
                text = "Choose a city and the next $PREVIEW_DAYS fire times appear here, " +
                    "before you commit to anything.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextMuted,
            )
            return@SunCard
        }

        val zone = resolveZone(rule.zoneId)
        Text(
            text = "SunApp has no fixed alarm time. This is what the rule actually does over " +
                "the next month.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        SpreadRow("Earliest", summary.earliest, zone)
        Spacer(Modifier.height(8.dp))
        SpreadRow("Latest", summary.latest, zone)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Spread",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextMuted,
                modifier = Modifier.width(84.dp),
            )
            Text(
                text = if (summary.spreadMinutes == 0) {
                    "Under a minute across the whole month"
                } else {
                    "${formatOffset(summary.spreadMinutes)} across the month"
                },
                style = MaterialTheme.typography.titleSmall,
                color = Sun.Orange,
            )
        }

        if (summary.fallbackDays > 0) {
            Spacer(Modifier.height(14.dp))
            Notice(
                title = fallbackTitle(rule.anchor, summary),
                body = "The sun does not reach that point on ${summary.fallbackDays} of these " +
                    "days at ${rule.placeName}. Those alarms ring at solar noon instead, so " +
                    "the alarm never simply goes missing. The rows below are marked.",
                accent = Sun.Orange,
            )
        }

        if (summary.clampedDays > 0) {
            Spacer(Modifier.height(10.dp))
            Notice(
                title = "Clamped on ${summary.clampedDays} of $PREVIEW_DAYS days",
                body = "On those days the bound decides the time, not the sun.",
                accent = Sun.Blue,
            )
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Sun.Border)
        summary.rows.forEach { row -> PreviewRow(row, zone) }
    }
}

/** "No sunrise on 6 of the next 30 days", with the anchor's own word. */
private fun fallbackTitle(anchor: SolarEvent, summary: PreviewSummary): String {
    val first = summary.rows.firstOrNull { it.usedFallback }
    val event = anchorLabel(anchor).lowercase(Locale.UK)
    return if (first != null && first == summary.rows.firstOrNull()) {
        // The nearest row is the one the user is about to be woken by, so it gets named
        // rather than counted: "no sunrise tomorrow" is the warning the owner asked for.
        "No $event on ${SHORT_DATE.format(first.anchorDate)}"
    } else {
        "No $event on ${summary.fallbackDays} of the next $PREVIEW_DAYS days"
    }
}

@Composable
private fun SpreadRow(label: String, occurrence: Occurrence?, zone: ZoneId) {
    if (occurrence == null) return
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = CLOCK.format(occurrence.fireAt.atZone(zone)),
            style = MaterialTheme.typography.titleMedium,
            color = Sun.TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = SHORT_DATE.format(occurrence.anchorDate),
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
    }
}

@Composable
private fun PreviewRow(row: Occurrence, zone: ZoneId) {
    val fireDate = row.fireAt.atZone(zone).toLocalDate()
    val dayShift = fireDate.toEpochDay() - row.anchorDate.toEpochDay()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SHORT_DATE.format(row.anchorDate),
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = CLOCK.format(row.fireAt.atZone(zone)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.usedFallback) Sun.Orange else Sun.TextPrimary,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = buildList {
                // The offset can push a fire time off the day its anchor belongs to. Saying so
                // per row is the only way the calendar reads honestly, because the left-hand
                // date is the anchor's, not the alarm's.
                if (dayShift == 1L) add("next day")
                if (dayShift == -1L) add("day before")
                if (dayShift > 1L) add("$dayShift days later")
                if (dayShift < -1L) add("${-dayShift} days earlier")
                if (row.usedFallback) add("solar noon")
                if (row.clamped) add("clamped")
            }.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = if (row.usedFallback) Sun.Orange else Sun.TextMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The clamp: collapsed, and off, until somebody asks for it (owner's confirmed decision 2).
 *
 * The copy inside argues against using it, which is deliberate. A clamp stops the alarm
 * tracking the sun, which is the only thing SunApp does that a normal alarm clock cannot, so
 * the honest presentation is "here it is, here is what it costs you".
 */
@Composable
private fun ClampCard(
    draft: RuleDraft,
    onDraftChange: (RuleDraft) -> Unit,
    onEditBound: (ClampBoundField) -> Unit,
) {
    SunCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (draft.clampOpen) "Hide the limits" else "Show the limits",
                ) { onDraftChange(draft.copy(clampOpen = !draft.clampOpen)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Limits (optional)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Sun.TextPrimary,
                )
                Text(
                    text = when {
                        draft.earliest == null && draft.latest == null -> "Off. The alarm follows the sun."
                        draft.earliest != null && draft.latest != null ->
                            "Between ${CLOCK.format(draft.earliest)} and ${CLOCK.format(draft.latest)}"
                        draft.earliest != null -> "Never before ${CLOCK.format(draft.earliest)}"
                        else -> "Never after ${CLOCK.format(draft.latest)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.earliest == null && draft.latest == null) Sun.TextMuted else Sun.Blue,
                )
            }
            Text(
                text = if (draft.clampOpen) "Hide" else "Show",
                style = MaterialTheme.typography.labelLarge,
                color = Sun.TextSecondary,
            )
        }

        if (draft.clampOpen) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "A limit stops the alarm following the sun past a wall-clock time. It " +
                    "is off by default because the drift is the feature: the preview above " +
                    "exists so you can decide you do not need this.",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            BoundRow(
                label = "Never before",
                value = draft.earliest,
                onEdit = { onEditBound(ClampBoundField.EARLIEST) },
                onClear = { onDraftChange(draft.copy(earliest = null)) },
            )
            Spacer(Modifier.height(8.dp))
            BoundRow(
                label = "Never after",
                value = draft.latest,
                onEdit = { onEditBound(ClampBoundField.LATEST) },
                onClear = { onDraftChange(draft.copy(latest = null)) },
            )
        }
    }
}

@Composable
private fun BoundRow(
    label: String,
    value: LocalTime?,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Sun.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .background(Sun.Surface, shape)
                .border(1.dp, if (value == null) Sun.Border else Sun.BorderActive, shape)
                .clickable(role = Role.Button, onClickLabel = "Set $label") { onEdit() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = value?.let { CLOCK.format(it) } ?: "Not set",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == null) Sun.TextMuted else Sun.TextPrimary,
                fontFamily = if (value == null) FontFamily.Default else FontFamily.Monospace,
            )
        }
        if (value != null) {
            Box(
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = "Clear $label") { onClear() }
                    .padding(12.dp),
            ) {
                Text("×", color = Sun.TextSecondary, fontSize = 20.sp)
            }
        }
    }
}

/**
 * The offer to reproduce a wall time asked for by another app.
 *
 * `AlarmClock.ACTION_SET_ALARM` names an hour and a minute, which SunApp has no way of
 * honouring as such: every alarm here is anchored to the sun. Rather than refuse or, worse,
 * pretend, the editor computes the offset that lands on that time **today** and then lets the
 * preview show how far it walks afterwards. The user gets what they asked for tonight and can
 * see what it becomes by next month.
 */
@Composable
private fun MatchRequestedTimeCard(
    requested: LocalTime,
    draft: RuleDraft,
    onApply: (RuleDraft) -> Unit,
) {
    SunCard(borderColour = Sun.BorderActive) {
        Text(
            text = "Another app asked for ${CLOCK.format(requested)}",
            style = MaterialTheme.typography.titleSmall,
            color = Sun.Gold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "SunApp alarms track the sun, so there is no fixed time to set. This " +
                "matches ${CLOCK.format(requested)} on today's date and then follows " +
                "${anchorLabel(draft.anchor).lowercase(Locale.UK)} from there.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { onApply(matchRequestedTime(draft, requested)) }) {
            Text("Match it today", color = Sun.Gold)
        }
    }
}

/**
 * Set [RuleDraft.direction] and the offset boxes so that today's fire time is [requested].
 *
 * The anchor instant comes from [OccurrenceEngine], not from a second call into the solar
 * layer, so the offset this produces is the offset the scheduler will actually use. Returns
 * the draft unchanged when there is no place yet, or when the day has no anchor at all and
 * the engine has already substituted solar noon: matching a time against a substitution would
 * bake today's fallback into a permanent offset.
 */
internal fun matchRequestedTime(draft: RuleDraft, requested: LocalTime): RuleDraft {
    val probe = draft.copy(direction = Direction.AT).toRule() ?: return draft
    val zone = resolveZone(probe.zoneId)
    val today = LocalDate.now(zone)
    val occurrence = OccurrenceEngine.occurrenceFor(probe, today) ?: return draft
    if (occurrence.usedFallback) return draft

    val target: Instant = today.atTime(requested).atZone(zone).toInstant()
    val deltaMinutes = Math.round((target.toEpochMilli() - occurrence.anchorAt.toEpochMilli()) / 60_000.0)
    val magnitude = Math.abs(deltaMinutes).toInt()
    return draft.copy(
        direction = if (deltaMinutes < 0) Direction.BEFORE else Direction.AFTER,
        offsetHours = (magnitude / 60).toString(),
        offsetMinutes = (magnitude % 60).toString(),
        requestedTime = null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    // 24-hour, always. The rest of SunApp prints HH:mm:ss, the product is British and
    // Norwegian, and a clamp bound of "7" that turns out to have meant the evening is exactly
    // the kind of mistake an alarm clock must not make possible.
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = Sun.Surface) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Sun.TextPrimary,
                )
                Spacer(Modifier.height(16.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Sun.TextSecondary) }
                    TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) {
                        Text("Set", color = Sun.Gold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun sunFieldColours() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Sun.TextPrimary,
    unfocusedTextColor = Sun.TextPrimary,
    disabledTextColor = Sun.TextMuted,
    focusedContainerColor = Sun.Surface,
    unfocusedContainerColor = Sun.Surface,
    disabledContainerColor = Sun.Surface,
    focusedBorderColor = Sun.BorderActive,
    unfocusedBorderColor = Sun.Border,
    disabledBorderColor = Sun.Border,
    errorBorderColor = Sun.Rose,
    cursorColor = Sun.Gold,
)

@Composable
internal fun sunSwitchColours() = SwitchDefaults.colors(
    checkedThumbColor = Sun.Background,
    checkedTrackColor = Sun.Gold,
    checkedBorderColor = Sun.Gold,
    uncheckedThumbColor = Sun.TextMuted,
    uncheckedTrackColor = Sun.Surface,
    uncheckedBorderColor = Sun.Border,
)

/** Thirty days, which is the window the owner asked for and roughly one month of drift. */
internal const val PREVIEW_DAYS: Int = 30

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
