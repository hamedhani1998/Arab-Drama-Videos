package com.arabsexxx.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabSexXxxPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabSexXxxProvider())
    }
}