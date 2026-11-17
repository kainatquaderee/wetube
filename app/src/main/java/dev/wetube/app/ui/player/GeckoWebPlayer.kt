package dev.wetube.app.ui.player

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.wetube.app.BuildConfig
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Firefox's engine, embedded so that one kind of video can play.
 *
 * An age-restricted video is streamed only to a session a browser can vouch
 * for, so the app hands the whole job to YouTube's own player. Android's
 * WebView cannot do that job — it borrows the system's WebView provider, whose
 * behaviour this app does not control — so the page runs on an engine that
 * ships inside the APK instead. GeckoView is a complete browser engine: it
 * carries its own network stack, its own cookie store and its own media
 * pipeline, which is exactly why it works where the WebView did not.
 *
 * That cookie store is also the one thing the engine will not share: there is
 * no cookie API on [GeckoRuntime], so the sign-in the app already holds is
 * handed over by a bundled extension before the page loads ([WebCookieBridge]).
 * The profile it writes into is process-wide and outlives every player screen,
 * so what it can hold is a security surface rather than a scratch copy:
 *
 * - Only two owners are ever admitted: YouTube (`youtube.com`, `*.youtube.com`,
 *   `youtu.be`) and the handful of Google hosts a sign-in walks through
 *   ([YouTubeOnlyNavigation]).
 * - A sign-in on `accounts.google.com` inside the page does create a session in
 *   the profile while it is in progress — a `.google.com` cookie the app did
 *   not make, does not hold and cannot show in Settings, and that YouTube
 *   answers the next page as. Assuming the profile only ever mirrored the
 *   selected account is what let a Google session outlive the account it was
 *   created for.
 * - Two things clear it. Every seeding round drops the profile's `youtube.com`
 *   and `google.com` cookies before the app's own are written, so a sign-in
 *   completed inside the page is not carried into the next video. And
 *   [WebCookieBridge.wipe] empties the profile's whole cookie store — every
 *   host, regional Google domains included — when the app's account is signed
 *   out or switched, whether or not a player is on screen. Neither runs once the
 *   page is on screen: a video that is playing keeps the session it started with
 *   until the player is left or the account changes.
 *
 * What the app's player adds — background playback, the quality picker,
 * downloads, casting — is unavailable here; the trade is that the video plays
 * at all.
 */
/**
 * What the embedded page has done with the screen, and the way back out.
 *
 * The page's own fullscreen is the only way to see the picture without
 * YouTube's title, description and comments around it, and it is not something
 * the app can arrange: the site asks, the embedder grants
 * ([EmbeddedPageContentDelegate]), and only then may the app stop drawing its
 * own bar over the result. This object is that one fact, held outside the
 * player composable so the screen around it can react to it — and it keeps the
 * session because leaving the site's fullscreen is a request to the page, not a
 * layout change the app can make on its own.
 *
 * It is deliberately not `@Stable`: [session] is engine plumbing rather than
 * state any composable reads, and only [fullScreen] is observable.
 */
internal class EmbeddedPageState {
    internal var session: GeckoSession? = null

    /** True while the page has taken the whole screen for a single element. */
    var fullScreen by mutableStateOf(false)
        private set

    internal fun update(entered: Boolean) {
        fullScreen = entered
    }

    /**
     * Asks the page to leave fullscreen, exactly as the site's own control does.
     *
     * The app's back path calls this first: dropping the window's fullscreen
     * while the page stayed in its own would leave a picture drawn under a page
     * that has hidden every control it owns.
     */
    fun exitFullScreen() {
        session?.exitFullScreen()
    }
}

