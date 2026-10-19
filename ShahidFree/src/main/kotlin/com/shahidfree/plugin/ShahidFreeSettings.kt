package com.shahidfree.plugin

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
 * إعدادات مصدر «ShahidFree». تُعرض من زر الإعدادات في CloudStream، وتُحفظ في
 * ملف تفضيلات خاص بالوحدة (تنميط مثل DramaVideoShow).
 *
 * الخيارات هنا خاصة بالإضافة لا عامة: الموقع بلا حلقات — كل مسلسلٍ قصيرٍ
 * «حلقة» واحدة بعدة سيرفرات معكوسة لنفس الفيديو، والمشغّل الوحيد الذي يفتح في
 * التطبيق هو Vidhold (بثّ HLS موقّع)، فلا معنى لإعداد «عدد مصادر التشغيل».
 * ولا توجد على الموقع أية ملفات ترجمة يمكن الوصول إليها إطلاقاً (لا في صفحة
 * ووردبريس ولا داخل Vidhold)، فلا يُعرَض إعداد «الترجمة». ما ينفع المستخدم:
 * إظهار الواجهة أو إخفاؤها، عدد أقسامها (زمن أول رسم)، ووسم الصيغة في اسم
 * السيرفر.
 */
class ShahidFreeSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        /** اسم ملف التفضيلات — يطابق ما يمرّره [ShahidFreePlugin]. */
        const val PREFS_NAME = "ShahidFree"

        // مفاتيح هذه الوحدة (بادئة فريدة — لا تعارض مع أي وحدة أخرى).
        const val KEY_SHOW_HOME = "shf_show_home"     // Boolean — إظهار الواجهة الرئيسية
        const val KEY_HOME_ROWS = "shf_home_rows"     // "all" | "2" | "1" — عدد أقسام الرئيسية
        const val KEY_FORMAT_TAG = "shf_format_tag"   // Boolean — وسم الصيغة في اسم السيرفر

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ShahidFreeSettings(prefs).show(fm, "shf_settings")
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

            // إظهار الصفحة الرئيسية (أقسام «الأحدث» والتصنيفات). `hasMainPage`
            // خاصية ديناميكية فيُسحَب المصدر من الصفحة الرئيسية أو يُخفى فوراً.
            val showHomePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_HOME
                title = "إظهار الواجهة الرئيسية"
                summary = "أقسام الموقع الرئيسية (الأحدث، المدبلج، المترجم)"
                setDefaultValue(true)
            }
            homeCategory.addPreference(showHomePref)

            // عدد أقسام الرئيسية المعروضة — كل قسم صفٌّ في الواجهة، والواجهة
            // تنتظر كل الصفوف قبل أول رسم، فتقليل العدد يسرّع فتح المصدر.
            // الموقع يُبقي ثلاثة أقسام فقط (الأحدث/المدبلج/المترجم).
            val homeRowsPref = ListPreference(ctx).apply {
                key = KEY_HOME_ROWS
                title = "عدد أقسام الرئيسية"
                summary = "كل قِسْم صفٌّ ينتظره أول رسم — الأقل أسرع"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "2", "1")
                entries = arrayOf(
                    "كل الأقسام (الافتراضي)",
                    "قسمان (أسرع)",
                    "قسم واحد (الأسرع)"
                )
                setDefaultValue("all")
            }
            homeCategory.addPreference(homeRowsPref)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            // وسم الصيغة في اسم سيرفر التشغيل، ليُميّز المستخدم بين M3U8 وMP4
            // قبل التشغيل (المشغّل يعرض ExtractorLink.name فقط في قائمة السيرفرات).
            val formatTagPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_FORMAT_TAG
                title = "إظهار صيغة السيرفر في الاسم"
                summary = "يُضيف [M3U8] أو [MP4] بجانب الجودة في اسم الخادم"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(formatTagPref)

            val resetPref = Preference(ctx).apply {
                key = "shf_reset_prefs"
                title = "إعادة الضبط"
                summary = "يعيد الخيارات أعلاه إلى الافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_HOME)
                        remove(KEY_HOME_ROWS)
                        remove(KEY_FORMAT_TAG)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}