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
 * خادم ترجمة محلي — يقدّم نص WebVTT من `127.0.0.1` بدل تسليم المشغّل رابط
 * يوتيوب مباشرةً.
 *
 * لماذا؟
 *  1) `SubtitleFile` لا يحمل محتوىً، والمشغّل يرفض عناوين `data:` — فلا مخرج
 *     إلا رابط حقيقي.
 *  2) روابط `timedtext` موقّعة ومحدودة الصلاحية، وقد يقتل مكدّس المشغّل
 *     (Cronet) الطلب على أجهزة/شبكات بعينها. الجلب عبر HTTP/1.1 هنا ثم
 *     التخزين المحلي يضمن وصول النص كما هو.
 *  3) يوتيوب يردّ XML افتراضيًا؛ نحن نحوّله إلى WebVTT قبل التسليم، فلا
 *     يعرض المشغّل نصًا خامًا أو في موضعٍ خاطئ.
 */
object YoutubeSubServer {
    private const val TAG = "YoutubeSubServer"

    private class Entry(val vtt: String, val at: Long)

    private val vttMap = ConcurrentHashMap<String, Entry>()

    private const val VTT_TTL_MS = 60L * 60 * 1000
    private const val VTT_MAX = 40

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
            thread(name = "yt-sub-server") {
                try {
                    while (activeServer === srv && !srv.isClosed) {
                        val client = srv.accept()
                        thread(name = "yt-sub-client") { handleClient(client) }
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

    /** يسجّل نص WebVTT ويعيد رابط 127.0.0.1، أو null إن كان النص فارغًا. */
    fun register(vtt: String): String? {
        val text = vtt.trim()
        if (text.isEmpty()) return null
        ensureStarted()
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        vttMap[id] = Entry(text, System.currentTimeMillis())
        prune()
        return "http://127.0.0.1:$serverPort/$id.vtt"
    }

    private fun prune() {
        val now = System.currentTimeMillis()
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
                // نقرأ الرأس كاملًا حتى السطر الفارغ: الإغلاق المبكر يجعل النظام
                // يرسل RST بدل FIN فيُمحى الرد قبل أن يقرأه المشغّل.
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
                            val bytes = content.toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/vtt; charset=utf-8\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Connection: close\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("\r\n")
                            }
                            out.write(head.toByteArray(Charsets.UTF_8))
                            out.write(bytes)
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray(Charsets.UTF_8))
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
            // عميل أُغلق — يتجاهل
        }
    }
}
