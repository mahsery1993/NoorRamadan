package com.noorramadan.prayertimes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

class PrayerNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prayerName = intent.getStringExtra("prayer_name") ?: "الصلاة"
        val prayerTime = intent.getStringExtra("prayer_time") ?: ""
        val locationName = intent.getStringExtra("location_name") ?: "موقعك الحالي"
        val notificationId = intent.getIntExtra("notification_id", 1001)
        val alertMode = intent.getStringExtra("alert_mode") ?: "screen"
        val isSuhoor = intent.getBooleanExtra("is_suhoor", false)

        if (alertMode != "off") {
            showPrayerHeadsUpNotification(
                context = context,
                prayerName = prayerName,
                prayerTime = prayerTime,
                locationName = locationName,
                notificationId = notificationId,
                withSound = (alertMode == "sound"),
                isSuhoor = isSuhoor
            )
        }

        // إعادة جدولة منبهات اليوم التالي وتحديث الودجت تلقائياً
        PrayerAlarmScheduler.scheduleFromSaved(context)
        PrayerWidgetProvider.refreshAllWidgets(context)
    }

    companion object {
        const val CHANNEL_ID_SCREEN = "noor_ramadan_prayer_screen_alerts"
        const val CHANNEL_NAME_SCREEN = "تنبيهات مواقيت الصلاة (على الشاشة)"
        const val CHANNEL_ID_SOUND = "noor_ramadan_prayer_sound_alerts"
        const val CHANNEL_NAME_SOUND = "تنبيهات مواقيت الصلاة (صوت وشاشة)"

        fun ensureChannel(context: Context) {
            ensureChannels(context)
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val screenChannel = NotificationChannel(
                    CHANNEL_ID_SCREEN,
                    CHANNEL_NAME_SCREEN,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات مرئية على شاشة الهاتف عند دخول وقت الصلاة بدون صوت"
                    enableVibration(true)
                    setSound(null, null)
                }
                manager.createNotificationChannel(screenChannel)

                val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val soundChannel = NotificationChannel(
                    CHANNEL_ID_SOUND,
                    CHANNEL_NAME_SOUND,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات صوتية ومرئية عند دخول وقت الصلاة أو تنبيه السحور"
                    enableVibration(true)
                    setSound(defaultSound, audioAttrs)
                }
                manager.createNotificationChannel(soundChannel)
            }
        }

        fun showPrayerHeadsUpNotification(
            context: Context,
            prayerName: String,
            prayerTime: String,
            locationName: String,
            notificationId: Int,
            withSound: Boolean = false,
            isSuhoor: Boolean = false
        ) {
            ensureChannels(context)
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

            val channelId = if (withSound) CHANNEL_ID_SOUND else CHANNEL_ID_SCREEN
            val title = if (isSuhoor) {
                "🌙 تذكير وقت السحور والإمساك"
            } else {
                "🕌 حان الآن موعد أذان $prayerName"
            }
            val shortText = if (isSuhoor) {
                "$prayerName • الفجر: $prayerTime • $locationName"
            } else {
                "الميقات: $prayerTime • $locationName — تقبل الله طاعتكم"
            }
            val expandedText = if (isSuhoor) {
                "$prayerName ($locationName)\n«تَسَحَّرُوا فَإِنَّ فِي السَّحُورِ بَرَكَةً»"
            } else {
                "الميقات: $prayerTime • $locationName\n«اللَّهُمَّ رَبَّ هَذِهِ الدَّعْوَةِ التَّامَّةِ، وَالصَّلَاةِ الْقَائِمَةِ، آتِ مُحَمَّدًا الْوَسِيلَةَ وَالْفَضِيلَةَ»"
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (!withSound) {
                builder.setSilent(true)
            }

            manager.notify(notificationId, builder.build())
        }
    }
}
