package com.carlink.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Forwards Android location data as NMEA sentences to the CPC200-CCPA adapter.
 *
 * The adapter repackages NMEA data for the phone: iAP2 LocationInformation on iOS,
 * the Android Auto location channel (openSDK) on Android.
 *
 * Generates $GPGGA (fix data) and $GPRMC (recommended minimum) sentences at ~1Hz.
 *
 * Platform consumption (important — it shapes what "good output" means here):
 *  - iOS: receives the full NMEA message and compares our reported accuracy against
 *    its own GPS fusion estimate. Vehicle GPS is used only for non-critical consumers
 *    (third-party apps, passive location updates) UNLESS our reported accuracy beats
 *    iOS's internal estimate — only then is it accepted for Maps navigation and the
 *    phone's own location. So honest HDOP / quality matter: understate accuracy and
 *    iOS ignores us; overstate and we poison iOS's fusion output.
 *  - Android Auto: a known bug in the openSDK binary on the CPC200-CCPA adapter
 *    strips the NMEA accuracy fields and downgrades the delivered position to a fixed
 *    ~1 km radius that bounces randomly inside that circle. Android accepts the
 *    degraded position as authoritative for ALL location consumers (including nav).
 *    There is no phone-side mitigation; the quality of fields we emit here does not
 *    change what Android Auto ultimately hands to the system location provider.
 *
 * Firmware buffer limit: adapter NMEA payload is documented as < 1024 bytes. NOT
 * enforced here — a GPGGA+GPRMC pair is ~200 bytes, well under the limit. If more
 * sentence types are added (e.g., $GPGSV), add a length guard in formatNmea().
 */
