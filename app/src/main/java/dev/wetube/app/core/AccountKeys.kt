package dev.wetube.app.core

import java.security.MessageDigest

/**
 * Domain separator prepended before hashing an account identity.
 *
 * It has no secret value: it only makes the hash of an account id
 * domain-specific to WeTube, so the same id hashed by another tool cannot
 * produce a colliding key, and so a future change of the derivation can be
 * told apart from the current one. Changing it invalidates every stored key.
 */
private const val ACCOUNT_KEY_SALT = "wetube-sync-v1|"

/**
 * A stable, non-reversible key for a signed-in account.
 *
 * Sync state has to be tied to an account without ever persisting the account
 * itself: the raw channel id (or the SAPISID fallback) identifies the person,
 * while this key cannot be turned back into it. Hashing with SHA-256 and
 * truncating to 32 hex characters keeps the key short enough to use as a map
 * key and a DataStore key while retaining 128 bits, far more than is needed to
 * keep distinct accounts apart.
 */
fun accountKey(accountId: String): String = sha256Hex(ACCOUNT_KEY_SALT + accountId).take(32)

/** Lowercase hex of the SHA-256 digest of [input], encoded as UTF-8. */
fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
