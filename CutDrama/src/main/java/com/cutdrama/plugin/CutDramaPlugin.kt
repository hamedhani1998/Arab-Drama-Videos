package com.cutdrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CutDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // بلا إعدادات: لا مفتاح ولا توكن ولا خيار عرض. كلّ ما تقرأه الإضافة
        // علني على `/api` بلا جلسة، فلا شيء يحتاج لوحةً لتغييره.
        registerMainAPI(CutDramaProvider())
    }
}
