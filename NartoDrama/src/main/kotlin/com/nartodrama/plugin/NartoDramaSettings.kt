package com.nartodrama.plugin

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
 * إعدادات مصدر «Narto Drama». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [NartoDramaProvider] مباشرة
 * عند كل بث — بلا تخزين مركزي. الربط عبر `preferenceManager
 * .setSharedPreferencesName(PREFS_NAME)` هو ما يضمن كتابة الاختيار في ذاك الملف
 * بالذات (وإلا لذهب إلى ملف التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: «الافتراضي (كما هو)» لا يعيد ترتيب شيئاً
 * (فلا حذف ولا تكرار ولا تغيير في عدد الروابط)، وإظهار رابط «كامل» مفعّل
 * كما هو اليوم حرفياً.
 */
class NartoDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره NartoDramaPlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "NartoDrama"

        // مفاتيح هذه الوحدة (بادئة ntd_ — لا تعارض مع أي وحدة أخرى في العملية).
        const val KEY_QUALITY_ORDER = "ntd_quality_order"   // "default" | "asc" | "desc"
        const val KEY_SHOW_FULL = "ntd_show_full"           // Boolean — الافتراضي true

        // إظهار/إخفاء صفوف الواجهة الرئيسية (ntd_row_<slug>، الافتراضي ظاهر).
        const val KEY_ROW_SUGGEST = "ntd_row_suggest"       // «الاقتراحات العربية»
        const val KEY_ROW_DRAMA = "ntd_row_drama"           // «دراما»
        const val KEY_ROW_DUBBED = "ntd_row_dubbed"         // «مدبلج»
        const val KEY_ROW_ROMANCE = "ntd_row_romance"       // «رومانسي»
        const val KEY_ROW_ACTION = "ntd_row_action"         // «أكشن»

        // الصفوف كما تُبنى في المصدر: (مفتاح التفضيلات، اسم الصف).
        // يجب أن تطابق هذه القائمة ترتيب Express في NartoDramaProvider.visibleRows().
        val ROWS: List<Pair<String, String>> = listOf(
            KEY_ROW_SUGGEST to "الاقتراحات العربية",
            KEY_ROW_DRAMA to "🎬 دراما",
            KEY_ROW_DUBBED to "🎙️ مدبلج",
            KEY_ROW_ROMANCE to "💕 رومانسي",
            KEY_ROW_ACTION to "⚔️ أكشن",
        )

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            NartoDramaSettingsBottomSheet(prefs).show(fm, "ntd_settings")
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

            // ترتيب الجودات عند البث — يعيد ترتيب روابط الحلقة (رابط «كامل» +
            // جودات multi_resolutions) بلا حذف أو تكرار؛ المتساوية تحتفظ بترتيبها
            // (فرز مستقر)، والافتراضي = نفس ترتيب اليوم حرفياً.
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

            // إظهار رابط «كامل» — الافتراضي مفعّل، أي سلوك اليوم حرفياً. عند إطفائه
            // تختفي روابط «كامل» فقط وتبقى روابط الجودات (1080/720/480) كما هي.
            val showFullPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_FULL
                title = "إظهار رابط «كامل» بالوكيل"
                summary = "يعرض رابط الملف الكامل إضافة إلى روابط الجودات المنفصلة"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showFullPref)

            // — إظهار/إخفاء صفوف الواجهة الرئيسية —
            // الصفوف تُبنى بهذا الترتيب نفسه في المصدر؛ إخفاء صفّ يمنع جلبه أصلاً
            // (لا بطاقات ولا شبكة)، فتُسرَّع الواجهة بصفوف أقل. إن أخفى المستخدم
            // الصفوف كلها نُبقي «الاقتراحات العربية» كي لا تفرغ الواجهة الرئيسية.
            val rowsCategory = PreferenceCategory(ctx)
            rowsCategory.title = "صفوف الواجهة الرئيسية"
            rowsCategory.summary = "أزل وسم أي صفّ لإخفائه من الواجهة الرئيسية"
            preferenceScreen.addPreference(rowsCategory)

            for ((rowKey, rowLabel) in ROWS) {
                val p = SwitchPreferenceCompat(ctx).apply {
                    key = rowKey
                    title = rowLabel
                    summary = "يعرض صفّ «$rowLabel» في الواجهة الرئيسية"
                    setDefaultValue(true)
                }
                rowsCategory.addPreference(p)
            }

            val resetPref = Preference(ctx).apply {
                key = "ntd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_FULL)
                        ROWS.forEach { (k, _) -> remove(k) }
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
