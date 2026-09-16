package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.*
import org.jsoup.Jsoup
import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import android.util.Log

/**
 * ThreeSk (قصة عشق) — Turkish drama, Arabic dub/sub. Site: https://3iskk.xyz
 *
 * Why NOT app.get (NiceHttp): NiceHttp/OkHttp IDN-converts the .xyz TLD on Android
 * (3iskk.xn--xyz-...) → UnknownHostException → empty main page.
 * Why NOT plain HttpURLConnection-by-hostname: some phone networks/DNS fail to
 * resolve .xyz even though the PC resolves it fine.
 *
 * Fix: own OkHttpClient whose Dns resolves 3iskk.xyz via DNS-over-HTTPS (no OS
 * DNS needed) and pins those IPs, sending proper SNI. Bypasses both the punycode
 * bug and the device's broken .xyz resolution.
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
        // last-known Cloudflare A records for 3iskk.xyz (DoH refresh overrides)
        val FALLBACK_IPS = listOf("104.21.71.36", "172.67.169.118")
    }

    override val mainPage = mainPageOf(
        "مسلسلات"        to "/w-srs/",
        "مدبلجة"        to "/genre/series-mudablij-121/",
        "أحدث الحلقات"  to "/w-hlqat/",
        "أفلام"          to "/w-mvs/",
    )

    // ── Trust-all SSL ─────────────────────────────────────────────────────
    private val unsafeTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val unsafeSSL: SSLContext by lazy {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf(unsafeTrustManager), SecureRandom())
            sc
        } catch (e: Exception) { Log.e(TAG, "SSLContext init failed", e); SSLContext.getDefault() }
    }

    // ── Codebase DNS: host -> pinned IPs (via DoH; falls back to system) ──
    private val ipCache = HashMap<String, List<InetAddress>>()

    /** Resolve a host's A records without the OS resolver — via DoH JSON. */
    private fun resolveViaDoh(host: String): List<String> {
        return try {
            val url = "https://cloudflare-dns.com/dns-query?name=$host&type=A"
            val req = Request.Builder()
                .url(url)
                .header("accept", "application/dns-json")
                .header("user-agent", UA)
                .build()
            // plain client (system DNS) on purpose — cloudflare-dns.com always resolves
            plainClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return emptyList()
                Regex("\"data\":\"(\\d{1,3}(\\.\\d{1,3}){3})\"").findAll(text)
                    .map { it.groupValues[1] }.distinct().toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH failed for $host: ${e.message}")
            emptyList()
        }
    }

    private val pinnedDns = Dns { host ->
        if (host.endsWith("3iskk.xyz", ignoreCase = true)) {
            ipCache[host]?.let { return@Dns it }
            val ips = resolveViaDoh(host)
                .ifEmpty { FALLBACK_IPS }
            val addrs = ips.mapNotNull { ip ->
                try { InetAddress.getByName(ip) } catch (_: Exception) { null }
            }
            if (addrs.isEmpty()) Dns.SYSTEM.lookup(host) else {
                ipCache[host] = addrs
                Log.d(TAG, "pinned $host -> ${addrs.joinToString { it.hostAddress }}")
                addrs
            }
        } else Dns.SYSTEM.lookup(host)
    }

    /** Plain client for DoH bootstrap. */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(unsafeSSL.socketFactory, unsafeTrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /** Main client — pins 3iskk.xyz via DoH, sends SNI properly. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(pinnedDns)
            .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(unsafeSSL.socketFactory, unsafeTrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun reqBuilder(url: String, referer: String?): Request.Builder {
        return Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ar,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
    }

    private fun safeGet(url: String, referer: String? = null): String? = try {
        val req = reqBuilder(url, referer).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            Log.d(TAG, "GET $url -> ${resp.code} (${body?.length ?: 0} chars)")
            if (resp.isSuccessful) body else {
                Log.w(TAG, "GET $url HTTP ${resp.code}"); null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "GET $url EXCEPTION: ${e.message}", e); null
    }

    private fun safePost(url: String, data: Map<String, String>, referer: String? = null): String? = try {
        val body = FormBody.Builder().apply {
            data.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = reqBuilder(url, referer).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string()
            Log.d(TAG, "POST $url -> ${resp.code} (${text?.length ?: 0} chars)")
            if (resp.isSuccessful) text else {
                Log.w(TAG, "POST $url HTTP ${resp.code}"); null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "POST $url EXCEPTION: ${e.message}", e); null
    }

    // ── Card parsing ─────────────────────────────────────────────────────
    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        var cards = doc.select("li.type_item_box a.type_item").mapNotNull { a ->
            val href  = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.attr("title").ifBlank { a.selectFirst(".item_title")?.text() }
                ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") } }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
        if (cards.isEmpty()) {
            cards = doc.select("a.type_item[href]").mapNotNull { a ->
                val href  = a.attr("href").ifBlank { return@mapNotNull null }
                val title = a.attr("title").ifBlank { a.text().takeIf { it.isNotBlank() } }
                    ?: return@mapNotNull null
                val poster = a.selectFirst("img")?.let {
                    it.attr("data-image").ifBlank { it.attr("src") } }
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
        Log.d(TAG, "parseCards: ${cards.size} cards")
        return cards
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl${request.data.trimEnd('/')}/page/$page/"
            Log.d(TAG, "getMainPage [$page] ${request.name} -> $url")
            val html = safeGet(url, mainUrl) ?: run {
                Log.e(TAG, "getMainPage: null HTML"); return null
            }
            val list = parseCards(html)
            if (list.isEmpty()) { Log.w(TAG, "getMainPage: 0 cards"); null }
            else newHomePageResponse(request.name, list)
        } catch (e: Exception) { Log.e(TAG, "getMainPage EXCEPTION: ${e.message}", e); null }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/${java.net.URLEncoder.encode(query.trim(), "UTF-8")}/"
            Log.d(TAG, "search: $url")
            val html = safeGet(url, mainUrl) ?: return emptyList()
            parseCards(html)
        } catch (e: Exception) { Log.e(TAG, "search EXCEPTION: ${e.message}", e); emptyList() }
    }

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
                    if (epUrl.isNotBlank())
                        episodes.add(newEpisode(epUrl) {
                            this.name    = epNum?.let { "الحلقة $it" }
                            this.season  = 1
                            this.episode = epNum
                        })
                }
            }
            Log.d(TAG, "load: title=$title episodes=${episodes.size}")
            if (episodes.isEmpty()) null
            else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
            }
        } catch (e: Exception) { Log.e(TAG, "load EXCEPTION: ${e.message}", e); null }
    }

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
            // Step 1: episode page -> gateway form (MUST be aa.3isk.icu, not the search box)
            val epHtml     = safeGet(url, mainUrl) ?: return false
            val doc        = Jsoup.parse(epHtml)
            val formAction = doc.selectFirst("form[action*=\"aa.3isk.icu\"]")?.attr("action") ?: run {
                Log.e(TAG, "loadLinks: gateway form aa.3isk.icu not found"); return false
            }
            val newsValue  = doc.selectFirst("input[name=news]")?.attr("value") ?: run {
                Log.e(TAG, "loadLinks: input[name=news] not found"); return false
            }
            Log.d(TAG, "loadLinks step1: form=$formAction news=${newsValue.take(30)}...")

            val middleHtml = safePost(formAction,
                mapOf("news" to newsValue, "u" to "", "submit" to "submit"), url) ?: return false
            val myUrl   = Regex("""var myUrl\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "loadLinks: myUrl not found"); return false
            }
            val inputVl = Regex("""myInput\.value\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "loadLinks: myInput.value not found"); return false
            }
            Log.d(TAG, "loadLinks step2: myUrl=$myUrl")

            val finalHtml = safePost(myUrl,
                mapOf("news" to inputVl, "u" to "", "submit" to "submit"), formAction) ?: return false
            val embedM = Regex("""(?:https?://[\w.-]+)?/embed/\d+/(\d+)/(\d+)/""")
                .find(finalHtml) ?: run {
                Log.e(TAG, "loadLinks: embed URL not found"); return false
            }
            val postId = embedM.groupValues[1]
            val typeId = embedM.groupValues[2]
            Log.d(TAG, "loadLinks step3: postId=$postId typeId=$typeId")

            var emitted = 0
            val seenHost = mutableSetOf<String>()
            for (t in 1..6) {
                val embedUrl  = "$mainUrl/embed/$t/$postId/$typeId/"
                val embedHtml = safeGet(embedUrl, url) ?: continue
                val playerUrl = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(embedHtml)?.groupValues?.get(1) ?: continue
                val host = try { java.net.URL(playerUrl).host } catch (_: Exception) { continue }
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
            Log.d(TAG, "loadLinks done: $emitted links")
            emitted > 0
        } catch (e: Exception) { Log.e(TAG, "loadLinks EXCEPTION: ${e.message}", e); false }
    }

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
            ?: run { Log.w(TAG, "resolveUkrcdn: master URL missing"); return false }
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