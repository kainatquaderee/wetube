package dev.wetube.app.data.backend

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
    INNERTUBE("Direct (InnerTube)", ""),

    /** Independent instance; needs a reachable host. */
    INVIDIOUS("Invidious", "https://invidious.materialio.us"),

    /** Independent instance; needs a reachable host. */
    PIPED("Piped", "https://pipedapi.kavin.rocks"),
}
