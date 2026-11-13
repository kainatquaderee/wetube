package dev.wetube.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {

    @Test
    fun `decodeEntities handles the named entities YouTube emits`() {
        assertEquals(
            "Tom & Jerry <3 \"quoted\" it's fine\u00A0here",
            decodeEntities("Tom &amp; Jerry &lt;3 &quot;quoted&quot; it&#39;s fine&nbsp;here"),
        )
    }

    @Test
    fun `decodeEntities handles decimal and hex code points`() {
        assertEquals("🔥", decodeEntities("&#128293;"))
        assertEquals("🔥", decodeEntities("&#x1F525;"))
        assertEquals("A", decodeEntities("&#65;"))
    }

    @Test
    fun `decodeEntities keeps decoding until the text stops changing`() {
        // One level of escaping hides a reference behind an escaped "&".
        assertEquals("🔥", decodeEntities("&amp;#128293;"))
        assertEquals("🔥💋", decodeEntities("&amp;#128293;&amp;#128139;"))
        assertEquals("<", decodeEntities("&amp;lt;"))
        assertEquals("&", decodeEntities("&amp;amp;"))
    }

    @Test
    fun `decodeEntities unwinds several levels of escaping`() {
        // "&amp;amp;amp;" is three escape levels on a literal ampersand.
        assertEquals("&", decodeEntities("&amp;amp;amp;"))
    }

    @Test
    fun `decodeEntities keeps literal text next to decoded entities`() {
        // The em dash is a real character, not a reference, so it survives as is.
        assertEquals(
            "Last life she died for me—reborn! 🔥💋",
            decodeEntities("Last life she died for me—reborn! &amp;#128293;&amp;#128139;"),
        )
    }

    @Test
    fun `decodeEntities is idempotent`() {
        val once = decodeEntities("A &amp; B &#128293;")
        assertEquals("A & B 🔥", once)
        assertEquals(once, decodeEntities(once))
    }

    @Test
    fun `decodeEntities leaves unknown references and stray ampersands alone`() {
        assertEquals("AT&T", decodeEntities("AT&T"))
        assertEquals("&unknown;", decodeEntities("&unknown;"))
        assertEquals("&#0;", decodeEntities("&#0;"))
        assertEquals("", decodeEntities(""))
    }

    @Test
    fun `compactCount matches the values from the blueprint`() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2K", compactCount(1_249))
        assertEquals("222K", compactCount(222_000))
        assertEquals("1M", compactCount(1_000_000))
        assertEquals("1.5M", compactCount(1_500_000))
        assertEquals("1.5B", compactCount(1_500_000_000))
    }

    @Test
    fun `compactCount does not print a trailing decimal zero`() {
        assertEquals("1K", compactCount(1_000))
        assertEquals("2M", compactCount(2_000_000))
    }

    @Test
    fun `compactCount promotes a value that rounds to one thousand`() {
        assertEquals("1M", compactCount(999_999))
        assertEquals("1B", compactCount(999_999_999))
    }

    @Test
    fun `compactCount handles missing and negative counts`() {
        assertEquals("0", compactCount(null))
        assertEquals("0", compactCount(0))
        assertEquals("0", compactCount(-5))
    }

    @Test
    fun `formatSpeed prints whole values without decimals and keeps real precision`() {
        assertEquals("1×", formatSpeed(1f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("1.75×", formatSpeed(1.75f))
        assertEquals("2×", formatSpeed(2f))
        assertEquals("0.75×", formatSpeed(0.75f))
        assertEquals("1.5×", formatSpeed(1.5f))
    }

    @Test
    fun `parseCount reads back the abbreviated labels the backend emits`() {
        assertEquals(1_234L, parseCount("1,234"))
        assertEquals(1_234L, parseCount("1234 views"))
        assertEquals(222_000L, parseCount("222.0K"))
        assertEquals(1_200_000L, parseCount("1.2M"))
        assertEquals(1_500_000_000L, parseCount("1.5B"))
    }

    @Test
    fun `parseCount returns zero when there is no number`() {
        assertEquals(0L, parseCount(""))
        assertEquals(0L, parseCount("   "))
        assertEquals(0L, parseCount("No views"))
    }

    @Test
    fun `parseCount round-trips a compactCount label`() {
        assertEquals(222_000L, parseCount(compactCount(222_000)))
        assertEquals(1_500_000L, parseCount(compactCount(1_500_000)))
    }

    @Test
    fun `decodeEntities handles lowercase hex and astral code points`() {
        assertEquals("🔥", decodeEntities("&#x1f525;"))
        assertEquals("😀", decodeEntities("&#x1F600;"))
        assertEquals("🔥 and 😀", decodeEntities("&#x1F525; and &#x1F600;"))
    }

    @Test
    fun `decodeEntities accepts an uppercase hex marker`() {
        // Real documents carry `&#X1F525;` as well as `&#x1F525;`.
        assertEquals("🔥", decodeEntities("&#X1F525;"))
        assertEquals("A", decodeEntities("&#X41;"))
        assertEquals("🔥 and A", decodeEntities("&#X1F525; and &#X41;"))
    }

    @Test
    fun `decodeEntities leaves out-of-range and malformed numeric references alone`() {
        // Above Character.MAX_CODE_POINT (0x10FFFF).
        assertEquals("&#x110000;", decodeEntities("&#x110000;"))
        // Overflows Int, so it cannot be a code point.
        assertEquals("&#xFFFFFFFFFF;", decodeEntities("&#xFFFFFFFFFF;"))
        // A bare ampersand and a truncated entity are not references.
        assertEquals("a & b", decodeEntities("a & b"))
        assertEquals("&#12", decodeEntities("&#12"))
    }

    @Test
    fun `compactCount keeps one decimal only when it is non-zero across boundaries`() {
        assertEquals("10K", compactCount(9_999))
        assertEquals("100K", compactCount(99_999))
        assertEquals("123.5K", compactCount(123_456))
        assertEquals("1.2M", compactCount(1_234_567))
        assertEquals("2M", compactCount(1_999_999))
        assertEquals("2.5M", compactCount(2_500_000))
        assertEquals("10M", compactCount(10_000_000))
        assertEquals("1B", compactCount(1_000_000_000))
        assertEquals("2B", compactCount(2_000_000_000))
    }

    @Test
    fun `compactCount promotes right at the rounding boundary`() {
        // 999_949 still prints as a K value; 999_950 rounds up to the next unit.
        assertEquals("999.9K", compactCount(999_949))
        assertEquals("1M", compactCount(999_950))
        assertEquals("999.9M", compactCount(999_949_999))
        assertEquals("1B", compactCount(999_950_000))
    }

    @Test
    fun `compactCount handles one and the thousands edge exactly`() {
        assertEquals("1", compactCount(1))
        assertEquals("999", compactCount(999))
        assertEquals("1K", compactCount(1_000))
        assertEquals("1K", compactCount(1_049))
    }

    @Test
    fun `parseCount parses the suffix case insensitively and ignores surrounding text`() {
        assertEquals(2_500L, parseCount("2.5K"))
        assertEquals(2_500L, parseCount("2.5k"))
        assertEquals(3_000_000L, parseCount("3M"))
        assertEquals(3_000_000L, parseCount("3m"))
        assertEquals(500L, parseCount(".5K"))
        assertEquals(12L, parseCount("Likes: 12"))
        assertEquals(42L, parseCount("  42  "))
        assertEquals(0L, parseCount("0"))
    }

    @Test
    fun `parseCount tolerates thousands separators`() {
        assertEquals(1_234_567L, parseCount("1,234,567"))
        assertEquals(123L, parseCount("1,2,3"))
    }

    @Test
    fun `parseCount returns zero for a malformed number`() {
        assertEquals(0L, parseCount("1.2.3K"))
        assertEquals(0L, parseCount("K"))
        assertEquals(0L, parseCount("views"))
    }

    @Test
    fun `formatSpeed prints the canonical labels without a bare decimal point`() {
        assertEquals("0.25×", formatSpeed(0.25f))
        assertEquals("1×", formatSpeed(1f))
        assertEquals("1.5×", formatSpeed(1.5f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("1.75×", formatSpeed(1.75f))
        assertEquals("2×", formatSpeed(2f))
        assertEquals("4×", formatSpeed(4f))
        assertEquals("0×", formatSpeed(0f))
    }

    @Test
    fun `subscriberLabel gives one spelling for text and numeric counts`() {
        assertEquals("15.8M subscribers", subscriberLabel("15.8M"))
        assertEquals("15.8M subscribers", subscriberLabel("15.8M subscribers"))
        assertEquals("15.8M subscribers", subscriberLabel(15_800_000L))
        assertEquals("34.2K subscribers", subscriberLabel("34.2K"))
    }

    @Test
    fun `subscriberLabel handles the singular and decodes before parsing`() {
        assertEquals("1 subscriber", subscriberLabel("1 subscriber"))
        assertEquals("1 subscriber", subscriberLabel(1L))
        // A numeric reference is decoded first, then read as a number.
        assertEquals("1.5K subscribers", subscriberLabel("&#49;.5K subscribers"))
    }

    @Test
    fun `subscriberLabel leaves text without a number alone and blanks empty input`() {
        assertEquals("No subscribers", subscriberLabel("No subscribers"))
        assertEquals("", subscriberLabel(""))
        assertEquals("", subscriberLabel(0L))
        assertEquals("", subscriberLabel(-3L))
        // A lone escaped ampersand decodes to itself and has no count to read.
        assertEquals("&", subscriberLabel("&amp;"))
    }
}
