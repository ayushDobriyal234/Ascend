package com.ayush.ascend.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.ayush.ascend.data.Settings as AppSettings
import java.time.LocalTime

/**
 * #5 Auto grayscale via the system "colour correction → monochromacy" setting.
 * Needs a one-time grant over adb:
 *   adb shell pm grant com.ayush.ascend android.permission.WRITE_SECURE_SETTINGS
 */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0

    /** Persisted so we only ever undo grayscale that *we* switched on. */
    private fun appliedByUs(ctx: Context) = ctx.getSharedPreferences("gray", Context.MODE_PRIVATE).getBoolean("ours", false)
    private fun setAppliedByUs(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences("gray", Context.MODE_PRIVATE).edit().putBoolean("ours", v).apply()

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(ctx: Context) = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, ENABLED, 0) == 1 &&
            Settings.Secure.getInt(ctx.contentResolver, MODE, -1) == MONOCHROMACY
    }.getOrDefault(false)

    fun set(ctx: Context, on: Boolean): Boolean = runCatching {
        if (!canWrite(ctx)) return false
        if (on) Settings.Secure.putInt(ctx.contentResolver, MODE, MONOCHROMACY)
        Settings.Secure.putInt(ctx.contentResolver, ENABLED, if (on) 1 else 0)
        setAppliedByUs(ctx, on)
        true
    }.getOrDefault(false)

    fun inBedtime(s: AppSettings, now: LocalTime = LocalTime.now()): Boolean {
        val h = now.hour
        return if (s.bedtimeHour > s.wakeHour) h >= s.bedtimeHour || h < s.wakeHour
        else h >= s.bedtimeHour && h < s.wakeHour
    }

    fun shouldBeGray(s: AppSettings, todayMinutes: Int) = todayMinutes >= s.grayAfterMin || inBedtime(s)

    /** Idempotent enforcement — called every minute by the guard. */
    fun enforce(ctx: Context, s: AppSettings, todayMinutes: Int) {
        val want = s.on(com.ayush.ascend.data.Feature.GRAYSCALE) && shouldBeGray(s, todayMinutes)
        if (want && !isOn(ctx)) set(ctx, true)
        else if (!want && appliedByUs(ctx) && isOn(ctx)) set(ctx, false)
    }
}
