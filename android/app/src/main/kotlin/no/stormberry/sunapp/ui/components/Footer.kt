package no.stormberry.sunapp.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import no.stormberry.sunapp.R
import no.stormberry.sunapp.ui.Sun

/**
 * The foot of the sun-times screen, laid out like the footer of CheckLink (and of the
 * stormberry.as Labs apps): the Stormberry wordmark and tagline, what runs on the device,
 * a rule, the icon row, the privacy line, the permissions line, the legal line, and a closing line.
 *
 * Every address it opens is in [FooterTargets]. They are the same addresses as in CheckLink's
 * footer, except Privacy and Disclaimer, which stay what this app has always opened, and
 * the fourteen addresses of the credit sentence.
 *
 * ### No INTERNET permission, still
 *
 * Opening a link fires an ACTION_VIEW intent and the browser (or the Nostr client) does the
 * fetching, in its own process. Do not "fix" this by adding the permission: the claim in
 * this very footer rests on its absence, and CI diffs the built APK against
 * expected-permissions.txt.
 */
@Composable
fun AppFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val open: (String) -> Unit = { url -> openExternal(context, url) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Opaque, as the web footer's --bg-base is: the footer ends the page rather than
            // floating on the sky glow behind the cards.
            .background(Sun.Background),
    ) {
        HorizontalDivider(color = Sun.Border)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                // Brand and the on-device statement. Left-aligned, stacked, 2rem apart: the
                // phone layout of the web footer's wrapped flex row.
                Column(modifier = Modifier.widthIn(max = 320.dp)) {
                    Wordmark(onClick = { open(FooterTargets.SITE) })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Smarter sales. Global culture. AI-driven strategy.",
                        color = Sun.TextSecondary,
                        fontSize = 15.2.sp,
                        lineHeight = 22.8.sp,
                    )
                }
                Spacer(Modifier.height(32.dp))
                // Credit where it is due, in the footer itself: every third-party part the
                // app ships, with a link to its source and one to its licence.
                Text(
                    text = creditLine(open),
                    color = Sun.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 22.4.sp,
                    modifier = Modifier.widthIn(max = 320.dp),
                )

                Spacer(Modifier.height(48.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Spacer(Modifier.height(32.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SocialRow(open)

                    Text(
                        text = "Your places and alarms never leave this device. Disconnect and try it.",
                        color = Sun.TextSecondary,
                        fontSize = 14.4.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = "Alarm permissions only. No internet. No tracking.",
                        color = Sun.TextSecondary,
                        // 13.6 sp, as the legal lines: at 14.4 sp the last word wrapped alone.
                        fontSize = 13.6.sp,
                        lineHeight = 21.8.sp,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = legalLine(open),
                        color = Sun.TextSecondary,
                        fontSize = 13.6.sp,
                        lineHeight = 21.8.sp,
                        textAlign = TextAlign.Center,
                    )

                    // Kept at 0.85 opacity as on the web: still above 4.5:1 on the page.
                    FooterNote("Privacy First: We do not collect cookies or personal data.")
                    FooterNote("City list dated $CITY_LIST_DATE")
                    FooterNote("Proudly powered by sovereign AI agents.")
                }
            }
        }
    }
}

/** The date of the bundled city catalogue (data/cities.tsv), as the footer states it. */
const val CITY_LIST_DATE = "2026-08-21"

@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        color = Sun.TextSecondary.copy(alpha = 0.85f),
        fontSize = 13.6.sp,
        lineHeight = 21.8.sp,
        textAlign = TextAlign.Center,
    )
}

