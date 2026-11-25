package com.dramix.plugin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramixProvider())
    }
}