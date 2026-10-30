package cloudstreamshared

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * محرّك روابط التحميل المشترك بين إضافات هذا المستودع.
 *
 * يفعل ما تفعله إضافة MoviesMod نفسها، لكن بلا أي افتراضٍ عن اسم مضيف:
 *  1. يتبع سلسلة البوّابات (`?sid=` / `?lp_go=` / `?go=` / `/getlink/`).
 *  2. يقرأ النماذج من الصفحة نفسها (`action` وأسماء الحقول) فلا يكسرها تبديلها.
 *  3. يتعامل مع كعكة التحقق التي تُضبط بـJS (`s_343('name','value',60)` + `?go=name`).
 *  4. يقرأ صفحة `driveseed.org` (`/file/` أو `/list/`) ويُخرج كل مراياها:
 *     `r2.dev` و`video-gen.xyz` و`workers.dev` — كلها بأسمائها الحقيقية.
 *  5. يُعلن ما هو **تحميل فقط**: أرشيف `zip/rar` أو `googleusercontent.com`.
 *
 * لماذا هنا لا في كل إضافة: السلسلة نفسها تتكرّر في المواقع الثلاثة، ونسخةٌ
 * واحدة تعني إصلاحًا واحدًا. (إضافة MoviesMod تحتفظ بنسختها المستقرّة.)
 */
object DriveChain {

    private const val TAG = "DriveChain"

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** رابطٌ نهائي جاهز للعرض في قائمة المشغّل. */
    data class Link(
        val url: String,
        val server: String,
        val quality: Int,
        val bytes: Long? = null,
        val note: String? = null,
        val referer: String? = null,
        val downloadOnly: Boolean = false,
        /** صفحةٌ محجوبة بتسجيل الدخول (أرشيف ZIP على G-Drive). */
        val gated: Boolean = false,
    )

    private val head get() = mapOf("User-Agent" to UA)
    private val formHead get() = mapOf(
        "User-Agent" to UA,
        "Content-Type" to "application/x-www-form-urlencoded",
    )

    // ---------- DNS ----------

    /**
     * تعويض سجلّ DNS المحذوف لبعض المضيفات (قِيس على الجهاز: `video-gen.xyz`
     * و`*.workers.dev` تردّ NXDOMAIN من مُحلِّل النظام بينما حافة Cloudflare
     * تخدمها فعلًا). التثبيت يترك الاسم و`SNI` كما هما فلا يعلم الخادم بشيء.
     */
    fun installDns() {
        try {
            val c = app.baseClient
            if (c.dns is PinnedDns) return
            val b = c.newBuilder()
            b.dns(PinnedDns(c.dns))
            if (c.readTimeoutMillis < 90000) {
                b.readTimeout(60, TimeUnit.SECONDS)
                b.connectTimeout(30, TimeUnit.SECONDS)
                b.writeTimeout(30, TimeUnit.SECONDS)
                b.callTimeout(90, TimeUnit.SECONDS)
            }
            app.baseClient = b.build()
            Log.i(TAG, "installDns: ${PinnedDns.lastIps.joinToString()}")
        } catch (e: Throwable) {
            Log.i(TAG, "installDns FAIL ${e.message}")
        }
    }

    private class PinnedDns(private val base: Dns) : Dns {
        // تُثبَّت المضيفات التي لا يُحلّها مُحلِّل النظام (NXDOMAIN) وحدها.
        // `driveseed.org` **لا** يُثبَّت: هو صاحب السلسلة كلها، وتثبيته على
        // عنوانٍ من مُحلِّلٍ محجوب كان يُسقط كل الروابط في الإضافتين معًا.
        private val pinnedHosts = setOf(
            "video-gen.xyz",
            "instant.video-gen.xyz",
            "cdn.video-gen.xyz",
        )
        private val pinnedSuffixes = listOf(".video-gen.xyz", ".workers.dev")

        private fun isPinned(host: String): Boolean =
            pinnedHosts.contains(host) || pinnedSuffixes.any { host.endsWith(it) }

        override fun lookup(hostname: String): List<InetAddress> {
            if (isPinned(hostname.lowercase())) {
                val ips = CF_EDGE_IPS.ifEmpty { resolveIps() ?: return base.lookup(hostname) }
                return ips.map { InetAddress.getByName(it) }
            }
            return base.lookup(hostname)
        }

