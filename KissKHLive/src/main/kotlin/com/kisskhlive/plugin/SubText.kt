package com.kisskhlive.plugin

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ترجمات `kisskh.la` المُشفَّرة (ملفّات `*.txt1`).
 *
 * الموقع لا يقدّم SRT عاديًا في هذه الملفّات، بل **كل سطر ترجمةٍ مشفَّرٌ
 * وحده** بترميز Base64 ثم AES-128-CBC. والمفتاح والمتجه ثابتان لكل الموقع
 * (تحقّقنا على ملفّات مسلسلاتٍ مختلفة):
 *
 * ```js
 * a2(text) = AES.decrypt(text, Utf8.parse('AmSmZVcH93UQUezi'),
 *                             { iv: Utf8.parse('ReBKWW8cqdjPEnF6') })
 * ```
 *
 * أمّا ملفّات `*.srt` العادية فهي نصّ صريح ولا تُمسّ.
 */
object SubText {

    private const val KEY_TEXT = "AmSmZVcH93UQUezi"
    private const val IV_TEXT = "ReBKWW8cqdjPEnF6"

    private val KEY = SecretKeySpec(KEY_TEXT.toByteArray(Charsets.UTF_8), "AES")
    private val IV = IvParameterSpec(IV_TEXT.toByteArray(Charsets.UTF_8))

    private val CIPHER_BLOB = Regex("^[A-Za-z0-9+/]{16,}={0,2}$")
    private val TIMESTAMP = "-->" 

    /**
     * يحوّل ملفّ ترجمة (SRT أو الملفّ المشفّر نفسه) إلى WebVTT.
     *
     * @param encrypted true لملفّات `*.txt1` (فكّ سطرٍ سطرًا)، وfalse للنصّ
     * الصريح.
     */
    fun toVtt(raw: String, encrypted: Boolean): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder("WEBVTT\n\n")
        var i = 0
        while (i < lines.size) {
            val text = lines[i].trimEnd()
            val t = text.trim()
            if (t.isEmpty()) {
                i++
                continue
            }
            if (t.contains(TIMESTAMP)) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                out.append(t.replace(',', '.')).append('\n')
                i++
                continue
            }
            // رقم الترجمة في SRT: يُحذف فقط إذا تبعه سطر وقت (كي لا نحذف
            // سطر ترجمةٍ هو رقمٌ مجرّد).
            if (isIndex(t) && nextText(lines, i + 1).contains(TIMESTAMP)) {
                i++
                continue
            }
            out.append(if (encrypted) decryptLine(t) else t).append('\n')
            i++
        }
        return out.toString().trimEnd() + "\n"
    }

    /** يفكّ سطرًا واحدًا؛ وإن لم يكن Base64 صالحًا يُعيده كما هو. */
    private fun decryptLine(line: String): String {
        if (!CIPHER_BLOB.matches(line)) return line
        return try {
            val pad = (4 - line.length % 4) % 4
            val bytes = Base64.decode(line + "=".repeat(pad), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, KEY, IV)
            String(cipher.doFinal(bytes), Charsets.UTF_8)
        } catch (e: Exception) {
            line
        }
    }

    private fun isIndex(s: String): Boolean =
        s.isNotEmpty() && s.length <= 6 && s.all { it.isDigit() }

    private fun nextText(lines: List<String>, from: Int): String {
        var i = from
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isNotEmpty()) return t
            i++
        }
        return ""
    }
}
