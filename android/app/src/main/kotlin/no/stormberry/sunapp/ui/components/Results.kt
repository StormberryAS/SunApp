package no.stormberry.sunapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.stormberry.sunapp.ui.Sun

/**
 * The results half of the screen: the meta rows, the three sun-time cards and
 * the day-length bar, in the same order the web app puts them.
 *
 * Everything here takes strings that are already formatted. Timezone conversion
 * and polar labelling are decisions, and decisions belong in the pure functions
 * in `ui/SunTimesScreen.kt` where they can be reasoned about and tested, not in
 * a composable where they can only be looked at.
 */

/** One value in a sun-time card: the text, its colour, and whether it is a clock. */
data class SunFieldValue(
    val text: String,
    val colour: Color,
    /** Clock readings are monospaced so the three cards line up digit for digit. */
    val monospace: Boolean,
)

/**
 * The web app's three meta pills, stacked as label/value rows.
 *
 * Stacked rather than laid out side by side because "Europe/Oslo" and a pair of
 * four-decimal coordinates do not fit across a phone at a readable size, and the
 * timezone is precisely the field a user checks when a time looks wrong.
 */
@Composable
fun MetaCard(
    coordinates: String,
    date: String,
    timezone: String,
    modifier: Modifier = Modifier,
) {
    SunCard(modifier = modifier) {
        MetaRow("Coordinates", coordinates)
        Spacer(Modifier.height(10.dp))
        MetaRow("Date", date, monospace = false)
        Spacer(Modifier.height(10.dp))
        MetaRow("Timezone", timezone)
    }
}

@Composable
private fun MetaRow(label: String, value: String, monospace: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Sun.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Sun.TextPrimary,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/**
 * Sunrise, solar noon and sunset side by side, as in the web app's grid.
 *
 * Solar noon sits in the middle and is never blank. It is the transit of the sun
 * rather than a horizon crossing, so it exists on every day at every latitude,
 * including the polar days where the other two cards read "Midnight Sun" or
 * "Polar Night" instead of a time.
 */
@Composable
fun SunTimesRow(
    sunrise: SunFieldValue,
    solarNoon: SunFieldValue,
    sunset: SunFieldValue,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 128.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SunTimeCard("🌅", "Sunrise", sunrise, Sun.Gold, Modifier.weight(1f))
        SunTimeCard("☀️", "Solar noon", solarNoon, Sun.Noon, Modifier.weight(1f))
        SunTimeCard("🌇", "Sunset", sunset, Sun.Orange, Modifier.weight(1f))
    }
}

@Composable
private fun SunTimeCard(
    icon: String,
    label: String,
    value: SunFieldValue,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Sun.CardHover, shape)
            .border(1.dp, accent.copy(alpha = 0.22f), shape)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = icon,
            fontSize = 22.sp,
            // Decorative twice over: the label under it names the event, and an
            // emoji read aloud as "sunrise over mountains" adds nothing but noise.
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Sun.TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value.text,
            style = MaterialTheme.typography.titleSmall,
            color = value.colour,
            fontFamily = if (value.monospace) FontFamily.Monospace else null,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Day length, as a figure and as a bar filled to its share of twenty-four hours.
 *
 * The denominator is the full day rather than the longest day of the year at this
 * latitude, which is what makes the bar comparable between places: a full bar
 * always means midnight sun and an empty one always means polar night, wherever
 * you are.
 */
@Composable
fun DayLengthBar(
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "dayLengthFill",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Day length",
                style = MaterialTheme.typography.labelMedium,
                color = Sun.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Sun.Gold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Sun.Surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Sun.Gold, Sun.Orange)),
                    ),
            )
        }
    }
}

/** What the results area shows before a place has been chosen. */
@Composable
fun EmptyResults(modifier: Modifier = Modifier) {
    SunCard(modifier = modifier) {
        Text(
            text = "No place chosen yet",
            style = MaterialTheme.typography.titleMedium,
            color = Sun.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Search for a city, or switch to coordinates and type a latitude and " +
                "longitude. SunApp cannot guess where you are, because it never asks the " +
                "device.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
    }
}
