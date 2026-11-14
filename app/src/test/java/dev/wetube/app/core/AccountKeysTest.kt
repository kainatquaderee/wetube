package dev.wetube.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [accountKey] and [sha256Hex] are pure, so they are tested directly. The
 * salt literal is repeated here on purpose: it is part of the on-disk key
 * format, and a change to it silently orphans every stored sync entry, so the
 * test is meant to fail loudly if it drifts.
 */
class AccountKeysTest {

    private val salt = "wetube-sync-v1|"

    @Test
    fun `the same account id always maps to the same key`() {
        assertEquals(accountKey("UCabc123"), accountKey("UCabc123"))
    }

    @Test
    fun `the key is exactly 32 lowercase hex characters`() {
        val key = accountKey("UCuAXFkgsw1L7xaCfnd5JJOw")

        assertEquals(32, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun `different account ids produce different keys`() {
        assertNotEquals(accountKey("UCa"), accountKey("UCb"))
    }

    @Test
    fun `the key is the truncated hash of the salted id`() {
        assertEquals(
            sha256Hex("$salt" + "UCuAXFkgsw1L7xaCfnd5JJOw").take(32),
            accountKey("UCuAXFkgsw1L7xaCfnd5JJOw"),
        )
    }

    @Test
    fun `the key never contains the raw account id`() {
        val id = "UCuAXFkgsw1L7xaCfnd5JJOw"

        assertFalse(accountKey(id).contains(id))
    }

    @Test
    fun `the documented digest for a known input is unchanged`() {
        // Locks the algorithm: SHA-256 of the UTF-8 bytes, lowercase hex.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }
}
