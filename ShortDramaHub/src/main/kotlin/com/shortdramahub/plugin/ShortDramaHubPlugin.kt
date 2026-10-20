package com.shortdramahub.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * إضافة «شورت دراما هب» — فهرس أفلامٍ قصيرة مدبلجة على `shortdramahub.world`.
 *
 * مستقلة تماماً عن إضافة «دراما قصيرة»: حزمة Kotlin مختلفة
 * (`com.shortdramahub`) واسم ملف تفضيلات مختلف (`SHORTDRAMAHUB` لا
 * `SHORTDRAMA`)، فلا تختلط إعدادات الإضافتين ولا يتعارض خادما DASH
 * والترجمة المحليان (كلٌّ يفتح مقبساً بمنفذٍ يختاره النظام).
 *
 * منطق التشغيل والاستخراج مطابق لـ«دراما قصيرة» — وكلتاهما على نمط ARY:
 * NewPipe + DASH محلي + ترجمة محلية. التفصيل في `ShortDramaHubProvider`.
 */
@CloudstreamPlugin
class ShortDramaHubPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(
            ShortDramaHubSettings.PREFS_NAME, Context.MODE_PRIVATE
        )
        registerMainAPI(ShortDramaHubProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                ShortDramaHubSettings.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}
