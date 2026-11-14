package dev.wetube.app.data.backend

import dev.wetube.app.core.DEFAULT_INVIDIOUS_HOST
import dev.wetube.app.core.DEFAULT_PIPED_HOST
import kotlinx.serialization.Serializable

/**
 * Which transport the app uses.
 *
 * `@Serializable` lets the chosen backend travel in a backup file; only the
 * enum constant name is written, not the constructor arguments.
 */
@Serializable
enum class BackendType(val label: String, val defaultHost: String) {
    /** Works out of the box; may break when YouTube changes the client profile. */
    INNERTUBE("Direct (built-in)", ""),

    /**
     * Independent instance; needs a reachable host. The host comes from the
     * same constant the store and the settings screen default to, so the two
     * can never drift apart.
     */
    INVIDIOUS("Invidious", DEFAULT_INVIDIOUS_HOST),

    /** Independent instance; needs a reachable host. */
    PIPED("Piped", DEFAULT_PIPED_HOST),
}
