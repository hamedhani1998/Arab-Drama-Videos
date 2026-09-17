package com.drama4all.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Drama4AllPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Drama4AllProvider())
    }
}