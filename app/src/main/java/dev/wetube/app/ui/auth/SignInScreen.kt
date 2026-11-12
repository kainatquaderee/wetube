package dev.wetube.app.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wetube.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Optional Google sign-in.
 *
 * Google no longer lets third-party apps run an OAuth flow for YouTube, so the
 * only honest route is the same one a browser takes: sign in on Google's own
 * page, then keep the resulting session cookies. The page is loaded in a WebView
 * the app owns, so nothing leaves the device except the ordinary login requests.
 *
 * Signing in brings in your own recommendations and subscriptions, and lets
 * Settings switch between several accounts. Everything else works without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(
    locator: ServiceLocator,
    onDone: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Adding a second account needs a clean WebView jar, otherwise Google
    // redirects straight back to the account that is already signed in and we
    // just re-harvest it.
    LaunchedEffect(Unit) {
        val hasAccount = runCatching { locator.auth.accounts.first().isNotEmpty() }.getOrDefault(false)
        if (hasAccount) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        ready = true
    }

    fun harvest(cookies: String?) {
        if (cookies.isNullOrBlank() || !cookies.contains("SAPISID")) return
        scope.launch {
            runCatching { locator.auth.signIn(cookies) }
                .onSuccess { onDone() }
                .onFailure { error = it.message ?: "Could not read that session." }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sign in to YouTube") },
            navigationIcon = {
                IconButton(onClick = onDone) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (ready) AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = CHROME_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            // The jar is populated as soon as Google redirects
                            // back to youtube.com, which is where we stop.
                            harvest(CookieManager.getInstance().getCookie("https://www.youtube.com"))
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            update = {},
        )

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WeTube never sees your password.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showPaste = true }) { Text("Paste cookies instead") }
        }
    }

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showPaste = false },
            title = { Text("Import cookies") },
            text = {
                Column {
                    Text(
                        "If Google blocks the sign-in page here, sign in to youtube.com in a " +
                            "browser and paste the exported cookies.txt for youtube.com.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Cookies") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && text.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { locator.auth.signIn(text) }
                                .onSuccess {
                                    busy = false
                                    showPaste = false
                                    onDone()
                                }
                                .onFailure {
                                    busy = false
                                    error = it.message ?: "Those cookies were not accepted."
                                }
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    }
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaste = false }, enabled = !busy) { Text("Cancel") }
            },
        )
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"

// A real Chrome identity is required: Google refuses a WebView default UA with
// "this browser or app may not be secure".
private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
