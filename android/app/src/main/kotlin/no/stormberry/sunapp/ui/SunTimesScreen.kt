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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.stormberry.sunapp.cities.City
import no.stormberry.sunapp.cities.CityAssets
import no.stormberry.sunapp.cities.CitySearch
import no.stormberry.sunapp.data.InputMode
import no.stormberry.sunapp.data.Place
import no.stormberry.sunapp.data.Settings
import no.stormberry.sunapp.solar.DayKind
import no.stormberry.sunapp.solar.DayKindCalculator
import no.stormberry.sunapp.solar.SolarEvent
import no.stormberry.sunapp.solar.SunCalc
import no.stormberry.sunapp.ui.components.AppFooter
import no.stormberry.sunapp.ui.components.AppHeader
import no.stormberry.sunapp.ui.components.DayLengthBar
import no.stormberry.sunapp.ui.components.EmptyResults
import no.stormberry.sunapp.ui.components.MetaCard
import no.stormberry.sunapp.ui.components.ModeTabs
import no.stormberry.sunapp.ui.components.NoLocationNotice
import no.stormberry.sunapp.ui.components.Notice
import no.stormberry.sunapp.ui.components.SectionLabel
import no.stormberry.sunapp.ui.components.SunCard
import no.stormberry.sunapp.ui.components.SunFieldValue
import no.stormberry.sunapp.ui.components.SunTimesRow
import no.stormberry.sunapp.ui.components.drawSkyGlow
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Everything release 1.0.0 does: choose a place, choose a date, read the sun.
 *
 * The information architecture is the web app's, in the web app's order, so that
 * someone who uses sun.stormberry.as recognises this immediately: location input
 * first, date underneath it, then the coordinates/date/timezone summary, then
 * sunrise, solar noon and sunset side by side, then the day-length bar.
 *
 * Three deliberate divergences from the web app, each argued at the point it
 * happens: there is no device-location mode (no permissions, see
 * [NoLocationNotice]), there is no Calculate button in city mode (choosing a city
 * is itself the commit, and recomputing costs microseconds), and the polar labels
 * drop the web app's inline emoji because they do not fit a third of a phone.
 *
 * No ViewModel. State is `remember` plus `mutableStateOf`, actions are local
 * functions, and the two collaborators that outlive a recomposition ([Settings]
 * and the parsed catalogue) are built once. That is the template idiom and it is
 * enough here: there is no asynchronous work beyond a single asset parse and
 * nothing to survive that SharedPreferences does not already carry.
 */
