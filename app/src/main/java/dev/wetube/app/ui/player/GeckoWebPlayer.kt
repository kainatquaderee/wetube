package dev.wetube.app.ui.player

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
 * The profile is therefore never the place a session is created — it is only
 * ever a copy of the account that is selected in Settings.
 *
 * What the app's player adds — background playback, the quality picker,
 * downloads, casting — is unavailable here; the trade is that the video plays
 * at all.
 */
@Composable
internal fun EmbeddedGeckoPlayer(
    videoId: String,
    cookies: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val runtime = remember { GeckoEngine.get(context) }
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
        }
    }

    LaunchedEffect(session, videoId, cookies) {
        // The account selected in Settings is written into the profile before
        // the page is asked for anything: YouTube answers the watch page as
        // whoever the cookies say, so arriving first is what keeps the video
        // from being refused once and reloaded. A round that fails is not fatal
        // — the page then loads with whatever session the profile already had.
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
private object GeckoEngine {
    private var runtime: GeckoRuntime? = null

    @Synchronized
    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            // Gecko's own console goes to logcat in debug builds, where it is
            // the only view into what the page did.
            .consoleOutput(BuildConfig.DEBUG)
            .build()
        return GeckoRuntime.create(context.applicationContext, settings).also {
            runtime = it
            // The app's session lives in this process, not in the engine's
            // profile, so the route into the cookie store is set up with the
            // runtime it belongs to (see [WebCookieBridge]).
            WebCookieBridge.prepare(it)
        }
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
 * Keeps the embedded player on YouTube and on the one other site it needs, the
 * same way the rest of the app keeps a hostile or injected link from driving it
 * elsewhere: the watch page's own links and Google's sign-in pages are https
 * pages on those two owners and stay usable, while a custom scheme, `file:`,
 * `content:` or any other host is refused.
 *
 * The sign-in pages are not optional. YouTube confirms an age with an account,
 * and it collects that account on `accounts.google.com`; refusing that host
 * leaves the page's Sign in button doing nothing at all, which looks like a
 * broken player rather than a blocked navigation.
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
 * True for a page the embedded player may open: YouTube itself, and Google's
 * account pages, which is where an age gets confirmed.
 *
 * `accounts.google.<tld>` is matched as well as `accounts.google.com` because
 * the sign-in flow picks the viewer's own regional domain.
 */
private fun isPlayablePage(uri: String?): Boolean {
    val parsed = uri?.toUri() ?: return false
    return isPlayableAddress(parsed.scheme, parsed.host)
}

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
        name == "google.com" ||
        name.endsWith(".google.com") ||
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
