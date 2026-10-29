package com.moviesleech.plugin

import android.content.Context
import cloudstreamshared.DriveChain
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesLeechPlugin : Plugin() {
    override fun load(context: Context) {
        DriveChain.installDns()
        registerMainAPI(MoviesLeechProvider())
    }
}
