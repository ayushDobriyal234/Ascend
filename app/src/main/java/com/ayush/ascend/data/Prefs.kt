package com.ayush.ascend.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.store by preferencesDataStore("ascend_prefs")

/** Protective features. Turning ON is instant; turning OFF waits 24 h. */
enum class Feature(val key: String, val title: String) {
    GATE("f_gate", "Intent gate"),
    REELS_IG("f_reels_ig", "Block Instagram Reels"),
    REELS_YT("f_reels_yt", "Block YouTube Shorts"),
    REELS_FB("f_reels_fb", "Block Facebook Reels"),
    GRAYSCALE("f_gray", "Auto grayscale"),
    DNS("f_dns", "DNS firewall"),
    ADULT("f_adult", "Block 18+ sites"),
    BATCH("f_batch", "Notification batching"),
    PREDICTOR("f_predict", "Relapse predictor");

    val prefKey get() = booleanPreferencesKey(key)
}

data class Settings(
    val features: Map<Feature, Boolean> = emptyMap(),
    val dailyGoalMin: Int = 180,
    val grayAfterMin: Int = 120,
    val bedtimeHour: Int = 22,
    val wakeHour: Int = 6,
    val dnsUpstream: String = "1.1.1.1",
    val rulesUrl: String = "",
    val batchSlots: List<String> = listOf("13:00", "18:00", "21:00"),
    val batchKeywords: List<String> = listOf("otp", "urgent"),
    val tgToken: String = "",
    val tgChat: String = "",
    val stakeLink: String = "",
    val stakeAmount: Int = 50,
    val partnerName: String = "",
    val myName: String = "Ayush",
    val pinned: Set<String> = emptySet(),
    val onboarded: Boolean = false,
    val modelWeights: String = "",
    val modelAccuracy: Double = 0.0,
    val modelSamples: Int = 0,
    val acPoints: Int = 0,
    val bonusMinutes: Int = 0,
    val chestDay: String = "",
    /** "taskId@yyyy-MM-dd" for tasks claimed today. */
    val claimed: Set<String> = emptySet(),
    /** Share cards hide app names. */
    val sharePrivate: Boolean = false,
) {
    fun on(f: Feature) = features[f] == true
}

class Prefs(private val ctx: Context, scope: CoroutineScope) {

    private object K {
        val goal = intPreferencesKey("goal")
        val grayAfter = intPreferencesKey("gray_after")
        val bed = intPreferencesKey("bed")
        val wake = intPreferencesKey("wake")
        val upstream = stringPreferencesKey("dns_up")
        val rulesUrl = stringPreferencesKey("rules_url")
        val slots = stringPreferencesKey("slots")
        val keywords = stringPreferencesKey("keywords")
        val tgToken = stringPreferencesKey("tg_token")
        val tgChat = stringPreferencesKey("tg_chat")
        val stakeLink = stringPreferencesKey("stake_link")
        val stakeAmount = intPreferencesKey("stake_amt")
        val partner = stringPreferencesKey("partner")
        val me = stringPreferencesKey("me")
        val pinned = stringSetPreferencesKey("pinned")
        val onboarded = booleanPreferencesKey("onboarded")
        val weights = stringPreferencesKey("w")
        val acc = stringPreferencesKey("acc")
        val samples = intPreferencesKey("samples")
        // rewards
        val ac = intPreferencesKey("ac_points")
        val bonus = intPreferencesKey("bonus_min")
        val chestDay = stringPreferencesKey("chest_day")
        val claimed = stringSetPreferencesKey("claimed")
        val stepDay = stringPreferencesKey("step_day")
        val stepBase = longPreferencesKey("step_base")
        val stepAccum = longPreferencesKey("step_accum")
        val stepLast = longPreferencesKey("step_last")
        val sharePrivate = booleanPreferencesKey("share_private")
        // tamper-safe clock + heartbeat
        val anchorWall = longPreferencesKey("anchor_wall")
        val anchorElapsed = longPreferencesKey("anchor_elapsed")
        val anchorBoot = intPreferencesKey("anchor_boot")
        val maxSeenWall = longPreferencesKey("max_seen")
        val heartbeat = longPreferencesKey("heartbeat")
        val heartbeatElapsed = longPreferencesKey("heartbeat_el")
    }

