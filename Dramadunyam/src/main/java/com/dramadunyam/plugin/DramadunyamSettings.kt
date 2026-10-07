package com.dramadunyam.plugin

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
 * إعدادات مصدر «Dramadunyam». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يقرأه [DramadunyamProvider]
 * مباشرة عند كل بث — بلا تخزين مركزي. الربط عبر `preferenceManager
 * .setSharedPreferencesName(PREFS_NAME)` هو ما يضمن كتابة الاختيار في ذاك
 * الملف بالذات (وإلا لذهب إلى ملف التفضيلات الافتراضي الذي لا يقرأه المصدر).
 *
 * الافتراضيات = سلوك اليوم تماماً: الحلقات بترتيبها، والترجمة مُظهَرة، والبحث
 * بالعربية (لأن الموقع لا يُعيد العناوين العربية بلا `lang=ar`).
 */
class DramadunyamSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        // ★ اسم ملف التفضيلات — يجب أن يطابق ما يمرّره DramadunyamPlugin للمصدر،
        //   وإلا ذهبت الاختيارات إلى ملف آخر لا يقرأه (سلوك صامت بلا أثر).
        const val PREFS_NAME = "Dramadunyam"

        // مفاتيح هذه الوحدة (بادئة فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_EPISODE_ORDER = "dun_episode_order"      // "as_is" | "desc"
        const val KEY_SHOW_SUBTITLES = "dun_show_subtitles"    // Boolean — الافتراضي true
        const val KEY_SEARCH_SCOPE = "dun_search_scope"        // "ar" | "orig" | "both"
        const val KEY_SHOW_FRONT = "dun_show_front"            // Boolean — القسم الأمامي
        const val KEY_SHOW_HOME = "dun_show_home"              // Boolean — الواجهة الرئيسية كلها
        const val KEY_SHOW_PLATFORMS = "dun_show_platforms"    // Boolean — صفوف المنصات
        const val KEY_HOME_ROWS = "dun_home_rows"              // "all" | "20" | "10" — عدد صفوف المنصات

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramadunyamSettingsBottomSheet(prefs).show(fm, "dun_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // ★ الربط الحاسم: اجعل الورقة تكتب في نفس الملف الذي يقرأه المصدر
            // (الاسم نفسه، والنمط الافتراضي MODE_PRIVATE يطابق ما يمرّره المكوّن).
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val frontCategory = PreferenceCategory(ctx)
            frontCategory.title = "الواجهة الرئيسية"
            preferenceScreen.addPreference(frontCategory)

            // ★ المفتاح الأم: إخفاء الواجهة الرئيسية كلها. `hasMainPage` خاصية
            //   ديناميكية فيُسحَب المصدر من الصفحة الرئيسية فوراً عند الإيقاف.
            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "إيقافها يُخفي الصفحة الرئيسية للمصدر كليّاً"
                setDefaultValue(true)
            }
            frontCategory.addPreference(showHomePref)

            // إظهار/إخفاء القسم الأمامي — صفّا «الأحدث» و«الأكثر مشاهدة» فوق
            // صفوف المنصات. الافتراضي مفعّل؛ إلغاؤه يُسقط الصفّين عند الرسم
            // لأن `mainPage` خاصية ديناميكية تقرأ هذا المفتاح في كل رسم.
            val showFrontPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_FRONT
                title = "إظهار القسم الأمامي"
                summary = "الأحدث والأكثر مشاهدة فوق قوائم المنصات"
                setDefaultValue(true)
            }
            frontCategory.addPreference(showFrontPref)

            // صفوف المنصات (43 طلباً موزّعة على بوابة تزامن) — إخفاؤها يترك
            // الواجهة قسمين خفيفين فقط (API واحد لكلٍّ منهما).
            val showPlatformsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PLATFORMS
                title = "إظهار قوائم المنصات"
                summary = "43 صفّاً لكل منصة — إيقافها يُخفّف الواجهة كثيراً"
                setDefaultValue(true)
            }
            frontCategory.addPreference(showPlatformsPref)

            // عدد صفوف المنصات المعروضة — كل صف صفحة طلبٍ، فتقليل العدد
            // يقصّ زمن فتح الواجهة الرئيسية مباشرة.
            val homeRowsPref = ListPreference(ctx).apply {
                key = KEY_HOME_ROWS
                title = "عدد صفوف المنصات"
                summary = "افتراضي: كل الصفوف (43)"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "20", "10")
                entries = arrayOf(
                    "كل الصفوف (43)",
                    "20 صفّاً",
                    "10 صفوف"
                )
                setDefaultValue("all")
            }
            frontCategory.addPreference(homeRowsPref)

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

            val searchCategory = PreferenceCategory(ctx)
            searchCategory.title = "خيارات البحث"
            preferenceScreen.addPreference(searchCategory)

            // ★ سياق البحث حاسم هنا: `lang=ar` وحده هو ما يُعيد العناوين العربية
            //   (استُقيس: «من الكراهية إلى الحب» تعطي 0 بلا `lang=ar` و1 معه)،
            //   أما بلاه فتعطي العناوين الأصلية التركية.
            val searchScopePref = ListPreference(ctx).apply {
                key = KEY_SEARCH_SCOPE
                title = "سياق البحث"
                summary = "افتراضي: العناوين العربية"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("ar", "orig", "both")
                entries = arrayOf(
                    "العربية (lang=ar)",
                    "العناوين الأصلية",
                    "كلاهما"
                )
                setDefaultValue("ar")
            }
            searchCategory.addPreference(searchScopePref)

            val resetPref = Preference(ctx).apply {
                key = "dun_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_EPISODE_ORDER)
                        remove(KEY_SHOW_SUBTITLES)
                        remove(KEY_SEARCH_SCOPE)
                        remove(KEY_SHOW_FRONT)
                        remove(KEY_SHOW_HOME)
                        remove(KEY_SHOW_PLATFORMS)
                        remove(KEY_HOME_ROWS)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
