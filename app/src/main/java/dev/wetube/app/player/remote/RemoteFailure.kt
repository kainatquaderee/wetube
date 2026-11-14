package dev.wetube.app.player.remote

/**
 * The one action the cast panel offers next to a failure's [RemoteFailure.message].
 *
 * Every failure gets exactly one of these. Two buttons that both look plausible
 * is how a "connecting…" state turns into a stuck screen, so the taxonomy fixes
 * the answer instead of letting each screen pick.
 */
enum class RemoteRecovery(val label: String) {
    /** Try the same thing again, usually because the network came back. */
    RETRY("Retry"),

    /** Stop casting and play on the phone, at the position we last saw. */
    PLAY_HERE("Play here instead"),
}

/**
 * Why casting is not working, in the form the UI can render without knowing
 * anything about UPnP.
 *
 * [message] is one plain line with no address, port or exception text in it:
 * renderer IPs stay in memory for the session and release builds must not show
 * or log them. The technical detail that belongs in `Log.d` under
 * `BuildConfig.DEBUG` — an HTTP status, a socket exception — is kept out of this
 * type on purpose and passed to the logger by whoever caught it.
 */
sealed interface RemoteFailure {

    /** One short, human sentence. Rendered as the only text of the error state. */
    val message: String

    /** The single next step offered under [message]. */
    val recovery: RemoteRecovery

    /** The device is offline, or the interface that carried the session went down. */
    data object NoNetwork : RemoteFailure {
        override val message: String = "You're not connected to a network."
        override val recovery: RemoteRecovery = RemoteRecovery.RETRY
    }

    /** Casting needs the same Wi-Fi the renderer is on; mobile data cannot reach it. */
    data object NotOnLan : RemoteFailure {
        override val message: String = "Casting needs a Wi-Fi network."
        override val recovery: RemoteRecovery = RemoteRecovery.RETRY
    }

    /** A sweep finished and nobody answered. Not the same as the network being down. */
    data object NoRendererFound : RemoteFailure {
        override val message: String = "No devices found on this Wi-Fi network."
        override val recovery: RemoteRecovery = RemoteRecovery.RETRY
    }

    /**
     * The renderer answered discovery but the control endpoint did not.
     *
     * [host] is kept for the debug log only; it never reaches [message].
     */
    data class Unreachable(val host: String) : RemoteFailure {
        override val message: String = "That device didn't answer."
        override val recovery: RemoteRecovery = RemoteRecovery.RETRY
    }

    /**
     * The device returned a SOAP fault.
     *
     * [code] is the UPnP `errorCode` (`714`, `716`, …) so the renderer can map a
     * "this format is not supported" fault to [Unsupported] before it is shown,
     * and [detail] is the device's own description, used for logging.
     */
    data class SoapFault(val code: Int, val detail: String) : RemoteFailure {
        override val message: String = "The device refused to play this."
        override val recovery: RemoteRecovery = RemoteRecovery.PLAY_HERE
    }

    /** The renderer understood us but cannot decode the stream we have. */
    data object Unsupported : RemoteFailure {
        override val message: String = "This device can't play this video."
        override val recovery: RemoteRecovery = RemoteRecovery.PLAY_HERE
    }

    /** There is no cast-eligible stream for this video at all. */
    data object StreamUnavailable : RemoteFailure {
        override val message: String = "This video can't be cast from here."
        override val recovery: RemoteRecovery = RemoteRecovery.PLAY_HERE
    }

    /** The OS refused something casting needs, e.g. the Wi-Fi multicast lock. */
    data object PermissionDenied : RemoteFailure {
        override val message: String = "Casting is blocked on this device."
        override val recovery: RemoteRecovery = RemoteRecovery.PLAY_HERE
    }

    /**
     * A session that was working stopped answering mid-playback.
     *
     * [target] is the friendly name, which is safe to show; the address behind it
     * is not.
     */
    data class TransportLost(val target: String) : RemoteFailure {
        override val message: String = "Lost connection to $target."
        override val recovery: RemoteRecovery = RemoteRecovery.RETRY
    }
}