package dev.wetube.app.data.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pins the reel watch sequence seed.
 *
 * The seed is what turns a one-clip Shorts response into a full page, so its
 * exact bytes matter: field 1 must carry the clip id, and it must stay byte
 * compatible with what the web Shorts surface itself emits (every real seed
 * starts with the same `0x0a <len>` prefix). The expected strings here are
 * real seeds captured from that surface, with only the trailing optional field
 * dropped, which is why they are compared exactly rather than structurally.
 */
class SequenceParamsTest {

    @Test
    fun `the seed wraps the clip id in field one`() {
        val decoded = Base64.getDecoder().decode(sequenceParamsFor("5L3xZJ4ek_U"))
        val expected = byteArrayOf(0x0a, 0x0b) + "5L3xZJ4ek_U".toByteArray()
        assertArrayEquals(expected, decoded)
    }

    @Test
    fun `known clips encode to their captured seeds`() {
        assertEquals("Cgs1TDN4Wko0ZWtfVQ==", sequenceParamsFor("5L3xZJ4ek_U"))
        assertEquals("CgtzbUc0NEtKRXBydw==", sequenceParamsFor("smG44KJEprw"))
        assertEquals("Cgt5LUh5eVd3MUlxTQ==", sequenceParamsFor("y-HyyWw1IqM"))
    }

    @Test
    fun `a seed never carries more than the clip id`() {
        // The web surface may append an optional field 5; we deliberately do
        // not, so the seed stays exactly id-byte length plus the two-byte tag.
        val clip = "y-HyyWw1IqM"
        val decoded = Base64.getDecoder().decode(sequenceParamsFor(clip))
        assertEquals(clip.length + 2, decoded.size)
        assertTrue(decoded.size > 2)
    }
}
