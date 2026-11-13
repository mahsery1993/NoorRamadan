package com.noorramadan.prayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
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
import kotlin.math.roundToInt

/**
 * MainActivity — نقطة الدخول الرئيسية لتطبيق نور رمضان ومواقيت الصلاة
 * يدعم:
 * 1. حساسات البوصلة المغناطيسية الأصلية (SensorManager TYPE_ROTATION_VECTOR) لتوجيه القبلة بدقة
 * 2. تحديد الموقع الجغرافي (GPS) عبر صلاحيات أندرويد الرسمية
 * 3. اختيار وتشغيل ملف صوت الأذان المخصص من الهاتف أو من مجلد res/raw/adhan.mp3
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    private var appWebView: WebView? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

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

    // دعم اختيار ملف صوت الأذان من ذاكرة الهاتف داخل WebView
    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            filePathCallback?.onReceiveValue(arrayOf(uri))
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.parseColor("#05110D")
        window.navigationBarColor = android.graphics.Color.parseColor("#05110D")

        // تهيئة حساس البوصلة المغناطيسي في الهاتف
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
                },
                onShowAudioFileChooser = { callback ->
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                    }
                    audioPickerLauncher.launch(Intent.createChooser(intent, "اختر ملف صوت الأذان"))
                    true
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthRad = orientationAngles[0].toDouble()
            val azimuthDeg = ((Math.toDegrees(azimuthRad) + 360.0) % 360.0).roundToInt()
            appWebView?.evaluateJavascript(
                "if(window.updateNativeCompassHeading){window.updateNativeCompassHeading($azimuthDeg);}",
                null
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
    onRequestGeoPermission: (String?, GeolocationPermissions.Callback?) -> Unit,
    onShowAudioFileChooser: (ValueCallback<Array<Uri>>) -> Boolean
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
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, request.url)
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

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        return if (filePathCallback != null) {
                            onShowAudioFileChooser(filePathCallback)
                        } else {
                            false
                        }
                    }
                }

                loadUrl("file:///android_asset/index.html")
                onWebViewCreated(this)
            }
        }
    )
}
