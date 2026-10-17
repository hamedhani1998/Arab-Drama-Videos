package com.threesk.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class ThreeSkPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («ThreeSk») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث.
        val prefs = context.getSharedPreferences(ThreeSkSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(ThreeSk(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) ThreeSkSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
