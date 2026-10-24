package no.stormberry.sunapp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import no.stormberry.sunapp.R
import no.stormberry.sunapp.data.InputMode
import no.stormberry.sunapp.ui.Sun

/**
 * Shared chrome for the sun-times screen: the hero, the section labels, the
 * two-way mode selector and the notices. The footer is in Footer.kt.
 *
 * These are the pieces that carry the web app's look across to the phone. Where
 * a choice here differs from sun.stormberry.as the comment says why, because the
 * two surfaces are meant to read as one product and a silent divergence is
 * indistinguishable from a mistake.
 */

/**
 * The hero: the mark centred with its glow, the name large beneath it and a centred tagline
 * under the name, the same order and proportions as CheckLink's (64 dp mark, 30 sp name,
 * a lede, 16 dp between them). The tagline is the one the website's hero shows, word for
 * word, set the way the website sets it: upper case, tracked out by 0.06 em, on two lines.
 */
@Composable
fun AppHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            // Decorative: the title below it is already the accessible name, so
            // announcing the mark as well would only slow a screen reader down.
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .drawBehind { drawLogoGlow() },
        )
        Text(
            text = "SunApp",
            color = Sun.TextPrimary,
            fontSize = 30.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            // The web's .tagline rule is text-transform: uppercase; the source text keeps
            // the lower-case "anywhere on Earth" exactly as index.html has it.
            text = HERO_TAGLINE.uppercase(),
            color = Sun.TextSecondary,
            fontSize = 15.2.sp,
            lineHeight = 23.sp,
            letterSpacing = 0.06.em,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 544.dp),
        )
    }
}

/**
 * The line under the name: the text of `<p class="tagline">` in index.html, with its `<br>`
 * as a newline. FooterTest compares the two, so the app cannot drift from the website.
 */
const val HERO_TAGLINE = "SUNRISE \u00B7 SOLAR NOON \u00B7 SUNSET\nanywhere on Earth"

/** The small upper-case captions the web app puts above each field group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Sun.TextMuted,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

/**
 * A translucent card, the Compose equivalent of the web app's `.card` rule.
 *
 * Written as a plain Box rather than a Material3 [androidx.compose.material3.Card]
 * so the fill can be the exact `--bg-card` alpha from style.css. Card would tint
 * it with its own elevation overlay and the two surfaces would drift apart.
 */
@Composable
fun SunCard(
    modifier: Modifier = Modifier,
    borderColour: Color = Sun.Border,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Sun.Card, shape)
            .border(1.dp, borderColour, shape)
            .padding(18.dp),
    ) {
        content()
    }
}

/**
 * The location-mode selector: two options, where the web app has three.
 *
 * "My Device" is missing on purpose and is explained in [NoLocationNotice] rather
 * than shown disabled, because a greyed-out control invites the user to hunt for
 * the setting that would enable it, and no such setting exists.
 */
@Composable
fun ModeTabs(
    mode: InputMode,
    onSelect: (InputMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(
            label = "City search",
            selected = mode == InputMode.CITY,
            onSelect = { onSelect(InputMode.CITY) },
            modifier = Modifier.weight(1f),
        )
        ModeChip(
            label = "Coordinates",
            selected = mode == InputMode.COORDINATES,
            onSelect = { onSelect(InputMode.COORDINATES) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeChip(
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

/**
 * Why there is no "use my location" button.
 *
 * This is the single most likely question from anyone who has used the web app,
 * where "My Device" is the third tab. The honest answer is a feature of this
 * build rather than an omission from it, so it is stated plainly and in the place
 * the missing control would have been, not buried in an about screen.
 */
@Composable
fun NoLocationNotice(modifier: Modifier = Modifier) {
    Notice(
        title = "No \"use my location\" button",
        // Says "no location permission", NOT "no permissions at all". The latter was
        // true of the information-only 1.0.0 and became false the moment the alarm
        // feature landed with its nine install-time permissions. Claiming it here
        // would be contradicted by the app's own manifest, which is a worse look on a
        // privacy-first app than the permissions themselves.
        body = "Reading your position would need a location permission, and SunApp " +
            "asks for none of any kind. Search for your city, or type coordinates " +
            "from any map. Everything is then computed on the device, with no " +
            "network access at all.",
        accent = Sun.Blue,
        modifier = modifier,
    )
}

/** A titled note in the accent colour of whatever it is warning about. */
@Composable
fun Notice(
    title: String,
    body: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), shape)
            .border(1.dp, accent.copy(alpha = 0.32f), shape)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextSecondary,
        )
    }
}

/**
 * The web app's blurred header glow and its cool lower wash, as two radial
 * gradients.
 *
 * Deliberately not `Modifier.blur`, which needs API 31 and would leave the
 * gradient flat for every device between minSdk 24 and there. A soft radial
 * gradient reaches the same look with no API floor.
 */
fun DrawScope.drawSkyGlow() {
    fun glow(colour: Color, centre: Offset, radius: Float, alpha: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(colour.copy(alpha = alpha), Color.Transparent),
                center = centre,
                radius = radius,
            ),
            radius = radius,
            center = centre,
        )
    }
    // Sunrise glow behind the header, matching .header-glow in style.css.
    glow(Sun.Orange, Offset(size.width * 0.5f, 0f), size.minDimension * 1.05f, 0.18f)
    glow(Sun.Gold, Offset(size.width * 0.18f, size.height * 0.04f), size.minDimension * 0.55f, 0.12f)
    // Night sky towards the bottom, so a long scroll does not end in flat black.
    glow(Sun.Blue, Offset(size.width * 0.95f, size.height * 0.85f), size.minDimension * 0.9f, 0.10f)
}

/** The gold halo the web app puts behind the same mark with a CSS drop-shadow. */
private fun DrawScope.drawLogoGlow() {
    val radius = size.minDimension * 0.95f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Sun.Gold.copy(alpha = 0.38f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
