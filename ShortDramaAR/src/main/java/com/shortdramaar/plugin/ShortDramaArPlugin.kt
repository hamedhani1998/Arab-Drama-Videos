package com.shortdramaar.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * إضافة «دراما قصيرة» — اثنتا عشرة قناة يوتيوب لدراما قصيرة مدبلجة ومترجمة.
 *
 * مستقلة تماماً عن إضافة `aryarabia`: حزمة Kotlin مختلفة (`com.shortdramaar`)
 * واسم ملف تفضيلات مختلف (`SHORTDRAMA` لا `ARY`)، فلا تختلط إعدادات
 * الإضافتين ولا يتعارض خادما DASH والترجمة المحليان. كل منطق التشغيل
 * والاستخراج مطابق لمصدر ARY (NewPipe + DASH محلي + ترجمة محلية) — راجع
 * `ShortDramaProvider`.
 */
@CloudstreamPlugin
class ShortDramaArPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("SHORTDRAMA", Context.MODE_PRIVATE)
        registerMainAPI(ShortDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                ShortDramaSettings.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}
