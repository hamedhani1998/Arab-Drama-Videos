package com.onshort.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OnShortPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OnShortProvider())
    }
}