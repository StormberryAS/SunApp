package no.stormberry.labs.carousel

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * The Stormberry Labs carousel, natively: the "Explore the Stormberry Ecosystem" row every Labs
 * website shows above its footer, re-built in Compose for the native apps. One file, the same
 * in every app (it names no app's R class: the icons come from the generated
 * LabsCarouselIcons.kt, see HOWTO-MoonApp.md).
 *
 * What the web carousel does, and what this does in its place:
 *
 * - a labelled region with the heading "Explore the Stormberry Ecosystem": a heading, and the
 *   region is one traversal group, so TalkBack reads the heading and then the cards;
 * - a visible "Pause the animation" tick box (WCAG 2.2.2): the same tick box; ticked, the row
 *   stops and scrolls by hand;
 * - the row slides one set of cards every 32 s, seamlessly, over two copies of the set: the
 *   same, with the second copy hidden from TalkBack and from keyboard focus, as the web hides
 *   its copies with aria-hidden and tabindex="-1";
 * - hover and keyboard focus stop the slide: a finger on the row and keyboard (or D-pad)
 *   focus on a card stop it here, and a drag scrolls it by hand; it also rests while the
 *   region is scrolled out of view, so it costs nothing below the fold;
 * - prefers-reduced-motion makes it static: the system's animator duration scale at 0 (which is
 *   also what Settings > Accessibility > Remove animations sets) makes it static here, with one
 *   set, no copies and no tick box ("nothing slides, so there is nothing to pause"), followed live;
 * - a screen reader that explores by touch (TalkBack) makes it static too, followed live. TalkBack's
 *   touch exploration and swipe navigation give the row no pointer press and no input focus, so
 *   nothing else would hold it, and the card being read would slide off the screen;
 * - the page's own app last with aria-current: the app's own card last, with an accent border
 *   and "This app" read after its name;
 * - 32 px icons: 32 dp, converted from the same SVG files in switcher-icons.
 *
 * Every card opens the app's website in the browser with an ACTION_VIEW intent. That needs no
 * permission: the browser fetches the page in its own process, so the apps keep having no
 * INTERNET permission, and nothing here may ever be "fixed" by adding one.
 */
