package com.ayush.ascend.core

import com.ayush.ascend.data.AppRule
import com.ayush.ascend.data.BatchApp
import com.ayush.ascend.data.BlockedDomain
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.PendingChange
import com.ayush.ascend.data.PendingKinds
import kotlin.math.ceil

const val MIN_LIMIT_MINUTES = 15
const val LOCK_DAYS = 45
const val LOOSEN_DELAY_MS = 24 * 60 * 60 * 1000L
private const val DAY_MS = 24 * 60 * 60 * 1000L

sealed interface EditResult {
    data object Applied : EditResult
    data class Scheduled(val applyAt: Long) : EditResult
    data class Locked(val daysLeft: Int, val unlockAt: Long) : EditResult
    data object NeedsNetwork : EditResult
    data class Invalid(val message: String) : EditResult
}

/**
 * All the "can I change this?" rules live here.
 *
 * Limits: min 15 min. Setting a limit locks it for 45 days. During the lock you can make it
 * STRICTER any time, but never looser or removed. After 45 days (verified with network time)
 * you can change it, which starts a fresh 45-day lock.
 *
 * Everything else that loosens protection (gate off, feature off, unblock domain…) waits 24 h.
 */
class Policy(private val g: Graph) {

    private val db get() = g.db

    fun daysLeft(rule: AppRule, now: Long): Int =
        if (rule.limitMinutes == null || now >= rule.lockedUntil) 0
        else ceil((rule.lockedUntil - now) / DAY_MS.toDouble()).toInt()

    suspend fun addRule(pkg: String, label: String, limit: Int?, gated: Boolean): EditResult {
        if (limit != null && limit < MIN_LIMIT_MINUTES) return EditResult.Invalid("Minimum limit is $MIN_LIMIT_MINUTES minutes")
        if (limit == null && !gated) return EditResult.Invalid("Set a limit or turn on the gate")
        val existing = db.rules().get(pkg)
        if (existing != null) return editLimit(existing, limit).also { if (gated) setGate(pkg, true) }
        val now = g.clock.now()
        db.rules().upsert(
            AppRule(pkg, label, limit, if (limit != null) now + LOCK_DAYS * DAY_MS else 0L, gated, now)
        )
        if (limit != null) g.partner.send("🔒 {me} locked $label to ${limit} min/day for $LOCK_DAYS days.", Partner.Event.LIMIT_CHANGE)
        return EditResult.Applied
    }

    suspend fun editLimit(rule: AppRule, newLimit: Int?): EditResult {
        if (newLimit != null && newLimit < MIN_LIMIT_MINUTES) return EditResult.Invalid("Minimum limit is $MIN_LIMIT_MINUTES minutes")
        val old = rule.limitMinutes
        val looser = old != null && (newLimit == null || newLimit > old)
        val now = g.clock.now()

        if (!looser) {
            val lockedUntil = if (old == null && newLimit != null) now + LOCK_DAYS * DAY_MS else rule.lockedUntil
            db.rules().upsert(rule.copy(limitMinutes = newLimit, lockedUntil = lockedUntil))
            return EditResult.Applied
        }
        if (now < rule.lockedUntil) return EditResult.Locked(daysLeft(rule, now), rule.lockedUntil)
        // Lock looks expired locally — confirm with network time so changing the phone date doesn't work.
        val verified = g.clock.verifiedNow() ?: return EditResult.NeedsNetwork
        if (verified < rule.lockedUntil) return EditResult.Locked(daysLeft(rule, verified), rule.lockedUntil)

        if (newLimit == null && !rule.gated) db.rules().delete(rule.pkg)
        else db.rules().upsert(rule.copy(limitMinutes = newLimit, lockedUntil = if (newLimit != null) verified + LOCK_DAYS * DAY_MS else 0L))
        g.partner.send(
            "🔓 {me} changed ${rule.label}: ${old}m → ${newLimit?.let { "${it}m" } ?: "no limit"} (45-day lock had expired).",
            Partner.Event.LIMIT_CHANGE,
        )
        return EditResult.Applied
    }

    suspend fun setGate(pkg: String, on: Boolean): EditResult {
        val rule = db.rules().get(pkg) ?: return EditResult.Invalid("Unknown app")
        if (on) { db.rules().upsert(rule.copy(gated = true)); cancel(PendingKinds.GATE_OFF, pkg); return EditResult.Applied }
        return schedule(PendingKinds.GATE_OFF, pkg)
    }

    suspend fun setFeature(f: Feature, on: Boolean): EditResult {
        if (on) { g.prefs.setFeatureNow(f, true); cancel(PendingKinds.FEATURE_OFF, f.key); return EditResult.Applied }
        if (!g.prefs.current().on(f)) return EditResult.Applied
        return schedule(PendingKinds.FEATURE_OFF, f.key)
    }

    suspend fun addDomain(d: String) {
        val clean = d.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
        if (clean.contains('.')) db.domains().add(BlockedDomain(clean, System.currentTimeMillis()))
        cancel(PendingKinds.DOMAIN_REMOVE, clean)
    }

    suspend fun removeDomain(d: String) = schedule(PendingKinds.DOMAIN_REMOVE, d)

    suspend fun addBatchApp(pkg: String, label: String) {
        db.notifs().addApp(BatchApp(pkg, label)); cancel(PendingKinds.BATCH_APP_REMOVE, pkg)
    }

    suspend fun removeBatchApp(pkg: String) = schedule(PendingKinds.BATCH_APP_REMOVE, pkg)

    suspend fun cancelPending(id: Long) = db.pending().delete(id)

    private suspend fun schedule(kind: String, target: String): EditResult {
        db.pending().find(kind, target)?.let { return EditResult.Scheduled(it.applyAt) }
        val now = g.clock.now()
        val at = now + LOOSEN_DELAY_MS
        db.pending().insert(PendingChange(kind = kind, target = target, applyAt = at, createdAt = now))
        return EditResult.Scheduled(at)
    }

    private suspend fun cancel(kind: String, target: String) {
        db.pending().find(kind, target)?.let { db.pending().delete(it.id) }
    }

    /** Called from the guard tick + app resume. */
    suspend fun applyDue() {
        val due = db.pending().due(g.clock.now())
        for (c in due) {
            when (c.kind) {
                PendingKinds.GATE_OFF -> db.rules().get(c.target)?.let {
                    if (it.limitMinutes == null) db.rules().delete(it.pkg) else db.rules().upsert(it.copy(gated = false))
                }
                PendingKinds.FEATURE_OFF -> Feature.entries.firstOrNull { it.key == c.target }?.let { g.prefs.setFeatureNow(it, false) }
                PendingKinds.DOMAIN_REMOVE -> db.domains().remove(c.target)
                PendingKinds.BATCH_APP_REMOVE -> db.notifs().removeApp(c.target)
            }
            db.pending().delete(c.id)
        }
    }
}
