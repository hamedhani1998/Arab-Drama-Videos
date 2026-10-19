package com.aryarabia.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
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

    /**
     * المانيفستات المسجّلة: `معرّف → (النص، وقت تسجيله)`.
     *
     * كان `ConcurrentHashMap<String, String>` بلا حدّ ولا عمر: كل حلقة تُشاهَد
     * تترك مانيفستها للأبد. أثران حقيقيان:
     *  1) روابط `googlevideo` داخله **موقّعة ومحدودة الصلاحية** (`expire`)،
     *     فتصير ميتة بعد ساعات بينما المانيفست ما زال مُخدَماً — واللاعب
     *     يطلب مانيفستاً صحيحاً فيه وسائط محذوفة فيتعثّر عند التشغيل.
     *  2) نموّ الذاكرة بلا سقف يضغط التطبيق حتى يقتله النظام، فيظهر
     *     الانقطاع فجأةً بلا رسالة (وهو «أحياناً» الذي شكا منه المستخدم).
     * فلا يُحتفظ إلا بما يستحق، ويُمسح الأقدم عند الحاجة.
     */
    private class Entry(val xml: String, val at: Long)

    private val manifestMap = ConcurrentHashMap<String, Entry>()

    /** عمر المانيفست: بعده يُرفض ويُمسح، لأن روابطه الموقّعة صارت ميتة. */
    private const val MANIFEST_TTL_MS = 3L * 60 * 60 * 1000
    /** أقصى عدد مانيفستات محفوظة؛ الزائد يُمسح من الأقدم. */
    private const val MANIFEST_MAX = 60
    private var activeServer: ServerSocket? = null
    @Volatile private var serverPort = 0

    /**
     * هل حلقة القبول عادية فعلاً؟ لا يكفي أن `ServerSocket` غير مغلق: إن
     * ماتت الحلقة من استثناء عابر بقي المقبس مفتوحاً، فكان فحص
     * `isClosed` وحده يظنّ أن الخادم حيّ فيعود `ensureStarted` بلا إعادة
     * تشغيل — فلا يقبل أحد الاتصالات، ويتصل المشغّل فلا يجد خادماً أبداً
     * فيدور بلا نهاية. فالحياوية تُشتق من الحلقة نفسها.
     */
    @Volatile private var loopAlive = false

    @Synchronized
    fun ensureStarted() {
        if (loopAlive && serverPort != 0) return
        try {
            runCatching { activeServer?.close() }
            val srv = ServerSocket(0)
            activeServer = srv
            serverPort = srv.localPort
            loopAlive = true
            thread(name = "ary-dash-server") {
                try {
                    while (activeServer === srv && !srv.isClosed) {
                        val client = srv.accept()
                        thread(name = "ary-dash-client") { handleClient(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                } finally {
                    // ★ العطل يصير صامتاً بلا هذا السطر: يبقى الخادم
                    //   «مفتوحاً» فلا يُعاد تشغيله، فلا يستجيب لأحد بعدها.
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
        // ★ نضمن البدء هنا أيضاً: كان يُفترض أن المتطّلع سبقه، فإن كانت
        //   الحلقة ميّتة في تلك اللحظة صارت كل الروابط `null` وصار
        //   التشغيل يدور بلا روابط.
        ensureStarted()
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = Entry(xmlContent, System.currentTimeMillis())
        prune()
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    /**
     * يُسقط ما تجاوز عمره أو الحدّ. تجاوز العمر أولاً: روابطه الموقّعة
     * صارت ميتة، وتقديم ميت أسوأ من 404 — فهو يقرأ اللاعب المانيفست
     * السليم ثم يتعثّر عند الوسائط. وتجاوز العدد يمنع تضخّم الذاكرة الذي
     * يُنهي العملية قسراً (وهو «أحياناً» الذي شكا منه المستخدم).
     */
    private fun prune() {
        val now = System.currentTimeMillis()
        // ★ `entries.iterator()` من java.util لا يدعم remove (يرمي
        //   UnsupportedOperationException)، فالمسح الصحيح removeIf.
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
                // ★ نقرأ رأس الطلب كاملاً حتى السطر الفارغ. الإبقاء على
                //   الترويسات في مخزن الاستقبال ثم الإغلاق يجعل النظام
                //   يُرسل RST بدل FIN، فيُمحى الرد قبل أن يقرأه اللاعب —
                //   وهو سبب تعذّر متقطّع (خطأ 2002) رغم صحة الخادم.
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
                        val head: String
                        val body: String
                        if (content != null) {
                            val bytes = content.toByteArray(Charsets.UTF_8)
                            head = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/dash+xml\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n" +
                                "Access-Control-Allow-Origin: *\r\n\r\n"
                            body = content
                            out.write(head.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        } else {
                            head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            body = ""
                            out.write(head.toByteArray(Charsets.UTF_8))
                        }
                        out.flush()
                        try { socket.shutdownOutput() } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            // اتصال مقطوع — يتجاهل
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
     * av01.*, mp4a.40.2 …) — يعرّفه اللاعب بلا تخمين — وإلا نرتد للأسرة
     * الصحيحة: مقطع فيديو → avc1/vp9، مقطع صوت → mp4a.40.2/opus.
     * (ضع كوديك فيديو على مقطع صوت يجعل ExoPlayer يوجّهه لمُعترف فيديو
     * فيسقط FfmpegVideoRenderer NPE — الخلل الذي أصلحه this.)
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