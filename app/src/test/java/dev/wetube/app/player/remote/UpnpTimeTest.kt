package dev.wetube.app.player.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Devices are sloppy about timestamps: hours appear and disappear, half of them
 * send a fractional part, and one class of TV answers `NOT_IMPLEMENTED` where a
 * number belongs. Every one of those must end up as a number a progress bar can
 * use, never as an exception.
 */
class UpnpTimeTest {

    @Test
    fun `a canonical timestamp is parsed`() {
        assertEquals(0L, UpnpTime.parse("0:00:00"))
        assertEquals(192_000L, UpnpTime.parse("0:03:12"))
        assertEquals(5_025_000L, UpnpTime.parse("1:23:45"))
    }

    @Test
    fun `a fractional part becomes milliseconds`() {
        assertEquals(5_025_678L, UpnpTime.parse("1:23:45.678"))
        assertEquals(5_025_600L, UpnpTime.parse("1:23:45.6"))
        assertEquals(5_025_500L, UpnpTime.parse("1:23:45.5"))
        // More precision than milliseconds is truncated, not rounded up.
        assertEquals(5_025_678L, UpnpTime.parse("1:23:45.6789"))
    }

    @Test
    fun `NOT_IMPLEMENTED is zero`() {
        assertEquals(0L, UpnpTime.parse("NOT_IMPLEMENTED"))
        assertEquals(0L, UpnpTime.parse("not_implemented"))
        assertEquals(0L, UpnpTime.parse("  NOT_IMPLEMENTED  "))
    }

    @Test
    fun `an hour-less timestamp is read as minutes and seconds`() {
        assertEquals(192_000L, UpnpTime.parse("3:12"))
        assertEquals(65_000L, UpnpTime.parse("1:05"))
    }

    @Test
    fun `blank and missing values are zero`() {
        assertEquals(0L, UpnpTime.parse(null))
        assertEquals(0L, UpnpTime.parse(""))
        assertEquals(0L, UpnpTime.parse("   "))
    }

    @Test
    fun `anything that is not a timestamp is zero`() {
        assertEquals(0L, UpnpTime.parse("soon"))
        assertEquals(0L, UpnpTime.parse("1:2:3:4"))
        assertEquals(0L, UpnpTime.parse("1:aa:03"))
        assertEquals(0L, UpnpTime.parse("::"))
        assertEquals(0L, UpnpTime.parse("-1:00:00"))
    }

    @Test
    fun `a malformed fraction is ignored rather than failing the parse`() {
        assertEquals(5_025_000L, UpnpTime.parse("1:23:45."))
        assertEquals(5_025_000L, UpnpTime.parse("1:23:45.xyz"))
    }

    @Test
    fun `format produces the form renderers accept for Seek`() {
        assertEquals("0:00:00", UpnpTime.format(0))
        assertEquals("0:03:12", UpnpTime.format(192_000))
        assertEquals("1:23:45", UpnpTime.format(5_025_678))
        assertEquals("10:00:00", UpnpTime.format(36_000_000))
    }

    @Test
    fun `format clamps negative positions to the start`() {
        assertEquals("0:00:00", UpnpTime.format(-1))
        assertEquals("0:00:00", UpnpTime.format(-60_000))
    }

    @Test
    fun `format and parse round-trip to the second`() {
        val text = UpnpTime.format(5_025_000)
        assertEquals(5_025_000L, UpnpTime.parse(text))
        assertEquals("1:23:45", text)
    }
}