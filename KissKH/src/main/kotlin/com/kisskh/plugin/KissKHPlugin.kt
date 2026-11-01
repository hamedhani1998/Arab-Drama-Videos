package com.kisskh.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KissKHProvider())
    }
}