        private fun resolveIps(): List<String>? {
            for (host in PINNED_SEEDS) {
                val got = try {
                    base.lookup(host).mapNotNull { it.hostAddress }
                        .filter { it.contains('.') && !it.contains(':') }
                } catch (e: Exception) {
                    emptyList()
                }
                if (got.isNotEmpty()) {
                    lastIps = got
                    return got
                }
            }
            return null
        }

        companion object {
            @Volatile var lastIps: List<String> = listOf("104.21.4.20", "172.67.131.141")
        }
    }

    // ---------- الواجهة ----------

    /**
     * يحلّ مدخلًا (رابط `?sid=` أو صفحة `/getlink/` أو صفحة أرشيف) إلى روابط
     * المشاهدة/التحميل. [label] عنوان الصفّ (يحمل الجودة ورقم الحلقة)،
     * و[zipOnly] يعني: أعطني ملفّات الأرشيف وحدها.
     */
    suspend fun resolve(
        entryUrl: String,
        label: String? = null,
        quality: Int = 0,
        zipOnly: Boolean = false,
    ): List<Link> {
        if (entryUrl.isBlank()) return emptyList()
        val fileUrl = followChain(entryUrl)
        if (fileUrl == null) {
            Log.i(TAG, "resolve: لا رابط نهائي لـ${entryUrl.take(70)}")
            return emptyList()
        }
        Log.i(TAG, "resolve: ${fileUrl.take(90)} label=$label")
        return mirrorsOf(fileUrl, label, quality, zipOnly)
    }

    // ---------- السلسلة ----------

    private data class Raw(val code: Int, val body: String, val location: String?)

    private suspend fun rawGet(url: String, cookie: String?, referer: String?): Raw? =
        withContext(Dispatchers.IO) {
            var c: java.net.HttpURLConnection? = null
            try {
                c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
                c.instanceFollowRedirects = false
                c.connectTimeout = 20000
                c.readTimeout = 20000
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
                if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                Raw(code, text, loc)
            } catch (e: Throwable) {
                Log.i(TAG, "rawGet FAIL ${e.javaClass.simpleName} ${e.message}")
                null
            } finally {
                try {
                    c?.disconnect()
                } catch (e: Throwable) {
                }
            }
        }

