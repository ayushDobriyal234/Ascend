package com.ayush.ascend.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * #10 Accountability partner — pings a friend through a Telegram bot.
 * Setup: create a bot with @BotFather, have your friend message it once,
 * get their chat id from https://api.telegram.org/bot<TOKEN>/getUpdates.
 */
class Partner(private val g: Graph) {

    enum class Event { STAKE, TAMPER, LIMIT_CHANGE, TEST }

    suspend fun isConfigured(): Boolean = g.prefs.current().let { it.tgToken.isNotBlank() && it.tgChat.isNotBlank() }

    suspend fun send(text: String, event: Event): Boolean = withContext(Dispatchers.IO) {
        val s = g.prefs.current()
        if (s.tgToken.isBlank() || s.tgChat.isBlank()) return@withContext false
        val msg = "Ascend · " + text.replace("{me}", s.myName.ifBlank { "Your friend" })
        runCatching {
            val c = URL("https://api.telegram.org/bot${s.tgToken}/sendMessage").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 8_000; c.readTimeout = 8_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "chat_id=" + URLEncoder.encode(s.tgChat, "UTF-8") + "&text=" + URLEncoder.encode(msg, "UTF-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        }.getOrDefault(false)
    }
}