    val settings: StateFlow<Settings> = ctx.store.data.map { p ->
        Settings(
            features = Feature.entries.associateWith { p[it.prefKey] ?: defaultOn(it) },
            dailyGoalMin = p[K.goal] ?: 180,
            grayAfterMin = p[K.grayAfter] ?: 120,
            bedtimeHour = p[K.bed] ?: 22,
            wakeHour = p[K.wake] ?: 6,
            dnsUpstream = p[K.upstream] ?: "1.1.1.1",
            rulesUrl = p[K.rulesUrl] ?: "",
            batchSlots = (p[K.slots] ?: "13:00,18:00,21:00").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            batchKeywords = (p[K.keywords] ?: "otp,urgent").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
            tgToken = p[K.tgToken] ?: "",
            tgChat = p[K.tgChat] ?: "",
            stakeLink = p[K.stakeLink] ?: "",
            stakeAmount = p[K.stakeAmount] ?: 50,
            partnerName = p[K.partner] ?: "",
            myName = p[K.me] ?: "Ayush",
            pinned = p[K.pinned] ?: emptySet(),
            onboarded = p[K.onboarded] ?: false,
            modelWeights = p[K.weights] ?: "",
            modelAccuracy = p[K.acc]?.toDoubleOrNull() ?: 0.0,
            modelSamples = p[K.samples] ?: 0,
            acPoints = p[K.ac] ?: 0,
            bonusMinutes = p[K.bonus] ?: 0,
            chestDay = p[K.chestDay] ?: "",
            claimed = p[K.claimed] ?: emptySet(),
            sharePrivate = p[K.sharePrivate] ?: false,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Waits for the first real load from disk (the initial StateFlow value is a placeholder). */
    suspend fun current(): Settings = settings.first { it.features.isNotEmpty() }

    private fun defaultOn(f: Feature) = when (f) {
        Feature.GATE, Feature.REELS_IG, Feature.REELS_YT, Feature.PREDICTOR -> true
        else -> false
    }

    suspend fun setFeatureNow(f: Feature, on: Boolean) = ctx.store.edit { it[f.prefKey] = on }

    suspend fun update(block: (MutablePreferences, Keys) -> Unit) = ctx.store.edit { block(it, Keys) }

    /** Typed key handles exposed for [update]. */
    object Keys {
        val goal get() = K.goal
        val grayAfter get() = K.grayAfter
        val bed get() = K.bed
        val wake get() = K.wake
        val upstream get() = K.upstream
        val rulesUrl get() = K.rulesUrl
        val slots get() = K.slots
        val keywords get() = K.keywords
        val tgToken get() = K.tgToken
        val tgChat get() = K.tgChat
        val stakeLink get() = K.stakeLink
        val stakeAmount get() = K.stakeAmount
        val partner get() = K.partner
        val me get() = K.me
        val pinned get() = K.pinned
        val onboarded get() = K.onboarded
        val weights get() = K.weights
        val acc get() = K.acc
        val samples get() = K.samples
        val ac get() = K.ac
        val bonus get() = K.bonus
        val chestDay get() = K.chestDay
        val claimed get() = K.claimed
        val stepDay get() = K.stepDay
        val stepBase get() = K.stepBase
        val stepAccum get() = K.stepAccum
        val stepLast get() = K.stepLast
        val sharePrivate get() = K.sharePrivate
    }

    // ---- raw access for TrustedClock / heartbeat (kept out of Settings to avoid churn) ----
    suspend fun raw(): Preferences = ctx.store.data.first()

    suspend fun saveAnchor(wall: Long, elapsed: Long, boot: Int) = ctx.store.edit {
        it[K.anchorWall] = wall; it[K.anchorElapsed] = elapsed; it[K.anchorBoot] = boot
        it[K.maxSeenWall] = maxOf(it[K.maxSeenWall] ?: 0L, wall)
    }
    suspend fun anchor(): Triple<Long, Long, Int>? = raw().let { p ->
        val w = p[K.anchorWall] ?: return null
        Triple(w, p[K.anchorElapsed] ?: 0L, p[K.anchorBoot] ?: -1)
    }
    suspend fun maxSeenWall(): Long = raw()[K.maxSeenWall] ?: 0L
    suspend fun bumpMaxSeen(wall: Long) = ctx.store.edit { it[K.maxSeenWall] = maxOf(it[K.maxSeenWall] ?: 0L, wall) }

    suspend fun heartbeat(wall: Long, elapsed: Long) = ctx.store.edit { it[K.heartbeat] = wall; it[K.heartbeatElapsed] = elapsed }
    suspend fun lastHeartbeat(): Pair<Long, Long>? = raw().let { p ->
        val w = p[K.heartbeat] ?: return null; w to (p[K.heartbeatElapsed] ?: 0L)
    }
}