    /**
     * يتبع سلسلة البوّابة حتى أوّل رابط `driveseed`.
     *
     * السلسلتان المقروءتان (2026-10):
     * - `thenaukriadda` (MoviesMod): `lp-land` → `lp-s1-form` → `sc(...)` + `?lp_go=`.
     * - `cloud.unblockedgames.world` (MoviesLeech): `landing(_wp_http)` →
     *   `landing(_wp_http2+token)` → صفحة تحقق فيها `s_343('pepe-…',…)` →
     *   `?go=pepe-…` مع الكعكة → `meta refresh` إلى `driveseed`.
     * والاثنتان تُقرأان من الصفحة، لا من ثوابت.
     */
    private suspend fun followChain(entry: String): String? {
        for (attempt in 1..2) {
            try {
                var current = entry
                var referer: String? = entry
                var pendingBody: String? = null
                var pendingUrl: String? = null
                val seenForms = HashSet<String>()
                var hops = 0
                while (hops < 24) {
                    hops++
                    val body: String
                    val pageUrl: String
                    if (pendingBody != null) {
                        body = pendingBody
                        pageUrl = pendingUrl ?: current
                        pendingBody = null
                        pendingUrl = null
                    } else {
                        val resp = app.get(
                            current,
                            headers = head,
                            referer = referer,
                            allowRedirects = false,
                        )
                        val loc = resp.headers["Location"] ?: resp.headers["location"]
                        if (!loc.isNullOrBlank()) {
                            val next = absUrl(current, loc)
                            if (isFinal(next)) return next
                            current = next
                            referer = next
                            continue
                        }
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        body = resp.text
                        pageUrl = resp.url.toString()
                    }

                    // 1) وصلنا إلى driveseed.
                    driveseedLink(body)?.let { return it }

                    // 2) كعكة التحقق (`s_343`) ثم `?go=<name>`.
                    val vc = verifyCookie(body)
                    if (vc != null) {
                        val name = vc.first
                        val value = vc.second
                        val go = rootOf(pageUrl) + "?go=" + name
                        Log.i(TAG, "hop$hops: go=$name")
                        var nextUrl: String? = null
                        val raw = rawGet(go, "$name=$value", pageUrl)
                        if (raw != null) {
                            if (!raw.location.isNullOrBlank()) {
                                nextUrl = absUrl(go, raw.location)
                            } else if (raw.code in 200..299) {
                                driveseedLink(raw.body)?.let { return it }
                                jsTarget(raw.body)?.let { mv -> nextUrl = absUrl(go, mv) }
                            }
                        }
                        if (nextUrl == null) {
                            // احتياط عبر CloudStream (جار كعك نظيف).
                            val gr = try {
                                app.get(
                                    go,
                                    headers = head + mapOf("Cookie" to "$name=$value"),
                                    referer = pageUrl,
                                    cookies = mapOf(name to value),
                                    allowRedirects = false,
                                )
                            } catch (e: Exception) {
                                null
                            }
                            if (gr != null) {
                                val gloc = gr.headers["Location"] ?: gr.headers["location"]
                                if (!gloc.isNullOrBlank()) {
                                    nextUrl = absUrl(go, gloc)
                                } else if (gr.isSuccessful) {
                                    driveseedLink(gr.text)?.let { return it }
                                    jsTarget(gr.text)?.let { mv -> nextUrl = absUrl(go, mv) }
                                }
                            }
                        }
                        if (nextUrl != null) {
                            if (isFinal(nextUrl)) return nextUrl
                            current = nextUrl
                            referer = go
                            continue
                        }
                        throw Exception("go بلا انتقال")
                    }

                    // 3) نموذج POST — أيّ نموذج، بأيّ اسم.
                    val form = anyForm(body)
                    if (form != null) {
                        val key = form.first + "|" + form.second.entries.sortedBy { it.key }
                            .joinToString("&")
                        if (!seenForms.add(key)) throw Exception("POST مكرّر")
                        val pr = app.post(
                            form.first,
                            data = form.second,
                            referer = pageUrl,
                            headers = formHead,
                            allowRedirects = false,
                        )
                        val ploc = pr.headers["Location"] ?: pr.headers["location"]
                        if (!ploc.isNullOrBlank()) {
                            val next = absUrl(form.first, ploc)
                            if (isFinal(next)) return next
                            current = next
                            referer = form.first
                            continue
                        }
                        if (!pr.isSuccessful) throw Exception("POST HTTP ${pr.code}")
                        val pbody = pr.text
                        driveseedLink(pbody)?.let { return it }
                        pendingBody = pbody
                        pendingUrl = pr.url.toString()
                        current = pr.url.toString()
                        referer = form.first
                        continue
                    }

                    // 4) كعكة `sc(...)` → `?lp_go=`.
                    val sc = scOf(body)
                    if (sc != null) {
                        val go = rootOf(pageUrl) + "?lp_go=" + sc.first
                        var nextUrl: String? = null
                        val raw = rawGet(go, "${sc.first}=${sc.second}", pageUrl)
                        if (raw != null) {
                            if (!raw.location.isNullOrBlank()) {
                                nextUrl = absUrl(go, raw.location)
                            } else if (raw.code in 200..299) {
                                driveseedLink(raw.body)?.let { return it }
                                jsTarget(raw.body)?.let { mv -> nextUrl = absUrl(go, mv) }
                            }
                        }
                        if (nextUrl != null) {
                            if (isFinal(nextUrl)) return nextUrl
                            current = nextUrl
                            referer = go
                            continue
                        }
                        throw Exception("lp_go بلا انتقال")
                    }

                    // 5) انتقال JS / meta.
                    jsTarget(body)?.let { mv ->
                        val next = absUrl(pageUrl, mv)
                        if (isFinal(next)) return next
                        if (next == pageUrl) throw Exception("redirect loop")
                        current = next
                        referer = next
                        continue
                    }

                    throw Exception("طريق مسدود ${pageUrl.take(70)} (${body.length} بايت)")
                }
                throw Exception("hops exhausted")
            } catch (e: Exception) {
                Log.i(TAG, "followChain try$attempt FAIL ${e.javaClass.simpleName} ${e.message}")
                if (attempt < 2) delay(900)
            }
        }
        return null
    }

    /** كعكة `sc("name","value",n)` إن وُجدت. */
    private fun scOf(body: String): Pair<String, String>? =
        RE_SC.find(normBody(body))?.let {
            it.groupValues[1] to unescapeText(it.groupValues[2]).replace(" ", "+")
        }

