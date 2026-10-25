package com.ayush.ascend.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Current temperature for the launcher, from Open-Meteo (free, no key). Coarse location, rounded to ~1 km. */
object Weather {
    /** [code] is the WMO weather code. */
    data class Now(val tempC: Int, val code: Int)

    private const val MAX_AGE_MS = 30 * 60_000L
    @Volatile private var cache: Pair<Long, Now>? = null

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun now(ctx: Context): Now? = withContext(Dispatchers.IO) {
        cache?.let { (at, n) -> if (System.currentTimeMillis() - at < MAX_AGE_MS) return@withContext n }
        val loc = location(ctx) ?: return@withContext cache?.second
        runCatching {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
                .format(Locale.US, loc.latitude, loc.longitude))
            val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
            val cur = conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("current")
            Now(cur.getDouble("temperature_2m").roundToInt(), cur.getInt("weather_code"))
        }.getOrNull()?.also { cache = System.currentTimeMillis() to it } ?: cache?.second
    }

    @Suppress("MissingPermission")  // checked by hasPermission
    private suspend fun location(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { c ->
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { c.resume(it) }
            }
        }
    }
}
