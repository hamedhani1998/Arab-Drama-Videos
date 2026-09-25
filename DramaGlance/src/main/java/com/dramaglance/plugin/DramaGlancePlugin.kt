package com.dramaglance.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaGlancePlugin : Plugin() {
    override fun load(context: Context) {
        // DramaGlance لا يحتاج إعدادات: مصدر واحد بلا تفضيلات (راجع DramaGlanceProvider).
        registerMainAPI(DramaGlanceProvider())
    }
}
