package com.rimshort.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * وسيطٌ محليّ بين اللاعب وحافّة `v-mps` — علاجُ التقطّع المتكرّر جذريّاً.
 *
 * المقياس (2026-10-20): طلباتٌ تتعثّر عشوائياً 20–45 ثانية على 6–30٪ منها —
 * حتى قائمةٍ صغيرة 6.9 كيلوبايت بحافّة `X-Cache: HIT` تأخّرت 22 ثانية، ثم
 * الطلب التالي فوراً. التعثّر يقع في **طور الأCodeAtال** (انتظار الرأس): جسم
 * المقطع الصغير لا يُبطئ 22 ثانية أبداً، والاتصال الدافئ يمنح 2.0–3.0
 * ميغابت/ث. واللاعب معدّله 8 ثوانٍ، فكل تعثّر = خطأ = دوّار = استئناف.
 *
 * والعلاج مُقاسٌ لا مُفترض — مرّتين:
 *  1. إعادة المحاولة على اتصالٍ طازج **تنجح دائماً** عند تعثّر الأCodeAtال
 *     (قياس: الطلب التالي فوراً بعد كل تعثّر، عشرات المرات).
 *  2. إبطاءُ الجسم المتَّجِم (trickle) لا يُعالَج بالإعادة — إعادةٌ تقطع
 *     جسماً كان سيكتمل بعد ثانيتين خسارةٌ محضة. فالجسم يُمرَّر كما هو.
 *
 * ومن هنا البنية، وكلُّ قرارٍ فيها مُقيَّد بقاعدةٍ واحدة: **أبداً أسوأ من
 * الجلب المباشر** —
 *  - طور الأCodeAtال: سقف 4 ثوانٍ لكل محاولة (صحّيّ 1.5–2.5ث، تعثّر 20–45ث —
 *    الفصل واسع)، وعند انتهائه يُغلق الاتصال ويُفتح طازج. ما يصله اللاعب
 *    بعد ذلك ≈ زمن الجلب المباشر في أسوأ الحالات، وأسرع بكثير في الحالة
 *    الشائعة من التعثّر.
 *  - طور الجسم: **بلا سقف جداري** — يُمرَّر مباشرةً إلى اللاعب أثناء الجلب
 *    (بثٌّ حيّ)، فالمقطع البطيء يتصرّف تماماً كجلبٍ مباشر: تغذيةٌ بطيئة بلا
 *    خطأ، واللاعب يتحمّل فجواته (مهلته 8ث لكل قراءة، والفجوات المقاسة
 *    أجزاء الثانية).
 *  - القوائم الصغيرة (7KB) تُخزَّن كاملاً — إعادة كتابتها تحتاج النصّ كلّه.
 *
 * المسار:
 *  - `/x/{id}`         → قائمة مُسجّلة عبر [register]، أبناؤها مُعاد كتابتهم
 *  - `/x/{id}/n/m/…`   → نزولٌ بفهرسات في شجرة القوائم (كل فهرس درجة)
 *  - سطرٌ غير معلَّق ← رابط الوسيط المطلق؛ وسمٌ يحوي `URI="…"` ← مسارٌ
 *    مُطلق مقابل مجلّد القائمة الأصلي (المفاتيح والترجمات و`EXT-X-MAP`
 *    تبقى تُجلب مباشرةً — طلبٌ صغيرٌ واحد لا يُقطّع الفيديو، والأهمّ ألاّ
 *    يُحلّ نسبياً على `127.0.0.1` فيضيع).
 */
object RimShortProxy {
    private const val TAG = "RimShortProxy"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val REFERER = "https://rimshort.cyou/"

