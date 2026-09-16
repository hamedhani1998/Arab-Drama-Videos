package com.zebzob.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ZebzobPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ZebzobProvider())
    }
}
