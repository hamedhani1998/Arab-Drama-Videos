package com.kirmzi.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KirmziPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KirmziProvider())
    }
}