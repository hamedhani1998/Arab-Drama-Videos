package com.arabplugins.extractors

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class KVSFlashvarsExtractor : ExtractorApi() {
    override var name = "KVS Flashvars"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Decode function/0/ URLs
            val decodedUrl = decodeUrl(url)
            
            // Try to get video URL from the decoded URL
            if (decodedUrl.contains(".mp4") || decodedUrl.contains(".m3u8")) {
                val type = if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(
                    source = name,
                    name = name,
                    url = decodedUrl,
                    type = type
                ) {
                    this.referer = referer ?: mainUrl
                })
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
                    Base64.decode(base64, Base64.DEFAULT).toString(Charsets.UTF_8)
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