package com.ayush.ascend.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ayush.ascend.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Clock you can't cheat by changing the phone's date.
 *
 * - Anchors to network time (HTTPS `Date` header) + elapsedRealtime, which can't be changed by the user.
 * - Never goes backwards (remembers the max time ever seen).
 * - Anything that *loosens* a rule must call [verifiedNow], which requires a fresh network sync.
 */
class TrustedClock(private val ctx: Context, private val prefs: Prefs) {

    private fun bootCount() = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    suspend fun now(): Long {
        val sys = System.currentTimeMillis()
        val a = prefs.anchor()
        val t = if (a != null && a.third == bootCount()) a.first + (SystemClock.elapsedRealtime() - a.second)
        else maxOf(sys, prefs.maxSeenWall())
        return t
    }

    /** True if the device clock differs from trusted time by > 10 min on this boot. */
    suspend fun isTampered(): Boolean {
        val a = prefs.anchor() ?: return false
        if (a.third != bootCount()) return false
        val trusted = a.first + (SystemClock.elapsedRealtime() - a.second)
        return abs(System.currentTimeMillis() - trusted) > 10 * 60_000
    }

    /** Fetches network time. Returns null when offline. */
    suspend fun verifiedNow(): Long? = withContext(Dispatchers.IO) {
        val net = fetchNetworkTime() ?: return@withContext null
        val elapsedAfter = SystemClock.elapsedRealtime()
        prefs.saveAnchor(net, elapsedAfter, bootCount())
        net
    }

    suspend fun tick() = prefs.bumpMaxSeen(now())

    private fun fetchNetworkTime(): Long? {
        for (host in listOf("https://www.google.com", "https://www.cloudflare.com")) {
            runCatching {
                val c = URL(host).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 5_000; c.readTimeout = 5_000
                c.useCaches = false
                c.connect()
                val d = c.date
                c.disconnect()
                if (d > 0) return d
            }
        }
        return null
    }
}
