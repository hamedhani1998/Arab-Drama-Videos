package cloudstreamshared

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * خادم DASH محلي — يعرض **كل** جودات يوتيوب (وليس المدمجة فقط).
 *
 * لماذا؟ يوتيوب لا يقدّم اليوم إلا جودةً مدمجة واحدة غالبًا (360p أو 720p)؛
 * أما 1080p/1440p/4K فهي مسارات فيديو بلا صوت (DASH video-only). لو سلّمناها
 * للاعب مباشرةً ظهرت صامتة، ولو تجاهلناها ظهرت جودة واحدة فقط.
 *
 * الحلّ (نفس أسلوب خادم ARY العربية): نبني مانيفست DASH صغيرًا لكل جودة،
 * نضع فيه مسار الفيديو **ومعه** أفضل مسار صوت، ثم نقدّمه من `127.0.0.1`.
 * الـ BaseURL داخل المانيفست يشير إلى googlevideo مباشرةً، فتُجلب الوسائط
 * من يوتيوب بطلبات Range رسمية — وهي التي يقبلها.
 *
 * الخادم لا ينقل بايتات الوسائط: يخدم ملف `.mpd` النصّي فقط.
 */
data class YtVideoTrack(
    val url: String,
    val mimeType: String,
    val height: Int,
    val label: String,
    val initRange: String? = null,
    val indexRange: String? = null,
    val codec: String? = null,
)

data class YtAudioTrack(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val initRange: String? = null,
    val indexRange: String? = null,
    val language: String = "DEFAULT",
    val codec: String? = null,
)

object YoutubeDashServer {
    private const val TAG = "YoutubeDashServer"

    private class Entry(val xml: String, val at: Long)

    private val manifestMap = ConcurrentHashMap<String, Entry>()

    /** عمر المانيفست: روابط googlevideo موقّعة ومحدودة الصلاحية. */
    private const val MANIFEST_TTL_MS = 3L * 60 * 60 * 1000
    private const val MANIFEST_MAX = 60

    private var activeServer: ServerSocket? = null

    @Volatile
    private var serverPort = 0

    @Volatile
    private var loopAlive = false

    @Synchronized
    fun ensureStarted() {
        if (loopAlive && serverPort != 0) return
        try {
            runCatching { activeServer?.close() }
            val srv = ServerSocket(0)
            activeServer = srv
            serverPort = srv.localPort
            loopAlive = true
            thread(name = "yt-dash-server") {
                try {
                    while (activeServer === srv && !srv.isClosed) {
                        val client = srv.accept()
                        thread(name = "yt-dash-client") { handleClient(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                } finally {
                    if (activeServer === srv) loopAlive = false
                }
            }
            Log.d(TAG, "DASH server on port $serverPort")
        } catch (e: Exception) {
            loopAlive = false
            serverPort = 0
            Log.e(TAG, "could not start server: ${e.message}")
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        ensureStarted()
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = Entry(xmlContent, System.currentTimeMillis())
        prune()
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        manifestMap.entries.removeIf { now - it.value.at > MANIFEST_TTL_MS }
        while (manifestMap.size > MANIFEST_MAX) {
            val oldest = manifestMap.entries.minByOrNull { it.value.at }?.key ?: break
            manifestMap.remove(oldest)
        }
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine()
                // نقرأ الرأس كاملًا حتى السطر الفارغ: الإغلاق المبكر يجعل النظام
                // يرسل RST بدل FIN فيُمحى الرد قبل أن يقرأه اللاعب.
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path.trim()]?.xml
                        val out = socket.getOutputStream()
                        if (content != null) {
                            val bytes = content.toByteArray(Charsets.UTF_8)
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/dash+xml\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n\r\n"
                                    ).toByteArray(Charsets.UTF_8)
                            )
                            out.write(bytes)
                        } else {
                            out.write(
                                ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                            )
                        }
                        out.flush()
                        try {
                            socket.shutdownOutput()
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // اتصال مقطوع — يتجاهل
        }
    }

    private fun escapeXml(url: String): String =
        url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** mime النوع من محتوى الرابط (video/audio + webm/mp4). */
    fun mimeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) {
            if (isAudio) "audio/mp4" else "video/mp4"
        }
    }

    /** يبني مانيفست DASH (فيديو + صوت اختياري) ويسجّله، ويعيد رابط localhost. */
    fun buildAndRegister(
        video: YtVideoTrack,
        audioList: List<YtAudioTrack>,
        durationSec: Long,
    ): String? = registerManifestAndGetUrl(buildDashManifestXml(video, audioList, durationSec))

    /**
     * كوديك كتابي للمانيفست: نفضّل ما ورد فعلاً في mimeType، وإلا نرتدّ
     * للأسرة الصحيحة (مقطع صوت لا يجوز أن يحمل كوديك فيديو).
     */
    fun codecFor(mimeType: String, codec: String?): String {
        if (!codec.isNullOrBlank()) return codec
        return when {
            mimeType.startsWith("audio/") && mimeType.contains("webm") -> "opus"
            mimeType.startsWith("audio/") -> "mp4a.40.2"
            mimeType.contains("webm") -> "vp9"
            else -> "avc1.4d401f"
        }
    }

    /** mimeType من NewPipe قد يحمل كوديك («video/mp4; codecs="…"») — نفصله. */
    fun bareMime(mime: String): String = mime.substringBefore(";").trim().ifBlank { mime }

    private fun buildDashManifestXml(
        video: YtVideoTrack,
        audioList: List<YtAudioTrack>,
        durationSec: Long,
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder()
        sb.append(
            """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">"""
        )
        sb.append("<Period>")

        val vMime = bareMime(video.mimeType)
        val vCodecs = codecFor(vMime, video.codec)

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append(
            """
            <AdaptationSet mimeType="$vMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
            """.trimIndent()
        )

        audioList.forEachIndexed { index, audio ->
            val cleanAudioUrl = escapeXml(audio.url)
            val audioId = "audio_$index"
            val aMime = bareMime(audio.mimeType)
            val aCodecs = codecFor(aMime, audio.codec)

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append(
                """
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if (audio.bitrate > 0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$cleanAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
                """.trimIndent()
            )
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }
}
