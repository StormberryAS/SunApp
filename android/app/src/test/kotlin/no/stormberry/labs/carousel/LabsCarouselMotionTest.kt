package no.stormberry.labs.carousel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the row moves, how fast, and the seamless wrap. */
class LabsCarouselMotionTest {

    @Test fun removeAnimationsMakesTheRowStill() {
        // "Remove animations" and the developer setting "Animator duration scale: off" both store 0.
        assertTrue(LabsCarouselMotion.isStill(0f))
        assertFalse(LabsCarouselMotion.isStill(1f))
        assertFalse(LabsCarouselMotion.isStill(0.5f))
        assertFalse(LabsCarouselMotion.isStill(10f))
    }

    @Test fun touchExplorationMakesTheRowStill() {
        // TalkBack gives the row no touch and no focus, so nothing else would hold it.
        assertTrue(LabsCarouselMotion.isStill(1f, touchExploration = true))
        assertTrue(LabsCarouselMotion.isStill(0f, touchExploration = true))
        assertFalse(LabsCarouselMotion.isStill(1f, touchExploration = false))
    }

    @Test fun theRowSlidesOnlyWhenNothingHoldsIt() {
        val free = mapOf("still" to false, "paused" to false, "focus" to false, "touch" to false, "drag" to false)
        fun slides(m: Map<String, Boolean>, onScreen: Boolean = true) = LabsCarouselMotion.slides(
            still = m.getValue("still"), onScreen = onScreen, paused = m.getValue("paused"),
            focusInside = m.getValue("focus"), touched = m.getValue("touch"), dragging = m.getValue("drag"),
        )
        assertTrue(slides(free))
        for (holder in free.keys) {
            assertFalse("held by $holder", slides(free + (holder to true)))
        }
        assertFalse("rests while scrolled out of view", slides(free, onScreen = false))
    }

    @Test fun oneSetGoesByIn32Seconds() {
        assertEquals(32.0, LabsCarouselMotion.SECONDS_PER_SET, 0.0)
        assertEquals(3200f, LabsCarouselMotion.distance(32_000_000_000L, 3200), 0.01f)
        // One 60 Hz frame of a 3,200 px set: 100 px a second.
        assertEquals(100f / 60f, LabsCarouselMotion.distance(1_000_000_000L / 60, 3200), 0.01f)
        assertEquals(0f, LabsCarouselMotion.distance(0L, 3200), 0f)
    }

    @Test fun theRowWrapsBackOneSetAfterAWholeSet() {
        val set = 3200
        val pad = 42f
        assertEquals(0, LabsCarouselMotion.wrapped(0, set, pad))
        assertEquals(3241, LabsCarouselMotion.wrapped(3241, set, pad))
        assertEquals(42, LabsCarouselMotion.wrapped(3242, set, pad))
        assertEquals(100, LabsCarouselMotion.wrapped(3300, set, pad))
        // Before the set has been measured nothing moves back.
        assertEquals(5000, LabsCarouselMotion.wrapped(5000, 0, pad))
    }
}
