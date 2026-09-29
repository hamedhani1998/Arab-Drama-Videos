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
 * خادم ترجمة محلي — نسخة مطابقة لـ `PakiSrtServer` في إضافة pakistanilive
 * (نفس النمط، نفس الغرض، مُختبَر في هذا المستودع).
 *
 * لماذا لا نمرّر رابط الترجمة إلى المشغّل مباشرة؟ سببان اثنان، وكلاهما
 * مُقاس على جهاز المستخدم:
 *
 *  1) المشغّل (ExoPlayer عبر Cronet/HTTP2) يطلب الترجمة بنفس مكدّس يوتيوب،
 *     وقد يموت على جهازٍ تختلف شبكته عن الخادم. الجلب عبر
 *     `HttpURLConnection` (HTTP/1.1) يردّ سليماً في الحالات التي يقتل فيها
 *     Cronet الطلب — وهذا ما يوثّقه `MosSubServer` لمصدر netshort.
 *
 *  2) رابط `timedtext` موقَّع ومحدود الصلاحية (`expire`). تسليم الرابط
 *     للمشغّل يعني أن يوتيوب يخدمه لاحقاً مباشرةً بلا وسيط. الخادم
 *     المحلي يجلب النص الآن ويُخزّنه، فيقرأه المشغّل بلا اعتماد على
 *     يوتيوب أثناء التشغيل.
 *
 * ولأن `SubtitleFile` لا يحمل سوى `lang/url/headers` (لا حقل محتوى)، ولأن
 * المشغّل يرفض عناوين `data:`، فلا مخرجَ إلا رابطٌ حقيقي — وهذا ما يقدّمه.
 *
 * نُقدّم WebVTT لا SRT: أزواج توقيت SRT (`00:00:00,000 --> ...`) متوافقة
 * مع VTT، لكن اللاعب يميّز مسار الترجمة عبر `text/vtt` و`.vtt`.
 */
object ArySubServer {
    private const val TAG = "ArySubServer"

    /**
     * نصوص الترجمة المسجّلة: `معرّف → (النص، وقت تسجيله)`.
     *
     * نفس سبب التنظيف في `AryDashServer`: الخريطة بلا حدّ ولا عمر فتنمو
     * مع كل حلقة مشاهَدة. ونصّ الترجمة يُقرأ مرة واحدة عند اختياره ثم
     * لا يُحتاج، فبقياؤه بعد مدة إهدار للذاكرة فقط.
     */
    private class Entry(val vtt: String, val at: Long)

    private val vttMap = ConcurrentHashMap<String, Entry>()

    /** عمر النص المحفوظ: ساعة تكفي (الترجمة تُقرأ عند الاختيار فوراً). */
    private const val VTT_TTL_MS = 60L * 60 * 1000
    /** أقصى عدد نصوص محفوظة؛ الزائد يُمسح من الأقدم. */
    private const val VTT_MAX = 40
    private var activeServer: ServerSocket? = null
    @Volatile private var serverPort = 0

    /** كما في AryDashServer: موت حلقة القبول صامت بلا هذا العلم. */
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
            thread(name = "ary-sub-server") {
                try {
                    while (activeServer === srv && !srv.isClosed) {
                        val client = srv.accept()
                        thread(name = "ary-sub-client") { handleClient(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                } finally {
                    if (activeServer === srv) loopAlive = false
                }
            }
            Log.d(TAG, "subtitle server on port $serverPort")
        } catch (e: Exception) {
            loopAlive = false
            serverPort = 0
            Log.e(TAG, "could not start server: ${e.message}")
        }
    }

    /**
     * يحوّل SRT إلى WebVTT. نطلب `fmt=vtt` من يوتيوب فيأتي WebVTT أصلاً،
     * فالدالة احتياط فقط: لو أعاد يوتيوب SRT (بلا ترويسة `WEBVTT`) فهي
     * صيغة نعرف تحويلها. ولا نُفقد الترويسة إن وُجدت.
     */
    fun toVtt(body: String): String {
        if (body.trimStart().startsWith("WEBVTT")) return body
        val sb = StringBuilder()
        var num = -1
        for (rawLine in body.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() && num >= 0) { num = -1; sb.append('\n') }
            if (num < 0 && line.toIntOrNull() != null) { num = line.toInt(); continue }
            sb.append(if (line.contains("-->")) line.replace(',', '.') else line).append('\n')
        }
        return "WEBVTT\n\n" + sb.toString()
    }

    /**
     * يسجّل نص الترجمة ويعيد رابط 127.0.0.1 يُسلَّم للمشغّل، أو `null`
     * إن كان النص فارغاً أو الخادم لم يبدأ — ولا نُصدر رابطاً ميتاً.
     */
    fun register(body: String): String? {
        val text = body.trim()
        if (text.isEmpty()) return null
        ensureStarted()
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        vttMap[id] = Entry(toVtt(text), System.currentTimeMillis())
        prune()
        return "http://127.0.0.1:$serverPort/$id.vtt"
    }

    /** يُسقط ما تجاوز عمره أو الحدّ — يمنع التسرّب عبر جلسة طويلة. */
    private fun prune() {
        val now = System.currentTimeMillis()
        // نفس ملاحظة AryDashServer: لا remove عبر iterator.
        vttMap.entries.removeIf { now - it.value.at > VTT_TTL_MS }
        while (vttMap.size > VTT_MAX) {
            val oldest = vttMap.entries.minByOrNull { it.value.at }?.key ?: break
            vttMap.remove(oldest)
        }
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine()
                // ★ نقرأ الرأس كاملاً حتى السطر الفارغ حتى لا يُرسل النظام
                //   RST بدل FIN فيُمحى الرد قبل قراءته (خطأ 2002 متقطّع).
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        val raw = parts[1].substringBefore('?')
                        var path = runCatching { URLDecoder.decode(raw.substring(1), "UTF-8") }
                            .getOrElse { raw.substring(1) }
                        if (path.endsWith(".vtt")) path = path.removeSuffix(".vtt")
                        val content = vttMap[path.trim()]?.vtt
                        val out = socket.getOutputStream()
                        if (content != null) {
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/vtt; charset=utf-8\r\n")
                                append("Content-Length: ${content.toByteArray(Charsets.UTF_8).size}\r\n")
                                append("Connection: close\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("\r\n")
                            }
                            out.write(head.toByteArray(Charsets.UTF_8))
                            out.write(content.toByteArray(Charsets.UTF_8))
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray(Charsets.UTF_8))
                        }
                        out.flush()
                        // FIN نظيف بعد الرد كاملاً، لا RST يقطعه.
                        try { socket.shutdownOutput() } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) {
            // عميل أُغلق — يتجاهل
        }
    }
}
