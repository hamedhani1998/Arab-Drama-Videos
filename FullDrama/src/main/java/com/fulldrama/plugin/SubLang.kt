package com.fulldrama.plugin

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile

internal object SubLang {
    private const val TAG = "FDSubLang"

    fun filter(list: List<SubtitleFile>, raw: String?): List<SubtitleFile> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return list
        val wanted = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return list
        val res = list.filter { it.lang?.lowercase() in wanted }
        if (res.isEmpty()) Log.d(TAG, "sub_filter: none matched, returning original (wanted=$wanted)")
        return res.ifEmpty { list }
    }

    fun bestAr(list: List<SubtitleFile>): SubtitleFile? {
        val ar = list.find { it.lang?.contains("ar", true) == true }
        return ar ?: list.firstOrNull()
    }
}
