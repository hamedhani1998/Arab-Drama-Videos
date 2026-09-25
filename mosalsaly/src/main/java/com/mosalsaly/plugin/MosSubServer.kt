package com.mosalsaly.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * خادم محلي داخل الإضافة (نمط AryDashServer المُثبت في aryarabia) يجلب المحتوى من مصادر
 * NetsShort عبر java.net.HttpURLConnection (مكدس HTTP/1.1) بدل CronetDataSource الذي يستخدمه
 * اللاعب (HTTP/2). جلب اللاعب المباشر لروابط ns-aws-cdn / dizi1 يموت على الجهاز
 * (InvalidResponseCode 403 / Source error) لنفس الرابط الذي يرد 200/206 عبر HTTP/1.1.
 *
 * يسجّل الإضافة وسيطاً (URL + MIME + isVideo) ويعيد رابط 127.0.0.1 يسلّمه اللاعب.
 * الخادم يدعم طلبات Range (مع Content-Range/Accept-Ranges) فيتجاوب سيّارة الفيديو في الكاميرا
 * والخاصية المنبثقة والسيك — أي أن مقطع mp4 كبير يتدفق بشكل صحيح بدل تسليم دفعة واحدة.
 *
 * يُفعَّل فقط لنطاق NetsShort — باقي المنصات تمرر روابطها المباشرة سليمة تماماً.
 */
object MosSubServer {
    private const val TAG = "MosSubServer"

    private enum class Kind(val ext: String, val mime: String) {
        VIDEO("mp4", "video/mp4"),
        SUBTITLE("vtt", "text/vtt; charset=utf-8"),
    }

    private const val DIZI1 = "https://dizi1.dramadizilerim.com/?url="

    private data class Job(
        val url: String,
        val headers: Map<String, String>,
        val kind: Kind,
    )
    private data class Established(
        val body: InputStream?,
        val contentLength: Long,
        val contentType: String?,
        val contentRange: String?,
        val acceptRanges: String?,
    )

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

    /** يسجّل فيديو/ترجمة ويعيد رابط 127.0.0.1 يُسلَّم للمشغّل. */
    fun registerVideo(url: String, headers: Map<String, String>): String? =
        register(url, headers, Kind.VIDEO)

    fun registerSubtitle(url: String, headers: Map<String, String>): String? =
        register(url, headers, Kind.SUBTITLE)

    private fun register(url: String, headers: Map<String, String>, kind: Kind): String? {
        ensureStarted()
        if (port == 0) return null
        val id = UUID.randomUUID().toString()
        jobs[id] = Job(url, headers, kind)
        return "http://127.0.0.1:$port/$id.${kind.ext}"
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("GET ")) return
                val rawPath = requestLine.split(' ').getOrNull(1) ?: return
                val path = rawPath.substringBefore('?')
                val id = path.trimStart('/').removeSuffix(".mp4").removeSuffix(".vtt")
                val job = jobs[id]
                if (job == null) {
                    respond(s, "HTTP/1.1 404 Not Found", ByteArray(0))
                    return
                }

                // قراءة رأس Range (إن وُجد) ونقلها للمصدر.
                var range = ""
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim()
                }

                var upstream: Established? = null
                var code = 502
                var msg = "Bad Gateway"

                // للمصدر المباشر (ns-aws): بعض الروابط تُرجع 403 من الجهاز حتى عبر HTTP/1.1
                // رغم أن auth طازج وأن الخادم يعطي 200 (معضلة v19/الجهاز). الاحتياط: جرّب dizi1
                // (بروكسي الموقع المركزي الذي يعطي 200 عبر HTTP/1.1) كمسار ثانٍ. محصور للترجمة
                // (والفيديو 403 الناتج عن br قديم يُستبعد أصلاً في الإضافة قبل الوصول هنا).
                val attempted = mutableListOf<String>()
                // الإستراتيجية: جرّب المصدر المباشر (ns-aws) أولاً — إن نجح فهو الأفضل (لا طبقة وسيطة).
                // عند الفشل (403/timeout من الجهاز عبر HTTP/1.1 رغم أن الخادم يرد 200)، نجرّب dizi1
                // (بروكسي الموقع المركزي الذي يرد 200/206 عبر HTTP/1.1). ينطبق على الفيديو والترجمة
                // netshort معاً؛ باقي المنصات لا تصل إلى هنا أصلاً (routeVideo netshort فقط).
                val proxy = DIZI1 + java.net.URLEncoder.encode(job.url, "UTF-8").replace("+", "%20")
                // الترجمة: dizi1 أولاً (بروكسي مركزي يسلّم WebVTT 200 لرابط مباشر قد يموت 000/403
                // من الجهاز)، ثم المباشر كاحتياط. الفيديو: المباشر أولاً ثم dizi1 كما كان.
                val attempts = if (job.kind == Kind.SUBTITLE) listOf(proxy, job.url)
                    else listOf(job.url, proxy)

                for (target in attempts) {
                    if (upstream != null) break
                    attempted.add(target.take(60))
                    try {
                        val conn = URL(target).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 20000
                        conn.readTimeout = 60000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", job.headers["User-Agent"]
                            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        conn.setRequestProperty("Referer", job.headers["Referer"] ?: "https://mosalsaly.com/")
                        conn.setRequestProperty("Accept", if (job.kind == Kind.SUBTITLE) "text/vtt, */*" else "*/*")
                        // الترجمة: لا نفرض Range إجبارياً — v19 أضاف bytes=0-1048575 كعلاج لكنه
                        // يكسّر dizi1 (يرد 403 Upstream Proxy للطلب المُقسّط) بينما نفس الرابط
                        // بلا Range يرد 200 WebVTT. نمرّر فقط Range إن أرسله اللاعب نفسه؛
                        // وحين لا يُرسل، نجلب كاملاً (200) — الترجمة صغيرة (كيلوبايتات) فلا مشكلة.
                        if (range.isNotEmpty()) {
                            conn.setRequestProperty("Range", range)
                        }
                        val rc = conn.responseCode
                        val cLen = runCatching { conn.contentLengthLong }.getOrDefault(-1L)
                        val cType = conn.contentType
                        val cRange = conn.getHeaderField("Content-Range")
                        val aRanges = conn.getHeaderField("Accept-Ranges")
                        val isRange = rc == 206
                        if (rc in 200..299) {
                            code = if (isRange) 206 else 200
                            msg = if (isRange) "Partial Content" else "OK"
                            upstream = Established(conn.inputStream, cLen, cType, cRange, aRanges)
                        } else {
                            Log.w(TAG, "proxy fetch $rc for ${target.take(160)}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "proxy fetch err ${e.message}")
                    }
                }

                if (upstream == null) {
                    Log.w(TAG, "all proxies failed $attempted")
                    respond(s, "HTTP/1.1 $code $msg", ByteArray(0))
                    return
                }
                streamOut(s, upstream, job.kind)
            }
        } catch (e: Exception) {
            // عميل أُغلق — يتجاهل
        }
    }

    private fun streamOut(s: java.net.Socket, est: Established, kind: Kind) {
        try {
            val body = est.body ?: return
            val status = if (est.contentRange != null) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
            val head = buildString {
                append("$status\r\n")
                append("Content-Type: ${kind.mime}\r\n")
                if (est.contentLength >= 0) append("Content-Length: ${est.contentLength}\r\n")
                append("Accept-Ranges: ${est.acceptRanges ?: "bytes"}\r\n")
                if (est.contentRange != null) append("Content-Range: ${est.contentRange}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = body.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
            body.close()
        } catch (e: Exception) {
            // عميل أُغلق mid-stream — طبيعي عند السيك
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