package com.pakistanilive.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * خادم ترجمة محلي — نمط AryDashServer/MosSubServer المُثبت.
 *
 * لماذا لا نمرّر نص SRT مباشرة للمشغّل؟ لأن لاعب CloudStream يرفض عناوين
 * `data:` للترجمة، و[SubtitleFile] لا يحمل سوى `lang/url/headers` (لا حقل محتوى)،
 * والـ plugin بلا Context لكتابة ملفٍ مؤقت. لذلك نسجّل نص الترجمة (المطمور داخل
 * `initCustomPlayer("video1","<yt>", \`SRT\`)` في صفحة الحلقة) على خادم
 * `http://127.0.0.1:port/{uuid}.vtt` ونعطي المشغّل هذا الرابط.
 *
 * نُقدّمها WebVTT (وليس SRT) لأن لاعب CloudStream يميّز مسار الترجمة عبر
 * `text/vtt`/.vtt — أزواج توقيت SRT (`00:00:00,000 --> ...`) متوافقة مع VTT.
 */
object PakiSrtServer {
    private const val TAG = "PakiSrtServer"

    private val srtMap = ConcurrentHashMap<String, String>()
    private var activeServer: ServerSocket? = null
    @Volatile private var serverPort = 0

    @Synchronized
    fun ensureStarted() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            val srv = ServerSocket(0)
            activeServer = srv
            serverPort = srv.localPort
            thread(name = "paki-srt-server") {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = srv.accept()
                        thread(name = "paki-srt-client") { handleClient(client) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "server loop exited: ${e.message}")
                }
            }
            Log.d(TAG, "SRT server on port $serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "could not start server: ${e.message}")
        }
    }

    /** SRT → WebVTT: يزيح أسطر الأرقام، يحوّل `,` إلى `.` في أسطر التوقيت (VTT يتطلّبه)، ويضع ترويسة. */
    private fun toVtt(srt: String): String {
        val sb = StringBuilder()
        var num = -1
        for (rawLine in srt.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty() && num >= 0) { num = -1; sb.append('\n') }
            if (num < 0 && line.toIntOrNull() != null) { num = line.toInt(); continue }
            sb.append(if (line.contains("-->")) line.replace(',', '.') else line).append('\n')
        }
        return "WEBVTT\n\n" + sb.toString()
    }

    /** يسجّل نص SRT ويعيد رابط 127.0.0.1 يُسلَّم للمشغّل (أو null عند الفشل). */
    fun register(srtText: String): String? {
        ensureStarted()
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        srtMap[id] = toVtt(srtText)
        return "http://127.0.0.1:$serverPort/$id.vtt"
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".vtt")) path = path.replace(".vtt", "")
                        val content = srtMap[path.trim()]
                        val out = socket.getOutputStream()
                        if (content != null) {
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/vtt; charset=utf-8\r\n")
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
                    }
                }
            }
        } catch (e: Exception) {
            // اتصال مغلق — يتجاهل
        }
    }
}