package uk.nktnet.webviewkiosk.ui.screens

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.URLUtil.isValidUrl
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.navigation.NavController
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.nktnet.webviewkiosk.config.*
import uk.nktnet.webviewkiosk.config.option.*
import uk.nktnet.webviewkiosk.handlers.backbutton.BackPressHandler
import uk.nktnet.webviewkiosk.handlers.InactivityTimeoutHandler
import uk.nktnet.webviewkiosk.handlers.KioskControlPanel
import uk.nktnet.webviewkiosk.states.LockStateSingleton
import uk.nktnet.webviewkiosk.ui.components.webview.*
import uk.nktnet.webviewkiosk.ui.components.setting.BasicAuthDialog
import uk.nktnet.webviewkiosk.ui.components.webview.AddressBarSearchSuggestions
import uk.nktnet.webviewkiosk.ui.components.webview.LinkOptionsDialog
import uk.nktnet.webviewkiosk.utils.*
import uk.nktnet.webviewkiosk.utils.webview.*
import uk.nktnet.webviewkiosk.utils.webview.html.*
import java.io.File

@Composable
fun WebviewScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val focusManager = LocalFocusManager.current

    val userSettings = remember { UserSettings(context) }
    val systemSettings = remember { SystemSettings(context) }
    val isLocked by LockStateSingleton.isLocked

    val lastVisitedUrl = systemSettings.currentUrl.takeIf { it.isNotEmpty() } ?: userSettings.homeUrl
    var urlBarText by remember { mutableStateOf(TextFieldValue(lastVisitedUrl)) }
    var isSwipeRefreshing by remember { mutableStateOf(false) }
    var addressBarHasFocus by remember { mutableStateOf(false) }
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    val showAddressBar = when (userSettings.addressBarMode) {
        AddressBarOption.SHOWN -> true
        AddressBarOption.HIDDEN -> false
        AddressBarOption.HIDDEN_WHEN_LOCKED -> !isLocked
    }

    var authHandler by remember { mutableStateOf<HttpAuthHandler?>(null) }
    var authHost by remember { mutableStateOf<String?>(null) }
    var authRealm by remember { mutableStateOf<String?>(null) }

    var toastRef: Toast? = null
    val showToast: (String) -> Unit = { msg ->
        toastRef?.cancel()
        toastRef = Toast.makeText(context, msg, Toast.LENGTH_SHORT).apply { show() }
    }

    val blacklistRegexes: List<Regex> by lazy {
        userSettings.websiteBlacklist.lines()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
    }

    val whitelistRegexes: List<Regex> by lazy {
        userSettings.websiteWhitelist.lines()
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
    }

    var lastErrorUrl by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(listOf<String>()) }

    // --- Upload file support ---
    var filePathCallback: ValueCallback<Array<Uri>>? = null
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) } ?: emptyArray())
        filePathCallback = null
    }

    if (userSettings.searchSuggestionEngine != SearchSuggestionEngineOption.NONE) {
        LaunchedEffect(addressBarHasFocus, urlBarText.text) {
            if (addressBarHasFocus && urlBarText.text.isNotBlank() && !isValidUrl(urlBarText.text)) {
                delay(300)
                suggestions = try {
                    withContext(Dispatchers.IO) {
                        SearchSuggestionEngine.suggest(userSettings.searchSuggestionEngine, urlBarText.text)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                suggestions = emptyList()
            }
        }
    }

    DisposableEffect(activity, isLocked) {
        if (activity != null) {
            if (shouldBeImmersed(activity, userSettings)) enterImmersiveMode(activity) else exitImmersiveMode(activity)
        }
        onDispose { activity?.let { exitImmersiveMode(it) } }
    }

    fun updateAddressBarAndHistory(url: String, originalUrl: String?) {
        if (!addressBarHasFocus) urlBarText = urlBarText.copy(text = url)
        WebViewNavigation.appendWebviewHistory(systemSettings, url, originalUrl, userSettings.replaceHistoryUrlOnRedirect)
    }

    val webView = createCustomWebview(
        context = context,
        config = WebViewConfig(
            systemSettings = systemSettings,
            userSettings = userSettings,
            blacklistRegexes = blacklistRegexes,
            whitelistRegexes = whitelistRegexes,
            showToast = showToast,
            setLastErrorUrl = { lastErrorUrl = it },
            onProgressChanged = { progress = it },
            finishSwipeRefresh = { isSwipeRefreshing = false },
            updateAddressBarAndHistory = ::updateAddressBarAndHistory,
            onHttpAuthRequest = { handler, host, realm -> authHandler = handler; authHost = host; authRealm = realm },
            onLinkLongClick = { linkToOpen = it },
        )
    )

    // Upload file support
    webView.webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: android.webkit.WebView?,
            filePathCallback_: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            filePathCallback = filePathCallback_
            getContent.launch("*/*") // hoặc "image/*" nếu chỉ cho upload ảnh
            return true
        }
    }

    fun customLoadUrl(newUrl: String) { /* giữ nguyên code loadUrl từ bản gốc */ }

    val addressBarSearch: (String) -> Unit = { input ->
        val searchUrl = resolveUrlOrSearch(userSettings.searchProviderUrl, input.trim())
        if (searchUrl.isNotBlank() && (searchUrl != systemSettings.currentUrl || userSettings.allowRefresh)) {
            webView.requestFocus()
            customLoadUrl(searchUrl)
        }
    }

    // --- UI ---
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showAddressBar) {
                AndroidView(factory = { ctx ->
                    ComposeView(ctx).apply {
                        setContent {
                            AddressBar(
                                urlBarText = urlBarText,
                                onUrlBarTextChange = { urlBarText = it },
                                hasFocus = addressBarHasFocus,
                                onFocusChanged = { focusState -> addressBarHasFocus = focusState.isFocused },
                                addressBarSearch = addressBarSearch,
                                customLoadUrl = ::customLoadUrl,
                            )
                        }
                    }
                }, modifier = Modifier.fillMaxWidth())
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(factory = { ctx ->
                    webView
                }, modifier = Modifier.fillMaxSize())
                if (progress < 100) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }

                if (addressBarHasFocus && suggestions.isNotEmpty() && userSettings.searchSuggestionEngine != SearchSuggestionEngineOption.NONE) {
                    AddressBarSearchSuggestions(
                        suggestions = suggestions,
                        onSelect = { selected -> addressBarSearch(selected) },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
            }
        }
    }

    // FloatingMenu, BackPressHandler, Dialogs
    if (!isLocked) {
        FloatingMenuButton(
            onHomeClick = { focusManager.clearFocus(); WebViewNavigation.goHome(::customLoadUrl, systemSettings, userSettings) },
            onLockClick = { focusManager.clearFocus(); tryLockTask(activity, showToast) },
            navController = navController
        )
    }

    BackPressHandler(::customLoadUrl)
    BasicAuthDialog(authHandler, authHost, authRealm) { authHandler = null }
    LinkOptionsDialog(link = linkToOpen, onDismiss = { linkToOpen = null }, onOpenLink = { customLoadUrl(it) })
    InactivityTimeoutHandler(systemSettings, userSettings, ::customLoadUrl)
    if (userSettings.kioskControlPanelRegion != KioskControlPanelRegionOption.DISABLED
        || userSettings.backButtonHoldAction == BackButtonHoldActionOption.OPEN_KIOSK_CONTROL_PANEL
    ) {
        KioskControlPanel(10, ::customLoadUrl)
    }

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(userSettings.acceptCookies)
    cookieManager.setAcceptThirdPartyCookies(webView, userSettings.acceptThirdPartyCookies)
}
