package com.mosalsaly.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * خادم ترجمة محلي (نمط AryDashServer المُثبت في aryarabia).
 *
 * على الجهاز، كان جلب اللاعب للترجمة الخارجية (CronetDataSource عبر SingleSampleMediaPeriod)
 * ينتهي 403 لنفس الرابط الذي يرد 200 على الخادم بهواه — بينما فيديو نفس المضيف يعمل.
 * السبب الجذري لم يُحسم، لكن تمرير الترجمة عبر هذا الخادم يجعله يحسم:
 *   1) يجلب الجسم من الجهاز نفسه بنفس الـ IP لكن عبر java.net HttpURLConnection (HTTP/1.1)
 *      بدل Cronet HTTP/2 — أي توقيع TLS/إطار مختلف عن جلب اللاعب الفاشل.
 *   2) يسلّم اللاعب جسماً WebVTT كاملاً من 127.0.0.1:port — بلا CDN في مسار جلب اللاعب إطلاقاً،
 *      فالـ 403 المتقطع يصبح مستحيلاً، ومصدر MIME صحيحاً لأنه .vtt.
 *
 * يُفعَّل فقط لترجمات NetsShort (التي يُجدَّد auth لها) — باقي المنصات تمرّ مباشرة دون تغيير.
 */
object MosSubServer {
    private const val TAG = "MosSubServer"
    private data class Job(val url: String, val headers: Map<String, String>)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var server: ServerSocket? = null
    @Volatile private var port = 0

    @Synchronized
    fun ensureStarted() {
        if (server != null && !server!!.isClosed) return
        try {
            val srv = ServerSocket(0)
            server = srv
            port = srv.localPort
            thread(name = "mos-sub-server") {
                try {
                    while (!srv.isClosed) {
                        val client = srv.accept()
                        thread(name = "mos-sub-client") { handle(client) }
                    }
                } catch (e: Exception) {
                    if (!srv.isClosed) Log.w(TAG, "loop exit: ${e.message}")
                }
            }
            Log.d(TAG, "sub server on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "start fail: ${e.message}")
        }
    }

    /** يسجّل الترجمة ويعيد رابط 127.0.0.1 يُسلَّم للمشغّل ليجلبه محلياً. */
    fun register(url: String, headers: Map<String, String>): String? {
        ensureStarted()
        if (port == 0) return null
        val id = UUID.randomUUID().toString()
        jobs[id] = Job(url, headers)
        return "http://127.0.0.1:$port/$id.vtt"
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return
                if (!line.startsWith("GET ")) return
                val raw = line.split(' ').getOrNull(1) ?: return
                val id = raw.trimStart('/').removeSuffix(".vtt")
                val job = jobs[id]
                if (job == null) {
                    respond(s, "HTTP/1.1 404 Not Found", ByteArray(0))
                    return
                }
                var code = 502
                var body = ByteArray(0)
                try {
                    val conn = URL(job.url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 20000
                    conn.readTimeout = 40000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", job.headers["User-Agent"]
                        ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    conn.setRequestProperty("Referer", job.headers["Referer"] ?: "https://mosalsaly.com/")
                    conn.setRequestProperty("Accept", "text/vtt, */*")
                    val rc = conn.responseCode
                    if (rc in 200..299) {
                        code = 200
                        body = conn.inputStream.use { it.readBytes() }
                    } else {
                        Log.w(TAG, "proxy fetch $rc for ${job.url.take(80)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "proxy fetch err ${e.message}")
                }
                val header = buildString {
                    append("HTTP/1.1 $code ")
                    append(if (code == 200) "OK" else "Bad Gateway")
                    append("\r\nContent-Type: text/vtt; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("\r\n")
                }
                val out = s.getOutputStream()
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        } catch (e: Exception) {
            // عميل أُغلق — يتجاهل
        }
    }

    private fun respond(s: java.net.Socket, status: String, body: ByteArray) {
        try {
            val h = "$status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(h.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        } catch (_: Exception) {}
    }
}