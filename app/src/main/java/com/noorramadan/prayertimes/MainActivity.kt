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
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity(), SensorEventListener {

    private var appWebView: WebView? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastGravity = FloatArray(3)
    private val lastMagnet = FloatArray(3)
    private var hasGravity = false
    private var hasMagnet = false

    // تصفية دائرية متكيفة + عتبة ثبات (Deadband) لمنع أي اهتزاز في بوصلة القبلة
    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var hasSmoothInit = false
    private var lastPushedDeg = -999.0
    private var lastCompassPushMs = 0L

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        PrayerNotificationReceiver.ensureChannels(this)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

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
        window.statusBarColor = android.graphics.Color.parseColor("#EEF2FF")
        window.navigationBarColor = android.graphics.Color.parseColor("#EEF2FF")

        PrayerNotificationReceiver.ensureChannels(this)
        requestNotificationPermissionIfNeeded()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }

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
                bridge = AndroidPrayerBridge(this),
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasSmoothInit = false
        lastPushedDeg = -999.0
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magnetometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        appWebView?.evaluateJavascript(
            "if(window.syncLiveTodayDateIfNeeded){window.syncLiveTodayDateIfNeeded();}",
            null
        )
        PrayerWidgetProvider.refreshAllWidgets(this)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        var rawAzimuthRad: Double? = null

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rawAzimuthRad = orientationAngles[0].toDouble()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastGravity, 0, 3)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMagnet, 0, 3)
                hasMagnet = true
            }
        }

        if (rawAzimuthRad == null && hasGravity && hasMagnet) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, lastGravity, lastMagnet)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rawAzimuthRad = orientationAngles[0].toDouble()
            }
        }

        if (rawAzimuthRad != null) {
            val s = sin(rawAzimuthRad)
            val c = cos(rawAzimuthRad)
            if (!hasSmoothInit) {
                smoothSin = s
                smoothCos = c
                hasSmoothInit = true
            } else {
                // تصفية متكيفة: ثبات عالٍ جداً عند السكون (0.06) واستجابة سلسة عند التدوير المقصود (0.20)
                val prevRad = atan2(smoothSin, smoothCos)
                val rawDeg = (Math.toDegrees(rawAzimuthRad) + 360.0) % 360.0
                val prevDeg = (Math.toDegrees(prevRad) + 360.0) % 360.0
                val delta = abs(((rawDeg - prevDeg + 540.0) % 360.0) - 180.0)
                val alpha = if (delta > 14.0) 0.20 else 0.06
                smoothSin += alpha * (s - smoothSin)
                smoothCos += alpha * (c - smoothCos)
            }

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastCompassPushMs >= 65L) {
                val filteredRad = atan2(smoothSin, smoothCos)
                val azimuthDeg = ((Math.toDegrees(filteredRad) + 360.0) % 360.0)
                val diffFromLast = if (lastPushedDeg < 0) 999.0 else abs(((azimuthDeg - lastPushedDeg + 540.0) % 360.0) - 180.0)
                if (diffFromLast >= 1.2) {
                    lastCompassPushMs = nowMs
                    lastPushedDeg = azimuthDeg
                    val stableDeg = azimuthDeg.roundToInt() % 360
                    appWebView?.evaluateJavascript(
                        "if(window.updateNativeCompassHeading){window.updateNativeCompassHeading($stableDeg);}",
                        null
                    )
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val needsCalibration = (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                    accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
            appWebView?.evaluateJavascript(
                "if(window.updateNativeCompassAccuracy){window.updateNativeCompassAccuracy(${!needsCalibration});}",
                null
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

    inner class AndroidPrayerBridge(private val context: Context) {

        @JavascriptInterface
        fun showImmediatePrayerNotification(
            prayerName: String,
            prayerTime: String,
            locationName: String
        ) {
            requestNotificationPermissionIfNeeded()
            PrayerNotificationReceiver.showPrayerHeadsUpNotification(
                context = context,
                prayerName = prayerName,
                prayerTime = prayerTime,
                locationName = locationName,
                notificationId = (System.currentTimeMillis() % 10000).toInt(),
                withSound = false,
                isSuhoor = false
            )
        }

        @JavascriptInterface
        fun scheduleDailyPrayerAlarms(prayersJson: String, locationName: String, enabled: Boolean) {
            PrayerAlarmScheduler.saveAndSchedule(
                context = context,
                prayersJson = prayersJson,
                locationName = locationName,
                enabled = enabled
            )
        }

        @JavascriptInterface
        fun updateWidgetData(
            nextName: String,
            nextTime: String,
            cityName: String,
            fajrTime: String,
            maghribTime: String,
            hijriDate: String
        ) {
            PrayerAlarmScheduler.saveWidgetSnapshot(
                context = context,
                nextName = nextName,
                nextTime = nextTime,
                cityName = cityName,
                fajrTime = fajrTime,
                maghribTime = maghribTime,
                hijriDate = hijriDate
            )
        }

        @JavascriptInterface
        fun isIgnoringBatteryOptimizations(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        @JavascriptInterface
        @SuppressLint("BatteryLife")
        fun requestIgnoreBatteryOptimizations() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(fallback)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NoorRamadanMainScreen(
    bridge: MainActivity.AndroidPrayerBridge,
    onWebViewCreated: (WebView) -> Unit,
    onRequestGeoPermission: (String?, GeolocationPermissions.Callback?) -> Unit,
    onShowAudioFileChooser: (ValueCallback<Array<Uri>>) -> Boolean
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEEF2FF))
            .systemBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#EEF2FF"))

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

                addJavascriptInterface(bridge, "AndroidBridge")

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