class GnssForwarder(
    private val context: Context,
    private val sendGnssData: (String) -> Boolean,
    private val logCallback: (String) -> Unit,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null
    private var handlerThread: HandlerThread? = null

    // @Volatile is visibility-only; start()/stop() are serialized by the single caller
    // (CarlinkManager state transitions), so there's no read-modify-write race to guard.
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            log("Location permission not granted - GPS forwarding disabled")
            return
        }

        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val nmea = formatNmea(location)
                    val sent = sendGnssData(nmea)
                    val acc = if (location.hasAccuracy()) "%.1fm".format(Locale.US, location.accuracy) else "n/a"
                    log(
                        "[GNSS] lat=%.5f lon=%.5f alt=%.0f spd=%.1f acc=$acc sent=$sent".format(
                            Locale.US,
                            location.latitude,
                            location.longitude,
                            location.altitude,
                            location.speed,
                        ),
                    )
                }

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {
                    log("[GNSS] Provider disabled: $provider")
                }

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) {}
            }

        try {
            // Use a dedicated background thread for location callbacks.
            // sendGnssData() does a blocking USB bulkTransfer — must never run on main thread.
            val thread = HandlerThread("GNSS-Forwarder").apply { start() }
            handlerThread = thread

            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                thread.looper,
            )
            locationListener = listener
            isRunning = true
            log("[GNSS] Started GPS forwarding (1Hz, GPS_PROVIDER, background thread)")
        } catch (e: Exception) {
            log("[GNSS] Failed to start location updates: ${e.message}")
            handlerThread?.quitSafely()
            handlerThread = null
        }
    }

    fun stop() {
        if (!isRunning) return

        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                log("[GNSS] Error removing location updates: ${e.message}")
            }
        }
        locationListener = null
        handlerThread?.quitSafely()
        handlerThread = null
        isRunning = false
        log("[GNSS] Stopped GPS forwarding")
    }

    private fun formatNmea(location: Location): String {
        val gpgga = formatGpgga(location)
        val gprmc = formatGprmc(location)
        return gpgga + gprmc
    }

    /**
     * Format $GPGGA sentence (GPS Fix Data).
     *
     * $GPGGA,HHMMSS.SS,DDMM.MMMM,N,DDDMM.MMMM,W,Q,NN,HDOP,ALT,M,GEOID,M,,*XX\r\n
     *
     * Derived fields: HDOP (from Location.getAccuracy() via a rough UERE≈5m heuristic)
     * and satellite count (from Location.extras["satellites"] or estimated from accuracy
     * buckets). Emitting honest HDOP matters for the iOS fusion comparison described in
     * the class KDoc.
     *
     * Hardcoded fields (known approximations, kept simple because Android Auto strips
     * accuracy anyway and iOS only needs the HDOP to be directionally correct):
     *  - Quality indicator: always "1" (GPS fix). No degraded/DGPS path.
     *  - Geoid separation: "0.0,M". Android's Location.altitude is WGS84-referenced, so
     *    labelling it as MSL is technically wrong by the local geoid undulation (~±25m),
     *    but neither consumer uses the altitude field for positioning.
     *  - Fractional seconds: always ".00" (see formatUtcTime).
     *  - Satellite count: clamped to 24 (GGA field supports 0-99, but the Location
     *    extras bundle rarely reports more and iOS doesn't differentiate above ~12).
     */
    private fun formatGpgga(location: Location): String {
        val time = formatUtcTime(location.time)
        val (lat, latDir) = toNmeaLatitude(location.latitude)
        val (lon, lonDir) = toNmeaLongitude(location.longitude)
        val alt = "%.1f".format(Locale.US, location.altitude)

        // Derive HDOP from accuracy: accuracy ≈ HDOP × UERE, with UERE ≈ 5m (rough
        // civilian-GPS value — modern multi-constellation receivers run closer to 2-3m,
        // so this slightly over-reports HDOP). Clamp to 0.5-25.0 (valid NMEA range).
        val hdop =
            if (location.hasAccuracy()) {
                "%.1f".format(Locale.US, min(25.0, max(0.5, location.accuracy / 5.0)))
            } else {
                "1.0"
            }

        // Use real satellite count from extras if available, otherwise estimate from accuracy
        val satellites = location.extras?.getInt("satellites", 0) ?: 0
        val satCount =
            if (satellites > 0) {
                "%02d".format(Locale.US, min(satellites, 24))
            } else if (location.hasAccuracy()) {
                // Rough estimate: better accuracy → more satellites
                val est =
                    when {
                        location.accuracy < 3f -> 12
                        location.accuracy < 10f -> 8
                        location.accuracy < 30f -> 5
                        else -> 4
                    }
                "%02d".format(Locale.US, est)
            } else {
                "08"
            }

        val body = "GPGGA,$time,$lat,$latDir,$lon,$lonDir,1,$satCount,$hdop,$alt,M,0.0,M,,"
        val checksum = computeNmeaChecksum(body)
        return "\$$body*$checksum\r\n"
    }

    /**
     * Format $GPRMC sentence (Recommended Minimum).
     *
     * $GPRMC,HHMMSS.SS,A,DDMM.MMMM,N,DDDMM.MMMM,W,SPEED,COURSE,DDMMYY,,,A*XX\r\n
     *
     * Hardcoded fields:
     *  - Status: always "A" (active). No "V" (void) path when accuracy is poor — iOS
     *    ignores the vehicle fix anyway when its own fusion is more accurate.
     *  - Magnetic variation: empty (",,"). NMEA allows this; iOS/Android Auto don't use it.
     *  - Course when stationary: "0.0" instead of empty field. Semantically implies
     *    "heading north" when stopped; NMEA-valid but not strictly correct. Kept because
     *    neither consumer derives heading from a stationary fix.
     */
    private fun formatGprmc(location: Location): String {
        val time = formatUtcTime(location.time)
        val date = formatUtcDate(location.time)
        val (lat, latDir) = toNmeaLatitude(location.latitude)
        val (lon, lonDir) = toNmeaLongitude(location.longitude)
        val speedKnots = "%.1f".format(Locale.US, location.speed * 1.94384)
        val course =
            if (location.hasBearing()) {
                "%.1f".format(Locale.US, location.bearing)
            } else {
                "0.0"
            }

        val body = "GPRMC,$time,A,$lat,$latDir,$lon,$lonDir,$speedKnots,$course,$date,,,A"
        val checksum = computeNmeaChecksum(body)
        return "\$$body*$checksum\r\n"
    }

    /**
     * NMEA checksum: XOR of all characters between $ and * (exclusive).
     *
     * Uses c.code (UTF-16 code unit), which matches the byte value only for ASCII —
     * safe here because every formatter in this file emits ASCII-only output. If a
     * future sentence includes non-ASCII (e.g., road names), switch to .toByte().toInt().
     */
    private fun computeNmeaChecksum(body: String): String {
        var checksum = 0
        for (c in body) {
            checksum = checksum xor c.code
        }
        return "%02X".format(checksum)
    }

    /**
     * Convert decimal degrees latitude to NMEA format DDMM.MMMM,N/S.
     */
    private fun toNmeaLatitude(lat: Double): Pair<String, String> {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        val formatted = "%02d%07.4f".format(Locale.US, degrees, minutes)
        val dir = if (lat >= 0) "N" else "S"
        return Pair(formatted, dir)
    }

    /**
     * Convert decimal degrees longitude to NMEA format DDDMM.MMMM,E/W.
     */
    private fun toNmeaLongitude(lon: Double): Pair<String, String> {
        val absLon = Math.abs(lon)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60.0
        val formatted = "%03d%07.4f".format(Locale.US, degrees, minutes)
        val dir = if (lon >= 0) "E" else "W"
        return Pair(formatted, dir)
    }

    /** Format UTC time as HHMMSS.SS — fractional seconds are always ".00" (not derived
     *  from Calendar.MILLISECOND). NMEA-valid; precision loss is ignored by both iOS and
     *  Android Auto consumers at 1 Hz update rate. */
    private fun formatUtcTime(timeMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timeMs
        return "%02d%02d%02d.00".format(
            Locale.US,
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    }

    /** Format UTC date as DDMMYY */
    private fun formatUtcDate(timeMs: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = timeMs
        return "%02d%02d%02d".format(
            Locale.US,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100,
        )
    }

    private fun log(message: String) {
        logCallback(message)
    }
}
