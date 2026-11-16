package com.noorramadan.prayertimes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class PrayerNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prayerName = intent.getStringExtra("prayer_name") ?: "الصلاة"
        val prayerTime = intent.getStringExtra("prayer_time") ?: ""
        val locationName = intent.getStringExtra("location_name") ?: "موقعك الحالي"
        val notificationId = intent.getIntExtra("notification_id", 1001)

        showPrayerHeadsUpNotification(
            context = context,
            prayerName = prayerName,
            prayerTime = prayerTime,
            locationName = locationName,
            notificationId = notificationId
        )
    }

    companion object {
        const val CHANNEL_ID = "noor_ramadan_prayer_screen_alerts"
        const val CHANNEL_NAME = "تنبيهات مواقيت الصلاة (على الشاشة)"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات مرئية على شاشة الهاتف عند دخول وقت الصلاة"
                    enableVibration(true)
                    setSound(null, null) // تنبيه مرئي على الشاشة فقط بدون صوت تلقائي مزعج
                }
                manager.createNotificationChannel(channel)
            }
        }

        fun showPrayerHeadsUpNotification(
            context: Context,
            prayerName: String,
            prayerTime: String,
            locationName: String,
            notificationId: Int
        ) {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, notificationId, openIntent, pendingFlags)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🕌 حان الآن موعد أذان $prayerName")
                .setContentText("الميقات: $prayerTime • $locationName — تقبل الله طاعتكم")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "الميقات: $prayerTime • $locationName\n«اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ»"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(notificationId, notification)
        }
    }
}
