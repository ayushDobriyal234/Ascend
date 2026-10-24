package com.ayush.ascend.core

import android.app.Application
import android.content.Context
import com.ayush.ascend.AscendApp
import com.ayush.ascend.data.AppDatabase
import com.ayush.ascend.data.Prefs
import com.ayush.ascend.data.UsageRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny manual DI container — one per process. */
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.get(app) }
    val prefs = Prefs(app, scope)
    val usage = UsageRepo(app)
    val clock = TrustedClock(app, prefs)
    val partner = Partner(this)
    val policy = Policy(this)
    val friction = Friction(this)
    val reels = ReelsRules(app)
    val predictor = Predictor(this)
    val wallet = Wallet(this)
}

val Context.graph: Graph get() = (applicationContext as AscendApp).graph

/** Well-known infinite-feed apps; always treated as "distracting" for the predictor. */
val KNOWN_DISTRACTING = setOf(
    "com.instagram.android", "com.google.android.youtube", "com.facebook.katana", "com.facebook.lite",
    "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage", "in.mohalla.sharechat",
    "in.mohalla.video", "com.eterno.shortvideos", "com.zhiliaoapp.musically", "com.netflix.mediaclient",
    "com.pinterest", "com.linkedin.android", "com.instagram.barcelona",
)
