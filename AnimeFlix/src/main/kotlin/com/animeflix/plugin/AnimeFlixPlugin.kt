package com.animeflix.plugin

import android.content.Context
import cloudstreamshared.DriveChain
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFlixPlugin : Plugin() {
    override fun load(context: Context) {
        // تعويض DNS لمرايا `video-gen` و`workers.dev` قبل أي طلب.
        DriveChain.installDns()
        registerMainAPI(AnimeFlixProvider())
    }
}
