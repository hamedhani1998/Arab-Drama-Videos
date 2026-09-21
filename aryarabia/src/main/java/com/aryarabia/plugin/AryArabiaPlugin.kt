package com.aryarabia.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AryArabiaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AryProvider())
    }
}
