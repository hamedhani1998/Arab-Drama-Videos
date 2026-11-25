package com.dramagaze.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaGazePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaGazeProvider())
    }
}