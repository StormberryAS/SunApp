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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.stormberry.sunapp.R
import no.stormberry.sunapp.data.InputMode
import no.stormberry.sunapp.ui.Sun

/**
 * Shared chrome for the sun-times screen: the header, the section labels, the
 * two-way mode selector, the notices and the footer.
 *
 * These are the pieces that carry the web app's look across to the phone. Where
 * a choice here differs from sun.stormberry.as the comment says why, because the
 * two surfaces are meant to read as one product and a silent divergence is
 * indistinguishable from a mistake.
 */

/** Header: the mark, the name and the web app's tagline, near enough verbatim. */
@Composable
fun AppHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            // Decorative: the title beside it is already the accessible name, so
            // announcing the mark as well would only slow a screen reader down.
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .drawBehind { drawLogoGlow() },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SunApp",
                style = MaterialTheme.typography.headlineSmall,
                color = Sun.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sunrise, solar noon and sunset, anywhere on Earth",
                style = MaterialTheme.typography.bodyMedium,
                color = Sun.TextSecondary,
            )
        }
    }
}

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

/** The claim that the manifest backs up, plus the house lockup. */
@Composable
fun AppFooter(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No permissions. No network. No tracking.",
            style = MaterialTheme.typography.bodySmall,
            color = Sun.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(R.drawable.ic_stormberry_logo),
            // Decorative: the wordmark inside the drawable repeats the line above,
            // and nothing here is a link, so there is nothing to announce.
            contentDescription = null,
            // Full white would out-shout the app's own content this far down.
            alpha = 0.72f,
            // Height only: the drawable carries the lockup's proportions, so the
            // width follows from them and never has to be kept in sync.
            modifier = Modifier.height(24.dp),
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
