package com.dramavideoshow.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «DramaVideoShow». تُعرض من زر الإعدادات في CloudStream، وتُحفظ
 * في ملف تفضيلات خاص بالوحدة (تنميط مثل Dramadunyam).
 */
class DramaVideoShowSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = android.view.View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        /** اسم ملف التفضيلات — يطابق ما يمرّره [DramaVideoShowPlugin]. */
        const val PREFS_NAME = "DramaVideoShow"

        // مفاتيح هذه الوحدة (بادئة فريدة).
        const val KEY_SHOW_HOME = "dvs_show_home"        // Boolean — الواجهة الرئيسية
        const val KEY_SHOW_LIST_LABEL = "dvs_show_list_label"  // Boolean — وسوم [M3U8]… في الاسم

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaVideoShowSettings(prefs).show(fm, "dvs_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val homeCategory = PreferenceCategory(ctx)
            homeCategory.title = "الواجهة الرئيسية"
            preferenceScreen.addPreference(homeCategory)

            // إظهار الصفحة الرئيسية (أقسام الموقع). `hasMainPage` خاصية ديناميكية
            // فيُسحَب المصدر من الصفحة الرئيسية أو يُخفى فوراً عند التبديل.
            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "أقسام «أحدث الإصدارات» وغيرها"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showHomePref)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // عراة وسم الصيغة في اسم السيرفر: [M3U8]/[MP4]/[DASH]. الموقع يبثّ
            // m3u8 دائماً، لكن الوسم يُظهر الصيغة بوضوح في قائمة السيرفرات.
            val showListLabelPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_LIST_LABEL
                title = "إظهار صيغة السيرفر في الاسم"
                summary = "يضيف [MP4] أو [M3U8] أو [DASH] إلى اسم خادم التشغيل"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showListLabelPref)
        }
    }
}