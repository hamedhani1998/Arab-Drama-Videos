package com.humarabia.plugin

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «هم العربية». BottomSheet تُعرض من زر الإعدادات أو من قائمة
 * «إعدادات الإضافات». كل الخيارات `SharedPreferences` مفتوحة — القراءة مباشرة
 * (كما aryarabia) بحيث تلتقطها `resolveFromNewPipe` عند كل تشغيل.
 */
class HumSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يطابق ما يمرّره HumPlugin للمصدر.
        const val PREFS_NAME = "HUM"
        const val KEY_PLAYBACK_MODE = "hum_playback_mode"     // "newpipe" | "extractor"
        const val KEY_MAX_QUALITY = "hum_max_quality"         // "all" | "high"
        const val KEY_QUALITY_ORDER = "hum_quality_order"     // "default" | "asc" | "desc"
        const val KEY_EXTRA_CHANNELS = "hum_extra_channels"  // أسطرٌ متعددة: رابط/معرّف قناة لكل سطر (فارغ = الافتراضي)

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            HumSettingsBottomSheet(prefs).show(fm, "hum_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ اجعل الورقة تكتب في ملف التفضيلات نفسه الذي يقرأه HumProvider.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // مسار الاستخراج لروابط يوتيوب
            val modePref = ListPreference(ctx).apply {
                key = KEY_PLAYBACK_MODE
                title = "مسار التشغيل"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("newpipe", "extractor")
                entries = arrayOf(
                    "NewPipe + DASH (موصى به)",
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

            // ترتيب الجودات عند البث — الافتراضي = ترتيب اليوم تماماً.
            val orderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "افتراضي: نفس ترتيب روابط التشغيل كما هي"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(orderPref)

            // قنوات يوتيوب إضافية (اختياري) — تظهر كلٌّ منها بقسمٍ خاص في
            // الرئيسية وتشملها نتائج البحث. الافتراضي فارغ = سلوك اليوم تماماً.
            val extraChannelPref = EditTextPreference(ctx).apply {
                key = KEY_EXTRA_CHANNELS
                title = "قنوات يوتيوب إضافية (اختياري)"
                summary = "ألصق رابط قناة لكل سطر (مثال https://youtube.com/@xxx) لتظهر مسلسلاتها بقسم مستقل بعد مسلسلات هم العربية. اتركه فارغاً للافتراضي."
                dialogTitle = "قنوات يوتيوب إضافية"
                dialogMessage = "ألصق رابط قناة أو معرّفها (UC… أو @handle) — رابطٌ واحد في كل سطر"
                setOnPreferenceChangeListener { _, newVal ->
                    (newVal as? String)?.isNotBlank() == false || newVal != null
                }
            }
            preferenceScreen.addPreference(extraChannelPref)

            // زر إعادة الضبط الافتراضي.
            val resetPref = Preference(ctx).apply {
                key = "hum_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد كل الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_PLAYBACK_MODE); remove(KEY_MAX_QUALITY); remove(KEY_QUALITY_ORDER)
                        remove(KEY_EXTRA_CHANNELS)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}