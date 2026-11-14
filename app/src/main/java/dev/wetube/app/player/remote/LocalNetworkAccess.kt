package dev.wetube.app.player.remote

import android.content.Context
import android.os.Build

/**
 * The forward-compatibility gate for local-network discovery.
 *
 * Android has begun gating access to the user's own LAN behind a runtime
 * permission (`ACCESS_LOCAL_NETWORK`) for apps that target the release where it
 * lands. WeTube does not request it yet and still targets API 35, so no check is
 * required and [canScan] is deliberately inert: it answers `true` on every
 * device this build supports.
 *
 * The seam exists anyway, and discovery consults it before every sweep, so the
 * day `targetSdk` crosses [GATED_SDK] there is exactly one place to add the
 * runtime request instead of teaching the SSDP socket about permissions. The
 * [Context] is taken now so that change never has to move a single call site.
 */
object LocalNetworkAccess {

    /**
     * The first API level at which the platform gates LAN discovery behind a
     * permission. Kept as a literal because no such constant exists in the SDK
     * this build compiles against.
     */
    const val GATED_SDK = 37

    /**
     * Whether this process may open a discovery socket on the local network.
     *
     * Always `true` in this release. Below [GATED_SDK] the platform asks for
     * nothing, and at or above it the grant is implicit while the app still
     * targets API 35 — so both branches answer the same thing. A future target
     * bump replaces the second branch with the runtime check it is standing in
     * for; nothing else about discovery changes.
     */
    fun canScan(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < GATED_SDK) return true
        // Reserved for the ACCESS_LOCAL_NETWORK request. This build does not
        // declare the permission, so the platform grants it implicitly until
        // targetSdk moves; discovery stays allowed rather than regressing on a
        // device that could still scan.
        @Suppress("UNUSED_EXPRESSION")
        context
        return true
    }
}
