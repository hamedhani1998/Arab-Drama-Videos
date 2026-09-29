package com.aryarabia.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * خادم DASH محلي — نسخة مطابقة لأسلوب «يوتيوب» في إضافة re-3arabi.
 *
 * لماذا يعمل عبر localhost بدل تسليم رابط googlevideo مباشرة للاعب كما كان
 * سابقاً؟ لأن `fetchPage()` من NewPipe يعطي روابط googlevideo **مفكوكة**
 * (توقيع + معامل n)، والمانيفست المُبنى هنا يتضمّن `SegmentBase` مع نطاقات
 * Initialization/Index، فيطلب اللاعب الوسائط على هيئة **Range requests**
 * رسمية للمضيف الذي وقّع عليه — فيقبلها يوتيوب حتى من عميل يوتيوب شكله
 * اصطناعياً (Cronet). دون فك التوقيع/النطاقات كان يوتيوب يرفض 403/2004.
 *
 * الخادم يقدّم المانيفست فقط (`http://127.0.0.1:port/{uuid}.mpd`)؛
 * BaseURL داخله يشير إلى googlevideo مباشرة، فيجلب اللاعب الوسائط من يوتيوب.
 */
data class StreamInfo(
    val url: String,
    val mimeType: String,
    val height: Int,
    val label: String,
    val initRange: String? = null,
    val indexRange: String? = null,
    /** كوديك حقيقي من NewPipe (avc1…/vp09…/av01…) — يُكتب في المانيفست. */
    val codec: String? = null
)

data class AudioInfo(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val initRange: String? = null,
    val indexRange: String? = null,
    val language: String = "DEFAULT",
    val codec: String? = null
)

object AryDashServer {
    private const val TAG = "AryDashServer"

    private val manifestMap = ConcurrentHashMap<String, String>()
    private var activeServer: ServerSocket? = null
    @Volatile private var serverPort = 0

    @Synchronized
    fun ensureStarted() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            val srv = ServerSocket(0)
            activeServer = srv
            serverPort = srv.localPort
            thread(name = "ary-dash-server") {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = srv.accept()
                        thread(name = "ary-dash-client") { handleClient(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                }
            }
            Log.d(TAG, "DASH server on port $serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "could not start server: ${e.message}")
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path.trim()]
                        if (content != null) {
                            output.println("HTTP/1.1 200 OK")
                            output.println("Content-Type: application/dash+xml")
                            output.println("Connection: close")
                            output.println("Access-Control-Allow-Origin: *")
                            output.println("")
                            output.println(content)
                        } else {
                            output.println("HTTP/1.1 404 Not Found")
                            output.println("")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // اتصال مغلق — يتجاهل
        }
    }

    private fun escapeXml(url: String): String =
        url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** يحدد mime النوع من محتوى الرابط (video/audio + webm/mp4). */
    fun mimeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) { if (isAudio) "audio/mp4" else "video/mp4" }
    }

    /** يبني مانيفست DASH فيديو+صوت (مع SegmentBase) ثم يسجّله ويعيد رابط localhost. */
    fun buildAndRegister(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String? = registerManifestAndGetUrl(buildDashManifestXml(video, audioList, durationSec))

    /**
     * كوديكٌ كتابي للمانيفست: نفضّل ما أعطاه NewPipe فعلاً (avc1.*, vp09.*,
     * av01.*, av1 …) — يعرّفه اللاعب بلا تخمين — وإلا نرتد للأسرة.
     */
    fun codecFor(mimeType: String, codec: String?): String {
        if (!codec.isNullOrBlank()) return codec
        return if (mimeType.contains("webm")) "vp9" else "avc1.4d401f"
    }

    /**
     * mimeType من NewPipe قد يحمل كوديك («video/mp4; codecs="avc1.…"») — نبعثره
     * فيسمة منفصلة. تُفرَّد صفة mimeType بقيمة خام (video/mp4) وإلا انكسرت XML إن
     * وُضعت الكوديك داخل عبارة الاقتباس.
     */
    fun bareMime(mime: String): String =
        mime.substringBefore(";").trim().ifBlank { mime }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder()
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vMime = bareMime(video.mimeType)
        val vCodecs = codecFor(vMime, video.codec)

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="$vMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val cleanAudioUrl = escapeXml(audio.url)
            val audioId = "audio_$index"
            val aMime = bareMime(audio.mimeType)
            val aCodecs = codecFor(aMime, audio.codec) // mp4a.40.2 / opus / …

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$cleanAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }
}