    /**
     * رؤوسُ (اتصال TLS + انتظار الحالة) لكل محاولة — لا يُنقص مع تقدّم
     * الجسم. الصحيحة المقاسة 1.5–2.5ث، والتعثّرة 20–45ث، فـ4 ثوانٌ تحجز
     * التعثّر ولا تقطع الصحّيّ. والمحاولة الثالثة تُترك لدورةٍ أطول إن
     * جاءتا متعاقبتين سريعتين ([ATTEMPT3_GATE_MS]).
     */
    private val ATTEMPTS_MS = intArrayOf(4_000, 4_000, 8_000)
    private const val ATTEMPT3_GATE_MS = 6_000L

    /** مهلة قراءة الجسم لكل عملية — تُبطئ التغذية لا تقتلها (مهلة اللاعب 8ث). */
    private const val BODY_READ_TIMEOUT_MS = 10_000

    /** سقفٌ لجسم القائمة المُخزَّن — حمايةٌ من مسارٍ مُضلِّل، والطبيعي 7KB. */
    private const val PLAYLIST_MAX_BYTES = 512 * 1024
    private const val PLAYLIST_CACHE_MAX = 64

    private val registry = ConcurrentHashMap<String, String>()
    private val playlistCache = ConcurrentHashMap<String, String>()
    private var activeServer: ServerSocket? = null
    @Volatile
    private var serverPort = 0

    private val RE_URI_ATTR = Regex("""URI="([^"]+)"""")
    private val RE_RANGE = Regex("""bytes=(\d*)-(\d*)""")
    private val RE_ORIGIN = Regex("""^(https?://[^/]+)""")

    /** يشغّل الخادم إن لم يكن شغّالاً. يعود false فقط لو تعذّر ربط المنفذ. */
    @Synchronized
    fun ensureStarted(): Boolean {
        val existing = activeServer
        if (existing != null && !existing.isClosed && serverPort != 0) return true
        return try {
            val srv = ServerSocket(0)
            activeServer = srv
            serverPort = srv.localPort
            thread(name = "rimshort-proxy") {
                try {
                    while (true) {
                        val client = srv.accept()
                        thread(name = "rimshort-proxy-client") { handle(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                    try {
                        srv.close()
                    } catch (_: Exception) {
                    }
                }
            }
            Log.d(TAG, "proxy started on port $serverPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not start proxy: ${e.message}")
            false
        }
    }

    /**
     * يسجّل قائمة تشغيلٍ ويُعيد رابطها عبر الوسيط — **بلا جلبٍ هنا**، لأن
     * نداء `loadLinks` يُجمَع دفعةً واحدة ولا يُعاد حتى يرجع. الجلب يقع أول
     * طلبٍ من اللاعب، داخل الخادم نفسه. يعود null (فيرسَل الرابط الأصلي
     * مباشرة) لو تعذّر تشغيل الخادم.
     */
    fun register(upstreamUrl: String): String? {
        if (!ensureStarted() || serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        registry[id] = upstreamUrl
        return "http://127.0.0.1:$serverPort/x/$id"
    }

    // ============================= upstream =============================

    /**
     * اتصالٌ مفتوحٌ وصلت رؤوسه — [conn] حيّ وجسمه لم يُقرأ بعد (أو بقيّته).
     * رؤوس الاتصال مُعاد فتحها على اتصالٍ طازج بعد كل تعثّر؛ فإذا وصلت،
     * تُسلَّم الجسد للكائن دون سقفٍ جداري (تطويف الجسم أبطأ من سقفنا،
     * وأعادةُ محاولة جسمٍ كان سيكتمل خسارة).
     */
    private data class Upstream(val conn: HttpURLConnection, val code: Int, val contentType: String?)

    private fun openUpstream(url: String): Upstream? {
        val t0 = System.currentTimeMillis()
        for (attempt in ATTEMPTS_MS.indices) {
            if (attempt == ATTEMPTS_MS.lastIndex &&
                System.currentTimeMillis() - t0 > ATTEMPT3_GATE_MS
            ) break
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = ATTEMPTS_MS[attempt]
                    readTimeout = ATTEMPTS_MS[attempt]
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Referer", REFERER)
                }
                val code = conn.responseCode
                if (code == 200) {
                    return Upstream(conn, code, conn.contentType)
                }
                Log.w(TAG, "upstream $code attempt=$attempt $url")
                // 4xx حاسم: عنوانٌ خاطئ لا تعالجه إعادة المحاولة.
                if (code in 400..499) return null
            } catch (e: Exception) {
                Log.w(TAG, "headers attempt $attempt ${e.javaClass.simpleName} $url")
            }
            // أي فشلٍ أعلاه ← أغلق هذا الاتصال قبل طازجٍ آخر.
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
        Log.e(TAG, "upstream headers exhausted $url")
        return null
    }

    /** قراءةٌ كاملة للجسم (بلا سقفٍ جداري، مهلةٌ لكل عملية قراءة). */
    private fun readAll(ins: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream(min(cap, 1 shl 16))
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() >= cap) break
        }
        return out.toByteArray()
    }

    /** نصّ قائمةٍ مُخزَّناً أو مُجلَباً — القوائم ثابتة (VOD) فالتخزين آمن. */
    private fun playlistText(url: String): String? {
        playlistCache[url]?.let {
            return it
        }
        val up = openUpstream(url) ?: return null
        return try {
            up.conn.readTimeout = BODY_READ_TIMEOUT_MS
            val bytes = readAll(up.conn.inputStream, PLAYLIST_MAX_BYTES)
            val text = String(bytes, Charsets.UTF_8)
            if (playlistCache.size >= PLAYLIST_CACHE_MAX) playlistCache.clear()
            playlistCache[url] = text
            text
        } catch (e: Exception) {
            Log.w(TAG, "playlist body ${e.javaClass.simpleName} $url")
            null
        } finally {
            try {
                up.conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** أبناء قائمةٍ كروابط مطلقة — نفس تعداد إعادة الكتابة: غير المعلَّق وحده. */
    private fun childUris(playlistUrl: String): List<String>? {
        val text = playlistText(playlistUrl) ?: return null
        val dir = dirOf(playlistUrl)
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolve(dir, it) }
    }

    /** نزولٌ في شجرة القوائم بفهرسات المسار: `/x/{id}/0/1` ← الجذر.ابن٠.ابن١. */
    private fun resolveChain(root: String, indices: List<Int>): String? {
        var current = root
        for (idx in indices) {
            current = childUris(current)?.getOrNull(idx) ?: return null
        }
        return current
    }

    // ============================== server ==============================

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    respond(socket, "400 Bad Request", "text/plain", "bad request".toByteArray(), null)
                    return
                }
                if (parts[0] != "GET") {
                    respond(socket, "405 Method Not Allowed", "text/plain", "GET only".toByteArray(), null)
                    return
                }
                route(socket, parts[1].substringBefore('?'), headers["range"])
            }
        } catch (e: Exception) {
            // عميلٌ أغلق الاتصال — يُتجاهل
        }
    }

