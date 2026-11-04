package com.daramly.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DaramlyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DaramlyProvider())
    }
}
