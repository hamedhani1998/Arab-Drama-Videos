package com.sexalarabnet.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexAlArabNetPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SexAlArabNetProvider())
    }
}