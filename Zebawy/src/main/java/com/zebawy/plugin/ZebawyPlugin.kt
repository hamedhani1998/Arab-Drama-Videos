package com.zebawy.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ZebawyPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ZebawyProvider())
    }
}
