package dev.wetube.app.data.backend

/** Which transport the app uses. */
enum class BackendType(val label: String, val defaultHost: String) {
    /** Works out of the box; may break when YouTube changes the client profile. */
    INNERTUBE("Direct (InnerTube)", ""),

    /** Independent instance; needs a reachable host. */
    INVIDIOUS("Invidious", "https://invidious.materialio.us"),

    /** Independent instance; needs a reachable host. */
    PIPED("Piped", "https://pipedapi.kavin.rocks"),
}
