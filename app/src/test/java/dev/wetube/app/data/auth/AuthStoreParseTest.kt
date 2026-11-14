package dev.wetube.app.data.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [extractAccountChannelId] is pure, so it is exercised here with hand-written
 * `account/account_menu` shapes. The real response is a renderer tree whose
 * exact nesting YouTube can move, which is why the extractor walks rather than
 * binding to a path.
 */
class AuthStoreParseTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun root(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `reads the channel id from a browseEndpoint`() {
        val tree = root(
            """
            {
              "actions": [{
                "menu": {
                  "header": {"accountName": {"simpleText": "Ada"}},
                  "items": [{
                    "compactLinkRenderer": {
                      "title": {"simpleText": "Your channel"},
                      "navigationEndpoint": {
                        "browseEndpoint": {
                          "browseId": "UCuAXFkgsw1L7xaCfnd5JJOw",
                          "canonicalBaseUrl": "/channel/UCuAXFkgsw1L7xaCfnd5JJOw"
                        }
                      }
                    }
                  }]
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", extractAccountChannelId(tree))
    }

    @Test
    fun `a channel base url wins over an earlier handle-only entry`() {
        val tree = root(
            """
            {
              "a": {"browseEndpoint": {"browseId": "UCother0000000000000000aa", "canonicalBaseUrl": "/@somebody"}},
              "b": {"browseEndpoint": {"browseId": "UCreal00000000000000000b", "canonicalBaseUrl": "/channel/UCreal00000000000000000b"}}
            }
            """.trimIndent(),
        )

        assertEquals("UCreal00000000000000000b", extractAccountChannelId(tree))
    }

    @Test
    fun `ignores browse ids that are not channels`() {
        val tree = root(
            """
            {
              "header": {"accountName": {"simpleText": "Ada"}},
              "shelf": {"browseEndpoint": {"browseId": "FEchannels"}},
              "playlist": {"browseEndpoint": {"browseId": "PLabc"}}
            }
            """.trimIndent(),
        )

        assertNull(extractAccountChannelId(tree))
    }

    @Test
    fun `a response without a channel id yields null`() {
        val tree = root("""{"header": {"accountName": {"simpleText": "Ada"}}}""")

        assertNull(extractAccountChannelId(tree))
    }
}