@Composable
fun SunTimesScreen(settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var catalogue by remember { mutableStateOf<CatalogueState>(CatalogueState.Loading) }

    // Deliberately `remember` rather than `rememberSaveable`: both of these are
    // written straight through to SharedPreferences, so a process death restores
    // them from disk on the next composition and a saved instance state would be
    // a second, staler copy of the same fact.
    var mode by remember { mutableStateOf(settings.mode) }
    var place by remember { mutableStateOf(settings.place) }

    // The date is NOT persisted. Opening the app tomorrow should show tomorrow.
    // Held as an epoch day rather than a LocalDate so that shifting a day is an
    // addition rather than an allocation on every arrow tap.
    //
    // Plain `remember` throughout below, not `rememberSaveable`: MainActivity
    // declares configChanges for orientation, size, density, font scale and ui
    // mode, so the activity is not recreated for any of them, and the only state
    // worth surviving an actual process death (the place and the mode) is written
    // to SharedPreferences as it changes and read back from there.
    var dateEpochDay by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val date = LocalDate.ofEpochDay(dateEpochDay)

    var query by remember { mutableStateOf("") }
    var latText by remember {
        mutableStateOf(settings.place?.takeIf { !it.fromCatalogue }?.latDeg?.toString() ?: "")
    }
    var lonText by remember {
        mutableStateOf(settings.place?.takeIf { !it.fromCatalogue }?.lonDeg?.toString() ?: "")
    }
    var coordinateError by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // The catalogue is 25,007 rows and parses in tens of milliseconds, which is
    // both too fast to warrant a splash screen and too slow to spend before the
    // first frame. LaunchedEffect runs after the first composition is applied and
    // withContext moves the parse off the main thread, so the screen is on screen
    // and interactive throughout. CityAssets caches for the life of the process,
    // so this is paid once however often the activity is recreated.
    LaunchedEffect(Unit) {
        catalogue = withContext(Dispatchers.IO) {
            try {
                CatalogueState.Ready(CityAssets.load(context))
            } catch (e: IOException) {
                CatalogueState.Failed(
                    "The city catalogue could not be read from the app package (${e.message}). " +
                        "Coordinates still work.",
                )
            } catch (e: IllegalArgumentException) {
                CatalogueState.Failed(
                    "The city catalogue in this build is malformed (${e.message}). " +
                        "Coordinates still work.",
                )
            }
        }
    }

    val suggestions = remember(query, catalogue) {
        val ready = catalogue as? CatalogueState.Ready
        if (ready == null || query.isBlank()) emptyList() else CitySearch.search(ready.table, query)
    }

    // Pure, cheap and keyed on exactly the two things it depends on, so scrolling
    // and typing in the other mode never recompute it.
    val result = remember(place, date) { place?.let { computeSunTimes(it, date) } }

    fun selectCity(city: City) {
        val next = Place(
            label = "${city.name}, ${city.country}",
            latDeg = city.lat,
            lonDeg = city.lon,
            // Catalogue cities carry their own IANA zone, so there is nothing to
            // resolve and nothing to get wrong.
            zoneId = city.tz,
            fromCatalogue = true,
        )
        place = next
        settings.place = next
        settings.mode = InputMode.CITY
        // Clearing the query closes the suggestion list, which is what the web
        // app's dropdown does on selection.
        query = ""
        coordinateError = null
        focusManager.clearFocus()
    }

    fun applyCoordinates() {
        val lat = parseCoordinate(latText)
        val lon = parseCoordinate(lonText)
        val ready = catalogue as? CatalogueState.Ready
        coordinateError = when {
            lat == null || lon == null ->
                "Enter a number for both latitude and longitude."
            lat < -90.0 || lat > 90.0 ->
                "Latitude must be between -90 and 90."
            lon < -180.0 || lon > 180.0 ->
                "Longitude must be between -180 and 180."
            ready == null ->
                "The catalogue is still loading. It carries the timezone table, so give it a moment."
            else -> {
                // No coordinate carries a timezone, so it is inherited from the
                // nearest catalogue city, exactly as the web app does it. The
                // device zone is the last resort and only reachable with an empty
                // table, which a parse failure would already have reported.
                val zone = CitySearch.nearestTimezone(ready.table, lat, lon)
                    ?: ZoneId.systemDefault().id
                val next = Place(
                    label = formatCoordinates(lat, lon),
                    latDeg = lat,
                    lonDeg = lon,
                    zoneId = zone,
                    fromCatalogue = false,
                )
                place = next
                settings.place = next
                settings.mode = InputMode.COORDINATES
                focusManager.clearFocus()
                null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().drawBehind { drawSkyGlow() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
        ) {
            AppHeader()
            Spacer(Modifier.height(22.dp))

            SunCard {
                ModeTabs(
                    mode = mode,
                    onSelect = {
                        mode = it
                        settings.mode = it
                        coordinateError = null
                    },
                )
                Spacer(Modifier.height(16.dp))

                when (mode) {
                    InputMode.CITY -> CityPicker(
                        state = catalogue,
                        query = query,
                        onQueryChange = { query = it },
                        suggestions = suggestions,
                        onSelect = ::selectCity,
                        selectedLabel = place?.takeIf { it.fromCatalogue }?.label,
                        onClear = {
                            place = null
                            settings.place = null
                        },
                    )

                    InputMode.COORDINATES -> CoordinateFields(
                        latText = latText,
                        lonText = lonText,
                        onLatChange = { latText = it; coordinateError = null },
                        onLonChange = { lonText = it; coordinateError = null },
                        error = coordinateError,
                        onApply = ::applyCoordinates,
                    )
                }

                Spacer(Modifier.height(16.dp))
                NoLocationNotice()
                Spacer(Modifier.height(16.dp))

                SectionLabel("Date")
                Spacer(Modifier.height(8.dp))
                DateField(
                    date = date,
                    onShift = { days -> dateEpochDay += days },
                    onPick = { showDatePicker = true },
                )
            }

            Spacer(Modifier.height(20.dp))

            if (result == null) {
                EmptyResults()
            } else {
                Results(result = result, placeLabel = place?.label.orEmpty())
            }

            Spacer(Modifier.height(28.dp))
            AppFooter()
        }
    }

    if (showDatePicker) {
        DateDialog(
            initial = date,
            onDismiss = { showDatePicker = false },
            onPick = { picked -> dateEpochDay = picked.toEpochDay() },
        )
    }
}

/* ------------------------------------------------------------------ *
 * Screen pieces
 * ------------------------------------------------------------------ */

@Composable
private fun Results(result: SunTimesResult, placeLabel: String) {
    // The place name heads the results rather than sitting between the two cards:
    // it is the answer to "where are these times for", so it has to be read before
    // the times are, not after.
    if (placeLabel.isNotEmpty()) {
        Text(
            text = placeLabel,
            style = MaterialTheme.typography.titleMedium,
            color = Sun.TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
    }

    MetaCard(
        coordinates = formatCoordinates(result.latDeg, result.lonDeg),
        date = formatFullDate(result.date),
        timezone = formatZone(result.zone, result.date),
    )

    Spacer(Modifier.height(12.dp))

    // The polar warning goes ABOVE the cards, not below them, because it is the
    // explanation for what the sunrise and sunset cards are about to say. A note
    // under the fold reads as an afterthought to a reading that already confused
    // the user.
    when (result.kind) {
        DayKind.MIDNIGHT_SUN -> {
            Notice(
                title = "Midnight sun",
                body = "The sun does not set on this date at this place, so there is no " +
                    "sunrise and no sunset to give. Solar noon still applies: it is when " +
                    "the sun is at its highest.",
                accent = Sun.Gold,
            )
            Spacer(Modifier.height(12.dp))
        }

        DayKind.POLAR_NIGHT -> {
            Notice(
                title = "Polar night",
                body = "The sun does not rise on this date at this place, so there is no " +
                    "sunrise and no sunset to give. Solar noon still applies: it is when " +
                    "the sun comes closest to the horizon.",
                accent = Sun.Blue,
            )
            Spacer(Modifier.height(12.dp))
        }

        DayKind.NORMAL -> Unit
    }

    SunCard {
        SunTimesRow(
            sunrise = sunFieldValue(result.sunrise, result.kind, result.zone),
            // Solar noon is the sun's transit rather than a horizon crossing, so
            // it exists on every day at every latitude and is never a polar label.
            solarNoon = SunFieldValue(
                text = formatClock(result.solarNoon, result.zone),
                colour = Sun.TextPrimary,
                monospace = true,
            ),
            sunset = sunFieldValue(result.sunset, result.kind, result.zone),
        )
        Spacer(Modifier.height(18.dp))
        DayLengthBar(
            value = formatDayLength(result.kind, result.dayLengthMinutes),
            fraction = dayLengthFraction(result.kind, result.dayLengthMinutes),
        )
    }
}

@Composable
private fun CoordinateFields(
    latText: String,
    lonText: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    error: String?,
    onApply: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoordinateField(
                label = "Latitude",
                placeholder = "59.9139",
                value = latText,
                onChange = onLatChange,
                imeAction = ImeAction.Next,
                onDone = {},
                modifier = Modifier.weight(1f),
            )
            CoordinateField(
                label = "Longitude",
                placeholder = "10.7522",
                value = lonText,
                onChange = onLonChange,
                imeAction = ImeAction.Done,
                onDone = onApply,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "North and east are positive, south and west negative. A decimal comma " +
                "is understood as well as a decimal point.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Sun.Rose,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onApply,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Sun.Gold,
                contentColor = Sun.Background,
            ),
        ) {
            // The web app's "Calculate Sun Times" button, kept for this mode only.
            // Typing coordinates has no moment of commitment the way tapping a city
            // does, and recomputing on every keystroke would rescan the catalogue
            // for a timezone while the user is still halfway through a number.
            Text("Use these coordinates", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CoordinateField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = Sun.TextMuted) },
            keyboardOptions = KeyboardOptions(
                // Decimal rather than Number, so the keyboard offers a separator
                // and a minus sign. Whether it offers a comma or a full stop is
                // the device's business; parseCoordinate accepts either.
                keyboardType = KeyboardType.Decimal,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onDone() },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Sun.TextPrimary,
                unfocusedTextColor = Sun.TextPrimary,
                focusedContainerColor = Sun.Surface,
                unfocusedContainerColor = Sun.Surface,
                focusedBorderColor = Sun.BorderActive,
                unfocusedBorderColor = Sun.Border,
                cursorColor = Sun.Gold,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DateField(
    date: LocalDate,
    onShift: (Long) -> Unit,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepButton(glyph = "‹", label = "Previous day") { onShift(-1L) }

        val shape = RoundedCornerShape(14.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Sun.Surface, shape)
                .border(1.dp, Sun.Border, shape)
                .clickable(role = Role.Button, onClickLabel = "Choose a date") { onPick() }
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatFullDate(date),
                style = MaterialTheme.typography.bodyLarge,
                color = Sun.TextPrimary,
            )
            Text(
                text = if (date == LocalDate.now()) "Today" else "Tap to change",
                style = MaterialTheme.typography.bodySmall,
                color = Sun.TextMuted,
            )
        }

        StepButton(glyph = "›", label = "Next day") { onShift(1L) }
    }
}

