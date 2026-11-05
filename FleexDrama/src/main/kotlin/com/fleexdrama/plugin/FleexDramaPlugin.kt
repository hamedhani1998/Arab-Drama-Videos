package com.fleexdrama.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FleexDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // بلا إعدادات: المصدر يعرض كل سيرفرات الصفحة وكل الجودات وكل الترجمات
        // افتراضيًا، فلا حاجة لملف تفضيلات.
        registerMainAPI(FleexDramaProvider())
    }
}
