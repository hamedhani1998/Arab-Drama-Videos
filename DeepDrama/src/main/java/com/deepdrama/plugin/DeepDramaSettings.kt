package com.deepdrama.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * إعدادات مصدر «Deep Drama». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [DeepDramaProvider] مباشرة
 * عند كل بث — بلا تخزين مركزي. الربط الصريح `preferenceManager.sharedPreferences
 * = prefs` هو ما يضمن كتابة الاختيار في ذاك الملف بالذات (وإلا لذهب إلى ملف
 * التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: «الافتراضي (كما هو)» لا يعيد ترتيب شيئاً،
 * وإظهار الترجمة مفعّل كما هو.
 */
class DeepDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره DeepDramaPlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "DeepDrama"

        // مفاتيح هذه الوحدة (سلسلة نصية فريدة—no تعارض مع أي وحدة أخرى).
        const val KEY_QUALITY_ORDER = "dd_quality_order"        // "default" | "asc" | "desc"
        const val KEY_SHOW_SUBTITLES = "dd_show_subtitles"     // Boolean — الافتراضي true

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DeepDramaSettingsBottomSheet(prefs).show(fm, "dd_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر
            // (الاسم نفسه، والنمط الافتراضي MODE_PRIVATE يطابق ما يمرّره المكوّن).
            // بدونه تُحفظ الاختيارات في الملف الافتراضي وتضيع بصمت.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // ترتيب الجودات عند البث — يعيد ترتيب روابط الخادم (master + الجودات
            // الفردية) بلا حذف أو تكرار؛ المتساوية تحتفظ بترتيبها، والافتراضي
            // = نفس ترتيب اليوم حرفياً.
            val qualityOrderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "افتراضي: نفس ترتيب روابط الموقع كما هو"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(qualityOrderPref)

            // إظهار الترجمة — الافتراضي مفعّل، أي سلوك اليوم حرفياً.
            val showSubsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBTITLES
                title = "إظهار الترجمة"
                summary = "يعرض ترجمات كل لغة يوفّرها الخادم"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showSubsPref)

            val resetPref = Preference(ctx).apply {
                key = "dd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_SUBTITLES)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