    /** كعكة `s_343('name','value',60)` التي تُضبط قبل `?go=`. */
    private fun verifyCookie(body: String): Pair<String, String>? =
        RE_VERIFY.find(normBody(body))?.let {
            it.groupValues[1] to it.groupValues[2]
        }

    // ---------- صفحات driveseed ----------

    private suspend fun mirrorsOf(
        fileUrl: String,
        serverLabel: String?,
        baseQuality: Int,
        zipOnly: Boolean,
    ): List<Link> {
        var quality = if (baseQuality != 0) baseQuality else qualityOf(serverLabel ?: fileUrl)
        val wantEp = epFromText(serverLabel ?: "")
        val baseNote = serverLabel?.let { label ->
            Regex("S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE).find(label)?.value
                ?: label.trim().ifBlank { null }
        }
        val out = ArrayList<Link>(12)
        val seen = HashSet<String>()
        val gatedNotes = HashSet<String>()

        fun add(
            rawUrl: String,
            server: String,
            referer: String?,
            note: String?,
            gated: Boolean = false,
            bytes: Long? = null,
        ) {
            val u = unescapeText(rawUrl).replace(RE_WS, "").trimEnd('.', ',', ')', ';')
            if (u.isBlank() || !seen.add("$u|$server")) return
            val noteVal = note ?: baseNote
            val dl = isArchiveUrl(u) || u.contains("googleusercontent.com", true) ||
                noteVal?.contains(".zip", true) == true || noteVal?.contains(".rar", true) == true
            out.add(
                Link(
                    url = u,
                    server = server,
                    quality = quality,
                    bytes = bytes,
                    note = noteVal,
                    referer = referer,
                    downloadOnly = dl,
                    gated = gated,
                )
            )
        }

        fun collect(html: String, pageUrl: String, note: String?): Int {
            val b = html.replace("\\/", "/")
            var found = 0
            for (a in RE_ANCHOR.findAll(b)) {
                val href = RE_HREF.find(a.groupValues[1])?.groupValues?.get(1)
                    ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
                if (href.isBlank()) continue
                val text = stripTags(a.groupValues[2]).trim()
                when {
                    href.contains(".r2.dev") -> {
                        add(href, text.ifBlank { "Cloud Download" }, null, note)
                        found++
                    }
                    href.contains("video-gen.xyz") -> {
                        add(
                            href,
                            text.ifBlank {
                                if (href.contains("cdn.")) "Instant Download"
                                else "Instant Download V2"
                            },
                            pageUrl,
                            note,
                        )
                        found++
                    }
                    href.contains("workers.dev") -> {
                        add(href, text.ifBlank { "Mirror" }, pageUrl, note)
                        found++
                    }
                }
            }
            if (found == 0) {
                for (m in RE_R2_ANY.findAll(b)) {
                    add(m.value, "Cloud Download", null, note)
                    found++
                }
                for (m in RE_VG_ANY.findAll(b)) {
                    add(
                        m.value,
                        if (m.value.contains("cdn.")) "Instant Download" else "Instant Download V2",
                        pageUrl,
                        note,
                    )
                    found++
                }
            }
            return found
        }

        fun addGated(pageUrl: String, html: String, note: String?) {
            val file = fileNameOf(html)?.let { displayName(it) } ?: note ?: "ZIP"
            if (gatedNotes.size >= 10 || !gatedNotes.add(file)) return
            add(
                pageUrl,
                "G-Drive",
                pageUrl,
                file + " · ZIP · login required",
                gated = true,
                bytes = sizeOf(html),
            )
        }

        suspend fun extras(pageUrl: String) {
            val id = pageUrl.substringAfter("/file/", "").substringBefore("?").substringBefore("/")
            if (id.isBlank()) return
            val targets = listOf(
                "https://driveseed.org/wfile/$id" to "Direct Links",
                "https://driveseed.org/wfile/$id?type=1" to "Server 1",
                "https://driveseed.org/wfile/$id?type=2" to "Server 2",
                "https://driveseed.org/zfile/$id" to "Cloud Resume Download",
            )
            val bodies = coroutineScope {
                targets.map { (u, server) ->
                    async(Dispatchers.IO) {
                        val body = getText(u, referer = pageUrl)?.first ?: return@async null
                        u to (server to body)
                    }
                }.awaitAll().filterNotNull()
            }
            for ((u, pair) in bodies) {
                val server = pair.first
                val body = pair.second
                for (a in RE_ANCHOR.findAll(body.replace("\\/", "/"))) {
                    val href = RE_HREF.find(a.groupValues[1])?.groupValues?.get(1)
                        ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
                    if (!href.contains("workers.dev") && !href.contains(".r2.dev")) continue
                    val text = stripTags(a.groupValues[2]).trim()
                    add(href, text.ifBlank { server }, u, null)
                }
            }
        }

        try {
            var current = fileUrl
            var referer: String? = null
            for (hop in 0..8) {
                val got = getText(current, referer = referer) ?: break
                val body = got.first
                val pageUrl = got.second
                referer = pageUrl

                val fileNote = fileNameOf(body)?.let { displayName(it) }
                fileNote?.let { q ->
                    qualityOf(q).takeIf { it != Qualities.Unknown.value }?.let { quality = it }
                }
                val note = fileNote ?: baseNote
                val found = collect(body, pageUrl, note)

                if (pageUrl.contains("/file/")) {
                    if (found == 0) {
                        extras(pageUrl)
                        if (out.isEmpty()) addGated(pageUrl, body, note)
                    }
                    if (out.isNotEmpty()) break
                }

                val files = fileLinksOf(body)
                if (files.isNotEmpty()) {
                    val picked = if (zipOnly) {
                        files.filter { (_, t) -> isArchiveName(t) }.take(6)
                    } else if (wantEp != null) {
                        val epFiles = files.filter { (_, t) -> epFromText(t) == wantEp }
                        val arch = files.filter { (_, t) -> isArchiveName(t) }.take(4)
                        (epFiles.ifEmpty { files } + arch).distinctBy { it.first }
                    } else {
                        files
                    }
                    if (picked.isEmpty()) break
                    val cap = if (zipOnly || wantEp != null) 8 else 20
                    val subs = ArrayList<Pair<String, String>>(picked.size)
                    for (chunk in picked.take(cap).chunked(8)) {
                        subs += coroutineScope {
                            chunk.map { (href, _) ->
                                async(Dispatchers.IO) {
                                    val fu = absUrl(pageUrl, href)
                                    getText(fu, referer = pageUrl)?.let { fu to it.first }
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }
                    for ((fu, text) in subs) {
                        val fn = fileNameOf(text)?.let { displayName(it) }
                        val ok = collect(text, fu, fn) > 0
                        if (!ok && fu.contains("/file/")) addGated(fu, text, fn)
                    }
                    if (out.isNotEmpty()) break
                }

                val mv = jsTarget(body) ?: break
                val next = absUrl(pageUrl, mv)
                if (next == current) break
                current = next
            }
        } catch (e: Exception) {
            Log.i(TAG, "mirrorsOf FAIL ${e.javaClass.simpleName} ${e.message}")
        }

        Log.i(TAG, "mirrorsOf: ${out.size} links (${out.map { it.server }.distinct().take(4)})")
        if (out.isEmpty()) return emptyList()

        // حلّ 302 لمرايا `video-gen` إلى رابط الوسائط الداخلي.
        return coroutineScope {
            out.map { c ->
                async(Dispatchers.IO) {
                    if (!c.url.contains("video-gen.xyz")) return@async c
                    val inner = innerMedia(c.url, c.referer) ?: return@async null
                    c.copy(url = inner, referer = null)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun innerMedia(hopUrl: String, referer: String?): String? = try {
        val r = app.get(hopUrl, headers = head, referer = referer, allowRedirects = false)
        val loc = r.headers["Location"] ?: r.headers["location"] ?: return null
        URI(loc).rawQuery?.split("&")
            ?.firstOrNull { it.startsWith("url=") }
            ?.substringAfter("url=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        null
    }

    // ---------- أدوات ----------

    private suspend fun getText(
        url: String,
        referer: String? = null,
        tries: Int = 3,
    ): Pair<String, String>? {
        var wait = 700L
        for (i in 1..tries) {
            try {
                val r = app.get(url, headers = head, referer = referer, allowRedirects = true)
                if (r.isSuccessful) return r.text to r.url.toString()
                val code = r.code
                if (code == 429 || code >= 500) {
                    if (i < tries) {
                        delay(wait)
                        wait *= 2
                        continue
                    }
                }
                // 403 من Cloudflare على عميلٍ بعينه: محاولةٌ خامّ بجارٍ آخر
                // (HttpURLConnection) قد تمرّ — وهي أرخص من إسقاط الصفحة.
                rawGet(url, null, referer)?.let { raw ->
                    if (raw.code in 200..299 && raw.body.isNotBlank()) return raw.body to url
                    if (!raw.location.isNullOrBlank()) {
                        val next = absUrl(url, raw.location)
                        val again = rawGet(next, null, url)
                        if (again != null && again.code in 200..299 && again.body.isNotBlank()) {
                            return again.body to next
                        }
                    }
                }
                return null
            } catch (e: Exception) {
                if (i == tries) {
                    rawGet(url, null, referer)?.let { raw ->
                        if (raw.code in 200..299 && raw.body.isNotBlank()) return raw.body to url
                    }
                    return null
                }
                delay(wait)
                wait *= 2
            }
        }
        return null
    }

    /**
     * مسح صفحةٍ مباشرةً عن أي رابط وسائط/أرشيف — بلا اتباع بوّابة. يُستعمل
     * كخطةٍ بديلة حين تفشل السلسلة، فيبقى ما يمكن إنقاذه ظاهرًا للمستخدم.
     */
    suspend fun scanDirect(url: String, label: String? = null, quality: Int = 0): List<Link> {
        var current = url
        var referer: String? = null
        for (hop in 0..2) {
            val got = getText(current, referer = referer) ?: return emptyList()
            val body = got.first.replace("\\/", "/")
            val pageUrl = got.second
            val q = if (quality != 0) quality else qualityOf(label ?: body)
            val note = label?.trim()?.ifBlank { null }
            val out = ArrayList<Link>()
            val seen = HashSet<String>()

            fun add(u: String, server: String, ref: String?) {
                val clean = unescapeText(u).replace(RE_WS, "").trimEnd('.', ',', ')', ';')
                if (clean.isBlank() || !seen.add(clean)) return
                val dl = isArchiveUrl(clean) || clean.contains("googleusercontent.com", true) ||
                    note?.contains(".zip", true) == true || note?.contains(".rar", true) == true
                out.add(
                    Link(
                        url = clean,
                        server = server,
                        quality = q,
                        note = note,
                        referer = ref,
                        downloadOnly = dl,
                    )
                )
            }

            for (a in RE_ANCHOR.findAll(body)) {
                val href = RE_HREF.find(a.groupValues[1])?.groupValues?.get(1)
                    ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
                if (href.isBlank()) continue
                val text = stripTags(a.groupValues[2]).trim()
                val abs = absUrl(pageUrl, href)
                when {
                    href.contains(".r2.dev") -> add(abs, text.ifBlank { "Cloud Download" }, null)
                    href.contains("video-gen.xyz") -> add(abs, text.ifBlank { "Instant Download" }, pageUrl)
                    href.contains("workers.dev") -> add(abs, text.ifBlank { "Mirror" }, pageUrl)
                    href.contains("googleusercontent.com", true) ->
                        add(abs, text.ifBlank { "G-Drive" }, pageUrl)
                    Regex("\\.(mp4|mkv|avi|webm|m3u8|zip|rar|7z)(\\?|$)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(href) -> add(abs, text.ifBlank { "Direct" }, pageUrl)
                }
            }
            if (out.isEmpty()) {
                for (m in RE_R2_ANY.findAll(body)) add(m.value, "Cloud Download", null)
                for (m in RE_VG_ANY.findAll(body)) add(m.value, "Instant Download", pageUrl)
            }
            if (out.isNotEmpty()) {
                Log.i(TAG, "scanDirect: ${out.size} links من ${url.take(70)}")
                return out
            }
            val mv = jsTarget(body) ?: break
            val next = absUrl(pageUrl, mv)
            if (next == current) break
            current = next
            referer = pageUrl
        }
        return emptyList()
    }

    private fun anyForm(html: String): Pair<String, Map<String, String>>? {
        for (m in RE_FORM.findAll(html)) {
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val action = RE_ACTION.find(attrs)?.groupValues?.get(1)
                ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
            if (action.isBlank() || action == "#") continue
            val fields = RE_FIELD.findAll(inner)
                .associate { unescapeText(it.groupValues[1]) to unescapeText(it.groupValues[2]) }
            if (fields.isEmpty()) continue
            return action to fields
        }
        return null
    }

    private fun absUrl(base: String, link: String): String {
        val l = unescapeText(link).trim()
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        if (l.startsWith("//")) return "https:$l"
        return try {
            java.net.URI(base).resolve(l).toString()
        } catch (e: Exception) {
            val b = try {
                java.net.URI(base)
            } catch (e2: Exception) {
                null
            }
            if (l.startsWith("/") && b?.host != null) "${b.scheme}://${b.host}$l" else l
        }
    }

    /** أصل الموقع (`https://host`) — لبناء `?go=` و`?lp_go=` على الجذر لا على مسار المقال. */
    private fun rootOf(url: String): String = try {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}/"
    } catch (e: Exception) {
        url.substringBefore("?").trimEnd('/') + "/"
    }

    private fun isFinal(u: String): Boolean =
        u.contains("driveseed") && (
            u.contains("/file/") || u.contains("/list/") ||
                u.contains("/wfile/") || u.contains("/l?") || u.contains("/r?")
            )

    private fun normBody(body: String): String =
        unescapeText(body)
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=").replace("\\u003d", "=")
            .replace("\\x2F", "/").replace("\\x2f", "/")
            .replace("\\x26", "&")

    private fun driveseedLink(body: String): String? = driveseedLink(body, 0)

    private fun driveseedLink(body: String, depth: Int): String? {
        val b = normBody(body)
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/(?:file|list|wfile)/[^\"'\\s<>]+")
            .find(b)?.let { return it.value }
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/(?:l|r|zfile)\\?[^\"'\\s<>]+")
            .find(b)?.let { return it.value }
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/[^\"'\\s<>]*")
            .find(b)?.let { return it.value }
        Regex("(?:^|[\"'(\\s])/(?:file|list|wfile)/[A-Za-z0-9_-]{6,}[^\"'\\s<>]*").find(b)?.let {
            return "https://driveseed.org" +
                it.value.trimStart('"', '\'', '(', ' ', '\n', '\t')
        }
        if (depth < 2) {
            Regex("[A-Za-z0-9+/]{40,}={0,2}").findAll(b).take(6).forEach { m ->
                try {
                    val dec = String(Base64.decode(m.value, Base64.DEFAULT))
                    if (dec.contains("driveseed")) driveseedLink(dec, depth + 1)?.let { return it }
                } catch (e: Throwable) {
                }
            }
        }
        return null
    }

    private fun jsTarget(body: String): String? {
        val b = normBody(body)
        RE_JS_REDIRECT.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        RE_LOC.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        RE_META_REFRESH.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            "(?:window\\.|document\\.|top\\.|parent\\.|self\\.)?location(?:\\s*\\.\\s*href)?\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        ).find(b)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("window\\.open\\s*\\(\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(b)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun fileLinksOf(html: String): List<Pair<String, String>> =
        Regex(
            "<a[^>]+href=\"([^\"]*/file/[^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") to stripTags(it.groupValues[2]) }
            .filterNot { it.first.contains("/file/delete/") || it.first.contains("/file/reset/") }
            .distinctBy { it.first }
            .toList()

    private fun fileNameOf(html: String): String? {
        Regex("Name\\s*:\\s*([^<\\r\\n]{3,240})").find(html)
            ?.groupValues?.get(1)?.let { return unescapeText(it).trim().ifBlank { null } }
        return Regex("Name\\s*:\\s*([^\\s<]{3,240})")
            .find(html.replace(Regex("<[^>]+>"), " "))
            ?.groupValues?.get(1)
            ?.let { unescapeText(it).trim() }
            ?.ifBlank { null }
    }

    private fun sizeOf(html: String): Long? {
        val m = Regex(
            "Size\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)B",
            RegexOption.IGNORE_CASE,
        ).find(html.replace(Regex("<[^>]+>"), " ")) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2].uppercase()) {
            "T" -> 1024.0 * 1024 * 1024 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "M" -> 1024.0 * 1024
            "K" -> 1024.0
            else -> 1.0
        }
        return (v * mul).toLong()
    }

    private fun displayName(raw: String): String {
        val t = raw.trim()
        var n = t.substringBeforeLast('.', t)
        n = n.replace(Regex("(?i)\\.?MoviesMod(\\.[A-Za-z0-9]+)*$"), "")
        n = n.replace(Regex("(?i)\\.?(army|meme|com)$"), "")
        return n.trim().ifBlank { t }
    }

    fun isArchiveUrl(u: String): Boolean {
        val s = u.lowercase()
        return s.contains(".zip") || s.contains(".rar") || s.contains(".7z")
    }

    private fun isArchiveName(name: String): Boolean =
        name.contains(".zip", true) || name.contains(".rar", true) || name.contains(".7z", true)

    fun epFromText(text: String): Int? {
        if (text.isBlank()) return null
        Regex("\\bS\\d{1,2}[\\s._-]*E(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("\\bE(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return Regex("\\bEpisode\\s+(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    fun qualityOf(text: String): Int = when {
        RE_Q2160.containsMatchIn(text) -> Qualities.P2160.value
        RE_Q1440.containsMatchIn(text) -> Qualities.P1440.value
        RE_Q1080.containsMatchIn(text) -> Qualities.P1080.value
        RE_Q720.containsMatchIn(text) -> Qualities.P720.value
        RE_Q480.containsMatchIn(text) -> Qualities.P480.value
        RE_Q360.containsMatchIn(text) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    fun heightOf(quality: Int): Int = when (quality) {
        Qualities.P2160.value -> 2160
        Qualities.P1440.value -> 1440
        Qualities.P1080.value -> 1080
        Qualities.P720.value -> 720
        Qualities.P480.value -> 480
        Qualities.P360.value -> 360
        Qualities.P240.value -> 240
        Qualities.P144.value -> 144
        else -> 0
    }

    fun stripTags(s: String): String =
        unescapeText(s.replace(RE_TAGS, "")).replace(RE_WS, " ").trim()

    fun unescapeText(s: String): String =
        org.jsoup.parser.Parser.unescapeEntities(s, false).replace("\\/", "/")

    private val PINNED_SEEDS = listOf("cloudflare.com", "driveseed.org", "episodes.modpro.blog")

    /**
     * عناوين حافة Cloudflare العامة (anycast): تخدم أي نطاقٍ خلفها بشرط
     * `SNI` الصحيح، فلا حاجة لسؤال DNS عنها — وهذا يقطع الطريق على مُحلِّلٍ
     * محجوب يعيد عنوانًا مزيفًا.
     */
    private val CF_EDGE_IPS = listOf("104.16.132.229", "104.16.133.229", "172.67.131.141")

    private val RE_WS = Regex("\\s+")
    private val RE_TAGS = Regex("<[^>]+>")
    private val RE_ANCHOR = Regex("<a([^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val RE_HREF = Regex("href=\"([^\"]*)\"")
    private val RE_ACTION = Regex("action=\"([^\"]*)\"")
    private val RE_FIELD = Regex("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"")
    private val RE_FORM = Regex("<form([^>]*)>(.*?)</form>", RegexOption.DOT_MATCHES_ALL)

    private val RE_SC = Regex("sc\\(\"([^\"]+)\",\"([^\"]+)\",\\d+\\)")
    private val RE_VERIFY = Regex("s_343\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'")

    private val RE_JS_REDIRECT = Regex(
        "(?:window\\.)?(?:location|document\\.location|top\\.location)\\.(?:replace|href|assign)\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?",
        RegexOption.IGNORE_CASE,
    )
    private val RE_LOC = Regex(
        "location(?:\\.replace|\\.href|\\.assign)\\s*\\(\\s*[\"']([^\"']+)[\"']",
    )
    private val RE_META_REFRESH = Regex(
        "<meta[^>]+http-equiv=[\"']refresh[\"'][^>]+content=[\"'][^\"']*url=([^\"'>\\s]+)[\"']",
        RegexOption.IGNORE_CASE,
    )

    private val RE_R2_ANY = Regex("https://pub-[a-z0-9-]+\\.r2\\.dev/[^\"'<>\\s\\\\]+")
    private val RE_VG_ANY = Regex("https://(?:instant|cdn)\\.video-gen\\.xyz/[^\"'<>\\s\\\\]+")

    private val RE_Q2160 = Regex("\\b(2160p|4k)\\b", RegexOption.IGNORE_CASE)
    private val RE_Q1440 = Regex("\\b1440p\\b", RegexOption.IGNORE_CASE)
    private val RE_Q1080 = Regex("\\b1080p\\b", RegexOption.IGNORE_CASE)
    private val RE_Q720 = Regex("\\b720p\\b", RegexOption.IGNORE_CASE)
    private val RE_Q480 = Regex("\\b480p\\b", RegexOption.IGNORE_CASE)
    private val RE_Q360 = Regex("\\b360p\\b", RegexOption.IGNORE_CASE)
}
