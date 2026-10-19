package com.arabshortdrama.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «ArabShortDrama». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [ArabShortDramaProvider]
 * مباشرة عند كل بث — بلا تخزين مركزي. الربط عبر
 * `preferenceManager.setSharedPreferencesName(PREFS_NAME)` يضمن أن تكتب الورقة في
 * ذاك الملف بالذات (وإلا لذهب الاختيار إلى الملف الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً.
 */
class ArabShortDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره ArabShortDramaPlugin للمصدر.
        const val PREFS_NAME = "ArabShortDrama"

        const val KEY_QUALITY_ORDER = "asd_quality_order"   // "default" | "asc" | "desc"
        const val KEY_SHOW_DEAD = "asd_show_dead"            // Boolean الافتراضي true
        const val KEY_PREFER_YOUTUBE = "asd_prefer_youtube"  // Boolean الافتراضي true

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ArabShortDramaSettingsBottomSheet(prefs).show(fm, "asd_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر
            // (الاسم نفسه، والنمط الافتراضي MODE_PRIVATE يطابق ما يمرّره المكوّن).
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // ترتيب الجودات عند البث — الافتراضي = ترتيب روابط المصدر تماماً؛
            // التصاعدي/التنازلي يعيدان ترتيب الروابط فقط بلا حذف أو تكرار.
            playbackCategory.addPreference(ListPreference(ctx).apply {
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
            })

            // روابط يوتيوب تُستخرج كلها بجوداتها؛ روابط ديلي موشن تصل بجودة واحدة.
            playbackCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_PREFER_YOUTUBE
                title = "تفضيل يوتيوب"
                summary = "عند توفّر رابط يوتيوب إلى جانب ديلي موشن، اعرضه أولاً (الافتراضي)"
                setDefaultValue(true)
            })

            val listCategory = PreferenceCategory(ctx)
            listCategory.title = "خيارات القائمة"
            preferenceScreen.addPreference(listCategory)

            // التحقّق من التوفّع يحتاج طلبات شبكة، وفيها تُوسم الدراما المعطّلة.
            // الافتراضي = إظهار الكل (بلا وسم)، والإطفاء يُخفي الموسوم فقط.
            listCategory.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_DEAD
                title = "الدراما غير المتاحة"
                summary = "إظهار الدراما التي تعذّر التحقّق من روابطها"
                setDefaultValue(true)
            })

            val resetPref = Preference(ctx).apply {
                key = "asd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_DEAD)
                        remove(KEY_PREFER_YOUTUBE)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
