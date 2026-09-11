package com.twks.wifi

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LogEntry(
    val date: String,
    val ip: String,
    val ua: String,
    val referer: String
)

object IpLogger {
    var token: String? = null
    var lastError: String = ""
    var lastRaw: String = ""

    fun create(targetUrl: String): String? {
        return try {
            val conn = URL("https://webhook.site/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
            body.put("default_status", 302)
            body.put("default_content", "")
            body.put("default_content_type", "text/html")
            val hdrs = JSONObject()
            hdrs.put("Location", targetUrl)
            body.put("default_headers", hdrs)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.readText() ?: ""
            lastRaw = "HTTP $code :: ${resp.take(200)}"
            if (code !in 200..299) {
                lastError = lastRaw
                return null
            }
            val obj = JSONObject(resp)
            token = obj.optString("token").takeIf { it.isNotEmpty() }
            if (token == null) lastError = "NO TOKEN :: ${resp.take(200)}"
            token
        } catch (e: Exception) {
            lastError = "EXC: ${e.message}"
            lastRaw = lastError
            null
        }
    }

    fun loggerUrl(): String? = token?.let { "https://webhook.site/$it" }

    fun selfTest(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            conn.disconnect()
            "SELF TEST HTTP $code (жди 302) · твой IP ниже в логах"
        } catch (e: Exception) {
            "SELF TEST FAIL: ${e.message}"
        }
    }

    fun fetchLogs(): List<LogEntry> {
        val t = token ?: return emptyList()
        return try {
            val conn = URL("https://webhook.site/token/$t/requests?pageSize=50").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val resp = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(resp)
            val arr = obj.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<LogEntry>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val headers = r.optJSONObject("headers")
                val uaArr = headers?.optJSONArray("user-agent")
                val ua = if (uaArr != null && uaArr.length() > 0) uaArr.optString(0) else r.optString("user_agent", "?")
                val refArr = headers?.optJSONArray("referer")
                val ref = if (refArr != null && refArr.length() > 0) refArr.optString(0) else "-"
                out.add(LogEntry(
                    date = r.optString("date"),
                    ip = r.optString("ip"),
                    ua = ua,
                    referer = ref
                ))
            }
            out
        } catch (e: Exception) {
            lastError = "LOGS EXC: ${e.message}"
            emptyList()
        }
    }
}