@Composable
private fun StepButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(Sun.Surface, CircleShape)
            .border(1.dp, Sun.Border, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = Sun.TextSecondary, fontSize = 22.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    // The Material date picker speaks epoch milliseconds at UTC midnight, which is
    // exactly a LocalDate with no zone attached. Converting through UTC in both
    // directions keeps it that way; going through the device zone would move the
    // date by one either side of midnight.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val colours = DatePickerDefaults.colors(containerColor = Sun.Surface)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colours,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) {
                Text("Select", color = Sun.Gold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Sun.TextSecondary) }
        },
    ) {
        DatePicker(state = state, colors = colours)
    }
}

/* ------------------------------------------------------------------ *
 * Pure logic: no Compose, no Android, no state.
 *
 * Everything below is a function of its arguments, which is what makes the
 * screen's behaviour reviewable. The polar decision in particular is NOT taken
 * here: it is DayKindCalculator's, which is the transcription of app.js the two
 * surfaces are required to share.
 * ------------------------------------------------------------------ */

/** One place on one date, resolved into what the screen renders. */
internal data class SunTimesResult(
    val date: LocalDate,
    val latDeg: Double,
    val lonDeg: Double,
    val zone: ZoneId,
    val kind: DayKind,
    val sunrise: Instant?,
    val solarNoon: Instant?,
    val sunset: Instant?,
    /** Sunset minus sunrise in whole minutes, or null when either is absent. */
    val dayLengthMinutes: Long?,
)

