package com.ayush.ascend.core

import android.content.Context
import com.ayush.ascend.data.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * #3 Surgical Reels/Shorts blocking.
 *
 * View IDs change when Instagram/YouTube update, so rules are data, not code:
 * bundled `assets/reels_rules.json`, optionally overridden by a JSON you host
 * (e.g. a raw GitHub URL) and set in Shield → Reels rules URL.
 */
data class ReelRule(
    val pkg: String,
    val feature: Feature,
    /** Block if any of these view ids is on screen. */
    val viewIds: List<String>,
    /** Block if a node with one of these content descriptions is *selected* (i.e. the Reels tab is active). */
    val selectedDescs: List<String>,
    /** Block if the activity class name contains one of these. */
    val activityHints: List<String>,
)

class ReelsRules(private val ctx: Context) {

    private val overrideFile get() = File(ctx.filesDir, "reels_rules.json")
    @Volatile var version: Int = 0
        private set
    @Volatile var rules: Map<String, ReelRule> = load()
        private set

    private fun load(): Map<String, ReelRule> {
        val text = runCatching { overrideFile.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: ctx.assets.open("reels_rules.json").bufferedReader().use { it.readText() }
        return parse(text) ?: parse(ctx.assets.open("reels_rules.json").bufferedReader().use { it.readText() })!!
    }

    private fun parse(text: String): Map<String, ReelRule>? = runCatching {
        val root = JSONObject(text)
        version = root.optInt("version", 0)
        val apps = root.getJSONObject("apps")
        apps.keys().asSequence().associateWith { pkg ->
            val o = apps.getJSONObject(pkg)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            ReelRule(
                pkg = pkg,
                feature = Feature.valueOf(o.getString("feature")),
                viewIds = list("viewIds"),
                selectedDescs = list("selectedDescs").map { it.lowercase() },
                activityHints = list("activityHints"),
            )
        }
    }.getOrNull()

    /** Downloads newer rules. Returns the new version or null. */
    suspend fun refresh(url: String): Int? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val parsed = parse(text) ?: return@runCatching null
            overrideFile.writeText(text)
            rules = parsed
            version
        }.getOrNull()
    }
}
