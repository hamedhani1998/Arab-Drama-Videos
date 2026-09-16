package com.xnxx3rab.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XNXX3rabPlugin : Plugin() {
    override fun load() {
        registerMainAPI(XNXX3rabProvider())
    }
}