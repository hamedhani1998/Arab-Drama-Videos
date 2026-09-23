package com.aryarabia.plugin

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «ARY العربية». BottomSheet تُعرض من زر الإعدادات أو من قائمة
 * «إعدادات الإضافات» في CloudStream. كل خياراتها `SharedPreferences` مفتوحة —
 * القراءة مباشرة وبدون تخزين مركزي — تماماً كأسلوب re-3arabi، بحيث تلتقطها
 * `resolveFromNewPipe`/`resolveFromHtml` عند كل تشغيل.
 *
 * حالياً تنقر على <خيارات التشغيل> الفعلي: أي مسار يوتيوب نستخدم وكم جودة
 * نُظهر. أضف المزيد بلا كسر (كل مفتاح يستقل بذاته).
 */
class ArySettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        const val KEY_PLAYBACK_MODE = "ary_playback_mode"     // "newpipe" | "direct" | "extractor"
        const val KEY_MAX_QUALITY = "ary_max_quality"         // "all" | "high"
        const val KEY_REDIRECT = "ary_use_redirect"           // Boolean بديل النطاق

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ArySettingsBottomSheet(prefs).show(fm, "ary_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // أسلوب الاستخراج لروابط يوتيوب
            val modePref = ListPreference(ctx).apply {
                key = KEY_PLAYBACK_MODE
                title = "مسار التشغيل"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("newpipe", "direct", "extractor")
                entries = arrayOf(
                    "NewPipe + DASH (موصى به)",
                    "روابط HTML المباشرة",
                    "مستخرج CloudStream المدمج"
                )
                setDefaultValue("newpipe")
            }
            playbackCategory.addPreference(modePref)

            // الجودات: الكل أم الأعلى فقط
            val qualityPref = ListPreference(ctx).apply {
                key = KEY_MAX_QUALITY
                title = "الجودات"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "high")
                entries = arrayOf("كل الجودات", "الأعلى فقط (أسرع)")
                setDefaultValue("all")
            }
            playbackCategory.addPreference(qualityPref)

            // النطاق البديل (redirector)
            val redirectPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_REDIRECT
                title = "نطاق بديل (redirector)"
                summary = "قد يساعد على الشبكات التي تحجب مضيف rr*—sn-*"
                setDefaultValue(false)
            }
            playbackCategory.addPreference(redirectPref)

            // زر إعادة تسجيل بيانات — احتياطي.
            val resetPref = Preference(ctx).apply {
                key = "ary_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد كل الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_PLAYBACK_MODE); remove(KEY_MAX_QUALITY); remove(KEY_REDIRECT)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}