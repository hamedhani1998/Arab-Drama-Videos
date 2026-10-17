package com.dramamondo.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DM_MAIN = "https://dramamondo.com"

class DramaMondoProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = DM_MAIN
    override var name = "DramaMondo"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val HOME_KEY = "__home__"

    override val mainPage = mainPageOf(
        HOME_KEY to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$mainUrl/api.php?t=${System.currentTimeMillis()}").text
        val json = try { mapper.readTree(res) as JsonNode } catch (e: Exception) { mapper.readTree("[]") }
        val items = mutableListOf<SearchResponse>()
        if (json.isArray) {
            json.forEach { it ->
                val slug = it["slug"]?.asText() ?: ""
                val title = it["title"]?.asText() ?: ""
                val poster = it["poster"]?.asText() ?: ""
                if (title.isNotEmpty()) {
                    val detailUrl = "$mainUrl/$slug"
                    items.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        }
        return newHomePageResponse(listOf(HomePageList("الأحدث", items.take(60))), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/api.php?t=${System.currentTimeMillis()}").text
        val json = try { mapper.readTree(res) as JsonNode } catch (e: Exception) { mapper.readTree("[]") }
        val items = mutableListOf<SearchResponse>()
        val q = query.lowercase()
        if (json.isArray) {
            json.forEach { it ->
                val title = it["title"]?.asText() ?: ""
                val slug = it["slug"]?.asText() ?: ""
                val poster = it["poster"]?.asText() ?: ""
                if (title.isNotEmpty() && (title.lowercase().contains(q) || slug.contains(q))) {
                    val detailUrl = "$mainUrl/$slug"
                    items.add(newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast("/")
        val res = app.get("$mainUrl/api.php?t=${System.currentTimeMillis()}").text
        val json = try { mapper.readTree(res) as JsonNode } catch (e: Exception) { mapper.readTree("[]") }
        var target: JsonNode? = null
        if (json.isArray) {
            json.forEach { it ->
                if (it["slug"]?.asText() == slug) { target = it; return@forEach }
            }
        }
        val obj = target ?: json.firstOrNull() ?: mapper.readTree("{}")
        val title = obj["title"]?.asText() ?: "DramaMondo"
        val poster = obj["poster"]?.asText() ?: ""
        val synopsis = obj["synopsis"]?.asText() ?: obj["description"]?.asText() ?: ""
        val episodes = mutableListOf<Episode>()
        val epsList = obj["episodesList"]
        if (epsList != null && epsList.isArray && epsList.size() > 0) {
            epsList.forEachIndexed { idx, e ->
                val servers = e["servers"]
                if (servers != null && servers.isArray && servers.size() > 0) {
                    val surl = servers[0]["url"]?.asText() ?: ""
                    if (surl.isNotEmpty()) {
                        val epNum = (e["number"]?.asInt() ?: (idx + 1))
                        episodes.add(newEpisode(surl) {
                            this.name = "الحلقة $epNum"
                            this.episode = epNum
                        })
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            val parts = obj["parts"]
            if (parts != null && parts.isArray && parts.size() > 0) {
                parts.forEachIndexed { pidx, part ->
                    val servers = part["servers"]
                    if (servers != null && servers.isArray && servers.size() > 0) {
                        val surl = servers[0]["url"]?.asText() ?: ""
                        if (surl.isNotEmpty()) {
                            episodes.add(newEpisode(surl) {
                                this.name = "الحلقة ${pidx + 1}"
                                this.episode = pidx + 1
                            })
                        }
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            val servers = obj["servers"]
            if (servers != null && servers.isArray) {
                servers.forEachIndexed { idx, s ->
                    val surl = s["url"]?.asText() ?: ""
                    if (surl.isNotEmpty()) {
                        episodes.add(newEpisode(surl) {
                            this.name = "الحلقة ${idx + 1}"
                            this.episode = idx + 1
                        })
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "الحلقة 1"
                this.episode = 1
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith(DM_MAIN) || data.startsWith("http")) {
            val rawUrl = data
            try {
                loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
                return true
            } catch (_: Throwable) {
            }
            val name = when {
                rawUrl.contains("rumble.com") -> "Rumble"
                rawUrl.contains("voe.sx") -> "Voe"
                rawUrl.contains("vidara.") -> "Vidara"
                rawUrl.contains("netshort") -> "NetShort"
                rawUrl.contains("dramabox") -> "Dramabox"
                rawUrl.contains("dramawave") -> "DramaWave"
                rawUrl.contains("reelshort") -> "ReelShort"
                rawUrl.contains("shorttv") -> "ShortTV"
                rawUrl.contains("flextv") -> "FlexTV"
                rawUrl.contains("shortmax") -> "ShortMax"
                rawUrl.contains("flickreels") -> "FlickReels"
                rawUrl.contains("moboreels") -> "MoboReels"
                rawUrl.contains("shortwave") -> "ShortWave"
                rawUrl.contains("stardust") -> "StarDust"
                rawUrl.contains("petadrama") -> "PetaDrama"
                rawUrl.contains("storyreel") -> "StoryReel"
                rawUrl.contains("kalostv") -> "KalosTV"
                rawUrl.contains("joyreels") -> "JoyReels"
                rawUrl.contains("goodshort") -> "GoodShort"
                rawUrl.contains("moreshort") -> "MoreShort"
                else -> "DramaMondo"
            }
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = rawUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
        return false
    }
}