@Composable
internal fun EmbeddedGeckoPlayer(
    videoId: String,
    cookies: String,
    state: EmbeddedPageState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Built by GeckoEngine.warmUp in Application.onCreate, never created here:
    // creating the runtime while the process is RESUMED is what killed the engine.
    val runtime = GeckoEngine.get()
    if (runtime == null) {
        EmbeddedEngineUnavailable(modifier)
        return
    }
    val session = remember(videoId) {
        GeckoSession(
            GeckoSessionSettings.Builder()
                // The player page is a scripted application; nothing renders
                // without it.
                .allowJavascript(true)
                .build(),
        ).apply {
            permissionDelegate = AutoplayPermissionDelegate
            navigationDelegate = YouTubeOnlyNavigation
            // Without this the page cannot go fullscreen at all; see the
            // delegate for why that is the embedder's decision.
            contentDelegate = EmbeddedPageContentDelegate(state)
            state.session = this
            open(runtime)
        }
    }
    val view = remember(session) {
        GeckoView(context).apply {
            // The page paints its own black player; matching it means no white
            // flash between the request and the first frame.
            setBackgroundColor(Color.BLACK)
            // The picture is on this screen and the app has no view of whether
            // the page is playing, so the screen is held awake for as long as
            // the page is. The flag goes with the view, which is released when
            // the player is left.
            keepScreenOn = true
            setSession(session)
        }
    }
    // A session outlives its view and holds a content process; releasing the
    // view alone would leak both. The runtime itself is deliberately kept: it
    // can only be created once per process.
    DisposableEffect(view, session) {
        onDispose {
            view.releaseSession()
            session.close()
            // The state outlives this player (it is remembered by the screen
            // around it), so a closed session must not be left behind for the
            // next video's back press to talk to.
            if (state.session === session) state.session = null
            state.update(false)
        }
    }

    LaunchedEffect(session, videoId, cookies) {
        // The account selected in Settings is written into the profile before
        // the page is asked for anything: YouTube answers the watch page as
        // whoever the cookies say, so arriving first is what keeps the video
        // from being refused once and reloaded. The same round drops the
        // profile's own `youtube.com` and `google.com` cookies first, so a
        // session created inside the page — a sign-in for an age gate, say —
        // does not carry over to the next video. A round that fails is not
        // fatal: the page then loads with whatever session the profile already
        // had.
        WebCookieBridge.seed(sessionCookies(cookies))
        // The watch page, and not an embed of the video.
        //
        // An embed would be the tidier surface — the player alone, no site
        // chrome — but YouTube will not play an age-restricted video in one:
        // the frame answers "this content is age-restricted and only available
        // on YouTube" however valid its parent page is. That is a decision about
        // the content, not a configuration error, so the watch page is the only
        // surface that plays these videos at all.
        session.loadUri("$MOBILE_YOUTUBE_ORIGIN/watch?v=$videoId")
    }

    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {},
    )
}

/**
 * The process's one and only [GeckoRuntime].
 *
 * GeckoView permits a single runtime per process, and the instance cannot be
 * recreated after it is released, so it is created once and kept for the life
 * of the app rather than tied to the player screen.
 */
internal object GeckoEngine {
    private val gate = EngineGate<GeckoRuntime>()

    /**
     * Builds the process's single [GeckoRuntime], and is the only place allowed
     * to do so.
     *
     * GeckoView permits one runtime per process and cannot rebuild it after a
     * failed create, so creation happens once, early, and its outcome is kept.
     *
     * Why early matters: `GeckoRuntime.create` ends by registering a
     * ProcessLifecycleOwner observer, and LifecycleRegistry replays the owner's
     * current state synchronously. Called from a Compose frame the process is
     * already RESUMED, so the replay runs Gecko's
     * `LifecycleListener.onResume` before its own handler thread exists;
     * GeckoView posts through that handler without a null check. The result is
     * a NullPointerException out of `create()` on debug builds, and a native
     * crash in Gecko's launcher thread on release builds - the player pane
     * flashes white and the whole app process disappears, with nothing to
     * report but a signal 11.
     *
     * Called from Application.onCreate the process lifecycle is still
     * INITIALIZED, nothing is replayed, and the first real resume arrives long
     * after the engine exists.
     */
    @Synchronized
    fun warmUp(context: Context) {
        if (gate.settled) return
        try {
            val settings = GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                // Gecko's own console goes to logcat in debug builds, where
                // that is the only view of what the page did.
                .consoleOutput(BuildConfig.DEBUG)
                .build()
            val created = GeckoRuntime.create(context.applicationContext, settings)
            // The app's session lives in the process, not in the engine's
            // profile, so the cookie store is routed onto the runtime we own
            // (see [WebCookieBridge]).
            WebCookieBridge.prepare(created)
            gate.succeed(created)
        } catch (t: Throwable) {
            // Terminal by construction: a half-built runtime cannot be thrown
            // away and replaced, so the player states the failure instead of
            // retrying into a broken engine.
            gate.fail(t)
            Log.e("WeTube", "Gecko runtime creation failed", t)
        }
    }

    /** The runtime once [warmUp] succeeded; null while warming up or after it failed. */
    fun get(): GeckoRuntime? = gate.ready

    /** Non-null only after a terminal warm-up failure. */
    val failed: Throwable? get() = gate.failed
}