internal fun computeSunTimes(place: Place, date: LocalDate): SunTimesResult {
    val times = SunCalc.times(date, place.latDeg, place.lonDeg)
    val sunrise = times[SolarEvent.SUNRISE]
    val sunset = times[SolarEvent.SUNSET]

    // DayKindCalculator recomputes the same event table internally. That is a few
    // microseconds of duplicated arithmetic, and it buys the guarantee that the
    // classifier the web app shares stays the single authority on what a polar day
    // is. Reimplementing the test here against the map above would be the exact
    // drift its KDoc warns about.
    val kind = DayKindCalculator.of(date, place.latDeg, place.lonDeg)

    val dayLengthMinutes = if (sunrise != null && sunset != null) {
        // app.js section 9.6: Math.round(ms / 60000), then split into hours and
        // minutes. Rounding rather than truncating, so a 12h 29m 40s day reads as
        // 12h 30m on both surfaces.
        ((sunset.toEpochMilli() - sunrise.toEpochMilli()) / 60_000.0).roundToLong()
    } else {
        null
    }

    return SunTimesResult(
        date = date,
        latDeg = place.latDeg,
        lonDeg = place.lonDeg,
        zone = resolveZone(place.zoneId),
        kind = kind,
        sunrise = sunrise,
        solarNoon = times[SolarEvent.SOLAR_NOON],
        sunset = sunset,
        dayLengthMinutes = dayLengthMinutes,
    )
}

/**
 * Turns a stored IANA id into a zone, falling back to UTC.
 *
 * The fallback is not decoration. Zone ids are persisted across app versions and
 * the tzdb underneath them is not frozen: a link retired between releases would
 * otherwise throw on the way out of SharedPreferences and take the screen with
 * it. UTC is wrong but visible, and the timezone row says so.
 */
internal fun resolveZone(id: String): ZoneId =
    try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        ZoneOffset.UTC
    }

