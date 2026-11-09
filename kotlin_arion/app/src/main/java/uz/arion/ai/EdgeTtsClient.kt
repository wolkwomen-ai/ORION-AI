package uz.arion.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Edge Read Aloud client for ARION.
 * Voice: uz-UZ-MadinaNeural.
 */
object EdgeTtsClient {
    private const val VOICE = "uz-UZ-MadinaNeural"
    private const val TRUSTED_TOKEN = EdgeToken.TRUSTED_CLIENT_TOKEN
    private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val EDGE_VERSION = "2-155.0.4283.45"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    fun synthesize(text: String): ByteArray {
        val clean = text.trim().take(3500)
        require(clean.isNotEmpty()) { "Matn bo‘sh" }

        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis() / 1000.0
        val token = EdgeToken.secMsGecV2(now)
        val url = "$WSS?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connectionId&Sec-MS-GEC=$token&Sec-MS-GEC-Version=$EDGE_VERSION"

        val audio = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        val request = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
            .header("Accept-Language", "uz-UZ,uz;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
                val stamp = edgeTimestamp()
                val config = "X-Timestamp:$stamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                val safe = escapeXml(clean)
                val ssml = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp}Z\r\nPath:ssml\r\n\r\n" +
                    "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='uz-UZ'><voice name='$VOICE'><prosody rate='+0%' pitch='+0Hz' volume='+0%'>$safe</prosody></voice></speak>"
                if (!webSocket.send(config) || !webSocket.send(ssml)) {
                    failure.set(IllegalStateException("Edge TTS so‘rovi yuborilmadi"))
                    completed.countDown()
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val header = text.substringBefore("\r\n\r\n")
                val path = header.lineSequence().firstOrNull { it.startsWith("Path:", true) }
                    ?.substringAfter(":")?.trim()
                if (path == "turn.end") {
                    completed.countDown()
                    webSocket.close(1000, "done")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val packet = bytes.toByteArray()
                if (packet.size < 2) return
                val headerLength = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
                if (headerLength + 2 > packet.size) {
                    failure.compareAndSet(null, IllegalStateException("Edge audio paketi noto‘g‘ri"))
                    completed.countDown()
                    return
                }
                val header = String(packet, 2, headerLength, Charsets.UTF_8)
                val path = header.lineSequence().firstOrNull { it.startsWith("Path:", true) }
                    ?.substringAfter(":")?.trim()
                if (path == "audio") synchronized(audio) {
                    audio.write(packet, headerLength + 2, packet.size - headerLength - 2)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(null, t)
                opened.countDown()
                completed.countDown()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (completed.count > 0) {
                    failure.compareAndSet(null, IllegalStateException("Edge TTS ulanishi erta yopildi: $reason"))
                    completed.countDown()
                }
            }
        })

        try {
            if (!opened.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Madina ovoz serveriga ulanib bo‘lmadi")
            if (!completed.await(35, TimeUnit.SECONDS)) throw IllegalStateException("Madina ovozi javob bermadi (vaqt tugadi)")
            failure.get()?.let { throw it }
            val result = synchronized(audio) { audio.toByteArray() }
            if (result.isEmpty()) throw IllegalStateException("Madina ovozi audio qaytarmadi")
            return result
        } finally {
            socket.cancel()
        }
    }

    private fun edgeTimestamp(): String {
        val format = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date())
    }

    private fun escapeXml(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> if (c.code !in 0..8 && c.code !in 11..12 && c.code !in 14..31) append(c) else append(' ')
            }
        }
    }
}