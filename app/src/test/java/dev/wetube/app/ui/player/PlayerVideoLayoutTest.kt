package dev.wetube.app.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The video area is a 16:9 slice of the page width, but the page it sits in can
 * be shorter than that slice — a landscape phone is the everyday case. The
 * slice is then clamped to the page so the box stays anchored at the page top;
 * otherwise the oversized box is centred and its top bar rides up under the
 * status bar. The fit is a pure function so it is pinned here without a device.
 */
class PlayerVideoLayoutTest {

    @Test
    fun `a portrait page keeps the width-derived 16 by 9 slice`() {
        // 384 x 853 dp is a portrait phone; 384 * 9 / 16 = 216, which fits.
        assertEquals(216.dp, fittedVideoHeight(384.dp, 853.dp))
    }

    @Test
    fun `a landscape page clamps the slice to the page height`() {
        // 805 x 356 dp is the same phone sideways; 805 * 9 / 16 = 452 > 356.
        assertEquals(356.dp, fittedVideoHeight(805.dp, 356.dp))
    }

    @Test
    fun `a page taller than the slice is not stretched`() {
        assertEquals(216.dp, fittedVideoHeight(384.dp, 2000.dp))
    }

    @Test
    fun `a page exactly as tall as the slice is left alone`() {
        assertEquals(216.dp, fittedVideoHeight(384.dp, 216.dp))
    }
}
