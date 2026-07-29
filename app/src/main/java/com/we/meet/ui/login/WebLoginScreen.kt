package com.we.meet.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.we.meet.BuildConfig
import com.we.meet.R
import com.we.meet.WeMeetApp
import com.we.meet.data.auth.KeycloakOidc
import com.we.meet.ui.theme.WeMeetTheme
import kotlinx.coroutines.launch

/**
 * In-WebView Keycloak login (p3-docs-app.md D1): loads the realm's unified
 * login page (phone-OTP + QR columns — the exact page the web frontend uses)
 * and intercepts the `com.we.meet://oidc/callback` redirect to finish the
 * authorization-code + PKCE exchange.
 *
 * Logging in INSIDE a WebView is the whole point, not a shortcut: it seeds
 * the Keycloak session cookie into the process-wide CookieManager, which is
 * the only thing that lets the Docs tab's WebView SSO silently. (Custom Tabs
 * would be the usual OIDC choice, but their cookies are NOT shared with
 * WebViews — the Docs tab would stay logged out.)
 *
 * If the KC session cookie is still alive (e.g. we landed here because a
 * token refresh failed), the authorize request silently re-issues a code and
 * the user never sees the form — free re-login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(onLoggedIn: () -> Unit) {
    val app = LocalContext.current.applicationContext as WeMeetApp
    val scope = rememberCoroutineScope()
    val oidc = app.apiClient.keycloakOidc

    // A bumped attempt regenerates PKCE material and rebuilds the WebView —
    // an authorization code/verifier pair is single-use, so retrying after
    // any failure must start a fresh authorize round trip.
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var exchanging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val verifier = remember(attempt) { oidc.newVerifier() }
    val authState = remember(attempt) { oidc.newState() }
    val authorizeUrl = remember(attempt) {
        oidc.authorizeUrl(authState, oidc.challengeS256(verifier))
    }

    // The login page paints itself; the app only has to make sure nothing
    // *around* it reads as "a web page loading" — same base color behind the
    // WebView and under the system bars, so there is no white flash and no
    // grey band when the page over-scrolls.
    val pageBg = MaterialTheme.colorScheme.background
    val pageBgArgb = pageBg.toArgb()

    // Keycloak's theme can't see the host app's night mode (a WebView only
    // reports prefers-color-scheme: dark with algorithmic darkening on), so we
    // tell it explicitly — login.css keys its mobile dark skin off this attr.
    val themeAttr = if (WeMeetTheme.isDark) "dark" else "light"
    val injectTheme: (WebView?) -> Unit = { view ->
        view?.evaluateJavascript(
            "document.documentElement.setAttribute('data-theme','$themeAttr')",
            null,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg),
    ) {
        key(attempt) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                update = { view ->
                    view.setBackgroundColor(pageBgArgb)
                    injectTheme(view)
                },
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Without explicit MATCH_PARENT params Blink never gets
                        // a viewport height for CSS: `100vh` computes to 0 and
                        // EVERY `(max-height: N)` media query matches, while JS
                        // innerHeight reports the right value — so a page can
                        // look fine in Chrome and silently mislay itself here.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The page is a fixed native-scale layout — let the
                        // system font-size setting scale the app's own text,
                        // not this WebView's, or the form blows out of shape.
                        settings.textZoom = 100
                        // Elastic over-scroll exposes the window behind the
                        // page: a dead give-away that this is a browser.
                        overScrollMode = View.OVER_SCROLL_NEVER
                        // Keycloak serves theme assets with max-age=30d and no
                        // validator, under a URL keyed by the *Keycloak* version
                        // — rebuilding the image never busts it. On debug builds
                        // that means theme edits are invisible until the app's
                        // data is cleared, so always go to the network there.
                        if (BuildConfig.DEBUG) {
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        setBackgroundColor(pageBgArgb)
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (!url.startsWith(KeycloakOidc.REDIRECT_URI)) return false
                                handleCallback(Uri.parse(url))
                                return true
                            }

                            // Set the scheme as early as the document exists so
                            // the dark skin is in place before first paint —
                            // waiting for onPageFinished flashes a white page.
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                injectTheme(view)
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                injectTheme(view)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                injectTheme(view)
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                err: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    error = err?.description?.toString() ?: "network error"
                                }
                            }

                            private fun handleCallback(uri: Uri) {
                                val kcError = uri.getQueryParameter("error")
                                val code = uri.getQueryParameter("code")
                                val returnedState = uri.getQueryParameter("state")
                                if (kcError != null || code.isNullOrBlank() ||
                                    returnedState != authState
                                ) {
                                    error = kcError ?: "invalid callback"
                                    return
                                }
                                if (exchanging) return
                                exchanging = true
                                scope.launch {
                                    app.authRepository.completeWebLogin(code, verifier)
                                        .onSuccess {
                                            // Persist the KC session cookie NOW —
                                            // it is what the Docs tab SSOs with.
                                            CookieManager.getInstance().flush()
                                            onLoggedIn()
                                        }
                                        .onFailure { e ->
                                            exchanging = false
                                            error = e.message ?: "token exchange failed"
                                        }
                                }
                            }
                        }
                        loadUrl(authorizeUrl)
                    }
                },
            )
        }

        // Opaque brand splash over the WebView until the page is up (and again
        // while the code is being exchanged). A bare spinner on a half-painted
        // web page is the single most "this is a browser" moment in the flow.
        if (loading || exchanging) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = pageBg,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        error?.let { message ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // The insets moved onto the WebView, so this overlay
                        // has to keep itself clear of the system bars.
                        .systemBarsPadding()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.web_login_error),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    )
                    Button(onClick = {
                        error = null
                        loading = true
                        exchanging = false
                        attempt++
                    }) {
                        Text(stringResource(R.string.web_login_retry))
                    }
                }
            }
        }
    }
}
