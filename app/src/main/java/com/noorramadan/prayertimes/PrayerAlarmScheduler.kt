package com.noorramadan.prayertimes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import java.util.Calendar

object PrayerAlarmScheduler {

    const val PREFS_NAME = "noor_ramadan_prefs"
    const val KEY_PRAYERS_JSON = "saved_prayers_json"
    const val KEY_LOCATION_NAME = "saved_location_name"
    const val KEY_ALERTS_ENABLED = "saved_alerts_enabled"
    const val KEY_WIDGET_NEXT_NAME = "widget_next_name"
    const val KEY_WIDGET_NEXT_TIME = "widget_next_time"
    const val KEY_WIDGET_CITY = "widget_city"
    const val KEY_WIDGET_FAJR = "widget_fajr"
    const val KEY_WIDGET_MAGHRIB = "widget_maghrib"
    const val KEY_WIDGET_HIJRI = "widget_hijri"

    fun saveAndSchedule(
        context: Context,
        prayersJson: String,
        locationName: String,
        enabled: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PRAYERS_JSON, prayersJson)
            .putString(KEY_LOCATION_NAME, locationName)
            .putBoolean(KEY_ALERTS_ENABLED, enabled)
            .apply()

        scheduleFromSaved(context)
        PrayerWidgetProvider.refreshAllWidgets(context)
    }

    fun scheduleFromSaved(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prayersJson = prefs.getString(KEY_PRAYERS_JSON, null) ?: return
        val locationName = prefs.getString(KEY_LOCATION_NAME, "موقعك الحالي") ?: "موقعك الحالي"
        val enabled = prefs.getBoolean(KEY_ALERTS_ENABLED, true)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // إلغاء أي تنبيهات سابقة (صلوات + سحور) قبل إعادة الجدولة
        for (i in 0..11) {
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
                val mode = obj.optString("mode", "screen")
                if (mode == "off") continue

                val name = obj.optString("name", "الصلاة")
                val formatted = obj.optString("formatted", "")
                val hour = obj.optInt("hour", 0)
                val minute = obj.optInt("minute", 0)
                val isSuhoor = obj.optBoolean("isSuhoor", false)

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
                    putExtra("alert_mode", mode)
                    putExtra("is_suhoor", isSuhoor)
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

    fun saveWidgetSnapshot(
        context: Context,
        nextName: String,
        nextTime: String,
        cityName: String,
        fajrTime: String,
        maghribTime: String,
        hijriDate: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_WIDGET_NEXT_NAME, nextName)
            .putString(KEY_WIDGET_NEXT_TIME, nextTime)
            .putString(KEY_WIDGET_CITY, cityName)
            .putString(KEY_WIDGET_FAJR, fajrTime)
            .putString(KEY_WIDGET_MAGHRIB, maghribTime)
            .putString(KEY_WIDGET_HIJRI, hijriDate)
            .apply()
        PrayerWidgetProvider.refreshAllWidgets(context)
    }
}
