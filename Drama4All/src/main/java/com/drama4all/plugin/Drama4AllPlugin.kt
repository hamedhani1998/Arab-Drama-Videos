package com.drama4all.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class Drama4AllPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («Drama4All») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث — تماماً كأسلوب NetShort.
        val prefs = context.getSharedPreferences(Drama4AllSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(Drama4AllProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) Drama4AllSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
