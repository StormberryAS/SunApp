package no.stormberry.sunapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * SunApp's palette, lifted verbatim from the web app's style.css custom
 * properties so the APK and sun.stormberry.as look like one product.
 *
 * Note this is NOT the same palette as UsernameGenerator, which the Android
 * scaffold was copied from: SunApp uses a deep-blue night sky with gold and
 * orange sun accents, not the indigo house default. Keep it in step with
 * style.css rather than with the sibling app.
 *
 * Dark-only by design. isSystemInDarkTheme() is deliberately not consulted
 * because a light variant of this palette does not exist.
 */
object Sun {
    /** --bg-base */
    val Background = Color(0xFF080C18)
    /** --bg-surface */
    val Surface = Color(0xFF0E1628)
    /** --bg-card, rgba(14, 22, 48, 0.72) */
    val Card = Color(0xB80E1630)
    /** --bg-card-hover, rgba(22, 34, 70, 0.85) */
    val CardHover = Color(0xD9162246)
    /** --border, rgba(255, 255, 255, 0.08) */
    val Border = Color(0x14FFFFFF)
    /** --border-active, rgba(255, 215, 0, 0.35) */
    val BorderActive = Color(0x59FFD700)

    /** --text-primary */
    val TextPrimary = Color(0xFFF0F4FF)
    /** --text-secondary */
    val TextSecondary = Color(0xFF8A9BCA)
    /** --text-muted */
    val TextMuted = Color(0xFF4A5580)

    /** --accent-gold, the sunrise/sunset accent */
    val Gold = Color(0xFFFFD700)
    /** --accent-orange */
    val Orange = Color(0xFFFF8C42)
    /** --accent-rose */
    val Rose = Color(0xFFFF6B6B)
    /** --accent-blue */
    val Blue = Color(0xFF4FC3F7)
    /** --accent-noon, used for solar noon */
    val Noon = Color(0xFFFFF176)
}

private val SunColors = darkColorScheme(
    primary = Sun.Gold,
    onPrimary = Sun.Background,
    secondary = Sun.Blue,
    onSecondary = Sun.Background,
    tertiary = Sun.Orange,
    background = Sun.Background,
    onBackground = Sun.TextPrimary,
    surface = Sun.Surface,
    onSurface = Sun.TextPrimary,
    surfaceVariant = Sun.Card,
    onSurfaceVariant = Sun.TextSecondary,
    outline = Sun.Border,
    error = Sun.Rose,
)

private val SunTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun SunAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SunColors,
        typography = SunTypography,
        content = content,
    )
}