@Composable
fun LabsCarousel(
    current: LabsApp,
    colors: LabsCarouselColors,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cards = remember(current) { LabsApp.cardsFor(current) }
    val still = LabsCarouselMotion.isStill(
        animatorDurationScale = rememberAnimatorDurationScale(),
        touchExploration = rememberTouchExplorationEnabled(),
    )
    var paused by rememberSaveable { mutableStateOf(false) }
    var focusInside by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    var onScreen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var setWidthPx by remember { mutableIntStateOf(0) }
    val startPadPx = with(LocalDensity.current) { MARQUEE_START_PAD.toPx() }

    val sliding = LabsCarouselMotion.slides(
        still = still,
        onScreen = onScreen,
        paused = paused,
        focusInside = focusInside,
        touched = touched,
        dragging = scroll.isScrollInProgress,
    )
    LaunchedEffect(sliding, setWidthPx) {
        if (sliding && setWidthPx > 0) slide(scroll, setWidthPx, startPadPx)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true }
            // Slide only while some of the region is on screen: below the fold it would redraw
            // the window every frame for nobody.
            .onGloballyPositioned { onScreen = it.boundsInWindow().height > 0f }
            .drawBehind {
                drawLine(colors.rule, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = LabsCarouselText.TITLE,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 19.2.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
            color = colors.title,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(if (still) 24.dp else 12.dp))

        if (!still) {
            PauseBox(paused = paused, onChange = { paused = it }, colors = colors)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // A finger resting on the row holds it, as the mouse pointer does on the web,
                    // so a card is never pulled from under a tap. Watched on the Initial pass and
                    // never consumed: the cards still get their taps and the row its drags.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        touched = true
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            touched = false
                        }
                    }
                }
                .horizontalScroll(scroll)
                .padding(start = MARQUEE_START_PAD, top = 8.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .onSizeChanged { setWidthPx = it.width }
                    .onFocusChanged { focusInside = it.hasFocus },
            ) {
                for (app in cards) {
                    LabsCard(app, isCurrent = app == current, copy = false, colors = colors) {
                        openLabsLink(context, app.url)
                    }
                }
            }
            if (!still) {
                // The loop's second copy: drawn and tappable, but not there for TalkBack or for
                // keyboard focus, so each app is read once.
                Row(modifier = Modifier.clearAndSetSemantics { }) {
                    for (app in cards) {
                        LabsCard(app, isCurrent = app == current, copy = true, colors = colors) {
                            openLabsLink(context, app.url)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The carousel's colours, taken from the app's own palette, as each website's carousel takes
 * its page's CSS custom properties.
 */
@Immutable
data class LabsCarouselColors(
    /** The heading and the tick box's label (the web's muted text colour). */
    val title: Color,
    /** A card's name. */
    val label: Color,
    /** A card's background (the web's glass or card background). */
    val cardFill: Color,
    /** A card's 1 dp border. */
    val cardBorder: Color,
    /** The border of the app's own card, and the ticked box (the web's accent). */
    val accent: Color,
    /** The hairline above the region. */
    val rule: Color,
    /** The tick inside a ticked box. */
    val checkmark: Color,
)

/**
 * The motion rules, apart from Compose so a JVM test can check them (LabsCarouselMotionTest).
 */
object LabsCarouselMotion {
    /** Seconds for the row to slide by one whole set of cards: the web's 32 s marquee. */
    const val SECONDS_PER_SET = 32.0

    /**
     * The row stands still: the system wants no animation (animator duration scale 0, which
     * "Remove animations" sets), or a screen reader explores by touch (TalkBack), which gives the
     * row no touch and no focus to hold it by.
     */
    fun isStill(animatorDurationScale: Float, touchExploration: Boolean = false): Boolean =
        animatorDurationScale == 0f || touchExploration

    /** Whether the row moves this frame. */
    fun slides(
        still: Boolean,
        onScreen: Boolean,
        paused: Boolean,
        focusInside: Boolean,
        touched: Boolean,
        dragging: Boolean,
    ): Boolean = !still && onScreen && !paused && !focusInside && !touched && !dragging

    /** How far the row moves in [frameNanos] when one set is [setWidthPx] wide. */
    fun distance(frameNanos: Long, setWidthPx: Int): Float =
        (setWidthPx * (frameNanos / 1e9) / SECONDS_PER_SET).toFloat()

    /**
     * The scroll position after [position], wrapped back by one set once a whole set has gone by,
     * so the copy takes the place of the original without a visible jump.
     */
    fun wrapped(position: Int, setWidthPx: Int, startPadPx: Float): Int =
        if (setWidthPx > 0 && position >= startPadPx + setWidthPx) position - setWidthPx else position
}

private val MARQUEE_START_PAD = 16.dp

private suspend fun slide(scroll: ScrollState, setWidthPx: Int, startPadPx: Float) {
    var last = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        scroll.dispatchRawDelta(LabsCarouselMotion.distance(now - last, setWidthPx))
        last = now
        val back = scroll.value - LabsCarouselMotion.wrapped(scroll.value, setWidthPx, startPadPx)
        if (back > 0) scroll.dispatchRawDelta(-back.toFloat())
    }
}

@Composable
private fun PauseBox(paused: Boolean, onChange: (Boolean) -> Unit, colors: LabsCarouselColors) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = paused, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = paused,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                uncheckedColor = colors.title,
                checkmarkColor = colors.checkmark,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = LabsCarouselText.PAUSE,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.6.sp, lineHeight = 20.sp),
            color = colors.title,
        )
    }
}

@Composable
private fun LabsCard(
    app: LabsApp,
    isCurrent: Boolean,
    copy: Boolean,
    colors: LabsCarouselColors,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val icon = labsSwitcherIcon(app.slug)
    Column(
        modifier = Modifier
            .padding(end = 16.dp)
            .clip(shape)
            .background(colors.cardFill)
            .border(1.dp, if (isCurrent) colors.accent else colors.cardBorder, shape)
            .focusProperties { if (copy) canFocus = false }
            .clickable(onClickLabel = LabsCarouselText.OPEN_ACTION, onClick = onOpen)
            .semantics { if (isCurrent) stateDescription = LabsCarouselText.CURRENT }
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != 0) {
            Image(
                painter = painterResource(icon),
                // Decorative: the name under it is the card's accessible name.
                contentDescription = null,
                modifier = Modifier.size(32.dp).alpha(0.9f),
            )
        }
        Text(
            text = app.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            color = colors.label,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The animator duration scale, followed live, so turning "Remove animations" on stops the row at once. */
@Composable
private fun rememberAnimatorDurationScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readAnimatorDurationScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorDurationScale(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

private fun readAnimatorDurationScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Whether a screen reader explores by touch (TalkBack), followed live, so turning TalkBack on stops the row at once. */
@Composable
private fun rememberTouchExplorationEnabled(): Boolean {
    val manager = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return false
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        // The state may have changed between the first read and the listener being added.
        enabled = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * Hands [url] to the browser with an ACTION_VIEW intent. With no app that opens web addresses,
 * or one the system refuses to start, a short message instead of a crash.
 */
internal fun openLabsLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, LabsCarouselText.NO_BROWSER, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, LabsCarouselText.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}
