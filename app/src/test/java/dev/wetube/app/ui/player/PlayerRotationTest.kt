package dev.wetube.app.ui.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player's rotate button is a manual toggle between portrait and landscape:
 * a portrait viewer wants landscape back and a landscape one wants portrait.
 * An orientation that is not yet settled is treated as portrait, so the first
 * tap goes to landscape, the orientation a viewer most often wants for a video.
 * The rule is a pure function, so it is pinned here without a device.
 */
class PlayerRotationTest {

    @Test
    fun `portrait asks for landscape`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            rotatedOrientation(Configuration.ORIENTATION_PORTRAIT),
        )
    }

    @Test
    fun `landscape asks for portrait`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            rotatedOrientation(Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun `an unsettled orientation asks for landscape`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            rotatedOrientation(Configuration.ORIENTATION_UNDEFINED),
        )
    }
}
