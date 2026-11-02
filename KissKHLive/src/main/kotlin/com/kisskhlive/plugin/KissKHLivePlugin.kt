package com.kisskhlive.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KissKHLiveProvider())
    }
}
