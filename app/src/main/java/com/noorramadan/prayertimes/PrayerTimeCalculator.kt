package com.noorramadan.prayertimes

import kotlin.math.*

data class DailyPrayerSchedule(
    val dayLabel: String,
    val imsak: String,
    val fajr: String,
    val sunrise: String,
    val dhuhr: String,
    val asr: String,
    val maghrib: String,
    val isha: String,
    val qiblaBearing: Double,
    val qiblaDistanceKm: Int
)

class PrayerTimeCalculator {
    private fun dtr(d: Double) = (d * PI) / 180.0
    private fun rtd(r: Double) = (r * 180.0) / PI
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(h: Double) = h - 24.0 * floor(h / 24.0)

    fun julianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(dtr(g)) + 0.020 * sin(dtr(2 * g)))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(rtd(atan2(cos(dtr(e)) * sin(dtr(l)), cos(dtr(l)))) / 15.0)
        var eqt = q / 15.0 - ra
        if (eqt > 12.0) eqt -= 24.0
        if (eqt < -12.0) eqt += 24.0
        val decl = rtd(asin(sin(dtr(e)) * sin(dtr(l))))
        return Pair(decl, eqt)
    }

    fun calculateDayJordanAwqaf(
        year: Int, month: Int, day: Int,
        lat: Double = 31.95522, lng: Double = 35.94503, tz: Double = 3.0
    ): DailyPrayerSchedule {
        val jd = julianDate(year, month, day) - lng / (15.0 * 24.0)
        val (decl, eqt) = sunPosition(jd + 0.5)
        val transit = 12.0 + tz - lng / 15.0 - eqt

        fun hourAngle(angle: Double): Double {
            val num = -sin(dtr(angle)) - sin(dtr(lat)) * sin(dtr(decl))
            val den = cos(dtr(lat)) * cos(dtr(decl))
            return rtd(acos((num / den).coerceIn(-1.0, 1.0))) / 15.0
        }

        val fajr = transit - hourAngle(18.0)
        val imsak = fajr - (10.0 / 60.0)
        val sunrise = transit - hourAngle(0.833) - (6.0 / 60.0)
        val dhuhr = transit + (0.6 / 60.0)
        val asrAngle = -rtd(atan(1.0 / (1.0 + tan(abs(dtr(lat - decl))))))
        val asr = transit + hourAngle(asrAngle) + (0.3 / 60.0)
        val maghrib = transit + hourAngle(0.833) + (6.4 / 60.0)
        val isha = transit + hourAngle(18.0) + (0.5 / 60.0)

        val (bearing, distKm) = calculateQibla(lat, lng)

        return DailyPrayerSchedule(
            dayLabel = "$year-$month-$day",
            imsak = formatTime(imsak),
            fajr = formatTime(fajr),
            sunrise = formatTime(sunrise),
            dhuhr = formatTime(dhuhr),
            asr = formatTime(asr),
            maghrib = formatTime(maghrib),
            isha = formatTime(isha),
            qiblaBearing = bearing,
            qiblaDistanceKm = distKm
        )
    }

    fun calculateQibla(lat: Double, lng: Double): Pair<Double, Int> {
        val kaabaLat = dtr(21.4225)
        val kaabaLng = dtr(39.8262)
        val phi1 = dtr(lat)
        val lam1 = dtr(lng)
        val dLam = kaabaLng - lam1
        val y = sin(dLam) * cos(kaabaLat)
        val x = cos(phi1) * sin(kaabaLat) - sin(phi1) * cos(kaabaLat) * cos(dLam)
        val bearing = (rtd(atan2(y, x)) + 360.0) % 360.0

        val dPhi = kaabaLat - phi1
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(kaabaLat) * sin(dLam / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val distKm = (6371.0088 * c).roundToInt()
        return Pair(bearing, distKm)
    }

    private fun formatTime(hours: Double): String {
        val norm = fixHour(hours)
        val totalMin = (norm * 60.0).roundToInt()
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return String.format("%02d:%02d", h, m)
    }
}