/** "Stormberry" with the brand's red full stop; a link to stormberry.as. */
@Composable
private fun Wordmark(onClick: () -> Unit) {
    Text(
        text = buildAnnotatedString {
            append("Stormberry")
            withStyle(SpanStyle(color = STORMBERRY_RED)) { append(".") }
        },
        color = Sun.TextPrimary,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "Stormberry AS website" }
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * The middle of the web gradient on the footer dot (#E21E26 to #FF4D54). A one-glyph gradient
 * would only ever show one end of itself.
 */
private val STORMBERRY_RED = Color(0xFFF03840)

@Composable
private fun SocialRow(open: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SocialIcon(FooterIcons.linkedIn, "LinkedIn") { open(FooterTargets.LINKEDIN) }
        SocialIcon(FooterIcons.gitHub, "GitHub") { open(FooterTargets.GITHUB) }
        SocialIcon(FooterIcons.nostr, "Nostr") { open(FooterTargets.NOSTR) }
        SocialIcon(FooterIcons.tor, "Tor onion site") { open(FooterTargets.ONION) }
    }
}

/** A 24 dp mark in a 48 dp target, the smallest size a thumb is promised to hit. */
@Composable
private fun SocialIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Sun.TextSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * "Runs entirely on your device: our own code, SunCalc's formulae (BSD 2-Clause), the Inter
 * typeface (OFL 1.1), Jetpack and Kotlin libraries (Apache 2.0), the Java time backport
 * (GPL 2.0 with Classpath Exception), Lucide's LinkedIn and GitHub icons (ISC) and city data
 * from GeoNames and Kartverket (CC BY 4.0). Nothing is sent."
 *
 * Each part links to its source and each licence to its text. NOTICE keeps the full notices
 * (BSD's copyright and disclaimer must travel with the code); this is the plain-sentence
 * credit, and it is not a substitute for NOTICE.
 */
private fun creditLine(open: (String) -> Unit): AnnotatedString {
    val link = TextLinkStyles(
        SpanStyle(color = Sun.Blue, textDecoration = TextDecoration.Underline),
    )
    fun androidx.compose.ui.text.AnnotatedString.Builder.url(label: String, url: String) {
        withLink(LinkAnnotation.Clickable(tag = url, styles = link) { open(url) }) { append(label) }
    }
    return buildAnnotatedString {
        append("Runs entirely on your device: our own code, ")
        url("SunCalc", FooterTargets.SUNCALC)
        append("'s formulae (")
        url("BSD 2-Clause", FooterTargets.SUNCALC_LICENCE)
        append("), the ")
        url("Inter", FooterTargets.INTER)
        append(" typeface (")
        url("OFL 1.1", FooterTargets.OFL)
        append("), ")
        url("Jetpack", FooterTargets.JETPACK)
        append(" and ")
        url("Kotlin", FooterTargets.KOTLIN)
        append(" libraries (")
        url("Apache 2.0", FooterTargets.APACHE)
        append("), the ")
        url("Java time backport", FooterTargets.DESUGAR)
        append(" (")
        url("GPL 2.0 with Classpath Exception", FooterTargets.GPL_CE)
        append("), the ")
        url("Lucide", FooterTargets.LUCIDE)
        append(" LinkedIn and GitHub icons (")
        url("ISC", FooterTargets.LUCIDE_LICENCE)
        append(") and city data from ")
        url("GeoNames", FooterTargets.GEONAMES)
        append(" and ")
        url("Kartverket", FooterTargets.KARTVERKET)
        append(" (")
        url("CC BY 4.0", FooterTargets.CC_BY)
        append("). Nothing is sent.")
    }
}

/**
 * "© 2026 Stormberry AS · Org.nr. NO 937 751 249 · Askøy, Norway · Privacy · Disclaimer",
 * the links as real links (so a screen reader lists them), in the app's blue and underlined,
 * because colour alone must not be the only thing marking a link. The organisation number
 * uses no-break spaces, as the web footer's nowrap does.
 */
private fun legalLine(open: (String) -> Unit): AnnotatedString {
    val link = TextLinkStyles(
        SpanStyle(color = Sun.Blue, textDecoration = TextDecoration.Underline),
    )
    fun androidx.compose.ui.text.AnnotatedString.Builder.url(label: String, url: String) {
        withLink(LinkAnnotation.Clickable(tag = url, styles = link) { open(url) }) { append(label) }
    }
    return buildAnnotatedString {
        append("© 2026 Stormberry AS · ")
        url("Org.nr. NO 937 751 249", FooterTargets.ORG_REGISTER)
        append(" · Askøy, Norway · ")
        url("Privacy", FooterTargets.PRIVACY)
        append(" · ")
        url("Disclaimer", FooterTargets.DISCLAIMER)
    }
}

/**
 * Every address the footer can open. Pure data, so a JVM test can pin the lot: the same
 * targets as CheckLink's footer, and nothing but web addresses and the nostr: scheme.
 */
object FooterTargets {
    const val SITE = "https://stormberry.as"
    const val LINKEDIN = "https://linkedin.com/company/StormberryAS"
    const val GITHUB = "https://github.com/StormberryAS"
    const val NOSTR = "nostr:npub1zz9w77p2jkn95t0st9tcuea8zdn2vknf0ctrwyd4g46w3kswdccqesr0pa"
    const val ONION = "http://stormberryr7fz5xdopi7f265d7id27bgizc6uxeliaepzstompcfpyd.onion"
    const val ORG_REGISTER = "https://virksomhet.brreg.no/nb/oppslag/enheter/937751249"

    // Privacy and Disclaimer open the pages this app has always opened, in the browser.
    const val PRIVACY = "https://stormberry.as/privacy.html"
    const val DISCLAIMER = "https://sun.stormberry.as/disclaimer.html"

    // The credit sentence: each part's source, and each licence's text.
    const val SUNCALC = "https://github.com/mourner/suncalc"
    const val SUNCALC_LICENCE = "https://github.com/mourner/suncalc/blob/master/LICENSE"
    const val INTER = "https://rsms.me/inter/"
    const val OFL = "https://openfontlicense.org/open-font-license-official-text/"
    const val JETPACK = "https://github.com/androidx/androidx"
    const val KOTLIN = "https://github.com/JetBrains/kotlin"
    const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"
    const val DESUGAR = "https://github.com/google/desugar_jdk_libs"
    const val GPL_CE = "https://openjdk.org/legal/gplv2+ce.html"
    const val LUCIDE = "https://github.com/lucide-icons/lucide"
    const val LUCIDE_LICENCE = "https://github.com/lucide-icons/lucide/blob/main/LICENSE"
    const val GEONAMES = "https://www.geonames.org/"
    const val KARTVERKET = "https://www.kartverket.no/en/api-and-data"
    const val CC_BY = "https://creativecommons.org/licenses/by/4.0/"

    val credits: List<String> = listOf(
        SUNCALC, SUNCALC_LICENCE, INTER, OFL, JETPACK, KOTLIN, APACHE, DESUGAR, GPL_CE, LUCIDE,
        LUCIDE_LICENCE, GEONAMES, KARTVERKET, CC_BY,
    )

    val all: List<String> = listOf(
        SITE, LINKEDIN, GITHUB, NOSTR, ONION, ORG_REGISTER, PRIVACY, DISCLAIMER,
    ) + credits
}

/**
 * Hands [url] to whichever app on the device handles it, as a browser would: a web address
 * to a browser, a nostr: address to a Nostr client that accepts links from the web
 * (BROWSABLE). With no such app, or one the system refuses to start, a short message
 * replaces what would otherwise be a crash.
 */
internal fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}
