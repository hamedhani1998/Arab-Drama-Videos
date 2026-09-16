package com.arabhubx.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabHubXPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabHubXProvider())
    }
}