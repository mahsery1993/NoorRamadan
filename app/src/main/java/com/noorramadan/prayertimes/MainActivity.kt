package com.noorramadan.prayertimes

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
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
import org.json.JSONArray
import java.util.Calendar
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener {

    private var appWebView: WebView? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // طلب صلاحية الإشعارات في أندرويد 13+ (TIRAMISU) لضمان وصول إشعارات الصلاة على شاشة الهاتف
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        PrayerNotificationReceiver.ensureChannel(this)
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
        window.statusBarColor = android.graphics.Color.parseColor("#050A18")
        window.navigationBarColor = android.graphics.Color.parseColor("#050A18")

        // تهيئة قناة إشعارات الصلاة وطلب الصلاحية على أندرويد 13 فما فوق
        PrayerNotificationReceiver.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

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
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // تحديث تاريخ اليوم تلقائياً عند العودة للتطبيق
        appWebView?.evaluateJavascript(
            "if(window.syncLiveTodayDateIfNeeded){window.syncLiveTodayDateIfNeeded();}",
            null
        )
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

    inner class AndroidPrayerBridge(private val context: Context) {

        @JavascriptInterface
        fun showImmediatePrayerNotification(prayerName: String, prayerTime: String, locationName: String) {
            requestNotificationPermissionIfNeeded()
            PrayerNotificationReceiver.showPrayerHeadsUpNotification(
                context = context,
                prayerName = prayerName,
                prayerTime = prayerTime,
                locationName = locationName,
                notificationId = (System.currentTimeMillis() % 10000).toInt()
            )
        }

        @JavascriptInterface
        fun scheduleDailyPrayerAlarms(prayersJson: String, locationName: String, enabled: Boolean) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // إلغاء أي تنبيهات سابقة قبل إعادة الجدولة
            for (i in 0..9) {
                val cancelIntent = Intent(context, PrayerNotificationReceiver::class.java)
                val pi = PendingIntent.getBroadcast(context, 2000 + i, cancelIntent, pendingFlags)
                alarmManager.cancel(pi)
            }

            if (!enabled) return

            try {
                val arr = JSONArray(prayersJson)
                val nowMillis = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "الصلاة")
                    val formatted = obj.optString("formatted", "")
                    val hour = obj.optInt("hour", 0)
                    val minute = obj.optInt("minute", 0)

                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= nowMillis) {
                            add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }

                    val intent = Intent(context, PrayerNotificationReceiver::class.java).apply {
                        putExtra("prayer_name", name)
                        putExtra("prayer_time", formatted)
                        putExtra("location_name", locationName)
                        putExtra("notification_id", 2000 + i)
                    }
                    val pi = PendingIntent.getBroadcast(context, 2000 + i, intent, pendingFlags)

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                cal.timeInMillis,
                                pi
                            )
                        } else {
                            alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                        }
                    } catch (_: SecurityException) {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                    }
                }
            } catch (_: Exception) {
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
            .background(Color(0xFF070D1F))
            .systemBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.parseColor("#070D1F"))

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
