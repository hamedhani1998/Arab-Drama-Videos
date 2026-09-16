package com.threesk.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ThreeSkPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ThreeSk())
    }
}
