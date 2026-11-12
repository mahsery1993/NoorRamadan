package com.noorramadan.prayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * MainActivity — نقطة الدخول الرئيسية لتطبيق نور رمضان ومواقيت الصلاة
 * مهيأ لإصدار APK المباشر وإصدار Google Play Store (AAB - Target SDK 35)
 */
class MainActivity : ComponentActivity() {

    private var appWebView: WebView? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // طلب صلاحية الموقع الجغرافي (GPS) لحساب مواقيت الصلاة واتجاه القبلة بدقة
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ضبط ألوان شريط الحالة والنظام بما يتوافق مع متطلبات Android 15 و Google Play
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#05110D")
        window.navigationBarColor = android.graphics.Color.parseColor("#05110D")

        // التعامل مع زر الرجوع في أندرويد للتنقل السلس داخل التطبيق
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = appWebView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            NoorRamadanMainScreen(
                onWebViewCreated = { webView -> appWebView = webView },
                onRequestGeoPermission = { origin, callback ->
                    handleGeolocationPermission(origin, callback)
                }
            )
        }
    }

    private fun handleGeolocationPermission(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            callback?.invoke(origin, true, false)
        } else {
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onDestroy() {
        appWebView?.destroy()
        appWebView = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NoorRamadanMainScreen(
    onWebViewCreated: (WebView) -> Unit,
    onRequestGeoPermission: (String?, GeolocationPermissions.Callback?) -> Unit
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071712))
            .systemBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#071712"))

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    setGeolocationEnabled(true)
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = true
                    allowContentAccess = true
                    textZoom = 100
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return if (url.startsWith("file:///android_asset/")) {
                            false
                        } else {
                            // فتح الروابط المرجعية الخارجية في المتصفح الخارجي بأمان
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    request.url
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                            true
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: GeolocationPermissions.Callback?
                    ) {
                        onRequestGeoPermission(origin, callback)
                    }
                }

                loadUrl("file:///android_asset/index.html")
                onWebViewCreated(this)
            }
        }
    )
}
