package com.kisskhlive.plugin

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * وسيطٌ محليّ صغير يعرض **الترجمة المفكوكة** كأنّها ملفّ على الشبكة.
 *
 * لماذا: `kisskh.la` يسلّم ملفّات `*.txt1` مشفّرة سطرًا سطرًا، و
 * `newSubtitleFile` في CloudStream لا يقبل إلا **رابطًا** (لا نصًّا جاهزًا —
 * Issue #2336). فنفتح مقبسًا على `127.0.0.1` بمنفذٍ عشوائيّ، وننشر لكل
 * ترجمةٍ مسارًا يبني محتواها عند الطلب: تنزيل ← فكّ ([SubText]) ← WebVTT.
 *
 * ولا يكلّف ذلك وقتًا عند فتح الروابط: البناء **كسول**، لا يقع إلا حين يختار
 * المستخدم الترجمة فعلًا.
 */
object SubProxy {

    private const val TAG = "KissKHSubProxy"

    /** مسار ← دالّة تبني المحتوى (null يعني 404). */
    private val routes = ConcurrentHashMap<String, () -> String?>()

    @Volatile
    private var port = 0

    /** ينشر محتوى تحت [token] ويُعيد رابطه المحلي، أو null إن تعذّر البدء. */
    fun publish(token: String, build: () -> String?): String? {
        val p = ensurePort() ?: return null
        // سقفٌ بسيط كي لا تتضخّم الخريطة مع الاستعمال الطويل.
        if (routes.size > 400) routes.clear()
        routes[token] = build
        return "http://127.0.0.1:$p/s/$token"
    }

    private fun ensurePort(): Int? {
        if (port != 0) return port
        synchronized(this) {
            if (port != 0) return port
            return try {
                val server = try {
                    ServerSocket(0, 40, InetAddress.getByName("127.0.0.1"))
                } catch (e: Exception) {
                    // بعض أجهزة Android ترفض الربط الصريح بالحلقة المحلية.
                    ServerSocket(0)
                }
                val p = server.localPort
                port = p
                thread(isDaemon = true, name = "kisskh-sub-proxy") {
                    while (true) {
                        val socket = try {
                            server.accept()
                        } catch (e: Exception) {
                            break
                        }
                        try {
                            serve(socket)
                        } catch (e: Exception) {
                            Log.i(TAG, "serve FAIL ${e.javaClass.simpleName}")
                        } finally {
                            try {
                                socket.close()
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                Log.i(TAG, "listening on 127.0.0.1:$p")
                p
            } catch (e: Exception) {
                Log.i(TAG, "start FAIL ${e.javaClass.simpleName} ${e.message}")
                null
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        // استنزاف الرؤوس حتى السطر الفارغ.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val token = path.removePrefix("/s/").substringBefore('?').substringBefore('#')
        val body = routes[token]?.invoke()

        val out = socket.getOutputStream()
        if (body == null) {
            out.write(
                ("HTTP/1.1 404 Not Found\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            )
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/vtt; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
        }
        out.flush()
    }
}