/**
 * Hands the page the one privilege GeckoView keeps for its embedder: going
 * fullscreen.
 *
 * The mobile watch page draws the picture inside itself, with the title,
 * description and comments below it, and offers its own button to drop all of
 * that. A browser answers that button by hiding its chrome; an embedded engine
 * has no chrome to hide and no way to ask its host, so the request is delivered
 * here instead — and if nothing listens, the request is simply refused. That is
 * why the site's fullscreen button did nothing in this player.
 *
 * Nothing is resized in reply. The fallback already opens as the whole screen
 * with the system bars hidden, so granting the request only means the page may
 * now draw the picture alone; [EmbeddedPageState] carries the fact outward so
 * the app's own bar can step aside and so back can hand the page back its
 * chrome before it hands the window back to the feed.
 */
private class EmbeddedPageContentDelegate(
    private val state: EmbeddedPageState,
) : GeckoSession.ContentDelegate {
    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
        state.update(fullScreen)
        if (BuildConfig.DEBUG) Log.d("WeTube", "embedded page fullscreen=$fullScreen")
    }
}

/**
 * Grants the page the one permission it needs to start playing by itself.
 *
 * The watch page begins playback without a tap, which browsers treat as a
 * privileged act, so the engine asks first. Every other request — camera,
 * microphone, location, notifications — is refused: this surface exists to play
 * one video, not to hand a web page the device.
 */
private object AutoplayPermissionDelegate : GeckoSession.PermissionDelegate {
    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int> {
        // Autoplay is only granted to the pages the navigator admits: a frame
        // from anywhere else has no business starting sound by itself.
        val granted = (
            perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
            ) && isPlayablePage(perm.uri)
        return GeckoResult.fromValue(
            if (granted) {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            } else {
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            },
        )
    }
}

/**
 * Keeps the embedded player on YouTube and on the exact Google pages an age
 * confirmation walks through, the same way the rest of the app keeps a hostile
 * or injected link from driving it elsewhere: an https page on one of those
 * hosts is usable, while a custom scheme, `file:`, `content:` or any other host
 * is refused.
 *
 * A refusal is still [AllowOrDeny.DENY], so the page the viewer is on stays
 * where it is and a load that cannot happen ends on the engine's own error page.
 * Nothing was loosened to make the shorter Google list easier to live with.
 *
 * The Google side is a list of hosts rather than a domain. `accounts.google.com`
 * (and the regional `accounts.google.<tld>` the account flow picks for itself)
 * collects the account; `www.google.com` serves the reCAPTCHA frame an unusual
 * sign-in is challenged with; `consent.google.com` is the consent interstitial
 * in front of an account that was just granted. Everything else Google
 * publishes is refused — `sites.google.com` above all, because that host serves
 * anybody's published page and a `.google.com` session cookie travels with every
 * request to any host under that domain.
 *
 * `accounts.youtube.com`, where the sign-in hands its result back to YouTube, is
 * admitted by the YouTube wildcard.
 */
private object YouTubeOnlyNavigation : GeckoSession.NavigationDelegate {
    override fun onLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny> = decide(request.uri, "page", inPageAddresses = false)

    /**
     * Frames pass the same check.
     *
     * A frame is not a page the viewer asked for, but it renders inside this
     * engine, behind the same cookie store and with the autoplay grant above,
     * and the engine consults a separate callback for it — so leaving this one
     * unoverridden would let anything the page, or an advertisement injected
     * into it, points a frame at load while the page behind it stays allowlisted.
     */
    override fun onSubframeLoadRequest(
        session: GeckoSession,
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): GeckoResult<AllowOrDeny>? = decide(request.uri, "frame", inPageAddresses = true)

    private fun decide(
        uri: String?,
        kind: String,
        inPageAddresses: Boolean,
    ): GeckoResult<AllowOrDeny> {
        val parsed = uri?.toUri()
        val allowed = parsed != null && (
            isPlayableAddress(parsed.scheme, parsed.host) ||
                (inPageAddresses && isInPageScheme(parsed.scheme))
            )
        if (!allowed) {
            Log.d("WeTube", "embedded player refused $kind at ${originOf(parsed)}")
        }
        return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
    }

