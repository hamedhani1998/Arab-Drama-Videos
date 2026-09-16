package com.arabx.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabxCamPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabxCamProvider())
    }
}
