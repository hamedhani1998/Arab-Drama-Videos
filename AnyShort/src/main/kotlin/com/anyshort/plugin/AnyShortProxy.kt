package com.anyshort.plugin

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
 * وسيطٌ محليّ بين اللاعب وحافّاتِ أي شورت (tiktokcdn / ns-aws-cdn / v-mps) —
 * علاجُ التأخّر والتقطّع المقيس.
 *
 * المقياس (2026-10-21): طلبٌ **بلا رأس إطلاقاً** يُسجَّل على tiktokcdn —
 * ٢/٥ محاولات بطلب Range تنتهي بتصفير الاتصال (code=000)، و٤/٥ بجلبٍ عارٍ
 * كذلك، و`v-mps` سجّل 21.8 ثانية لتقرير الحالة بينما وسيطه 0.69 ث. البصمة
 * هي بصمة RimShort نفسها: التعثّر في **طور الرأس** (TLS + انتظار سطر
 * الحالة)، وإعادة المحاولة على اتصالٍ طازج تنجح.
 *
 * القاعدة الحاكمة: **أبداً أسوأ من الجلب المباشر** —
 *  - طور الرأس: سقف 4 ثوانٍ لكل محاولة (الصحّيّ المقيس 0.4–2.7 ث)،
 *    وعند انتهائه يُغلق الاتصال ويُفتح طازج؛ 4 ثوانٍ تحجز التعثّر ولا
 *    تقطع الصحّيّ.
 *  - طور الجسم: **بلا سقف جداري** — يُمرَّر إلى اللاعب أثناء الجلب، فالبطء
 *    المتّصل يتصرّف كجلبٍ مباشر (فجوةٌ صامتةٌ وحدها تُقتل عند 10 ث).
 *  - فشلُ الوسيط: `302` إلى الرابط الأصلي، لا `502` — اللاعب يتبع التحويل
 *    فيتطابق الفشل مع الجلب المباشر.
 *
 * وفارقٌ عن RimShort: قوائم HLS وحدها تُخزَّن كاملةً (إعادة الكتابة تحتاج
 * النصّ كلّه)، أمّا **وسائط MP4 فلا تُخزَّن أبداً** — رأس `Range` من اللاعب
 * يُمرَّر إلى الأعلى ويُعاد `206` كما هو. لو خزّنّاها لانتظر اللاعب تنزيل
 * الملف كلّه قبل أول بايت (ملفّات دراما طويلة)، وهو تأخّرُ بدءٍ أسوأ من
 * التعثّر الذي نُعالجه.
 *
 * نوعُ المورد لا يُخمَّن من الامتداد (روابط tiktokcdn بلا `.mp4` إطلاقاً):
 * الرابط المُسجَّل يحمل حكماً صريحاً من [register]، وأبناء القوائم تُقرأ
 * بايتاتها الأولى — `#EXTM3U` فقائمة، وإلّا فمقطعٌ يُبثّ مع بقيّة الجسم بلا
 * رحلةٍ ثانية.
 *
 * المسار:
 *  - `/x/{id}`         → مورد مُسجَّل عبر [register]، أبناؤه مُعاد كتابتهم
 *  - `/x/{id}/n/m/…`   → نزولٌ بفهرسات في شجرة القوائم (reelshort قائمتان)
 *  - سطرٌ غير معلَّق ← رابط الوسيط المطلق؛ ووسمٌ يحوي `URI="…"` ← مسارٌ
 *    مطلق مقابل مجلّد القائمة الأصلي (المفاتيح والترجمات تبقى مباشرة).
 */
object AnyShortProxy {
    private const val TAG = "AnyShortProxy"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val REFERER = "https://anyshort.net/ar/"

    /** رؤوس (اتصال TLS + انتظار الحالة) لكل محاولة — لا يُنقص مع تقدّم الجسم. */
    private val ATTEMPTS_MS = intArrayOf(4_000, 4_000, 8_000)
    private const val ATTEMPT3_GATE_MS = 6_000L

    /** مهلة قراءة الجسم لكل عملية — تُبطئ التغذية لا تقتل. */
    private const val BODY_READ_TIMEOUT_MS = 10_000

    private const val PLAYLIST_MAX_BYTES = 512 * 1024
    private const val PLAYLIST_CACHE_MAX = 64
    private const val SNIFF_BYTES = 1024

    /** موردٌ مُسجَّل: رابطه الأصلي، وهل هو قائمة (حكمُ المصدر لا تخمينُ الامتداد). */
    private data class Registered(val url: String, val isPlaylist: Boolean)

    private val registry = ConcurrentHashMap<String, Registered>()
    private val playlistCache = ConcurrentHashMap<String, String>()
    private var activeServer: ServerSocket? = null

    @Volatile
    private var serverPort = 0

