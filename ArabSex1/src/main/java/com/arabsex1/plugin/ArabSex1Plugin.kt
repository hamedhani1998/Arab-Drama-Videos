package com.arabsex1.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabSex1Plugin : Plugin() {
    override fun load() {
        registerMainAPI(ArabSex1Provider())
    }
}