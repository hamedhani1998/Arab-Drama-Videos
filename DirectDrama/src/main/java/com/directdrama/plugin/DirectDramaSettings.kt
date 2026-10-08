package com.directdrama.plugin

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
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «DirectDrama». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [DirectDramaProvider]
 * مباشرة عند كل بث — بلا تخزين مركزي. الربط عبر `preferenceManager
 * .setSharedPreferencesName(PREFS_NAME)` هو ما يضمن كتابة الاختيار في ذاك
 * الملف بالذات (وإلا لذهب إلى ملف التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: الحلقات بترتيبها، والترجمة مُظهَرة.
 */
class DirectDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره DirectDramaPlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "DirectDrama"

        // مفاتيح هذه الوحدة (بادئة فريدة — لا تعارض مع أي وحدة أخرى؛
        // «dd_» محجوزة لـDeepDrama فأخذنا «ddr_»).
        const val KEY_EPISODE_ORDER = "ddr_episode_order"     // "as_is" | "desc"
        const val KEY_SHOW_SUBTITLES = "ddr_show_subtitles"   // Boolean — الافتراضي true
        const val KEY_SHOW_HOME = "ddr_show_home"             // Boolean — الواجهة الرئيسية كلها
        const val KEY_SHOW_FRONT = "ddr_show_front"           // Boolean — صفّا الواجهة الأمامية
        const val KEY_SHOW_PLATFORMS = "ddr_show_platforms"   // Boolean — صفوف المنصات الـ27
        const val KEY_HOME_ROWS = "ddr_home_rows"             // "all" | "10" | "18" — عدد صفوف المنصات
        const val KEY_HIDDEN_ROWS = "ddr_hidden_rows"         // Set<String> — مفاتيح الأقسام المخفية

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DirectDramaSettingsBottomSheet(prefs).show(fm, "ddr_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر
            // (الاسم نفسه، والنمط الافتراضي MODE_PRIVATE يطابق ما يمرّره المكوّن).
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            // ── الواجهة الرئيسية ────────────────────────────────────────────
            // كل صفٍّ هنا صفحة HTML كاملة (~438 كيلوبايت، 3–10 ثوانٍ للخادم)
            // والتطبيق ينتظر **كل** الصفوف قبل رسم الصفحة الأولى، فعدد الصفوف
            // هو مفتاح سرعة الواجهة مباشرة: صفّا الواجهة الأمامية (2) + المنصات (27).
            val homeCategory = PreferenceCategory(ctx)
            homeCategory.title = "الواجهة الرئيسية"
            preferenceScreen.addPreference(homeCategory)

            // إظهار/إخفاء الصفحة الرئيسية كاملة — يقرأها `hasMainPage` ديناميكياً.
            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "إيقافها تُخفي كل أقسام المصدر من CloudStream"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showHomePref)

            // ★ أقسام الواجهة الأمامية — صفّان **قبل** صفوف المنصات (طلب المستخدم
            //   2026-10-08 «بالمقدَّمة قبل المنصات»): «الأكثر رواجًا» من
            //   `/ar/popular` و«الأحدث إضافة» من `/ar/new-releases`.
            val showFrontPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_FRONT
                title = "إظهار قسم الواجهة الأمامية"
                summary = "صفّا «الأكثر رواجًا» و«الأحدث إضافة» قبل المنصات"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showFrontPref)

            val showPlatformsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PLATFORMS
                title = "إظهار قوائم المنصات"
                summary = "إيقافها يُبقي أقسام الواجهة الأمامية وحدها"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showPlatformsPref)

            val homeRowsPref = ListPreference(ctx).apply {
                key = KEY_HOME_ROWS
                title = "عدد صفوف المنصات"
                summary = "افتراضي: 18 صفّاً — الواجهة تنتظر كل الصفوف قبل الرسم"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("18", "10", "all")
                entries = arrayOf(
                    "18 صفّاً (الافتراضي)",
                    "10 صفوف (الأسرع)",
                    "كل الصفوف (27)"
                )
                setDefaultValue("18")
            }
            homeCategory.addPreference(homeRowsPref)

            // ★ التحكم المقطَّع: كل قسم على حدة. القيم = مفاتيح `MainPageData.data`
            //   نفسها: صفّا الواجهة الأمامية (`DDR_FRONT_ROWS`) ثم منصات الـ27.
            //   دلالة الاختيار مقلوبة عمداً: المؤشَّر = مُخفى، وبلا اختيار يظهر
            //   كل شيء — وإلا لاضطرّ المستخدم لتأشير 28 قسماً لإخفاء واحد.
            //   القائمة تُبنى من `DDR_FRONT_ROWS + DDR_PLATFORM_ROWS`
            //   (مصدر واحد للحقيقة مع `mainPage`).
            val hiddenRowsPref = MultiSelectListPreference(ctx).apply {
                key = KEY_HIDDEN_ROWS
                title = "إخفاء أقسام محدَّدة"
                summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> {
                    val n = it.values?.size ?: 0
                    if (n == 0) "بلا اختيار: كل الأقسام ظاهرة" else "$n قسماً مُخفياً عن الواجهة"
                }
                entryValues = (DDR_FRONT_ROWS + DDR_PLATFORM_ROWS).map { it.first }.toTypedArray()
                entries = (DDR_FRONT_ROWS + DDR_PLATFORM_ROWS).map { it.second }.toTypedArray()
            }
            homeCategory.addPreference(hiddenRowsPref)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            val episodeOrderPref = ListPreference(ctx).apply {
                key = KEY_EPISODE_ORDER
                title = "ترتيب الحلقات"
                summary = "افتراضي: بترتيب الحلقات كما هي على الموقع"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("as_is", "desc")
                entries = arrayOf(
                    "الافتراضي (من الحلقة 1)",
                    "من الأحدث إلى الأقدم"
                )
                setDefaultValue("as_is")
            }
            playbackCategory.addPreference(episodeOrderPref)

            // إظهار الترجمة — الافتراضي مفعّل، أي سلوك اليوم حرفياً.
            val showSubsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SUBTITLES
                title = "إظهار الترجمة"
                summary = "يعرض كل ملفات الترجمة التي يعيدها خادم البث"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(showSubsPref)

            val resetPref = Preference(ctx).apply {
                key = "ddr_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_EPISODE_ORDER)
                        remove(KEY_SHOW_SUBTITLES)
                        remove(KEY_SHOW_HOME)
                        remove(KEY_SHOW_FRONT)
                        remove(KEY_SHOW_PLATFORMS)
                        remove(KEY_HOME_ROWS)
                        remove(KEY_HIDDEN_ROWS)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
