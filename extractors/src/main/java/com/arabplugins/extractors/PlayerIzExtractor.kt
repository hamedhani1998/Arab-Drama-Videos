package com.arabplugins.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PlayerIzExtractor : ExtractorApi() {
    override var name = "PlayerIz"
    override var mainUrl = "https://playeriz.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = referer ?: mainUrl).document
            
            // Method 1: Look for video source in scripts
            val allScript = doc.select("script").joinToString("\n") { it.data() }
            
            // Try to find video_url in the script
            val videoUrlMatch = Regex("""video_url\s*[:=]\s*['"]([^'"]+)['"]""").find(allScript)
            if (videoUrlMatch != null) {
                val videoUrl = decodeUrl(videoUrlMatch.groupValues[1])
                callback(newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                })
                return
            }

            // Method 2: Look for m3u8 URL
            val m3u8Match = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(allScript)
            if (m3u8Match != null) {
                callback(newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: mainUrl
                })
                return
            }

            // Method 3: Look for mp4 URL
            val mp4Match = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(allScript)
            if (mp4Match != null) {
                callback(newExtractorLink(
                    source = name,
                    name = name,
                    url = mp4Match.groupValues[1],
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                })
                return
            }

            // Method 4: Look for video source tags
            doc.select("video source").forEach { source ->
                val srcUrl = source.attr("src")
                if (srcUrl.isNotBlank() && (srcUrl.contains(".mp4") || srcUrl.contains(".m3u8"))) {
                    val type = if (srcUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(
                        source = name,
                        name = name,
                        url = srcUrl,
                        type = type
                    ) {
                        this.referer = referer ?: mainUrl
                    })
                    return
                }
            }

            // Method 5: Look for iframe
            val iframe = doc.selectFirst("iframe[src]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    loadExtractor(iframeUrl, referer, subtitleCallback, callback)
                    return
                }
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    private fun decodeUrl(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try {
                    val base64 = url.removePrefix("function/0/")
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                } catch (_: Exception) { url.removePrefix("function/0/") }
            }
            else -> url
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") -> "https://${decoded.removePrefix("https/")}"
            else -> decoded
        }
    }
}