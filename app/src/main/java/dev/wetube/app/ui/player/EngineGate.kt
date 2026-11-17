package dev.wetube.app.ui.player

/**
 * One-shot latch for a value a process can only build once.
 *
 * The embedded engine is the case this exists for: GeckoView allows a single
 * runtime per process, cannot rebuild it after a failed create, and dies in a
 * way that leaves nothing to report if a second attempt is made. So warm-up
 * has two terminal outcomes and no third: a ready value, or a failure that is
 * never retried.
 *
 * Both outcomes are sticky. Whichever lands first wins - a late success cannot
 * paper over a failed create, and a late failure cannot discard a runtime that
 * is already alive and in use.
 */
internal class EngineGate<T : Any> {
    private var value: T? = null
    private var failure: Throwable? = null

    /** The built value, or null while absent or after failure. */
    val ready: T?
        get() = value

    /** The cause of the terminal failure, or null while healthy. */
    val failed: Throwable?
        get() = failure

    /** True once an outcome is recorded; further attempts are refused. */
    val settled: Boolean
        get() = value != null || failure != null

    /** Records success. Ignored when an outcome is already recorded. */
    fun succeed(built: T) {
        if (settled) return
        value = built
    }

    /** Records the terminal failure. Ignored when an outcome is already recorded. */
    fun fail(cause: Throwable) {
        if (settled) return
        failure = cause
    }
}