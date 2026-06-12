package com.kou.otoskop.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Çevrimdışı (internet gerektirmeyen) gök cismi konum hesabı.
 *
 * Verilen gözlemci konumu (enlem/boylam) ve UTC zaman için Güneş, Ay ve çıplak
 * gözle görülebilen 5 gezegenin (Merkür, Venüs, Mars, Jüpiter, Satürn) o anki
 * **azimut / yükseklik (alt-az)** konumunu döndürür.
 *
 * Yöntem: Paul Schlyter'in "Computing planetary positions" algoritması
 * (http://stjarnhimlen.se/comp/ppcomp.html) — klasik, doğrulanmış, kompakt.
 * Doğruluk: Güneş/Ay derece-altı, gezegenler ~birkaç arcdakika (Jüpiter/Satürn
 * pertürbasyon düzeltmeleri dahil). Servo + MPU hassasiyeti için fazlasıyla
 * yeterli; en önemlisi hiçbir ağ servisine bağımlı değil (503 sorunları biter).
 *
 * Tüm açılar derece cinsinden; saf fonksiyonlar (test edilebilir).
 */
object Ephemeris {

    data class Body(
        val name: String,
        val type: String,
        val azimuth: Double,
        val altitude: Double,
        val magnitude: Double,
    )

    /**
     * Verilen konum/zaman için ufuk üstündeki (alt > 0) tüm cisimleri döndürür.
     * [aboveHorizonOnly] false ise ufuk altındakiler de gelir (filtreyi çağıran yapar).
     */
    fun visibleBodies(
        latitude: Double,
        longitude: Double,
        timeUtc: Instant,
        aboveHorizonOnly: Boolean = true,
    ): List<Body> {
        val d = dayNumber(timeUtc)
        val ecl = 23.4393 - 3.563e-7 * d

        // Güneş — diğer hesapların referansı (geosentrik Güneş konumu).
        val sun = sunPosition(d)

        val bodies = ArrayList<Body>(8)
        fun add(name: String, type: String, eq: Equatorial, magnitude: Double) {
            val hor = toHorizontal(eq, latitude, longitude, d, sun)
            if (!aboveHorizonOnly || hor.altitude > 0.0) {
                bodies.add(Body(name, type, hor.azimuth, hor.altitude, magnitude))
            }
        }

        // Güneş
        add("Güneş", "sun", sun.equatorial(ecl), -26.7)

        // Ay (geosentrik; pertürbasyonlu)
        val moon = moonPosition(d)
        add("Ay", "moon", moon.equatorial(ecl), moonMagnitude(moon, sun))

        // Gezegenler
        for (p in Planet.values()) {
            val helio = p.heliocentric(d)
            // Jüpiter/Satürn pertürbasyon düzeltmeleri ekliptik lon/lat'a uygulanır.
            val corrected = p.applyPerturbations(helio, d)
            // Geosentrik = heliosentrik + Güneş'in geosentrik konumu.
            val geo = EclRect(
                corrected.x + sun.x,
                corrected.y + sun.y,
                corrected.z + sun.z,
            )
            val mag = p.magnitude(rHelio = corrected.r, rGeo = geo.r, rSun = sun.r, geo = geo, d = d)
            add(p.displayName, "planet", geo.equatorial(ecl), mag)
        }

        return bodies.sortedBy { it.magnitude }
    }

    // ----------------------------- Zaman ------------------------------------
    /** Schlyter gün numarası (2000-01-01 00:00 UT = 0), UT kesirli dahil. */
    private fun dayNumber(instant: Instant): Double {
        val t = instant.atOffset(ZoneOffset.UTC)
        val y = t.year
        val m = t.monthValue
        val day = t.dayOfMonth
        val ut = t.hour + t.minute / 60.0 + t.second / 3600.0
        // Tamsayı bölmeli formül (Schlyter) — int aritmetiği bilerek korunur.
        val dInt = 367 * y - 7 * (y + (m + 9) / 12) / 4 + 275 * m / 9 + day - 730530
        return dInt + ut / 24.0
    }

    // ----------------------------- Güneş ------------------------------------
    private fun sunPosition(d: Double): Sun {
        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val m = rev(356.0470 + 0.9856002585 * d)
        val ea = m + degOf(e) * sind(m) * (1.0 + e * cosd(m))
        val xv = cosd(ea) - e
        val yv = sqrt(1.0 - e * e) * sind(ea)
        val v = atan2d(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val lon = rev(v + w)
        // Güneş ekliptik düzlemde: z = 0
        val x = r * cosd(lon)
        val y = r * sind(lon)
        // Ortalama boylam (yıldız zamanı için): Ls = M + w
        val meanLon = rev(m + w)
        return Sun(x = x, y = y, z = 0.0, r = r, meanLongitude = meanLon)
    }

    // ----------------------------- Ay ---------------------------------------
    private fun moonPosition(d: Double): Moon {
        val n = rev(125.1228 - 0.0529538083 * d)
        val i = 5.1454
        val w = rev(318.0634 + 0.1643573223 * d)
        val a = 60.2666
        val e = 0.054900
        val m = rev(115.3654 + 13.0649929509 * d)

        // Eksantrik anomali (Ay'da e büyük -> iterasyon)
        var ea = m + degOf(e) * sind(m) * (1.0 + e * cosd(m))
        repeat(5) {
            val dEa = (ea - degOf(e) * sind(ea) - m) / (1.0 - e * cosd(ea))
            ea -= dEa
            if (abs(dEa) < 1e-5) return@repeat
        }
        val xv = a * (cosd(ea) - e)
        val yv = a * (sqrt(1.0 - e * e) * sind(ea))
        val v = atan2d(yv, xv)
        val r = sqrt(xv * xv + yv * yv)

        // Geosentrik ekliptik (pertürbasyonsuz)
        var lon = rev(n + v + w)
        val xh = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(i))
        val yh = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(i))
        val zh = r * (sind(v + w) * sind(i))
        var lonecl = atan2d(yh, xh)
        var latecl = atan2d(zh, sqrt(xh * xh + yh * yh))
        var rg = r

        // --- Ay pertürbasyonları (Schlyter, en büyük terimler) ---
        val ws = 282.9404 + 4.70935e-5 * d
        val ms = rev(356.0470 + 0.9856002585 * d)        // Güneş ort. anomali
        val ls = rev(ms + ws)                            // Güneş ort. boylam
        val lm = rev(m + w + n)                          // Ay ort. boylam
        val dm = rev(lm - ls)                            // ort. elongasyon
        val f = rev(lm - n)                              // enlem argümanı

        lonecl += (-1.274 * sind(m - 2 * dm)
            + 0.658 * sind(2 * dm)
            - 0.186 * sind(ms)
            - 0.059 * sind(2 * m - 2 * dm)
            - 0.057 * sind(m - 2 * dm + ms)
            + 0.053 * sind(m + 2 * dm)
            + 0.046 * sind(2 * dm - ms)
            + 0.041 * sind(m - ms)
            - 0.035 * sind(dm)
            - 0.031 * sind(m + ms)
            - 0.015 * sind(2 * f - 2 * dm)
            + 0.011 * sind(m - 4 * dm))
        latecl += (-0.173 * sind(f - 2 * dm)
            - 0.055 * sind(m - f - 2 * dm)
            - 0.046 * sind(m + f - 2 * dm)
            + 0.033 * sind(f + 2 * dm)
            + 0.017 * sind(2 * m + f))
        rg += (-0.58 * cosd(m - 2 * dm) - 0.46 * cosd(2 * dm))

        lon = rev(lonecl)
        // Düzeltilmiş geosentrik dikdörtgen
        val x = rg * cosd(lon) * cosd(latecl)
        val y = rg * sind(lon) * cosd(latecl)
        val z = rg * sind(latecl)
        return Moon(x, y, z, rg)
    }

    private fun moonMagnitude(moon: Moon, sun: Sun): Double {
        // Faz açısına bağlı kaba yaklaşım: dolunay ~ -12.7, yeniay'a doğru söner.
        val cosPhase = ((moon.x * (-sun.x) + moon.y * (-sun.y) + moon.z * (-sun.z)) /
            (moon.r * sun.r)).coerceIn(-1.0, 1.0)
        val phase = (1.0 + cosPhase) / 2.0   // 0=yeniay, 1=dolunay
        return -12.7 + 2.5 * log10((1.0 - phase + 0.02).coerceAtLeast(0.02))
    }

    // ----------------------------- Gezegenler -------------------------------
    private enum class Planet(val displayName: String) {
        MERCURY("Merkür"),
        VENUS("Venüs"),
        MARS("Mars"),
        JUPITER("Jüpiter"),
        SATURN("Satürn");

        fun heliocentric(d: Double): EclRect {
            val (n, i, w, a, e, m) = elements(d)
            var ea = m + degOf(e) * sind(m) * (1.0 + e * cosd(m))
            repeat(5) {
                val dEa = (ea - degOf(e) * sind(ea) - m) / (1.0 - e * cosd(ea))
                ea -= dEa
                if (abs(dEa) < 1e-6) return@repeat
            }
            val xv = a * (cosd(ea) - e)
            val yv = a * (sqrt(1.0 - e * e) * sind(ea))
            val v = atan2d(yv, xv)
            val r = sqrt(xv * xv + yv * yv)
            val x = r * (cosd(n) * cosd(v + w) - sind(n) * sind(v + w) * cosd(i))
            val y = r * (sind(n) * cosd(v + w) + cosd(n) * sind(v + w) * cosd(i))
            val z = r * (sind(v + w) * sind(i))
            return EclRect(x, y, z)
        }

        /** Sadece Jüpiter/Satürn için anlamlı; diğerlerinde aynen döner. */
        fun applyPerturbations(helio: EclRect, d: Double): EclRect {
            if (this != JUPITER && this != SATURN) return helio
            val mj = rev(19.8950 + 0.0830853001 * d)
            val msat = rev(316.9670 + 0.0334442282 * d)
            var lonecl = atan2d(helio.y, helio.x)
            var latecl = atan2d(helio.z, sqrt(helio.x * helio.x + helio.y * helio.y))
            val r = helio.r
            when (this) {
                JUPITER -> {
                    lonecl += (-0.332 * sind(2 * mj - 5 * msat - 67.6)
                        - 0.056 * sind(2 * mj - 2 * msat + 21)
                        + 0.042 * sind(3 * mj - 5 * msat + 21)
                        - 0.036 * sind(mj - 2 * msat)
                        + 0.022 * cosd(mj - msat)
                        + 0.023 * sind(2 * mj - 3 * msat + 52)
                        - 0.016 * sind(mj - 5 * msat - 69))
                }
                SATURN -> {
                    lonecl += (0.812 * sind(2 * mj - 5 * msat - 67.6)
                        - 0.229 * cosd(2 * mj - 4 * msat - 2)
                        + 0.119 * sind(mj - 2 * msat - 3)
                        + 0.046 * sind(2 * mj - 6 * msat - 69)
                        + 0.014 * sind(mj - 3 * msat + 32))
                    latecl += (-0.020 * cosd(2 * mj - 4 * msat - 2)
                        + 0.018 * sind(2 * mj - 6 * msat - 49))
                }
                else -> {}
            }
            val x = r * cosd(lonecl) * cosd(latecl)
            val y = r * sind(lonecl) * cosd(latecl)
            val z = r * sind(latecl)
            return EclRect(x, y, z)
        }

        fun magnitude(rHelio: Double, rGeo: Double, rSun: Double, geo: EclRect, d: Double): Double {
            // Faz açısı (Sun-planet-Earth, gezegen köşesinde), kosinüs teoremi.
            val cosFv = ((rHelio * rHelio + rGeo * rGeo - rSun * rSun) /
                (2.0 * rHelio * rGeo)).coerceIn(-1.0, 1.0)
            val fv = acosd(cosFv)
            val base = 5.0 * log10(rHelio * rGeo)
            return when (this) {
                MERCURY -> -0.36 + base + 0.027 * fv + 2.2e-13 * pow6(fv)
                VENUS -> -4.34 + base + 0.013 * fv + 4.2e-7 * fv * fv * fv
                MARS -> -1.51 + base + 0.016 * fv
                JUPITER -> -9.25 + base + 0.014 * fv
                SATURN -> {
                    // Halka katkısı: Schlyter halka eğim açısı B.
                    val ir = 28.06
                    val nr = 169.51 + 3.82e-5 * d
                    val lon = atan2d(geo.y, geo.x)
                    val lat = atan2d(geo.z, sqrt(geo.x * geo.x + geo.y * geo.y))
                    val b = asind(sind(lat) * cosd(ir) - cosd(lat) * sind(ir) * sind(lon - nr))
                    val ring = -2.6 * sind(abs(b)) + 1.2 * sind(b) * sind(b)
                    -9.0 + base + 0.044 * fv + ring
                }
            }
        }

        /** N, i, w, a, e, M elemanları (Schlyter, d = gün numarası). */
        fun elements(d: Double): Elements = when (this) {
            MERCURY -> Elements(
                rev(48.3313 + 3.24587e-5 * d), 7.0047 + 5.00e-8 * d,
                rev(29.1241 + 1.01444e-5 * d), 0.387098,
                0.205635 + 5.59e-10 * d, rev(168.6562 + 4.0923344368 * d),
            )
            VENUS -> Elements(
                rev(76.6799 + 2.46590e-5 * d), 3.3946 + 2.75e-8 * d,
                rev(54.8910 + 1.38374e-5 * d), 0.723330,
                0.006773 - 1.302e-9 * d, rev(48.0052 + 1.6021302244 * d),
            )
            MARS -> Elements(
                rev(49.5574 + 2.11081e-5 * d), 1.8497 - 1.78e-8 * d,
                rev(286.5016 + 2.92961e-5 * d), 1.523688,
                0.093405 + 2.516e-9 * d, rev(18.6021 + 0.5240207766 * d),
            )
            JUPITER -> Elements(
                rev(100.4542 + 2.76854e-5 * d), 1.3030 - 1.557e-7 * d,
                rev(273.8777 + 1.64505e-5 * d), 5.20256,
                0.048498 + 4.469e-9 * d, rev(19.8950 + 0.0830853001 * d),
            )
            SATURN -> Elements(
                rev(113.6634 + 2.38980e-5 * d), 2.4886 - 1.081e-7 * d,
                rev(339.3939 + 2.97661e-5 * d), 9.55475,
                0.055546 - 9.499e-9 * d, rev(316.9670 + 0.0334442282 * d),
            )
        }
    }

    // ----------------------- Koordinat dönüşümleri --------------------------
    /** Ekliptik dikdörtgen -> ekvatoral (RA/Dec). */
    private fun EclRect.equatorial(ecl: Double): Equatorial {
        val xe = x
        val ye = y * cosd(ecl) - z * sind(ecl)
        val ze = y * sind(ecl) + z * cosd(ecl)
        val ra = rev(atan2d(ye, xe))
        val dec = atan2d(ze, sqrt(xe * xe + ye * ye))
        return Equatorial(ra, dec)
    }

    private fun Sun.equatorial(ecl: Double): Equatorial =
        EclRect(x, y, z).equatorial(ecl)

    private fun Moon.equatorial(ecl: Double): Equatorial =
        EclRect(x, y, z).equatorial(ecl)

    /** Ekvatoral (RA/Dec) -> yatay (alt/az), gözlemci enlem/boylam ve zamana göre. */
    private fun toHorizontal(
        eq: Equatorial,
        latitude: Double,
        longitude: Double,
        d: Double,
        sun: Sun,
    ): Horizontal {
        // Yerel yıldız zamanı (derece)
        val gmst0 = rev(sun.meanLongitude + 180.0)         // derece
        val utHours = (d - floor(d)) * 24.0
        val lst = rev(gmst0 + utHours * 15.0 + longitude)  // derece
        val ha = rev(lst - eq.ra)                          // saat açısı (derece)

        val x = cosd(ha) * cosd(eq.dec)
        val y = sind(ha) * cosd(eq.dec)
        val z = sind(eq.dec)

        val xhor = x * sind(latitude) - z * cosd(latitude)
        val yhor = y
        val zhor = x * cosd(latitude) + z * sind(latitude)

        val azimuth = rev(atan2d(yhor, xhor) + 180.0)      // 0=Kuzey, saat yönü
        val altitude = atan2d(zhor, sqrt(xhor * xhor + yhor * yhor))
        return Horizontal(azimuth, altitude)
    }

    // ----------------------------- Tipler -----------------------------------
    private data class EclRect(val x: Double, val y: Double, val z: Double) {
        val r: Double get() = sqrt(x * x + y * y + z * z)
    }

    private data class Sun(
        val x: Double, val y: Double, val z: Double,
        val r: Double, val meanLongitude: Double,
    )

    private data class Moon(val x: Double, val y: Double, val z: Double, val r: Double)

    private data class Equatorial(val ra: Double, val dec: Double)

    private data class Horizontal(val azimuth: Double, val altitude: Double)

    private data class Elements(
        val n: Double, val i: Double, val w: Double,
        val a: Double, val e: Double, val m: Double,
    )

    // --------------------------- Derece trig --------------------------------
    private fun sind(deg: Double) = sin(deg * DEG2RAD)
    private fun cosd(deg: Double) = cos(deg * DEG2RAD)
    private fun atan2d(y: Double, x: Double) = atan2(y, x) * RAD2DEG
    private fun asind(v: Double) = asin(v.coerceIn(-1.0, 1.0)) * RAD2DEG
    private fun acosd(v: Double) = kotlin.math.acos(v.coerceIn(-1.0, 1.0)) * RAD2DEG
    private fun degOf(e: Double) = e * RAD2DEG
    private fun pow6(x: Double): Double { val x2 = x * x; return x2 * x2 * x2 }

    /** Açıyı 0..360'a indirger. */
    private fun rev(deg: Double): Double {
        var v = deg % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI
}
