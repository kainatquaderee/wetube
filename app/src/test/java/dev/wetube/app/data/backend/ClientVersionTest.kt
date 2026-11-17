package dev.wetube.app.data.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the reading of the client version out of a youtube.com response.
 *
 * Every WEB call asks as this version and it is only ever read from the site's
 * own payload, so the two ways the read can go wrong are both pinned here:
 * missing the value the config block carries, and inventing one out of a body
 * that names none. A wrong answer is not cosmetic — the version is part of the
 * identity YouTube matches a request against.
 */
class ClientVersionTest {
    @Test
    fun `the version is read out of the config block the page embeds`() {
        val home = "<script>var ytcfg={\"INNERTUBE_CLIENT_VERSION\":" +
            "\"2.20261116.00.00\",\"INNERTUBE_API_KEY\":\"a\"};</script>"
        assertEquals("2.20261116.00.00", clientVersionFrom(home))
    }

    @Test
    fun `a body that names no version is left alone`() {
        assertNull(clientVersionFrom("<html><body>nothing to read</body></html>"))
    }

    @Test
    fun `an empty version counts as none`() {
        assertNull(clientVersionFrom("{\"INNERTUBE_CLIENT_VERSION\":\"\"}"))
    }
}
