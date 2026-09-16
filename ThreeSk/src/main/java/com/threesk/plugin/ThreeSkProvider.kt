package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import java.security.SecureRandom
import java.security.cert.X509Certificate
import android.util.Log

/**
 * ThreeSk (قصة عشق) — Turkish drama, Arabic dub/sub.
 *
 * Site: https://3iskk.xyz
 * NiceHttp (OkHttp) converts .xyz to punycode on Android → UnknownHostException.
 * Uses raw HttpURLConnection + trust-all SSLContext instead.
 * All calls logged to logcat tag "ThreeSk" for diagnostics.
 */
class ThreeSk : MainAPI() {
    override var name      = "قصة عشق"
    override var mainUrl   = "https://3iskk.xyz"
    override var hasMainPage = true
    override var lang      = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private companion object {
        const val TAG = "ThreeSk"
        const val UA  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "مسلسلات"        to "/w-srs/",
        "مدبلجة"        to "/genre/series-mudablij-121/",
        "أحدث الحلقات"  to "/w-hlqat/",
        "أفلام"          to "/w-mvs/",
    )

    // ── Trust-all SSL + hostname verifier (belt-and-suspenders for .xyz) ──
    private val unsafeSSL: SSLContext by lazy {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
            sc
        } catch (e: Exception) { Log.e(TAG, "SSLContext init failed", e); SSLContext.getDefault() }
    }
    private val trustAllHosts = HostnameVerifier { _, _ -> true }

    // ── Low-level HTTP helpers ────────────────────────────────────────────
    private fun openConnection(url: String, method: String = "GET"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 25_000
        conn.readTimeout    = 25_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "ar,en;q=0.9")
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = unsafeSSL.socketFactory
            conn.hostnameVerifier = trustAllHosts
        }
        return conn
    }

    /** Read response body, auto-detecting gzip */
    private fun readBody(conn: HttpURLConnection): String {
        val stream: InputStream = when {
            conn.responseCode in 200..399 -> conn.inputStream
            else -> conn.errorStream ?: return ""
        }
        val decoded = if (conn.contentEncoding?.contains("gzip", true) == true)
            GZIPInputStream(stream) else stream
        return decoded.bufferedReader().use { it.readText() }
    }

    private fun safeGet(url: String, referer: String? = null): String? = try {
        val conn = openConnection(url)
        if (referer != null) conn.setRequestProperty("Referer", referer)
        conn.connect()
        val code = conn.responseCode
        val body = readBody(conn)
        Log.d(TAG, "GET $url → $code (${body.length} chars)")
        if (code in 200..399) body else { Log.w(TAG, "GET $url failed HTTP $code"); null }
    } catch (e: Exception) {
        Log.e(TAG, "GET $url EXCEPTION: ${e.message}", e); null
    }

    private fun safePost(url: String, data: Map<String, String>, referer: String? = null): String? = try {
        val conn = openConnection(url, "POST")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        conn.connect()
        val body = data.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val resp = readBody(conn)
        Log.d(TAG, "POST $url → $code (${resp.length} chars)")
        if (code in 200..399) resp else { Log.w(TAG, "POST $url failed HTTP $code"); null }
    } catch (e: Exception) {
        Log.e(TAG, "POST $url EXCEPTION: ${e.message}", e); null
    }

    // ── Card parsing ─────────────────────────────────────────────────────
    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        // Primary: li.type_item_box a.type_item
        var cards = doc.select("li.type_item_box a.type_item").mapNotNull { a ->
            val href  = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.attr("title").ifBlank { a.selectFirst(".item_title")?.text() }
                ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") } }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
        // Fallback: any a with class containing type_item (in case markup changed)
        if (cards.isEmpty()) {
            cards = doc.select("a.type_item[href], a[class*=type_item][href]").mapNotNull { a ->
                val href  = a.attr("href").ifBlank { return@mapNotNull null }
                val title = a.attr("title").ifBlank { a.text().takeIf { t -> t.isNotBlank() } }
                    ?: return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-image").ifBlank { it.attr("src") } }
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
        Log.d(TAG, "parseCards: ${cards.size} cards found in ${html.length} chars")
        return cards
    }

    // ── getMainPage ──────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl${request.data.trimEnd('/')}/page/$page/"
            Log.d(TAG, "getMainPage [$page] ${request.name} → $url")
            val html = safeGet(url, mainUrl)
            if (html == null) { Log.e(TAG, "getMainPage: HTML is null"); return null }
            val list = parseCards(html)
            if (list.isEmpty()) { Log.w(TAG, "getMainPage: 0 cards"); return null }
            newHomePageResponse(request.name, list)
        } catch (e: Exception) { Log.e(TAG, "getMainPage EXCEPTION: ${e.message}", e); null }
    }

    // ── search ───────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/${URLEncoder.encode(query.trim(), "UTF-8")}/"
            Log.d(TAG, "search: $url")
            val html = safeGet(url, mainUrl) ?: return emptyList()
            parseCards(html)
        } catch (e: Exception) { Log.e(TAG, "search EXCEPTION: ${e.message}", e); emptyList() }
    }

    // ── load (series detail → episodes) ──────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        return try {
            Log.d(TAG, "load: $url")
            val html = safeGet(url, mainUrl) ?: return null
            val doc  = Jsoup.parse(html)

            val title = doc.selectFirst("h1.title, .title")?.text()
                ?: doc.title().substringBefore("موقع").trim()

            val poster = doc.selectFirst(".poster-wrapper img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") } }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

            val description = doc.selectFirst(".description")?.text()

            val episodes = mutableListOf<Episode>()
            doc.select(".seasons-wrapper .seasons-selection ul li[data-value]").forEach { seasonLi ->
                val seasonNum = seasonLi.attr("data-value").toIntOrNull() ?: return@forEach
                doc.select("#season-num-$seasonNum a.ep-num").forEach { a ->
                    val epUrl = a.attr("href")
                    val epNum = a.attr("data-ep-num").toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    val epTitle = a.selectFirst(".cl_srt")?.text()
                        ?: a.attr("title").ifBlank { null }
                        ?: "الحلقة $epNum"
                    if (epUrl.isNotBlank())
                        episodes.add(newEpisode(epUrl) {
                            this.name    = epTitle
                            this.season  = seasonNum
                            this.episode = epNum
                        })
                }
            }
            if (episodes.isEmpty()) {
                doc.select("a.ep-num[href*=episodes]").forEach { a ->
                    val epUrl = a.attr("href")
                    val epNum = a.attr("data-ep-num").toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    val epTitle = a.selectFirst(".cl_srt")?.text()
                        ?: a.attr("title").ifBlank { null }
                        ?: "الحلقة $epNum"
                    if (epUrl.isNotBlank())
                        episodes.add(newEpisode(epUrl) {
                            this.name    = epTitle
                            this.season  = 1
                            this.episode = epNum
                        })
                }
            }
            Log.d(TAG, "load: title=$title, episodes=${episodes.size}")
            if (episodes.isEmpty()) null
            else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
            }
        } catch (e: Exception) { Log.e(TAG, "load EXCEPTION: ${e.message}", e); null }
    }

    // ── loadLinks ────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val qa = Regex("""(?:season-(\d+))?.*?episode-(\d+)(?:/|$)""").find(url)
        val epLabel = if (qa != null && qa.groupValues[2].isNotBlank()) " EP${qa.groupValues[2]}" else ""

        return try {
            Log.d(TAG, "loadLinks: $url")
            // Step 1: episode page → gateway form ⚠️ must be aa.3isk.icu, NOT search form
            val epHtml     = safeGet(url, mainUrl) ?: return false
            val doc        = Jsoup.parse(epHtml)
            val formAction = doc.selectFirst("form[action*=\"aa.3isk.icu\"]")?.attr("action") ?: run {
                Log.e(TAG, "loadLinks: gateway form aa.3isk.icu not found"); return false
            }
            val newsValue  = doc.selectFirst("input[name=news]")?.attr("value") ?: run {
                Log.e(TAG, "loadLinks: input[name=news] not found"); return false
            }
            Log.d(TAG, "loadLinks step1: form=$formAction, news=${newsValue.take(30)}...")

            // Step 2: POST → middle page
            val middleHtml = safePost(formAction,
                mapOf("news" to newsValue, "u" to "", "submit" to "submit"), url) ?: return false

            val myUrl   = Regex("""var myUrl\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "loadLinks: myUrl not found in middle page"); return false
            }
            val inputVl = Regex("""myInput\.value\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "loadLinks: myInput.value not found"); return false
            }
            Log.d(TAG, "loadLinks step2: myUrl=$myUrl")

            // Step 3: POST → final page with iframe
            val finalHtml = safePost(myUrl,
                mapOf("news" to inputVl, "u" to "", "submit" to "submit"), formAction) ?: return false

            // Parse the embed URL → postid + type
            val embedM = Regex("""(?:https?://[\w.-]+)?/embed/\d+/(\d+)/(\d+)/""")
                .find(finalHtml) ?: run {
                Log.e(TAG, "loadLinks: embed URL not found in final page"); return false
            }
            val postId = embedM.groupValues[1]
            val typeId = embedM.groupValues[2]
            Log.d(TAG, "loadLinks step3: postId=$postId typeId=$typeId")

            var emitted = 0
            val seenHost = mutableSetOf<String>()

            // Enumerate servers: embed/{t}/{pid}/{type}
            for (t in 1..6) {
                val embedUrl  = "$mainUrl/embed/$t/$postId/$typeId/"
                val embedHtml = safeGet(embedUrl, url) ?: continue

                val playerUrl = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(embedHtml)?.groupValues?.get(1) ?: continue
                val host = try { URL(playerUrl).host } catch (_: Exception) { continue }
                if (host.isBlank() || !seenHost.add(host)) continue
                Log.d(TAG, "loadLinks server $t: host=$host player=$playerUrl")

                when {
                    host.contains("ukrcdn") -> {
                        if (resolveUkrcdn(playerUrl, epLabel, callback)) emitted++
                    }
                    else -> {
                        callback(newExtractorLink(name, "سيرفر ${seenHost.size}$epLabel", embedUrl, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.headers = mapOf(
                                "User-Agent" to UA,
                                "Referer" to "$mainUrl/"
                            )
                        })
                        emitted++
                    }
                }
            }
            Log.d(TAG, "loadLinks done: $emitted links emitted")
            emitted > 0
        } catch (e: Exception) { Log.e(TAG, "loadLinks EXCEPTION: ${e.message}", e); false }
    }

    /**
     * ukrcdn.club → direct master.m3u8 (CF-free).
     */
    private suspend fun resolveUkrcdn(
        pageUrl: String,
        epLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = safeGet(pageUrl, mainUrl) ?: return false
        val apiUrl = Regex("""https?://[^"\s]+/api/videos/[^"\s]+/playback[^"\s]*""")
            .find(page)?.value?.replace("\\/", "/") ?: run {
            Log.w(TAG, "resolveUkrcdn: playback API not found"); return false
        }
        val json = safeGet(apiUrl, pageUrl) ?: return false
        val master = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: run {
            Log.w(TAG, "resolveUkrcdn: master URL not in JSON"); return false
            }

        Log.d(TAG, "resolveUkrcdn: master=$master")
        callback(newExtractorLink(name, "سيرفر ukrcdn$epLabel · HLS", master, ExtractorLinkType.M3U8) {
            this.referer = "https://ukrcdn.club/"
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://ukrcdn.club/"
            )
        })
        return true
    }
}
