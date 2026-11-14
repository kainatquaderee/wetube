package dev.wetube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The quality chip's wording is a pure function so the label a viewer reads can
 * be pinned without a device: Auto names the height it resolved to, and an
 * explicit pick never claims more than the stream actually serves.
 */
class QualityValueTest {

    @Test
    fun `auto names the height it resolved to`() {
        assertEquals("Auto (1080p)", qualityValue(quality = 0, effectiveHeight = 1080))
    }

    @Test
    fun `auto without a resolved height stays plain`() {
        assertEquals("Auto", qualityValue(quality = 0, effectiveHeight = 0))
    }

    @Test
    fun `an explicit pick shows the chosen height`() {
        assertEquals("720p", qualityValue(quality = 720, effectiveHeight = 720))
    }

    @Test
    fun `an explicit pick never overstates a smaller stream`() {
        // Requested 2160p, but the video only serves 1080p: the chip must show
        // what is playing, not what was asked for.
        assertEquals("1080p", qualityValue(quality = 2160, effectiveHeight = 1080))
    }

    @Test
    fun `an explicit pick without a resolved height falls back to the choice`() {
        assertEquals("480p", qualityValue(quality = 480, effectiveHeight = 0))
    }
}
