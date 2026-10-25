package com.ayush.ascend.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.ayush.ascend.core.Grayscale
import com.ayush.ascend.core.Mood
import com.ayush.ascend.core.Partner
import com.ayush.ascend.core.Sessions
import com.ayush.ascend.core.Wallet
import com.ayush.ascend.core.graph
import com.ayush.ascend.data.AppRule
import com.ayush.ascend.data.Feature
import com.ayush.ascend.data.OpenEvent
import com.ayush.ascend.ui.gate.GateActivity
import com.ayush.ascend.ui.overlay.LimitReachedOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches which app is in the foreground and applies YOUR rules:
 * daily limits, the intent gate (#2), session expiry, Reels/Shorts blocking (#3),
 * grayscale (#5) and unlock sampling for the predictor (#9).
 * You can switch it off any time in Settings → Accessibility.
 */
class GuardService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val g by lazy { graph }
    private lateinit var overlay: OverlayHost

    @Volatile private var rules: Map<String, AppRule> = emptyMap()
    @Volatile private var fgPkg: String? = null
    @Volatile private var lastFgBeforeLock: String? = null
    private var imePkg: String? = null
    private var lastGateLaunch = 0L
    private var lastReelCheck = 0L
    private var lastReelToast = 0L
    private var lastReelCount = 0L
    private var inReels = false
    private val stakeStarted = HashMap<String, Long>()
    private var visitPkg: String? = null
    private var visitStart = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { lastFgBeforeLock = fgPkg; overlay.hide(); trackVisit(null) }
                Intent.ACTION_USER_PRESENT -> {
                    trackVisit(fgPkg)
                    scope.launch(Dispatchers.Default) { runCatching { g.predictor.onUnlock(applicationContext, lastFgBeforeLock) } }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        overlay = OverlayHost(this)
        imePkg = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        scope.launch { g.db.rules().observeAll().collectLatest { list -> rules = list.associateBy { it.pkg } } }
        scope.launch { sessionTick() }
        scope.launch { minuteTick() }
    }

    override fun onDestroy() {
        running = false
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (::overlay.isInitialized) overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == packageName || pkg == imePkg || pkg == "com.android.systemui") return
                if (pkg != fgPkg && overlay.isShowing) overlay.hide()
                fgPkg = pkg
                trackVisit(pkg)
                onForeground(pkg)
                checkReels(pkg, e.className?.toString())
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == fgPkg) checkReels(pkg, null)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == fgPkg) checkReels(pkg, null, scrolled = true)
        }
    }

    // ---------------- limits + gate ----------------

    /** Times each stay in a distracting app; leaving one (switch, home, session kick-out, screen off) may ask for a mood. */
    private fun trackVisit(pkg: String?) {
        val prev = visitPkg
        if (prev == pkg && pkg != null) return
        val now = SystemClock.elapsedRealtime()
        if (prev != null) {
            val min = ((now - visitStart) / 60_000).toInt()
            scope.launch { runCatching { Mood.maybeAsk(this@GuardService, prev, min) } }
        }
        visitPkg = pkg?.takeIf { rules[it] != null || it in com.ayush.ascend.core.KNOWN_DISTRACTING }
        visitStart = now
    }

    private fun onForeground(pkg: String) {
        val rule = rules[pkg]
        val distracting = rule != null || pkg in com.ayush.ascend.core.KNOWN_DISTRACTING
        if (!distracting) return
        scope.launch {
            val s = g.prefs.current()
            if (rule?.limitMinutes != null && !Sessions.active("stake:$pkg")) {
                val used = withContext(Dispatchers.Default) { g.usage.today().minutes(pkg) }
                if (used >= rule.limitMinutes) { showLimitReached(rule, used); return@launch }
            }
            val gateNeeded = s.on(Feature.GATE) &&
                (rule?.gated == true || (s.on(Feature.PREDICTOR) && g.predictor.highRisk()))
            if (gateNeeded && !Sessions.active(pkg) && !Sessions.active("stake:$pkg")) launchGate(pkg, rule?.label ?: g.usage.label(pkg))
        }
    }

    private fun launchGate(pkg: String, label: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGateLaunch < 1500) return
        lastGateLaunch = now
        startActivity(GateActivity.intent(this, pkg, label, g.predictor.highRisk()))
    }

    private fun showLimitReached(rule: AppRule, used: Int) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        scope.launch {
            val s = g.prefs.current()
            val started = stakeStarted[rule.pkg]
            val canConfirm = started != null && SystemClock.elapsedRealtime() - started in 20_000..30 * 60_000L
            overlay.show {
                LimitReachedOverlay(
                    appLabel = rule.label,
                    usedMinutes = used,
                    limitMinutes = rule.limitMinutes ?: 0,
                    stakeAmount = s.stakeAmount.takeIf { s.stakeLink.isNotBlank() },
                    canConfirmPaid = canConfirm,
                    bonusMinutes = s.bonusMinutes,
                    onUseBonus = {
                        overlay.hide()
                        scope.launch {
                            val m = g.wallet.takeBonus(Wallet.BONUS_CHUNK)
                            if (m == 0) return@launch
                            // same session key as a paid unlock: every limit check already honours it
                            Sessions.grant("stake:${rule.pkg}", m)
                            g.partner.send("🎁 {me} used $m bonus minutes on ${rule.label}.", Partner.Event.STAKE)
                            g.usage.launch(rule.pkg)
                        }
                    },
                    onClose = { overlay.hide() },
                    onPay = {
                        stakeStarted[rule.pkg] = SystemClock.elapsedRealtime()
                        overlay.hide()
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.stakeLink)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    onConfirmPaid = {
                        overlay.hide()
                        stakeStarted.remove(rule.pkg)
                        Sessions.grant("stake:${rule.pkg}", 15)
                        scope.launch {
                            g.db.opens().insert(OpenEvent(pkg = rule.pkg, ts = System.currentTimeMillis(), type = "stake", minutes = 15))
                            g.partner.send("💸 {me} paid ₹${s.stakeAmount} to unlock ${rule.label} for 15 min. Check the payment page to confirm.", Partner.Event.STAKE)
                            g.usage.launch(rule.pkg)
                        }
                    },
                )
            }
        }
    }

    /** Every second: end expired sessions, catch limits hit mid-session. */
    private suspend fun sessionTick() {
        var n = 0
        while (scope.isActive) {
            delay(1_000)
            val pkg = fgPkg ?: continue
            if (Sessions.expired(pkg)) {
                Sessions.clear(pkg)
                performGlobalAction(GLOBAL_ACTION_HOME)
                toast("Session over for ${g.usage.label(pkg)}")
                continue
            }
            if (Sessions.expired("stake:$pkg")) { Sessions.clear("stake:$pkg"); performGlobalAction(GLOBAL_ACTION_HOME); continue }
            if (++n % 5 == 0) {
                val rule = rules[pkg] ?: continue
                val limit = rule.limitMinutes ?: continue
                if (Sessions.active("stake:$pkg")) continue
                val used = withContext(Dispatchers.Default) { g.usage.today().minutes(pkg) }
                if (used >= limit) showLimitReached(rule, used)
            }
        }
    }

    /** Every minute: pending 24 h changes, grayscale, keep DNS firewall up, clock anchor. */
    private suspend fun minuteTick() {
        while (scope.isActive) {
            runCatching {
                g.policy.applyDue()
                g.clock.tick()
                g.wallet.sampleSteps()
                val s = g.prefs.current()
                val total = withContext(Dispatchers.Default) { g.usage.today(30_000).totalMinutes }
                Grayscale.enforce(this, s, total)
                if (s.on(Feature.DNS) && !DnsVpnService.running && VpnService.prepare(this) == null) DnsVpnService.start(this)
                if (!s.on(Feature.DNS) && DnsVpnService.running) DnsVpnService.stop(this)
            }
            delay(60_000)
        }
    }

    // ---------------- #3 Reels / Shorts ----------------

    private fun checkReels(pkg: String, className: String?, scrolled: Boolean = false) {
        val rule = g.reels.rules[pkg] ?: return
        val s = g.prefs.settings.value
        if (!s.on(rule.feature)) return
        val now = SystemClock.elapsedRealtime()
        if (className == null && now - lastReelCheck < 350) return
        lastReelCheck = now

        val hit = (className != null && rule.activityHints.any { className.contains(it) }) ||
            rootInActiveWindow?.let { root -> matches(root, rule.viewIds, rule.selectedDescs) } == true
        if (!hit) { inReels = false; return }
        if (s.reelsToday < s.reelLimit) {
            // ponytail: one swipe = one reel (scroll bursts debounced to 800 ms); a fast flicker can miscount by one
            val entered = !inReels
            inReels = true
            if (entered || (scrolled && now - lastReelCount > 800)) {
                lastReelCount = now
                scope.launch {
                    val n = g.prefs.countReel()
                    if (n == s.reelLimit) toast("That was your last reel for today")
                    else if (n == s.reelLimit - 5) toast("5 reels left today")
                }
            }
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (now - lastReelToast > 4_000) {
            lastReelToast = now
            val why = if (s.reelLimit > 0) "${s.reelLimit} for today used up" else "blocked"
            toast(if (pkg.contains("youtube")) "Shorts $why — search & videos still work" else "Reels $why — DMs still work")
        }
    }

    private fun matches(root: AccessibilityNodeInfo, ids: List<String>, descs: List<String>): Boolean {
        for (id in ids) {
            if (root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }) return true
        }
        if (descs.isEmpty()) return false
        // bounded BFS for a selected tab whose description matches (e.g. "Reels")
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val n = queue.removeFirst(); visited++
            if (n.isSelected) {
                val d = (n.contentDescription ?: n.text)?.toString()?.lowercase()
                if (d != null && descs.any { d == it || d.startsWith("$it,") || d.startsWith("$it ") }) return true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        @Volatile var running = false
            private set
        @Volatile private var instance: GuardService? = null

        /** Double-tap-to-lock for Ascend Home; false if the accessibility service isn't on. */
        fun lockScreen(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains("${ctx.packageName}/") && list.contains("GuardService")
        }
    }
}