/**
 * "HH:mm:ss" in the SELECTED place's zone, never the device's.
 *
 * This is the whole point of carrying a zone with the place. Someone in Bergen
 * looking up Tokyo wants Tokyo's clock, and the instants the solar layer returns
 * are absolute, so the zone is the only thing that decides what they read as.
 */
internal fun formatClock(instant: Instant?, zone: ZoneId): String =
    if (instant == null) "—" else CLOCK_FORMAT.format(instant.atZone(zone))

/** "Friday, 21 August 2026". British long form, as the rest of the product uses. */
internal fun formatFullDate(date: LocalDate): String = DATE_FORMAT.format(date)

/**
 * "CEST / Europe/Oslo", or just the id when there is no abbreviation to show.
 *
 * The abbreviation is resolved at local noon on the date being displayed rather
 * than now, so a date inside British Summer Time reads BST even when it is looked
 * up in January. Where the platform has no short name it prints an offset such as
 * "GMT+02:00", which is repetitive next to the id but never wrong.
 */
internal fun formatZone(zone: ZoneId, date: LocalDate): String {
    val abbreviation = try {
        ZONE_FORMAT.format(date.atTime(12, 0).atZone(zone))
    } catch (_: DateTimeException) {
        ""
    }
    return if (abbreviation.isEmpty() || abbreviation == zone.id) zone.id else "$abbreviation / ${zone.id}"
}

/** "59.9139 N, 10.7522 E", the web app's four decimals with hemispheres spelled out. */
internal fun formatCoordinates(latDeg: Double, lonDeg: Double): String {
    val lat = String.format(Locale.UK, "%.4f", abs(latDeg))
    val lon = String.format(Locale.UK, "%.4f", abs(lonDeg))
    // Hemisphere letters rather than signs: a minus in front of a longitude is
    // easy to misread on a phone, and "W" cannot be mistaken for anything else.
    val ns = if (latDeg < 0) "S" else "N"
    val ew = if (lonDeg < 0) "W" else "E"
    return "$lat $ns, $lon $ew"
}

/** "7h 32m", or the polar constants the web app prints. */
internal fun formatDayLength(kind: DayKind, minutes: Long?): String = when {
    minutes != null -> "${minutes / 60}h ${minutes % 60}m"
    kind == DayKind.MIDNIGHT_SUN -> "24h 0m"
    else -> "0h 0m"
}

/** The bar's share of a full twenty-four hours, clamped to [0, 1]. */
internal fun dayLengthFraction(kind: DayKind, minutes: Long?): Float = when {
    minutes != null -> (minutes / 1440.0).coerceIn(0.0, 1.0).toFloat()
    kind == DayKind.MIDNIGHT_SUN -> 1f
    else -> 0f
}

/**
 * Reads a typed latitude or longitude, or null if it is not a number.
 *
 * Three tolerances, all of them things real keyboards produce. A decimal comma,
 * because that is what a Norwegian, German or Brazilian keyboard offers and
 * `toDouble` rejects. A Unicode minus (U+2212), because some keyboards and every
 * copy-and-paste from a typeset page uses it instead of a hyphen. A trailing
 * degree sign, because coordinates are usually written with one.
 */
internal fun parseCoordinate(raw: String): Double? {
    val cleaned = raw.trim()
        .replace('−', '-')
        .replace(',', '.')
        .removeSuffix("°")
        .trim()
    val value = cleaned.toDoubleOrNull() ?: return null
    // NaN and the infinities parse happily and would poison every downstream
    // comparison, so they are rejected here rather than checked for later.
    return if (value.isFinite()) value else null
}

/** Sunrise or sunset: a clock reading on a normal day, a polar label otherwise. */
private fun sunFieldValue(instant: Instant?, kind: DayKind, zone: ZoneId): SunFieldValue = when (kind) {
    // The web app prefixes these with an emoji. Dropped here because the card is a
    // third of a phone wide and "🌞 Midnight Sun" wraps to three lines in it, while
    // the card already carries its own icon above the label.
    DayKind.MIDNIGHT_SUN -> SunFieldValue("Midnight Sun", Sun.Gold, monospace = false)
    DayKind.POLAR_NIGHT -> SunFieldValue("Polar Night", Sun.Blue, monospace = false)
    DayKind.NORMAL -> SunFieldValue(formatClock(instant, zone), Sun.TextPrimary, monospace = true)
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.UK)
private val ZONE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("zzz", Locale.UK)
