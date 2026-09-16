package com.sexarabfun.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SexArabFunPlugin : Plugin() {
    override fun load() {
        registerMainAPI(SexArabFunProvider())
    }
}