    private fun route(socket: Socket, path: String, rangeHeader: String?) {
        val segs = path.trim('/').split('/')
        val id = if (segs.size >= 2 && segs[0] == "x") segs[1] else null
        val root = id?.let { registry[it] }
        if (root == null) {
            respond(socket, "404 Not Found", "text/plain", "unknown stream".toByteArray(), null)
            return
        }
        val indices = ArrayList<Int>(segs.size - 2)
        for (i in 2 until segs.size) {
            val n = segs[i].toIntOrNull()
            if (n == null || n < 0) {
                respond(socket, "404 Not Found", "text/plain", "bad index".toByteArray(), null)
                return
            }
            indices.add(n)
        }

        val target = resolveChain(root, indices)
        if (target == null) {
            respond(socket, "404 Not Found", "text/plain", "no such child".toByteArray(), null)
            return
        }
        val up = openUpstream(target)
        if (up == null) {
            respond(socket, "502 Bad Gateway", "text/plain", "upstream failed".toByteArray(), null)
            return
        }
        try {
            // من هنا فصاعداً طورُ الجسم: مهلةُ القراءة مهلةُ التغذية لا
            // سقفُ المجموع — فجوةٌ صامتةٌ وحدها تُقتل (10ث)، لا بطءٌ متّصل.
            up.conn.readTimeout = BODY_READ_TIMEOUT_MS
            val ins = up.conn.inputStream

            // نظرةٌ أولى: قائمة أم مقطع؟ المحتوى هو الحاكم لا الامتداد.
            val peek = ByteArray(1024)
            var peekLen = 0
            while (peekLen < peek.size) {
                val n = ins.read(peek, peekLen, peek.size - peekLen)
                if (n < 0) break
                peekLen += n
            }
            val head = String(peek, 0, min(peekLen, 32), Charsets.UTF_8)
            val mime = up.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

            if (head.contains("#EXTM3U")) {
                // قائمة: تُخزَّن كاملاً (إعادة الكتابة تحتاج النصّ كلّه).
                val out = ByteArrayOutputStream(peekLen + 8192)
                out.write(peek, 0, peekLen)
                val rest = readAll(ins, PLAYLIST_MAX_BYTES - peekLen)
                out.write(rest)
                val body = out.toByteArray()
                if (playlistCache.size >= PLAYLIST_CACHE_MAX) playlistCache.clear()
                playlistCache[target] = String(body, Charsets.UTF_8)
                val rewritten = rewrite(body, target, root, indices)
                respond(socket, "200 OK", "application/vnd.apple.mpegurl", rewritten, null)
            } else if (rangeHeader != null) {
                // طلبٌ مُنَوَّع: يُخزَّن كاملاً ثم يُقطَّع (الجسم في الذاكرة أصلاً).
                val out = ByteArrayOutputStream(peekLen + 65536)
                out.write(peek, 0, peekLen)
                out.write(readAll(ins, 64 * 1024 * 1024))
                serveMedia(socket, out.toByteArray(), mime, rangeHeader)
            } else {
                // مقطعٌ حيّ: يُمرَّر إلى اللاعب أثناء الجلب — بلا سقفٍ جداري،
                // فبطءُ المسار يتصرّف كجلبٍ مباشر تماماً. فشلُه بعد بدء
                // الإرسال يُبتلع هنا — إرسالُ استجابةٍ ثانيةٍ فوق ناقصةٍ
                // يفسد الاتصال ولا يُصلحه؛ واللاعب يرى قراءةً منتهية ويعيد
                // طلبه بنفسه (سلوكُ الجلب المباشر نفسه).
                try {
                    streamToClient(socket, mime, peek, peekLen, ins)
                } catch (e: Exception) {
                    Log.w(TAG, "stream ${e.javaClass.simpleName} $target")
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "route ${e.javaClass.simpleName} $target")
            try {
                respond(socket, "502 Bad Gateway", "text/plain", "upstream read failed".toByteArray(), null)
            } catch (_: Exception) {
            }
        } finally {
            try {
                up.conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * مقطعٌ مخزَّن بالكامل: يُدعم `Range` لأن الجسم في الذاكرة أصلاً.
     */
    private fun serveMedia(socket: Socket, body: ByteArray, mime: String, rangeHeader: String?) {
        val total = body.size
        val range = parseRange(rangeHeader, total)
        if (range === RANGE_UNSATISFIABLE) {
            val extra = listOf("Content-Range: bytes */$total")
            respond(socket, "416 Range Not Satisfiable", mime, ByteArray(0), extra)
            return
        }
        if (range == null) {
            respond(socket, "200 OK", mime, body, null)
        } else {
            val (start, end) = range
            val slice = body.copyOfRange(start, end + 1)
            val extra = listOf("Content-Range: bytes $start-$end/$total")
            respond(socket, "206 Partial Content", mime, slice, extra)
        }
    }

    /**
     * تمريرُ الجسم الحيّ: الرأس بلا `Content-Length` (الجسم ينتهي بغلق
     * الاتصال)، ثم ما تبقّى من الأCodeAtال ثم الاستمرار. كلُّ فجوةٍ صامتة
     * تُقطع عند مهلة القراءة (10ث)؛ وفجواتُ التطويف المقاسة أجزاء الثانية
     * تمرّ كما تمرّ مع الجلب المباشر.
     */
    private fun streamToClient(socket: Socket, mime: String, peek: ByteArray, peekLen: Int, ins: InputStream) {
        val out = socket.getOutputStream()
        val header = StringBuilder()
            .append("HTTP/1.1 200 OK\n")
            .append("Content-Type: ").append(mime).append('\n')
            .append("Connection: close\n")
            .append("Access-Control-Allow-Origin: *\n")
            .append('\n')
            .toString()
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (peekLen > 0) out.write(peek, 0, peekLen)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
        }
        out.flush()
    }

    /**
     * إعادة كتابة القائمة: كل سطر ابنٍ ← رابط الوسيط المطلق بفهرس سلسلته،
     * وكل وسمٍ يحوي `URI="…"` ← مسارٌ مطلق مقابل مجلّد القائمة الأصلي.
     */
    private fun rewrite(body: ByteArray, playlistUrl: String, root: String, chain: List<Int>): ByteArray {
        val dir = dirOf(playlistUrl)
        val suffix = chain.joinToString("") { "/$it" }
        val out = StringBuilder(body.size + 128)
        var childIdx = 0
        for (raw in String(body, Charsets.UTF_8).lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> out.append(absoluteizeUriAttr(line, dir))
                else -> {
                    out.append("http://127.0.0.1:$serverPort/x/$root$suffix/$childIdx")
                    childIdx++
                }
            }
            out.append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** `URI="sub/ar.m3u8"` ← مسارٌ مطلق — وحدهٌ ما يتغيّر داخل الأسطر المعلَّقة. */
    private fun absoluteizeUriAttr(line: String, dir: String): String =
        RE_URI_ATTR.replace(line) { m -> "URI=\"" + resolve(dir, m.groupValues[1]) + "\"" }

    private fun respond(
        socket: Socket,
        status: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String>?,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append('\n')
        sb.append("Content-Type: ").append(contentType).append('\n')
        sb.append("Content-Length: ").append(body.size).append('\n')
        sb.append("Connection: close").append('\n')
        sb.append("Access-Control-Allow-Origin: *").append('\n')
        extraHeaders?.forEach { sb.append(it).append('\n') }
        sb.append('\n')
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    // ============================== helpers ==============================

    private val RANGE_UNSATISFIABLE = IntArray(0)

    /**
     * `bytes=a-b` / `bytes=a-` / `bytes=-n` مقابل جسمٍ معروف — يعود مصفوفة
     * [start, end]، أو [RANGE_UNSATISFIABLE]، أو null لجسمٍ كامل 200.
     */
    private fun parseRange(header: String?, total: Int): IntArray? {
        if (header.isNullOrBlank() || total <= 0) return null
        val m = RE_RANGE.find(header) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a.isEmpty() && b.isEmpty()) return null
        if (a.isEmpty()) {
            val n = b.toIntOrNull() ?: return null
            if (n <= 0) return RANGE_UNSATISFIABLE
            val start = (total - n).coerceAtLeast(0)
            return intArrayOf(start, total - 1)
        }
        val start = a.toIntOrNull() ?: return null
        if (start >= total) return RANGE_UNSATISFIABLE
        val end = if (b.isEmpty()) total - 1
        else (b.toIntOrNull()?.coerceAtMost(total - 1) ?: (total - 1))
        if (end < start) return RANGE_UNSATISFIABLE
        return intArrayOf(start, end)
    }

    private fun dirOf(url: String): String = url.substringBeforeLast('/', url) + "/"

    private fun resolve(dir: String, uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("//") -> "https:$uri"
        uri.startsWith("/") -> RE_ORIGIN.find(dir)?.groupValues?.get(1)?.plus(uri) ?: uri
        else -> dir + uri
    }
}