    private val RE_URI_ATTR = Regex("""URI="([^"]+)"""")
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
            thread(name = "anyshort-proxy") {
                try {
                    while (true) {
                        val client = srv.accept()
                        thread(name = "anyshort-proxy-client") { handle(client) }
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
     * يسجّل رابطاً ويُعيد رابط الوسيط — **بلا جلبٍ هنا**، لأن نداء `loadLinks`
     * يُجمَع دفعةً واحدة. الجلب يقع أول طلبٍ من اللاعب داخل الخادم. يعود
     * null (فيرسَل الرابط الأصلي) لو تعذّر تشغيل الخادم.
     *
     * @param isPlaylist حكم المصدر (`ExtractorLinkType.M3U8`) — يمنع إرسال
     * `Range` إلى قائمة، وهو ما لا تفعله القوائم.
     */
    fun register(upstreamUrl: String, isPlaylist: Boolean): String? {
        if (!ensureStarted() || serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        registry[id] = Registered(upstreamUrl, isPlaylist)
        return "http://127.0.0.1:$serverPort/x/$id"
    }

    // ============================= upstream =============================

    /**
     * اتصالٌ مفتوحٌ وصلت رؤوسه. رؤوس الاتصال تُعاد على اتصالٍ طازج بعد كل
     * تعثّر، فإذا وصلت تُسلَّم للكائن دون سقفٍ جداري (تطويف الجسم أبطأ من
     * سقفنا، وإعادةُ محاولة جسمٍ كان سيكتمل خسارة).
     */
    private data class Upstream(
        val conn: HttpURLConnection,
        val code: Int,
        val contentType: String?,
        val contentLength: Int,
        val contentRange: String?,
    )

    /**
     * @param rangeHeader رأس `Range` من اللاعب — يُمرَّر إلى الأعلى للوسائط
     * (يُعاد `206` مُقطَّعاً من المصدر)، ويُترك null للقوائم والمقاطع.
     */
    private fun openUpstream(url: String, rangeHeader: String? = null): Upstream? {
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
                    if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
                }
                val code = conn.responseCode
                if (code == 200 || code == 206) {
                    val len = conn.getHeaderField("Content-Length")?.toIntOrNull() ?: -1
                    return Upstream(
                        conn, code, conn.contentType, len,
                        conn.getHeaderField("Content-Range"),
                    )
                }
                Log.w(TAG, "upstream $code attempt=$attempt $url")
                // 4xx حاسم: عنوانٌ خاطئ لا تعالجه إعادة المحاولة.
                if (code in 400..499) return null
            } catch (e: Exception) {
                Log.w(TAG, "headers attempt $attempt ${e.javaClass.simpleName} $url")
            }
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
        Log.e(TAG, "upstream headers exhausted $url")
        return null
    }

    /**
     * قراءةٌ كاملة (بلا سقفٍ جداري، مهلةٌ لكل عملية قراءة). `prefix` بايتاتٌ
     * قُرئت أثناء الفحص تُسبَق إلى المُخرَج فلا تُعاد قراءتها من الشبكة.
     */
    private fun readAll(ins: InputStream, cap: Int, prefix: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream(min(cap, 1 shl 16))
        prefix?.let { out.write(it, 0, it.size) }
        val buf = ByteArray(64 * 1024)
        while (out.size() < cap) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** نصّ قائمةٍ مُخزَّناً أو مُجلَباً — القوائم ثابتة (VOD) فالتخزين آمن. */
    private fun playlistText(url: String): String? {
        playlistCache[url]?.let { return it }
        val up = openUpstream(url) ?: return null
        return try {
            up.conn.readTimeout = BODY_READ_TIMEOUT_MS
            val text = String(readAll(up.conn.inputStream, PLAYLIST_MAX_BYTES), Charsets.UTF_8)
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
        val entry = id?.let { registry[it] }
        if (entry == null) {
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

        val target = resolveChain(entry.url, indices)
        if (target == null) {
            respond(socket, "404 Not Found", "text/plain", "no such child".toByteArray(), null)
            return
        }

        // الجذر: حكم المصدر صريح. الابن: غير معروف — يُقرأ من بايتاته الأولى.
        val knownPlaylist = if (indices.isEmpty()) entry.isPlaylist else null
        if (knownPlaylist == true) {
            servePlaylist(socket, target, entry.url, indices)
        } else {
            serveStream(socket, target, entry.url, indices, rangeHeader, knownPlaylist)
        }
    }

    /**
     * موردٌ غير محسوم: يُفتح، تُختلس بايتاته الأولى، فإن بدأت بـ`#EXTM3U`
     * فقائمةٌ تُخزَّن وتُعاد كتابتها، وإلّا فمقطعٌ/وسائط تُبثّ — **بالبايتات
     * المختلسة نفسها** فلا رحلة ثانية ولا إعادة تنزيل.
     *
     * `Range` يُرسَل فقط حين يعلم المصدر أنه وسائط (الجذر غير قائمة)؛
     * المقاطع تُجلب كاملة كما يفعل اللاعب.
     */
    private fun serveStream(
        socket: Socket,
        target: String,
        root: String,
        indices: List<Int>,
        rangeHeader: String?,
        knownPlaylist: Boolean?,
    ) {
        val up = openUpstream(target, if (knownPlaylist == false) rangeHeader else null)
        if (up == null) {
            redirect(socket, target)
            return
        }
        try {
            up.conn.readTimeout = BODY_READ_TIMEOUT_MS
            val ins = up.conn.inputStream

            if (knownPlaylist == null) {
                val head = ByteArray(SNIFF_BYTES)
                var got = 0
                while (got < SNIFF_BYTES) {
                    val n = ins.read(head, got, SNIFF_BYTES - got)
                    if (n < 0) break
                    got += n
                }
                val peek = if (got == SNIFF_BYTES) head else head.copyOf(got)
                if (looksLikePlaylist(peek)) {
                    // الجسم كاملٌ من نفس الاتصال المفتوح — لا رحلة ثانية.
                    val text = String(readAll(ins, PLAYLIST_MAX_BYTES, peek), Charsets.UTF_8)
                    if (playlistCache.size >= PLAYLIST_CACHE_MAX) playlistCache.clear()
                    playlistCache[target] = text
                    val rewritten = rewrite(text.toByteArray(Charsets.UTF_8), target, root, indices)
                    respond(socket, "200 OK", "application/vnd.apple.mpegurl", rewritten, null)
                    return
                }
                serveMedia(socket, up, target, peek)
                return
            }

            serveMedia(socket, up, target, null)
        } catch (e: Exception) {
            Log.w(TAG, "stream ${e.javaClass.simpleName} $target")
        } finally {
            try {
                up.conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** قائمةٌ تُخزَّن كاملةً وتُعاد كتابتها — أبناؤها يمرّون بالوسيط. */
    private fun servePlaylist(socket: Socket, target: String, root: String, indices: List<Int>) {
        val text = playlistText(target)
        if (text == null) {
            redirect(socket, target)
            return
        }
        val rewritten = rewrite(text.toByteArray(Charsets.UTF_8), target, root, indices)
        respond(socket, "200 OK", "application/vnd.apple.mpegurl", rewritten, null)
    }

    /**
     * وسائط: الحالة والترويسات من الأعلى كما هي (بما فيها `206` و
     * `Content-Range`)، والجسم يُمرَّر حيّاً بلا تخزين — فملفٌّ كبير يبدأ
     * فوراً بلا انتظار تنزيله كلّه. `prefix` بايتاتٌ قُرئت أثناء الفحص
     * تُرسَل أولاً. فشلُ الجسم بعد بدء الإرسال يُبتلع: إرسالُ استجابةٍ
     * ثانيةٍ فوق ناقصةٍ يفسد الاتصال، واللاعب يعيد الطلب بنفسه.
     */
    private fun serveMedia(socket: Socket, up: Upstream, target: String, prefix: ByteArray?) {
        val mime = up.contentType?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        val status = if (up.code == 206) "206 Partial Content" else "200 OK"
        val extra = ArrayList<String>(3)
        if (up.contentLength >= 0) extra.add("Content-Length: ${up.contentLength}")
        up.contentRange?.let { extra.add("Content-Range: $it") }
        extra.add("Accept-Ranges: bytes")

        val out = socket.getOutputStream()
        val header = StringBuilder()
            .append("HTTP/1.1 ").append(status).append('\n')
            .append("Content-Type: ").append(mime).append('\n')
            .append("Connection: close\n")
            .append("Access-Control-Allow-Origin: *\n")
        extra.forEach { header.append(it).append('\n') }
        header.append('\n')
        out.write(header.toString().toByteArray(Charsets.US_ASCII))
        prefix?.let { if (it.isNotEmpty()) out.write(it) }
        out.flush()

        val ins = up.conn.inputStream
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            out.flush()
        }
        out.flush()
    }

    /** توقيع HLS: أول سطر غير فارغ `#EXTM3U` (يُتسامح مع BOM ومسافةٍ بادئة). */
    private fun looksLikePlaylist(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) i = 3
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())
        ) i++
        val magic = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        if (bytes.size - i < magic.size) return false
        for (k in magic.indices) if (bytes[i + k] != magic[k]) return false
        return true
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

    /**
     * ارتدادٌ إلى الحافّة حين يفشل الوسيط: `302` إلى الرابط الأصلي لا `502`.
     * اللاعب يتبع التحويل فيصير الفشل مطابقاً للجلب المباشر تماماً — بلا
     * سطرٍ إضافيّ يفسد التشغيل. (الرابط الأصلي قد يكون موقّعاً؛ يُرسَل كما هو.)
     */
    private fun redirect(socket: Socket, target: String) {
        val sb = StringBuilder()
            .append("HTTP/1.1 302 Found\n")
            .append("Location: ").append(target).append('\n')
            .append("Content-Length: 0\n")
            .append("Connection: close\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

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

    private fun dirOf(url: String): String = url.substringBeforeLast('/', url) + "/"

    private fun resolve(dir: String, uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("//") -> "https:$uri"
        uri.startsWith("/") -> RE_ORIGIN.find(dir)?.groupValues?.get(1)?.plus(uri) ?: uri
        else -> dir + uri
    }
}
