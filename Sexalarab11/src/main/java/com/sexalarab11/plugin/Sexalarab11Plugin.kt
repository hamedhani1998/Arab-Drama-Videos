package com.sexalarab11.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Sexalarab11Plugin : Plugin() {
    override fun load() {
        registerMainAPI(Sexalarab11Provider())
    }
}
