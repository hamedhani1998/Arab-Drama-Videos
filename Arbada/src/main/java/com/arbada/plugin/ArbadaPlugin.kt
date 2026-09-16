package com.arbada.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArbadaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArbadaProvider())
    }
}
