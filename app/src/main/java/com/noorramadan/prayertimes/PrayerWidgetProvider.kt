package com.noorramadan.prayertimes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Calendar

class PrayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PrayerWidgetProvider::class.java)
            )
            for (id in ids) {
                updateSingleWidget(context, manager, id)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PrayerAlarmScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            var nextName = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_NEXT_NAME, "الفجر") ?: "الفجر"
            var nextTime = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_NEXT_TIME, "--:--") ?: "--:--"
            val city = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_CITY, "عمّان، الأردن") ?: "عمّان، الأردن"
            val fajr = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_FAJR, "--:--") ?: "--:--"
            val maghrib = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_MAGHRIB, "--:--") ?: "--:--"
            val hijri = prefs.getString(PrayerAlarmScheduler.KEY_WIDGET_HIJRI, "نور رمضان") ?: "نور رمضان"

            // حساب الصلاة القادمة مباشرة من جدول الصلوات المحفوظ لضمان دقة الودجت حتى لو لم يُفتح التطبيق
            val prayersJson = prefs.getString(PrayerAlarmScheduler.KEY_PRAYERS_JSON, null)
            var remainingLabel = "افتح التطبيق للتفاصيل"
            if (prayersJson != null) {
                try {
                    val arr = JSONArray(prayersJson)
                    val now = Calendar.getInstance()
                    val nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    var bestDiff = Int.MAX_VALUE
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (obj.optBoolean("isSuhoor", false)) continue
                        val pMins = obj.optInt("hour", 0) * 60 + obj.optInt("minute", 0)
                        val diff = if (pMins > nowMins) pMins - nowMins else (1440 - nowMins + pMins)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            nextName = obj.optString("name", nextName)
                            nextTime = obj.optString("formatted", nextTime)
                        }
                    }
                    if (bestDiff != Int.MAX_VALUE) {
                        val hrs = bestDiff / 60
                        val mins = bestDiff % 60
                        remainingLabel = if (hrs > 0) {
                            "متبقي $hrs س و $mins د"
                        } else {
                            "متبقي $mins دقيقة"
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_prayer_times)
            views.setTextViewText(R.id.widget_city_hijri, "$city • $hijri")
            views.setTextViewText(R.id.widget_next_prayer_name, "الصلاة القادمة: $nextName")
            views.setTextViewText(R.id.widget_next_prayer_time, nextTime)
            views.setTextViewText(R.id.widget_remaining_time, remainingLabel)
            views.setTextViewText(R.id.widget_fajr_maghrib, "الفجر: $fajr  |  المغرب: $maghrib")

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getActivity(context, 3001, openIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
