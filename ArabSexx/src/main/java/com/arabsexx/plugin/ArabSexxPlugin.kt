package com.arabsexx.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabSexxPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabSexxProvider())
    }
}