    /**
     * Leaves the engine's own error page in place — it names the failure in the
     * viewer's language — but records why the page did not load, because a
     * blank player is otherwise indistinguishable from a slow one.
     *
     * Debug builds only: this fires for frames as well as pages, so an unfiltered
     * address here would put whatever a third party sent into logcat on a
     * shipped build.
     */
    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: org.mozilla.geckoview.WebRequestError,
    ): GeckoResult<String>? {
        if (BuildConfig.DEBUG) {
            Log.w("WeTube", "embedded player failed to load ${originOf(uri?.toUri())}: code=${error.code}")
        }
        return null
    }
}

/**
 * An address reduced to its scheme and host.
 *
 * Everything after the authority is dropped: a refused navigation can carry a
 * sign-in token or a one-time code in its path or query, and a log is not the
 * place for one.
 */
private fun originOf(uri: android.net.Uri?): String {
    val host = uri?.host ?: return uri?.scheme ?: "(unreadable)"
    return "${uri.scheme}://$host"
}

/**
 * True for a page the embedded player may open: YouTube itself, and the Google
 * host a sign-in is collected on, which is where an age gets confirmed.
 *
 * `accounts.google.<tld>` is matched as well as `accounts.google.com` because
 * the sign-in flow picks the viewer's own regional domain.
 */
private fun isPlayablePage(uri: String?): Boolean {
    val parsed = uri?.toUri() ?: return false
    return isPlayableAddress(parsed.scheme, parsed.host)
}

/**
 * The Google hosts the account flow itself visits.
 *
 * A list rather than the whole `google.com` family. The session a sign-in leaves
 * behind is a `.google.com` cookie, and a domain cookie is sent with every
 * request to every host under that domain — including `sites.google.com`, which
 * serves anybody's published page. Only the pages that take part in a sign-in
 * are worth handing that to.
 */
private val SIGN_IN_HOSTS = setOf(
    // The form itself, and the regional domain the flow may pick instead.
    "accounts.google.com",
    // The reCAPTCHA challenge frame an unusual sign-in is answered with.
    "www.google.com",
    // The consent interstitial in front of an account that was just granted.
    "consent.google.com",
)

/**
 * The allowlist itself, as a comparison of the two parts of an address it cares
 * about.
 *
 * Kept apart from [isPlayablePage] because `android.net.Uri` is a framework
 * class with no implementation in a JVM test, while this is the part that has
 * to be right.
 */
internal fun isPlayableAddress(scheme: String?, host: String?): Boolean {
    if (!scheme.equals("https", ignoreCase = true)) return false
    val name = host?.lowercase(java.util.Locale.US).orEmpty()
    return name == "youtu.be" ||
        name == "youtube.com" ||
        name.endsWith(".youtube.com") ||
        name in SIGN_IN_HOSTS ||
        REGIONAL_SIGN_IN_HOST.matches(name)
}

/**
 * True for the scheme of an address a page writes for itself rather than
 * fetches: `about:blank` and the `blob:` and `data:` URLs the page's own script
 * hands to a frame.
 *
 * Refusing these would break the player without closing anything, because the
 * content is produced by the page that already runs; they are still refused as
 * a top-level navigation, where they would replace the page the viewer is on.
 */
internal fun isInPageScheme(scheme: String?): Boolean {
    val name = scheme?.lowercase(java.util.Locale.US) ?: return false
    return name == "about" || name == "blob" || name == "data"
}

/** `accounts.google.co.uk`, `accounts.google.de`, and so on. */
private val REGIONAL_SIGN_IN_HOST = Regex("""accounts\.google\.[a-z]{2,3}(\.[a-z]{2})?""")

/** The mobile site, whose watch page is the surface that plays here. */
private const val MOBILE_YOUTUBE_ORIGIN = "https://m.youtube.com"

/**
 * The height of YouTube's own top bar on the page the embedded player shows.
 *
 * The app's video area is a 16:9 slice sized for a bare picture, and the page
 * puts this bar above the picture, so the slice has to grow by this much for the
 * whole video to be visible. It is the site's measurement, not the app's: if
 * YouTube changes it the picture is cropped or a strip of black shows, and
 * nothing else breaks.
 */
internal val embeddedPageHeaderHeight = 48.dp

/**
 * Stands in for the embedded page when the engine could not be built.
 *
 * Showing why beats the alternatives that came before it: a white pane that
 * looks like a slow page, or a finished activity with no explanation. The
 * process stays alive and the rest of the app keeps working.
 */
@Composable
private fun EmbeddedEngineUnavailable(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                text = "The embedded player could not start on this device.\nOpen the video in the normal player instead."
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                setPadding(64, 64, 64, 64)
            }
        },
    )
}
